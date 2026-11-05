package com.ahif.lumen.data.tools.encoders

import java.io.OutputStream

/**
 * Still GIF89a writer. Images with 256 colours or fewer are stored exactly; others are reduced
 * with variance-based median cut, optionally Floyd–Steinberg dithered. Pixels under 50% alpha
 * become the transparent colour.
 */
object GifWriter {

    fun write(src: PixelSource, out: OutputStream, dither: Boolean) {
        val w = src.width
        val h = src.height
        require(w in 1..65535 && h in 1..65535) { "GIF supports up to 65535×65535" }
        val row = IntArray(w)

        // Pass 1: colour statistics.
        val hist = Histogram()
        val exact = ColorIndex(256)
        var exactOk = true
        var transparent = false
        for (y in 0 until h) {
            src.row(y, row)
            for (x in 0 until w) {
                val p = row[x]
                if (p ushr 24 < 128) {
                    transparent = true
                    continue
                }
                val rgb = p and 0xFFFFFF
                hist.add(rgb)
                if (exactOk && exact.get(rgb) < 0 && !exact.add(rgb)) exactOk = false
            }
        }
        val maxColors = if (transparent) 255 else 256
        if (exact.size > maxColors) exactOk = false
        val palette = if (exactOk) exact.colors() else hist.medianCut(maxColors)
        val transparentIndex = palette.size

        val used = palette.size + if (transparent) 1 else 0
        var tableBits = 1
        while (1 shl tableBits < used) tableBits++

        // Header, screen descriptor and global colour table.
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.le16(w)
        out.le16(h)
        out.write(0x80 or (7 shl 4) or (tableBits - 1))
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio
        for (i in 0 until (1 shl tableBits)) {
            val c = if (i < palette.size) palette[i] else 0
            out.write(c shr 16 and 0xFF)
            out.write(c shr 8 and 0xFF)
            out.write(c and 0xFF)
        }
        if (transparent) {
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write(0x01) // transparent colour flag
            out.le16(0) // delay
            out.write(transparentIndex)
            out.write(0)
        }
        out.write(0x2C)
        out.le16(0); out.le16(0); out.le16(w); out.le16(h)
        out.write(0) // no local table, not interlaced

        val minCodeSize = maxOf(2, tableBits)
        out.write(minCodeSize)
        val sink = GifBlockSink(out)
        val lzw = LzwEncoder(minCodeSize, tiff = false, sink = sink)

        // Pass 2: map to palette indices.
        val indices = IntArray(w)
        val mapper = PaletteMapper(palette)
        if (exactOk) {
            for (y in 0 until h) {
                src.row(y, row)
                for (x in 0 until w) {
                    val p = row[x]
                    lzw.write(if (p ushr 24 < 128) transparentIndex else exact.get(p and 0xFFFFFF))
                }
            }
        } else if (!dither) {
            for (y in 0 until h) {
                src.row(y, row)
                for (x in 0 until w) {
                    val p = row[x]
                    lzw.write(if (p ushr 24 < 128) transparentIndex else mapper.nearest(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF))
                }
            }
        } else {
            // Floyd–Steinberg, serpentine. Errors are kept ×16 in integer buffers with a 1px border.
            var cur = IntArray((w + 2) * 3)
            var nxt = IntArray((w + 2) * 3)
            for (y in 0 until h) {
                src.row(y, row)
                nxt.fill(0)
                val ltr = y % 2 == 0
                val dir = if (ltr) 1 else -1
                for (i in 0 until w) {
                    val x = if (ltr) i else w - 1 - i
                    val p = row[x]
                    if (p ushr 24 < 128) {
                        indices[x] = transparentIndex
                        continue
                    }
                    val e = (x + 1) * 3
                    val r = clamp((p shr 16 and 0xFF) + cur[e] / 16)
                    val g = clamp((p shr 8 and 0xFF) + cur[e + 1] / 16)
                    val b = clamp((p and 0xFF) + cur[e + 2] / 16)
                    val k = mapper.nearest(r, g, b)
                    indices[x] = k
                    val c = palette[k]
                    val er = r - (c shr 16 and 0xFF)
                    val eg = g - (c shr 8 and 0xFF)
                    val eb = b - (c and 0xFF)
                    val ahead = e + dir * 3
                    val behind = e - dir * 3
                    cur[ahead] += er * 7; cur[ahead + 1] += eg * 7; cur[ahead + 2] += eb * 7
                    nxt[behind] += er * 3; nxt[behind + 1] += eg * 3; nxt[behind + 2] += eb * 3
                    nxt[e] += er * 5; nxt[e + 1] += eg * 5; nxt[e + 2] += eb * 5
                    nxt[ahead] += er; nxt[ahead + 1] += eg; nxt[ahead + 2] += eb
                }
                for (x in 0 until w) lzw.write(indices[x])
                val t = cur; cur = nxt; nxt = t
            }
        }
        lzw.finish()
        sink.close()
        out.write(0x3B)
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
}

/** Small open-addressing RGB → index map that gives up after [limit] distinct colours. */
private class ColorIndex(private val limit: Int) {
    private val keys = IntArray(1024) { -1 }
    private val vals = IntArray(1024)
    var size = 0
        private set

