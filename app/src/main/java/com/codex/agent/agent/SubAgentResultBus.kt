package com.codex.agent.agent

/**
 * Barramento global de resultados de sub-agentes.
 * O orquestrador registra um callback que injeta o resultado no histórico da sessão
 * quando o sub-agente termina — permitindo delegação em background (sem bloquear o turno).
 */
object SubAgentResultBus {
    @Volatile var onResult: ((task: String, result: String) -> Unit)? = null

    fun emit(task: String, result: String) {
        onResult?.invoke(task, result)
    }
}
