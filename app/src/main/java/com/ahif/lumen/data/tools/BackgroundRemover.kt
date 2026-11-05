package com.ahif.lumen.data.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cuts the main subject (person, pet, object…) out of a photo on-device using
 * ML Kit Subject Segmentation. The model lives in Google Play services and is
 * fetched once on first use; images never leave the phone.
 */
object BackgroundRemover {

    private val segmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
    }

    /** Makes sure the model is installed. [onProgress] receives 0..1 while downloading. */
    suspend fun ensureModel(context: Context, onProgress: (Float) -> Unit): Boolean {
        val client = ModuleInstall.getClient(context)
        val available = runCatching { client.areModulesAvailable(segmenter).awaitResult().areModulesAvailable() }
            .getOrDefault(false)
        if (available) return true

        val done = CompletableDeferred<Boolean>()
        val listener = InstallStatusListener { update ->
            update.progressInfo?.let { p ->
                if (p.totalBytesToDownload > 0) onProgress(p.bytesDownloaded.toFloat() / p.totalBytesToDownload)
            }
            when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> done.complete(true)
                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> done.complete(false)
            }
        }
        return try {
            val request = ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build()
            val response = client.installModules(request).awaitResult()
            if (response.areModulesAlreadyInstalled()) true
            else withTimeoutOrNull(180_000) { done.await() } ?: false
        } catch (e: Exception) {
            false
        } finally {
            runCatching { client.unregisterListener(listener) }
        }
    }

    /** The subject on a transparent background, same size as [bitmap]; null if none was found. */
    suspend fun cutOut(bitmap: Bitmap): Bitmap? = runCatching {
        segmenter.process(InputImage.fromBitmap(bitmap, 0)).awaitResult().foregroundBitmap
    }.getOrNull()
}

sealed interface Backdrop {
    data object Transparent : Backdrop
    data class Solid(val argb: Int) : Backdrop
    data class Gradient(val colors: List<Int>) : Backdrop
    data class Blur(val strength: Int) : Backdrop
    data class Photo(val bitmap: Bitmap) : Backdrop
}

object BackdropComposer {

    suspend fun compose(foreground: Bitmap, original: Bitmap, backdrop: Backdrop): Bitmap =
        withContext(Dispatchers.Default) {
            val w = foreground.width
            val h = foreground.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val full = Rect(0, 0, w, h)
            when (backdrop) {
                Backdrop.Transparent -> Unit
                is Backdrop.Solid -> canvas.drawColor(backdrop.argb)
                is Backdrop.Gradient -> {
                    paint.shader = LinearGradient(
                        0f, 0f, w.toFloat(), h.toFloat(),
                        backdrop.colors.toIntArray(), null, Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                    paint.shader = null
                }
                is Backdrop.Blur -> canvas.drawBitmap(blur(original, backdrop.strength), null, full, paint)
                is Backdrop.Photo -> canvas.drawBitmap(backdrop.bitmap, centerCrop(backdrop.bitmap, w, h), full, paint)
            }
            canvas.drawBitmap(foreground, 0f, 0f, paint)
            out
        }

    /** Source rect of [bmp] that fills a [w]×[h] frame without stretching. */
    private fun centerCrop(bmp: Bitmap, w: Int, h: Int): Rect {
        val target = w.toFloat() / h
        val src = bmp.width.toFloat() / bmp.height
        return if (src > target) {
            val cw = (bmp.height * target).toInt()
            val x = (bmp.width - cw) / 2
            Rect(x, 0, x + cw, bmp.height)
        } else {
            val ch = (bmp.width / target).toInt()
            val y = (bmp.height - ch) / 2
            Rect(0, y, bmp.width, y + ch)
        }
    }

    /** Soft blur: shrink, box-blur the small copy, then scale back up with filtering. */
    fun blur(src: Bitmap, strength: Int): Bitmap {
        val factor = (4 + strength.coerceIn(1, 10) * 3)
        val sw = (src.width / factor).coerceAtLeast(4)
        val sh = (src.height / factor).coerceAtLeast(4)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(2) { boxBlur(px, sw, sh, 2) }
        val blurred = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(blurred, src.width, src.height, true)
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        for (o in 0 until outer) {
            for (i in 0 until inner) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, inner - 1)
                    val c = if (horizontal) src[o * w + j] else src[j * w + o]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                val idx = if (horizontal) o * w + i else i * w + o
                dst[idx] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
