package com.tamaqai.domain.usecase

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

/** Bounded local GTIN reader. No product identity is inferred from package appearance. */
object ProductBarcode {
    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        require(width in 1..4096 && height in 1..4096 && width.toLong()*height == pixels.size.toLong())
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E), DecodeHintType.TRY_HARDER to true)
        val reader = MultiFormatReader().apply { setHints(hints) }
        var orientedPixels = pixels
        var orientedWidth = width
        var orientedHeight = height
        // RGBLuminanceSource cannot rotate itself. Camera JPEG EXIF and the
        // user's package orientation can both leave an otherwise valid EAN
        // sideways; a bounded local pass covers all four rotations.
        repeat(4) { orientation ->
            try {
                val source = RGBLuminanceSource(orientedWidth, orientedHeight, orientedPixels)
                return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: NotFoundException) {
                // One attempt per orientation, with no asynchronous retries.
            } finally {
                reader.reset()
            }
            if (orientation < 3) {
                orientedPixels = clockwise(orientedPixels, orientedWidth, orientedHeight)
                val previousWidth = orientedWidth
                orientedWidth = orientedHeight
                orientedHeight = previousWidth
            }
        }
        return null
    }

    private fun clockwise(pixels: IntArray, width: Int, height: Int): IntArray {
        val rotated = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) rotated[x * height + height - 1 - y] = pixels[row + x]
        }
        return rotated
    }
}
