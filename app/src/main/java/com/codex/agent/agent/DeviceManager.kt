package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Gerenciador de dispositivos Android conectados (SEUS próprios dispositivos).
 *
 * Suporta:
 *  - listar dispositivos via adb (USB e WiFi)
 *  - conectar via ADB over WiFi (pareamento Android 11+ e conexão TCP)
 *  - desconectar, reiniciar adb server, capturar screenshot/logcat do dispositivo
 *  - instalar APK em dispositivo conectado
 *
 * Finalidade: depuração/controle dos seus próprios aparelhos na sua rede.
 * Não acessa dispositivos de terceiros sem autorização.
 */
class DeviceManager(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository
) {

    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'action' é obrigatório."
        return try {
            when (action) {
                "list" -> listDevices()
                "connect_wifi" -> connectWifi(args)
                "pair_wifi" -> pairWifi(args)
                "disconnect" -> disconnect(args)
                "restart_server" -> restartServer()
                "screenshot" -> screenshot(args)
                "logcat" -> logcat(args)
                "install_apk" -> installApk(args)
                "silent_install" -> silentInstall(args)
                "shell" -> remoteShell(args)
                else -> "Ação desconhecida em device_manager: $action"
            }
        } catch (e: Exception) {
            "Erro em device_manager ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    private fun sq(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    private suspend fun run(cmd: String, timeout: Int = 60): String {
        val r = shellExecutor.execute(cmd, timeoutSec = timeout)
        return if (r.exitCode == 0) r.output.trim().ifBlank { "OK (sem saída)" }
        else "exit=${r.exitCode}:\n${r.output.trim()}"
    }

    private suspend fun adbAvailable(): Boolean =
        shellExecutor.execute("command -v adb >/dev/null 2>&1 && echo yes || echo no", timeoutSec = 15)
            .output.trim() == "yes"

    private suspend fun listDevices(): String {
        if (!adbAvailable()) return "adb não instalado. Instale android-tools: pkg install android-tools"
        val out = run("adb devices -l 2>&1")
        val lines = out.lines().filter { it.contains("device") || it.contains("offline") || it.contains("unauthorized") }
        return if (lines.size <= 1) "Nenhum dispositivo conectado.\n\nDica USB: ative 'Depuração USB' no aparelho.\n" +
            "Dica WiFi: use action='pair_wifi' (Android 11+) ou 'connect_wifi'."
        else "=== Dispositivos conectados ===\n$out"
    }

    private suspend fun connectWifi(args: JsonObject): String {
        if (!adbAvailable()) return "adb não instalado."
        val host = args["host"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'host' é obrigatório (IP:porta)."
        if (!host.matches(Regex("^[A-Za-z0-9.:_-]{1,120}$"))) return "Erro: host inválido."
        val port = (args["port"]?.jsonPrimitive?.contentOrNull ?: "5555").toIntOrNull()
            ?: return "Erro: 'port' deve ser numérico."
        val target = if (host.contains(":")) host else "$host:$port"
        return run("adb connect ${sq(target)} 2>&1", 30)
    }

    /** Pareamento ADB via WiFi (Android 11+): requer código e porta de pareamento do menu do dispositivo. */
    private suspend fun pairWifi(args: JsonObject): String {
        if (!adbAvailable()) return "adb não instalado."
        val host = args["host"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'host' é obrigatório."
        if (!host.matches(Regex("^[A-Za-z0-9.:_-]{1,120}$"))) return "Erro: host inválido."
        val pairPort = (args["pair_port"]?.jsonPrimitive?.contentOrNull)?.toIntOrNull()
            ?: return "Erro: 'pair_port' obrigatório e numérico."
        val code = args["code"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'code' obrigatório (código de pareamento de 6 dígitos)."
        if (!code.matches(Regex("^[0-9]{6}$"))) return "Erro: código de pareamento deve ter 6 dígitos."
        val pair = run("adb pair ${sq("$host:$pairPort")} ${sq(code)} 2>&1", 30)
        val connectPort = (args["connect_port"]?.jsonPrimitive?.contentOrNull ?: "5555").toIntOrNull() ?: 5555
        val connect = run("adb connect ${sq("$host:$connectPort")} 2>&1", 30)
        return "Pareamento:\n$pair\n\nConexão:\n$connect"
    }

    private suspend fun disconnect(args: JsonObject): String {
        if (!adbAvailable()) return "adb não instalado."
        val host = args["host"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'host' obrigatório."
        return run("adb disconnect $host 2>&1", 30)
    }

    private suspend fun restartServer(): String {
        if (!adbAvailable()) return "adb não instalado."
        val a = run("adb kill-server 2>&1", 20)
        val b = run("adb start-server 2>&1", 30)
        return "Servidor ADB reiniciado.\n$a\n$b"
    }

    private suspend fun screenshot(args: JsonObject): String {
        if (!adbAvailable()) return "adb não instalado."
        val serial = args["serial"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val prefix = if (serial.isBlank()) "" else "-s $serial "
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/device_screenshot_${System.currentTimeMillis()}.png")
        out.parentFile?.mkdirs()
        val r = shellExecutor.execute("adb ${prefix}exec-out screencap -p > \"${out.absolutePath}\"", timeoutSec = 60)
        return if (r.exitCode == 0 && out.exists() && out.length() > 0) "Screenshot salvo: ${out.absolutePath} (${out.length()} bytes)"
        else "Falha ao capturar: ${r.output.trim()}"
    }

    private suspend fun logcat(args: JsonObject): String {
        if (!adbAvailable()) return "adb não instalado."
        val serial = args["serial"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val prefix = if (serial.isBlank()) "" else "-s $serial "
        val lines = (args["lines"]?.jsonPrimitive?.contentOrNull ?: "100").toIntOrNull()
            ?.coerceIn(1, 10000) ?: 100
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // filtro: só caracteres seguros para grep
        val safeFilter = filter.filter { it.isLetterOrDigit() || it in "._-" }
        return run("adb ${prefix}logcat -d -t $lines ${if (safeFilter.isBlank()) "" else "| grep -i ${sq(safeFilter)}"} 2>&1", 60)
    }

    private suspend fun installApk(args: JsonObject): String {
        if (!adbAvailable()) return "adb não instalado."
        val apk = args["apk"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'apk' obrigatório."
        val serial = args["serial"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val prefix = if (serial.isBlank()) "" else "-s $serial "
        return run("adb ${prefix}install -r \"$apk\" 2>&1", 180)
    }

    /**
     * Instalação silenciosa APK no dispositivo conectado (USO PRÓPRIO / autorizado).
     * Usa `pm install -r -g`: substitui o app e concede as permissões de runtime
     * automaticamente, sem diálogo de confirmação na tela.
     *
     * Requisitos: dispositivo conectado via ADB com depuração já autorizada pelo dono.
     * Funciona somente em aparelhos que você possui/controla — não é bypass de consentimento.
     */
    private suspend fun silentInstall(args: JsonObject): String {
        if (!adbAvailable()) return "adb não instalado."
        val apk = args["apk"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'apk' obrigatório (caminho do APK)."
        val serial = args["serial"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val prefix = if (serial.isBlank()) "" else "-s $serial "
        // Empurra o APK para /data/local/tmp (onde o instalador do sistema pode ler) e instala
        val remote = "/data/local/tmp/_codex_silent_${System.currentTimeMillis()}.apk"
        val push = run("adb ${prefix}push \"$apk\" $remote 2>&1", 180)
        if (push.contains("error", ignoreCase = true) || push.contains("failed", ignoreCase = true)) {
            return "Falha ao enviar APK:\n$push"
        }
        val install = run("adb ${prefix}shell \"pm install -r -g $remote\" 2>&1", 180)
        run("adb ${prefix}shell \"rm -f $remote\" 2>&1", 30)
        return "Instalação silenciosa concluída:\n$install"
    }

    private suspend fun remoteShell(args: JsonObject): String {
        if (!adbAvailable()) return "adb não instalado."
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'command' obrigatório."
        val serial = args["serial"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (serial.isNotBlank() && !serial.matches(Regex("^[A-Za-z0-9.:_-]{1,120}$"))) return "Erro: serial inválido."
        val prefix = if (serial.isBlank()) "" else "-s ${sq(serial)} "
        // Analisa o comando exato que o shell remoto vai interpretar (aspas simples no outer shell
        // impedem que $/backticks sejam expandidos antes do sh -c — o que o guardrail checou).
        val block = com.codex.agent.shell.ShellSafetyGuard.analyze(command)
        if (block != null) return "BLOQUEADO POR SEGURANÇA: $block"
        // Envolve em aspas simples (escapando ' internas) para o outer shell do adb.
        val quoted = "'" + command.replace("'", "'\\''") + "'"
        return run("adb ${prefix}shell $quoted 2>&1", 120)
    }

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT
}
