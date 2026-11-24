package com.codex.agent.service

import android.util.Log
import com.codex.agent.agent.AgentOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Servidor HTTP LOCAL (na sua rede) para controlar o agente a partir do SEU computador
 * ou outro dispositivo seu. Usa ServerSocket puro — sem dependências extras.
 *
 * Endpoints:
 *  - GET  /health    → {"status":"ok"}
 *  - GET  /status    → status do agente (JSON)
 *  - GET  /messages  → últimas mensagens da sessão ativa (JSON)
 *  - POST /prompt    → corpo: texto da ordem → envia ao agente
 *
 * SEGURANÇA: escuta apenas em 127.0.0.1 por padrão (localhost). Para acessar
 * de outro dispositivo da sua rede, ative explicitamente `bindAll` e proteja com token.
 * Nunca exponha a internet sem autenticação.
 */
class LocalAgentServer(
    private val orchestrator: AgentOrchestrator,
    private val getMessagesJson: suspend () -> String
) {
    companion object {
        private const val TAG = "LocalAgentServer"
        const val DEFAULT_PORT = 8787
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var isRunning: Boolean = false
        private set
    @Volatile private var bindAll: Boolean = false
    @Volatile private var authToken: String = ""
    @Volatile var port: Int = DEFAULT_PORT
        private set

    /** Inicia o servidor. `bindAll=true` escuta em 0.0.0.0 (sua rede local). */
    fun start(port: Int = DEFAULT_PORT, bindAll: Boolean = false, token: String = ""): String {
        if (isRunning) return "Servidor já está rodando na porta $port."
        this.port = port
        this.bindAll = bindAll
        this.authToken = token
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(
                if (bindAll) InetSocketAddress("0.0.0.0", port)
                else InetSocketAddress("127.0.0.1", port),
                50
            )
            serverSocket = ss
            isRunning = true
            pool.execute {
                while (isRunning) {
                    try {
                        val client = ss.accept()
                        pool.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            }
            val host = if (bindAll) "0.0.0.0 (rede local)" else "127.0.0.1"
            "Agente HTTP ativo em http://$host:$port\n" +
                (if (token.isNotBlank()) "Autenticação: header X-Auth-Token\n" else "Sem token (apenas localhost seguro).\n") +
                "Endpoints: /health /status /messages /prompt"
        } catch (e: Exception) {
            "Falha ao iniciar servidor: ${e.message}"
        }
    }

    fun stop(): String {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        return "Servidor HTTP encerrado."
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?")
            val query = rawPath.substringAfter("?", "")

            var contentLength = 0
            var providedToken = ""
            var contentType = ""
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val h = line ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                if (h.startsWith("X-Auth-Token:", ignoreCase = true)) {
                    providedToken = h.substringAfter(":").trim()
                }
                if (h.startsWith("Content-Type:", ignoreCase = true)) {
                    contentType = h.substringAfter(":").trim()
                }
            }

            val body = if (contentLength > 0) {
                // CORREÇÃO: lê exatamente contentLength BYTES (não chars) e decodifica UTF-8.
                // Antes tratava bytes como chars → truncava texto acentuado.
                val buf = ByteArray(contentLength)
                var read = 0
                val rawIn = socket.getInputStream()
                while (read < contentLength) {
                    val r = rawIn.read(buf, read, contentLength - read)
                    if (r <= 0) break
                    read += r
                }
                String(buf, 0, read, StandardCharsets.UTF_8)
            } else ""

            // Autenticação obrigatória setoken configurado (e especialmente se bindAll)
            if (authToken.isNotBlank() && providedToken != authToken) {
                respond(socket, 401, "{\"error\":\"unauthorized\"}")
                return
            }

            when {
                path == "/health" || path == "/" -> respond(socket, 200, "{\"status\":\"ok\",\"agent\":\"CodexAgent\"}")
                path == "/status" -> {
                    val s = orchestrator.uiState.value
                    respond(socket, 200,
                        "{\"running\":${s.isRunning},\"status\":\"${escape(s.statusText)}\"," +
                            "\"step\":${s.currentStep},\"maxSteps\":${s.maxSteps}," +
                            "\"tokens\":${s.totalTokensUsed},\"workspace\":\"${escape(s.workspacePath)}\"}")
                }
                path == "/messages" -> {
                    val json = kotlinx.coroutines.runBlocking { getMessagesJson() }
                    respond(socket, 200, json)
                }
                path == "/prompt" && method == "POST" -> {
                    // Decodifica apenas se veio como form-urlencoded; caso contrário usa o texto cru
                    // (evita corromper '+' e estourar com '%' solto em prompts em português).
                    val prompt = runCatching {
                        if (contentType.contains("x-www-form-urlencoded", ignoreCase = true)) {
                            URLDecoder.decode(body.trim(), StandardCharsets.UTF_8.name())
                        } else body.trim()
                    }.getOrDefault(body.trim())
                    if (prompt.isBlank()) {
                        respond(socket, 400, "{\"error\":\"empty prompt\"}")
                    } else {
                        orchestrator.submitUserPrompt(prompt)
                        respond(socket, 200, "{\"ok\":true,\"prompt\":\"${escape(prompt.take(120))}\"}")
                    }
                }
                else -> respond(socket, 404, "{\"error\":\"not found\"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleClient: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun respond(socket: Socket, code: Int, body: String) {
        try {
            val out: OutputStream = socket.getOutputStream()
            val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; else -> "Not Found" }
            val header = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                // Sem CORS curinga: evita que páginas web arbitrárias leiam /messages
                // ou POSTem em /prompt neste endpoint de controle localhost.
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.write(body.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        } catch (_: Exception) {}
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "")
}
