package com.codex.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.codex.agent.data.model.CheckpointKind

/**
 * Checkpoint Git (shadow commit) criado antes de cada solicitação do usuário ou
 * sob demanda pela IA (manage_checkpoints). Permite rollback com 1 toque.
 */
@Entity(
    tableName = "checkpoints",
    indices = [Index("workspaceId"), Index("sessionId")]
)
data class CheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val workspaceId: Long,
    val commitHash: String,
    val message: String,
    val kind: CheckpointKind = CheckpointKind.AUTO_SHADOW,
    val createdAt: Long = System.currentTimeMillis()
)
