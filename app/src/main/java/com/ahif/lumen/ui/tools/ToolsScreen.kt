package com.ahif.lumen.ui.tools

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.ahif.lumen.data.model.DocState
import com.ahif.lumen.data.model.Document
import com.ahif.lumen.data.model.DocumentType
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.reader.BitmapUtils
import com.ahif.lumen.data.reader.ImageFilters
import com.ahif.lumen.data.reader.PdfBuilder
import com.ahif.lumen.data.reader.ScanFilter
import com.ahif.lumen.data.tools.SavedImage
import com.ahif.lumen.di.Graph
import com.ahif.lumen.ui.util.DocumentActions
import kotlinx.coroutines.launch
import java.io.File

private enum class ToolMode { MENU, TEXT, REVIEW }
private data class Tool(val title: String, val subtitle: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onOpenDocument: (LibraryItem) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(ToolMode.MENU) }
    var busy by remember { mutableStateOf(false) }
    var resultFile by remember { mutableStateOf<File?>(null) }

    val pages = remember { mutableStateListOf<Uri>() }
    var isScan by remember { mutableStateOf(false) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    fun documentFor(file: File): Document {
        val uri = Uri.fromFile(file)
        return Document(uri.toString(), uri, file.name, DocumentType.PDF, file.length(), file.lastModified(), file.parentFile?.name)
    }

    fun finish(file: File?) {
        busy = false
        if (file != null) {
            resultFile = file
            Graph.library.refresh()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Some OEM cameras return RESULT_CANCELED even on success; trust the file instead.
        val file = pendingCameraFile
        if (file != null && file.exists() && file.length() > 0) {
            pages.add(Uri.fromFile(file))
        }
    }

    var editorUri by remember { mutableStateOf<Uri?>(null) }
    // Survives opening a saved image in the reader and coming back.
    var activeTool by rememberSaveable { mutableStateOf<String?>(null) }
    val openSaved: (SavedImage) -> Unit = { img -> onOpenDocument(LibraryItem(img.asDocument(), DocState())) }
    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) editorUri = uri }

    fun capture() {
        val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        pendingCameraFile = file
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(null, uri)
        }
        runCatching { cameraLauncher.launch(intent) }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pages.clear(); pages.addAll(uris); isScan = false; mode = ToolMode.REVIEW
        }
    }

    val ed = editorUri
    if (ed != null) {
        BackHandler { editorUri = null }
        PdfEditorScreen(
            uri = ed,
            onClose = { editorUri = null },
            onSaved = { file -> editorUri = null; finish(file) }
        )
        return
    }
    when (activeTool) {
        "compress" -> { CompressorScreen(onClose = { activeTool = null }, onOpenImage = openSaved); return }
        "background" -> { BackgroundScreen(onClose = { activeTool = null }, onOpenImage = openSaved); return }
        "convert" -> {
            ConverterScreen(onClose = { activeTool = null }, onOpenDocument = { doc -> onOpenDocument(LibraryItem(doc, DocState())) })
            return
        }
    }

    // System back inside a sub-tool returns to the tool list instead of leaving the tab.
    BackHandler(enabled = mode != ToolMode.MENU) { mode = ToolMode.MENU; pages.clear() }

    Scaffold(
        topBar = {
            if (mode != ToolMode.MENU) {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                mode == ToolMode.TEXT -> "Text to PDF"
                                isScan -> "Scan to PDF"
                                else -> "Images to PDF"
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { mode = ToolMode.MENU; pages.clear() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (mode) {
                ToolMode.MENU -> ToolMenu(
                    onImageToPdf = { imagePicker.launch(arrayOf("image/*")) },
                    onTextToPdf = { mode = ToolMode.TEXT },
                    onScan = { pages.clear(); isScan = true; mode = ToolMode.REVIEW; capture() },
                    onEdit = { pdfPicker.launch(arrayOf("application/pdf")) },
                    onCompress = { activeTool = "compress" },
                    onBackground = { activeTool = "background" },
                    onConvert = { activeTool = "convert" }
                )
                ToolMode.TEXT -> TextToPdf(onCreate = { title, body ->
                    busy = true
                    scope.launch { finish(PdfBuilder.textToPdf(context, body, title)); mode = ToolMode.MENU }
                })
                ToolMode.REVIEW -> PdfReview(
                    pages = pages,
                    isScan = isScan,
                    defaultName = if (isScan) "scan" else "images",
                    onAddPage = { if (isScan) capture() else imagePicker.launch(arrayOf("image/*")) },
                    onCreate = { name, filter ->
                        busy = true
                        val list = pages.toList()
                        scope.launch { finish(PdfBuilder.imagesToPdf(context, list, name, filter)); mode = ToolMode.MENU }
                    }
                )
            }

            if (busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    resultFile?.let { file ->
        AlertDialog(
            onDismissRequest = { resultFile = null },
            title = { Text("PDF created") },
            text = { Text("Saved as ${file.name} in Documents/AR Reader.") },
            confirmButton = {
                TextButton(onClick = {
                    val doc = documentFor(file)
                    resultFile = null
                    onOpenDocument(LibraryItem(doc, DocState()))
                }) { Text("Open") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { DocumentActions.share(context, listOf(documentFor(file))) }) { Text("Share") }
                    TextButton(onClick = { resultFile = null }) { Text("Close") }
                }
            }
        )
    }

}

@Composable
private fun ToolMenu(
    onImageToPdf: () -> Unit,
    onTextToPdf: () -> Unit,
    onScan: () -> Unit,
    onEdit: () -> Unit,
    onCompress: () -> Unit,
    onBackground: () -> Unit,
    onConvert: () -> Unit
) {
    val tools = listOf(
        Tool("Image to PDF", "Combine photos into a PDF", Icons.Filled.PhotoLibrary),
        Tool("Text to PDF", "Type or paste text, export a PDF", Icons.Filled.TextFields),
        Tool("Scan to PDF", "Snap pages, boost the text", Icons.Filled.DocumentScanner),
        Tool("Edit PDF", "Draw, highlight & sign", Icons.Filled.Edit),
        Tool("Compress Images", "Shrink photos, keep quality", Icons.Filled.Compress),
        Tool("Change Background", "Remove or replace backgrounds", Icons.Filled.Wallpaper),
        Tool("Convert Format", "JPG, PNG, WebP, HEIC, GIF, TIFF, ICO…", Icons.Filled.Transform)
    )
    Column(Modifier.fillMaxSize()) {
        Text(
            "Tools",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            gridItems(tools) { tool ->
                Card(
                    modifier = Modifier.aspectRatio(1.1f).clickable {
                        when (tool.title) {
                            "Image to PDF" -> onImageToPdf()
                            "Text to PDF" -> onTextToPdf()
                            "Scan to PDF" -> onScan()
                            "Compress Images" -> onCompress()
                            "Change Background" -> onBackground()
                            "Convert Format" -> onConvert()
                            else -> onEdit()
                        }
                    },
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(tool.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text(tool.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextToPdf(onCreate: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = { onCreate(title.ifBlank { "Document" }, body) }, enabled = body.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Create PDF")
        }
    }
}

@Composable
private fun PdfReview(
    pages: MutableList<Uri>,
    isScan: Boolean,
    defaultName: String,
    onAddPage: () -> Unit,
    onCreate: (String, ScanFilter) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(defaultName) }
    var filter by remember { mutableStateOf(if (isScan) ScanFilter.ENHANCE else ScanFilter.ORIGINAL) }
    var previewIndex by remember { mutableStateOf(0) }
    val safeIndex = previewIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

    Column(Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            item {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            item {
                val previewUri = pages.getOrNull(safeIndex)
                if (previewUri != null) {
                    Text(
                        "Preview — page ${safeIndex + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    FilterPreview(context = context, uri = previewUri, filter = filter)
                    Spacer(Modifier.height(10.dp))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ScanFilter.entries) { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            item {
                OutlinedButton(onClick = onAddPage, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (isScan) Icons.Filled.AddAPhoto else Icons.Filled.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (isScan) "Add another page" else "Add more images")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${pages.size} page(s) · tap a page to preview",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            items(pages.size) { index ->
                PageRow(
                    uri = pages[index],
                    index = index,
                    total = pages.size,
                    selected = index == safeIndex,
                    onClick = { previewIndex = index },
                    onRemove = {
                        if (index < pages.size) {
                            pages.removeAt(index)
                            if (previewIndex >= pages.size) previewIndex = (pages.size - 1).coerceAtLeast(0)
                        }
                    },
                    onUp = { if (index > 0) { val u = pages.removeAt(index); pages.add(index - 1, u); previewIndex = index - 1 } },
                    onDown = { if (index < pages.size - 1) { val u = pages.removeAt(index); pages.add(index + 1, u); previewIndex = index + 1 } }
                )
            }
        }
        FilledTonalButton(
            onClick = { onCreate(name.ifBlank { defaultName }, filter) },
            enabled = pages.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Create PDF (${pages.size})")
        }
    }
}

@Composable
private fun PageRow(
    uri: Uri,
    index: Int,
    total: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = uri,
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 54.dp, height = 70.dp).clip(RoundedCornerShape(6.dp))
        )
        Text("Page ${index + 1}", modifier = Modifier.weight(1f).padding(start = 12.dp))
        IconButton(onClick = onUp, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
        IconButton(onClick = onDown, enabled = index < total - 1) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
    }
}

@Composable
private fun FilterPreview(context: android.content.Context, uri: Uri, filter: ScanFilter) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uri, key2 = filter) {
        value = runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val base = bytes?.let { BitmapUtils.decodeSampled(it, 700) }
            base?.let { ImageFilters.apply(it, filter) }
        }.getOrNull()
    }
    val bmp = bitmap
    Box(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = "Preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}
