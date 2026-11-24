package com.codex.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class AppTheme(val label: String) {
    DEFAULT("Padrão (GitHub Dark)"),
    BLACK("Todo Preto"),
    WHITE("Branco"),
    GREEN("Verde Matrix"),
    RED("Vermelho"),
    YELLOW("Amarelo")
}

var IdeBackground by mutableStateOf(Color(0xFF0D1117))
var IdeSurface by mutableStateOf(Color(0xFF161B22))
var IdeSurfaceVariant by mutableStateOf(Color(0xFF21262D))
var IdeBorder by mutableStateOf(Color(0xFF30363D))
var IdePrimary by mutableStateOf(Color(0xFF58A6FF))
var IdeSecondary by mutableStateOf(Color(0xFF3FB950))
var IdeWarning by mutableStateOf(Color(0xFFD29922))
var IdeError by mutableStateOf(Color(0xFFF85149))
var IdeTextPrimary by mutableStateOf(Color(0xFFE6EDF3))
var IdeTextSecondary by mutableStateOf(Color(0xFF8B949E))

fun applyThemeColors(theme: AppTheme) {
    when (theme) {
        AppTheme.DEFAULT -> {
            IdeBackground = Color(0xFF0D1117)
            IdeSurface = Color(0xFF161B22)
            IdeSurfaceVariant = Color(0xFF21262D)
            IdeBorder = Color(0xFF30363D)
            IdePrimary = Color(0xFF58A6FF)
            IdeSecondary = Color(0xFF3FB950)
            IdeWarning = Color(0xFFD29922)
            IdeError = Color(0xFFF85149)
            IdeTextPrimary = Color(0xFFE6EDF3)
            IdeTextSecondary = Color(0xFF8B949E)
        }
        AppTheme.BLACK -> {
            IdeBackground = Color(0xFF000000)
            IdeSurface = Color(0xFF0A0A0A)
            IdeSurfaceVariant = Color(0xFF141414)
            IdeBorder = Color(0xFF222222)
            IdePrimary = Color(0xFF58A6FF)
            IdeSecondary = Color(0xFF3FB950)
            IdeWarning = Color(0xFFD29922)
            IdeError = Color(0xFFF85149)
            IdeTextPrimary = Color(0xFFE6EDF3)
            IdeTextSecondary = Color(0xFF8B949E)
        }
        AppTheme.WHITE -> {
            IdeBackground = Color(0xFFFFFFFF)
            IdeSurface = Color(0xFFF0F0F0)
            IdeSurfaceVariant = Color(0xFFE0E0E0)
            IdeBorder = Color(0xFFCCCCCC)
            IdePrimary = Color(0xFF0366D6)
            IdeSecondary = Color(0xFF28A745)
            IdeWarning = Color(0xFFDBAB09)
            IdeError = Color(0xFFD73A49)
            IdeTextPrimary = Color(0xFF24292E)
            IdeTextSecondary = Color(0xFF586069)
        }
        AppTheme.GREEN -> {
            IdeBackground = Color(0xFF000000)
            IdeSurface = Color(0xFF001100)
            IdeSurfaceVariant = Color(0xFF002200)
            IdeBorder = Color(0xFF004400)
            IdePrimary = Color(0xFF00FF00)
            IdeSecondary = Color(0xFF00AA00)
            IdeWarning = Color(0xFFD29922)
            IdeError = Color(0xFFFF0000)
            IdeTextPrimary = Color(0xFF00FF00)
            IdeTextSecondary = Color(0xFF008800)
        }
        AppTheme.RED -> {
            IdeBackground = Color(0xFF1A0000)
            IdeSurface = Color(0xFF2D0000)
            IdeSurfaceVariant = Color(0xFF400000)
            IdeBorder = Color(0xFF660000)
            IdePrimary = Color(0xFFFF4444)
            IdeSecondary = Color(0xFFFF8888)
            IdeWarning = Color(0xFFD29922)
            IdeError = Color(0xFFFF0000)
            IdeTextPrimary = Color(0xFFFFDDDD)
            IdeTextSecondary = Color(0xFFAA5555)
        }
        AppTheme.YELLOW -> {
            IdeBackground = Color(0xFF1A1A00)
            IdeSurface = Color(0xFF2D2D00)
            IdeSurfaceVariant = Color(0xFF404000)
            IdeBorder = Color(0xFF666600)
            IdePrimary = Color(0xFFFFFF44)
            IdeSecondary = Color(0xFFFFFF88)
            IdeWarning = Color(0xFFFFAA00)
            IdeError = Color(0xFFFF0000)
            IdeTextPrimary = Color(0xFFFFFFDD)
            IdeTextSecondary = Color(0xFFAAAA55)
        }
    }
}

@Composable
fun CodexAgentTheme(content: @Composable () -> Unit) {
    val CodexDarkColorScheme = darkColorScheme(
        primary = IdePrimary,
        onPrimary = Color(0xFF041E42),
        secondary = IdeSecondary,
        onSecondary = Color(0xFF03260B),
        tertiary = IdeWarning,
        background = IdeBackground,
        onBackground = IdeTextPrimary,
        surface = IdeSurface,
        onSurface = IdeTextPrimary,
        surfaceVariant = IdeSurfaceVariant,
        onSurfaceVariant = IdeTextSecondary,
        error = IdeError,
        outline = IdeBorder
    )

    MaterialTheme(
        colorScheme = CodexDarkColorScheme,
        content = content
    )
}
