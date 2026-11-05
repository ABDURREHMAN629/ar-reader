package com.ahif.lumen.ui.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahif.lumen.data.prefs.AppSettings
import com.ahif.lumen.di.Graph
import kotlinx.coroutines.launch

/**
 * A floating brightness slider for use inside readers. Writes the app-local
 * brightness override (persisted; applied window-wide by MainActivity), so it
 * stays fixed and ignores system brightness.
 */
@Composable
fun BrightnessOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black.copy(alpha = 0.6f),
    contentColor: Color = Color.White
) {
    if (!visible) return
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val value = if (settings.brightness < 0f) 0.6f else settings.brightness.coerceIn(0.05f, 1f)

    Surface(color = containerColor, shape = RoundedCornerShape(24.dp), modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.BrightnessLow, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
            Slider(
                value = value,
                onValueChange = { v -> scope.launch { Graph.settings.setBrightness(v.coerceIn(0.05f, 1f)) } },
                valueRange = 0.05f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = contentColor,
                    activeTrackColor = contentColor,
                    inactiveTrackColor = contentColor.copy(alpha = 0.3f)
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
            )
            Icon(Icons.Filled.BrightnessHigh, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
        }
    }
}
