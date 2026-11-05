package com.ahif.lumen.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.reader.Spreadsheet
import com.ahif.lumen.data.reader.SpreadsheetParser
import com.ahif.lumen.ui.util.DocumentActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpreadsheetReader(item: LibraryItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val bookState = produceState<Spreadsheet?>(initialValue = null, key1 = item.document.id) {
        value = SpreadsheetParser.read(context, item.document.contentUri, item.document.name)
    }
    val book = bookState.value
    var sheetIndex by remember { mutableIntStateOf(0) }
    var showBrightness by remember { androidx.compose.runtime.mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.document.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    ReaderOverflowMenu(
                        actions = listOf(
                            ReaderAction(if (showBrightness) "Hide brightness" else "Brightness", Icons.Filled.BrightnessMedium) {
                                showBrightness = !showBrightness
                            },
                            ReaderAction("Share", Icons.Filled.Share) { DocumentActions.share(context, listOf(item.document)) }
                        ),
                        themes = emptyList(),
                        current = ReaderThemeId.SYSTEM,
                        onTheme = {}
                    )
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                book == null -> LoadingOrNotice(item)
                else -> {
                    val sheets = book.sheets
                    val current = sheets.getOrElse(sheetIndex) { sheets.first() }
                    Column(Modifier.fillMaxSize()) {
                        if (sheets.size > 1) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(sheets) { s ->
                                    val idx = sheets.indexOf(s)
                                    FilterChip(
                                        selected = idx == sheetIndex,
                                        onClick = { sheetIndex = idx },
                                        label = { Text(s.name) }
                                    )
                                }
                            }
                        }
                        SheetTable(current)
                    }
                }
            }
            BrightnessOverlay(
                visible = showBrightness,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }
}

@Composable
private fun LoadingOrNotice(item: LibraryItem) {
    val legacy = com.ahif.lumen.data.model.DocumentType.isLegacyBinary(item.document.name)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (legacy) {
            Text(
                "Legacy .${item.document.name.substringAfterLast('.')} files aren't supported yet. Re-save as .xlsx and open again.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SheetTable(sheet: com.ahif.lumen.data.reader.Sheet) {
    val cellWidth = 128.dp
    val colCount = sheet.rows.maxOfOrNull { it.size } ?: 0
    val hScroll = rememberScrollState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val border = MaterialTheme.colorScheme.outlineVariant

    // freePan moves both axes together so the grid can be dragged diagonally.
    Box(
        Modifier
            .fillMaxSize()
            .freePan(horizontal = hScroll, vertical = listState)
            .horizontalScroll(hScroll, enabled = false)
    ) {
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            modifier = Modifier.width(cellWidth * colCount)
        ) {
            itemsIndexed(sheet.rows) { rowIndex, row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    for (c in 0 until colCount) {
                        val text = row.getOrNull(c) ?: ""
                        Box(
                            modifier = Modifier
                                .width(cellWidth)
                                .fillMaxHeight()
                                .border(0.5.dp, border)
                                .background(
                                    if (rowIndex == 0) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface
                                )
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
