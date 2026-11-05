package com.ahif.lumen.data.library

import android.net.Uri
import android.os.Environment
import com.ahif.lumen.data.model.Document
import com.ahif.lumen.data.model.DocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Walks shared storage directly (requires All-files access) to auto-discover every
 * supported document, the way ReadEra does. Skips restricted and hidden folders.
 */
object FileSystemScanner {

    suspend fun scanExternal(): List<Document> = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory() ?: return@withContext emptyList()
        val results = ArrayList<Document>()
        val visited = HashSet<String>()
        walk(root, results, visited, depth = 0)
        results
    }

    private fun walk(dir: File, out: MutableList<Document>, visited: MutableSet<String>, depth: Int) {
        if (depth > 12) return
        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        if (!visited.add(canonical)) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (file in children) {
            if (file.isDirectory) {
                if (shouldSkipDir(file)) continue
                walk(file, out, visited, depth + 1)
            } else if (file.isFile) {
                val type = DocumentType.fromName(file.name)
                if (type != DocumentType.UNKNOWN && file.length() > 0) {
                    val uri = Uri.fromFile(file)
                    out.add(
                        Document(
                            id = uri.toString(),
                            uri = uri,
                            name = file.name,
                            type = type,
                            sizeBytes = file.length(),
                            lastModified = file.lastModified(),
                            folderName = file.parentFile?.name
                        )
                    )
                }
            }
        }
    }

    private fun shouldSkipDir(dir: File): Boolean {
        val name = dir.name
        if (name.startsWith(".")) return true          // hidden
        // Restricted app-private trees (Android/data & Android/obb) — but keep Android/media.
        val path = dir.absolutePath
        if (path.endsWith("/Android/data") || path.endsWith("/Android/obb")) return true
        return false
    }
}
