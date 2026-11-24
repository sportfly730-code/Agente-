package com.codex.agent.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.codex.agent.data.entity.ProviderConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderConfigDao {

    @Query("SELECT * FROM provider_configs ORDER BY isDefault DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs ORDER BY isDefault DESC, updatedAt DESC LIMIT 1")
    fun observeDefault(): Flow<ProviderConfigEntity?>

    @Query("SELECT * FROM provider_configs WHERE id = :id")
    suspend fun getById(id: Long): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs ORDER BY isDefault DESC, updatedAt DESC LIMIT 1")
    suspend fun getDefault(): ProviderConfigEntity?

    @Query("SELECT COUNT(*) FROM provider_configs")
    suspend fun count(): Int

    @Query("SELECT * FROM provider_configs ORDER BY isDefault DESC, updatedAt DESC")
    suspend fun getAll(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_configs WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ProviderConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ProviderConfigEntity): Long

    @Update
    suspend fun update(config: ProviderConfigEntity)

    @Delete
    suspend fun delete(config: ProviderConfigEntity)

    @Query("DELETE FROM provider_configs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE provider_configs SET isDefault = 0")
    suspend fun clearDefaults()

    @Query("UPDATE provider_configs SET isDefault = 1, updatedAt = :now WHERE id = :id")
    suspend fun markDefault(id: Long, now: Long)
}
