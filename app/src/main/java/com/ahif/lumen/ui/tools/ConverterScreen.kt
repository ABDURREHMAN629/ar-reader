package com.ahif.lumen.ui.tools

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahif.lumen.data.model.Document
import com.ahif.lumen.data.model.DocumentType
import com.ahif.lumen.data.tools.ConvertOptions
import com.ahif.lumen.data.tools.ConvertResult
import com.ahif.lumen.data.tools.ICON_SIZES
import com.ahif.lumen.data.tools.ImageCompressor
import com.ahif.lumen.data.tools.ImageConverter
import com.ahif.lumen.data.tools.ImageSource
import com.ahif.lumen.data.tools.Resize
import com.ahif.lumen.data.tools.TargetFormat
import com.ahif.lumen.di.Graph
import com.ahif.lumen.ui.util.DocumentActions
import com.ahif.lumen.ui.util.formatSize
import kotlinx.coroutines.launch

private val convertResizeChoices: List<Pair<String, Resize>> = listOf(
    "Original" to Resize.Original,
    "75%" to Resize.Percent(75),
    "50%" to Resize.Percent(50),
    "25%" to Resize.Percent(25),
    "Max 4096" to Resize.MaxSide(4096),
    "Max 2048" to Resize.MaxSide(2048),
    "Max 1920" to Resize.MaxSide(1920),
    "Max 1280" to Resize.MaxSide(1280),
    "Max 1080" to Resize.MaxSide(1080),
    "Max 720" to Resize.MaxSide(720),
    "Max 512" to Resize.MaxSide(512)
)

private val backgroundChoices = listOf(
    "White" to android.graphics.Color.WHITE,
    "Black" to android.graphics.Color.BLACK,
    "Grey" to 0xFF808080.toInt()
)

private fun tileTitle(f: TargetFormat) = if (f == TargetFormat.WEBP_LOSSLESS) "WebP" else f.label

private fun tileTag(f: TargetFormat) = when (f) {
    TargetFormat.JPG -> "Photos"
    TargetFormat.PNG -> "Lossless"
    TargetFormat.WEBP -> "Lossy · small"
    TargetFormat.WEBP_LOSSLESS -> "Lossless"
    TargetFormat.HEIC -> "High efficiency"
    TargetFormat.AVIF -> "Smallest"
    TargetFormat.GIF -> "256 colours"
    TargetFormat.BMP -> "Uncompressed"
    TargetFormat.TIFF -> "Print quality"
    TargetFormat.ICO -> "Icons"
    TargetFormat.TGA -> "Textures"
    TargetFormat.PSD -> "Photoshop"
    TargetFormat.PDF -> "Document"
}

private fun convertQualityHint(q: Int) = when {
    q >= 90 -> "Near-original quality"
    q >= 75 -> "High quality, much smaller"
    q >= 50 -> "Medium quality"
    else -> "Low quality — visible artefacts"
}

