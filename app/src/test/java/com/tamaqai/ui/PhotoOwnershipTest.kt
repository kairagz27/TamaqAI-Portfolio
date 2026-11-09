package com.tamaqai.ui

import androidx.lifecycle.viewModelScope
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.MealRepository
import com.tamaqai.ui.screens.ScannerViewModel
import com.tamaqai.ui.screens.ScannerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var vm: ScannerViewModel
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun teardown() { if (::vm.isInitialized) vm.viewModelScope.cancel(); Dispatchers.resetMain() }

    @Test fun successfulDraftOwnsPhotoAndOpensEditor() = runTest {
        val repo = MemoryMeals()
        val photos = PhotoStore(temporary.newFolder())
        vm = ScannerViewModel(repo, photos, ioDispatcher = StandardTestDispatcher(testScheduler), processPhoto = { it.readBytes() })
        val source = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        vm.analyzePhotoFile(source); runCurrent()
        val meal = repo.rows.single()
        assertEquals(MealStatus.PendingUpload, meal.status)
        assertNotNull(meal.details.analysisId)
        assertArrayEquals(byteArrayOf(1, 2, 3), photos.read(meal.details.photoName!!))
        assertFalse(source.exists())
        assertEquals(meal.uid, vm.openEditor.first())
        assertEquals(ScannerState.Idle, vm.state.value)
    }

    @Test fun cancellationDuringDatabaseCommitKeepsCommittedPhoto() = runTest {
        val release = CompletableDeferred<Unit>()
        val repo = MemoryMeals(afterSave = { release.await() })
        val photos = PhotoStore(temporary.newFolder())
        vm = ScannerViewModel(repo, photos, ioDispatcher = StandardTestDispatcher(testScheduler), processPhoto = { it.readBytes() })
        val source = temporary.newFile().apply { writeBytes(byteArrayOf(4, 5)) }
        val job = vm.analyzePhotoFile(source); runCurrent()
        assertEquals(1, repo.rows.size)
        job.cancel(); runCurrent()
        release.complete(Unit); runCurrent()
        assertTrue(job.isCancelled)
        assertArrayEquals(byteArrayOf(4, 5), photos.read(repo.rows.single().details.photoName!!))
        assertFalse(source.exists())
    }

    @Test fun failedDatabaseSaveCleansOnlyUnownedPhoto() = runTest {
        val repo = MemoryMeals(failSave = true)
        val folder = temporary.newFolder()
        vm = ScannerViewModel(repo, PhotoStore(folder), ioDispatcher = StandardTestDispatcher(testScheduler), processPhoto = { it.readBytes() })
        val source = temporary.newFile().apply { writeBytes(byteArrayOf(7)) }
        vm.analyzePhotoFile(source); runCurrent()
        assertTrue(repo.rows.isEmpty())
        assertTrue(folder.listFiles()!!.isEmpty())
        assertFalse(source.exists())
        assertTrue(vm.state.value is ScannerState.Error)
    }

    @Test fun cancellationBeforeReadyCreatesNoDraftAndRemovesCapture() = runTest {
        val repo = MemoryMeals()
        val folder = temporary.newFolder()
        vm = ScannerViewModel(repo, PhotoStore(folder), ready = CompletableDeferred(), ioDispatcher = StandardTestDispatcher(testScheduler), processPhoto = { error("Must not process") })
        val source = temporary.newFile()
        val job = vm.analyzePhotoFile(source); runCurrent(); job.cancel(); runCurrent()
        assertTrue(repo.rows.isEmpty())
        assertTrue(folder.listFiles()!!.isEmpty())
        assertFalse(source.exists())
    }

    private class MemoryMeals(val failSave: Boolean = false, val afterSave: suspend () -> Unit = {}) : MealRepository {
        val rows = mutableListOf<Meal>()
        override fun getAllMeals() = flowOf(rows.toList())
        override suspend fun getMeal(uid: String) = rows.find { it.uid == uid }
        override suspend fun saveDraft(meal: Meal) { check(!failSave); rows.add(meal); afterSave() }
        override suspend fun prepareEdit(uid: String) = uid
        override suspend fun confirm(meal: Meal) = error("Unexpected confirm")
        override suspend fun delete(uid: String) { rows.removeAll { it.uid == uid } }
        override suspend fun recoverInterruptedAnalyses() = Unit
    }
}
