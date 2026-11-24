package com.codex.agent.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Configuração global chave-valor consumida pelo [com.codex.agent.data.repository.SettingsRepository]
 * (modo de execução, system prompt base, limites, provider ativo, flags de provisionamento).
 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val settingKey: String,
    val value: String
)
