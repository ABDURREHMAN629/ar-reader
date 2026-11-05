package com.ahif.lumen.data.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ContentLoaders {

    private const val MAX_TEXT_CHARS = 3_000_000

    suspend fun readText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                if (text.length > MAX_TEXT_CHARS) text.substring(0, MAX_TEXT_CHARS) else text
            } ?: ""
        }.getOrElse { "Could not open this file.\n\n${it.message}" }
    }

    /** Split text into paragraph blocks (blank line separated), preserving order. */
    fun toParagraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\n[ \t]*\n"))
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
        return if (blocks.isEmpty()) listOf(normalized) else blocks
    }
}
