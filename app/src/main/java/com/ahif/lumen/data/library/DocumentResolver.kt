package com.ahif.lumen.data.library

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ahif.lumen.data.model.Document
import com.ahif.lumen.data.prefs.DocumentStateStore
import com.ahif.lumen.data.prefs.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Apps like WhatsApp share files as temporary `content://` links that stop working
 * once that app's grant expires — so History entries saved with them later just spin.
 * This turns a shared document into a durable `file://` one: the same file found on
 * disk (library or the usual download/WhatsApp folders), or else a private copy.
 */
object DocumentResolver {

    private const val OPENED_DIR = "opened"
    private const val MAX_COPY_BYTES = 300L * 1024 * 1024

    /** Folder holding private copies of shared files; History cleans it up. */
    fun openedDir(context: Context): File = File(context.filesDir, OPENED_DIR)

    suspend fun makeDurable(context: Context, doc: Document, library: LibraryRepository): Document =
        withContext(Dispatchers.IO) {
            if (doc.uri.scheme == "file") return@withContext doc
            findOnDisk(context, doc, library) ?: copyToPrivate(context, doc) ?: doc
        }

    /**
     * One pass over History at startup: entries saved before this fix still hold shared
     * links that have since expired (no cover, can't open). Swap each for the same file
     * found on disk, carrying its reading progress over.
     */
    suspend fun repairHistory(
        context: Context,
        history: HistoryStore,
        stateStore: DocumentStateStore,
        library: LibraryRepository
    ) = withContext(Dispatchers.IO) {
        history.awaitLoaded()
        history.entries.value
            .filter { it.uri.scheme == "content" }
            .forEach { old ->
                if (isReadable(context, old)) return@forEach // e.g. a granted SAF file: fine as is
                val found = findOnDisk(context, old, library) ?: return@forEach
                stateStore.migrate(old.id, found.id)
                history.replace(old.id, found)
            }
    }

    /** True if the document's bytes can still be read right now. */
    suspend fun isReadable(context: Context, doc: Document): Boolean = withContext(Dispatchers.IO) {
        if (doc.uri.scheme == "file") return@withContext doc.uri.path?.let { File(it).canRead() } == true
        runCatching {
            context.contentResolver.openFileDescriptor(doc.uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private suspend fun findOnDisk(context: Context, doc: Document, library: LibraryRepository): Document? {
        if (doc.sizeBytes <= 0L) return null
        library.awaitCache()
        library.documents.value.firstOrNull { it.name == doc.name && it.sizeBytes == doc.sizeBytes }
            ?.let { return it }
        if (!StoragePermissions.hasAllFilesAccess(context)) return null
        val match = shareFolders().firstNotNullOfOrNull { findIn(it, doc, depth = 0) } ?: return null
        return fileDocument(match)
    }

    /** Where shared files usually live (WhatsApp keeps a real copy of every received document). */
    private fun shareFolders(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents",
            "WhatsApp/Media/WhatsApp Documents",
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS
        ).map { File(root, it) }.filter { it.isDirectory }
    }

    private fun findIn(dir: File, doc: Document, depth: Int): File? {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val ext = doc.name.substringAfterLast('.', "").lowercase()
        // Exact name first; WhatsApp sometimes adds " (1)" on a name clash, so then same size + stem.
        children.firstOrNull { it.isFile && it.name == doc.name && it.length() == doc.sizeBytes }?.let { return it }
        children.firstOrNull {
            it.isFile && it.length() == doc.sizeBytes &&
                it.extension.lowercase() == ext && it.name.startsWith(doc.title)
        }?.let { return it }
        if (depth >= 2) return null
        return children.asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .firstNotNullOfOrNull { findIn(it, doc, depth + 1) }
    }

    private fun copyToPrivate(context: Context, doc: Document): Document? = runCatching {
        if (doc.sizeBytes > MAX_COPY_BYTES) return null
        val dir = openedDir(context).apply { mkdirs() }
        val safeName = doc.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val target = uniqueTarget(dir, safeName, doc.sizeBytes)
        if (!(target.exists() && target.length() == doc.sizeBytes && doc.sizeBytes > 0)) {
            val tmp = File(dir, target.name + ".part")
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
            } ?: return null
            if (!tmp.renameTo(target)) { tmp.delete(); return null }
        }
        fileDocument(target).copy(folderName = "Shared files")
    }.getOrNull()

    /** Reuses an existing copy of the same size, else picks "name (2).ext" etc. */
    private fun uniqueTarget(dir: File, name: String, size: Long): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (true) {
            val f = File(dir, if (n == 1) name else "$base ($n)$ext")
            if (!f.exists() || f.length() == size) return f
            n++
        }
    }

    private fun fileDocument(file: File): Document {
        val uri = Uri.fromFile(file)
        return Document(
            id = uri.toString(),
            uri = uri,
            name = file.name,
            type = com.ahif.lumen.data.model.DocumentType.fromName(file.name),
            sizeBytes = file.length(),
            lastModified = file.lastModified(),
            folderName = file.parentFile?.name
        )
    }
}
