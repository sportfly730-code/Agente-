package com.codex.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.agent.data.repository.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun AppSettingsScreen(
    settingsRepository: SettingsRepository
) {
    val scope = rememberCoroutineScope()
    val settingsSnapshot by settingsRepository.observe().collectAsState(
        initial = com.codex.agent.data.repository.AppSettingsSnapshot()
    )

    Surface(color = IdeBackground, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Configurações do Aplicativo",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = IdeTextPrimary
            )
            Spacer(modifier = Modifier.height(24.dp))

            // -- Seleção de Tema --
            Text("Tema do Aplicativo", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = IdeTextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            var showThemeMenu by remember { mutableStateOf(false) }
            val currentThemeName = runCatching { AppTheme.valueOf(settingsSnapshot.appTheme) }.getOrDefault(AppTheme.DEFAULT)

            Card(
                colors = CardDefaults.cardColors(containerColor = IdeSurfaceVariant),
                border = BorderStroke(1.dp, IdeBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showThemeMenu = true }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = currentThemeName.label, color = IdeTextPrimary, fontSize = 14.sp)
                    Icon(Icons.Default.ExpandMore, contentDescription = null, tint = IdeTextSecondary)
                }
            }
            DropdownMenu(
                expanded = showThemeMenu,
                onDismissRequest = { showThemeMenu = false }
            ) {
                AppTheme.entries.forEach { themeOpt ->
                    DropdownMenuItem(
                        text = { Text(themeOpt.label, color = IdeTextPrimary, fontSize = 14.sp) },
                        onClick = {
                            applyThemeColors(themeOpt)
                            scope.launch {
                                settingsRepository.setAppTheme(themeOpt.name)
                            }
                            showThemeMenu = false
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // -- Keep Alive Ping --
            Text("Conexão Contínua (Keep-Alive)", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = IdeTextPrimary)
            Text(
                "Se ativado, o aplicativo enviará mensagens ocultas a cada minuto (quando ocioso) para impedir que a conexão com a IA morra (útil para APIs que derrubam o modelo da VRAM).",
                fontSize = 12.sp,
                color = IdeTextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (settingsSnapshot.keepAliveEnabled) "Ativado (Automático)" else "Desativado (Manual)",
                    color = if (settingsSnapshot.keepAliveEnabled) IdeSecondary else IdeTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Switch(
                    checked = settingsSnapshot.keepAliveEnabled,
                    onCheckedChange = { checked ->
                        scope.launch { settingsRepository.setKeepAliveEnabled(checked) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = IdeBackground,
                        checkedTrackColor = IdeSecondary,
                        uncheckedThumbColor = IdeTextSecondary,
                        uncheckedTrackColor = IdeSurfaceVariant
                    )
                )
            }
        }
    }
}
