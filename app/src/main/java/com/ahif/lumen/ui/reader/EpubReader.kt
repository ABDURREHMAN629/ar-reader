package com.ahif.lumen.ui.reader

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.reader.EpubBook
import com.ahif.lumen.data.reader.EpubParser
import com.ahif.lumen.di.Graph
import com.ahif.lumen.ui.util.DocumentActions
import kotlinx.coroutines.launch

private data class EpubTheme(val label: String, val bg: String, val fg: String, val link: String)

private val epubThemes = listOf(
    EpubTheme("Light", "#FFFFFF", "#1B1B1B", "#3B5BDB"),
    EpubTheme("Sepia", "#F4ECD8", "#5B4636", "#8A5A2B"),
    EpubTheme("Night", "#14161A", "#C9CBD1", "#8AB4F8"),
    EpubTheme("Black", "#000000", "#B4B4B4", "#8AB4F8"),
    EpubTheme("Console", "#000000", "#5CF08A", "#4EFF91")
)

/** Maps the shared reading theme onto this reader's palette; "System" follows the app's light/dark. */
private fun epubThemeFor(id: ReaderThemeId, appIsDark: Boolean): EpubTheme {
    val label = if (id == ReaderThemeId.SYSTEM) (if (appIsDark) "Night" else "Light") else id.label
    return epubThemes.firstOrNull { it.label == label } ?: epubThemes.first()
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReader(item: LibraryItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.ahif.lumen.data.prefs.AppSettings()
    )
    KeepScreenOn(settings.keepScreenOn)

    val bookState = produceState<EpubBook?>(initialValue = null, key1 = item.document.id) {
        value = EpubParser.open(context, item.document.uri)
    }
    val book = bookState.value
    val chapterCount = book?.chapters?.size ?: 0

    var chapterIndex by remember {
        mutableIntStateOf(item.state.positionUnit.coerceAtLeast(0))
    }
    val docTheme = rememberDocReadingTheme(item)
    val themeId = docTheme.current
    val appIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val theme = epubThemeFor(themeId, appIsDark)

    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchCount by remember { mutableIntStateOf(0) }
    var showBrightness by remember { mutableStateOf(false) }

    val currentChapter by rememberUpdatedState(chapterIndex)

    val webView = remember {
        WebView(context).apply {
            // Security: JS off, and file:// pages may not reach out to other files or the network.
            this.settings.javaScriptEnabled = false
            this.settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            this.settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            this.settings.allowUniversalAccessFromFileURLs = false
            this.settings.blockNetworkLoads = true
            this.settings.builtInZoomControls = true
            this.settings.displayZoomControls = false
            setFindListener { _, numberOfMatches, _ -> matchCount = numberOfMatches }
        }
    }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            webView.clearMatches()
            matchCount = 0
        } else {
            webView.findAllAsync(query)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val c = chapterCount
            if (c > 0) {
                val idx = currentChapter
                val progress = if (c > 1) idx.toFloat() / (c - 1) else 1f
                Graph.appScope.launch { Graph.stateStore.saveProgress(item.document.id, progress, idx, 0, c) }
            }
            webView.destroy()
        }
    }

    LaunchedEffect(book, chapterIndex, theme, settings.readerFontScale) {
        val b = book ?: return@LaunchedEffect
        val idx = chapterIndex.coerceIn(0, b.chapters.lastIndex)
        val file = b.chapters[idx]
        val raw = runCatching { file.readText() }.getOrElse { "<p>Could not open chapter.</p>" }
        val fontPercent = (settings.readerFontScale * 100).toInt()
        val html = injectStyle(raw, theme, fontPercent)
        val base = "file://" + (file.parentFile?.absolutePath ?: "") + "/"
        webView.setBackgroundColor(AndroidColor.parseColor(theme.bg))
        webView.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
        if (chapterCount > 1) {
            Graph.stateStore.saveProgress(
                item.document.id, idx.toFloat() / (chapterCount - 1), idx, 0, chapterCount
            )
        }
    }

    Scaffold(
        containerColor = Color(AndroidColor.parseColor(theme.bg)),
        topBar = {
            val fgC = Color(AndroidColor.parseColor(theme.fg))
            val bgC = Color(AndroidColor.parseColor(theme.bg))
            if (searchActive) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(bgC).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        searchActive = false; query = ""; webView.clearMatches()
                    }) { Icon(Icons.Filled.Close, contentDescription = "Close search", tint = fgC) }
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Find in book") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    Text(if (query.isBlank()) "" else "$matchCount", color = fgC, style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = { webView.findNext(false) }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous", tint = fgC)
                    }
                    IconButton(onClick = { webView.findNext(true) }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next", tint = fgC)
                    }
                }
            } else {
                TopAppBar(
                    title = { Text(book?.title ?: item.document.title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        ReaderOverflowMenu(
                            actions = listOf(
                                ReaderAction("Find in book", Icons.Filled.Search) { searchActive = true },
                                ReaderAction(if (showBrightness) "Hide brightness" else "Brightness", Icons.Filled.BrightnessMedium) {
                                    showBrightness = !showBrightness
                                },
                                ReaderAction("Share", Icons.Filled.Share) { DocumentActions.share(context, listOf(item.document)) }
                            ),
                            themes = ReaderThemeId.entries,
                            current = themeId,
                            onTheme = docTheme::set,
                            tint = fgC
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = bgC,
                        titleContentColor = fgC,
                        navigationIconContentColor = fgC,
                        actionIconContentColor = fgC
                    )
                )
            }
        },
        bottomBar = {
            if (chapterCount > 0) {
                val fgC = Color(AndroidColor.parseColor(theme.fg))
                val bgC = Color(AndroidColor.parseColor(theme.bg))
                Column {
                    if (chapterCount > 1) {
                        PageScrubber(
                            current = chapterIndex,
                            total = chapterCount,
                            onSeek = { chapterIndex = it },
                            containerColor = bgC,
                            contentColor = fgC,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        )
                    }
                    BottomAppBar(containerColor = bgC, contentColor = fgC) {
                        IconButton(
                            onClick = { if (chapterIndex > 0) chapterIndex-- },
                            enabled = chapterIndex > 0
                        ) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous chapter") }
                        Text(
                            "Chapter ${chapterIndex + 1} of $chapterCount",
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        IconButton(
                            onClick = { if (chapterIndex < chapterCount - 1) chapterIndex++ },
                            enabled = chapterIndex < chapterCount - 1
                        ) { Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next chapter") }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (book == null) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            }
            BrightnessOverlay(
                visible = showBrightness,
                containerColor = Color(AndroidColor.parseColor(theme.fg)).copy(alpha = 0.16f),
                contentColor = Color(AndroidColor.parseColor(theme.fg)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }
}

private fun injectStyle(raw: String, theme: EpubTheme, fontPercent: Int): String {
    val style = """
        <style>
        html, body {
            background: ${theme.bg} !important;
            color: ${theme.fg} !important;
            font-size: $fontPercent% !important;
            line-height: 1.65 !important;
            padding: 8px 5vw 40px 5vw !important;
            margin: 0 !important;
            word-wrap: break-word;
        }
        p { text-align: justify; }
        img { max-width: 100% !important; height: auto !important; }
        a { color: ${theme.link} !important; }
        pre, code { white-space: pre-wrap !important; }
        </style>
    """.trimIndent()
    return if (raw.contains("</head>", ignoreCase = true)) {
        raw.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$style</head>")
    } else {
        "$style$raw"
    }
}

@Suppress("unused")
private fun Color.argb() = this.toArgb()
