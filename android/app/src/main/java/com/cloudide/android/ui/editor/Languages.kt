package com.cloudide.android.ui.editor

import com.cloudide.android.ui.editor.HighlightTheme as T

private fun keywords(words: List<String>): Regex {
    val joined = words.joinToString("|")
    return Regex("\\b(?:$joined)\\b")
}

// Common to many C-like languages.
private val DOUBLE_STRING = Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"")
private val SINGLE_STRING = Regex("'(?:\\\\.|[^'\\\\\\n])*'")
private val BACKTICK_STRING = Regex("`(?:\\\\.|[^`\\\\])*`")
private val LINE_COMMENT = Regex("//.*")
private val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
private val NUMBER = Regex("\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b")

private val JS_TS_KEYWORDS = listOf(
    "var", "let", "const", "function", "class", "extends", "implements",
    "new", "this", "super", "static", "async", "await", "yield",
    "import", "export", "from", "as", "default",
    "true", "false", "null", "undefined", "NaN", "Infinity",
    "interface", "type", "enum", "namespace", "declare", "abstract",
    "public", "private", "protected", "readonly", "module"
)
private val JS_TS_CONTROL = listOf(
    "if", "else", "for", "while", "do", "return", "switch", "case",
    "break", "continue", "try", "catch", "finally", "throw", "in", "of"
)

val JsTsHighlighter: Highlighter = RegexHighlighter(listOf(
    RegexHighlighter.Rule(BLOCK_COMMENT, T.Comment),
    RegexHighlighter.Rule(LINE_COMMENT, T.Comment),
    RegexHighlighter.Rule(DOUBLE_STRING, T.String),
    RegexHighlighter.Rule(SINGLE_STRING, T.String),
    RegexHighlighter.Rule(BACKTICK_STRING, T.String),
    RegexHighlighter.Rule(keywords(JS_TS_CONTROL), T.ControlKeyword),
    RegexHighlighter.Rule(keywords(JS_TS_KEYWORDS), T.Keyword),
    RegexHighlighter.Rule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), T.Type),
    RegexHighlighter.Rule(Regex("\\b([a-zA-Z_$][\\w$]*)(?=\\s*\\()"), T.Function),
    RegexHighlighter.Rule(NUMBER, T.Number),
))

private val PY_KEYWORDS = listOf(
    "False", "None", "True", "and", "as", "assert", "async", "await",
    "class", "def", "del", "elif", "else", "except", "finally", "for",
    "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
    "not", "or", "pass", "raise", "return", "try", "while", "with",
    "yield", "self", "cls"
)

val PythonHighlighter: Highlighter = RegexHighlighter(listOf(
    RegexHighlighter.Rule(Regex("#.*"), T.Comment),
    RegexHighlighter.Rule(Regex("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''"), T.String),
    RegexHighlighter.Rule(DOUBLE_STRING, T.String),
    RegexHighlighter.Rule(SINGLE_STRING, T.String),
    RegexHighlighter.Rule(keywords(PY_KEYWORDS), T.Keyword),
    RegexHighlighter.Rule(Regex("@[A-Za-z_][\\w.]*"), T.Function),
    RegexHighlighter.Rule(Regex("\\b([A-Za-z_][\\w]*)(?=\\s*\\()"), T.Function),
    RegexHighlighter.Rule(NUMBER, T.Number),
))

val JsonHighlighter: Highlighter = RegexHighlighter(listOf(
    RegexHighlighter.Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\"\\s*:"), T.Attribute),
    RegexHighlighter.Rule(DOUBLE_STRING, T.String),
    RegexHighlighter.Rule(Regex("\\b(?:true|false|null)\\b"), T.Constant),
    RegexHighlighter.Rule(NUMBER, T.Number),
))

private val KT_KEYWORDS = listOf(
    "abstract", "actual", "annotation", "by", "catch", "class", "companion",
    "const", "constructor", "data", "delegate", "do", "dynamic", "else",
    "enum", "expect", "external", "false", "field", "file", "final", "finally",
    "fun", "get", "if", "import", "in", "infix", "init", "inline", "inner",
    "interface", "internal", "is", "lateinit", "noinline", "null", "object",
    "open", "operator", "out", "override", "package", "param", "private",
    "property", "protected", "public", "receiver", "reified", "return", "sealed",
    "set", "setparam", "super", "suspend", "tailrec", "this", "throw", "true",
    "try", "typealias", "typeof", "val", "var", "vararg", "when", "where",
    "while", "yield"
)

