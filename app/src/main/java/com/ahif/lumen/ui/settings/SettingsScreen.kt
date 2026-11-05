package com.ahif.lumen.ui.settings

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahif.lumen.data.model.ThemeMode
import com.ahif.lumen.ui.theme.AccentColor
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp)
            )
        }

        item { SectionCard(title = "Appearance") {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(8.dp))
            ThemeModeSelector(state.themeMode, vm::setThemeMode)

            Spacer(Modifier.size(16.dp))
            SwitchRow(
                title = "Dynamic colour",
                subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    "Match your wallpaper (Material You)"
                else "Needs Android 12 or newer",
                checked = state.dynamicColor,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onCheckedChange = vm::setDynamicColor
            )

            if (!state.dynamicColor) {
                Spacer(Modifier.size(16.dp))
                Text("Accent colour", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(10.dp))
                AccentPicker(selectedId = state.accentId, onSelect = vm::setAccent)
            }

            Spacer(Modifier.size(8.dp))
            SwitchRow(
                title = "AMOLED black",
                subtitle = "Pure-black background in dark mode",
                checked = state.amoledDark,
                onCheckedChange = vm::setAmoled
            )
        } }

        item { SectionCard(title = "Reading") {
            SwitchRow(
                title = "Keep screen on",
                subtitle = "Prevent the display from sleeping while reading",
                checked = state.keepScreenOn,
                onCheckedChange = vm::setKeepScreenOn
            )
            Spacer(Modifier.size(12.dp))
            Text("Text size", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = state.readerFontScale,
                    onValueChange = vm::setFontScale,
                    valueRange = 0.7f..2.0f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(12.dp))
                Text("${(state.readerFontScale * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.size(12.dp))
            SwitchRow(
                title = "App brightness",
                subtitle = "Keep a fixed brightness in the app, unaffected by system changes",
                checked = state.brightness >= 0f,
                onCheckedChange = { on -> vm.setBrightness(if (on) 0.6f else -1f) }
            )
            if (state.brightness >= 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = state.brightness,
                        onValueChange = vm::setBrightness,
                        valueRange = 0.05f..1f,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("${(state.brightness * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            }
        } }

        if (state.folders.isNotEmpty()) {
            item { SectionCard(title = "Folders") {
                state.folders.forEach { uri ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            folderName(uri),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )
                        IconButton(onClick = { vm.removeFolder(uri) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove folder")
                        }
                    }
                }
            } }
        }

        item { SectionCard(title = "About") {
            Text("AR Reader", style = MaterialTheme.typography.titleMedium)
            Text(
                "Version 1.0  ·  Reads PDF, EPUB, comics, images and text.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size)
            ) {
                Text(mode.label)
            }
        }
    }
}

@Composable
private fun AccentPicker(selectedId: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(AccentColor.entries) { accent ->
            val color = accent.light.primary
            val isSelected = accent.id == selectedId
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                    .clickable { onSelect(accent.id) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Filled.Check, contentDescription = accent.displayName, tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private fun folderName(uri: String): String = runCatching {
    val treeUri = Uri.parse(uri)
    val id = DocumentsContract.getTreeDocumentId(treeUri)
    id.substringAfterLast('/').substringAfterLast(':').ifBlank { id }
}.getOrElse { uri }
