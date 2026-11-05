package com.ahif.lumen.ui.reader

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahif.lumen.data.model.DocumentType
import com.ahif.lumen.data.reader.HtmlDoc
import com.ahif.lumen.data.reader.OfficeHtml
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.di.Graph
import com.ahif.lumen.ui.util.DocumentActions

private data class HtmlTheme(val label: String, val bg: String, val fg: String, val line: String)

private val htmlThemes = listOf(
    HtmlTheme("Light", "#FFFFFF", "#1B1B1B", "#D5D5DD"),
    HtmlTheme("Sepia", "#F4ECD8", "#5B4636", "#D8C6A5"),
    HtmlTheme("Night", "#14161A", "#C9CBD1", "#3A3D44"),
    HtmlTheme("Black", "#000000", "#B4B4B4", "#333333"),
    HtmlTheme("Console", "#000000", "#5CF08A", "#1E3A28")
)

/** Maps the shared reading theme onto this reader's palette; "System" follows the app's light/dark. */
private fun htmlThemeFor(id: ReaderThemeId, appIsDark: Boolean): HtmlTheme {
    val label = if (id == ReaderThemeId.SYSTEM) (if (appIsDark) "Night" else "Light") else id.label
    return htmlThemes.firstOrNull { it.label == label } ?: htmlThemes.first()
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeHtmlReader(item: LibraryItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.ahif.lumen.data.prefs.AppSettings()
    )
    KeepScreenOn(settings.keepScreenOn)

    val docState = produceState<HtmlDoc?>(initialValue = null, key1 = item.document.id) {
        value = OfficeHtml.render(context, item.document.contentUri, item.document.name, item.document.type)
    }
    val doc = docState.value

    val docTheme = rememberDocReadingTheme(item)
    val themeId = docTheme.current
    val appIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val theme = htmlThemeFor(themeId, appIsDark)
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchCount by remember { mutableIntStateOf(0) }
    var showBrightness by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            this.settings.javaScriptEnabled = false
            this.settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            this.settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            this.settings.allowUniversalAccessFromFileURLs = false
            this.settings.blockNetworkLoads = true
            this.settings.builtInZoomControls = true
            this.settings.displayZoomControls = false
            setFindListener { _, n, _ -> matchCount = n }
        }
    }

    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    LaunchedEffect(doc, theme, settings.readerFontScale) {
        val d = doc ?: return@LaunchedEffect
        val fontPercent = (settings.readerFontScale * 100).toInt()
        val html = wrapHtml(d.body, theme, fontPercent)
        webView.setBackgroundColor(AndroidColor.parseColor(theme.bg))
        webView.loadDataWithBaseURL(d.baseUrl, html, "text/html", "utf-8", null)
    }

    LaunchedEffect(query) {
        if (query.isBlank()) { webView.clearMatches(); matchCount = 0 } else webView.findAllAsync(query)
    }

    val fg = Color(AndroidColor.parseColor(theme.fg))
    val bg = Color(AndroidColor.parseColor(theme.bg))

    Scaffold(
        containerColor = bg,
        topBar = {
            if (searchActive) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { searchActive = false; query = ""; webView.clearMatches() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close search", tint = fg)
                    }
                    TextField(
                        value = query,
                        onValueChange = { query = it },
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
                    Text(if (query.isBlank()) "" else "$matchCount", color = fg, style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = { webView.findNext(false) }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous", tint = fg) }
                    IconButton(onClick = { webView.findNext(true) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next", tint = fg) }
                }
            } else {
                TopAppBar(
                    title = { Text(item.document.title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
                        containerColor = bg, titleContentColor = fg, navigationIconContentColor = fg, actionIconContentColor = fg
                    )
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                doc == null && DocumentType.isLegacyBinary(item.document.name) -> Text(
                    "Legacy .${item.document.name.substringAfterLast('.')} files aren't supported yet. Re-save as .${modernExt(item.document.type)} and open again.",
                    color = fg,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
                doc == null -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.Center))
                else -> AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            }
            BrightnessOverlay(
                visible = showBrightness,
                containerColor = fg.copy(alpha = 0.16f),
                contentColor = fg,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }
}

private fun modernExt(type: DocumentType) = when (type) {
    DocumentType.WORD -> "docx"
    DocumentType.POWERPOINT -> "pptx"
    else -> "docx"
}

private fun wrapHtml(body: String, theme: HtmlTheme, fontPercent: Int): String = """
    <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
      html,body{background:${theme.bg};color:${theme.fg};font-size:$fontPercent%;line-height:1.6;
        padding:10px 4vw 48px 4vw;margin:0;word-wrap:break-word;font-family:sans-serif;}
      h1{font-size:1.6em;} h2{font-size:1.35em;} h3{font-size:1.15em;}
      img{max-width:100%;height:auto;border-radius:6px;margin:6px 0;}
      table{border-collapse:collapse;width:100%;margin:10px 0;display:block;overflow-x:auto;}
      td,th{border:1px solid ${theme.line};padding:6px 8px;vertical-align:top;}
      .slide{border:1px solid ${theme.line};border-radius:10px;padding:14px;margin:14px 0;}
      .slidenum{font-weight:bold;opacity:0.55;font-size:0.85em;margin-bottom:6px;}
      p{margin:0.5em 0;}
    </style></head><body>$body</body></html>
""".trimIndent()
