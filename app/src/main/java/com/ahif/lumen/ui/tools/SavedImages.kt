package com.ahif.lumen.ui.tools

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ahif.lumen.data.model.Document
import com.ahif.lumen.data.model.DocumentType
import com.ahif.lumen.data.tools.MediaSaver
import com.ahif.lumen.data.tools.SavedImage
import com.ahif.lumen.ui.util.DocumentActions
import com.ahif.lumen.ui.util.formatSize
import kotlinx.coroutines.launch

internal fun SavedImage.asDocument() =
    Document(uri.toString(), uri, name, DocumentType.IMAGE, size, dateAdded * 1000, "AR Reader")

internal val SavedImage.ext: String get() = name.substringAfterLast('.', "").lowercase()

/** Formats Android (and so this app's image viewer) can decode. */
private val viewableExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "ico", "heic", "heif", "avif")

/** Opens in the app when Android can display the format, otherwise offers other apps. */
internal fun openSavedImage(context: Context, img: SavedImage, openInApp: (SavedImage) -> Unit) {
    if (img.ext in viewableExt) openInApp(img)
    else DocumentActions.openWith(context, img.uri, MimeTypeMap.getSingleton().getMimeTypeFromExtension(img.ext) ?: "image/*")
}

/** Thumbnail that falls back to the file extension for formats Android can't draw (TIFF, PSD…). */
@Composable
internal fun FormatThumbnail(uri: Uri, ext: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(ext.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** A horizontal strip of images a tool has saved, with Open / Share / Delete. */
@Composable
fun SavedImagesSection(
    title: String,
    folder: String,
    refreshKey: Int,
    onOpen: (SavedImage) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localRefresh by remember { mutableIntStateOf(0) }
    val images by produceState(initialValue = emptyList<SavedImage>(), refreshKey, localRefresh) {
        value = MediaSaver.list(context, folder)
    }
    var selected by remember { mutableStateOf<SavedImage?>(null) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${images.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Saved in Pictures/AR Reader/$folder",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (images.isEmpty()) {
            Text(
                "Nothing saved yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                items(images, key = { it.uri.toString() }) { img ->
                    Column(Modifier.width(96.dp).clickable { selected = img }) {
                        FormatThumbnail(img.uri, img.ext, 96.dp)
                        Text(
                            "${img.ext.uppercase()} · ${formatSize(img.size)}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }

    selected?.let { img ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(img.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("${formatSize(img.size)} · Pictures/AR Reader/$folder") },
            confirmButton = {
                Row {
                    TextButton(onClick = { selected = null; openSavedImage(context, img, onOpen) }) {
                        Text(if (img.ext in viewableExt) "Open" else "Open with…")
                    }
                    TextButton(onClick = { DocumentActions.share(context, listOf(img.asDocument())) }) { Text("Share") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        MediaSaver.delete(context, img.uri)
                        selected = null
                        localRefresh++
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}
