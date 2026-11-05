package com.ahif.lumen.data.model

import android.net.Uri

/** Supported document kinds. */
enum class DocumentType(val label: String) {
    PDF("PDF"),
    EPUB("EPUB"),
    TEXT("Text"),
    MARKDOWN("Markdown"),
    IMAGE("Image"),
    COMIC("Comic"),
    WORD("Word"),
    EXCEL("Excel"),
    POWERPOINT("Slides"),
    UNKNOWN("File");

    companion object {
        fun fromName(name: String): DocumentType {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "pdf" -> PDF
                "epub" -> EPUB
                "txt", "text", "log", "csv" -> TEXT
                "md", "markdown", "mkd" -> MARKDOWN
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif" -> IMAGE
                "cbz", "cbr", "cb7", "cbt" -> COMIC
                "docx", "doc" -> WORD
                "xlsx", "xls" -> EXCEL
                "pptx", "ppt", "ppsx", "pps" -> POWERPOINT
                else -> UNKNOWN
            }
        }

        /** Legacy OLE binary formats we can't parse without heavy libraries. */
        fun isLegacyBinary(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in setOf("doc", "xls", "ppt", "pps")

        val supportedExtensions: Set<String> = setOf(
            "pdf", "epub", "txt", "text", "log", "csv", "md", "markdown", "mkd",
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
            "cbz", "cbr", "cb7", "cbt",
            "docx", "doc", "xlsx", "xls", "pptx", "ppt", "ppsx", "pps"
        )
    }
}

/** A readable file discovered in the user's library. */
data class Document(
    val id: String,            // stable content-URI string
    val uri: Uri,
    val name: String,          // display name incl. extension
    val type: DocumentType,
    val sizeBytes: Long,
    val lastModified: Long,
    val folderName: String? = null,
    /** Where readers load the bytes from when not [uri] — e.g. a decrypted copy of a protected file. */
    val readUri: Uri? = null
) {
    val title: String get() = name.substringBeforeLast('.', name)

    /** What readers should open. Share/print keep using [uri] (the original file). */
    val contentUri: Uri get() = readUri ?: uri
}

/** Per-document user state that persists across sessions. */
data class DocState(
    val progress: Float = 0f,      // 0f..1f
    val positionUnit: Int = 0,     // page / chapter / image index
    val positionOffset: Int = 0,   // fine scroll offset within a unit
    val totalUnits: Int = 0,
    val lastOpened: Long = 0L,
    val favorite: Boolean = false,
    val zoom: Float = 1f,          // last PDF zoom level
    val readingTheme: String? = null, // this document's reading theme; null = never changed (opens Light)
    val bookmarks: List<Bookmark> = emptyList()
) {
    val started: Boolean get() = lastOpened > 0L
    val finished: Boolean get() = progress >= 0.999f
}

data class Bookmark(
    val unit: Int,
    val label: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** A library entry combining a document with its saved state. */
data class LibraryItem(
    val document: Document,
    val state: DocState
)

/** Stable key identifying the folder a document lives in (for grouping). */
val Document.folderKey: String
    get() = if (uri.scheme == "file") {
        uri.path?.substringBeforeLast('/')?.ifBlank { "/" } ?: (folderName ?: "Other")
    } else {
        folderName ?: "Other"
    }

/** Short folder name to show as a title. */
val Document.folderDisplayName: String
    get() = folderName
        ?: uri.path?.substringBeforeLast('/')?.substringAfterLast('/')?.ifBlank { "Files" }
        ?: "Files"

/** Human-readable full folder path (best effort). */
val Document.folderPathPretty: String
    get() {
        val path = uri.path?.substringBeforeLast('/') ?: return ""
        return path
            .replace("/storage/emulated/0", "Internal storage")
            .replace("/storage/emulated/legacy", "Internal storage")
    }
