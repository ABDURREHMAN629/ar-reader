package com.ahif.lumen.data.reader

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.ahif.lumen.data.model.DocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

data class Sheet(val name: String, val rows: List<List<String>>)
data class Spreadsheet(val sheets: List<Sheet>)

/** Parses a modern .xlsx workbook into sheets of rows/cells (aligned by column). */
object SpreadsheetParser {

    private const val MAX_ROWS = 600
    private const val MAX_COLS = 60

    suspend fun read(context: Context, uri: Uri, name: String): Spreadsheet? = withContext(Dispatchers.IO) {
        if (DocumentType.isLegacyBinary(name)) return@withContext null
        runCatching {
            val cache = File(context.cacheDir, "xlsx_" + uri.hashCode() + ".xlsx")
            if (!cache.exists() || cache.length() == 0L) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cache.outputStream().use { input.copyTo(it) }
                } ?: return@withContext null
            }
            ZipFile(cache).use { zip ->
                val shared = zip.getEntry("xl/sharedStrings.xml")?.let { readSharedStrings(zip.getInputStream(it)) } ?: emptyList()
                val sheetNames = zip.getEntry("xl/workbook.xml")?.let { readSheetNames(zip.getInputStream(it)) } ?: emptyList()
                val sheetEntries = zip.entries().toList()
                    .filter { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                    .sortedBy { it.name.substringAfterLast("sheet").substringBefore(".").toIntOrNull() ?: 0 }

                val sheets = sheetEntries.mapIndexed { index, entry ->
                    val rows = readRows(zip.getInputStream(entry), shared)
                    Sheet(sheetNames.getOrNull(index) ?: "Sheet ${index + 1}", rows)
                }.filter { it.rows.isNotEmpty() }

                if (sheets.isEmpty()) null else Spreadsheet(sheets)
            }
        }.getOrNull()
    }

    private fun readSheetNames(stream: InputStream): List<String> {
        val names = ArrayList<String>()
        stream.use {
            val p = newParser(it)
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && local(p.name) == "sheet") {
                    p.getAttributeValue(null, "name")?.let { n -> names.add(n) }
                }
                e = p.next()
            }
        }
        return names
    }

    private fun readSharedStrings(stream: InputStream): List<String> {
        val strings = ArrayList<String>()
        stream.use {
            val p = newParser(it)
            val sb = StringBuilder()
            var inSi = false
            var inText = false
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                when (e) {
                    XmlPullParser.START_TAG -> when (local(p.name)) {
                        "si" -> { sb.clear(); inSi = true }
                        "t" -> if (inSi) inText = true
                    }
                    XmlPullParser.TEXT -> if (inText) sb.append(p.text)
                    XmlPullParser.END_TAG -> when (local(p.name)) {
                        "t" -> inText = false
                        "si" -> { strings.add(sb.toString()); inSi = false }
                    }
                }
                e = p.next()
            }
        }
        return strings
    }

    private fun readRows(stream: InputStream, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        stream.use {
            val p = newParser(it)
            var cells = HashMap<Int, String>()
            var maxCol = -1
            var col = 0
            var cellType = ""
            val value = StringBuilder()
            var inValue = false
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT && rows.size < MAX_ROWS) {
                when (e) {
                    XmlPullParser.START_TAG -> when (local(p.name)) {
                        "row" -> { cells = HashMap(); maxCol = -1 }
                        "c" -> {
                            cellType = p.getAttributeValue(null, "t") ?: ""
                            col = columnOf(p.getAttributeValue(null, "r"))
                            value.clear()
                        }
                        "v", "t" -> inValue = true
                    }
                    XmlPullParser.TEXT -> if (inValue) value.append(p.text)
                    XmlPullParser.END_TAG -> when (local(p.name)) {
                        "v", "t" -> inValue = false
                        "c" -> {
                            val raw = value.toString()
                            val text = if (cellType == "s") raw.toIntOrNull()?.let { i -> shared.getOrNull(i) } ?: "" else raw
                            if (col in 0 until MAX_COLS) {
                                cells[col] = text
                                if (col > maxCol) maxCol = col
                            }
                        }
                        "row" -> {
                            if (maxCol >= 0) {
                                val row = (0..maxCol).map { c -> cells[c] ?: "" }
                                if (row.any { s -> s.isNotBlank() }) rows.add(row)
                            }
                        }
                    }
                }
                e = p.next()
            }
        }
        return rows
    }

    /** "B7" -> 1 (0-based column index). */
    private fun columnOf(ref: String?): Int {
        if (ref.isNullOrEmpty()) return 0
        var col = 0
        for (ch in ref) {
            if (ch.isLetter()) col = col * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return (col - 1).coerceAtLeast(0)
    }

    private fun newParser(stream: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(stream, null)
        return p
    }

    private fun local(name: String?): String = name?.substringAfterLast(':') ?: ""
}
