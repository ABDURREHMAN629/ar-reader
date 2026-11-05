package com.ahif.lumen.ui.reader

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.reader.BitmapUtils
import com.ahif.lumen.data.reader.ComicSource
import com.ahif.lumen.di.Graph
import com.ahif.lumen.ui.util.DocumentActions
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicReader(item: LibraryItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.ahif.lumen.data.prefs.AppSettings()
    )
    KeepScreenOn(settings.keepScreenOn)

    var chromeVisible by remember { mutableStateOf(true) }
    var showBrightness by remember { mutableStateOf(false) }

    val sourceState = produceState<ComicSource?>(initialValue = null, key1 = item.document.id) {
        val src = ComicSource.open(context, item.document.uri, item.document.name)
        value = src
        awaitDispose { src?.close() }
    }
    val source = sourceState.value
    val pageCount = source?.pageCount ?: 0

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = item.state.positionUnit.coerceAtLeast(0),
        pageCount = { pageCount }
    )
    val currentPage by rememberUpdatedState(pagerState.currentPage)

    LaunchedEffect(pageCount) {
        if (pageCount > 0) {
            snapshotFlow { pagerState.currentPage }
                .debounce(300)
                .collect { idx ->
                    val progress = if (pageCount > 1) idx.toFloat() / (pageCount - 1) else 1f
                    Graph.stateStore.saveProgress(item.document.id, progress, idx, 0, pageCount)
                }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val c = pageCount
            if (c > 0) {
                val idx = currentPage
                val progress = if (c > 1) idx.toFloat() / (c - 1) else 1f
                Graph.appScope.launch { Graph.stateStore.saveProgress(item.document.id, progress, idx, 0, c) }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            source == null -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )
            pageCount == 0 -> Text(
                "This comic has no readable pages, or the format isn't supported.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )
            else -> HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ComicPage(source, page, onTap = { chromeVisible = !chromeVisible })
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
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
                            ReaderAction("Share", Icons.Filled.Share) { DocumentActions.share(context, listOf(item.document)) }
                        ),
                        themes = emptyList(),
                        current = ReaderThemeId.SYSTEM,
                        onTheme = {},
                        tint = Color.White
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.5f))
            )
        }

        if (pageCount > 1) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                PageScrubber(
                    current = currentPage,
                    total = pageCount,
                    onSeek = { scope.launch { pagerState.scrollToPage(it) } }
                )
            }
        }
        BrightnessOverlay(
            visible = showBrightness && chromeVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = if (pageCount > 1) 74.dp else 16.dp)
        )
    }
}

@Composable
private fun ComicPage(source: ComicSource, page: Int, onTap: () -> Unit) {
    val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = page) {
        val bytes = source.pageBytes(page)
        value = bytes?.let { BitmapUtils.decodeSampled(it) }
    }
    val bmp = bitmapState.value
    if (bmp != null) {
        ZoomableBox(onTap = onTap) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${page + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}
