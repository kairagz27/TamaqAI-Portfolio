package com.tamaqai.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

internal object PhotoProcessor {
    fun downsampleToJpeg(photoFile: File): ByteArray {
        require(photoFile.length() in 1..20L * 1024 * 1024) { "ui_error_046" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 50_000_000) { "ui_error_047" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decodedBitmap = BitmapFactory.decodeFile(photoFile.absolutePath, options)
            ?: throw IOException("The captured photo could not be decoded.")

        var bitmapToCompress = decodedBitmap
        try {
            val exif = ExifInterface(photoFile)
            val rotationDegrees = exif.rotationDegrees
            val matrix = Matrix().apply {
                if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
                if (exif.isFlipped) postScale(-1f, 1f)
            }

            if (rotationDegrees != 0 || exif.isFlipped) {
                bitmapToCompress = Bitmap.createBitmap(
                    decodedBitmap,
                    0,
                    0,
                    decodedBitmap.width,
                    decodedBitmap.height,
                    matrix,
                    true,
                )
                decodedBitmap.recycle()
            }

            val longest = maxOf(bitmapToCompress.width, bitmapToCompress.height)
            if (longest > 1024) {
                val previous = bitmapToCompress
                bitmapToCompress = previous.scale(
                    (previous.width * 1024.0 / longest).toInt().coerceAtLeast(1),
                    (previous.height * 1024.0 / longest).toInt().coerceAtLeast(1), true)
                if (previous !== bitmapToCompress) previous.recycle()
            }
            val jpegBytes = ByteArrayOutputStream().use { output ->
                if (!bitmapToCompress.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw IOException("The captured photo could not be encoded.")
                }
                output.toByteArray()
            }
            require(jpegBytes.size <= 2 * 1024 * 1024) { "ui_error_048" }
            return jpegBytes
        } finally {
            if (!bitmapToCompress.isRecycled) bitmapToCompress.recycle()
            if (!decodedBitmap.isRecycled) decodedBitmap.recycle()
        }
    }

    private const val JPEG_QUALITY = 85
}
