package com.ahif.lumen.data.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
import com.ahif.lumen.data.reader.PdfBuilder
import com.ahif.lumen.data.tools.encoders.BmpWriter
import com.ahif.lumen.data.tools.encoders.FlattenedSource
import com.ahif.lumen.data.tools.encoders.GifWriter
import com.ahif.lumen.data.tools.encoders.IcoImage
import com.ahif.lumen.data.tools.encoders.IcoWriter
import com.ahif.lumen.data.tools.encoders.PixelSource
import com.ahif.lumen.data.tools.encoders.PsdWriter
import com.ahif.lumen.data.tools.encoders.TgaWriter
import com.ahif.lumen.data.tools.encoders.TiffWriter
import com.ahif.lumen.data.tools.encoders.hasTransparency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.min
import kotlin.math.roundToInt

enum class TargetFormat(
    val label: String,
    val ext: String,
    val mime: String,
    val blurb: String,
    val lossy: Boolean,
    val keepsAlpha: Boolean
) {
    JPG("JPG", "jpg", "image/jpeg", "Photos — opens everywhere", true, false),
    PNG("PNG", "png", "image/png", "Lossless, keeps transparency", false, true),
    WEBP("WebP", "webp", "image/webp", "Small files for web & chat, keeps transparency", true, true),
    WEBP_LOSSLESS("WebP lossless", "webp", "image/webp", "Exact pixels, usually smaller than PNG", false, true),
    HEIC("HEIC", "heic", "image/heic", "iPhone photo format — about half the size of JPG", true, false),
    AVIF("AVIF", "avif", "image/avif", "Newest format, smallest photos", true, false),
    GIF("GIF", "gif", "image/gif", "256 colours, keeps transparency — logos & stickers", false, true),
    BMP("BMP", "bmp", "image/bmp", "Uncompressed Windows bitmap", false, false),
    TIFF("TIFF", "tiff", "image/tiff", "Lossless — for printing, scanning & archiving", false, true),
    ICO("ICO", "ico", "image/x-icon", "Windows & website icons (several sizes in one file)", false, true),
    TGA("TGA", "tga", "image/x-tga", "Games & 3D textures, keeps transparency", false, true),
    PSD("PSD", "psd", "image/vnd.adobe.photoshop", "Opens in Photoshop, Photopea & GIMP", false, true),
    PDF("PDF", "pdf", "application/pdf", "Document — one page per image", false, false);

    /** Android can show this format itself; the rest open in another app. */
    val viewableInApp: Boolean
        get() = this in setOf(JPG, PNG, WEBP, WEBP_LOSSLESS, HEIC, AVIF, GIF, BMP, ICO, PDF)
}

val ICON_SIZES = listOf(16, 24, 32, 48, 64, 128, 256)

data class ConvertOptions(
    val format: TargetFormat = TargetFormat.JPG,
    val quality: Int = 90,
    val resize: Resize = Resize.Original,
    /** Fills transparent areas for formats without transparency. */
    val background: Int = Color.WHITE,
    val dither: Boolean = true,
    val iconSizes: Set<Int> = setOf(16, 32, 48, 256),
    val keepDetails: Boolean = true,
    val removeLocation: Boolean = true,
    val combinePdf: Boolean = true
)

class ConvertResult(
    val source: ImageSource,
    val name: String,
    val uri: Uri?,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val error: String?
) {
    val ok: Boolean get() = uri != null
}

object ImageConverter {

    const val FOLDER = "Converted"

