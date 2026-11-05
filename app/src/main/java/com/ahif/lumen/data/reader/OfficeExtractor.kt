package com.ahif.lumen.data.reader

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.compose.ui.text.AnnotatedString
import com.ahif.lumen.data.model.DocumentType
import com.ahif.lumen.ui.reader.BlockKind
import com.ahif.lumen.ui.reader.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Extracts readable text from modern Office Open XML files (.docx / .pptx / .xlsx)
 * by reading the zip package directly — no external libraries. Legacy binary
 * .doc/.xls/.ppt are not supported and return a friendly notice.
 */
object OfficeExtractor {

    suspend fun extract(context: Context, uri: Uri, name: String, type: DocumentType): List<TextBlock> =
        withContext(Dispatchers.IO) {
            if (DocumentType.isLegacyBinary(name)) {
                return@withContext notice(
                    "Legacy “.${name.substringAfterLast('.')}” files aren’t supported yet. " +
                        "Please re-save it as .docx, .xlsx or .pptx and open again."
                )
            }
            runCatching {
                val cache = File(context.cacheDir, "office_" + uri.hashCode() + "_" + name.substringAfterLast('.'))
                if (!cache.exists() || cache.length() == 0L) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        cache.outputStream().use { input.copyTo(it) }
                    } ?: return@withContext notice("Could not open the file.")
                }
                ZipFile(cache).use { zip ->
                    when (type) {
                        DocumentType.WORD -> parseDocx(zip)
                        DocumentType.POWERPOINT -> parsePptx(zip)
                        DocumentType.EXCEL -> parseXlsx(zip)
                        else -> notice("Unsupported document.")
                    }
                }
            }.getOrElse { notice("This file couldn't be read.\n\n${it.message ?: ""}") }
        }

    private fun notice(text: String) = listOf(TextBlock(BlockKind.PARAGRAPH, AnnotatedString(text)))

    // ---- Word ----
    private fun parseDocx(zip: ZipFile): List<TextBlock> {
        val entry = zip.getEntry("word/document.xml") ?: return notice("Empty Word document.")
        val blocks = ArrayList<TextBlock>()
        zip.getInputStream(entry).use { stream ->
            val parser = newParser(stream)
            val para = StringBuilder()
            var inText = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (local(parser.name)) {
                        "t" -> inText = true
                        "tab" -> para.append('\t')
                        "br", "cr" -> para.append('\n')
                    }
                    XmlPullParser.TEXT -> if (inText) para.append(parser.text)
                    XmlPullParser.END_TAG -> when (local(parser.name)) {
                        "t" -> inText = false
                        "p" -> {
                            val text = para.toString().trim()
                            if (text.isNotEmpty()) blocks.add(TextBlock(BlockKind.PARAGRAPH, AnnotatedString(text)))
                            para.clear()
                        }
                    }
                }
                event = parser.next()
            }
        }
        return blocks.ifEmpty { notice("This Word document has no readable text.") }
    }

    // ---- PowerPoint ----
    private fun parsePptx(zip: ZipFile): List<TextBlock> {
        val slideEntries = zip.entries().toList()
            .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.name.substringAfterLast("slide").substringBefore(".").toIntOrNull() ?: 0 }
        if (slideEntries.isEmpty()) return notice("This presentation has no slides.")

        val blocks = ArrayList<TextBlock>()
        slideEntries.forEachIndexed { index, entry ->
            blocks.add(TextBlock(BlockKind.H2, AnnotatedString("Slide ${index + 1}")))
            zip.getInputStream(entry).use { stream ->
                val parser = newParser(stream)
                val line = StringBuilder()
                var inText = false
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> if (local(parser.name) == "t") inText = true
                        XmlPullParser.TEXT -> if (inText) line.append(parser.text)
                        XmlPullParser.END_TAG -> when (local(parser.name)) {
                            "t" -> inText = false
                            "p" -> {
                                val text = line.toString().trim()
                                if (text.isNotEmpty()) blocks.add(TextBlock(BlockKind.PARAGRAPH, AnnotatedString(text)))
                                line.clear()
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        }
        return blocks
    }

    // ---- Excel ----
    private fun parseXlsx(zip: ZipFile): List<TextBlock> {
        val shared = zip.getEntry("xl/sharedStrings.xml")?.let { readSharedStrings(zip, it) } ?: emptyList()
        val sheetEntries = zip.entries().toList()
            .filter { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.name.substringAfterLast("sheet").substringBefore(".").toIntOrNull() ?: 0 }
        if (sheetEntries.isEmpty()) return notice("This spreadsheet has no sheets.")

        val blocks = ArrayList<TextBlock>()
        sheetEntries.forEachIndexed { index, entry ->
            blocks.add(TextBlock(BlockKind.H2, AnnotatedString("Sheet ${index + 1}")))
            val rows = readSheetRows(zip, entry, shared, maxRows = 400)
            if (rows.isEmpty()) {
                blocks.add(TextBlock(BlockKind.PARAGRAPH, AnnotatedString("(empty)")))
            } else {
                rows.forEach { row ->
                    blocks.add(TextBlock(BlockKind.CODE, AnnotatedString(row.joinToString("  |  "))))
                }
            }
        }
        return blocks
    }

    private fun readSharedStrings(zip: ZipFile, entry: java.util.zip.ZipEntry): List<String> {
        val strings = ArrayList<String>()
        zip.getInputStream(entry).use { stream ->
            val parser = newParser(stream)
            val sb = StringBuilder()
            var depth = 0
            var inText = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (local(parser.name)) {
                        "si" -> { sb.clear(); depth = 1 }
                        "t" -> if (depth > 0) inText = true
                    }
                    XmlPullParser.TEXT -> if (inText) sb.append(parser.text)
                    XmlPullParser.END_TAG -> when (local(parser.name)) {
                        "t" -> inText = false
                        "si" -> { strings.add(sb.toString()); depth = 0 }
                    }
                }
                event = parser.next()
            }
        }
        return strings
    }

    private fun readSheetRows(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        shared: List<String>,
        maxRows: Int
    ): List<List<String>> {
        val rows = ArrayList<List<String>>()
        zip.getInputStream(entry).use { stream ->
            val parser = newParser(stream)
            var currentRow: MutableList<String>? = null
            var cellType = ""
            var value = StringBuilder()
            var inValue = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && rows.size < maxRows) {
                when (event) {
                    XmlPullParser.START_TAG -> when (local(parser.name)) {
                        "row" -> currentRow = ArrayList()
                        "c" -> { cellType = parser.getAttributeValue(null, "t") ?: ""; value.clear() }
                        "v" -> inValue = true
                        "t" -> inValue = true
                    }
                    XmlPullParser.TEXT -> if (inValue) value.append(parser.text)
                    XmlPullParser.END_TAG -> when (local(parser.name)) {
                        "v", "t" -> inValue = false
                        "c" -> {
                            val raw = value.toString()
                            val text = if (cellType == "s") {
                                raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            } else raw
                            currentRow?.add(text)
                        }
                        "row" -> {
                            currentRow?.let { if (it.any { c -> c.isNotBlank() }) rows.add(it) }
                            currentRow = null
                        }
                    }
                }
                event = parser.next()
            }
        }
        return rows
    }

    private fun newParser(stream: InputStream): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)
        return parser
    }

    private fun local(name: String?): String = name?.substringAfterLast(':') ?: ""
}
