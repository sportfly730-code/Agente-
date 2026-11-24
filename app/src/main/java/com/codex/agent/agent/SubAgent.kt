package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.ProviderRepository
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.llm.LlmClient
import com.codex.agent.llm.LlmMessage
import com.codex.agent.llm.ToolCallRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sub-agente: delega uma tarefa para uma sessão isolada (contexto separado da conversa principal)
 * usando o modelo configurado. Possui acesso somente a ferramentas de leitura/análise e executa
 * um loop limitado, devolvendo um resumo结构化 para o agente principal.
 *
 * Útil para: "analise este APK", "mapeie a arquitetura", "encontre onde X é implementado" —
 * o agente principal continua livre enquanto o sub-agente investiga em paralelo.
 */
class SubAgent(
    private val llmClient: LlmClient,
    private val toolExecutor: ToolExecutor,
    private val providerRepository: ProviderRepository,
    private val workspaceRepository: WorkspaceRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Delegação EM BACKGROUND: inicia a investigação e retorna imediatamente.
     * O resultado é entregue via [SubAgentResultBus] quando concluído, permitindo que
     * o agente principal continue trabalhando sem bloquear o turno atual.
     */
    fun delegateAsync(task: String, maxSteps: Int = 6): String {
        scope.launch {
            val result = delegate(task, maxSteps)
            SubAgentResultBus.emit(task, result)
        }
        return "🚀 Sub-agente iniciado em BACKGROUND para: '${task.take(120)}'. " +
            "O relatório será injetado automaticamente na conversa quando concluir. " +
            "Você pode continuar com outras tarefas."
    }

    suspend fun delegate(task: String, maxSteps: Int = 6): String = withContext(Dispatchers.IO) {
        val provider = providerRepository.resolvePreferred(null)
            ?: return@withContext "Erro: nenhum provedor de IA configurado para o sub-agente."
        val workspaceRoot = workspaceRepository.active()?.rootPath
            ?: Constants.DEFAULT_WORKSPACE_ROOT

        val systemPrompt = """
            Você é um SUB-AGENTE de análise do Codex Agent, executando em contexto isolado.
            Sua missão: investigar a tarefa abaixo e devolver um RELATÓRIO CONCISO e técnico.

            REGRAS:
            - Use apenas ferramentas de LEITURA/ANÁLISE: file_manager(action='read'|'list'),
              search_code, search_semantic, apk_analyzer_tool (ações de inspeção).
            - NÃO modifique arquivos, NÃO instale pacotes, NÃO execute comandos destrutivos.
            - Seja eficiente: no máximo $maxSteps passos. Vá direto ao ponto.
            - Ao concluir, responda em Markdown com: descobertas, caminhos de arquivos relevantes,
              e recomendações acionáveis. Não descreva os passos intermediários.
        """.trimIndent()

        val messages = mutableListOf(
            LlmMessage(role = "system", content = systemPrompt),
            LlmMessage(role = "user", content = task)
        )
        val tools = ToolDefinitions.getSubAgentToolsSchema(workspaceRoot)

        for (step in 1..maxSteps) {
            val response = llmClient.chatCompletion(
                provider = provider,
                messages = messages.toList(),
                toolsSchema = tools,
                temperature = 0.2
            ).getOrElse {
                return@withContext "Sub-agente falhou: ${it.message}"
            }

            messages.add(LlmMessage(
                role = "assistant",
                content = response.content,
                toolCalls = response.toolCalls
            ))

            if (response.toolCalls.isEmpty()) {
                return@withContext response.content.ifBlank { "Sub-agente concluiu sem descobertas." }
            }

            for (call in response.toolCalls) {
                val result = toolExecutor.executeTool(call, 0L)
                messages.add(LlmMessage(
                    role = "tool",
                    content = result.take(8000),
                    toolCallId = call.id,
                    toolName = call.name
                ))
            }
        }

        val last = messages.lastOrNull { it.role == "assistant" }?.content.orEmpty()
        "Sub-agente atingiu o limite de $maxSteps passos. Resumo parcial:\n${last.take(3000)}"
    }
}
