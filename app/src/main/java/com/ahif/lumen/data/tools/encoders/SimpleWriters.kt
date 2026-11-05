package com.ahif.lumen.data.tools.encoders

import java.io.OutputStream

/** 24-bit Windows bitmap (bottom-up rows, padded to 4 bytes). Expects an opaque source. */
object BmpWriter {
    fun write(src: PixelSource, out: OutputStream) {
        val w = src.width
        val h = src.height
        val rowSize = (w * 3 + 3) and 3.inv()
        val imageSize = rowSize.toLong() * h
        require(54 + imageSize < Int.MAX_VALUE) { "Too large for BMP" }
        out.write('B'.code); out.write('M'.code)
        out.le32((54 + imageSize).toInt())
        out.le32(0)
        out.le32(54)
        out.le32(40) // BITMAPINFOHEADER
        out.le32(w)
        out.le32(h)
        out.le16(1)
        out.le16(24)
        out.le32(0) // BI_RGB
        out.le32(imageSize.toInt())
        out.le32(2835) // 72 dpi
        out.le32(2835)
        out.le32(0)
        out.le32(0)
        val px = IntArray(w)
        val buf = ByteArray(rowSize)
        for (y in h - 1 downTo 0) {
            src.row(y, px)
            var i = 0
            for (x in 0 until w) {
                val p = px[x]
                buf[i++] = p.toByte()
                buf[i++] = (p shr 8).toByte()
                buf[i++] = (p shr 16).toByte()
            }
            out.write(buf)
        }
    }
}

/** Run-length compressed Truevision TGA, top-left origin, 32-bit with alpha or 24-bit. */
object TgaWriter {
    fun write(src: PixelSource, out: OutputStream, alpha: Boolean) {
        val w = src.width
        val h = src.height
        require(w in 1..65535 && h in 1..65535) { "TGA supports up to 65535×65535" }
        val bpp = if (alpha) 4 else 3
        out.write(0) // no image ID
        out.write(0) // no colour map
        out.write(10) // RLE true-colour
        repeat(5) { out.write(0) } // colour map spec
        out.le16(0); out.le16(0) // origin
        out.le16(w); out.le16(h)
        out.write(bpp * 8)
        out.write((if (alpha) 8 else 0) or 0x20) // alpha bits, top-left origin
        val px = IntArray(w)
        val mask = if (alpha) -1 else 0xFFFFFF
        for (y in 0 until h) {
            src.row(y, px)
            var x = 0
            while (x < w) {
                var run = 1
                while (x + run < w && run < 128 && (px[x + run] and mask) == (px[x] and mask)) run++
                if (run >= 2) {
                    out.write(0x80 or (run - 1))
                    pixel(out, px[x], alpha)
                    x += run
                } else {
                    var n = 1
                    while (x + n < w && n < 128 &&
                        !(x + n + 1 < w && (px[x + n] and mask) == (px[x + n + 1] and mask))
                    ) n++
                    out.write(n - 1)
                    for (i in x until x + n) pixel(out, px[i], alpha)
                    x += n
                }
            }
        }
        // TGA 2.0 footer
        out.le32(0)
        out.le32(0)
        out.write("TRUEVISION-XFILE.".toByteArray(Charsets.US_ASCII))
        out.write(0)
    }

    private fun pixel(out: OutputStream, p: Int, alpha: Boolean) {
        out.write(p and 0xFF)
        out.write(p shr 8 and 0xFF)
        out.write(p shr 16 and 0xFF)
        if (alpha) out.write(p ushr 24)
    }
}

/** Flattened Photoshop document (no layers), RLE-compressed RGB or RGBA channels. */
object PsdWriter {
    fun write(src: PixelSource, out: OutputStream, alpha: Boolean) {
        val w = src.width
        val h = src.height
        require(w in 1..30000 && h in 1..30000) { "PSD supports up to 30000×30000" }
        val channels = if (alpha) 4 else 3
        out.write("8BPS".toByteArray(Charsets.US_ASCII))
        out.be16(1) // version
        repeat(6) { out.write(0) }
        out.be16(channels)
        out.be32(h)
        out.be32(w)
        out.be16(8) // bits per channel
        out.be16(3) // RGB
        out.be32(0) // colour mode data
        out.be32(0) // image resources
        out.be32(0) // layer and mask info
        out.be16(1) // RLE (PackBits)

        val shifts = intArrayOf(16, 8, 0, 24)
        val px = IntArray(w)
        val plane = ByteArray(w)
        val packed = ByteArray(w + (w + 127) / 128 + 1)
        // All row byte counts come first, so compress twice: once to measure, once to write.
        for (c in 0 until channels) {
            for (y in 0 until h) {
                src.row(y, px)
                for (x in 0 until w) plane[x] = (px[x] shr shifts[c]).toByte()
                out.be16(packBits(plane, w, packed))
            }
        }
        for (c in 0 until channels) {
            for (y in 0 until h) {
                src.row(y, px)
                for (x in 0 until w) plane[x] = (px[x] shr shifts[c]).toByte()
                out.write(packed, 0, packBits(plane, w, packed))
            }
        }
    }
}

/**
 * PackBits: runs as (1−n, byte), everything else as (n−1, n literal bytes). Literals only break
 * for runs of 3+, so output never exceeds n + n/128 + 1 bytes.
 */
internal fun packBits(src: ByteArray, n: Int, dst: ByteArray): Int {
    var i = 0
    var o = 0
    while (i < n) {
        var run = 1
        while (i + run < n && run < 128 && src[i + run] == src[i]) run++
        if (run >= 2) {
            dst[o++] = (1 - run).toByte()
            dst[o++] = src[i]
            i += run
        } else {
            var lit = 1
            while (i + lit < n && lit < 128 &&
                !(i + lit + 2 < n && src[i + lit] == src[i + lit + 1] && src[i + lit] == src[i + lit + 2])
            ) lit++
            dst[o++] = (lit - 1).toByte()
            System.arraycopy(src, i, dst, o, lit)
            o += lit
            i += lit
        }
    }
    return o
}

class IcoImage(val size: Int, val png: ByteArray)

/** Windows icon holding one PNG-compressed image per size (Vista+ / all browsers). */
object IcoWriter {
    fun write(images: List<IcoImage>, out: OutputStream) {
        require(images.isNotEmpty())
        out.le16(0)
        out.le16(1) // icon
        out.le16(images.size)
        var offset = 6 + 16 * images.size
        for (img in images) {
            val dim = if (img.size >= 256) 0 else img.size
            out.write(dim)
            out.write(dim)
            out.write(0) // no palette
            out.write(0)
            out.le16(1) // planes
            out.le16(32) // bits per pixel
            out.le32(img.png.size)
            out.le32(offset)
            offset += img.png.size
        }
        images.forEach { out.write(it.png) }
    }
}
