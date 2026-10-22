package com.tamaqai.ui.screens

import androidx.camera.core.ImageCapture
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.tamaqai.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tamaqai.R

@Composable fun CameraPreview(onCapture: (LifecycleCameraController) -> Unit, captureEnabled: Boolean,
    modifier: Modifier = Modifier, onCameraError: () -> Unit = {}, onClose: () -> Unit = {}, onGallery: () -> Unit = {},
    barcode: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val controller = remember(context) { LifecycleCameraController(context).apply { setEnabledUseCases(CameraController.IMAGE_CAPTURE) } }
    var flashAvailable by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    DisposableEffect(controller, lifecycle) {
        try {
            controller.bindToLifecycle(lifecycle)
            controller.initializationFuture.addListener({
                flashAvailable = controller.cameraInfo?.hasFlashUnit() == true
            }, ContextCompat.getMainExecutor(context))
        } catch (_: RuntimeException) { onCameraError() }
        onDispose { controller.unbind() }
    }
    Box(modifier.fillMaxSize().background(CameraSurface)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; this.controller = controller } },
            update = { it.controller = controller })
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(CameraSurface.copy(alpha=.94f)).statusBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = onClose,modifier=Modifier.size(WellnessSpace.touch), colors = cameraControlColors()) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
            Surface(modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                color = CameraSurface, shape = RoundedCornerShape(20.dp)) {
                Text(stringResource(if(barcode) R.string.catalog_barcode_title else R.string.camera_title), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = CameraContent, style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (flashAvailable) FilledIconButton(onClick = {
                flash = !flash
                controller.imageCaptureFlashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            }, modifier=Modifier.size(WellnessSpace.touch), enabled = captureEnabled, colors = cameraControlColors()) { Icon(if (flash) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                stringResource(if (flash) R.string.flash_off else R.string.flash_on)) }
            else Spacer(Modifier.size(48.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(CameraSurface.copy(alpha=.96f))
            .navigationBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(color = CameraSurface, shape = RoundedCornerShape(20.dp)) {
                Text(stringResource(if(barcode) R.string.catalog_barcode_hint else R.string.camera_hint), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = CameraContent, style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = onGallery,modifier=Modifier.size(WellnessSpace.touch), enabled = captureEnabled, colors = cameraControlColors()) {
                    Icon(if(barcode) Icons.Outlined.Search else Icons.Outlined.PhotoLibrary,
                        stringResource(if(barcode) R.string.catalog_open_search else R.string.gallery)) }
                val description = stringResource(R.string.capture)
                Box(Modifier.size(80.dp).border(3.dp, CameraContent, CircleShape).padding(7.dp)
                    .clip(CircleShape).background(if (captureEnabled) CameraContent else DisabledSurface)
                    .clickable(enabled = captureEnabled) { onCapture(controller) }.semantics { contentDescription = description })
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable private fun cameraControlColors() = IconButtonDefaults.filledIconButtonColors(
    containerColor = CameraContent, contentColor = CameraSurface,
    disabledContainerColor = CameraContent.copy(alpha=.8f), disabledContentColor = CameraSurface)
