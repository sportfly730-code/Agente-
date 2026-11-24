package com.codex.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.codex.agent.data.model.JobStatus

/**
 * Processo em background (is_background = true) rastreado pelo Terminal Live:
 * stream bruto de stdout/stderr, PID, caminho do log integral e botão Kill.
 */
@Entity(
    tableName = "background_jobs",
    indices = [Index("status"), Index("sessionId")]
)
data class BackgroundJobEntity(
    @PrimaryKey val jobId: String,
    val sessionId: Long? = null,
    val command: String,
    val workdir: String,
    val pid: String? = null,
    val logPath: String,
    val status: JobStatus = JobStatus.RUNNING,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)