    private val encoderTypes: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder }
                .flatMap { it.supportedTypes.map { t -> t.lowercase() } }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** Null when the format can be written on this phone, otherwise why not. */
    fun unavailableReason(format: TargetFormat): String? = when (format) {
        TargetFormat.WEBP_LOSSLESS ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) "Needs Android 10 or newer" else null
        TargetFormat.HEIC -> when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "Needs Android 10 or newer"
            "image/vnd.android.heic" !in encoderTypes && "video/hevc" !in encoderTypes -> "This phone has no HEVC (H.265) encoder"
            else -> null
        }
        TargetFormat.AVIF -> when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "Needs Android 14 or newer"
            "video/av01" !in encoderTypes -> "This phone has no AV1 encoder, which AVIF needs"
            else -> null
        }
        else -> null
    }

    fun mimeOf(format: TargetFormat): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(format.ext) ?: format.mime

    private fun baseName(source: ImageSource) = source.name.substringBeforeLast('.').ifBlank { "image" }

    suspend fun convert(context: Context, source: ImageSource, options: ConvertOptions): ConvertResult =
        withContext(Dispatchers.Default) {
            val format = options.format
            val name = "${baseName(source)}.${format.ext}"
            fun fail(message: String) = ConvertResult(source, name, null, 0, 0, 0, message)
            unavailableReason(format)?.let { return@withContext fail(it) }

            val dir = File(context.cacheDir, "convert").apply { mkdirs() }
            val tmp = File(dir, "out_${System.nanoTime()}.${format.ext}")
            try {
                // Icons never need more than 256 px, so don't decode a 50 MP photo for one.
                val resize = if (format == TargetFormat.ICO) Resize.MaxSide(1024) else options.resize
                val bmp = ImageCompressor.decode(context, source.uri, resize)
                var width = bmp.width
                var height = bmp.height
                val pixels = BitmapPixels(bmp)
                // Only these writers choose between an RGB and an RGBA layout.
                val layoutChoice = format == TargetFormat.TIFF || format == TargetFormat.TGA || format == TargetFormat.PSD
                val alpha = layoutChoice && bmp.hasAlpha() && pixels.hasTransparency()
                val keepOrFlatten: PixelSource = if (alpha) pixels else FlattenedSource(pixels, options.background)

                when (format) {
                    TargetFormat.JPG -> compressTo(tmp, flatten(bmp, options.background), Bitmap.CompressFormat.JPEG, options.quality)
                    TargetFormat.PNG -> compressTo(tmp, bmp, Bitmap.CompressFormat.PNG, 100)
                    TargetFormat.WEBP -> compressTo(
                        tmp, bmp,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP,
                        options.quality
                    )
                    // Before Android 11, WEBP at quality 100 is the lossless mode (Android 10+).
                    TargetFormat.WEBP_LOSSLESS -> compressTo(
                        tmp, bmp,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS
                        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP,
                        100
                    )
                    TargetFormat.HEIC -> writeHeif(flatten(bmp, options.background), tmp, options.quality, avif = false)
                    TargetFormat.AVIF -> writeHeif(flatten(bmp, options.background), tmp, options.quality, avif = true)
                    TargetFormat.GIF -> stream(tmp) { GifWriter.write(pixels, it, options.dither) }
                    TargetFormat.BMP -> stream(tmp) { BmpWriter.write(FlattenedSource(pixels, options.background), it) }
                    TargetFormat.TIFF -> TiffWriter.write(keepOrFlatten, tmp, alpha)
                    TargetFormat.TGA -> stream(tmp) { TgaWriter.write(keepOrFlatten, it, alpha) }
                    TargetFormat.PSD -> stream(tmp) { PsdWriter.write(keepOrFlatten, it, alpha) }
                    TargetFormat.ICO -> {
                        val sizes = options.iconSizes.sorted().ifEmpty { listOf(256) }
                        val images = sizes.map { s -> IcoImage(s, iconPng(bmp, s)) }
                        stream(tmp) { IcoWriter.write(images, it) }
                        width = sizes.last()
                        height = sizes.last()
                    }
                    TargetFormat.PDF -> error("PDF goes through convertToPdf")
                }

                if (options.keepDetails && format in setOf(TargetFormat.JPG, TargetFormat.PNG, TargetFormat.WEBP, TargetFormat.WEBP_LOSSLESS)) {
                    ImageCompressor.copyDetails(context, source.uri, tmp, options.removeLocation)
                }
                val size = tmp.length()
                if (size <= 0) return@withContext fail("The encoder produced no data")
                val uri = MediaSaver.saveFile(context, tmp, name, mimeOf(format), FOLDER)
                    ?: return@withContext fail("Couldn't save to Pictures/AR Reader/$FOLDER")
                ConvertResult(source, name, uri, size, width, height, null)
            } catch (_: OutOfMemoryError) {
                fail("Image too large — choose a smaller size under Resize")
            } catch (e: Exception) {
                fail(e.message?.takeIf { it.isNotBlank() } ?: "Conversion failed")
            } finally {
                tmp.delete()
            }
        }

    /** PDFs go to Documents/AR Reader like the other PDF tools; one file, or one per image. */
    suspend fun convertToPdf(context: Context, sources: List<ImageSource>, combine: Boolean): List<ConvertResult> {
        if (sources.isEmpty()) return emptyList()
        val groups = if (combine && sources.size > 1) listOf(sources) else sources.map { listOf(it) }
        return groups.map { group ->
            val first = group.first()
            val base = if (group.size > 1) "${baseName(first)} and ${group.size - 1} more" else baseName(first)
            val file = runCatching { PdfBuilder.imagesToPdf(context, group.map { it.uri }, base) }.getOrNull()
            if (file != null) ConvertResult(first, file.name, Uri.fromFile(file), file.length(), 0, 0, null)
            else ConvertResult(first, "$base.pdf", null, 0, 0, 0, "Couldn't create the PDF")
        }
    }

    private fun compressTo(file: File, bmp: Bitmap, format: Bitmap.CompressFormat, quality: Int) {
        val ok = file.outputStream().buffered().use { bmp.compress(format, quality.coerceIn(0, 100), it) }
        check(ok) { "Android couldn't encode this image" }
    }

    private inline fun stream(file: File, block: (OutputStream) -> Unit) {
        file.outputStream().buffered(1 shl 16).use(block)
    }

    private fun flatten(src: Bitmap, background: Int): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(background or 0xFF000000.toInt())
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }

    private fun writeHeif(bmp: Bitmap, file: File, quality: Int, avif: Boolean) {
        val q = quality.coerceIn(0, 100)
        if (avif) {
            AvifWriter.Builder(file.absolutePath, bmp.width, bmp.height, AvifWriter.INPUT_MODE_BITMAP)
                .setQuality(q)
                .setMaxImages(1)
                .setGridEnabled(true)
                .build()
                .use { w ->
                    w.start()
                    w.addBitmap(bmp)
                    w.stop(HEIF_TIMEOUT_MS)
                }
        } else {
            HeifWriter.Builder(file.absolutePath, bmp.width, bmp.height, HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(q)
                .setMaxImages(1)
                .setGridEnabled(true)
                .build()
                .use { w ->
                    w.start()
                    w.addBitmap(bmp)
                    w.stop(HEIF_TIMEOUT_MS)
                }
        }
    }

    private const val HEIF_TIMEOUT_MS = 60_000L

    /** The whole image, fitted and centred on a transparent square. */
    private fun iconPng(src: Bitmap, size: Int): ByteArray {
        val scale = min(size.toFloat() / src.width, size.toFloat() / src.height)
        val w = (src.width * scale).roundToInt().coerceIn(1, size)
        val h = (src.height * scale).roundToInt().coerceIn(1, size)
        val scaled = scaleSmooth(src, w, h)
        val icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(icon).drawBitmap(scaled, ((size - w) / 2).toFloat(), ((size - h) / 2).toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return ByteArrayOutputStream().also { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Halves step by step before the final resize, so big downscales stay sharp without aliasing. */
    private fun scaleSmooth(src: Bitmap, w: Int, h: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= w && cur.height / 2 >= h) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        return if (cur.width == w && cur.height == h) cur else Bitmap.createScaledBitmap(cur, w, h, true)
    }
}

private class BitmapPixels(private val bmp: Bitmap) : PixelSource {
    override val width = bmp.width
    override val height = bmp.height
    override fun row(y: Int, out: IntArray) = bmp.getPixels(out, 0, width, 0, y, width, 1)
}
