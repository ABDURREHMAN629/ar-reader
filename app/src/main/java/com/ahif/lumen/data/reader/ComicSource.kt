package com.ahif.lumen.data.reader

import android.content.Context
import android.net.Uri
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Reads image pages out of a comic archive. Supports CBZ/CBT (zip) and CBR (rar).
 * The archive is copied into the cache once so entries can be read at random.
 */
class ComicSource private constructor(
    private val cacheFile: File,
    private val zip: ZipFile?,
    private val zipPages: List<ZipEntry>?,
    private val archive: Archive?,
    private val rarPages: List<FileHeader>?
) {
    private val mutex = Mutex()

    val pageCount: Int = zipPages?.size ?: rarPages?.size ?: 0

    suspend fun pageBytes(index: Int): ByteArray? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                when {
                    zip != null && zipPages != null -> zip.getInputStream(zipPages[index]).use { it.readBytes() }
                    archive != null && rarPages != null -> {
                        val out = ByteArrayOutputStream()
                        archive.extractFile(rarPages[index], out)
                        out.toByteArray()
                    }
                    else -> null
                }
            }.getOrNull()
        }
    }

    fun close() {
        runCatching { zip?.close() }
        runCatching { archive?.close() }
        runCatching { cacheFile.delete() }
    }

    companion object {
        private val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        private fun isImage(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in imageExts

        suspend fun open(context: Context, uri: Uri, displayName: String): ComicSource? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val ext = displayName.substringAfterLast('.', "").lowercase()
                    val cache = File(context.cacheDir, "comic_" + uri.hashCode() + "." + ext)
                    if (!cache.exists() || cache.length() == 0L) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            cache.outputStream().use { output -> input.copyTo(output) }
                        } ?: return@withContext null
                    }

                    if (ext == "cbr") {
                        val archive = Archive(cache)
                        val pages = archive.fileHeaders
                            .filter { !it.isDirectory && isImage(it.fileName) }
                            .sortedWith { a, b -> compareNatural(a.fileName, b.fileName) }
                        ComicSource(cache, null, null, archive, pages)
                    } else {
                        val zip = ZipFile(cache)
                        val pages = zip.entries().toList()
                            .filter { !it.isDirectory && isImage(it.name) }
                            .sortedWith { a, b -> compareNatural(a.name, b.name) }
                        ComicSource(cache, zip, pages, null, null)
                    }
                }.getOrNull()
            }
    }
}
