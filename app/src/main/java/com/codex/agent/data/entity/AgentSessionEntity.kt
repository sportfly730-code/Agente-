package com.codex.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.codex.agent.data.model.AgentRunMode
import com.codex.agent.data.model.Constants
import com.codex.agent.data.model.SessionStatus

/**
 * Sessão de conversa/engenharia com o agente dentro de um workspace.
 * compactionSummary/lastCompactedMessageId guardam o resultado da auto-compactação.
 * planText guarda o plano técnico quando o modo é PLAN_FIRST.
 */
@Entity(
    tableName = "agent_sessions",
    indices = [Index("workspaceId"), Index("providerId")]
)
data class AgentSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: Long,
    val providerId: Long? = null,
    val title: String = "Nova sessão",
    val runMode: AgentRunMode = AgentRunMode.YOLO,
    val status: SessionStatus = SessionStatus.IDLE,
    val systemPrompt: String? = null,
    val maxSteps: Int = Constants.DEFAULT_MAX_STEPS,
    val contextWindowTokens: Int? = null,
    val compactionSummary: String? = null,
    val lastCompactedMessageId: Long = 0,
    val planText: String? = null,
    val totalTokensUsed: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
