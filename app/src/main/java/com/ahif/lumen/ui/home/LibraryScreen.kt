package com.ahif.lumen.ui.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahif.lumen.data.library.StoragePermissions
import com.ahif.lumen.data.model.FormatFilter
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.model.SortOption
import com.ahif.lumen.data.model.ViewMode
import com.ahif.lumen.data.model.folderDisplayName
import com.ahif.lumen.data.model.folderKey
import com.ahif.lumen.data.model.folderPathPretty
import com.ahif.lumen.ui.util.DocumentActions
import androidx.compose.ui.platform.LocalContext

private data class FolderGroup(val key: String, val name: String, val path: String, val count: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenDocument: (LibraryItem) -> Unit,
    vm: HomeViewModel = viewModel()
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val continueReading by vm.continueReading.collectAsStateWithLifecycle()
    val settings by vm.appSettings.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val filter by vm.formatFilter.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showAddSheet by remember { mutableStateOf(false) }
    var hasAccess by remember { mutableStateOf(StoragePermissions.hasAllFilesAccess(context)) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.addFolder(it) } }
    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.addFiles(uris) }
    val accessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasAccess = StoragePermissions.hasAllFilesAccess(context)
        if (hasAccess) vm.refresh()
    }
    val requestAccess = { accessLauncher.launch(StoragePermissions.manageAllFilesIntent(context)) }

    var openFolder by remember(filter) { mutableStateOf<String?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showDelete by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    val searchActive = query.isNotBlank()

    fun exitSelection() { selectionMode = false; selected = emptySet() }
    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
        if (selected.isEmpty()) selectionMode = false
    }

    BackHandler(enabled = selectionMode || openFolder != null || searchActive) {
        when {
            selectionMode -> exitSelection()
            openFolder != null -> openFolder = null
            searchActive -> vm.setSearch("")
        }
    }

    val folderGroups = remember(items) {
        items.groupBy { it.document.folderKey }
            .map { (key, docs) ->
                FolderGroup(key, docs.first().document.folderDisplayName, docs.first().document.folderPathPretty, docs.size)
            }
            .sortedBy { it.name.lowercase() }
    }
    val visibleFiles = remember(items, openFolder, searchActive) {
        when {
            searchActive -> items
            openFolder != null -> items.filter { it.document.folderKey == openFolder }
            else -> emptyList()
        }
    }

    Scaffold(
        floatingActionButton = {
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    text = { Text("Add") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { showAddSheet = true }
                )
            }
        },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        if (selected.size == 1) {
                            IconButton(onClick = { showRename = true }) {
                                Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Rename")
                            }
                        }
                        IconButton(onClick = {
                            val docs = items.filter { it.document.id in selected }.map { it.document }
                            if (docs.isNotEmpty()) DocumentActions.share(context, docs)
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { selected = visibleFiles.map { it.document.id }.toSet() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = { if (selected.isNotEmpty()) showDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (!selectionMode) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp)
                )
                if (!hasAccess) AccessBanner(onGrant = requestAccess)
                SearchField(query = query, onQueryChange = vm::setSearch, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                FilterRow(selected = filter, onSelect = { vm.setFilter(it); openFolder = null })
            }
            if (scanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val hasSources = settings.folders.isNotEmpty() || items.isNotEmpty() || hasAccess
            when {
                !hasSources -> EmptyState(
                    icon = { Icon(Icons.Filled.LibraryBooks, null, modifier = Modifier.size(64.dp)) },
                    title = "Find your files",
                    message = "Turn on file access and AR Reader will automatically list every book, PDF, comic and document on your phone.",
                    action = { FilledTonalButton(onClick = requestAccess) { Text("Allow file access") } }
                )

                items.isEmpty() && !searchActive -> EmptyState(
                    icon = { Icon(Icons.Filled.LibraryBooks, null, modifier = Modifier.size(64.dp)) },
                    title = if (filter == FormatFilter.ALL) "No files yet" else "No ${filter.label} files",
                    message = if (hasAccess) "Nothing matched here. Try another filter or add files."
                    else "Turn on file access to auto-find files, or add a folder.",
                    action = {
                        if (hasAccess) FilledTonalButton(onClick = { showAddSheet = true }) { Text("Add files") }
                        else FilledTonalButton(onClick = requestAccess) { Text("Allow file access") }
                    }
                )

                searchActive || openFolder != null -> FilesArea(
                    files = visibleFiles,
                    folderTitle = if (!searchActive) openFolder?.let { key -> folderGroups.firstOrNull { it.key == key }?.name } else null,
                    viewMode = settings.viewMode,
                    sort = settings.sort,
                    selectionMode = selectionMode,
                    selected = selected,
                    onUp = { openFolder = null },
                    onOpen = { item ->
                        if (selectionMode) toggle(item.document.id) else onOpenDocument(item)
                    },
                    onLongPress = { item ->
                        selectionMode = true; toggle(item.document.id)
                    },
                    onToggleFavorite = { vm.toggleFavorite(it.document.id) },
                    onSetSort = vm::setSort,
                    onSetView = vm::setViewMode,
                    onRefresh = vm::refresh
                )

                folderGroups.isEmpty() -> EmptyState(
                    icon = { Icon(Icons.Filled.SearchOff, null, modifier = Modifier.size(64.dp)) },
                    title = "Nothing here",
                    message = "No ${if (filter == FormatFilter.ALL) "" else filter.label + " "}files were found."
                )

                else -> FolderBrowser(
                    folders = folderGroups,
                    continueReading = continueReading,
                    onOpenFolder = { openFolder = it },
                    onOpenDocument = onOpenDocument
                )
            }
        }
    }

    if (showAddSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }, sheetState = sheetState) {
            Text("Add to library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp))
            ListItem(
                headlineContent = { Text("Add a folder") },
                supportingContent = { Text("Scan a folder for all supported files") },
                leadingContent = { Icon(Icons.Filled.CreateNewFolder, null) },
                modifier = Modifier.clickable { showAddSheet = false; folderLauncher.launch(null) }
            )
            ListItem(
                headlineContent = { Text("Add files") },
                supportingContent = { Text("Pick individual documents") },
                leadingContent = { Icon(Icons.Filled.NoteAdd, null) },
                modifier = Modifier.clickable { showAddSheet = false; filesLauncher.launch(arrayOf("*/*")) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDelete) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete $n ${if (n == 1) "file" else "files"}?") },
            text = { Text("This permanently removes the selected ${if (n == 1) "file" else "files"} from your device. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(selected)
                    showDelete = false
                    exitSelection()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
        )
    }

    if (showRename) {
        val target = items.firstOrNull { it.document.id in selected }
        if (target == null) {
            showRename = false
        } else {
            var newName by remember(target.document.id) { mutableStateOf(target.document.title) }
            AlertDialog(
                onDismissRequest = { showRename = false },
                title = { Text("Rename") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("File name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.rename(target.document.id, newName)
                        showRename = false
                        exitSelection()
                    }, enabled = newName.isNotBlank()) { Text("Rename") }
                },
                dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Search your library") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        )
    )
}

@Composable
private fun FilterRow(selected: FormatFilter, onSelect: (FormatFilter) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(FormatFilter.entries) { f ->
            FilterChip(
                selected = selected == f,
                onClick = { onSelect(f) },
                label = { Text(f.label) },
                leadingIcon = if (selected == f) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

@Composable
private fun FolderBrowser(
    folders: List<FolderGroup>,
    continueReading: List<LibraryItem>,
    onOpenFolder: (String) -> Unit,
    onOpenDocument: (LibraryItem) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp)) {
        if (continueReading.isNotEmpty()) {
            item { ContinueReadingSection(continueReading, onOpenDocument) }
        }
        item {
            Text(
                "Folders",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
            )
        }
        items(folders, key = { it.key }) { folder ->
            FolderRow(folder, onClick = { onOpenFolder(folder.key) })
        }
    }
}

@Composable
private fun FolderRow(folder: FolderGroup, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                folder.path.ifBlank { "${folder.count} items" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text("${folder.count}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FilesArea(
    files: List<LibraryItem>,
    folderTitle: String?,
    viewMode: ViewMode,
    sort: SortOption,
    selectionMode: Boolean,
    selected: Set<String>,
    onUp: () -> Unit,
    onOpen: (LibraryItem) -> Unit,
    onLongPress: (LibraryItem) -> Unit,
    onToggleFavorite: (LibraryItem) -> Unit,
    onSetSort: (SortOption) -> Unit,
    onSetView: (ViewMode) -> Unit,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        if (folderTitle != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up") }
                Text(folderTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (files.isEmpty()) {
            EmptyState(
                icon = { Icon(Icons.Filled.SearchOff, null, modifier = Modifier.size(64.dp)) },
                title = "No matches",
                message = "Nothing to show here."
            )
            return@Column
        }
        if (viewMode == ViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ToolbarRow(files.size, sort, viewMode, onSetSort, onSetView, onRefresh)
                }
                gridItems(files, key = { it.document.id }) { item ->
                    DocumentGridCard(
                        item = item,
                        onOpen = { onOpen(item) },
                        onToggleFavorite = { onToggleFavorite(item) },
                        selected = item.document.id in selected,
                        selectionMode = selectionMode,
                        onLongClick = { onLongPress(item) }
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { ToolbarRow(files.size, sort, viewMode, onSetSort, onSetView, onRefresh) }
                items(files, key = { it.document.id }) { item ->
                    DocumentListRow(
                        item = item,
                        onOpen = { onOpen(item) },
                        onToggleFavorite = { onToggleFavorite(item) },
                        selected = item.document.id in selected,
                        selectionMode = selectionMode,
                        onLongClick = { onLongPress(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingSection(items: List<LibraryItem>, onOpen: (LibraryItem) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text("Continue reading", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.document.id }) { item ->
                Column(modifier = Modifier.width(120.dp).clickable { onOpen(item) }) {
                    DocumentCover(item = item, modifier = Modifier.width(120.dp).height(160.dp))
                    Text(item.document.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolbarRow(
    count: Int,
    sort: SortOption,
    viewMode: ViewMode,
    onSetSort: (SortOption) -> Unit,
    onSetView: (ViewMode) -> Unit,
    onRefresh: () -> Unit
) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$count ${if (count == 1) "item" else "items"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 4.dp)
        )
        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Rescan") }
        Box {
            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort") }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = { onSetSort(option); sortMenu = false },
                        trailingIcon = { if (option == sort) Icon(Icons.Filled.Check, contentDescription = null) }
                    )
                }
            }
        }
        IconButton(onClick = { onSetView(if (viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID) }) {
            Icon(
                if (viewMode == ViewMode.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                contentDescription = "Toggle view"
            )
        }
    }
}

@Composable
private fun AccessBanner(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Auto-find your files", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Allow file access so AR Reader lists every book, PDF, comic and document on your phone automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onGrant) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Allow file access")
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.primary
        ) { icon() }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}
