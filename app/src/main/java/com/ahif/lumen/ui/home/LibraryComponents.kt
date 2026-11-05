package com.ahif.lumen.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ahif.lumen.data.model.DocumentType
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.reader.ThumbnailLoader
import com.ahif.lumen.ui.util.formatSize
import androidx.compose.material3.Text

data class FormatVisual(val icon: ImageVector, val color: Color)

fun visualFor(type: DocumentType): FormatVisual = when (type) {
    DocumentType.PDF -> FormatVisual(Icons.Filled.PictureAsPdf, Color(0xFFE0524A))
    DocumentType.EPUB -> FormatVisual(Icons.Filled.MenuBook, Color(0xFF5B7CFA))
    DocumentType.TEXT -> FormatVisual(Icons.Filled.Description, Color(0xFF5F8DA6))
    DocumentType.MARKDOWN -> FormatVisual(Icons.Filled.Article, Color(0xFF2FA6A0))
    DocumentType.IMAGE -> FormatVisual(Icons.Filled.Image, Color(0xFF43A66B))
    DocumentType.COMIC -> FormatVisual(Icons.Filled.AutoStories, Color(0xFFE08A2E))
    DocumentType.WORD -> FormatVisual(Icons.Filled.Description, Color(0xFF2B579A))
    DocumentType.EXCEL -> FormatVisual(Icons.Filled.TableChart, Color(0xFF217346))
    DocumentType.POWERPOINT -> FormatVisual(Icons.Filled.Slideshow, Color(0xFFC13B1B))
    DocumentType.UNKNOWN -> FormatVisual(Icons.Filled.InsertDriveFile, Color(0xFF8A8A94))
}

@Composable
private fun ProgressStrip(progress: Float, modifier: Modifier = Modifier) {
    if (progress > 0f) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = modifier.fillMaxWidth().height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun GradientCover(visual: FormatVisual, label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(visual.color.copy(alpha = 0.9f), visual.color.copy(alpha = 0.55f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(visual.icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(46.dp))
        Surface(
            color = Color.Black.copy(alpha = 0.28f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
        ) {
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PdfThumb(item: LibraryItem, visual: FormatVisual) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = item.document.id) {
        value = ThumbnailLoader.pdfThumbnail(context, item.document)
    }
    val bmp = bitmap
    if (bmp != null) {
        Box(Modifier.fillMaxSize().background(Color.White)) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.document.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        GradientCover(visual, item.document.type.label)
    }
}

@Composable
fun DocumentCover(
    item: LibraryItem,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true
) {
    val doc = item.document
    val visual = visualFor(doc.type)
    Box(modifier = modifier.clip(RoundedCornerShape(16.dp))) {
        when (doc.type) {
            DocumentType.IMAGE -> AsyncImage(
                model = doc.uri,
                contentDescription = doc.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(visual.color.copy(alpha = 0.12f))
            )
            DocumentType.PDF -> PdfThumb(item, visual)
            else -> GradientCover(visual, doc.type.label)
        }
        if (showProgress) {
            ProgressStrip(item.state.progress, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentGridCard(
    item: LibraryItem,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val doc = item.document
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .padding(4.dp)
    ) {
        Box {
            DocumentCover(item = item, modifier = Modifier.fillMaxWidth().aspectRatio(0.72f))
            if (selectionMode) {
                SelectionBadge(selected, Modifier.align(Alignment.TopStart))
            } else {
                FavoriteButton(item.state.favorite, onToggleFavorite, Modifier.align(Alignment.TopEnd))
            }
            if (selected) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)))
            }
        }
        Text(
            text = doc.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp)
        )
        Text(
            text = subtitleFor(item),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 2.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentListRow(
    item: LibraryItem,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DocumentCover(item = item, modifier = Modifier.size(width = 52.dp, height = 68.dp))
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(item.document.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                subtitleFor(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.state.progress > 0f) {
                LinearProgressIndicator(
                    progress = { item.state.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
        if (selectionMode) {
            SelectionBadge(selected)
        } else {
            FavoriteButton(item.state.favorite, onToggleFavorite)
        }
    }
}

@Composable
private fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    Icon(
        imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
        contentDescription = if (selected) "Selected" else "Not selected",
        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        modifier = modifier.padding(6.dp).size(24.dp)
    )
}

@Composable
private fun FavoriteButton(favorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = if (favorite) 0.0f else 0.25f),
        shape = RoundedCornerShape(50),
        modifier = modifier.padding(6.dp)
    ) {
        Icon(
            imageVector = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (favorite) "Remove favourite" else "Add favourite",
            tint = if (favorite) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .combinedClickableSafe(onClick)
                .padding(6.dp)
                .size(20.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSafe(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

private fun subtitleFor(item: LibraryItem): String {
    val doc = item.document
    val parts = buildList {
        add(doc.type.label)
        if (doc.sizeBytes > 0) add(formatSize(doc.sizeBytes))
        val pct = (item.state.progress * 100).toInt()
        if (pct in 1..99) add("$pct%")
    }
    return parts.joinToString("  ·  ")
}
