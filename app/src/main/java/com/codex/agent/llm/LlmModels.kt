package com.codex.agent.llm

import kotlinx.serialization.Serializable
import kotlin.math.ceil

/**
 * DTOs compartilhados pelo LLMClient e pelo AgentOrchestrator (Parte 3).
 * Independentes de protocolo: o cliente converte para OpenAI-compatible ou Anthropic nativo.
 */

/** Tool call solicitada pelo modelo. */
@Serializable
data class ToolCallRequest(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/** Mensagem do histórico. role: "system" | "user" | "assistant" | "tool". */
data class LlmMessage(
    val role: String,
    val content: String,
    val reasoning: String? = null,
    /** Base64 puro (sem prefixo data:) para análise multimodal de imagens. */
    val imageBase64: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null
)

/** Resposta consolidada (bloqueante ou resultado final do streaming). */
data class LlmResponse(
    val content: String,
    val reasoning: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/** Callbacks de streaming (SSE). Chamados na thread de IO do cliente. */
class LlmStreamCallbacks(
    val onContent: (String) -> Unit = {},
    val onReasoning: (String) -> Unit = {},
    val onStatus: (String) -> Unit = {}
)

/** Estimativa de tokens sem tokenizer (chars/4) — usada quando a API não retorna usage. */
object TokenEstimator {
    const val CHARS_PER_TOKEN = 4.0

    fun estimate(text: String): Int =
        ceil(text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(0)
}
