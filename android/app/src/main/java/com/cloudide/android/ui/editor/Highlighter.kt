package com.cloudide.android.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

data class HighlightSpan(val start: Int, val end: Int, val color: Color)

interface Highlighter {
    /**
     * Returns spans in **non-overlapping** order. Multiple rules may match the same range;
     * the highlighter resolves overlaps so callers can apply spans directly.
     */
    fun spans(text: CharSequence): List<HighlightSpan>
}

object PlainHighlighter : Highlighter {
    override fun spans(text: CharSequence): List<HighlightSpan> = emptyList()
}

/**
 * Pattern-based highlighter. Patterns are tried in priority order — the first match wins
 * for any given character (so e.g. comments must come before keywords).
 */
class RegexHighlighter(private val rules: List<Rule>) : Highlighter {
    data class Rule(val regex: Regex, val color: Color)

    override fun spans(text: CharSequence): List<HighlightSpan> {
        if (text.isEmpty()) return emptyList()
        val taken = BooleanArray(text.length)
        val out = mutableListOf<HighlightSpan>()
        for (rule in rules) {
            for (match in rule.regex.findAll(text)) {
                val range = match.range
                if (range.first < 0 || range.last >= text.length) continue
                // Only apply if the entire range is still untouched.
                var conflict = false
                for (i in range.first..range.last) {
                    if (taken[i]) { conflict = true; break }
                }
                if (conflict) continue
                for (i in range.first..range.last) taken[i] = true
                out.add(HighlightSpan(range.first, range.last + 1, rule.color))
            }
        }
        out.sortBy { it.start }
        return out
    }
}

fun applyHighlight(text: String, highlighter: Highlighter): AnnotatedString {
    if (highlighter == PlainHighlighter) return AnnotatedString(text)
    val builder = AnnotatedString.Builder(text)
    for (span in highlighter.spans(text)) {
        builder.addStyle(SpanStyle(color = span.color), span.start, span.end)
    }
    return builder.toAnnotatedString()
}
