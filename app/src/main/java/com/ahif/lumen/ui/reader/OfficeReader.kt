package com.ahif.lumen.ui.reader

import androidx.compose.runtime.Composable
import com.ahif.lumen.data.model.LibraryItem
import com.ahif.lumen.data.reader.OfficeExtractor

@Composable
fun OfficeReader(item: LibraryItem, onBack: () -> Unit) {
    BlockReader(item = item, onBack = onBack) { context ->
        OfficeExtractor.extract(context, item.document.uri, item.document.name, item.document.type)
    }
}
