package com.codex.agent.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Syntax highlighter leve (sem dependências externas) para Kotlin/Java/XML/Smali/JSON/Python/JS/Shell.
 * Tokeniza o código e aplica SpanStyle por categoria: keywords, strings, comentários, números,
 * anotações, tipos, chamadas de função. Funciona 100% offline.
 */
object SyntaxHighlighter {

    private val KEYWORDS = setOf(
        // Kotlin
        "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "annotation",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue", "import", "package",
        "private", "public", "protected", "internal", "override", "abstract", "open", "final", "suspend",
        "companion", "constructor", "init", "this", "super", "null", "true", "false", "is", "in", "as",
        "try", "catch", "finally", "throw", "typeof", "out", "in", "reified", "inline", "lazy",
        // Java
        "int", "long", "float", "double", "boolean", "char", "byte", "short", "void", "new", "static",
        "extends", "implements", "instanceof", "switch", "case", "default", "synchronized", "volatile",
        "transient", "native", "strictfp", "assert", "new",
        // Smali
        "const", "const-string", "iget", "iput", "sget", "sput", "invoke-virtual", "invoke-static",
        "invoke-direct", "invoke-interface", "return-void", "goto", "if-eq", "if-ne", "move-result",
        "new-instance", "check-cast", "instance-of", "array-length", ".class", ".super", ".source",
        ".method", ".end", ".field", ".implements", ".annotation"
    )

    private val TYPE_KEYWORDS = setOf(
        "String", "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte", "Short",
        "List", "MutableList", "Map", "MutableMap", "Set", "Array", "Unit", "Any", "Nothing",
        "ArrayList", "HashMap", "StringBuilder", "Context", "View", "Bundle", "Intent"
    )

    private val COL_KEYWORD = androidx.compose.ui.graphics.Color(0xFFC792EA)   // purple
    private val COL_STRING = androidx.compose.ui.graphics.Color(0xFFC3E88D)    // green
    private val COL_COMMENT = androidx.compose.ui.graphics.Color(0xFF546E7A)   // gray-blue
    private val COL_NUMBER = androidx.compose.ui.graphics.Color(0xFFF78C6C)    // orange
    private val COL_TYPE = androidx.compose.ui.graphics.Color(0xFF82AAFF)      // blue
    private val COL_ANNOTATION = androidx.compose.ui.graphics.Color(0xFFFFCB6B) // yellow
    private val COL_FUNCTION = androidx.compose.ui.graphics.Color(0xFF82AAFF)   // blue
    private val COL_DEFAULT = androidx.compose.ui.graphics.Color(0xFFD4D4D4)   // light gray

    /** Tokeniza o código e retorna um AnnotatedString colorido. */
    fun highlight(code: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            val c = code[i]

            // Comentários de linha
            if (c == '/' && i + 1 < code.length && code[i + 1] == '/') {
                val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                pushStyle(SpanStyle(color = COL_COMMENT, fontStyle = FontStyle.Italic, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                append(code.substring(i, end))
                pop()
                i = end
                continue
            }

            // Comentários de bloco
            if (c == '/' && i + 1 < code.length && code[i + 1] == '*') {
                val end = code.indexOf("*/", i + 2).let { if (it == -1) code.length else it + 2 }
                pushStyle(SpanStyle(color = COL_COMMENT, fontStyle = FontStyle.Italic, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                append(code.substring(i, end))
                pop()
                i = end
                continue
            }

            // Strings (simples, duplas, triplas)
            if (c == '"' || c == '\'') {
                val quote = c
                val isTriple = quote == '"' && i + 2 < code.length && code[i + 1] == '"' && code[i + 2] == '"'
                val end = if (isTriple) {
                    code.indexOf("\"\"\"", i + 3).let { if (it == -1) code.length else it + 3 }
                } else {
                    var j = i + 1
                    while (j < code.length) {
                        if (code[j] == '\\') { j += 2; continue }
                        if (code[j] == quote) { j += 1; break }
                        j++
                    }
                    j.coerceAtMost(code.length)
                }
                pushStyle(SpanStyle(color = COL_STRING, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                append(code.substring(i, end))
                pop()
                i = end
                continue
            }

            // Anotações (@)
            if (c == '@') {
                var j = i + 1
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '.')) j++
                pushStyle(SpanStyle(color = COL_ANNOTATION, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                append(code.substring(i, j))
                pop()
                i = j
                continue
            }

            // Números
            if (c.isDigit()) {
                var j = i + 1
                while (j < code.length && (code[j].isDigit() || code[j] == '.' || code[j] == 'x' || code[j] == 'L' || code[j] == 'f')) j++
                pushStyle(SpanStyle(color = COL_NUMBER, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                append(code.substring(i, j))
                pop()
                i = j
                continue
            }

            // Identificadores (palavras)
            if (c.isLetter() || c == '_') {
                var j = i + 1
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                val color = when {
                    word in KEYWORDS -> COL_KEYWORD
                    word in TYPE_KEYWORDS -> COL_TYPE
                    // Função: palavra seguida de '('
                    j < code.length && code[j] == '(' -> COL_FUNCTION
                    // XML tags / smali directives
                    word.startsWith(".") -> COL_ANNOTATION
                    else -> COL_DEFAULT
                }
                val style = if (word in KEYWORDS) SpanStyle(color = color, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                else SpanStyle(color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                pushStyle(style)
                append(word)
                pop()
                i = j
                continue
            }

            // Outros caracteres
            pushStyle(SpanStyle(color = COL_DEFAULT, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
            append(c)
            pop()
            i++
        }
    }
}
