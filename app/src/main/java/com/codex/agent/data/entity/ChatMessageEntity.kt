package com.codex.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.codex.agent.data.model.MessageRole

/**
 * Mensagem persistida da sessão. Suporta:
 * - ASSISTANT com reasoning (raciocínio visível na UI) e imageBase64 (multimodal/visão);
 * - TOOL com toolName/toolArgsJson/toolCallId/toolResultOk (resultado de ferramenta);
 * - SYSTEM_INJECTION para mensagens do orquestrador (anti-loop, compaction, guidance).
 */
@Entity(
    tableName = "chat_messages",
    indices = [Index("sessionId"), Index("toolCallId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: MessageRole,
    val content: String = "",
    val reasoning: String? = null,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val toolCallId: String? = null,
    val toolResultOk: Boolean? = null,
    val imageBase64: String? = null,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
