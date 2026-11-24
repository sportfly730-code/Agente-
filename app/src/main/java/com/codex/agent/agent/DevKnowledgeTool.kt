package com.codex.agent.agent

import com.codex.agent.data.repository.WorkspaceRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Ferramenta de conhecimento técnico premium (Android/IPTV) — instala docs e reinicializa o RAG. */
class DevKnowledgeTool(
    private val workspaceRepository: WorkspaceRepository
) {
    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' obrigatório."
        return when (action) {
            "install" -> {
                val root = workspaceRepository.active()?.rootPath
                    ?: return "Erro: nenhum workspace ativo."
                val files = PremiumKnowledgeBase.installDocs(root)
                "✅ Base de conhecimento técnica instalada (${files.size} documentos) em .codex/knowledge:\n" +
                    files.joinToString("\n") { "  • ${it.substringAfterLast('/')}" } +
                    "\n\nUse search_semantic(query) para consultar. O índice RAG será atualizado."
            }
            "topics" -> {
                "Tópicos disponíveis:\n" + PremiumKnowledgeBase.TECH_DOCS.keys.sorted()
                    .joinToString("\n") { "  • $it" }
            }
            else -> "Ação desconhecida em dev_knowledge: $action"
        }
    }
}