    private fun slot(rgb: Int): Int {
        var s = (rgb * -0x61c88647) ushr 22
        while (keys[s] != -1 && keys[s] != rgb) s = (s + 1) and 1023
        return s
    }

    fun get(rgb: Int): Int {
        val s = slot(rgb)
        return if (keys[s] == rgb) vals[s] else -1
    }

    /** Adds a colour not yet present; false once more than [limit] colours were seen. */
    fun add(rgb: Int): Boolean {
        if (size >= limit) {
            size = limit + 1
            return false
        }
        val s = slot(rgb)
        keys[s] = rgb
        vals[s] = size++
        return true
    }

    fun colors(): IntArray {
        val out = IntArray(size)
        for (i in keys.indices) if (keys[i] != -1) out[vals[i]] = keys[i]
        return out
    }
}

/** 5-bit-per-channel histogram with exact colour sums, reduced by variance-based median cut. */
private class Histogram {
    val count = IntArray(BINS)
    val sum = Array(3) { LongArray(BINS) }
    val sq = Array(3) { LongArray(BINS) }

    fun add(rgb: Int) {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val bin = (r shr 3 shl 10) or (g shr 3 shl 5) or (b shr 3)
        count[bin]++
        sum[0][bin] += r.toLong(); sum[1][bin] += g.toLong(); sum[2][bin] += b.toLong()
        sq[0][bin] += (r * r).toLong(); sq[1][bin] += (g * g).toLong(); sq[2][bin] += (b * b).toLong()
    }

    private class Box(val from: Int, val to: Int, val error: Double, val axis: Int)

    fun medianCut(maxColors: Int): IntArray {
        val idx = (0 until BINS).filter { count[it] > 0 }.toIntArray()
        if (idx.isEmpty()) return intArrayOf(0)
        val boxes = ArrayList<Box>()
        boxes += box(idx, 0, idx.size)
        while (boxes.size < maxColors) {
            var best = -1
            for (i in boxes.indices) {
                val b = boxes[i]
                if (b.to - b.from >= 2 && b.error > 0 && (best < 0 || b.error > boxes[best].error)) best = i
            }
            if (best < 0) break
            val b = boxes[best]
            sortByAxis(idx, b.from, b.to, SHIFTS[b.axis])
            var total = 0L
            for (i in b.from until b.to) total += count[idx[i]]
            var acc = 0L
            var cut = b.to - 1
            for (i in b.from until b.to - 1) {
                acc += count[idx[i]]
                if (acc * 2 >= total) { cut = i + 1; break }
            }
            boxes[best] = box(idx, b.from, cut)
            boxes += box(idx, cut, b.to)
        }
        return IntArray(boxes.size) { i ->
            val b = boxes[i]
            var n = 0L
            val s = LongArray(3)
            for (j in b.from until b.to) {
                val bin = idx[j]
                n += count[bin]
                for (c in 0..2) s[c] += sum[c][bin]
            }
            val r = ((s[0] + n / 2) / n).toInt()
            val g = ((s[1] + n / 2) / n).toInt()
            val bl = ((s[2] + n / 2) / n).toInt()
            (r shl 16) or (g shl 8) or bl
        }
    }

    private fun box(idx: IntArray, from: Int, to: Int): Box {
        var n = 0.0
        val s = DoubleArray(3)
        val q = DoubleArray(3)
        for (j in from until to) {
            val bin = idx[j]
            n += count[bin]
            for (c in 0..2) { s[c] += sum[c][bin].toDouble(); q[c] += sq[c][bin].toDouble() }
        }
        var error = 0.0
        var axis = 0
        var widest = -1.0
        for (c in 0..2) {
            val v = q[c] - s[c] * s[c] / n
            error += v
            if (v > widest) { widest = v; axis = c }
        }
        return Box(from, to, error, axis)
    }

    private fun sortByAxis(idx: IntArray, from: Int, to: Int, shift: Int) {
        val starts = IntArray(33)
        for (i in from until to) starts[(idx[i] shr shift and 31) + 1]++
        for (k in 1..32) starts[k] += starts[k - 1]
        val tmp = IntArray(to - from)
        for (i in from until to) {
            val c = idx[i] shr shift and 31
            tmp[starts[c]++] = idx[i]
        }
        System.arraycopy(tmp, 0, idx, from, tmp.size)
    }

    companion object {
        const val BINS = 1 shl 15
        val SHIFTS = intArrayOf(10, 5, 0)
    }
}

/** Nearest palette colour, cached on a 6-bit-per-channel grid. */
private class PaletteMapper(private val palette: IntArray) {
    private val cache = IntArray(1 shl 18) { -1 }

    fun nearest(r: Int, g: Int, b: Int): Int {
        val key = (r shr 2 shl 12) or (g shr 2 shl 6) or (b shr 2)
        val cached = cache[key]
        if (cached >= 0) return cached
        val cr = (r and 0xFC) + 2
        val cg = (g and 0xFC) + 2
        val cb = (b and 0xFC) + 2
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val c = palette[i]
            val dr = cr - (c shr 16 and 0xFF)
            val dg = cg - (c shr 8 and 0xFF)
            val db = cb - (c and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i }
        }
        cache[key] = best
        return best
    }
}
