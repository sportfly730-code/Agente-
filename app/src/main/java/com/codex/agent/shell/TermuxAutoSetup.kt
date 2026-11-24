package com.codex.agent.shell

import android.content.Context
import android.content.pm.PackageManager
import com.codex.agent.data.model.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Configuração AUTOMÁTICA da ponte Termux — sem intervenção do usuário.
 *
 * Estratégia (funciona com Shizuku concedido ou via adb shell):
 *  1. Escreve um script de setup em /sdcard (área pública, acessível ao app e ao Termux).
 *  2. Abre o Termux via `am start` (comando shell via Shizuku).
 *  3. Usa `input text` / `input keyevent` via Shizuku para digitar `bash /sdcard/<script>`
 *     no terminal do Termux e dar Enter.
 *  4. O script (rodando DENTRO do Termux) escreve `allow-external-apps=true` no
 *     termux.properties, recarrega as settings e cria a flag `.ready`.
 *
 * Isso resolve o ciclo virtuoso: RUN_COMMAND exige allow-external-apps, mas para ativá-lo
 * precisamos rodar um comando DENTRO do Termux — feito automaticamente via input.
 */
class TermuxAutoSetup(
    private val context: Context,
    private val runShellViaShizuku: suspend (Array<String>) -> String?
) {
    companion object {
        private const val SCRIPT_NAME = "codex_auto_setup.sh"
    }

    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        File(Constants.TERMUX_BRIDGE_READY_FLAG).exists()
    }

    suspend fun autoConfigure(): String = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext "Ponte Termux já está pronta."

        // 1) Escreve o script de setup no /sdcard
        val script = File(android.os.Environment.getExternalStorageDirectory(), SCRIPT_NAME)
        val scriptContent = """
            mkdir -p ~/.termux
            grep -v 'allow-external-apps' ~/.termux/termux.properties > /tmp/.tp 2>/dev/null
            cp /tmp/.tp ~/.termux/termux.properties 2>/dev/null
            echo 'allow-external-apps=true' >> ~/.termux/termux.properties
            termux-reload-settings
            mkdir -p /sdcard/.codex_agent_bridge
            echo ready > /sdcard/.codex_agent_bridge/.ready
            echo PONTE_TERMUX_PRONTA
        """.trimIndent()
        runCatching { script.writeText(scriptContent) }
            .onFailure { return@withContext "Falha ao escrever script de setup: ${it.message}" }

        // 2) Abre o Termux
        runShellViaShizuku(arrayOf("am", "start", "-n", "com.termux/com.termux.app.TermuxActivity"))
        delay(2500)

        // 3) Garante que o Termux está em foco (verifica topResumedActivity)
        var focused = false
        repeat(6) {
            val out = runShellViaShizuku(arrayOf("dumpsys", "activity", "activities"))
            if (out != null && out.contains("com.termux/.app.TermuxActivity")) {
                focused = true
                return@repeat
            }
            runShellViaShizuku(arrayOf("am", "start", "-n", "com.termux/com.termux.app.TermuxActivity"))
            delay(1200)
        }
        if (!focused) {
            return@withContext "Não foi possível focar o Termux automaticamente. " +
                "Abra o Termux manualmente e rode: bash /sdcard/$SCRIPT_NAME"
        }

        // 4) Digita o comando no Termux e executa
        runShellViaShizuku(arrayOf("input", "text", "bash"))
        runShellViaShizuku(arrayOf("input", "keyevent", "62")) // espaço
        runShellViaShizuku(arrayOf("input", "text", script.absolutePath))
        runShellViaShizuku(arrayOf("input", "keyevent", "66")) // Enter

        // 5) Aguarda a flag .ready aparecer
        val deadline = System.currentTimeMillis() + 20_000
        while (!isReady() && System.currentTimeMillis() < deadline) {
            delay(500)
        }

        if (isReady()) {
            "✅ Ponte Termux configurada automaticamente via Shizuku."
        } else {
            "Comando enviado ao Termux, mas a flag .ready ainda não apareceu. " +
                "Verifique o Termux (rode: bash /sdcard/$SCRIPT_NAME)."
        }
    }
}
