package com.ahif.lumen.ui.reader

import androidx.compose.runtime.Composable
import com.ahif.lumen.data.model.DocumentType
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.reader.ContentLoaders

@Composable
fun TextReader(item: LibraryItem, onBack: () -> Unit) {
    BlockReader(item = item, onBack = onBack) { context ->
        val text = ContentLoaders.readText(context, item.document.uri)
        val paragraphs = ContentLoaders.toParagraphs(text)
        parseBlocks(paragraphs, markdown = item.document.type == DocumentType.MARKDOWN)
    }
}
