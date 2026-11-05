package com.ahif.lumen.data.tools

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class OutFormat(val label: String, val ext: String, val mime: String) {
    JPEG("JPG", "jpg", "image/jpeg"),
    WEBP("WebP", "webp", "image/webp"),
    PNG("PNG", "png", "image/png")
}

sealed interface Resize {
    data object Original : Resize
    data class Percent(val percent: Int) : Resize
    data class MaxSide(val px: Int) : Resize
}

data class CompressOptions(
    val format: OutFormat = OutFormat.JPEG,
    val quality: Int = 85,
    /** When set, quality (and if needed size) is searched so the file fits this many KB. */
    val targetKb: Int? = null,
    val resize: Resize = Resize.Original,
    /** Copy date/camera EXIF into the output (JPG only). */
    val keepDetails: Boolean = true,
    /** Drop GPS tags even when keeping other details. */
    val removeLocation: Boolean = true
)

data class ImageSource(val uri: Uri, val name: String, val bytes: Long, val width: Int, val height: Int)

class CompressedImage(
    val source: ImageSource,
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val quality: Int
) {
    val savedPercent: Int
        get() = if (source.bytes > 0) ((1.0 - data.size.toDouble() / source.bytes) * 100).roundToInt() else 0
}

object ImageCompressor {

    /** Reads name, file size and (orientation-corrected) dimensions without decoding pixels. */
    suspend fun inspect(context: Context, uri: Uri): ImageSource? = withContext(Dispatchers.IO) {
        runCatching {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
            var size = 0L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { name = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
            }
            name = realName(context, uri) ?: name
            if (size <= 0) size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var w = bounds.outWidth
            var h = bounds.outHeight
            if (w <= 0 || h <= 0) return@runCatching null
            if (rotationOf(context, uri) % 180 != 0) { val t = w; w = h; h = t }
            ImageSource(uri, name, size, w, h)
        }.getOrNull()
    }

