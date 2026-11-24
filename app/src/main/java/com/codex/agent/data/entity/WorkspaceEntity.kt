package com.codex.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Workspace = raiz de um projeto onde o agente opera (ex.: /sdcard/CodexProjects/MeuApp).
 * Apenas um workspace ativo por vez; todos os comandos/arquivos relativos resolvem a ele.
 */
@Entity(tableName = "workspaces", indices = [Index(value = ["rootPath"], unique = true)])
data class WorkspaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val rootPath: String,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)
