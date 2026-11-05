package com.ahif.lumen.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.di.Graph
import com.ahif.lumen.ui.util.DocumentActions
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageReader(item: LibraryItem, onBack: () -> Unit) {
    val context = LocalContext.current
    var chromeVisible by remember { mutableStateOf(true) }
    var showBrightness by remember { mutableStateOf(false) }

    LaunchedEffect(item.document.id) {
        Graph.appScope.launch {
            Graph.stateStore.saveProgress(item.document.id, 1f, 0, 0, 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ZoomableBox(onTap = { chromeVisible = !chromeVisible }) {
            AsyncImage(
                model = item.document.uri,
                contentDescription = item.document.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.4f)
                )
            )
        }
        BrightnessOverlay(
            visible = showBrightness && chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}
