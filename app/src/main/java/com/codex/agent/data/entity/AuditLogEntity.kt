package com.codex.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Registro de auditoria de comandos shell executados pelo agente (OWASP: rastreabilidade).
 * Comandos marcados como perigosos/bloqueados são registrados mesmo quando não executados.
 */
@Entity(
    tableName = "audit_log",
    indices = [Index("timestamp"), Index("sessionId")]
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long = 0,
    val command: String,
    val workdir: String? = null,
    val engineUsed: String? = null,
    val exitCode: Int? = null,
    val wasBlocked: Boolean = false,
    val blockReason: String? = null,
    val durationMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)
