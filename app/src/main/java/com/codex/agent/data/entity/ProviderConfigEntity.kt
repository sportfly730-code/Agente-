package com.codex.agent.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.codex.agent.data.model.ProviderKind

/**
 * Configuração de um provedor LLM. Suporta qualquer API via endpoint customizado,
 * API key, cabeçalhos extras (JSON) e roteamento dual-model:
 * - mainModel: Coder/Architect (raciocínio complexo, patches cirúrgicos).
 * - utilityModel: rápido/econômico (compactação de contexto e sumarização de logs).
 */
@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    val baseUrl: String,
    val apiKey: String = "",
    val extraHeadersJson: String = "{}",
    val mainModel: String = "",
    val utilityModel: String = "",
    val contextWindowTokens: Int = 128000,
    val temperature: Double = 0.2,
    val maxOutputTokens: Int = 8192,
    val supportsVision: Boolean = true,
    val supportsParallelToolCalls: Boolean = true,
    val supportsReasoningStream: Boolean = false,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
