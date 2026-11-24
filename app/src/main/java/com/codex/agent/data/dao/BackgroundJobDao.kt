package com.codex.agent.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.codex.agent.data.entity.BackgroundJobEntity
import com.codex.agent.data.model.JobStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BackgroundJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: BackgroundJobEntity)

    @Query("UPDATE background_jobs SET pid = :pid WHERE jobId = :jobId")
    suspend fun updatePid(jobId: String, pid: String?)

    @Query("UPDATE background_jobs SET status = :status, finishedAt = :finishedAt WHERE jobId = :jobId")
    suspend fun finish(jobId: String, status: JobStatus, finishedAt: Long)

    @Query("UPDATE background_jobs SET logPath = :logPath WHERE jobId = :jobId")
    suspend fun updateLogPath(jobId: String, logPath: String)

    @Query("SELECT * FROM background_jobs WHERE jobId = :jobId")
    suspend fun getByJobId(jobId: String): BackgroundJobEntity?

    @Query("SELECT * FROM background_jobs WHERE status = 'RUNNING' ORDER BY startedAt DESC")
    fun observeRunning(): Flow<List<BackgroundJobEntity>>

    @Query("SELECT * FROM background_jobs WHERE status = 'RUNNING'")
    suspend fun getRunning(): List<BackgroundJobEntity>

    @Query("SELECT * FROM background_jobs ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<BackgroundJobEntity>

    @Query("DELETE FROM background_jobs WHERE jobId = :jobId")
    suspend fun delete(jobId: String)

    @Query("DELETE FROM background_jobs WHERE status != 'RUNNING'")
    suspend fun purgeFinished()
}
