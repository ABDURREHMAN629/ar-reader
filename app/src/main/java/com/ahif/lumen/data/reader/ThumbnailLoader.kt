package com.ahif.lumen.data.reader

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.ahif.lumen.data.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Generates and caches small preview bitmaps (currently PDF first-page previews). */
object ThumbnailLoader {

    private val cache = LruCache<String, Bitmap>(80)
    private val misses = HashSet<String>()
    private val mutex = Mutex()

    suspend fun pdfThumbnail(context: Context, doc: Document): Bitmap? {
        cache.get(doc.id)?.let { return it }
        if (doc.id in misses) return null
        return mutex.withLock {
            cache.get(doc.id)?.let { return it }
            if (doc.id in misses) return null
            val bitmap = withContext(Dispatchers.IO) {
                val source = PdfDocumentSource.open(context, doc.uri) ?: return@withContext null
                try {
                    source.renderPage(0, 360)
                } finally {
                    source.close()
                }
            }
            if (bitmap != null) cache.put(doc.id, bitmap) else misses.add(doc.id)
            bitmap
        }
    }
}
