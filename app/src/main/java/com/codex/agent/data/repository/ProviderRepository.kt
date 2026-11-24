package com.codex.agent.data.repository

import androidx.room.withTransaction
import com.codex.agent.data.db.AppDatabase
import com.codex.agent.data.entity.ProviderConfigEntity
import com.codex.agent.data.model.ProviderKind
import com.codex.agent.security.SecretsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * CRUD de provedores LLM + seeds padrão. As API keys vivem em [SecretsStore]
 * (EncryptedSharedPreferences / Android Keystore), nunca em texto puro no Room.
 * O Room mantém apenas metadados (baseUrl, modelos, etc.); o campo apiKey da entidade
 * é preenchido em memória a partir do SecretsStore nas leituras.
 */
class ProviderRepository(
    private val db: AppDatabase,
    private val secretsStore: SecretsStore
) {

    private val dao get() = db.providerConfigDao()

    fun observeAll(): Flow<List<ProviderConfigEntity>> =
        dao.observeAll().map { list -> list.map { hydrate(it) } }

    fun observeDefault(): Flow<ProviderConfigEntity?> =
        dao.observeDefault().map { it?.let { hydrate(it) } }

    suspend fun byId(id: Long): ProviderConfigEntity? =
        dao.getById(id)?.let { hydrate(it) }

    suspend fun default(): ProviderConfigEntity? =
        dao.getDefault()?.let { hydrate(it) }

    suspend fun getAll(): List<ProviderConfigEntity> =
        dao.getAll().map { hydrate(it) }

    /** Cadeia de fallback: preferido primeiro, depois provedores com key configurada. */
    suspend fun fallbackChain(preferredId: Long?): List<ProviderConfigEntity> {
        val all = getAll()
        val preferred = resolvePreferred(preferredId)
        // Filtra endpoints locais (Ollama/LM Studio) só se estiverem realmente rodando —
        // caso contrário o fallback tenta 127.0.0.1:11434, falha e a mensagem final
        // mostra o erro errado (do último provider) em vez do erro do provedor principal.
        val usable = all.filter { p ->
            when {
                p.apiKey.isNotBlank() -> true
                isLocalEndpoint(p.baseUrl) -> isLocalServerUp(p.baseUrl)
                else -> false
            }
        }
        val result = mutableListOf<ProviderConfigEntity>()
        if (preferred != null) result.add(preferred)
        usable.forEach { p -> if (result.none { it.id == p.id }) result.add(p) }
        // Vyce AI tem key fallback ("vyce_free_access"), inclui mesmo sem key salva
        all.filter { it.baseUrl.contains("vyceai.com") && result.none { r -> r.id == it.id } }
            .forEach { result.add(it) }
        return result
    }

    suspend fun count(): Int = dao.count()

    suspend fun resolvePreferred(preferredId: Long?): ProviderConfigEntity? {
        preferredId?.let { preferred ->
            val found = byId(preferred)
            if (found != null) return found
        }
        default()?.let { default ->
            if (default.apiKey.isNotBlank() || isLocalEndpoint(default.baseUrl)) return default
        }
        getAll().firstOrNull { it.apiKey.isNotBlank() || isLocalEndpoint(it.baseUrl) }?.let { return it }
        return getAll().firstOrNull() ?: default()
    }

    suspend fun upsert(config: ProviderConfigEntity): Long {
        val id = if (config.id == 0L) {
            // Persiste sem a key (será guardada no SecretsStore)
            val newId = dao.insert(config.copy(apiKey = "", updatedAt = System.currentTimeMillis()))
            secretsStore.putSecret(secretKey(newId), config.apiKey)
            newId
        } else {
            dao.update(config.copy(apiKey = "", updatedAt = System.currentTimeMillis()))
            secretsStore.putSecret(secretKey(config.id), config.apiKey)
            config.id
        }
        if (config.isDefault) setDefault(id)
        return id
    }

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
        secretsStore.removeSecret(secretKey(id))
    }

    suspend fun setDefault(id: Long) {
        db.withTransaction {
            dao.clearDefaults()
            dao.markDefault(id, System.currentTimeMillis())
        }
    }

    suspend fun ensureDefaultTemplates() {
        val existingNames = dao.getAll().map { it.name }.toSet()
        val now = System.currentTimeMillis()
        val templates = listOf(
            // (modelo local removido)
            ProviderConfigEntity(name = "OpenAI", kind = ProviderKind.OPENAI_COMPATIBLE,
                baseUrl = "https://api.openai.com/v1", mainModel = "gpt-4.1",
                utilityModel = "gpt-4.1-mini", contextWindowTokens = 1047576, isDefault = false,
                createdAt = now, updatedAt = now),
            ProviderConfigEntity(name = "Anthropic (nativo)", kind = ProviderKind.ANTHROPIC_NATIVE,
                baseUrl = "https://api.anthropic.com/v1", mainModel = "claude-sonnet-4-5",
                utilityModel = "claude-haiku-4-5", contextWindowTokens = 200000,
                supportsReasoningStream = true, createdAt = now, updatedAt = now),
            ProviderConfigEntity(name = "Google Gemini (OpenAI-compatible)", kind = ProviderKind.OPENAI_COMPATIBLE,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
                mainModel = "gemini-3.1-pro-preview", utilityModel = "gemini-2.5-flash",
                contextWindowTokens = 1000000, createdAt = now, updatedAt = now),
            ProviderConfigEntity(name = "DeepSeek", kind = ProviderKind.OPENAI_COMPATIBLE,
                baseUrl = "https://api.deepseek.com/v1", mainModel = "deepseek-chat",
                utilityModel = "deepseek-chat", contextWindowTokens = 128000,
                createdAt = now, updatedAt = now),
            ProviderConfigEntity(name = "OpenRouter", kind = ProviderKind.OPENAI_COMPATIBLE,
                baseUrl = "https://openrouter.ai/api/v1", mainModel = "openai/gpt-4o",
                utilityModel = "openai/gpt-4o-mini", contextWindowTokens = 128000,
                createdAt = now, updatedAt = now),
            ProviderConfigEntity(name = "Groq", kind = ProviderKind.OPENAI_COMPATIBLE,
                baseUrl = "https://api.groq.com/openai/v1", mainModel = "llama-3.3-70b-versatile",
                utilityModel = "llama-3.1-8b-instant", contextWindowTokens = 131072,
                createdAt = now, updatedAt = now),
            ProviderConfigEntity(name = "Vyce AI", kind = ProviderKind.OPENAI_COMPATIBLE,
                baseUrl = "https://vyceai.com/v1", mainModel = "gpt-4o",
                utilityModel = "gpt-4o-mini", contextWindowTokens = 128000,
                createdAt = now, updatedAt = now)
        )
        var firstId = 0L
        for (template in templates) {
            // Idempotente: só insere templates que ainda não existem. Isso garante que
            // provedores novos (ex.: Vyce AI) cheguem também a instalações existentes.
            if (existingNames.contains(template.name)) continue
            // Nunca preserva isDefault do template: isso impediria re-seed de sequestrar
            // o provedor padrão escolhido pelo usuário (ex.: após ele deletar o OpenAI).
            val id = dao.insert(template.copy(apiKey = "", updatedAt = now, isDefault = false))
            if (template.apiKey.isNotBlank()) {
                secretsStore.putSecret(secretKey(id), template.apiKey)
            }
            if (firstId == 0L) firstId = id
        }
        // Só redefine o default se não havia nenhum provedor antes (primeira instalação).
        if (existingNames.isEmpty() && firstId != 0L) {
            dao.clearDefaults()
            dao.markDefault(firstId, now)
        }
    }

    /** Hidrata a entidade com a API key do SecretsStore (em memória). */
    private fun hydrate(entity: ProviderConfigEntity): ProviderConfigEntity =
        entity.copy(apiKey = secretsStore.getSecret(secretKey(entity.id)).orEmpty())

    private fun secretKey(id: Long): String = "${SecretsStore.PREFIX_PROVIDER_KEY}$id"

    private fun isLocalEndpoint(baseUrl: String): Boolean {
        val lower = baseUrl.lowercase()
        return lower.contains("localhost") || lower.contains("127.0.0.1") ||
            lower.contains("10.0.2.2") || lower.contains("192.168.")
    }

    /** Verifica rapidamente se um servidor local (Ollama/LM Studio) está aceitando conexões. */
    private fun isLocalServerUp(baseUrl: String): Boolean {
        return runCatching {
            val url = java.net.URL(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 300
            conn.readTimeout = 300
            conn.requestMethod = "GET"
            try { conn.connect(); true } finally { conn.disconnect() }
        }.getOrDefault(false)
    }
}
