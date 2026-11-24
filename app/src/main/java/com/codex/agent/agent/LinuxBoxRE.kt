package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Properties

/**
 * RE de Cineboxes / TV boxes LINUX (Enigma2, E2B, firmware própria baseada em Linux).
 * Conecta via SSH (JSch) nos aparelhos do PRÓPRIO usuário.
 *
 * Escopo legítimo: modificar o sistema do seu aparelho, instalar seu IPTV, ler logs,
 * listar arquivos, executar comandos. Sem burlar DRM, licenças ou assinaturas de conteúdo.
 *
 * Ações: connect, disconnect, exec, info, files, pull, push, packages, processes,
 * services, logs, install_iptv, reboot, mounts, firmware_info.
 */
class LinuxBoxRE(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository
) {
    private var session: Session? = null
    private var lastHost = ""
    private var lastUser = ""
    private var lastPort = 22

    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' obrigatório."
        return try {
            when (action) {
                "connect" -> connect(args)
                "disconnect" -> disconnect()
                "status" -> status()
                "exec" -> exec(args)
                "info" -> execCmd("uname -a; cat /etc/os-release 2>/dev/null | head -10; cat /proc/cpuinfo 2>/dev/null | grep -m2 'Hardware\\|model name'; free -h 2>/dev/null | head -3; df -h 2>/dev/null | head -8")
                "files" -> execCmd("ls -la ${args["path"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "/" }} 2>&1 | head -80")
                "processes" -> execCmd("ps aux 2>/dev/null | head -60")
                "services" -> execCmd("ps | grep -iE 'enigma|e2 |dvb|stream|iptv' | head -30")
                "logs" -> execCmd("tail -n 100 /var/log/messages 2>/dev/null || dmesg 2>/dev/null | tail -n 100")
                "mounts" -> execCmd("cat /proc/mounts 2>/dev/null | head -50")
                "firmware_info" -> execCmd("cat /etc/issue 2>/dev/null; cat /proc/version 2>/dev/null; uptime 2>/dev/null; cat /etc/enigma2/settings 2>/dev/null | head -30")
                "packages" -> execCmd("opkg list-installed 2>/dev/null | head -80 || dpkg -l 2>/dev/null | head -80")
                "pull" -> pull(args)
                "push" -> push(args)
                "install_iptv" -> installIptv(args)
                "reboot" -> execCmd("reboot 2>&1 || echo 'reboot enviado'")
                else -> "Ação desconhecida em linuxbox_re: $action"
            }
        } catch (e: Exception) {
            "Erro em linuxbox_re ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    private fun connect(args: JsonObject): String {
        disconnect()
        val host = args["host"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'host' obrigatório."
        val user = args["user"]?.jsonPrimitive?.contentOrNull ?: "root"
        val pass = args["password"]?.jsonPrimitive?.contentOrNull ?: ""
        val port = (args["port"]?.jsonPrimitive?.contentOrNull ?: "22").toIntOrNull() ?: 22
        return try {
            val jsch = JSch()
            val s = jsch.getSession(user, host, port)
            s.setPassword(pass)
            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            config["UserKnownHostsFile"] = "/dev/null"
            s.setConfig(config)
            s.connect(15000)
            session = s
            lastHost = host; lastUser = user; lastPort = port
            "✅ Conectado via SSH a $user@$host:$port"
        } catch (e: Exception) {
            "Falha SSH: ${e.message}"
        }
    }

    private fun disconnect(): String {
        session?.disconnect()
        session = null
        return "Desconectado."
    }

    private fun status(): String {
        val s = session
        return if (s != null && s.isConnected) "SSH ativo: $lastUser@$lastHost:$lastPort"
        else "Nenhuma conexão SSH ativa. Use action='connect'."
    }

    /** Executa comando no shell remoto com validação de segurança. */
    private fun exec(args: JsonObject): String {
        val cmd = args["command"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'command' obrigatório."
        val block = com.codex.agent.shell.ShellSafetyGuard.analyze(cmd)
        if (block != null) return "BLOQUEADO POR SEGURANÇA: $block"
        return execCmd(cmd)
    }

    private fun execCmd(cmd: String): String {
        val s = session ?: return "Erro: não conectado. Use action='connect' primeiro."
        return try {
            val channel = s.openChannel("exec") as ChannelExec
            channel.setCommand(cmd)
            val out = channel.inputStream
            val err = channel.errStream
            channel.connect(15000)
            val output = out.bufferedReader(Charsets.UTF_8).readText()
            val error = err.bufferedReader(Charsets.UTF_8).readText()
            while (!channel.isEOF) Thread.sleep(20)
            channel.disconnect()
            (output + error).trim().ifBlank { "OK (sem saída)" }
        } catch (e: Exception) {
            "Erro ao executar: ${e.message}"
        }
    }

    private suspend fun pull(args: JsonObject): String {
        val remote = args["remote"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'remote' obrigatório."
        val localName = args["local_name"]?.jsonPrimitive?.contentOrNull
            ?: remote.substringAfterLast('/').ifBlank { "pulled" }
        if (!localName.matches(Regex("^[A-Za-z0-9._-]+$"))) return "Erro: nome inválido."
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/$localName")
        out.parentFile?.mkdirs()
        val data = execCmd("cat ${sq(remote)}")
        if (data.startsWith("Erro") || data.startsWith("cat:")) return "Falha no pull: $data"
        out.writeText(data)
        return "Arquivo baixado: ${out.absolutePath} (${out.length()} bytes)"
    }

    private suspend fun push(args: JsonObject): String {
        val localPath = args["local"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'local' obrigatório."
        val remote = args["remote"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'remote' obrigatório."
        val local = File(workRoot(), localPath)
        if (!local.exists()) return "Erro: arquivo local não encontrado."
        val content = local.readText().replace("'", "'\\''")
        val r = execCmd("printf '%s' '$content' > ${sq(remote)} && echo OK")
        return "Push: ${local.name} → $remote\n$r"
    }

    /** Instala o sistema IPTV do usuário na Cinebox Linux (via script que ele fornece). */
    private suspend fun installIptv(args: JsonObject): String {
        val scriptPath = args["script"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'script' obrigatório (caminho do script de instalação no workspace)."
        val script = File(workRoot(), scriptPath)
        if (!script.exists()) return "Erro: script não encontrado."
        val content = script.readText()
        val block = com.codex.agent.shell.ShellSafetyGuard.analyze(content)
        if (block != null) return "Erro: script bloqueado pelo guardrail: $block"
        val remote = "/tmp/install_iptv_${System.currentTimeMillis()}.sh"
        val r = execCmd("cat > $remote <<'EOF'\n$content\nEOF\nchmod +x $remote && sh $remote 2>&1 | head -80")
        return "=== Instalação do IPTV na Cinebox ===\n$r"
    }

    private fun sq(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT
}
