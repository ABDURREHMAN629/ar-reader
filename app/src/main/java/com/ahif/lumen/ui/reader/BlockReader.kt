package com.ahif.lumen.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.di.Graph
import com.ahif.lumen.ui.util.DocumentActions
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

internal enum class ReadingTheme(val label: String, val bg: Color?, val fg: Color?) {
    SYSTEM("System", null, null),
    LIGHT("Light", Color(0xFFFFFFFF), Color(0xFF1B1B1B)),
    SEPIA("Sepia", Color(0xFFF4ECD8), Color(0xFF5B4636)),
    NIGHT("Night", Color(0xFF14161A), Color(0xFFC9CBD1)),
    BLACK("Black", Color(0xFF000000), Color(0xFFB4B4B4)),
    CONSOLE("Console", Color(0xFF000000), Color(0xFF5CF08A))
}

/**
 * A reusable reader for paragraph-based content (plain text, Markdown, Office docs)
 * with reading themes, adjustable font size, resumable position and find-in-document.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockReader(
    item: LibraryItem,
    onBack: () -> Unit,
    loader: suspend (android.content.Context) -> List<TextBlock>
) {
    val context = LocalContext.current
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.ahif.lumen.data.prefs.AppSettings()
    )
    val scope = rememberCoroutineScope()

    var blocks by remember { mutableStateOf<List<TextBlock>?>(null) }
    val docTheme = rememberDocReadingTheme(item)
    val themeId = docTheme.current
    val readingTheme = ReadingTheme.valueOf(themeId.name)

    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var currentMatch by remember { mutableIntStateOf(0) }
    var showBrightness by remember { mutableStateOf(false) }

    LaunchedEffect(item.document.id) {
        blocks = loader(context)
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = item.state.positionUnit)
    val total = blocks?.size ?: 0
    val currentIndex by rememberUpdatedState(listState.firstVisibleItemIndex)

    // matches: indices of blocks containing the query
    val matches = remember(query, blocks) {
        val b = blocks
        if (query.isBlank() || b == null) emptyList()
        else b.indices.filter { b[it].content.text.contains(query, ignoreCase = true) }
    }
    LaunchedEffect(matches) { currentMatch = 0 }
    LaunchedEffect(currentMatch, matches) {
        if (matches.isNotEmpty()) {
            listState.animateScrollToItem(matches[currentMatch.coerceIn(0, matches.lastIndex)])
        }
    }

    LaunchedEffect(total) {
        if (total > 0) {
            snapshotFlow { listState.firstVisibleItemIndex }.debounce(400).collect { idx ->
                val progress = if (total > 1) idx.toFloat() / (total - 1) else 1f
                Graph.stateStore.saveProgress(item.document.id, progress, idx, 0, total)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val t = total
            if (t > 0) {
                val idx = currentIndex
                val progress = if (t > 1) idx.toFloat() / (t - 1) else 1f
                Graph.appScope.launch { Graph.stateStore.saveProgress(item.document.id, progress, idx, 0, t) }
            }
        }
    }

    KeepScreenOn(settings.keepScreenOn)

    val bg = readingTheme.bg ?: MaterialTheme.colorScheme.background
    val fg = readingTheme.fg ?: MaterialTheme.colorScheme.onBackground
    val fontScale = settings.readerFontScale

    Scaffold(
        containerColor = bg,
        topBar = {
            if (searchActive) {
                SearchBarRow(
                    query = query,
                    onQuery = { query = it },
                    matchCount = matches.size,
                    matchIndex = if (matches.isEmpty()) 0 else currentMatch + 1,
                    onPrev = { if (matches.isNotEmpty()) currentMatch = (currentMatch - 1 + matches.size) % matches.size },
                    onNext = { if (matches.isNotEmpty()) currentMatch = (currentMatch + 1) % matches.size },
                    onClose = { searchActive = false; query = "" },
                    bg = bg, fg = fg
                )
            } else {
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
                                ReaderAction("Find in document", Icons.Filled.Search) { searchActive = true },
                                ReaderAction(if (showBrightness) "Hide brightness" else "Brightness", Icons.Filled.BrightnessMedium) {
                                    showBrightness = !showBrightness
                                },
                                ReaderAction("Share", Icons.Filled.Share) { DocumentActions.share(context, listOf(item.document)) }
                            ),
                            themes = ReaderThemeId.entries,
                            current = themeId,
                            onTheme = docTheme::set,
                            tint = fg
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = bg,
                        titleContentColor = fg,
                        navigationIconContentColor = fg,
                        actionIconContentColor = fg
                    )
                )
            }
        },
        bottomBar = {
            BottomAppBar(containerColor = bg, contentColor = fg) {
                IconButton(onClick = {
                    scope.launch { Graph.settings.setReaderFontScale((fontScale - 0.1f).coerceAtLeast(0.7f)) }
                }) { Icon(Icons.Filled.TextDecrease, contentDescription = "Smaller text") }
                Text("${(fontScale * 100).toInt()}%", color = fg)
                IconButton(onClick = {
                    scope.launch { Graph.settings.setReaderFontScale((fontScale + 0.1f).coerceAtMost(2.0f)) }
                }) { Icon(Icons.Filled.TextIncrease, contentDescription = "Larger text") }
                Box(Modifier.fillMaxWidth().padding(end = 16.dp), contentAlignment = Alignment.CenterEnd) {
                    val pct = if (total > 1) (currentIndex * 100 / (total - 1)) else 100
                    Text("$pct%", color = fg, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val current = blocks
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(bg),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    items(current.size) { index ->
                        BlockView(current[index], fg, fontScale, if (searchActive) query else "")
                    }
                }
            }
            BrightnessOverlay(
                visible = showBrightness,
                containerColor = fg.copy(alpha = 0.14f),
                contentColor = fg,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBarRow(
    query: String,
    onQuery: (String) -> Unit,
    matchCount: Int,
    matchIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    bg: Color,
    fg: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close search", tint = fg) }
        TextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Find in document") },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
        Text(
            if (query.isBlank()) "" else "$matchIndex/$matchCount",
            color = fg,
            style = MaterialTheme.typography.labelSmall
        )
        IconButton(onClick = onPrev) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous", tint = fg) }
        IconButton(onClick = onNext) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next", tint = fg) }
    }
}

@Composable
internal fun BlockView(block: TextBlock, color: Color, scale: Float, highlight: String = "") {
    val base = 17.sp * scale
    val content = if (highlight.isBlank()) block.content else highlightMatches(block.content, highlight)
    when (block.kind) {
        BlockKind.H1 -> Text(content, color = color, fontSize = 28.sp * scale, fontWeight = FontWeight.Bold, lineHeight = 34.sp * scale, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        BlockKind.H2 -> Text(content, color = color, fontSize = 23.sp * scale, fontWeight = FontWeight.SemiBold, lineHeight = 29.sp * scale, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        BlockKind.H3 -> Text(content, color = color, fontSize = 20.sp * scale, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp * scale, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        BlockKind.BULLET -> Text(content, color = color, fontSize = base, lineHeight = base * 1.5f, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
        BlockKind.QUOTE -> Box(Modifier.padding(vertical = 6.dp).background(color.copy(alpha = 0.06f), RoundedCornerShape(8.dp)).padding(12.dp)) {
            Text(content, color = color.copy(alpha = 0.85f), fontSize = base, lineHeight = base * 1.5f)
        }
        BlockKind.CODE -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(12.dp)) {
            Text(content, color = color, fontSize = base * 0.92f, fontFamily = FontFamily.Monospace)
        }
        BlockKind.PARAGRAPH -> Text(content, color = color, fontSize = base, lineHeight = base * 1.6f, modifier = Modifier.padding(vertical = 6.dp))
    }
}

private fun highlightMatches(content: AnnotatedString, query: String): AnnotatedString {
    val text = content.text
    if (query.isBlank() || !text.contains(query, ignoreCase = true)) return content
    return buildAnnotatedString {
        append(content)
        var idx = text.indexOf(query, 0, ignoreCase = true)
        while (idx >= 0) {
            addStyle(SpanStyle(background = Color(0xFFFFE082), color = Color(0xFF1B1B1B)), idx, idx + query.length)
            idx = text.indexOf(query, idx + query.length, ignoreCase = true)
        }
    }
}
