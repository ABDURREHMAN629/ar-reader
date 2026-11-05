package com.ahif.lumen.data.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

enum class ScanFilter(val label: String) {
    ORIGINAL("Original"),
    ENHANCE("Enhance"),
    GRAYSCALE("Grayscale"),
    BW("Black & white")
}

/** Document-scan style image enhancements to make text crisper. */
object ImageFilters {

    fun apply(src: Bitmap, filter: ScanFilter): Bitmap = when (filter) {
        ScanFilter.ORIGINAL -> src
        ScanFilter.ENHANCE -> enhance(src)
        ScanFilter.GRAYSCALE -> matrixFilter(src, ColorMatrix().apply { setSaturation(0f) })
        ScanFilter.BW -> blackAndWhite(src)
    }

    private fun enhance(src: Bitmap): Bitmap {
        val contrast = 1.45f
        val brightness = 8f
        val translate = (-0.5f * contrast + 0.5f) * 255f + brightness
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(ColorMatrix().apply { setSaturation(1.1f) })
        return matrixFilter(src, cm)
    }

    private fun matrixFilter(src: Bitmap, cm: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Adaptive-ish threshold: boosts dark text against a bright background. */
    private fun blackAndWhite(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0L
        val lum = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = (r * 30 + g * 59 + b * 11) / 100
            lum[i] = l
            sum += l
        }
        val mean = (sum / pixels.size).toInt()
        val threshold = (mean * 0.92f).toInt() // slightly below mean so text stays black
        for (i in pixels.indices) {
            pixels[i] = if (lum[i] > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