val KotlinHighlighter: Highlighter = RegexHighlighter(listOf(
    RegexHighlighter.Rule(BLOCK_COMMENT, T.Comment),
    RegexHighlighter.Rule(LINE_COMMENT, T.Comment),
    RegexHighlighter.Rule(Regex("\"\"\"[\\s\\S]*?\"\"\""), T.String),
    RegexHighlighter.Rule(DOUBLE_STRING, T.String),
    RegexHighlighter.Rule(keywords(KT_KEYWORDS), T.Keyword),
    RegexHighlighter.Rule(Regex("@[A-Za-z_][\\w.]*"), T.Function),
    RegexHighlighter.Rule(Regex("\\b[A-Z][A-Za-z0-9_]*\\b"), T.Type),
    RegexHighlighter.Rule(Regex("\\b([a-z_][\\w]*)(?=\\s*\\()"), T.Function),
    RegexHighlighter.Rule(NUMBER, T.Number),
))

val HtmlHighlighter: Highlighter = RegexHighlighter(listOf(
    RegexHighlighter.Rule(Regex("<!--[\\s\\S]*?-->"), T.Comment),
    RegexHighlighter.Rule(Regex("<![A-Za-z][^>]*>"), T.Keyword),
    RegexHighlighter.Rule(Regex("</?[A-Za-z][\\w-]*"), T.Tag),
    RegexHighlighter.Rule(Regex("[A-Za-z-]+(?==)"), T.Attribute),
    RegexHighlighter.Rule(DOUBLE_STRING, T.String),
    RegexHighlighter.Rule(SINGLE_STRING, T.String),
    RegexHighlighter.Rule(Regex("[/<>]"), T.Punctuation),
))

val CssHighlighter: Highlighter = RegexHighlighter(listOf(
    RegexHighlighter.Rule(BLOCK_COMMENT, T.Comment),
    RegexHighlighter.Rule(Regex("[.#][A-Za-z][\\w-]*"), T.Type),
    RegexHighlighter.Rule(Regex("@[A-Za-z-]+"), T.Keyword),
    RegexHighlighter.Rule(Regex("[A-Za-z-]+(?=\\s*:)"), T.Attribute),
    RegexHighlighter.Rule(DOUBLE_STRING, T.String),
    RegexHighlighter.Rule(SINGLE_STRING, T.String),
    RegexHighlighter.Rule(Regex("#[0-9A-Fa-f]{3,8}\\b"), T.Constant),
    RegexHighlighter.Rule(NUMBER, T.Number),
))

val MarkdownHighlighter: Highlighter = RegexHighlighter(listOf(
    RegexHighlighter.Rule(Regex("(?m)^#{1,6}.*"), T.Heading),
    RegexHighlighter.Rule(Regex("```[\\s\\S]*?```"), T.String),
    RegexHighlighter.Rule(Regex("`[^`\\n]+`"), T.String),
    RegexHighlighter.Rule(Regex("\\*\\*[^*]+\\*\\*|__[^_]+__"), T.Keyword),
    RegexHighlighter.Rule(Regex("\\*[^*\\n]+\\*|_[^_\\n]+_"), T.ControlKeyword),
    RegexHighlighter.Rule(Regex("\\[[^\\]]*]\\([^\\)]*\\)"), T.Function),
    RegexHighlighter.Rule(Regex("(?m)^[-*+] "), T.Punctuation),
))

private val SHELL_KEYWORDS = listOf(
    "if", "then", "else", "elif", "fi", "case", "esac", "for", "while",
    "do", "done", "in", "function", "return", "break", "continue",
    "export", "local", "readonly", "echo", "exit", "set", "unset"
)

val ShellHighlighter: Highlighter = RegexHighlighter(listOf(
    RegexHighlighter.Rule(Regex("#.*"), T.Comment),
    RegexHighlighter.Rule(DOUBLE_STRING, T.String),
    RegexHighlighter.Rule(SINGLE_STRING, T.String),
    RegexHighlighter.Rule(keywords(SHELL_KEYWORDS), T.Keyword),
    RegexHighlighter.Rule(Regex("\\$\\{?[A-Za-z_][\\w]*\\}?"), T.Variable),
    RegexHighlighter.Rule(NUMBER, T.Number),
))

fun highlighterForFileName(fileName: String): Highlighter {
    val lower = fileName.lowercase()
    return when {
        lower.endsWith(".ts") || lower.endsWith(".tsx") ||
                lower.endsWith(".js") || lower.endsWith(".jsx") ||
                lower.endsWith(".mjs") || lower.endsWith(".cjs") -> JsTsHighlighter
        lower.endsWith(".py") -> PythonHighlighter
        lower.endsWith(".json") -> JsonHighlighter
        lower.endsWith(".kt") || lower.endsWith(".kts") -> KotlinHighlighter
        lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xml") -> HtmlHighlighter
        lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".sass") || lower.endsWith(".less") -> CssHighlighter
        lower.endsWith(".md") || lower.endsWith(".markdown") -> MarkdownHighlighter
        lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh") -> ShellHighlighter
        else -> PlainHighlighter
    }
}
