package com.tamaqai.ui.screens

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamaqai.R
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.*
import java.util.UUID

class ScannerViewModel(private val meals: MealRepository, private val photos: PhotoStore,
    private val ready: Deferred<Unit>? = null, private val text: (Int) -> String = { it.toString() }) : ViewModel() {
    private val _state = MutableStateFlow<ScannerState>(ScannerState.Idle)
    val state = _state.asStateFlow()
    private val openChannel = Channel<String>(Channel.BUFFERED)
    val openEditor = openChannel.receiveAsFlow()
    private var date = LocalDate.now()
    fun captureDate(value: LocalDate) { date = value }
    fun beginCapture() { if (_state.value == ScannerState.Idle) _state.value = ScannerState.Loading }
    fun analyzePhotoFile(photoFile: File) = viewModelScope.launch { prepare(photoFile, date) }
    fun importPhoto(uri: Uri, resolver: ContentResolver, day: LocalDate) {
        if (_state.value is ScannerState.Loading) return
        _state.value = ScannerState.Loading
        viewModelScope.launch {
            var temporary: File? = null
            try {
                temporary = withContext(Dispatchers.IO) {
                    val file = File.createTempFile("tamaq_capture_picker_", ".image")
                    try {
                        resolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var count = 0L
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                count += size
                                require(count <= 20L * 1024 * 1024)
                                output.write(buffer, 0, size)
                            }
                        } } ?: error("photo_error")
                        file
                    } catch (e: Exception) { file.delete(); throw e }
                }
                prepare(temporary, day)
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { _state.value = ScannerState.Error("photo_error") }
            finally { withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() } }
        }
    }
    private suspend fun prepare(photoFile: File, day: LocalDate) {
        _state.value = ScannerState.Loading
        var savedPhoto: String? = null
        try {
            ready?.await()
            val uid = UUID.randomUUID().toString()
            val name = withContext(Dispatchers.IO) { photos.save(uid, PhotoProcessor.downsampleToJpeg(photoFile)) }
            savedPhoto = name
            val zone = ZoneId.systemDefault()
            val timestamp = day.atTime(LocalTime.now()).atZone(zone).toInstant().toEpochMilli()
            val meal = Meal(uid = uid, nutrition = Nutrition(text(R.string.status_pending), 0, 0, 0, 0),
                timestamp = timestamp, zoneId = zone.id, status = MealStatus.PendingUpload,
                details = MealDetails(emptyList(), photoName = name, analysisId = UUID.randomUUID().toString()))
            meals.saveDraft(meal)
            savedPhoto = null
            _state.value = ScannerState.Idle
            openChannel.send(uid)
        } catch (c: CancellationException) { throw c }
        catch (_: Exception) { _state.value = ScannerState.Error("photo_error") }
        finally { withContext(NonCancellable + Dispatchers.IO) { photoFile.delete(); photos.delete(savedPhoto) } }
    }
    fun onCaptureFailed() { _state.value = ScannerState.Error("camera_error") }
    fun dismissError() { _state.value = ScannerState.Idle }
}
