package com.tamaqai.domain

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.tamaqai.domain.usecase.ProductBarcode
import org.junit.Assert.*
import org.junit.Test

class ProductBarcodeTest {
    private fun fixture(code: String, format: BarcodeFormat = BarcodeFormat.EAN_13): Triple<IntArray, Int, Int> {
        val bits = MultiFormatWriter().encode(code, format, 640, 240)
        val pixels = IntArray(bits.width * bits.height) { i ->
            if (bits[i % bits.width, i / bits.width]) 0xff000000.toInt() else -1
        }
        return Triple(pixels, bits.width, bits.height)
    }

    @Test fun decodesLocalGtinAndRejectsNonBarcodeImage() {
        // Official EAN example, a decoder fixture, never a seeded food record.
        val (pixels, width, height) = fixture("5901234123457")
        assertEquals("5901234123457", ProductBarcode.decode(pixels, width, height))
        assertNull(ProductBarcode.decode(IntArray(128*128){-1},128,128))
    }

    @Test fun decodesEanAtEveryQuarterTurnWithoutMutatingTheCapturedPixels() {
        val (pixels, width, height) = fixture("5901234123457")
        val original = pixels.copyOf()
        // Rotate independently of the production implementation: map each
        // output position back to the original image for all EXIF rotations.
        for (turns in 0..3) {
            val outputWidth = if (turns % 2 == 0) width else height
            val outputHeight = if (turns % 2 == 0) height else width
            val rotated = IntArray(pixels.size) { index ->
                val x = index % outputWidth
                val y = index / outputWidth
                when (turns) {
                    0 -> pixels[y * width + x]
                    1 -> pixels[(height - 1 - x) * width + y]
                    2 -> pixels[(height - 1 - y) * width + width - 1 - x]
                    else -> pixels[x * width + width - 1 - y]
                }
            }
            val before = rotated.copyOf()
            assertEquals("rotation $turns", "5901234123457", ProductBarcode.decode(rotated, outputWidth, outputHeight))
            assertArrayEquals("caller pixels must remain unchanged", before, rotated)
        }
        assertArrayEquals(original, pixels)
    }

    @Test fun decodesRotatedEanEightAndKeepsNoBarcodeOutcomeBounded() {
        val (pixels, width, height) = fixture("96385074", BarcodeFormat.EAN_8)
        val rotated = IntArray(pixels.size) { index ->
            val x = index % height
            val y = index / height
            pixels[(height - 1 - x) * width + y]
        }
        assertEquals("96385074", ProductBarcode.decode(rotated, height, width))
        val nonBarcode = IntArray(97 * 181) { index ->
            if (index % 97 < 48) 0xff666666.toInt() else 0xffaaaaaa.toInt()
        }
        assertNull(ProductBarcode.decode(nonBarcode, 97, 181))
    }

    @Test fun rejectsInvalidImageBoundsBeforeAllocatingOrDecoding() {
        listOf(Triple(IntArray(1), 0, 1), Triple(IntArray(1), 1, 4097), Triple(IntArray(3), 2, 2)).forEach {
            (pixels, width, height) ->
            assertThrows(IllegalArgumentException::class.java) { ProductBarcode.decode(pixels, width, height) }
        }
    }
}
