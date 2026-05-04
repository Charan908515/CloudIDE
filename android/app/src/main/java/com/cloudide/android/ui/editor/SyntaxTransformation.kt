package com.cloudide.android.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Compose VisualTransformation that paints syntax-highlighted spans on top of the
 * raw text. The transformation is identity (no chars added/removed), so cursor
 * offsets stay in sync.
 *
 * Skipped for very large files — re-running regex on every keystroke would lag.
 */
class SyntaxHighlightTransformation(
    private val highlighter: Highlighter,
    private val maxChars: Int = 80_000,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (highlighter == PlainHighlighter || text.length > maxChars) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val highlighted = applyHighlight(text.text, highlighter)
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}
