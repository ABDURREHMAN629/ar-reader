package com.ahif.lumen.ui.tools

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ahif.lumen.data.model.Document
import com.ahif.lumen.data.model.DocumentType
import com.ahif.lumen.data.reader.BitmapUtils
import com.ahif.lumen.data.tools.CompressOptions
import com.ahif.lumen.data.tools.CompressedImage
import com.ahif.lumen.data.tools.ImageCompressor
import com.ahif.lumen.data.tools.ImageSource
import com.ahif.lumen.data.tools.OutFormat
import com.ahif.lumen.data.tools.Resize
import com.ahif.lumen.data.tools.SavedImage
import com.ahif.lumen.ui.util.DocumentActions
import com.ahif.lumen.ui.util.formatSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private enum class Preset(val label: String, val hint: String) {
    BEST("Best quality", "Visually lossless — keeps the original size"),
    BALANCED("Balanced", "Great quality, max 2048 px — big savings"),
    SMALL("Smallest", "Good for sharing, max 1280 px"),
    CUSTOM("Custom", "Your own settings")
}

private enum class CompressBy { QUALITY, TARGET }

private val targetChoices = listOf(50, 100, 200, 500, 1024)

private val resizeChoices: List<Pair<String, Resize>> = listOf(
    "Original" to Resize.Original,
    "75%" to Resize.Percent(75),
    "50%" to Resize.Percent(50),
    "25%" to Resize.Percent(25),
    "Max 2048" to Resize.MaxSide(2048),
    "Max 1920" to Resize.MaxSide(1920),
    "Max 1280" to Resize.MaxSide(1280),
    "Max 1080" to Resize.MaxSide(1080),
    "Max 720" to Resize.MaxSide(720)
)

private class ResultRow(val source: ImageSource, val image: CompressedImage?, val saved: Uri?)

private fun sizeLabel(kb: Int) = if (kb >= 1024) "${kb / 1024} MB" else "$kb KB"

private fun qualityHint(q: Int) = when {
    q >= 80 -> "Visually lossless"
    q >= 60 -> "High quality"
    q >= 40 -> "Medium quality"
    else -> "Low quality — visible artefacts"
}

