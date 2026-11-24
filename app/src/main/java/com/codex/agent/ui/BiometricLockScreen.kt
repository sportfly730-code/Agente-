package com.codex.agent.ui

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Portão biométrico opcional (ativado em Configurações → Segurança).
 * Bloqueia a UI até a autenticação bem-sucedida; se o dispositivo não tiver
 * biometria, libera automaticamente.
 */
@Composable
fun BiometricLockScreen(
    enabled: Boolean,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    var attempted by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            onUnlocked()
            return@LaunchedEffect
        }
        val activity = context as? FragmentActivity ?: run {
            onUnlocked()
            return@LaunchedEffect
        }
        val bm = BiometricManager.from(context)
        val canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Sem biometria disponível → libera (não bloqueia o uso)
            onUnlocked()
            return@LaunchedEffect
        }
        if (attempted) return@LaunchedEffect
        attempted = true

        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onUnlocked()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    // Mantém bloqueado; usuário pode tentar de novo
                } else {
                    Toast.makeText(context, "Biometria indisponível: $errString", Toast.LENGTH_SHORT).show()
                    onUnlocked()
                }
            }
            override fun onAuthenticationFailed() {
                Toast.makeText(context, "Falha na autenticação. Tente novamente.", Toast.LENGTH_SHORT).show()
            }
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Codex Agent — Desbloquear")
            .setSubtitle("Autentique-se para acessar o agente e as chaves de API")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }

    if (enabled) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(IdeBackground),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = IdePrimary, modifier = Modifier.height(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("App bloqueado", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = IdeTextPrimary)
            Spacer(Modifier.height(8.dp))
            Text("Use a biometria ou PIN do dispositivo para continuar.", fontSize = 12.sp, color = IdeTextSecondary)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { attempted = false }) {
                Text("Tentar novamente")
            }
        }
    }
}
