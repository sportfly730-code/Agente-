package com.codex.agent.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.codex.agent.data.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Insert
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    @Update
    suspend fun update(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeForSession(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getForSession(sessionId: Long): List<ChatMessageEntity>

    /** Busca global em todas as sessões (memória de longo prazo). */
    @Query("SELECT * FROM chat_messages WHERE role IN ('USER','ASSISTANT') AND LENGTH(content) > 20 AND sessionId != :excludeSessionId ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentAcrossSessions(limit: Int, excludeSessionId: Long): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE role IN ('USER','ASSISTANT') AND content LIKE '%' || :term || '%' ORDER BY id DESC LIMIT :limit")
    suspend fun searchAcrossSessions(term: String, limit: Int): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId AND id >= :fromId ORDER BY id ASC")
    suspend fun getFromId(sessionId: Long, fromId: Long): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY id DESC LIMIT :limit")
    suspend fun getLatest(sessionId: Long, limit: Int): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE toolCallId = :toolCallId LIMIT 1")
    suspend fun getByToolCallId(toolCallId: String): ChatMessageEntity?

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    @Query("SELECT COALESCE(SUM(LENGTH(content)), 0) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun totalContentChars(sessionId: Long): Long

    /** Remove mensagens antigas (id <= upToId) durante a auto-compactação. */
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND id <= :upToId")
    suspend fun deleteUpTo(sessionId: Long, upToId: Long)

    /** Trunca o histórico depois de um rollback (id > afterId). */
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND id > :afterId")
    suspend fun deleteAfter(sessionId: Long, afterId: Long)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}
