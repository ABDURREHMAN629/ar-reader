package com.ahif.lumen.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.di.Graph
import kotlinx.coroutines.launch

/** Reading themes shared by every reader. The id is persisted per document (see [DocReadingTheme]). */
enum class ReaderThemeId(val id: String, val label: String, val bg: Color, val fg: Color) {
    SYSTEM("system", "System", Color(0xFF9E9E9E), Color(0xFFFFFFFF)),
    LIGHT("light", "Light", Color(0xFFFFFFFF), Color(0xFF1B1B1B)),
    SEPIA("sepia", "Sepia", Color(0xFFF4ECD8), Color(0xFF5B4636)),
    NIGHT("night", "Night", Color(0xFF14161A), Color(0xFFC9CBD1)),
    BLACK("black", "Black", Color(0xFF000000), Color(0xFFB4B4B4)),
    CONSOLE("console", "Console", Color(0xFF000000), Color(0xFF5CF08A));

    companion object {
        fun of(id: String?): ReaderThemeId = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * The reading theme of one document. Every new document opens in [ReaderThemeId.LIGHT];
 * picking another theme applies to (and is remembered for) that document only.
 */
class DocReadingTheme(private val docId: String, initial: ReaderThemeId) {
    var current by mutableStateOf(initial)
        private set

    fun set(theme: ReaderThemeId) {
        current = theme
        Graph.appScope.launch { Graph.stateStore.saveReadingTheme(docId, theme.id) }
    }
}

@Composable
fun rememberDocReadingTheme(item: LibraryItem): DocReadingTheme = remember(item.document.id) {
    DocReadingTheme(item.document.id, item.state.readingTheme?.let(ReaderThemeId::of) ?: ReaderThemeId.LIGHT)
}

/** One tappable row in the reader's ⋮ menu. */
data class ReaderAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Extra rows the host screen appends to every reader's ⋮ menu (e.g. "Close" in the external viewer). */
val LocalHostReaderActions = staticCompositionLocalOf<List<ReaderAction>> { emptyList() }

/**
 * The reader's three-dot overflow menu: general actions on top, then a
 * "Reading theme" section listing [themes] by name with a check on [current].
 */
@Composable
fun ReaderOverflowMenu(
    actions: List<ReaderAction>,
    themes: List<ReaderThemeId>,
    current: ReaderThemeId,
    onTheme: (ReaderThemeId) -> Unit,
    tint: Color = LocalContentColor.current
) {
    var open by remember { mutableStateOf(false) }
    val actions = actions + LocalHostReaderActions.current
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = tint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label) },
                    leadingIcon = { Icon(a.icon, contentDescription = null) },
                    onClick = { open = false; a.onClick() }
                )
            }
            if (themes.isNotEmpty()) {
                if (actions.isNotEmpty()) HorizontalDivider()
                Text(
                    "Reading theme",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                )
                themes.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.label) },
                        leadingIcon = { ThemeSwatch(t) },
                        trailingIcon = { if (t == current) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                        onClick = { open = false; onTheme(t) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(t: ReaderThemeId) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(t.bg)
            .border(3.dp, t.fg, CircleShape)
    )
}
