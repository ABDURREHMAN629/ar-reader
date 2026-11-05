package com.ahif.lumen.ui.reader

import androidx.compose.runtime.Composable
import com.ahif.lumen.data.model.DocumentType
import com.ahif.lumen.data.model.LibraryItem

/** Chooses the correct reader for a document's type. */
@Composable
fun ReaderRoute(item: LibraryItem?, onBack: () -> Unit) {
    if (item == null) {
        ReaderScreen(item = null, onBack = onBack)
        return
    }
    when (item.document.type) {
        DocumentType.TEXT, DocumentType.MARKDOWN -> TextReader(item, onBack)
        DocumentType.IMAGE -> ImageReader(item, onBack)
        DocumentType.COMIC -> ComicReader(item, onBack)
        DocumentType.PDF -> PdfReader(item, onBack)
        DocumentType.EPUB -> EpubReader(item, onBack)
        DocumentType.EXCEL -> ProtectedOfficeGate(item, onBack) { SpreadsheetReader(it, onBack) }
        DocumentType.WORD, DocumentType.POWERPOINT -> ProtectedOfficeGate(item, onBack) { OfficeHtmlReader(it, onBack) }
        DocumentType.UNKNOWN -> ReaderScreen(item, onBack)
    }
}
