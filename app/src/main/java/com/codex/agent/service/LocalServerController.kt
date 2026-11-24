package com.codex.agent.service

/** Ponte global para o servidor HTTP local, permitindo que tools controlem o ciclo de vida. */
object LocalServerController {
    @Volatile private var server: LocalAgentServer? = null

    fun attach(server: LocalAgentServer) {
        this.server = server
    }

    fun get(): LocalAgentServer? = server

    fun start(port: Int = LocalAgentServer.DEFAULT_PORT, bindAll: Boolean = false, token: String = ""): String =
        server?.start(port, bindAll, token) ?: "Servidor não inicializado (app ainda carregando)."

    fun stop(): String = server?.stop() ?: "Servidor não inicializado."

    fun status(): String {
        val s = server ?: return "Servidor não inicializado."
        return "HTTP ${if (s.isRunning) "ATIVO" else "parado"} na porta ${s.port}."
    }
}
