package com.hereliesaz.geministrator

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Just enough Markdown for an agent's answer read on the hive (What it made): headings, bold,
 * italic, inline code, bullets and checkboxes. Anything else stays as written, so nothing is lost.
 */
internal fun markdownLite(source: String): AnnotatedString = buildAnnotatedString {
    val lines = source.replace("\r\n", "\n").trimEnd().lines()
    var inFence = false
    lines.forEachIndexed { index, raw ->
        if (index > 0) append('\n')
        if (raw.trimStart().startsWith("~~~") || raw.trimStart().startsWith("```")) {
            inFence = !inFence
            return@forEachIndexed
        }
        if (inFence) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(raw) }
            return@forEachIndexed
        }
        val indent = raw.takeWhile { it == ' ' }
        val line = raw.trimStart()
        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Black)) {
                inline(heading.groupValues[2].trimEnd('#', ' '))
            }
            line.startsWith("- [ ] ") || line.startsWith("* [ ] ") -> { append(indent); append("☐ "); inline(line.drop(6)) }
            line.startsWith("- [x] ", ignoreCase = true) || line.startsWith("* [x] ", ignoreCase = true) -> {
                append(indent); append("☑ "); inline(line.drop(6))
            }
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> { append(indent); append("• "); inline(line.drop(2)) }
            line.matches(Regex("^-{3,}$|^\\*{3,}$|^_{3,}$")) -> append("———")
            else -> { append(indent); inline(line) }
        }
    }
}

// No look-behind (not every web target has it): an italic's leading boundary is captured and kept.
private val inlineToken = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|(^|[\s(])\*(\S|\S[^*]*?\S)\*(?=$|[\s.,;:!?)])""")

private fun AnnotatedString.Builder.inline(text: String) {
    var at = 0
    for (match in inlineToken.findAll(text)) {
        append(text.substring(at, match.range.first))
        val (bold, boldAlt, code, lead, italic) = match.destructured
        when {
            bold.isNotEmpty() || boldAlt.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Black)) {
                inline(bold.ifEmpty { boldAlt })
            }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            else -> {
                append(lead)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(italic) }
            }
        }
        at = match.range.last + 1
    }
    append(text.substring(at))
}