    /**
     * The photo picker hides file names (a photo shows up as "73041.jpg"). Look the real name up
     * in MediaStore when the app may read it, else build one from the capture time.
     */
    private fun realName(context: Context, uri: Uri): String? {
        if (uri.authority != MediaStore.AUTHORITY || uri.pathSegments.none { it.startsWith("picker") }) return null
        val id = uri.lastPathSegment?.toLongOrNull()
        if (id != null) runCatching {
            val media = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            context.contentResolver.query(media, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    val taken = c.getLong(0)
                    if (taken > 0) "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(taken)) else null
                } else null
            }
        }.getOrNull()
    }

    fun targetSize(w: Int, h: Int, resize: Resize): Pair<Int, Int> = when (resize) {
        Resize.Original -> w to h
        is Resize.Percent -> (w * resize.percent / 100).coerceAtLeast(1) to (h * resize.percent / 100).coerceAtLeast(1)
        is Resize.MaxSide -> {
            val longest = max(w, h)
            if (longest <= resize.px) w to h
            else { val s = resize.px.toFloat() / longest; (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1) }
        }
    }

    /** Decodes upright (EXIF orientation applied), scaled per [resize], as a software ARGB_8888 bitmap. */
    suspend fun decode(context: Context, uri: Uri, resize: Resize): Bitmap = withContext(Dispatchers.IO) {
        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (tw, th) = targetSize(info.size.width, info.size.height, resize)
                if (tw != info.size.width || th != info.size.height) decoder.setTargetSize(tw, th)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else legacyDecode(context, uri, resize)
        if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun legacyDecode(context: Context, uri: Uri, resize: Resize): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val rotation = rotationOf(context, uri)
        val (tw, th) = targetSize(bounds.outWidth, bounds.outHeight, resize)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts)!! }
        if (bmp.width != tw || bmp.height != th) bmp = Bitmap.createScaledBitmap(bmp, tw, th, true)
        if (rotation != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        return bmp
    }

    private fun rotationOf(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
    }.getOrDefault(0)

    fun encode(bmp: Bitmap, format: OutFormat, quality: Int): ByteArray {
        val cf = when (format) {
            OutFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutFormat.PNG -> Bitmap.CompressFormat.PNG
            OutFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        val out = ByteArrayOutputStream()
        bmp.compress(cf, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }

    /** JPG has no transparency; flatten onto white so transparent areas don't turn black. */
    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return out
    }

    suspend fun compress(context: Context, source: ImageSource, options: CompressOptions): CompressedImage {
        var bmp = decode(context, source.uri, options.resize)
        return withContext(Dispatchers.Default) {
            if (options.format == OutFormat.JPEG && bmp.hasAlpha()) bmp = flattenOnWhite(bmp)
            val targetKb = options.targetKb
            if (targetKb == null) {
                return@withContext CompressedImage(source, encode(bmp, options.format, options.quality), bmp.width, bmp.height, options.quality)
            }
            val target = targetKb * 1024L
            var current = bmp
            var result = fitQuality(current, options.format, target)
            var attempts = 0
            // Quality alone couldn't reach the target: shrink dimensions a step at a time.
            while (result.first.size > target && attempts < 6 && current.width > 200 && current.height > 200) {
                val scale = sqrt(target.toDouble() / result.first.size) * 0.95
                current = Bitmap.createScaledBitmap(
                    current,
                    (current.width * scale).roundToInt().coerceAtLeast(1),
                    (current.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
                result = fitQuality(current, options.format, target)
                attempts++
            }
            CompressedImage(source, result.first, current.width, current.height, result.second)
        }
    }

    /** Highest quality whose output fits [target] bytes (binary search). */
    private fun fitQuality(bmp: Bitmap, format: OutFormat, target: Long): Pair<ByteArray, Int> {
        if (format == OutFormat.PNG) return encode(bmp, format, 100) to 100
        val top = encode(bmp, format, 95)
        if (top.size <= target) return top to 95
        var lo = 5
        var hi = 94
        var best: ByteArray? = null
        var bestQ = 5
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val data = encode(bmp, format, mid)
            if (data.size <= target) { best = data; bestQ = mid; lo = mid + 1 } else hi = mid - 1
        }
        return (best ?: encode(bmp, format, 5)) to bestQ
    }

    /** Saves to Pictures/AR Reader/Compressed, copying photo details if requested. */
    suspend fun save(context: Context, image: CompressedImage, options: CompressOptions): Uri? {
        val base = image.source.name.substringBeforeLast('.').ifBlank { "image" }
        val name = "${base}_compressed.${options.format.ext}"
        var bytes = image.data
        if (options.keepDetails && options.format == OutFormat.JPEG) {
            bytes = withContext(Dispatchers.IO) { copyExif(context, image.source.uri, bytes, options.removeLocation) } ?: bytes
        }
        return MediaSaver.save(context, bytes, name, options.format.mime, "Compressed")
    }

    private fun copyExif(context: Context, sourceUri: Uri, jpeg: ByteArray, removeLocation: Boolean): ByteArray? {
        val tmp = File.createTempFile("exif", ".jpg", context.cacheDir)
        try {
            tmp.writeBytes(jpeg)
            return if (copyDetails(context, sourceUri, tmp, removeLocation)) tmp.readBytes() else null
        } finally {
            tmp.delete()
        }
    }

    /** Copies date/camera EXIF from [sourceUri] into an existing JPG, PNG or WebP [target]. */
    internal fun copyDetails(context: Context, sourceUri: Uri, target: File, removeLocation: Boolean): Boolean = runCatching {
        val src = context.contentResolver.openInputStream(sourceUri)?.use { ExifInterface(it) } ?: return@runCatching false
        val dst = ExifInterface(target.absolutePath)
        val tags = mutableListOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION
        )
        if (!removeLocation) tags += listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP
        )
        tags.forEach { t -> src.getAttribute(t)?.let { dst.setAttribute(t, it) } }
        // Pixels are already upright, so reset orientation to avoid a double rotation.
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        dst.saveAttributes()
        true
    }.getOrDefault(false)
}
