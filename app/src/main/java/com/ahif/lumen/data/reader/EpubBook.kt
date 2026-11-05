package com.ahif.lumen.data.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** A parsed EPUB: title plus chapter files in reading (spine) order. */
class EpubBook(
    val title: String,
    val chapters: List<File>
)

object EpubParser {

    suspend fun open(context: Context, uri: Uri): EpubBook? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "epub_" + uri.hashCode())
            if (!dir.exists() || dir.listFiles().isNullOrEmpty()) {
                dir.mkdirs()
                extract(context, uri, dir)
            }

            val containerFile = File(dir, "META-INF/container.xml")
            if (!containerFile.exists()) return@runCatching null
            val opfPath = readOpfPath(containerFile) ?: return@runCatching null
            val opfFile = File(dir, opfPath)
            if (!opfFile.exists()) return@runCatching null
            val opfDir = opfFile.parentFile ?: dir

            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(opfFile)

            // manifest: id -> href
            val idToHref = HashMap<String, String>()
            val manifestItems = doc.getElementsByTagName("item")
            for (i in 0 until manifestItems.length) {
                val el = manifestItems.item(i) as? Element ?: continue
                val id = el.getAttribute("id")
                val href = el.getAttribute("href")
                if (id.isNotEmpty() && href.isNotEmpty()) idToHref[id] = href
            }

            // spine order
            val chapters = ArrayList<File>()
            val itemrefs = doc.getElementsByTagName("itemref")
            for (i in 0 until itemrefs.length) {
                val el = itemrefs.item(i) as? Element ?: continue
                val idref = el.getAttribute("idref")
                val href = idToHref[idref] ?: continue
                val file = File(opfDir, href)
                if (file.exists() && looksLikeHtml(href)) chapters.add(file)
            }

            val title = firstTagText(doc, "dc:title")
                ?: firstTagText(doc, "title")
                ?: "EPUB"

            if (chapters.isEmpty()) null else EpubBook(title, chapters)
        }.getOrNull()
    }

    private fun looksLikeHtml(href: String): Boolean {
        val ext = href.substringAfterLast('.', "").lowercase()
        return ext == "html" || ext == "htm" || ext == "xhtml" || ext == "xml"
    }

    private fun readOpfPath(container: File): String? = runCatching {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(container)
        val rootfiles = doc.getElementsByTagName("rootfile")
        if (rootfiles.length == 0) return null
        (rootfiles.item(0) as Element).getAttribute("full-path").ifBlank { null }
    }.getOrNull()

    private fun firstTagText(doc: org.w3c.dom.Document, tag: String): String? {
        val nodes = doc.getElementsByTagName(tag)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent?.trim()?.ifBlank { null }
    }

    private fun extract(context: Context, uri: Uri, dir: File) {
        val canonicalRoot = dir.canonicalPath
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(dir, entry.name)
                    if (outFile.canonicalPath.startsWith(canonicalRoot)) {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zis.copyTo(it) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }
}
