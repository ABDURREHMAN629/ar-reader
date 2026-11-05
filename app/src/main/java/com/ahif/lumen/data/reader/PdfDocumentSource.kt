package com.ahif.lumen.data.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps [PdfRenderer]. Only one page may be open at a time, so all rendering is
 * serialised through a mutex. Falls back to copying the file into the cache when
 * the content provider hands back a non-seekable descriptor.
 */
class PdfDocumentSource private constructor(
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor,
    private val cacheFile: File?
) {
    val pageCount: Int = renderer.pageCount
    private val mutex = Mutex()
    private var closed = false

    // Page sizes never change, and opening a page is the slow part — remember them.
    private val aspects = FloatArray(pageCount)

    // Recently rendered pages, so scrolling back or re-zooming doesn't re-render (or flash blank).
    private val bitmaps = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Already-known aspect ratio (height / width), or null if the page hasn't been measured yet. */
    fun knownAspect(index: Int): Float? = aspects.getOrNull(index)?.takeIf { it > 0f }

    /** A rendered page already in memory at exactly this width, without any waiting. */
    fun cachedPage(index: Int, targetWidthPx: Int): Bitmap? = bitmaps.get(key(index, targetWidthPx))

    /** Aspect ratio (height / width) of a page, for placeholder sizing. */
    suspend fun pageAspect(index: Int): Float {
        knownAspect(index)?.let { return it }
        return mutex.withLock {
            if (closed) return 1.4f
            withContext(Dispatchers.IO) {
                runCatching {
                    renderer.openPage(index).use { it.height.toFloat() / it.width.toFloat() }
                }.getOrDefault(1.4f).also { if (index in aspects.indices) aspects[index] = it }
            }
        }
    }

    suspend fun renderPage(index: Int, targetWidthPx: Int): Bitmap? {
        val width = targetWidthPx.coerceIn(320, 3000)
        bitmaps.get(key(index, width))?.let { return it }
        return mutex.withLock {
            if (closed || index !in 0 until pageCount) return null
            bitmaps.get(key(index, width))?.let { return it }
            withContext(Dispatchers.IO) {
                runCatching {
                    renderer.openPage(index).use { page ->
                        aspects[index] = page.height.toFloat() / page.width.toFloat()
                        val height = (width * aspects[index]).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }.getOrNull()?.also { bitmaps.put(key(index, width), it) }
            }
        }
    }

    private fun key(index: Int, width: Int) = "$index:${width.coerceIn(320, 3000)}"

    fun close() {
        if (closed) return
        closed = true
        bitmaps.evictAll()
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        cacheFile?.let { runCatching { it.delete() } }
    }

    companion object {
        /** About an eighth of the app's heap for rendered pages (a few screens' worth). */
        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 8).coerceIn(16L * 1024 * 1024, 128L * 1024 * 1024).toInt()

        suspend fun open(context: Context, uri: Uri): PdfDocumentSource? =
            (load(context, uri, null) as? PdfOpenResult.Opened)?.source

        /**
         * Opens the PDF, using [password] for password-protected files. Tells apart
         * "needs a password", "wrong password" and "can't be read at all".
         */
        suspend fun load(context: Context, uri: Uri, password: String?): PdfOpenResult = withContext(Dispatchers.IO) {
            var locked = false
            fun attempt(pfd: ParcelFileDescriptor, cacheFile: File?): PdfDocumentSource? = try {
                PdfDocumentSource(newRenderer(pfd, password), pfd, cacheFile)
            } catch (e: SecurityException) {
                locked = true // PdfRenderer's way of saying the file is encrypted
                runCatching { pfd.close() }
                null
            } catch (e: Exception) {
                runCatching { pfd.close() }
                null
            }

            // Try the direct (seekable) descriptor first.
            runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                ?.let { pfd -> attempt(pfd, null)?.let { return@withContext PdfOpenResult.Opened(it) } }
            if (locked) return@withContext lockedResult(password)

            // Fallback: copy into cache and open a file-backed descriptor.
            val cache = File(context.cacheDir, "pdf_" + uri.hashCode().toString() + ".pdf")
            val copied = runCatching {
                if (!cache.exists() || cache.length() == 0L) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        cache.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching false
                }
                true
            }.getOrDefault(false)
            if (!copied) return@withContext PdfOpenResult.Failed
            val cpfd = runCatching { ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
                ?: return@withContext PdfOpenResult.Failed
            attempt(cpfd, cache)?.let { return@withContext PdfOpenResult.Opened(it) }
            cache.delete()
            if (locked) lockedResult(password) else PdfOpenResult.Failed
        }

        private fun lockedResult(password: String?): PdfOpenResult = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM -> PdfOpenResult.PasswordUnsupported
            password == null -> PdfOpenResult.NeedsPassword
            else -> PdfOpenResult.WrongPassword
        }

        private fun newRenderer(pfd: ParcelFileDescriptor, password: String?): PdfRenderer =
            if (password != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                PdfRenderer(pfd, LoadParams.Builder().setPassword(password).build())
            } else {
                PdfRenderer(pfd)
            }
    }
}

sealed interface PdfOpenResult {
    class Opened(val source: PdfDocumentSource) : PdfOpenResult
    /** The PDF is encrypted; ask the user for its password. */
    data object NeedsPassword : PdfOpenResult
    data object WrongPassword : PdfOpenResult
    /** Encrypted, but this Android version (before 15) can't open protected PDFs. */
    data object PasswordUnsupported : PdfOpenResult
    data object Failed : PdfOpenResult
}
