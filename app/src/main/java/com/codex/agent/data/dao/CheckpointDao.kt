package com.codex.agent.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.codex.agent.data.entity.CheckpointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckpointDao {

    @Insert
    suspend fun insert(checkpoint: CheckpointEntity): Long

    @Update
    suspend fun update(checkpoint: CheckpointEntity)

    @Query("SELECT * FROM checkpoints WHERE id = :id")
    suspend fun getById(id: Long): CheckpointEntity?

    @Query("SELECT * FROM checkpoints WHERE commitHash = :hash ORDER BY createdAt DESC LIMIT 1")
    suspend fun getByHash(hash: String): CheckpointEntity?

    @Query("SELECT * FROM checkpoints WHERE workspaceId = :workspaceId ORDER BY createdAt DESC")
    fun observeForWorkspace(workspaceId: Long): Flow<List<CheckpointEntity>>

    @Query("SELECT * FROM checkpoints WHERE workspaceId = :workspaceId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(workspaceId: Long, limit: Int): List<CheckpointEntity>

    @Query("SELECT * FROM checkpoints WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestForSession(sessionId: Long): CheckpointEntity?

    @Query("DELETE FROM checkpoints WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM checkpoints WHERE workspaceId = :workspaceId")
    suspend fun deleteForWorkspace(workspaceId: Long)
}
