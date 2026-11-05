package com.ahif.lumen.data.model

/** How the library grid/list is ordered. */
enum class SortOption(val label: String) {
    RECENT("Recently opened"),
    NAME_ASC("Name (A–Z)"),
    NAME_DESC("Name (Z–A)"),
    DATE_NEW("Newest first"),
    DATE_OLD("Oldest first"),
    SIZE_DESC("Largest first"),
    SIZE_ASC("Smallest first"),
    TYPE("File type");
}

enum class ViewMode { GRID, LIST }

/** Format filter chips shown above the library. */
enum class FormatFilter(val label: String, val types: Set<DocumentType>) {
    ALL("All", DocumentType.entries.toSet()),
    PDF("PDF", setOf(DocumentType.PDF)),
    BOOKS("Books", setOf(DocumentType.EPUB)),
    OFFICE("Office", setOf(DocumentType.WORD, DocumentType.EXCEL, DocumentType.POWERPOINT)),
    COMICS("Comics", setOf(DocumentType.COMIC)),
    IMAGES("Images", setOf(DocumentType.IMAGE)),
    TEXT("Text", setOf(DocumentType.TEXT, DocumentType.MARKDOWN));
}

/** Theme mode selection. */
enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark");
}
