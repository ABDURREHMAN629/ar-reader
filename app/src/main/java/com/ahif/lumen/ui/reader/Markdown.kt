package com.ahif.lumen.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

enum class BlockKind { H1, H2, H3, BULLET, QUOTE, CODE, PARAGRAPH }

data class TextBlock(val kind: BlockKind, val content: AnnotatedString)

/** Very small Markdown-ish formatter: headings, bullets, quotes, code, bold/italic/inline-code. */
fun parseBlocks(paragraphs: List<String>, markdown: Boolean): List<TextBlock> {
    return paragraphs.map { raw ->
        val line = raw.trimStart()
        if (!markdown) return@map TextBlock(BlockKind.PARAGRAPH, AnnotatedString(raw))
        when {
            line.startsWith("### ") -> TextBlock(BlockKind.H3, annotateInline(line.removePrefix("### ")))
            line.startsWith("## ") -> TextBlock(BlockKind.H2, annotateInline(line.removePrefix("## ")))
            line.startsWith("# ") -> TextBlock(BlockKind.H1, annotateInline(line.removePrefix("# ")))
            line.startsWith("> ") -> TextBlock(BlockKind.QUOTE, annotateInline(line.removePrefix("> ")))
            line.startsWith("```") -> TextBlock(BlockKind.CODE, AnnotatedString(raw.trim('`', '\n', ' ')))
            line.startsWith("- ") || line.startsWith("* ") ->
                TextBlock(BlockKind.BULLET, annotateInline(line.drop(2)))
            else -> TextBlock(BlockKind.PARAGRAPH, annotateInline(raw))
        }
    }
}

private fun annotateInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        when {
            rest.startsWith("**") -> {
                val end = rest.indexOf("**", 2)
                if (end > 0) {
                    withStyleRange(SpanStyle(fontWeight = FontWeight.Bold)) { append(rest.substring(2, end)) }
                    i += end + 2
                } else { append("**"); i += 2 }
            }
            rest.startsWith("*") -> {
                val end = rest.indexOf('*', 1)
                if (end > 0) {
                    withStyleRange(SpanStyle(fontStyle = FontStyle.Italic)) { append(rest.substring(1, end)) }
                    i += end + 1
                } else { append("*"); i += 1 }
            }
            rest.startsWith("`") -> {
                val end = rest.indexOf('`', 1)
                if (end > 0) {
                    withStyleRange(SpanStyle(fontFamily = FontFamily.Monospace)) { append(rest.substring(1, end)) }
                    i += end + 1
                } else { append("`"); i += 1 }
            }
            else -> { append(text[i]); i += 1 }
        }
    }
}

private inline fun AnnotatedString.Builder.withStyleRange(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    val start = length
    block()
    addStyle(style, start, length)
}
