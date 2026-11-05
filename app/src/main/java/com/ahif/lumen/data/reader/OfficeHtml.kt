package com.ahif.lumen.data.reader

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.ahif.lumen.data.model.DocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipInputStream

/** Body HTML plus the extracted base directory (so file:// images resolve). */
data class HtmlDoc(val body: String, val baseUrl: String)

/**
 * Renders modern .docx / .pptx to HTML for display in a WebView, preserving
 * bold/italic/underline, headings, tables and embedded images.
 */
object OfficeHtml {

    suspend fun render(context: Context, uri: Uri, name: String, type: DocumentType): HtmlDoc? =
        withContext(Dispatchers.IO) {
            if (DocumentType.isLegacyBinary(name)) return@withContext null
            runCatching {
                val dir = extractToDir(context, uri) ?: return@withContext null
                val base = "file://" + dir.absolutePath + "/"
                when (type) {
                    DocumentType.WORD -> HtmlDoc(docxBody(dir), base)
                    DocumentType.POWERPOINT -> HtmlDoc(pptxBody(dir), base)
                    else -> null
                }
            }.getOrNull()
        }

    // ---------- extraction ----------
    private fun extractToDir(context: Context, uri: Uri): File? {
        val dir = File(context.cacheDir, "office_html_" + uri.hashCode())
        if (dir.exists() && !dir.listFiles().isNullOrEmpty()) return dir
        dir.mkdirs()
        val root = dir.canonicalPath
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(dir, entry.name)
                    if (out.canonicalPath.startsWith(root)) {
                        if (entry.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: return null
        return dir
    }

    private fun readRels(relsFile: File, mediaBaseUrl: String): Map<String, String> {
        if (!relsFile.exists()) return emptyMap()
        val map = HashMap<String, String>()
        relsFile.inputStream().use { stream ->
            val p = newParser(stream)
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && local(p.name) == "Relationship") {
                    val id = attr(p, "Id")
                    val target = attr(p, "Target")
                    if (id != null && target != null) {
                        map[id] = mediaBaseUrl + target.substringAfterLast("../").removePrefix("/")
                    }
                }
                e = p.next()
            }
        }
        return map
    }

