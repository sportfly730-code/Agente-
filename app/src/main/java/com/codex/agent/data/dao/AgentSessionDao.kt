package com.codex.agent.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.codex.agent.data.entity.AgentSessionEntity
import com.codex.agent.data.model.AgentRunMode
import com.codex.agent.data.model.SessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentSessionDao {

    @Insert
    suspend fun insert(session: AgentSessionEntity): Long

    @Update
    suspend fun update(session: AgentSessionEntity)

    @Query("DELETE FROM agent_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM agent_sessions WHERE workspaceId = :workspaceId")
    suspend fun deleteForWorkspace(workspaceId: Long)

    @Query("SELECT * FROM agent_sessions WHERE id = :id")
    suspend fun getById(id: Long): AgentSessionEntity?

    @Query("SELECT * FROM agent_sessions WHERE id = :id")
    fun observeById(id: Long): Flow<AgentSessionEntity?>

    @Query("SELECT * FROM agent_sessions WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    fun observeForWorkspace(workspaceId: Long): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    suspend fun getForWorkspace(workspaceId: Long): List<AgentSessionEntity>

    @Query(
        "SELECT * FROM agent_sessions WHERE workspaceId = :workspaceId AND status = :status " +
            "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun getLatestWithStatus(workspaceId: Long, status: SessionStatus): AgentSessionEntity?

    @Query("UPDATE agent_sessions SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: SessionStatus, now: Long)

    @Query("UPDATE agent_sessions SET runMode = :runMode, updatedAt = :now WHERE id = :id")
    suspend fun updateRunMode(id: Long, runMode: AgentRunMode, now: Long)

    @Query("UPDATE agent_sessions SET providerId = :providerId, updatedAt = :now WHERE id = :id")
    suspend fun updateProvider(id: Long, providerId: Long?, now: Long)

    @Query("UPDATE agent_sessions SET maxSteps = :maxSteps WHERE id = :id")
    suspend fun updateMaxSteps(id: Long, maxSteps: Int)

    @Query("UPDATE agent_sessions SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE agent_sessions SET planText = :plan, status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updatePlan(id: Long, plan: String?, status: SessionStatus, now: Long)

    @Query(
        "UPDATE agent_sessions SET compactionSummary = :summary, lastCompactedMessageId = :lastCompactedMessageId, " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun updateCompaction(id: Long, summary: String, lastCompactedMessageId: Long, now: Long)

    @Query("UPDATE agent_sessions SET totalTokensUsed = totalTokensUsed + :delta, updatedAt = :now WHERE id = :id")
    suspend fun addTokensUsed(id: Long, delta: Long, now: Long)
}
