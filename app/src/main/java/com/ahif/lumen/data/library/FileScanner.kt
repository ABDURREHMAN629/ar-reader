package com.ahif.lumen.data.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.ahif.lumen.data.model.Document
import com.ahif.lumen.data.model.DocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recursively scans a Storage-Access-Framework tree for supported documents
 * using DocumentsContract queries (far faster than DocumentFile.listFiles).
 */
object FileScanner {

    private const val MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR

    suspend fun scanTree(context: Context, treeUri: Uri): List<Document> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Document>()
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext emptyList()
        val rootName = treeDisplayName(treeUri)
        val queue = ArrayDeque<Pair<String, String?>>() // docId to folderName
        queue.add(rootDocId to rootName)

        while (queue.isNotEmpty()) {
            val (docId, folderName) = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            runCatching {
                context.contentResolver.query(childrenUri, projection, null, null, null)
            }.getOrNull()?.use { c ->
                val idIdx = 0; val nameIdx = 1; val mimeIdx = 2; val sizeIdx = 3; val modIdx = 4
                while (c.moveToNext()) {
                    val childId = c.getString(idIdx)
                    val name = c.getString(nameIdx) ?: continue
                    val mime = c.getString(mimeIdx)
                    val size = if (c.isNull(sizeIdx)) 0L else c.getLong(sizeIdx)
                    val modified = if (c.isNull(modIdx)) 0L else c.getLong(modIdx)
                    if (mime == MIME_DIR) {
                        queue.add(childId to name)
                    } else {
                        val type = DocumentType.fromName(name)
                        if (type != DocumentType.UNKNOWN) {
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            results += Document(
                                id = childUri.toString(),
                                uri = childUri,
                                name = name,
                                type = type,
                                sizeBytes = size,
                                lastModified = modified,
                                folderName = folderName
                            )
                        }
                    }
                }
            }
        }
        results
    }

    private fun treeDisplayName(treeUri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return "Folder"
        return id.substringAfterLast('/').substringAfterLast(':').ifBlank { "Folder" }
    }

    /** Build a Document from a single file URI (e.g. opened via another app or the file picker). */
    fun documentFromUri(context: Context, uri: Uri): Document? {
        var name: String? = null
        var size = 0L
        var modified = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (modIdx >= 0 && !c.isNull(modIdx)) modified = c.getLong(modIdx)
                }
            }
        }
        val displayName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: return null
        return Document(
            id = uri.toString(),
            uri = uri,
            name = displayName,
            type = DocumentType.fromName(displayName),
            sizeBytes = size,
            lastModified = modified,
            folderName = null
        )
    }
}
