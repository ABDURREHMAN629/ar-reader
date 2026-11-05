package com.ahif.lumen.data.prefs

import android.content.Context
import com.ahif.lumen.data.model.Bookmark
import com.ahif.lumen.data.model.DocState
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
 * Lightweight persistence for per-document reading state (progress, bookmarks,
 * favorites) backed by a single JSON file in the app's private storage.
 * Keyed by the document's content-URI string.
 */
class DocumentStateStore(context: Context) {

    private val file = File(context.filesDir, "doc_states.json")
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, DocState>>(emptyMap())
    val states: StateFlow<Map<String, DocState>> = _states.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            _states.value = load()
            loaded.complete(Unit)
        }
    }

    /** Waits for the file to be read, so a cold start (e.g. opened from WhatsApp) still resumes. */
    suspend fun awaitLoaded() = loaded.await()

    private fun load(): Map<String, DocState> = runCatching {
        if (!file.exists()) return emptyMap()
        val root = JSONObject(file.readText())
        buildMap {
            root.keys().forEach { key ->
                val o = root.getJSONObject(key)
                val bookmarks = mutableListOf<Bookmark>()
                o.optJSONArray("bookmarks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val b = arr.getJSONObject(i)
                        bookmarks += Bookmark(
                            unit = b.optInt("unit"),
                            label = b.optString("label"),
                            createdAt = b.optLong("createdAt")
                        )
                    }
                }
                put(
                    key,
                    DocState(
                        progress = o.optDouble("progress", 0.0).toFloat(),
                        positionUnit = o.optInt("positionUnit"),
                        positionOffset = o.optInt("positionOffset"),
                        totalUnits = o.optInt("totalUnits"),
                        lastOpened = o.optLong("lastOpened"),
                        favorite = o.optBoolean("favorite"),
                        zoom = o.optDouble("zoom", 1.0).toFloat(),
                        readingTheme = o.optString("readingTheme").ifBlank { null },
                        bookmarks = bookmarks
                    )
                )
            }
        }
    }.getOrElse { emptyMap() }

    private suspend fun persist(map: Map<String, DocState>) = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject()
            map.forEach { (key, s) ->
                val o = JSONObject()
                    .put("progress", s.progress.toDouble())
                    .put("positionUnit", s.positionUnit)
                    .put("positionOffset", s.positionOffset)
                    .put("totalUnits", s.totalUnits)
                    .put("lastOpened", s.lastOpened)
                    .put("favorite", s.favorite)
                    .put("zoom", s.zoom.toDouble())
                s.readingTheme?.let { o.put("readingTheme", it) }
                val arr = JSONArray()
                s.bookmarks.forEach { b ->
                    arr.put(
                        JSONObject()
                            .put("unit", b.unit)
                            .put("label", b.label)
                            .put("createdAt", b.createdAt)
                    )
                }
                o.put("bookmarks", arr)
                root.put(key, o)
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        }
    }

    fun get(id: String): DocState = _states.value[id] ?: DocState()

    suspend fun update(id: String, transform: (DocState) -> DocState) = mutex.withLock {
        loaded.await() // never persist a partial map over the saved file
        val current = _states.value
        val newState = transform(current[id] ?: DocState())
        val next = current + (id to newState)
        _states.value = next
        persist(next)
    }

    suspend fun toggleFavorite(id: String) = update(id) { it.copy(favorite = !it.favorite) }

    suspend fun saveZoom(id: String, zoom: Float) = update(id) { it.copy(zoom = zoom) }

    suspend fun saveReadingTheme(id: String, themeId: String) = update(id) { it.copy(readingTheme = themeId) }

    /** Carries reading state over when a document's id changes (a shared link resolved to a real file). */
    suspend fun migrate(fromId: String, toId: String) = mutex.withLock {
        loaded.await()
        val current = _states.value
        val old = current[fromId] ?: return@withLock
        if (current[toId]?.started == true) return@withLock
        val next = current + (toId to old)
        _states.value = next
        persist(next)
    }

    suspend fun saveProgress(id: String, progress: Float, unit: Int, offset: Int, total: Int) =
        update(id) {
            it.copy(
                progress = progress.coerceIn(0f, 1f),
                positionUnit = unit,
                positionOffset = offset,
                totalUnits = total,
                lastOpened = System.currentTimeMillis()
            )
        }

    suspend fun addBookmark(id: String, bookmark: Bookmark) = update(id) {
        it.copy(bookmarks = it.bookmarks + bookmark)
    }

    suspend fun removeBookmark(id: String, bookmark: Bookmark) = update(id) {
        it.copy(bookmarks = it.bookmarks.filterNot { b -> b.unit == bookmark.unit && b.createdAt == bookmark.createdAt })
    }
}
