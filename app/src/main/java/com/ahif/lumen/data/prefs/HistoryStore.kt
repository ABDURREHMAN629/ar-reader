package com.ahif.lumen.data.prefs

import android.content.Context
import android.net.Uri
import com.ahif.lumen.data.library.DocumentResolver
import com.ahif.lumen.data.model.Document
import com.ahif.lumen.data.model.DocumentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent log of every document the user has opened — from the library or from
 * other apps (WhatsApp, Files…). Stores enough metadata to re-open and to display.
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val openedDir = DocumentResolver.openedDir(context)
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _entries = MutableStateFlow<List<Document>>(emptyList())
    val entries: StateFlow<List<Document>> = _entries.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            _entries.value = load()
            loaded.complete(Unit)
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    /** Swaps the entry [oldId] for [doc] in place (same position in the list). */
    suspend fun replace(oldId: String, doc: Document) = mutex.withLock {
        loaded.await()
        val next = _entries.value.map { if (it.id == oldId) doc else it }.distinctBy { it.id }
        _entries.value = next
        persist(next)
    }

    private fun load(): List<Document> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uri = Uri.parse(o.getString("uri"))
                add(
                    Document(
                        id = o.getString("id"),
                        uri = uri,
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

    private suspend fun persist(list: List<Document>) = withContext(Dispatchers.IO) {
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
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        }
    }

    /**
     * Move [doc] to the top of history (most-recently opened first). [replaces] is the id
     * of an older entry for the same file (e.g. its expired shared link), dropped here.
     */
    suspend fun record(doc: Document, replaces: String? = null) = mutex.withLock {
        loaded.await() // never persist a partial list over the saved file
        val next = (listOf(doc) + _entries.value.filterNot { it.id == doc.id || it.id == replaces }).take(300)
        _entries.value = next
        persist(next)
        pruneCopies(next)
    }

    suspend fun remove(id: String) = mutex.withLock {
        loaded.await()
        val next = _entries.value.filterNot { it.id == id }
        _entries.value = next
        persist(next)
        pruneCopies(next)
    }

    suspend fun clear() = mutex.withLock {
        loaded.await()
        _entries.value = emptyList()
        persist(emptyList())
        pruneCopies(emptyList())
    }

    /** Deletes private copies of shared files that are no longer in history. */
    private suspend fun pruneCopies(kept: List<Document>) = withContext(Dispatchers.IO) {
        val keep = kept.mapNotNull { it.uri.path }.toSet()
        val now = System.currentTimeMillis()
        openedDir.listFiles()?.forEach { f ->
            // Leave just-written copies alone: they may be about to be recorded.
            if (f.absolutePath !in keep && now - f.lastModified() > 60_000) f.delete()
        }
    }
}
