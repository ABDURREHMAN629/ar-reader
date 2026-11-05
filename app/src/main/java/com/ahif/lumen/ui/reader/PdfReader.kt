package com.ahif.lumen.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Pageview
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.reader.PdfDocumentSource
import com.ahif.lumen.data.reader.PdfOpenResult
import com.ahif.lumen.di.Graph
import com.ahif.lumen.ui.util.DocumentActions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private val invertFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

private enum class PdfColorMode { NORMAL, SEPIA, NIGHT, CONSOLE }

/** Shared reading theme -> PDF colour mode (PDF pages are images, so modes are colour filters). */
private fun pdfModeFor(id: ReaderThemeId): PdfColorMode = when (id) {
    ReaderThemeId.SYSTEM, ReaderThemeId.LIGHT -> PdfColorMode.NORMAL
    ReaderThemeId.SEPIA -> PdfColorMode.SEPIA
    ReaderThemeId.NIGHT, ReaderThemeId.BLACK -> PdfColorMode.NIGHT
    ReaderThemeId.CONSOLE -> PdfColorMode.CONSOLE
}

private fun themeForPdfMode(mode: PdfColorMode): ReaderThemeId = when (mode) {
    PdfColorMode.NORMAL -> ReaderThemeId.LIGHT
    PdfColorMode.SEPIA -> ReaderThemeId.SEPIA
    PdfColorMode.NIGHT -> ReaderThemeId.NIGHT
    PdfColorMode.CONSOLE -> ReaderThemeId.CONSOLE
}

// White paper -> warm cream (#F4ECD8), black ink -> dark brown (#5B4636).
private val sepiaFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.6f, 0f, 0f, 0f, 91f,
            0f, 0.651f, 0f, 0f, 70f,
            0f, 0f, 0.635f, 0f, 54f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