private fun extOf(name: String) = name.substringAfterLast('.', "").lowercase()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(onClose: () -> Unit, onOpenDocument: (Document) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sources = remember { mutableStateListOf<ImageSource>() }
    var format by remember { mutableStateOf(TargetFormat.JPG) }
    var quality by remember { mutableIntStateOf(90) }
    var resize by remember { mutableStateOf<Resize>(Resize.Original) }
    var background by remember { mutableIntStateOf(android.graphics.Color.WHITE) }
    var dither by remember { mutableStateOf(true) }
    val iconSizes = remember { mutableStateListOf(16, 32, 48, 256) }
    var keepDetails by remember { mutableStateOf(true) }
    var removeLocation by remember { mutableStateOf(true) }
    var combinePdf by remember { mutableStateOf(true) }
    var loadingPick by remember { mutableStateOf(false) }

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<ConvertResult>?>(null) }
    var resultFormat by remember { mutableStateOf(TargetFormat.JPG) }
    var savedRefresh by remember { mutableIntStateOf(0) }

    val unavailable = ImageConverter.unavailableReason(format)
    val options = ConvertOptions(
        format = format,
        quality = quality,
        resize = resize,
        background = background,
        dither = dither,
        iconSizes = iconSizes.toSet(),
        keepDetails = keepDetails,
        removeLocation = removeLocation,
        combinePdf = combinePdf
    )

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            loadingPick = true
            var failed = 0
            uris.forEach { u ->
                if (sources.none { it.uri == u }) {
                    val s = ImageCompressor.inspect(context, u)
                    if (s != null) sources.add(s) else failed++
                }
            }
            loadingPick = false
            if (failed > 0) {
                Toast.makeText(context, "$failed file(s) skipped — Android can't read that image type", Toast.LENGTH_LONG).show()
            }
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { addUris(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addUris(it) }
    fun pickGallery() = galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    fun pickFiles() = filePicker.launch(arrayOf("image/*"))

    fun runAll() {
        val list = sources.toList()
        val opts = options
        running = true
        progress = 0
        scope.launch {
            val out = if (opts.format == TargetFormat.PDF) {
                ImageConverter.convertToPdf(context, list, opts.combinePdf).also {
                    progress = list.size
                    Graph.library.refresh()
                }
            } else {
                list.map { src -> ImageConverter.convert(context, src, opts).also { progress++ } }
            }
            resultFormat = opts.format
            running = false
            results = out
            savedRefresh++
        }
    }

    fun documentFor(r: ConvertResult): Document? {
        val uri = r.uri ?: return null
        val type = if (resultFormat == TargetFormat.PDF) DocumentType.PDF else DocumentType.IMAGE
        return Document(uri.toString(), uri, r.name, type, r.bytes, System.currentTimeMillis(), "AR Reader")
    }

    fun openResult(r: ConvertResult) {
        val uri = r.uri ?: return
        if (resultFormat.viewableInApp) documentFor(r)?.let(onOpenDocument)
        else DocumentActions.openWith(context, uri, ImageConverter.mimeOf(resultFormat))
    }

    fun goBack() {
        when {
            running -> Unit
            // Back to the options with the same images, ready for another format.
            results != null -> results = null
            else -> onClose()
        }
    }
    BackHandler { goBack() }

    val canConvert = sources.isNotEmpty() && unavailable == null && !(format == TargetFormat.ICO && iconSizes.isEmpty())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Convert format") },
                navigationIcon = {
                    IconButton(onClick = { goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        bottomBar = {
            if (sources.isNotEmpty() && results == null && !running) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = { runAll() },
                        enabled = canConvert,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Icon(Icons.Filled.Transform, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            when {
                                unavailable != null -> "${format.label} isn't available on this phone"
                                format == TargetFormat.PDF && combinePdf && sources.size > 1 -> "Combine ${sources.size} images into one PDF"
                                else -> "Convert ${sources.size} image${if (sources.size == 1) "" else "s"} to ${format.label}"
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                running -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Converting $progress of ${sources.size}…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (sources.isEmpty()) 0f else progress.toFloat() / sources.size },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                results != null -> ConvertResultsView(
                    results = results!!,
                    format = resultFormat,
                    onOpen = { openResult(it) },
                    onShare = { list ->
                        val docs = list.mapNotNull { documentFor(it) }
                        if (docs.isNotEmpty()) DocumentActions.share(context, docs)
                    },
                    onAnotherFormat = { results = null },
                    onNewImages = { results = null; sources.clear() },
                    onDone = onClose
                )

                sources.isEmpty() -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.Transform, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text("Select images", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Convert one or many images to JPG, PNG, WebP, HEIC, AVIF, GIF, BMP, TIFF, ICO, TGA, PSD or PDF.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FilledTonalButton(onClick = { pickGallery() }) {
                                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.size(6.dp))
                                        Text("Gallery")
                                    }
                                    OutlinedButton(onClick = { pickFiles() }) {
                                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.size(6.dp))
                                        Text("Files")
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Reads JPG, PNG, WebP, HEIC/HEIF, AVIF, GIF, BMP, ICO and DNG. Use Files for images that aren't in the gallery.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                if (loadingPick) {
                                    Spacer(Modifier.height(12.dp))
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        SavedImagesSection("Converted images", ImageConverter.FOLDER, savedRefresh) { onOpenDocument(it.asDocument()) }
                    }
                }

                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
                    // Selected images
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${sources.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { pickGallery() }) { Text("Gallery") }
                            TextButton(onClick = { pickFiles() }) { Text("Files") }
                            TextButton(onClick = { sources.clear() }) { Text("Clear") }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(sources, key = { _, s -> s.uri.toString() }) { i, s ->
                                Column(Modifier.size(width = 72.dp, height = 96.dp)) {
                                    Box(Modifier.size(72.dp)) {
                                        FormatThumbnail(s.uri, extOf(s.name), 72.dp)
                                        Box(
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(2.dp)
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.55f))
                                                .clickable { if (i < sources.size) sources.removeAt(i) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    Text(
                                        "${extOf(s.name).uppercase()} · ${formatSize(s.bytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                        if (loadingPick) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        Spacer(Modifier.height(16.dp))
                    }

                    // Target format grid
                    item {
                        SectionTitle("Convert to")
                        TargetFormat.entries.chunked(3).forEach { rowFormats ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowFormats.forEach { f ->
                                    FormatTile(
                                        format = f,
                                        selected = f == format,
                                        available = ImageConverter.unavailableReason(f) == null,
                                        modifier = Modifier.weight(1f)
                                    ) { format = f }
                                }
                                repeat(3 - rowFormats.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(format.blurb, style = MaterialTheme.typography.bodyMedium)
                        if (unavailable != null) {
                            Text(unavailable, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        } else if (!format.viewableInApp) {
                            Text(
                                "Android can't preview ${format.label} — it opens in other apps and on computers.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    if (format.lossy) item {
                        SectionTitle("Quality")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = quality.toFloat(),
                                onValueChange = { quality = it.toInt() },
                                valueRange = 10f..100f,
                                modifier = Modifier.weight(1f)
                            )
                            Text("$quality%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                        Text(convertQualityHint(quality), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                    }

                    if (format == TargetFormat.GIF) item {
                        SectionTitle("Colours")
                        ToolSwitchRow(
                            "Smooth gradients (dithering)",
                            "Mixes the 256 colours so photos don't band. Off gives flatter, smaller files.",
                            dither
                        ) { dither = it }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (format == TargetFormat.ICO) item {
                        SectionTitle("Icon sizes")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ICON_SIZES) { s ->
                                FilterChip(
                                    selected = s in iconSizes,
                                    onClick = { if (s in iconSizes) iconSizes.remove(s) else iconSizes.add(s) },
                                    label = { Text("$s") }
                                )
                            }
                        }
                        Text(
                            if (iconSizes.isEmpty()) "Pick at least one size" else "All selected sizes go into one .ico file. The image is fitted into a square.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (iconSizes.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    if (format == TargetFormat.PDF && sources.size > 1) item {
                        SectionTitle("PDF")
                        ToolSwitchRow(
                            "Combine into one PDF",
                            if (combinePdf) "One document, one page per image" else "A separate PDF for every image",
                            combinePdf
                        ) { combinePdf = it }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (!format.keepsAlpha && format != TargetFormat.PDF) item {
                        SectionTitle("Transparent areas become")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(backgroundChoices) { (label, color) ->
                                FilterChip(selected = background == color, onClick = { background = color }, label = { Text(label) })
                            }
                        }
                        Text(
                            "${format.label} has no transparency. Only matters for images that have it (PNG, WebP, GIF…).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    if (format != TargetFormat.ICO && format != TargetFormat.PDF) item {
                        SectionTitle("Resize")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(convertResizeChoices) { (label, r) ->
                                FilterChip(selected = resize == r, onClick = { resize = r }, label = { Text(label) })
                            }
                        }
                        sources.firstOrNull()?.let { s ->
                            val (tw, th) = ImageCompressor.targetSize(s.width, s.height, resize)
                            Text(
                                "${s.width}×${s.height} → $tw×$th" + if (sources.size > 1) " (first image)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    if (format in setOf(TargetFormat.JPG, TargetFormat.PNG, TargetFormat.WEBP, TargetFormat.WEBP_LOSSLESS)) item {
                        SectionTitle("Photo details")
                        ToolSwitchRow("Keep photo details", "Date & camera info are copied", keepDetails) { keepDetails = it }
                        ToolSwitchRow("Remove location", "Strip GPS position for privacy", removeLocation, enabled = keepDetails) {
                            removeLocation = it
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    item {
                        SavedImagesSection("Converted images", ImageConverter.FOLDER, savedRefresh) { onOpenDocument(it.asDocument()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatTile(
    format: TargetFormat,
    selected: Boolean,
    available: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.primaryContainer else scheme.surfaceVariant.copy(alpha = 0.6f),
        border = if (selected) BorderStroke(2.dp, scheme.primary) else null,
        modifier = modifier.heightIn(min = 64.dp).alpha(if (available) 1f else 0.5f)
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                tileTitle(format),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface
            )
            Text(
                if (available) tileTag(format) else "Not on this phone",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ConvertResultsView(
    results: List<ConvertResult>,
    format: TargetFormat,
    onOpen: (ConvertResult) -> Unit,
    onShare: (List<ConvertResult>) -> Unit,
    onAnotherFormat: () -> Unit,
    onNewImages: () -> Unit,
    onDone: () -> Unit
) {
    val ok = results.filter { it.ok }
    val after = ok.sumOf { it.bytes }
    val before = if (format == TargetFormat.PDF) results.sumOf { it.source.bytes } else ok.sumOf { it.source.bytes }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (format == TargetFormat.PDF) "Done — ${ok.size} PDF${if (ok.size == 1) "" else "s"} created"
                        else "Done — ${ok.size} of ${results.size} converted to ${format.label}",
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (ok.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatSize(before)} → ${formatSize(after)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        if (format == TargetFormat.PDF) "Saved in Documents/AR Reader" else "Saved in Pictures/AR Reader/${ImageConverter.FOLDER}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onShare(ok) }, enabled = ok.isNotEmpty()) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(if (ok.size > 1) "Share all" else "Share")
                        }
                        OutlinedButton(onClick = onAnotherFormat) { Text("Another format") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onNewImages) { Text("New images") }
                        TextButton(onClick = onDone) { Text("Done") }
                    }
                }
            }
        }
        items(results) { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val uri = r.uri
                if (uri != null) FormatThumbnail(uri, extOf(r.name), 56.dp)
                else FormatThumbnail(r.source.uri, extOf(r.source.name), 56.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    if (r.ok) {
                        Text(
                            "${extOf(r.source.name).uppercase()} ${formatSize(r.source.bytes)} → ${extOf(r.name).uppercase()} ${formatSize(r.bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (r.width > 0) {
                            Text("${r.width}×${r.height}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text(r.error ?: "Couldn't convert", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (r.ok) {
                    IconButton(onClick = { onOpen(r) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = if (format.viewableInApp) "Open" else "Open with")
                    }
                    IconButton(onClick = { onShare(listOf(r)) }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                }
            }
        }
    }
}
