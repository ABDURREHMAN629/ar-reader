package com.ahif.lumen.ui.tools

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ahif.lumen.data.tools.Backdrop
import com.ahif.lumen.data.tools.BackdropComposer
import com.ahif.lumen.data.tools.BackgroundRemover
import com.ahif.lumen.data.tools.ImageCompressor
import com.ahif.lumen.data.tools.MediaSaver
import com.ahif.lumen.data.tools.OutFormat
import com.ahif.lumen.data.tools.Resize
import com.ahif.lumen.data.tools.SavedImage
import com.ahif.lumen.ui.util.DocumentActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BgType(val label: String) {
    TRANSPARENT("Transparent"), COLOR("Colour"), GRADIENT("Gradient"), BLUR("Blur"), PHOTO("Photo")
}

private val swatches: List<Int> = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF9E9E9E, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF00897B, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60
).map { it.toInt() }

private val gradients: List<List<Int>> = listOf(
    listOf(0xFF6D77F5, 0xFF7C3AED), listOf(0xFFFF9A8B, 0xFFFF6A88), listOf(0xFF43E97B, 0xFF38F9D7),
    listOf(0xFF4FACFE, 0xFF00F2FE), listOf(0xFFFA709A, 0xFFFEE140), listOf(0xFF30CFD0, 0xFF330867),
    listOf(0xFFF6D365, 0xFFFDA085), listOf(0xFF232526, 0xFF414345)
).map { g -> g.map { it.toInt() } }

private sealed interface Phase {
    data object Idle : Phase
    data class Working(val message: String, val progress: Float?) : Phase
    data object Ready : Phase
    data class Failed(val message: String) : Phase
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundScreen(onClose: () -> Unit, onOpenImage: (SavedImage) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseName by remember { mutableStateOf("image") }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var foreground by remember { mutableStateOf<Bitmap?>(null) }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }

    var type by remember { mutableStateOf(BgType.TRANSPARENT) }
    var color by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var hue by remember { mutableFloatStateOf(210f) }
    var gradientIdx by remember { mutableIntStateOf(0) }
    var blurStrength by remember { mutableIntStateOf(5) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var saveAsPng by remember { mutableStateOf(false) }

    var composed by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<SavedImage?>(null) }
    var savedRefresh by remember { mutableIntStateOf(0) }

    fun process(uri: Uri) {
        lastUri = uri
        scope.launch {
            foreground = null
            composed = null
            phase = Phase.Working("Loading photo…", null)
            baseName = ImageCompressor.inspect(context, uri)?.name?.substringBeforeLast('.')?.ifBlank { "image" } ?: "image"
            val bmp = runCatching { ImageCompressor.decode(context, uri, Resize.MaxSide(2048)) }.getOrNull()
            if (bmp == null) { phase = Phase.Failed("Couldn't open this photo."); return@launch }
            original = bmp
            phase = Phase.Working("Preparing background remover…", null)
            val ready = BackgroundRemover.ensureModel(context) { p ->
                phase = Phase.Working("Downloading the one-time AI model…", p)
            }
            if (!ready) {
                phase = Phase.Failed(
                    "The background-removal model isn't ready yet. Google Play services downloads it once — " +
                        "make sure the phone is online, then tap Retry."
                )
                return@launch
            }
            phase = Phase.Working("Removing background…", null)
            val fg = BackgroundRemover.cutOut(bmp)
            if (fg == null) {
                phase = Phase.Failed("Couldn't find a clear subject. Try a photo with a person, pet or object in focus.")
                return@launch
            }
            foreground = fg
            phase = Phase.Ready
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { process(it) } }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let {
            scope.launch {
                photo = runCatching { ImageCompressor.decode(context, it, Resize.MaxSide(2048)) }.getOrNull()
                if (photo != null) type = BgType.PHOTO
            }
        }
    }
    fun pickMain() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    fun pickPhoto() = photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    val backdrop: Backdrop = when (type) {
        BgType.TRANSPARENT -> Backdrop.Transparent
        BgType.COLOR -> Backdrop.Solid(color)
        BgType.GRADIENT -> Backdrop.Gradient(gradients[gradientIdx])
        BgType.BLUR -> Backdrop.Blur(blurStrength)
        BgType.PHOTO -> photo?.let { Backdrop.Photo(it) } ?: Backdrop.Transparent
    }
    LaunchedEffect(foreground, backdrop) {
        val fg = foreground ?: return@LaunchedEffect
        val orig = original ?: return@LaunchedEffect
        delay(60)
        composed = BackdropComposer.compose(fg, orig, backdrop)
    }

    val transparentOut = backdrop == Backdrop.Transparent
    fun save() {
        val img = composed ?: return
        val png = transparentOut || saveAsPng
        saving = true
        scope.launch {
            val bytes = withContext(Dispatchers.Default) {
                ImageCompressor.encode(img, if (png) OutFormat.PNG else OutFormat.JPEG, 92)
            }
            val fileName = "${baseName}_bg.${if (png) "png" else "jpg"}"
            val uri = MediaSaver.save(context, bytes, fileName, if (png) "image/png" else "image/jpeg", "Backgrounds")
            saving = false
            if (uri != null) {
                saved = SavedImage(uri, fileName, bytes.size.toLong(), System.currentTimeMillis() / 1000)
                savedRefresh++
            }
        }
    }

