package com.ahif.lumen.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahif.lumen.data.model.LibraryItem

@Composable
fun FavoritesScreen(
    onOpenDocument: (LibraryItem) -> Unit,
    vm: HomeViewModel = viewModel()
) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Favourites",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp)
        )
        if (favorites.isEmpty()) {
            EmptyState(
                icon = { Icon(Icons.Filled.FavoriteBorder, null, modifier = Modifier.size(64.dp)) },
                title = "No favourites yet",
                message = "Tap the heart on any book or document to keep it here."
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(favorites, key = { it.document.id }) { item ->
                    DocumentGridCard(
                        item = item,
                        onOpen = { onOpenDocument(item) },
                        onToggleFavorite = { vm.toggleFavorite(item.document.id) }
                    )
                }
            }
        }
    }
}
