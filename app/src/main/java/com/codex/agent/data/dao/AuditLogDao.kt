package com.codex.agent.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.codex.agent.data.entity.AuditLogEntity

@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(entry: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AuditLogEntity>

    @Query("SELECT * FROM audit_log WHERE wasBlocked = 1 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getBlocked(limit: Int): List<AuditLogEntity>

    @Query("SELECT COUNT(*) FROM audit_log WHERE wasBlocked = 1")
    suspend fun countBlocked(): Int

    @Query("DELETE FROM audit_log")
    suspend fun clearAll()
}
