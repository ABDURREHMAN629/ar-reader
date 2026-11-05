package com.ahif.lumen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ahif.lumen.data.library.DocumentResolver
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.di.Graph
import com.ahif.lumen.ui.home.FavoritesScreen
import com.ahif.lumen.ui.home.LibraryScreen
import com.ahif.lumen.ui.home.RecentScreen
import com.ahif.lumen.ui.reader.ReaderRoute
import kotlinx.coroutines.launch
import com.ahif.lumen.ui.settings.SettingsScreen
import com.ahif.lumen.ui.theme.LumenTheme
import com.ahif.lumen.ui.tools.ToolsScreen

private enum class TopDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector
) {
    LIBRARY("library", "Library", Icons.Filled.LocalLibrary, Icons.Outlined.LocalLibrary),
    RECENT("recent", "History", Icons.Filled.History, Icons.Outlined.History),
    TOOLS("tools", "Tools", Icons.Filled.Build, Icons.Outlined.Build),
    FAVORITES("favorites", "Saved", Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by Graph.settings.settings.collectAsStateWithLifecycle(
                initialValue = com.ahif.lumen.data.prefs.AppSettings()
            )
            LumenTheme(
                themeMode = settings.themeMode,
                accentId = settings.accentId,
                dynamicColor = settings.dynamicColor,
                amoled = settings.amoledDark
            ) {
                BrightnessController(settings.brightness)
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val selectedItem = remember { mutableStateOf<LibraryItem?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Files opened from other apps (WhatsApp, Files…) go to ViewerActivity instead.
    val openDocument: (LibraryItem) -> Unit = { item ->
        scope.launch {
            val ready = prepareToOpen(context, item) ?: return@launch
            Graph.appScope.launch { Graph.history.record(ready.document, replaces = item.document.id) }
            selectedItem.value = ready
            navController.navigate("reader") { launchSingleTop = true }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = TopDestination.entries.any { dest ->
        currentDestination?.hierarchy?.any { it.route == dest.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopDestination.entries.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) dest.selectedIcon else dest.icon,
                                    contentDescription = dest.label
                                )
                            },
                            label = { Text(dest.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.LIBRARY.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(TopDestination.LIBRARY.route) {
                LibraryScreen(onOpenDocument = openDocument)
            }
            composable(TopDestination.RECENT.route) {
                RecentScreen(onOpenDocument = openDocument)
            }
            composable(TopDestination.TOOLS.route) {
                ToolsScreen(onOpenDocument = openDocument)
            }
            composable(TopDestination.FAVORITES.route) {
                FavoritesScreen(onOpenDocument = openDocument)
            }
            composable(TopDestination.SETTINGS.route) {
                SettingsScreen()
            }
            composable("reader") {
                ReaderRoute(
                    item = selectedItem.value,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

/**
 * Checks the file can still be read before showing a reader (instead of a spinner that
 * never ends). An old History entry holding an expired shared link (WhatsApp…) is
 * swapped for the same file found on disk when possible.
 */
private suspend fun prepareToOpen(context: Context, item: LibraryItem): LibraryItem? {
    val doc = item.document
    if (DocumentResolver.isReadable(context, doc)) return item
    if (doc.uri.scheme != "file") {
        val found = DocumentResolver.makeDurable(context, doc, Graph.library)
        if (found.uri.scheme == "file" && DocumentResolver.isReadable(context, found)) {
            Graph.stateStore.migrate(doc.id, found.id)
            return LibraryItem(found, Graph.stateStore.get(found.id))
        }
        Toast.makeText(
            context,
            "This file was shared from another app and can't be opened here any more. Open it again from that app.",
            Toast.LENGTH_LONG
        ).show()
    } else {
        Toast.makeText(context, "File not found. It may have been moved or deleted.", Toast.LENGTH_LONG).show()
    }
    return null
}

/** Applies an app-local screen brightness override (independent of system brightness). */
@Composable
internal fun BrightnessController(brightness: Float) {
    val view = LocalView.current
    LaunchedEffect(brightness) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val lp = window.attributes
        lp.screenBrightness = if (brightness < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            brightness.coerceIn(0.02f, 1f)
        }
        window.attributes = lp
    }
}

internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
