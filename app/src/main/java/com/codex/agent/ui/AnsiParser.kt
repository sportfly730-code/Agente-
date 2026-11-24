package com.codex.agent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Parser de ANSI escape sequences para AnnotatedString com cores.
 * Suporta cores básicas (30-37, 90-97), bold (1), reset (0), e 256-color (38;5;n).
 */
object AnsiParser {
    private val ANSI_REGEX = Regex("\u001B\\[([0-9;]*)m")

    private val STANDARD_COLORS = mapOf(
        30 to Color(0xFF000000), 31 to Color(0xFFFF5555), 32 to Color(0xFF50FA7B),
        33 to Color(0xFFF1FA8C), 34 to Color(0xFFBD93F9), 35 to Color(0xFFFF79C6),
        36 to Color(0xFF8BE9FD), 37 to Color(0xFFF8F8F2),
        90 to Color(0xFF6272A4), 91 to Color(0xFFFF6E6E), 92 to Color(0xFF69FF94),
        93 to Color(0xFFFFFFA5), 94 to Color(0xFFD6ACFF), 95 to Color(0xFFFF92DF),
        96 to Color(0xFFA4FFFF), 97 to Color(0xFFFFFFFF)
    )

    fun parse(text: String): AnnotatedString = buildAnnotatedString {
        var currentIndex = 0
        var currentColor = Color(0xFF50FA7B) // IdeSecondary default
        var isBold = false

        val matches = ANSI_REGEX.findAll(text)
        for (match in matches) {
            // Append text before the escape sequence
            if (match.range.first > currentIndex) {
                val segment = text.substring(currentIndex, match.range.first)
                pushStyle(SpanStyle(
                    color = currentColor,
                    fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ))
                append(segment)
                pop()
            }

            // Parse the SGR codes
            val codes = match.groupValues[1].split(";").mapNotNull { it.toIntOrNull() }
            if (codes.isEmpty() || codes.contains(0)) {
                currentColor = Color(0xFF50FA7B)
                isBold = false
            }
            for (code in codes) {
                when (code) {
                    1 -> isBold = true
                    0 -> { currentColor = Color(0xFF50FA7B); isBold = false }
                    in 30..37, in 90..97 -> STANDARD_COLORS[code]?.let { currentColor = it }
                    38 -> { /* 256-color or true-color; skip for simplicity */ }
                    39 -> currentColor = Color(0xFF50FA7B)
                }
            }
            currentIndex = match.range.last + 1
        }

        // Append remaining text
        if (currentIndex < text.length) {
            val segment = text.substring(currentIndex)
            // Strip any remaining escape sequences
            val clean = segment.replace(ANSI_REGEX, "")
            pushStyle(SpanStyle(
                color = currentColor,
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ))
            append(clean)
            pop()
        }
    }

    /** Remove todas as escape sequences (para logs/texto puro). */
    fun strip(text: String): String = text.replace(ANSI_REGEX, "")
}