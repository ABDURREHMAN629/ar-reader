package com.ahif.lumen.data.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Creates PDF files from images or text using the platform PdfDocument API. */
object PdfBuilder {

    /** Preferred output directory: public Documents/AR Reader, falling back to app storage. */
    fun outputDir(context: Context): File {
        val public = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AR Reader")
        if (runCatching { public.mkdirs(); public.exists() && public.canWrite() }.getOrDefault(false)) return public
        val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "AR Reader")
        fallback.mkdirs()
        return fallback
    }

    /** Public: a clean, collision-safe output file in the AR Reader folder. */
    fun uniqueOutput(context: Context, baseName: String, ext: String = "pdf"): File =
        uniqueFile(outputDir(context), sanitize(baseName), ext)

    /** A clean output file: "name.pdf", or "name (2).pdf" only if that already exists. */
    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var candidate = File(dir, "$base.$ext")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n).$ext")
            n++
        }
        return candidate
    }

    suspend fun imagesToPdf(
        context: Context,
        uris: List<Uri>,
        baseName: String = "images",
        filter: ScanFilter = ScanFilter.ORIGINAL
    ): File? = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext null
        runCatching {
            val document = PdfDocument()
            var pageNo = 1
            var pagesWritten = 0
            uris.forEach { uri ->
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@forEach
                val decoded = BitmapUtils.decodeSampled(bytes, 2200) ?: return@forEach
                val bmp = ImageFilters.apply(decoded, filter)
                val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, pageNo++).create()
                val page = document.startPage(info)
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                document.finishPage(page)
                pagesWritten++
                if (bmp !== decoded) decoded.recycle()
                bmp.recycle()
            }
            if (pagesWritten == 0) {
                document.close()
                return@runCatching null
            }
            val out = uniqueFile(outputDir(context), sanitize(baseName), "pdf")
            out.outputStream().use { document.writeTo(it) }
            document.close()
            out
        }.getOrNull()
    }

    suspend fun textToPdf(context: Context, text: String, title: String = "Document"): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val pageWidth = 595   // A4 @72dpi
                val pageHeight = 842
                val margin = 48
                val contentWidth = pageWidth - margin * 2
                val contentHeight = pageHeight - margin * 2

                val paint = TextPaint().apply {
                    color = Color.BLACK
                    textSize = 14f
                    isAntiAlias = true
                }
                val layout = StaticLayout.Builder
                    .obtain(text.ifBlank { " " }, 0, text.length.coerceAtLeast(1), paint, contentWidth)
                    .build()

                val document = PdfDocument()
                val totalHeight = layout.height
                val pages = (totalHeight + contentHeight - 1) / contentHeight
                for (i in 0 until pages.coerceAtLeast(1)) {
                    val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create()
                    val page = document.startPage(info)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    canvas.save()
                    canvas.clipRect(Rect(margin, margin, pageWidth - margin, pageHeight - margin))
                    canvas.translate(margin.toFloat(), (margin - i * contentHeight).toFloat())
                    layout.draw(canvas)
                    canvas.restore()
                    document.finishPage(page)
                }
                val out = uniqueFile(outputDir(context), sanitize(title), "pdf")
                out.outputStream().use { document.writeTo(it) }
                document.close()
                out
            }.getOrNull()
        }

    private fun sanitize(name: String): String =
        name.trim().ifBlank { "Document" }.replace(Regex("[^a-zA-Z0-9-_ ]"), "").ifBlank { "Document" }

    @Suppress("unused")
    private fun blank(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}
