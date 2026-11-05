package com.ahif.lumen.data.tools.encoders

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Baseline little-endian TIFF: 8-bit RGB or RGBA (unassociated alpha), LZW with the horizontal
 * predictor — lossless and readable by Photoshop, Windows, macOS, GIMP and print workflows.
 */
object TiffWriter {

    fun write(src: PixelSource, file: File, alpha: Boolean) {
        val w = src.width
        val h = src.height
        val spp = if (alpha) 4 else 3
        val rowBytes = w * spp
        val rowsPerStrip = (65536 / rowBytes).coerceIn(1, h)
        val strips = (h + rowsPerStrip - 1) / rowsPerStrip
        val offsets = LongArray(strips)
        val counts = LongArray(strips)
        var ifdOffset = 0L

        CountingStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).use { out ->
            out.write('I'.code); out.write('I'.code)
            out.le16(42)
            out.le32(0) // IFD offset, patched below

            val px = IntArray(w)
            val bytes = IntArray(rowBytes)
            for (s in 0 until strips) {
                offsets[s] = out.count
                val sink = MsbFirstSink(out)
                val lzw = LzwEncoder(8, tiff = true, sink = sink)
                val y0 = s * rowsPerStrip
                for (y in y0 until minOf(h, y0 + rowsPerStrip)) {
                    src.row(y, px)
                    var i = 0
                    for (x in 0 until w) {
                        val p = px[x]
                        bytes[i++] = p shr 16 and 0xFF
                        bytes[i++] = p shr 8 and 0xFF
                        bytes[i++] = p and 0xFF
                        if (alpha) bytes[i++] = p ushr 24
                    }
                    // Horizontal differencing predictor (TIFF Predictor = 2).
                    for (k in rowBytes - 1 downTo spp) bytes[k] = (bytes[k] - bytes[k - spp]) and 0xFF
                    for (k in 0 until rowBytes) lzw.write(bytes[k])
                }
                lzw.finish()
                sink.close()
                counts[s] = out.count - offsets[s]
            }
            if (out.count % 2 != 0L) out.write(0)
            ifdOffset = out.count
            check(ifdOffset < 0xFFFFFFFFL) { "Too large for TIFF" }

            val entries = ArrayList<Entry>()
            val software = "AR Reader\u0000".toByteArray(Charsets.US_ASCII)
            entries += Entry(256, LONG, 1, intArrayOf(w))
            entries += Entry(257, LONG, 1, intArrayOf(h))
            entries += Entry(258, SHORT, spp, IntArray(spp) { 8 })
            entries += Entry(259, SHORT, 1, intArrayOf(5)) // LZW
            entries += Entry(262, SHORT, 1, intArrayOf(2)) // RGB
            entries += Entry(273, LONG, strips, IntArray(strips) { offsets[it].toInt() })
            entries += Entry(277, SHORT, 1, intArrayOf(spp))
            entries += Entry(278, LONG, 1, intArrayOf(rowsPerStrip))
            entries += Entry(279, LONG, strips, IntArray(strips) { counts[it].toInt() })
            entries += Entry(282, RATIONAL, 1, intArrayOf(72, 1))
            entries += Entry(283, RATIONAL, 1, intArrayOf(72, 1))
            entries += Entry(284, SHORT, 1, intArrayOf(1)) // chunky
            entries += Entry(296, SHORT, 1, intArrayOf(2)) // inch
            entries += Entry(305, ASCII, software.size, software.map { it.toInt() }.toIntArray())
            entries += Entry(317, SHORT, 1, intArrayOf(2)) // horizontal predictor
            if (alpha) entries += Entry(338, SHORT, 1, intArrayOf(2)) // unassociated alpha

            // Values over 4 bytes live after the IFD, each starting on an even offset.
            var extra = ifdOffset + 2 + 12L * entries.size + 4
            val extraOffsets = LongArray(entries.size)
            entries.forEachIndexed { i, e ->
                if (e.byteSize > 4) {
                    extraOffsets[i] = extra
                    extra += (e.byteSize + 1) and 1.inv()
                }
            }
            out.le16(entries.size)
            entries.forEachIndexed { i, e ->
                out.le16(e.tag)
                out.le16(e.type)
                out.le32(e.count)
                if (e.byteSize > 4) out.le32(extraOffsets[i].toInt()) else {
                    e.writeValues(out)
                    repeat(4 - e.byteSize) { out.write(0) }
                }
            }
            out.le32(0) // no next IFD
            entries.forEach { e ->
                if (e.byteSize > 4) {
                    e.writeValues(out)
                    if (e.byteSize % 2 != 0) out.write(0)
                }
            }
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            val v = ifdOffset.toInt()
            raf.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        }
    }

    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5

    private class Entry(val tag: Int, val type: Int, val count: Int, val values: IntArray) {
        val byteSize: Int
            get() = count * when (type) {
                ASCII -> 1
                SHORT -> 2
                LONG -> 4
                else -> 8
            }

        fun writeValues(out: OutputStream) {
            when (type) {
                ASCII -> values.forEach { out.write(it) }
                SHORT -> values.forEach { out.le16(it) }
                else -> values.forEach { out.le32(it) } // LONG, or RATIONAL as numerator/denominator pairs
            }
        }
    }

    private class CountingStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            inner.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
            count += len
        }

        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }
}
