package com.codex.agent.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.codex.agent.data.entity.WorkspaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {

    @Query("SELECT * FROM workspaces ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<WorkspaceEntity?>

    @Query("SELECT * FROM workspaces WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: Long): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE rootPath = :rootPath LIMIT 1")
    suspend fun getByRootPath(rootPath: String): WorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(workspace: WorkspaceEntity): Long

    @Update
    suspend fun update(workspace: WorkspaceEntity)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE workspaces SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE workspaces SET isActive = 1 WHERE id = :id")
    suspend fun markActive(id: Long)

    @Query("UPDATE workspaces SET lastUsedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)
}