// Maps luminance -> inverted -> green (#5CF08A): black page becomes green-on-black terminal look.
private val consoleFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.108f, -0.2124f, -0.0396f, 0f, 91.8f,
            -0.282f, -0.5546f, -0.1034f, 0f, 239.7f,
            -0.162f, -0.3186f, -0.0594f, 0f, 137.7f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReader(item: LibraryItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.ahif.lumen.data.prefs.AppSettings()
    )
    KeepScreenOn(settings.keepScreenOn)

    val unlock: ReaderUnlockViewModel = viewModel()
    var password by remember { mutableStateOf(unlock.passwords[item.document.id]) }
    var openFailed by remember { mutableStateOf<String?>(null) }
    var locked by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) } // re-checks even if the same password is typed again
    val sourceState = produceState<PdfDocumentSource?>(initialValue = null, item.document.id, password, attempt) {
        when (val result = PdfDocumentSource.load(context, item.document.uri, password)) {
            is PdfOpenResult.Opened -> {
                locked = false
                password?.let { unlock.passwords[item.document.id] = it }
                value = result.source
                awaitDispose { result.source.close() }
            }
            PdfOpenResult.NeedsPassword -> locked = true
            PdfOpenResult.WrongPassword -> {
                locked = true
                passwordError = "Wrong password. Try again."
            }
            PdfOpenResult.PasswordUnsupported ->
                openFailed = "This PDF is password protected. Opening protected PDFs needs Android 15 or newer."
            PdfOpenResult.Failed ->
                openFailed = "Can't open this PDF. It may be damaged."
        }
    }
    val source = sourceState.value
    val checkingPassword = locked && password != null && source == null && passwordError == null

    // Measure every page up front (cheap) so the list never jumps when scrolling back up.
    LaunchedEffect(source) {
        val s = source ?: return@LaunchedEffect
        for (i in 0 until s.pageCount) s.pageAspect(i)
    }

    val docTheme = rememberDocReadingTheme(item)
    val colorMode = pdfModeFor(docTheme.current)
    var chromeVisible by remember { mutableStateOf(true) }
    var showGoTo by remember { mutableStateOf(false) }
    var showBrightness by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(item.state.zoom.coerceIn(1f, 5f)) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = item.state.positionUnit)
    val pageCount = source?.pageCount ?: 0
    val currentIndex by rememberUpdatedState(listState.firstVisibleItemIndex)

    LaunchedEffect(pageCount) {
        if (pageCount > 0) {
            // Pages exist now, so restore the saved page (the list couldn't do it while empty).
            val target = item.state.positionUnit.coerceIn(0, pageCount - 1)
            if (target > 0 && listState.firstVisibleItemIndex != target) {
                listState.scrollToItem(target)
            }
            snapshotFlow { listState.firstVisibleItemIndex }.debounce(400).collect { idx ->
                val progress = if (pageCount > 1) idx.toFloat() / (pageCount - 1) else 1f
                Graph.stateStore.saveProgress(item.document.id, progress, idx, 0, pageCount)
            }
        }
    }
    // Persist zoom as soon as it changes (so it survives even a quick back-and-reopen).
    LaunchedEffect(Unit) {
        snapshotFlow { zoom }.debounce(350).collect { z ->
            Graph.stateStore.saveZoom(item.document.id, z)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val c = pageCount
            if (c > 0) {
                val idx = currentIndex
                val progress = if (c > 1) idx.toFloat() / (c - 1) else 1f
                val z = zoom
                Graph.appScope.launch {
                    Graph.stateStore.saveProgress(item.document.id, progress, idx, 0, c)
                    Graph.stateStore.saveZoom(item.document.id, z)
                }
            }
        }
    }

    Scaffold(
        containerColor = when (colorMode) {
            PdfColorMode.NORMAL -> Color(0xFF303338)
            PdfColorMode.SEPIA -> Color(0xFF2B2620)
            PdfColorMode.NIGHT -> Color(0xFF0B0B0B)
            PdfColorMode.CONSOLE -> Color(0xFF000000)
        },
        topBar = {
            if (chromeVisible) {
                TopAppBar(
                    title = { Text(item.document.title, maxLines = 1, color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        ReaderOverflowMenu(
                            actions = listOf(
                                ReaderAction(if (showBrightness) "Hide brightness" else "Brightness", Icons.Filled.BrightnessMedium) {
                                    showBrightness = !showBrightness
                                },
                                ReaderAction("Go to page", Icons.Filled.Pageview) { showGoTo = true },
                            ) + listOfNotNull(
                                // The print service can't unlock a protected file, so only offer it for normal PDFs.
                                if (password == null) ReaderAction("Print", Icons.Filled.Print) { DocumentActions.printPdf(context, item.document) } else null
                            ) + listOf(
                                ReaderAction("Share", Icons.Filled.Share) { DocumentActions.share(context, listOf(item.document)) }
                            ),
                            themes = listOf(ReaderThemeId.LIGHT, ReaderThemeId.SEPIA, ReaderThemeId.NIGHT, ReaderThemeId.CONSOLE),
                            current = themeForPdfMode(colorMode),
                            onTheme = docTheme::set,
                            tint = Color.White
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.55f))
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val failure = openFailed
            if (source == null && failure != null) {
                Text(
                    failure,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
            } else if (source == null && locked) {
                PasswordPrompt(
                    title = item.document.title,
                    error = passwordError,
                    checking = checkingPassword,
                    onSubmit = { typed ->
                        passwordError = null
                        password = typed
                        attempt++
                    }
                )
            } else if (source == null) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                val density = LocalDensity.current
                val hScroll = rememberScrollState()
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val baseWidthPx = with(density) { maxWidth.toPx() }
                    // Render pages at a stepped resolution so pinch stays smooth but re-renders rarely.
                    val renderZoom = (kotlin.math.ceil(zoom * 2f) / 2f).coerceIn(1f, 5f)
                    val renderWidthPx = (baseWidthPx * renderZoom).toInt().coerceIn(320, 3000)
                    val columnWidth = maxWidth * zoom

                    // Render the next pages ahead of time so scrolling down is seamless.
                    LaunchedEffect(source, renderWidthPx) {
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                            .collectLatest { last ->
                                if (last != null) {
                                    source.renderPage(last + 1, renderWidthPx)
                                    source.renderPage(last + 2, renderWidthPx)
                                }
                            }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { chromeVisible = !chromeVisible },
                                    onDoubleTap = { zoom = if (zoom > 1f) 1f else 2.5f }
                                )
                            }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        // Initial pass so a 2-finger pinch is claimed before the scrollers.
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        // Only two-finger pinch changes zoom; single-finger drags are
                                        // left for the freePan handler below.
                                        if (event.changes.count { it.pressed } >= 2) {
                                            val zoomChange = event.calculateZoom()
                                            if (zoomChange != 1f) {
                                                zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            // One-finger drag pans both axes at once (diagonal scrolling) with fling.
                            .freePan(horizontal = hScroll, vertical = listState)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(hScroll, enabled = false)
                        ) {
                            LazyColumn(
                                state = listState,
                                userScrollEnabled = false,
                                modifier = Modifier.fillMaxHeight().width(columnWidth)
                            ) {
                                items(pageCount) { index ->
                                    PdfPageView(source, index, renderWidthPx, colorMode)
                                }
                            }
                        }
                    }
                }
                if (chromeVisible && pageCount > 1) {
                    PageScrubber(
                        current = currentIndex,
                        total = pageCount,
                        onSeek = { scope.launch { listState.scrollToItem(it) } },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    )
                }
                if (chromeVisible) {
                    BrightnessOverlay(
                        visible = showBrightness,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = if (pageCount > 1) 74.dp else 16.dp)
                    )
                }
                if (showGoTo && pageCount > 0) {
                    GoToPageDialog(
                        total = pageCount,
                        onGo = { p -> scope.launch { listState.scrollToItem(p) } },
                        onDismiss = { showGoTo = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPageView(source: PdfDocumentSource, index: Int, renderWidthPx: Int, mode: PdfColorMode) {
    // Start from anything already rendered, and keep showing the old bitmap while a sharper
    // one renders after a zoom change, so pages never flash blank.
    var bitmap by remember(index) { mutableStateOf(source.cachedPage(index, renderWidthPx)) }
    var aspect by remember(index) { mutableFloatStateOf(source.knownAspect(index) ?: 1.4f) }

    LaunchedEffect(index, renderWidthPx) {
        if (source.knownAspect(index) == null) aspect = source.pageAspect(index)
        source.renderPage(index, renderWidthPx)?.let { bitmap = it }
        aspect = source.knownAspect(index) ?: aspect
    }

    val pageBg = when (mode) {
        PdfColorMode.NORMAL -> Color.White
        PdfColorMode.SEPIA -> Color(0xFFF4ECD8)
        PdfColorMode.NIGHT -> Color(0xFF111111)
        PdfColorMode.CONSOLE -> Color(0xFF000000)
    }
    val filter = when (mode) {
        PdfColorMode.NORMAL -> null
        PdfColorMode.SEPIA -> sepiaFilter
        PdfColorMode.NIGHT -> invertFilter
        PdfColorMode.CONSOLE -> consoleFilter
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f / aspect)
            .padding(vertical = 6.dp)
            .background(pageBg),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                colorFilter = filter,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text(
                    "Page ${index + 1}",
                    color = if (mode == PdfColorMode.NORMAL || mode == PdfColorMode.SEPIA) Color.Gray else Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
