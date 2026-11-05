package com.ahif.lumen.data.tools.encoders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.random.Random

/**
 * Round-trips every custom encoder through an independent decoder (the JDK's ImageIO for
 * BMP/GIF/TIFF/PNG, spec-driven parsers here for TGA/PSD/ICO) and compares pixels.
 * Output files are kept in build/encoder-test for checking with other viewers.
 */
class EncodersTest {

    private val dir = File("build/encoder-test").apply { mkdirs() }

    // ---------- test images ----------

    private fun photo(w: Int, h: Int, alpha: Boolean, seed: Int = 1): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = (x * 255 / maxOf(1, w - 1) + rnd.nextInt(-12, 13)).coerceIn(0, 255)
            val g = (y * 255 / maxOf(1, h - 1) + rnd.nextInt(-12, 13)).coerceIn(0, 255)
            val b = ((x + y) * 3 % 256 + rnd.nextInt(-6, 7)).coerceIn(0, 255)
            val a = if (!alpha) 255 else when {
                x < w / 4 -> 0
                x < w / 2 -> (y * 255 / maxOf(1, h - 1))
                else -> 255
            }
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun flat(w: Int, h: Int, colors: Int, transparentHoles: Boolean = false): IntArray {
        val rnd = Random(7)
        val palette = IntArray(colors) { -0x1000000 or rnd.nextInt(0x1000000) }
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (transparentHoles && (x / 10 + y / 10) % 5 == 0) 0x00123456
            else palette[((x / 17) + (y / 13) * 7) % colors]
        }
    }

    private fun noise(w: Int, h: Int, seed: Int = 3): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { -0x1000000 or rnd.nextInt(0x1000000) }
    }

    private fun save(name: String, bytes: ByteArray): File = File(dir, name).also { it.writeBytes(bytes) }

    private fun encode(block: (ByteArrayOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also(block).toByteArray()

    private fun assertPixels(expected: IntArray, w: Int, h: Int, img: BufferedImage, compareAlpha: Boolean, label: String) {
        assertEquals("$label width", w, img.width)
        assertEquals("$label height", h, img.height)
        for (y in 0 until h) for (x in 0 until w) {
            val e = expected[y * w + x]
            val a = img.getRGB(x, y)
            if (compareAlpha) {
                if (e ushr 24 == 0) {
                    assertEquals("$label alpha at $x,$y", 0, a ushr 24)
                } else {
                    assertEquals("$label pixel at $x,$y", e, a)
                }
            } else {
                assertEquals("$label pixel at $x,$y", e and 0xFFFFFF, a and 0xFFFFFF)
            }
        }
    }

    private fun flattened(px: IntArray, bg: Int) = IntArray(px.size) { flatten(px[it], bg) }

    // ---------- BMP ----------

    @Test
    fun bmpRoundTrip() {
        for ((w, h) in listOf(333 to 217, 1 to 1, 4 to 3, 5 to 7)) {
            val px = photo(w, h, alpha = true)
            val bytes = encode { BmpWriter.write(FlattenedSource(ArgbPixels(w, h, px), 0xFFFFFF), it) }
            val file = save("bmp_${w}x$h.bmp", bytes)
            val img = ImageIO.read(file)
            assertNotNull("ImageIO decoded BMP", img)
            assertPixels(flattened(px, 0xFFFFFF), w, h, img, compareAlpha = false, label = "BMP ${w}x$h")
        }
    }

    // ---------- TIFF ----------

    private fun tiffCheck(name: String, w: Int, h: Int, px: IntArray, alpha: Boolean) {
        val file = File(dir, name)
        TiffWriter.write(ArgbPixels(w, h, px), file, alpha)
        val img = ImageIO.read(file)
        assertNotNull("ImageIO decoded $name", img)
        assertPixels(px, w, h, img, compareAlpha = alpha, label = name)
    }

    @Test
    fun tiffRgb() = tiffCheck("tiff_rgb.tiff", 333, 217, photo(333, 217, alpha = false), alpha = false)

    @Test
    fun tiffRgba() = tiffCheck("tiff_rgba.tiff", 333, 217, photo(333, 217, alpha = true), alpha = true)

    @Test
    fun tiffNoiseExercisesTableResets() = tiffCheck("tiff_noise.tiff", 700, 500, noise(700, 500), alpha = false)

    @Test
    fun tiffFlatLongRuns() = tiffCheck("tiff_flat.tiff", 1200, 900, flat(1200, 900, 5), alpha = false)

    @Test
    fun tiffTinyAndWide() {
        tiffCheck("tiff_1x1.tiff", 1, 1, intArrayOf(-0xff8100), alpha = false)
        tiffCheck("tiff_wide.tiff", 30000, 3, noise(30000, 3, seed = 9), alpha = false)
    }

    // ---------- GIF ----------

    @Test
    fun gifExactPalette() {
        val w = 300
        val h = 200
        val px = flat(w, h, 40)
        val file = save("gif_exact.gif", encode { GifWriter.write(ArgbPixels(w, h, px), it, dither = true) })
        val img = ImageIO.read(file)
        assertNotNull(img)
        assertPixels(px, w, h, img, compareAlpha = false, label = "GIF exact")
    }

    @Test
    fun gifExact256AndTwoColours() {
        val w = 256
        val h = 64
        val px256 = IntArray(w * h) { i -> -0x1000000 or ((i % 256) * 0x010101) }
        val f1 = save("gif_256.gif", encode { GifWriter.write(ArgbPixels(w, h, px256), it, dither = false) })
        assertPixels(px256, w, h, ImageIO.read(f1), compareAlpha = false, label = "GIF 256")
        val px2 = IntArray(w * h) { i -> if ((i / 7) % 2 == 0) -0x1000000 else -1 }
        val f2 = save("gif_2.gif", encode { GifWriter.write(ArgbPixels(w, h, px2), it, dither = false) })
        assertPixels(px2, w, h, ImageIO.read(f2), compareAlpha = false, label = "GIF 2 colours")
        val one = IntArray(9) { -0xff0100 }
        val f3 = save("gif_1.gif", encode { GifWriter.write(ArgbPixels(3, 3, one), it, dither = false) })
        assertPixels(one, 3, 3, ImageIO.read(f3), compareAlpha = false, label = "GIF 1 colour")
    }

    @Test
    fun gifTransparency() {
        val w = 200
        val h = 150
        val px = flat(w, h, 30, transparentHoles = true)
        val file = save("gif_transparent.gif", encode { GifWriter.write(ArgbPixels(w, h, px), it, dither = false) })
        val img = ImageIO.read(file)
        assertPixels(px, w, h, img, compareAlpha = true, label = "GIF transparency")
    }

    private fun meanError(px: IntArray, img: BufferedImage): Double {
        var total = 0L
        for (i in px.indices) {
            val a = img.getRGB(i % img.width, i / img.width)
            val e = px[i]
            total += abs((a shr 16 and 255) - (e shr 16 and 255)) +
                abs((a shr 8 and 255) - (e shr 8 and 255)) +
                abs((a and 255) - (e and 255))
        }
        return total / (px.size * 3.0)
    }

    @Test
    fun gifQuantizedPhoto() {
        val w = 400
        val h = 300
        val px = photo(w, h, alpha = false)
        for (dither in listOf(false, true)) {
            val file = save("gif_photo_dither_$dither.gif", encode { GifWriter.write(ArgbPixels(w, h, px), it, dither) })
            val img = ImageIO.read(file)
            assertEquals(w, img.width)
            assertEquals(h, img.height)
            val err = meanError(px, img)
            println("GIF photo dither=$dither mean abs error = $err, size = ${file.length()}")
            assertTrue("GIF colour error too high ($err)", err < 12.0)
        }
    }

    @Test
    fun gifNoiseExercisesTableResets() {
        val w = 640
        val h = 480
        val px = noise(w, h)
        val file = save("gif_noise.gif", encode { GifWriter.write(ArgbPixels(w, h, px), it, dither = true) })
        val img = ImageIO.read(file)
        assertEquals(w, img.width)
        assertTrue(meanError(px, img) < 40.0)
    }

    @Test
    fun gifPhotoWithAlpha() {
        val w = 320
        val h = 240
        val px = photo(w, h, alpha = true)
        val file = save("gif_photo_alpha.gif", encode { GifWriter.write(ArgbPixels(w, h, px), it, dither = true) })
        val img = ImageIO.read(file)
        for (y in 0 until h) for (x in 0 until w) {
            val srcA = px[y * w + x] ushr 24
            val outA = img.getRGB(x, y) ushr 24
            assertEquals("alpha at $x,$y", if (srcA < 128) 0 else 255, outA)
        }
    }

    // ---------- TGA ----------

    private fun decodeTga(bytes: ByteArray): Triple<Int, Int, IntArray> {
        val idLen = bytes[0].toInt() and 255
        assertEquals(0, bytes[1].toInt())
        assertEquals(10, bytes[2].toInt())
        fun u16(o: Int) = (bytes[o].toInt() and 255) or ((bytes[o + 1].toInt() and 255) shl 8)
        val w = u16(12)
        val h = u16(14)
        val bpp = (bytes[16].toInt() and 255) / 8
        val desc = bytes[17].toInt() and 255
        assertEquals("top-left origin", 0x20, desc and 0x30)
        var p = 18 + idLen
        val out = IntArray(w * h)
        var i = 0
        fun readPixel(): Int {
            val b = bytes[p].toInt() and 255
            val g = bytes[p + 1].toInt() and 255
            val r = bytes[p + 2].toInt() and 255
            val a = if (bpp == 4) bytes[p + 3].toInt() and 255 else 255
            p += bpp
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        while (i < w * h) {
            val hdr = bytes[p++].toInt() and 255
            val n = (hdr and 0x7F) + 1
            assertTrue("packet crosses a scanline", (i % w) + n <= w)
            if (hdr and 0x80 != 0) {
                val c = readPixel()
                repeat(n) { out[i++] = c }
            } else repeat(n) { out[i++] = readPixel() }
        }
        val footer = String(bytes, bytes.size - 18, 17, Charsets.US_ASCII)
        assertEquals("TRUEVISION-XFILE.", footer)
        assertEquals(bytes.size - 26, p)
        return Triple(w, h, out)
    }

    @Test
    fun tgaRoundTrip() {
        for (alpha in listOf(false, true)) {
            for ((w, h, px) in listOf(
                Triple(333, 217, photo(333, 217, alpha)),
                Triple(300, 200, flat(300, 200, 6, transparentHoles = alpha)),
                Triple(1, 1, intArrayOf(0x7F102030))
            )) {
                val bytes = encode { TgaWriter.write(ArgbPixels(w, h, px), it, alpha) }
                save("tga_${w}x${h}_$alpha.tga", bytes)
                val (dw, dh, out) = decodeTga(bytes)
                assertEquals(w, dw)
                assertEquals(h, dh)
                for (k in px.indices) {
                    val e = if (alpha) px[k] else px[k] or -0x1000000
                    assertEquals("TGA pixel $k (alpha=$alpha)", e, out[k])
                }
            }
        }
    }

    // ---------- PSD ----------

    private fun unpackBits(src: ByteArray, off: Int, len: Int, n: Int): ByteArray {
        val out = ByteArray(n)
        var p = off
        var o = 0
        while (p < off + len) {
            val hdr = src[p++].toInt()
            when {
                hdr >= 0 -> { System.arraycopy(src, p, out, o, hdr + 1); p += hdr + 1; o += hdr + 1 }
                hdr != -128 -> { val c = src[p++]; repeat(1 - hdr) { out[o++] = c } }
            }
        }
        assertEquals("row length", n, o)
        return out
    }

    @Test
    fun psdRoundTrip() {
        for (alpha in listOf(false, true)) {
            val w = 333
            val h = 217
            val px = photo(w, h, alpha)
            val bytes = encode { PsdWriter.write(ArgbPixels(w, h, px), it, alpha) }
            save("psd_$alpha.psd", bytes)
            val din = DataInputStream(ByteArrayInputStream(bytes))
            val sig = ByteArray(4).also { din.readFully(it) }
            assertEquals("8BPS", String(sig, Charsets.US_ASCII))
            assertEquals(1, din.readUnsignedShort())
            din.skipBytes(6)
            val channels = din.readUnsignedShort()
            assertEquals(if (alpha) 4 else 3, channels)
            assertEquals(h, din.readInt())
            assertEquals(w, din.readInt())
            assertEquals(8, din.readUnsignedShort())
            assertEquals(3, din.readUnsignedShort())
            assertEquals(0, din.readInt())
            assertEquals(0, din.readInt())
            assertEquals(0, din.readInt())
            assertEquals(1, din.readUnsignedShort())
            val counts = IntArray(channels * h) { din.readUnsignedShort() }
            var offset = bytes.size - din.available()
            assertEquals("data size", counts.sum(), din.available())
            val shifts = intArrayOf(16, 8, 0, 24)
            for (c in 0 until channels) for (y in 0 until h) {
                val len = counts[c * h + y]
                val row = unpackBits(bytes, offset, len, w)
                offset += len
                for (x in 0 until w) {
                    assertEquals("PSD ch$c $x,$y", px[y * w + x] shr shifts[c] and 255, row[x].toInt() and 255)
                }
            }
        }
    }

    @Test
    fun packBitsRoundTrip() {
        val rnd = Random(11)
        repeat(200) {
            val n = rnd.nextInt(1, 700)
            val src = ByteArray(n) { i -> if (rnd.nextInt(4) == 0) rnd.nextInt(256).toByte() else (i / rnd.nextInt(1, 40)).toByte() }
            val dst = ByteArray(n + (n + 127) / 128 + 1)
            val len = packBits(src, n, dst)
            val back = unpackBits(dst, 0, len, n)
            assertTrue(src.contentEquals(back))
        }
        // Worst case for naive encoders: "x yy x yy …" must not expand beyond n + n/128 + 1.
        val n = 999
        val tricky = ByteArray(n) { i -> if (i % 3 == 0) (i / 3).toByte() else 200.toByte() }
        val dst = ByteArray(n + (n + 127) / 128 + 1)
        val len = packBits(tricky, n, dst)
        assertTrue("expanded to $len", len <= n + n / 128 + 1)
        assertTrue(tricky.contentEquals(unpackBits(dst, 0, len, n)))
    }

    // ---------- ICO ----------

    @Test
    fun icoStructure() {
        val sizes = listOf(16, 32, 48, 256)
        val images = sizes.map { s ->
            val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
            val px = photo(s, s, alpha = true)
            img.setRGB(0, 0, s, s, px, 0, s)
            val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
            IcoImage(s, png)
        }
        val bytes = encode { IcoWriter.write(images, it) }
        save("icon.ico", bytes)
        fun u16(o: Int) = (bytes[o].toInt() and 255) or ((bytes[o + 1].toInt() and 255) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        assertEquals(0, u16(0))
        assertEquals(1, u16(2))
        assertEquals(sizes.size, u16(4))
        sizes.forEachIndexed { i, s ->
            val e = 6 + i * 16
            assertEquals(if (s == 256) 0 else s, bytes[e].toInt() and 255)
            assertEquals(if (s == 256) 0 else s, bytes[e + 1].toInt() and 255)
            assertEquals(32, u16(e + 6))
            val len = u32(e + 8)
            val off = u32(e + 12)
            val img = ImageIO.read(ByteArrayInputStream(bytes, off, len))
            assertEquals(s, img.width)
            assertEquals(s, img.height)
        }
    }
}
