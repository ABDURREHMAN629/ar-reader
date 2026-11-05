package com.ahif.lumen.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A bottom page scrubber: a slider to jump between pages plus a tappable page
 * number that opens a "go to page" dialog. [current] and the values passed to
 * [onSeek] are 0-based.
 */
@Composable
fun PageScrubber(
    current: Int,
    total: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black.copy(alpha = 0.6f),
    contentColor: Color = Color.White
) {
    if (total <= 1) return
    var dragging by remember { mutableStateOf(false) }
    var temp by remember { mutableFloatStateOf(current.toFloat()) }
    var showDialog by remember { mutableStateOf(false) }

    val display = (if (dragging) temp.roundToInt() else current).coerceIn(0, total - 1)

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${display + 1}",
                color = contentColor,
                modifier = Modifier
                    .clickable { showDialog = true }
                    .padding(end = 10.dp)
            )
            Slider(
                value = (if (dragging) temp else current.toFloat()).coerceIn(0f, (total - 1).toFloat()),
                onValueChange = { dragging = true; temp = it },
                onValueChangeFinished = { onSeek(temp.roundToInt().coerceIn(0, total - 1)); dragging = false },
                valueRange = 0f..(total - 1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = contentColor,
                    activeTrackColor = contentColor,
                    inactiveTrackColor = contentColor.copy(alpha = 0.3f)
                ),
                modifier = Modifier.weight(1f)
            )
            Text("$total", color = contentColor, modifier = Modifier.padding(start = 10.dp))
        }
    }

    if (showDialog) {
        GoToPageDialog(total = total, onGo = { onSeek(it) }, onDismiss = { showDialog = false })
    }
}

@Composable
internal fun GoToPageDialog(total: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { s -> text = s.filter { it.isDigit() }.take(6) },
                label = { Text("Page (1–$total)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val page = text.toIntOrNull()
                    if (page != null) onGo((page - 1).coerceIn(0, total - 1))
                    onDismiss()
                },
                enabled = text.isNotBlank()
            ) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
