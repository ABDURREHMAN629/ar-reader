package com.ahif.lumen

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ahif.lumen.data.library.DocumentResolver
import com.ahif.lumen.data.library.FileScanner
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.prefs.AppSettings
import com.ahif.lumen.di.Graph
import com.ahif.lumen.ui.reader.LocalHostReaderActions
import com.ahif.lumen.ui.reader.ReaderAction
import com.ahif.lumen.ui.reader.ReaderRoute
import com.ahif.lumen.ui.theme.LumenTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows a file opened from another app (WhatsApp, Files, Gmail…) in its own task:
 * `documentLaunchMode="intoExisting"` gives each document its own card in Recents,
 * separate from the library. Back returns straight to the app that opened it and
 * leaves the document open at the same page; "Close document" in the ⋮ menu (or
 * swiping the card away in Recents) ends it.
 */
class ViewerActivity : ComponentActivity() {

    private val viewModel: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel.open(intent?.data)
        setContent {
            val settings by Graph.settings.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings()
            )
            LumenTheme(
                themeMode = settings.themeMode,
                accentId = settings.accentId,
                dynamicColor = settings.dynamicColor,
                amoled = settings.amoledDark
            ) {
                BrightnessController(settings.brightness)
                Surface(color = MaterialTheme.colorScheme.background) {
                    ViewerContent(
                        state = viewModel.state,
                        onLeave = ::leave,
                        onClose = ::finishAndRemoveTask,
                        onTitle = ::labelTask
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.open(intent.data)
    }

    /** Back to the app that opened this file, keeping the document open in Recents. */
    private fun leave() {
        if (!moveTaskToBack(true)) finish()
    }

    /** Names this task's Recents card after the document instead of the app. */
    private fun labelTask(title: String) {
        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityManager.TaskDescription.Builder().setLabel(title).build()
        } else {
            @Suppress("DEPRECATION")
            ActivityManager.TaskDescription(title)
        }
        setTaskDescription(description)
    }
}

@Composable
private fun ViewerContent(
    state: ViewerState,
    onLeave: () -> Unit,
    onClose: () -> Unit,
    onTitle: (String) -> Unit
) {
    // Readers' own BackHandlers (registered later) still win; this is the last stop.
    BackHandler(onBack = onLeave)
    when (state) {
        ViewerState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ViewerState.Failed -> CantOpen(onClose)
        is ViewerState.Ready -> {
            val doc = state.item.document
            LaunchedEffect(doc.id) { onTitle(doc.title) }
            val hostActions = remember(onClose) {
                listOf(ReaderAction("Close document", Icons.Filled.Close, onClose))
            }
            CompositionLocalProvider(LocalHostReaderActions provides hostActions) {
                key(doc.id) { ReaderRoute(item = state.item, onBack = onLeave) }
            }
        }
    }
}

@Composable
private fun CantOpen(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Text(
            "Can't open this file",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "The app that shared it may no longer allow access. Open it again from that app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        Button(onClick = onClose) { Text("Close") }
    }
}

sealed interface ViewerState {
    data object Loading : ViewerState
    data object Failed : ViewerState
    data class Ready(val item: LibraryItem) : ViewerState
}

/** Resolves the incoming uri once and keeps it across rotation. */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf<ViewerState>(ViewerState.Loading)
        private set

    private var uri: Uri? = null
    private var job: Job? = null

    fun open(uri: Uri?) {
        if (uri == null) {
            state = ViewerState.Failed
            return
        }
        if (uri == this.uri) return
        this.uri = uri
        state = ViewerState.Loading
        job?.cancel()
        job = viewModelScope.launch {
            val shared = withContext(Dispatchers.IO) {
                FileScanner.documentFromUri(getApplication(), uri)
            }
            if (shared == null) {
                state = ViewerState.Failed
                return@launch
            }
            // The shared link expires later, so read and remember the real file instead —
            // History and Continue reading can then reopen it any time.
            val doc = DocumentResolver.makeDurable(getApplication(), shared, Graph.library)
            if (doc.id != shared.id) Graph.stateStore.migrate(shared.id, doc.id)
            Graph.appScope.launch { Graph.history.record(doc, replaces = shared.id) }
            Graph.stateStore.awaitLoaded() // so the saved page/zoom is there on a cold start
            state = ViewerState.Ready(LibraryItem(doc, Graph.stateStore.get(doc.id)))
        }
    }
}