    // ---------- Word ----------
    private fun docxBody(dir: File): String {
        val doc = File(dir, "word/document.xml")
        if (!doc.exists()) return "<p>Empty document.</p>"
        // rId -> absolute file:// image url (media is under word/)
        val rels = readRels(File(dir, "word/_rels/document.xml.rels"), "file://" + File(dir, "word").absolutePath + "/")

        val out = StringBuilder()
        doc.inputStream().use { stream ->
            val p = newParser(stream)
            val para = StringBuilder()
            val run = StringBuilder()
            var style: String? = null
            var bold = false; var italic = false; var underline = false
            var inText = false
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                when (e) {
                    XmlPullParser.START_TAG -> when (local(p.name)) {
                        "p" -> { para.setLength(0); style = null }
                        "pStyle" -> style = attr(p, "val")
                        "r" -> { run.setLength(0); bold = false; italic = false; underline = false }
                        "b" -> if (attr(p, "val") !in listOf("0", "false")) bold = true
                        "i" -> if (attr(p, "val") !in listOf("0", "false")) italic = true
                        "u" -> if ((attr(p, "val") ?: "single") != "none") underline = true
                        "t" -> inText = true
                        "tab" -> run.append("&nbsp;&nbsp;&nbsp;")
                        "br", "cr" -> run.append("<br/>")
                        "blip" -> attr(p, "embed")?.let { rels[it] }?.let { out.append("<img src=\"$it\"/>") }
                        "tbl" -> out.append("<table>")
                        "tr" -> out.append("<tr>")
                        "tc" -> out.append("<td>")
                    }
                    XmlPullParser.TEXT -> if (inText) run.append(escape(p.text))
                    XmlPullParser.END_TAG -> when (local(p.name)) {
                        "t" -> inText = false
                        "r" -> {
                            var s = run.toString()
                            if (s.isNotEmpty()) {
                                if (bold) s = "<b>$s</b>"
                                if (italic) s = "<i>$s</i>"
                                if (underline) s = "<u>$s</u>"
                                para.append(s)
                            }
                        }
                        "p" -> {
                            val tag = headingTag(style)
                            val content = para.toString()
                            if (content.isNotBlank()) out.append("<$tag>$content</$tag>")
                            else out.append("<p>&nbsp;</p>")
                        }
                        "tc" -> out.append("</td>")
                        "tr" -> out.append("</tr>")
                        "tbl" -> out.append("</table>")
                    }
                }
                e = p.next()
            }
        }
        return out.toString()
    }

    private fun headingTag(style: String?): String {
        if (style == null) return "p"
        val s = style.lowercase()
        return when {
            s.contains("title") -> "h1"
            s.startsWith("heading1") || s == "heading 1" -> "h1"
            s.startsWith("heading2") -> "h2"
            s.startsWith("heading3") -> "h3"
            s.startsWith("heading4") || s.startsWith("heading5") || s.startsWith("heading6") -> "h4"
            else -> "p"
        }
    }

    // ---------- PowerPoint ----------
    private fun pptxBody(dir: File): String {
        val slidesDir = File(dir, "ppt/slides")
        val slides = slidesDir.listFiles { f -> f.name.matches(Regex("slide\\d+\\.xml")) }
            ?.sortedBy { it.name.substringAfter("slide").substringBefore(".").toIntOrNull() ?: 0 }
            ?: return "<p>No slides.</p>"

        val out = StringBuilder()
        slides.forEachIndexed { index, slide ->
            val rels = readRels(
                File(dir, "ppt/slides/_rels/${slide.name}.rels"),
                "file://" + File(dir, "ppt").absolutePath + "/"
            )
            out.append("<section class=\"slide\"><div class=\"slidenum\">Slide ${index + 1}</div>")
            slide.inputStream().use { stream ->
                val p = newParser(stream)
                val para = StringBuilder()
                val run = StringBuilder()
                var bold = false; var italic = false
                var inText = false
                var e = p.eventType
                while (e != XmlPullParser.END_DOCUMENT) {
                    when (e) {
                        XmlPullParser.START_TAG -> when (local(p.name)) {
                            "p" -> para.setLength(0)
                            "r" -> { run.setLength(0); bold = false; italic = false }
                            "rPr" -> {
                                if (attr(p, "b") == "1") bold = true
                                if (attr(p, "i") == "1") italic = true
                            }
                            "t" -> inText = true
                            "blip" -> attr(p, "embed")?.let { rels[it] }?.let { out.append("<img src=\"$it\"/>") }
                        }
                        XmlPullParser.TEXT -> if (inText) run.append(escape(p.text))
                        XmlPullParser.END_TAG -> when (local(p.name)) {
                            "t" -> inText = false
                            "r" -> {
                                var s = run.toString()
                                if (s.isNotEmpty()) {
                                    if (bold) s = "<b>$s</b>"
                                    if (italic) s = "<i>$s</i>"
                                    para.append(s)
                                }
                            }
                            "p" -> {
                                val c = para.toString()
                                if (c.isNotBlank()) out.append("<p>$c</p>")
                            }
                        }
                    }
                    e = p.next()
                }
            }
            out.append("</section>")
        }
        return out.toString()
    }

    // ---------- helpers ----------
    private fun newParser(stream: java.io.InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(stream, null)
        return p
    }

    private fun local(name: String?): String = name?.substringAfterLast(':') ?: ""

    /** Attribute lookup by local name (ignores the namespace prefix). */
    private fun attr(p: XmlPullParser, localName: String): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i).substringAfterLast(':') == localName) return p.getAttributeValue(i)
        }
        return null
    }

    private fun escape(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