    BackHandler { if (!saving) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change background") },
                navigationIcon = {
                    IconButton(onClick = { if (!saving) onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (phase == Phase.Ready) {
                        IconButton(onClick = { save() }, enabled = composed != null && !saving) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val p = phase) {
                Phase.Idle -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth().clickable { pickMain() }) {
                            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Wallpaper, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("Select a photo", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "The subject is cut out automatically on your phone. Then make the background transparent, a colour, a gradient, blurred, or another photo.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        SavedImagesSection("Saved backgrounds", "Backgrounds", savedRefresh, onOpenImage)
                    }
                }

                is Phase.Working -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (p.progress == null) CircularProgressIndicator()
                    else LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Text(p.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    if (p.progress != null) Text("${(p.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                }

                is Phase.Failed -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(p.message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        lastUri?.let { u -> FilledTonalButton(onClick = { process(u) }) { Text("Retry") } }
                        OutlinedButton(onClick = { pickMain() }) { Text("Choose another photo") }
                    }
                }

                Phase.Ready -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
                    item {
                        val shown = composed ?: foreground
                        if (shown != null) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val ratio = shown.width.toFloat() / shown.height
                                val maxH = 380.dp
                                var bw = maxWidth
                                var bh = bw / ratio
                                if (bh > maxH) { bh = maxH; bw = bh * ratio }
                                Box(
                                    Modifier
                                        .size(bw, bh)
                                        .align(Alignment.Center)
                                        .clip(RoundedCornerShape(12.dp))
                                ) {
                                    if (transparentOut) Checkerboard(Modifier.fillMaxSize())
                                    Image(shown.asImageBitmap(), contentDescription = "Result", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }

                    item {
                        SectionTitle("Background")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(BgType.entries) { t ->
                                FilterChip(selected = type == t, onClick = {
                                    if (t == BgType.PHOTO && photo == null) pickPhoto() else type = t
                                }, label = { Text(t.label) })
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        when (type) {
                            BgType.TRANSPARENT -> Text(
                                "Saved as a PNG with a see-through background — great for stickers and logos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            BgType.COLOR -> {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(swatches) { c ->
                                        Box(
                                            Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color(c))
                                                .border(
                                                    if (color == c) 3.dp else 1.dp,
                                                    if (color == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                    CircleShape
                                                )
                                                .clickable { color = c }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text("Custom colour", style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Slider(
                                        value = hue,
                                        onValueChange = { hue = it; color = Color.hsv(it, 0.75f, 0.95f).toArgb() },
                                        valueRange = 0f..360f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Box(
                                        Modifier.padding(start = 12.dp).size(28.dp).clip(CircleShape)
                                            .background(Color.hsv(hue, 0.75f, 0.95f))
                                    )
                                }
                            }
                            BgType.GRADIENT -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                itemsIndexed(gradients) { i, g ->
                                    Box(
                                        Modifier
                                            .size(width = 56.dp, height = 40.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Brush.linearGradient(g.map { Color(it) }))
                                            .border(
                                                if (gradientIdx == i) 3.dp else 0.dp,
                                                if (gradientIdx == i) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable { gradientIdx = i }
                                    )
                                }
                            }
                            BgType.BLUR -> {
                                Text("Blur strength", style = MaterialTheme.typography.bodyMedium)
                                Slider(
                                    value = blurStrength.toFloat(),
                                    onValueChange = { blurStrength = it.toInt() },
                                    valueRange = 1f..10f,
                                    steps = 8
                                )
                            }
                            BgType.PHOTO -> OutlinedButton(onClick = { pickPhoto() }) {
                                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(if (photo == null) "Choose background photo" else "Change background photo")
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    item {
                        if (!transparentOut) {
                            SectionTitle("Save as")
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(!saveAsPng, { saveAsPng = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("JPG (smaller)") }
                                SegmentedButton(saveAsPng, { saveAsPng = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("PNG (lossless)") }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                        Button(onClick = { save() }, enabled = composed != null && !saving, modifier = Modifier.fillMaxWidth()) {
                            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Save image")
                        }
                        TextButton(onClick = { pickMain() }, modifier = Modifier.fillMaxWidth()) { Text("Choose another photo") }
                        Spacer(Modifier.height(8.dp))
                        SavedImagesSection("Saved backgrounds", "Backgrounds", savedRefresh, onOpenImage)
                    }
                }
            }
        }
    }

    saved?.let { s ->
        AlertDialog(
            onDismissRequest = { saved = null },
            title = { Text("Saved") },
            text = { Text("${s.name} saved in Pictures/AR Reader/Backgrounds.") },
            confirmButton = {
                Row {
                    TextButton(onClick = { saved = null; onOpenImage(s) }) { Text("Open") }
                    TextButton(onClick = { DocumentActions.share(context, listOf(s.asDocument())) }) { Text("Share") }
                }
            },
            dismissButton = { TextButton(onClick = { saved = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun Checkerboard(modifier: Modifier) {
    Canvas(modifier) {
        val cell = 12.dp.toPx()
        drawRect(Color.White)
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (r in 0 until rows) for (c in 0 until cols) {
            if ((r + c) % 2 == 0) drawRect(Color(0xFFDDDDDD), topLeft = Offset(c * cell, r * cell), size = Size(cell, cell))
        }
    }
}
