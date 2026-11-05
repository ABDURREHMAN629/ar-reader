package com.ahif.lumen.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahif.lumen.data.model.DocState
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.di.Graph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecentViewModel : ViewModel() {
    private val history = Graph.history
    private val stateStore = Graph.stateStore

    val items: StateFlow<List<LibraryItem>> =
        combine(history.entries, stateStore.states) { entries, states ->
            entries.map { LibraryItem(it, states[it.id] ?: DocState()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clear() = viewModelScope.launch { history.clear() }
    fun remove(id: String) = viewModelScope.launch { history.remove(id) }
    fun toggleFavorite(id: String) = viewModelScope.launch { stateStore.toggleFavorite(id) }
}

@Composable
fun RecentScreen(
    onOpenDocument: (LibraryItem) -> Unit,
    vm: RecentViewModel = viewModel()
) {
    val items by vm.items.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "History",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (items.isNotEmpty()) {
                IconButton(onClick = { vm.clear() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear history")
                }
            }
        }

        if (items.isEmpty()) {
            EmptyState(
                icon = { Icon(Icons.Filled.History, null, modifier = Modifier.size(64.dp)) },
                title = "No history yet",
                message = "Files you open — including ones opened from WhatsApp or Files — show up here."
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp)
            ) {
                items(items, key = { it.document.id }) { item ->
                    DocumentListRow(
                        item = item,
                        onOpen = { onOpenDocument(item) },
                        onToggleFavorite = { vm.toggleFavorite(item.document.id) }
                    )
                }
            }
        }
    }
}
