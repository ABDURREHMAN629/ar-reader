package com.ahif.lumen.data.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object BitmapUtils {
    /** Decode [bytes] downsampled so its largest side is <= [maxDimension]. */
    fun decodeSampled(bytes: ByteArray, maxDimension: Int = 2200): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (largest / sample > maxDimension) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }
}

/** Natural (human) ordering of file names so page2 sorts before page10. */
internal fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ni = i
            while (ni < a.length && a[ni].isDigit()) ni++
            var nj = j
            while (nj < b.length && b[nj].isDigit()) nj++
            val na = a.substring(i, ni).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(j, nj).trimStart('0').ifEmpty { "0" }
            if (na.length != nb.length) return na.length - nb.length
            val cmp = na.compareTo(nb)
            if (cmp != 0) return cmp
            i = ni; j = nj
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return cmp
            i++; j++
        }
    }
    return (a.length - i) - (b.length - j)
}
