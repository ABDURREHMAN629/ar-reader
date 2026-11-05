package com.ahif.lumen.ui.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.ahif.lumen.data.model.Document
import com.ahif.lumen.data.model.DocumentType
import java.io.File
import java.io.FileOutputStream

object DocumentActions {

    private fun authority(context: Context) = context.packageName + ".fileprovider"

    /** A content:// uri safe to hand to other apps. */
    private fun shareUri(context: Context, doc: Document): Uri? = when (doc.uri.scheme) {
        "content" -> doc.uri
        "file" -> runCatching {
            FileProvider.getUriForFile(context, authority(context), File(doc.uri.path!!))
        }.getOrNull()
        else -> null
    }

    private fun mimeFor(doc: Document): String = when (doc.type) {
        DocumentType.PDF -> "application/pdf"
        DocumentType.EPUB -> "application/epub+zip"
        DocumentType.IMAGE -> "image/*"
        DocumentType.TEXT -> "text/plain"
        DocumentType.MARKDOWN -> "text/markdown"
        DocumentType.WORD -> "application/msword"
        DocumentType.EXCEL -> "application/vnd.ms-excel"
        DocumentType.POWERPOINT -> "application/vnd.ms-powerpoint"
        else -> "*/*"
    }

    fun share(context: Context, docs: List<Document>) {
        val pairs = docs.mapNotNull { d -> shareUri(context, d)?.let { it to d } }
        if (pairs.isEmpty()) return
        val uris = pairs.map { it.first }

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(pairs[0].second)
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        // Grant read access to whichever app the user picks.
        val clip = ClipData.newRawUri(null, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val chooser = Intent.createChooser(intent, "Share").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }

    /** Lets the user pick another app to view [uri] (for formats this app can't display). */
    fun openWith(context: Context, uri: Uri, mime: String) {
        val target = if (uri.scheme == "file") {
            runCatching { FileProvider.getUriForFile(context, authority(context), File(uri.path!!)) }.getOrNull() ?: return
        } else uri
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Open with").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(chooser) }
    }

    fun printPdf(context: Context, doc: Document) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val adapter = PdfPrintAdapter(context, doc.uri, doc.title)
        runCatching {
            printManager.print(doc.title, adapter, PrintAttributes.Builder().build())
        }
    }
}

/** Streams a PDF file straight to the Android print framework. */
private class PdfPrintAdapter(
    private val context: Context,
    private val uri: Uri,
    private val jobName: String
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: android.os.Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder("$jobName.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback?.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?
    ) {
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                FileOutputStream(destination!!.fileDescriptor).use { output ->
                    input!!.copyTo(output)
                }
            }
            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback?.onWriteFailed(e.message)
        }
    }
}
