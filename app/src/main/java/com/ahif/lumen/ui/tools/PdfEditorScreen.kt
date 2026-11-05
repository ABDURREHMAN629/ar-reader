package com.ahif.lumen.ui.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Path as ComposePath
import com.ahif.lumen.data.reader.PdfBuilder
import com.ahif.lumen.data.reader.PdfDocumentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A pen stroke stored in normalised page coordinates (0..1) so it scales to any size. */
private data class PenStroke(val points: List<Offset>, val color: Color, val widthFraction: Float)

private data class Pen(val label: String, val color: Color, val widthFraction: Float)

private val pens = listOf(
    Pen("Black", Color(0xFF111111), 0.004f),
    Pen("Red", Color(0xFFE53935), 0.004f),
    Pen("Blue", Color(0xFF1E88E5), 0.004f),
    Pen("Green", Color(0xFF2E7D32), 0.004f),
    Pen("Highlight", Color(0x66FFEB3B), 0.03f)
)

private const val EXPORT_WIDTH = 1400

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfEditorScreen(uri: Uri, onClose: () -> Unit, onSaved: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sourceState = produceState<PdfDocumentSource?>(initialValue = null, key1 = uri) {
        val src = PdfDocumentSource.open(context, uri)
        value = src
        awaitDispose { src?.close() }
    }
    val source = sourceState.value
    val pageCount = source?.pageCount ?: 0

    var currentPage by remember { mutableIntStateOf(0) }
    var penIndex by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val pageStrokes = remember { mutableStateMapOf<Int, SnapshotStateList<PenStroke>>() }
    val pen = pens[penIndex]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit PDF", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val src = source ?: return@IconButton
                            saving = true
                            scope.launch {
                                val file = exportPdf(context, src, pageStrokes)
                                saving = false
                                if (file != null) onSaved(file)
                            }
                        },
                        enabled = source != null && !saving
                    ) { Icon(Icons.Filled.Save, contentDescription = "Save") }
                }
            )
        },
        bottomBar = {
            EditorControls(
                pens = pens,
                selected = penIndex,
                onSelect = { penIndex = it },
                onUndo = { pageStrokes[currentPage]?.let { if (it.isNotEmpty()) it.removeAt(it.size - 1) } },
                page = currentPage,
                total = pageCount,
                onPrev = { if (currentPage > 0) currentPage-- },
                onNext = { if (currentPage < pageCount - 1) currentPage++ }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFF2B2B2B))) {
            if (source == null) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.Center))
            } else {
                val density = LocalDensity.current
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.TopCenter) {
                    val widthPx = with(density) { maxWidth.toPx() }.toInt()
                    val aspect by produceState(initialValue = 1.4f, key1 = currentPage, key2 = source) {
                        value = source.pageAspect(currentPage)
                    }
                    val heightDp = with(density) { (widthPx * aspect).toDp() }
                    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = currentPage, key2 = widthPx, key3 = source) {
                        value = source.renderPage(currentPage, widthPx)
                    }
                    Box(modifier = Modifier.width(maxWidth).height(heightDp).background(Color.White)) {
                        val bmp = bitmap
                        if (bmp != null) {
                            Image(bitmap = bmp.asImageBitmap(), contentDescription = "Page", modifier = Modifier.fillMaxSize())
                        }
                        DrawArea(
                            strokes = pageStrokes[currentPage] ?: emptyList(),
                            pen = pen,
                            onAddStroke = { pageStrokes.getOrPut(currentPage) { mutableStateListOf() }.add(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            if (saving) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DrawArea(
    strokes: List<PenStroke>,
    pen: Pen,
    onAddStroke: (PenStroke) -> Unit,
    modifier: Modifier = Modifier
) {
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    ComposeCanvas(
        modifier = modifier.pointerInput(pen) {
            detectDragGestures(
                onDragStart = { current = listOf(it) },
                onDrag = { change, _ -> current = current + change.position; change.consume() },
                onDragEnd = {
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    if (current.size > 1 && w > 0 && h > 0) {
                        onAddStroke(PenStroke(current.map { Offset(it.x / w, it.y / h) }, pen.color, pen.widthFraction))
                    }
                    current = emptyList()
                },
                onDragCancel = { current = emptyList() }
            )
        }
    ) {
        val w = size.width; val h = size.height
        strokes.forEach { s ->
            if (s.points.size >= 2) {
                val path = ComposePath().apply {
                    moveTo(s.points[0].x * w, s.points[0].y * h)
                    s.points.drop(1).forEach { lineTo(it.x * w, it.y * h) }
                }
                drawPath(path, s.color, style = Stroke(width = s.widthFraction * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (current.size > 1) {
            val path = ComposePath().apply {
                moveTo(current[0].x, current[0].y)
                current.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, pen.color, style = Stroke(width = pen.widthFraction * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun EditorControls(
    pens: List<Pen>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onUndo: () -> Unit,
    page: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            pens.forEachIndexed { index, p ->
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(p.color.copy(alpha = 1f))
                        .border(
                            width = if (index == selected) 3.dp else 1.dp,
                            color = if (index == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                        .clickable { onSelect(index) }
                )
            }
            Box(Modifier.weight(1f))
            IconButton(onClick = onUndo) { Icon(Icons.Filled.Undo, contentDescription = "Undo") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = onPrev, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous page") }
            Text("Page ${page + 1} of $total", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = onNext, enabled = page < total - 1) { Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next page") }
        }
    }
}

private suspend fun exportPdf(
    context: android.content.Context,
    source: PdfDocumentSource,
    pageStrokes: Map<Int, List<PenStroke>>
): File? = withContext(Dispatchers.IO) {
    runCatching {
        val document = PdfDocument()
        for (p in 0 until source.pageCount) {
            val base = source.renderPage(p, EXPORT_WIDTH) ?: continue
            val mutable = if (base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutable)
            pageStrokes[p]?.forEach { s ->
                if (s.points.size >= 2) {
                    val paint = Paint().apply {
                        color = s.color.toArgb()
                        style = Paint.Style.STROKE
                        strokeWidth = s.widthFraction * mutable.width
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true
                    }
                    val path = Path()
                    s.points.forEachIndexed { i, pt ->
                        val x = pt.x * mutable.width
                        val y = pt.y * mutable.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    canvas.drawPath(path, paint)
                }
            }
            val info = PdfDocument.PageInfo.Builder(mutable.width, mutable.height, p + 1).create()
            val page = document.startPage(info)
            page.canvas.drawBitmap(mutable, 0f, 0f, null)
            document.finishPage(page)
            if (mutable !== base) base.recycle()
        }
        val out = PdfBuilder.uniqueOutput(context, "edited")
        out.outputStream().use { document.writeTo(it) }
        document.close()
        out
    }.getOrNull()
}
