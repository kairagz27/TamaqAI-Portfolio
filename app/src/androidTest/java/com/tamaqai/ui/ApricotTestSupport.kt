package com.tamaqai.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import com.tamaqai.BuildConfig
import java.io.File

/** Only the separate synthetic UI package may write or capture fixtures. */
internal fun requireApricotQa() = check(BuildConfig.APPLICATION_ID == "com.tamaqai.visual")

/** 360 dp content without changing the phone's global display/font settings. */
@Composable
internal fun ApricotViewport(fontScale: Float = 1f, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val qaDensity = constraints.maxWidth / 360f
        val qaConfiguration = Configuration(configuration).apply {
            screenWidthDp = 360
            screenHeightDp = (constraints.maxHeight / qaDensity).toInt()
            densityDpi = (qaDensity * 160).toInt()
            this.fontScale = fontScale
        }
        CompositionLocalProvider(
            LocalDensity provides Density(qaDensity, fontScale),
            LocalConfiguration provides qaConfiguration,
        ) {
            Box(Modifier.fillMaxSize().testTag("apricot-viewport")) { content() }
        }
    }
}

/** Semantic actions work without HyperOS granting adb gesture injection. */
internal fun SemanticsNodeInteraction.apricotClick() =
    performSemanticsAction(SemanticsActions.OnClick) { check(it()) }

/** Capture only Compose UI, never the status bar or another phone application. */
internal fun SemanticsNodeInteraction.apricotShot(filesDir: File, name: String) {
    requireApricotQa()
    require(name.matches(Regex("[a-z0-9-]+")))
    val directory = File(filesDir, "apricot_screenshots").apply { mkdirs() }
    val bitmap = captureToImage().asAndroidBitmap()
    File(directory, "$name.png").outputStream().use {
        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
    }
}
