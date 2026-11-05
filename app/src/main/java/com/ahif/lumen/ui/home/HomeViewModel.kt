package com.ahif.lumen.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahif.lumen.data.model.DocState
import com.ahif.lumen.data.model.Document
import com.ahif.lumen.data.model.DocumentType
import com.ahif.lumen.data.model.FormatFilter
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.model.SortOption
import com.ahif.lumen.data.model.ViewMode
import com.ahif.lumen.data.prefs.AppSettings
import com.ahif.lumen.di.Graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val library = Graph.library
    private val settings = Graph.settings
    private val stateStore = Graph.stateStore

    val searchQuery = MutableStateFlow("")
    val formatFilter = MutableStateFlow(FormatFilter.ALL)

    val scanning: StateFlow<Boolean> = library.scanning

    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val libraryItems = combine(library.documents, stateStore.states) { docs, states ->
        docs.map { LibraryItem(it, states[it.id] ?: DocState()) }
    }

    val items: StateFlow<List<LibraryItem>> =
        combine(libraryItems, searchQuery, formatFilter, settings.settings) { list, query, filter, s ->
            list.asSequence()
                .filter {
                    // "All" excludes images — they live under the Images filter only.
                    if (filter == FormatFilter.ALL) it.document.type != DocumentType.IMAGE
                    else it.document.type in filter.types
                }
                .filter { matchesQuery(it.document, query) }
                .sortedWith(comparatorFor(s.sort))
                .toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Started, unfinished documents — from the library and from History (WhatsApp files etc.). */
    val continueReading: StateFlow<List<LibraryItem>> =
        combine(library.documents, Graph.history.entries, stateStore.states) { docs, history, states ->
            val reading = states.filterValues { it.started && !it.finished }
            if (reading.isEmpty()) return@combine emptyList()
            val found = LinkedHashMap<String, Document>()
            history.forEach { if (it.id in reading) found[it.id] = it }
            docs.forEach { if (it.id in reading && it.id !in found) found[it.id] = it }
            found.values
                .filter { it.type != DocumentType.IMAGE }
                .map { LibraryItem(it, reading.getValue(it.id)) }
                .sortedByDescending { it.state.lastOpened }
                .take(12)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<LibraryItem>> =
        combine(libraryItems, searchQuery) { list, query ->
            list.filter { it.state.favorite }
                .filter { matchesQuery(it.document, query) }
                .sortedByDescending { it.state.lastOpened }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun matchesQuery(doc: Document, query: String): Boolean {
        if (query.isBlank()) return true
        return doc.name.contains(query, ignoreCase = true) ||
            (doc.folderName?.contains(query, ignoreCase = true) == true)
    }

    private fun comparatorFor(sort: SortOption): Comparator<LibraryItem> = when (sort) {
        SortOption.RECENT -> compareByDescending { it.state.lastOpened }
        SortOption.NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.document.name }
        SortOption.NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.document.name }
        SortOption.DATE_NEW -> compareByDescending { it.document.lastModified }
        SortOption.DATE_OLD -> compareBy { it.document.lastModified }
        SortOption.SIZE_DESC -> compareByDescending { it.document.sizeBytes }
        SortOption.SIZE_ASC -> compareBy { it.document.sizeBytes }
        SortOption.TYPE -> compareBy<LibraryItem> { it.document.type.ordinal }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.document.name }
    }

    fun setSearch(query: String) { searchQuery.value = query }
    fun setFilter(filter: FormatFilter) { formatFilter.value = filter }

    fun setViewMode(mode: ViewMode) = viewModelScope.launch { settings.setViewMode(mode) }
    fun setSort(sort: SortOption) = viewModelScope.launch { settings.setSort(sort) }

    fun addFolder(uri: Uri) = library.addFolder(uri)
    fun addFiles(uris: List<Uri>) = library.addFiles(uris)
    fun removeFolder(uri: String) = library.removeFolder(uri)
    fun refresh() = library.refresh()

    fun toggleFavorite(id: String) = viewModelScope.launch { stateStore.toggleFavorite(id) }

    fun delete(ids: Set<String>, onDone: (Int) -> Unit = {}) {
        val docs = library.documents.value.filter { it.id in ids }
        library.deleteDocuments(docs, onDone)
    }

    fun rename(id: String, newBaseName: String) {
        val doc = library.documents.value.firstOrNull { it.id == id } ?: return
        library.rename(doc, newBaseName)
    }
}
