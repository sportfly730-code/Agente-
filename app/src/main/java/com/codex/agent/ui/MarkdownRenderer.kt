package com.codex.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Renderizador leve de Markdown para as mensagens do chat.
 * Suporta: **bold**, *italic*, `inline code`, ```bloco de código```, listas com -, e links.
 * Blocos de código usam SyntaxHighlighter para cores.
 */
object MarkdownRenderer {

    fun render(text: String): AnnotatedString = buildAnnotatedString {
        val lines = text.split("\n")
        var inCodeBlock = false
        val codeLines = mutableListOf<String>()
        val codeLang = StringBuilder()

        for (line in lines) {
            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    // Fecha bloco
                    val highlighted = SyntaxHighlighter.highlight(codeLines.joinToString("\n"))
                    append(highlighted)
                    append("\n")
                    codeLines.clear()
                    codeLang.clear()
                    inCodeBlock = false
                } else {
                    // Abre bloco
                    inCodeBlock = true
                    val lang = line.trim().removePrefix("```").trim()
                    if (lang.isNotBlank()) codeLang.append(lang)
                    // pequeno espaçamento
                    append("\n")
                }
                continue
            }

            if (inCodeBlock) {
                codeLines.add(line)
                continue
            }

            // Lista
            if (line.trim().startsWith("- ") || line.trim().startsWith("* ")) {
                append("  • ")
                appendInline(line.trim().substring(2))
                append("\n")
                continue
            }
            if (line.trim().matches(Regex("^\\d+\\.\\s+.*"))) {
                append("  " + line.trim().substringBefore(".") + ". ")
                appendInline(line.trim().substringAfter(". ").trim())
                append("\n")
                continue
            }

            // Linha normal
            appendInline(line)
            append("\n")
        }

        // Se ficou aberto (malformado), fecha
        if (inCodeBlock && codeLines.isNotEmpty()) {
            val highlighted = SyntaxHighlighter.highlight(codeLines.joinToString("\n"))
            append(highlighted)
            append("\n")
        }
    }

    private fun AnnotatedString.Builder.appendInline(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                // Inline code `...`
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        val code = text.substring(i + 1, end)
                        pushStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = IdeSurfaceVariant,
                            color = IdeSecondary,
                            fontSize = 12.sp
                        ))
                        append(code)
                        pop()
                        i = end + 1
                        continue
                    }
                    append(c); i++
                }
                // Bold **...**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = IdeTextPrimary))
                        append(text.substring(i + 2, end))
                        pop()
                        i = end + 2
                        continue
                    }
                    append("**"); i += 2
                }
                // Italic *...*
                c == '*' && !text.startsWith("**", i) -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i) {
                        pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = IdeTextPrimary))
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                        continue
                    }
                    append(c); i++
                }
                // Link [text](url) — só renderiza o texto
                c == '[' -> {
                    val closeBracket = text.indexOf(']', i)
                    val openParen = if (closeBracket > i) text.indexOf('(', closeBracket) else -1
                    val closeParen = if (openParen > closeBracket) text.indexOf(')', openParen) else -1
                    if (closeBracket > i && closeParen > openParen) {
                        pushStyle(SpanStyle(color = IdePrimary, fontWeight = FontWeight.Medium))
                        append(text.substring(i + 1, closeBracket))
                        pop()
                        i = closeParen + 1
                        continue
                    }
                    append(c); i++
                }
                else -> { append(c); i++ }
            }
        }
    }
}
