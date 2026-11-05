package com.ahif.lumen.data.tools.encoders

import java.io.OutputStream

/**
 * Row-by-row access to non-premultiplied ARGB pixels, so encoders never need a second
 * full-size copy of a large photo. Plain Kotlin — no Android types — so it is unit-testable.
 */
interface PixelSource {
    val width: Int
    val height: Int

    /** Fills [out] (size ≥ width) with row [y], counted from the top. */
    fun row(y: Int, out: IntArray)
}

class ArgbPixels(override val width: Int, override val height: Int, private val pixels: IntArray) : PixelSource {
    override fun row(y: Int, out: IntArray) = System.arraycopy(pixels, y * width, out, 0, width)
}

/** Composites every pixel over an opaque [background], for formats without transparency. */
class FlattenedSource(private val src: PixelSource, private val background: Int) : PixelSource {
    override val width get() = src.width
    override val height get() = src.height
    override fun row(y: Int, out: IntArray) {
        src.row(y, out)
        for (x in 0 until width) out[x] = flatten(out[x], background)
    }
}

private const val OPAQUE = -0x1000000 // 0xFF000000

internal fun flatten(p: Int, bg: Int): Int {
    val a = p ushr 24
    if (a == 255) return p
    if (a == 0) return bg or OPAQUE
    val inv = 255 - a
    val r = ((p shr 16 and 0xFF) * a + (bg shr 16 and 0xFF) * inv + 127) / 255
    val g = ((p shr 8 and 0xFF) * a + (bg shr 8 and 0xFF) * inv + 127) / 255
    val b = ((p and 0xFF) * a + (bg and 0xFF) * inv + 127) / 255
    return OPAQUE or (r shl 16) or (g shl 8) or b
}

fun PixelSource.hasTransparency(): Boolean {
    val row = IntArray(width)
    for (y in 0 until height) {
        row(y, row)
        for (x in 0 until width) if (row[x] ushr 24 != 255) return true
    }
    return false
}

internal fun OutputStream.le16(v: Int) {
    write(v and 0xFF); write(v ushr 8 and 0xFF)
}

internal fun OutputStream.le32(v: Int) {
    le16(v and 0xFFFF); le16(v ushr 16)
}

internal fun OutputStream.be16(v: Int) {
    write(v ushr 8 and 0xFF); write(v and 0xFF)
}

internal fun OutputStream.be32(v: Int) {
    be16(v ushr 16); be16(v and 0xFFFF)
}
