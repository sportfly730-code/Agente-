package com.codex.agent.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.codex.agent.data.dao.AgentSessionDao
import com.codex.agent.data.dao.AuditLogDao
import com.codex.agent.data.dao.BackgroundJobDao
import com.codex.agent.data.dao.ChatMessageDao
import com.codex.agent.data.dao.CheckpointDao
import com.codex.agent.data.dao.ProviderConfigDao
import com.codex.agent.data.dao.SettingsDao
import com.codex.agent.data.dao.WorkspaceDao
import com.codex.agent.data.entity.AgentSessionEntity
import com.codex.agent.data.entity.AuditLogEntity
import com.codex.agent.data.entity.BackgroundJobEntity
import com.codex.agent.data.entity.ChatMessageEntity
import com.codex.agent.data.entity.CheckpointEntity
import com.codex.agent.data.entity.ProviderConfigEntity
import com.codex.agent.data.entity.SettingEntity
import com.codex.agent.data.entity.WorkspaceEntity

@Database(
    entities = [
        ProviderConfigEntity::class,
        WorkspaceEntity::class,
        AgentSessionEntity::class,
        ChatMessageEntity::class,
        CheckpointEntity::class,
        BackgroundJobEntity::class,
        SettingEntity::class,
        AuditLogEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun providerConfigDao(): ProviderConfigDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun agentSessionDao(): AgentSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun backgroundJobDao(): BackgroundJobDao
    abstract fun settingsDao(): SettingsDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "codex_agent.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
