package com.tamaqai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.R
import com.tamaqai.ui.components.*
import java.io.File
import kotlinx.coroutines.*

@Composable fun ScannerScreen(scannerViewModel: ScannerViewModel, onClose: () -> Unit, onGallery: () -> Unit,
    onPermissionChanged: (Boolean) -> Unit = {}, barcode: Boolean = false, onBarcode: (String) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    val state by scannerViewModel.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var barcodeBusy by remember { mutableStateOf(false) }
    var barcodeMissing by remember { mutableStateOf(false) }
    // Also update navigation when the permission result arrives without an Activity recreation.
    LaunchedEffect(permission) { onPermissionChanged(permission) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize()) {
        if (permission) CameraPreview(onCapture = { controller ->
            if(barcode) barcodeBusy=true else scannerViewModel.beginCapture()
            val file = File(context.cacheDir, "tamaq_capture_" + System.nanoTime() + ".jpg")
            try {
                controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            if(!barcode) scannerViewModel.analyzePhotoFile(file)
                            else scope.launch {
                                try {
                                    val code=withContext(Dispatchers.Default) {
                                        val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                                        android.graphics.BitmapFactory.decodeFile(file.path,bounds)
                                        var sample=1
                                        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048) sample*=2
                                        val bitmap=android.graphics.BitmapFactory.decodeFile(file.path,
                                            android.graphics.BitmapFactory.Options().apply {inSampleSize=sample}) ?: return@withContext null
                                        try {
                                            val pixels=IntArray(bitmap.width*bitmap.height)
                                            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                                            com.tamaqai.domain.usecase.ProductBarcode.decode(pixels,bitmap.width,bitmap.height)
                                        } finally {bitmap.recycle()}
                                    }
                                    if(code==null) barcodeMissing=true
                                    else withContext(Dispatchers.Main.immediate) {onBarcode(code)}
                                } catch(cancel:CancellationException) {throw cancel}
                                catch(_:Exception) {barcodeMissing=true}
                                finally {file.delete();barcodeBusy=false}
                            }.invokeOnCompletion {file.delete()}
                        }
                        override fun onError(exception: ImageCaptureException) { file.delete(); barcodeBusy=false; scannerViewModel.onCaptureFailed() }
                    })
            } catch (_: RuntimeException) { file.delete(); barcodeBusy=false; scannerViewModel.onCaptureFailed() }
        }, captureEnabled = state is ScannerState.Idle && !barcodeBusy, onCameraError = scannerViewModel::onCaptureFailed,
            onClose = onClose, onGallery = onGallery, barcode=barcode)
        else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            WellnessTextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            Spacer(Modifier.weight(1f))
            EmptyState(stringResource(R.string.camera_permission_title), stringResource(R.string.camera_permission_body))
            PrimaryButton(stringResource(R.string.allow_camera), { launcher.launch(Manifest.permission.CAMERA) })
            WellnessOutlinedButton(onClick = onGallery, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if(barcode) R.string.catalog_open_search else R.string.gallery)) }
            Spacer(Modifier.weight(1f))
        }
        if (state is ScannerState.Loading || barcodeBusy) Box(Modifier.fillMaxSize().background(com.tamaqai.ui.theme.CameraSurface), contentAlignment = Alignment.Center) {
            TamaqCard { WellnessLoadingIndicator(); Text(stringResource(R.string.preparing_photo)) }
        }
        if(barcodeMissing) WellnessDialog(onDismissRequest={barcodeMissing=false},
            title={Text(stringResource(R.string.catalog_barcode_title))},text={Text(stringResource(R.string.catalog_barcode_missing))},
            confirmButton={WellnessTextButton({barcodeMissing=false;onBarcode("")}) {Text(stringResource(R.string.catalog_open_search))}},
            dismissButton={WellnessTextButton({barcodeMissing=false}) {Text(stringResource(R.string.retry))}})
        (state as? ScannerState.Error)?.let { error ->
            WellnessDialog(onDismissRequest = scannerViewModel::dismissError, title = { Text(stringResource(R.string.error_title)) },
                text = { Text(messageText(error.message)) }, confirmButton = {
                    WellnessTextButton(onClick = scannerViewModel::dismissError) { Text(stringResource(R.string.retry)) }
                })
        }
    }
}
