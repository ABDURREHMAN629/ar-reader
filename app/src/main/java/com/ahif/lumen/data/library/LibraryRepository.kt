package com.ahif.lumen.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.ahif.lumen.data.model.Document
import com.ahif.lumen.data.model.DocumentType
import com.ahif.lumen.data.prefs.DocumentStateStore
import com.ahif.lumen.data.prefs.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Owns the scanned list of documents. When the app has All-files access it scans
 * shared storage device-wide (auto-discovery, ReadEra style); otherwise it falls
 * back to folders the user granted through the Storage Access Framework.
 * The last scan is cached to disk for instant startup.
 */
class LibraryRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    val stateStore: DocumentStateStore
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val cacheFile = File(context.filesDir, "library_cache.json")

    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    val documents: StateFlow<List<Document>> = _documents.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val cacheLoaded = CompletableDeferred<Unit>()

    /** Waits until the cached list is in [documents] (instant after the first launch). */
    suspend fun awaitCache() = cacheLoaded.await()

    fun loadCacheThenRefresh() {
        scope.launch {
            val cached = loadCache()
            if (cached.isNotEmpty()) _documents.value = cached
            cacheLoaded.complete(Unit)
            rescan()
        }
    }

    fun refresh() {
        scope.launch { rescan() }
    }

    /** Rename a document on disk (keeps its extension), then rescan. */
    fun rename(doc: Document, newBaseName: String, onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            val ext = doc.name.substringAfterLast('.', "")
            val cleaned = newBaseName.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { doc.name.substringBeforeLast('.') }
            val finalName = if (ext.isNotEmpty()) "$cleaned.$ext" else cleaned
            val ok = runCatching {
                when (doc.uri.scheme) {
                    "file" -> {
                        val f = File(doc.uri.path!!)
                        val target = File(f.parentFile, finalName)
                        f.renameTo(target)
                    }
                    else -> {
                        DocumentsContract.renameDocument(context.contentResolver, doc.uri, finalName) != null
                    }
                }
            }.getOrDefault(false)
            onDone(ok)
            rescan()
        }
    }

    /** Permanently delete the given documents from storage, then rescan. */
    fun deleteDocuments(docs: List<Document>, onDone: (Int) -> Unit = {}) {
        scope.launch {
            var deleted = 0
            docs.forEach { d ->
                val ok = runCatching {
                    when (d.uri.scheme) {
                        "file" -> d.uri.path?.let { File(it).delete() } ?: false
                        else -> DocumentsContract.deleteDocument(context.contentResolver, d.uri)
                    }
                }.getOrDefault(false)
                if (ok) deleted++
            }
            // Drop them from the in-memory list immediately for a snappy UI.
            val ids = docs.map { it.id }.toSet()
            _documents.value = _documents.value.filterNot { it.id in ids }
            saveCache(_documents.value)
            onDone(deleted)
            rescan()
        }
    }

    fun addFolder(treeUri: Uri) {
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            settings.addFolder(treeUri.toString())
            rescan()
        }
    }

    fun removeFolder(uri: String) {
        scope.launch {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            settings.removeFolder(uri)
            rescan()
        }
    }

    fun addFiles(uris: List<Uri>) {
        scope.launch {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            val added = uris.mapNotNull { FileScanner.documentFromUri(context, it) }
            if (added.isNotEmpty()) {
                val merged = mergeUnique(_documents.value, added)
                _documents.value = merged
                saveCache(merged)
            }
        }
    }

    private suspend fun rescan() {
        _scanning.value = true
        try {
            val all = ArrayList<Document>()
            if (StoragePermissions.hasAllFilesAccess(context)) {
                all += FileSystemScanner.scanExternal()
            } else {
                val folders = settings.settings.first().folders
                folders.forEach { folderUri ->
                    runCatching { FileScanner.scanTree(context, Uri.parse(folderUri)) }
                        .getOrNull()?.let { all += it }
                }
            }
            val merged = mergeUnique(emptyList(), all)
            _documents.value = merged
            saveCache(merged)
        } finally {
            _scanning.value = false
        }
    }

    private fun mergeUnique(base: List<Document>, extra: List<Document>): List<Document> {
        val map = LinkedHashMap<String, Document>()
        base.forEach { map[it.id] = it }
        extra.forEach { map[it.id] = it }
        return map.values.toList()
    }

    private fun loadCache(): List<Document> = runCatching {
        if (!cacheFile.exists()) return emptyList()
        val arr = JSONArray(cacheFile.readText())
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Document(
                        id = o.getString("id"),
                        uri = Uri.parse(o.getString("uri")),
                        name = o.getString("name"),
                        type = runCatching { DocumentType.valueOf(o.getString("type")) }.getOrDefault(DocumentType.UNKNOWN),
                        sizeBytes = o.optLong("size"),
                        lastModified = o.optLong("modified"),
                        folderName = o.optString("folder").ifBlank { null }
                    )
                )
            }
        }
    }.getOrElse { emptyList() }

    private suspend fun saveCache(list: List<Document>) = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            list.forEach { d ->
                arr.put(
                    JSONObject()
                        .put("id", d.id)
                        .put("uri", d.uri.toString())
                        .put("name", d.name)
                        .put("type", d.type.name)
                        .put("size", d.sizeBytes)
                        .put("modified", d.lastModified)
                        .put("folder", d.folderName ?: "")
                )
            }
            cacheFile.writeText(arr.toString())
        }
    }
}