private fun savingsText(img: CompressedImage): String {
    val p = img.savedPercent
    return if (p >= 0) "−$p%" else "+${abs(p)}% (already optimised)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressorScreen(onClose: () -> Unit, onOpenImage: (SavedImage) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sources = remember { mutableStateListOf<ImageSource>() }
    var previewIndex by remember { mutableIntStateOf(0) }
    var preset by remember { mutableStateOf(Preset.BEST) }
    var compressBy by remember { mutableStateOf(CompressBy.QUALITY) }
    var quality by remember { mutableIntStateOf(85) }
    var targetKb by remember { mutableIntStateOf(200) }
    var customTarget by remember { mutableStateOf("") }
    var resize by remember { mutableStateOf<Resize>(Resize.Original) }
    var format by remember { mutableStateOf(OutFormat.JPEG) }
    var keepDetails by remember { mutableStateOf(true) }
    var removeLocation by remember { mutableStateOf(true) }
    var showCompressed by remember { mutableStateOf(true) }
    var loadingPick by remember { mutableStateOf(false) }

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<ResultRow>?>(null) }
    var savedRefresh by remember { mutableIntStateOf(0) }

    fun applyPreset(p: Preset) {
        preset = p
        when (p) {
            Preset.BEST -> { compressBy = CompressBy.QUALITY; quality = 85; resize = Resize.Original }
            Preset.BALANCED -> { compressBy = CompressBy.QUALITY; quality = 75; resize = Resize.MaxSide(2048) }
            Preset.SMALL -> { compressBy = CompressBy.QUALITY; quality = 60; resize = Resize.MaxSide(1280) }
            Preset.CUSTOM -> Unit
        }
    }

    val options = CompressOptions(
        format = format,
        quality = quality,
        targetKb = if (compressBy == CompressBy.TARGET) targetKb else null,
        resize = resize,
        keepDetails = keepDetails,
        removeLocation = removeLocation
    )

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            loadingPick = true
            uris.forEach { u ->
                if (sources.none { it.uri == u }) ImageCompressor.inspect(context, u)?.let { sources.add(it) }
            }
            loadingPick = false
        }
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    val previewSource = sources.getOrNull(previewIndex.coerceIn(0, max(0, sources.size - 1)))
    var estimate by remember { mutableStateOf<CompressedImage?>(null) }
    var estimating by remember { mutableStateOf(false) }
    LaunchedEffect(previewSource, options) {
        estimate = null
        val src = previewSource ?: return@LaunchedEffect
        delay(350)
        estimating = true
        try {
            estimate = runCatching { ImageCompressor.compress(context, src, options) }.getOrNull()
        } finally {
            estimating = false
        }
    }
    val compressedBitmap = remember(estimate) { estimate?.let { BitmapUtils.decodeSampled(it.data, 1600) } }

    fun runAll() {
        val list = sources.toList()
        val opts = options
        running = true
        progress = 0
        scope.launch {
            val rows = ArrayList<ResultRow>()
            list.forEach { src ->
                val img = runCatching { ImageCompressor.compress(context, src, opts) }.getOrNull()
                val uri = img?.let { ImageCompressor.save(context, it, opts) }
                rows += ResultRow(src, img, uri)
                progress++
            }
            running = false
            results = rows
            savedRefresh++
        }
    }

    fun goBack() {
        when {
            running -> Unit
            results != null -> { results = null; sources.clear() }
            else -> onClose()
        }
    }
    BackHandler { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compress images") },
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
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Icon(Icons.Filled.Compress, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Compress ${sources.size} image${if (sources.size == 1) "" else "s"}")
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
                    Text("Compressing $progress of ${sources.size}…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (sources.isEmpty()) 0f else progress.toFloat() / sources.size },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                results != null -> ResultsView(
                    rows = results!!,
                    onShareAll = {
                        val docs = results!!.mapNotNull { r ->
                            r.saved?.let { u -> Document(u.toString(), u, r.source.name, DocumentType.IMAGE, r.image?.data?.size?.toLong() ?: 0, 0, null) }
                        }
                        if (docs.isNotEmpty()) DocumentActions.share(context, docs)
                    },
                    onMore = { results = null; sources.clear() },
                    onDone = onClose
                )

                sources.isEmpty() -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth().clickable { pick() }) {
                            Column(
                                Modifier.fillMaxWidth().padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Filled.AddPhotoAlternate, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text("Select images", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Shrink photos without visible quality loss. Pick one or many — choose quality, a target size, resize or format.",
                                    style = MaterialTheme.typography.bodyMedium,
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
                        SavedImagesSection("Compressed images", "Compressed", savedRefresh, onOpenImage)
                    }
                }

                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
                    // Preview with before/after toggle and live size estimate
                    item {
                        val src = previewSource!!
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(260.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val cb = compressedBitmap
                                    if (showCompressed && cb != null) {
                                        Image(cb.asImageBitmap(), contentDescription = "Compressed", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                    } else {
                                        AsyncImage(model = src.uri, contentDescription = "Original", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                    }
                                    if (estimating) {
                                        CircularProgressIndicator(
                                            Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    SegmentedButton(
                                        selected = !showCompressed, onClick = { showCompressed = false },
                                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                                    ) { Text("Original") }
                                    SegmentedButton(
                                        selected = showCompressed, onClick = { showCompressed = true },
                                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                                    ) { Text("Compressed") }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Original: ${formatSize(src.bytes)} · ${src.width}×${src.height}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val est = estimate
                                Text(
                                    when {
                                        est != null -> "Compressed: ${formatSize(est.data.size.toLong())} · ${est.width}×${est.height} · ${savingsText(est)}"
                                        estimating -> "Estimating…"
                                        else -> " "
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (est != null && compressBy == CompressBy.TARGET && format != OutFormat.PNG) {
                                    Text("Quality used: ${est.quality}%", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // Selected images
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${sources.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { pick() }) { Text("Add more") }
                            TextButton(onClick = { sources.clear() }) { Text("Clear") }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(sources, key = { _, s -> s.uri.toString() }) { i, s ->
                                Box(Modifier.size(64.dp)) {
                                    AsyncImage(
                                        model = s.uri,
                                        contentDescription = s.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(
                                                if (i == previewIndex) 2.dp else 0.dp,
                                                if (i == previewIndex) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable { previewIndex = i }
                                    )
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(2.dp)
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.55f))
                                            .clickable {
                                                sources.removeAt(i)
                                                if (previewIndex >= sources.size) previewIndex = max(0, sources.size - 1)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // Presets
                    item {
                        SectionTitle("Preset")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(Preset.entries) { p ->
                                FilterChip(selected = preset == p, onClick = { applyPreset(p) }, label = { Text(p.label) })
                            }
                        }
                        Text(preset.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                    }

                    // Quality vs target size
                    item {
                        SectionTitle("Compress by")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = compressBy == CompressBy.QUALITY,
                                onClick = { compressBy = CompressBy.QUALITY; preset = Preset.CUSTOM },
                                shape = SegmentedButtonDefaults.itemShape(0, 2)
                            ) { Text("Quality") }
                            SegmentedButton(
                                selected = compressBy == CompressBy.TARGET,
                                onClick = { compressBy = CompressBy.TARGET; preset = Preset.CUSTOM },
                                shape = SegmentedButtonDefaults.itemShape(1, 2)
                            ) { Text("Target size") }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (compressBy == CompressBy.QUALITY) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Slider(
                                    value = quality.toFloat(),
                                    onValueChange = { quality = it.toInt(); preset = Preset.CUSTOM },
                                    valueRange = 10f..100f,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("$quality%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
                            }
                            Text(
                                if (format == OutFormat.PNG) "PNG is lossless — quality has no effect. Use resize, or JPG/WebP."
                                else qualityHint(quality),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(targetChoices) { kb ->
                                    FilterChip(
                                        selected = targetKb == kb && customTarget.isBlank(),
                                        onClick = { targetKb = kb; customTarget = ""; preset = Preset.CUSTOM },
                                        label = { Text("≤ ${sizeLabel(kb)}") }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = customTarget,
                                onValueChange = { s ->
                                    customTarget = s.filter { it.isDigit() }.take(6)
                                    customTarget.toIntOrNull()?.takeIf { it > 0 }?.let { targetKb = it; preset = Preset.CUSTOM }
                                },
                                label = { Text("Custom size (KB)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            )
                            Text(
                                "Finds the best quality that fits, and shrinks dimensions only if it has to.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // Resize
                    item {
                        SectionTitle("Resize")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(resizeChoices) { (label, r) ->
                                FilterChip(selected = resize == r, onClick = { resize = r; preset = Preset.CUSTOM }, label = { Text(label) })
                            }
                        }
                        previewSource?.let { s ->
                            val (tw, th) = ImageCompressor.targetSize(s.width, s.height, resize)
                            Text(
                                "${s.width}×${s.height} → $tw×$th",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // Format
                    item {
                        SectionTitle("Format")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            OutFormat.entries.forEachIndexed { i, f ->
                                SegmentedButton(
                                    selected = format == f,
                                    onClick = { format = f; preset = Preset.CUSTOM },
                                    shape = SegmentedButtonDefaults.itemShape(i, OutFormat.entries.size)
                                ) { Text(f.label) }
                            }
                        }
                        Text(
                            when (format) {
                                OutFormat.JPEG -> "Best for photos — works everywhere."
                                OutFormat.WEBP -> "Smaller than JPG at the same quality."
                                OutFormat.PNG -> "Lossless, keeps transparency — larger files."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    // Metadata
                    item {
                        SectionTitle("Photo details")
                        ToolSwitchRow(
                            "Keep photo details",
                            if (format == OutFormat.JPEG) "Date & camera info are copied" else "Only kept for JPG — other formats remove all details",
                            keepDetails
                        ) { keepDetails = it; preset = Preset.CUSTOM }
                        ToolSwitchRow("Remove location", "Strip GPS position for privacy", removeLocation, enabled = keepDetails) {
                            removeLocation = it
                        }
                        Spacer(Modifier.height(8.dp))
                        SavedImagesSection("Compressed images", "Compressed", savedRefresh, onOpenImage)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsView(rows: List<ResultRow>, onShareAll: () -> Unit, onMore: () -> Unit, onDone: () -> Unit) {
    val ok = rows.filter { it.image != null && it.saved != null }
    val before = ok.sumOf { it.source.bytes }
    val after = ok.sumOf { it.image!!.data.size.toLong() }
    val pct = if (before > 0) ((1.0 - after.toDouble() / before) * 100).toInt() else 0

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Done — ${ok.size} of ${rows.size} saved", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatSize(before)} → ${formatSize(after)}  (${if (pct >= 0) "−$pct%" else "+${abs(pct)}%"})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Saved in Pictures/AR Reader/Compressed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onShareAll, enabled = ok.isNotEmpty()) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Share all")
                        }
                        OutlinedButton(onClick = onMore) { Text("Compress more") }
                        TextButton(onClick = onDone) { Text("Done") }
                    }
                }
            }
        }
        items(rows) { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = r.saved ?: r.source.uri,
                    contentDescription = r.source.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(r.source.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    val img = r.image
                    if (img != null && r.saved != null) {
                        Text(
                            "${formatSize(r.source.bytes)} → ${formatSize(img.data.size.toLong())} · ${savingsText(img)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("${img.width}×${img.height}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Couldn't compress this image", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
internal fun ToolSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
