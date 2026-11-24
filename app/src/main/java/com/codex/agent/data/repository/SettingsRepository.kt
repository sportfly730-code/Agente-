package com.codex.agent.data.repository

import com.codex.agent.data.dao.SettingsDao
import com.codex.agent.data.entity.SettingEntity
import com.codex.agent.data.model.Constants
import com.codex.agent.data.model.ExecutionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Chaves da tabela settings.
 */
object SettingsKeys {
    const val APP_THEME = "app_theme"
    const val KEEP_ALIVE_ENABLED = "keep_alive_enabled"
    const val EXECUTION_MODE = "execution_mode"
    const val BASE_SYSTEM_PROMPT = "base_system_prompt"
    const val DEFAULT_MAX_STEPS = "default_max_steps"
    const val DEFAULT_WORKSPACE_ROOT = "default_workspace_root"
    const val OUTPUT_TRUNCATION_LIMIT = "output_truncation_limit"
    const val AUTO_CHECKPOINT_ENABLED = "auto_checkpoint_enabled"
    const val COMPACTION_ENABLED = "context_compaction_enabled"
    const val ACTIVE_PROVIDER_ID = "active_provider_id"
    const val TOOLCHAIN_PROVISIONED = "toolchain_provisioned"
    const val ANDROID_SDK_PROVISIONED = "android_sdk_provisioned"
    const val ENV_BOOTSTRAP_DONE = "env_bootstrap_done"
    const val FIRST_SETUP_DONE = "first_setup_done"
    const val PREFERRED_SHELL = "preferred_shell"
    const val BIOMETRIC_LOCK_ENABLED = "biometric_lock_enabled"
    /** Usuário declara que APKs, sistemas e sites no escopo são dele — evita recusas falsas. */
    const val TRUST_WORKSPACE_APK = "trust_workspace_apk"
    const val TRUST_OWNED_SCOPE = "trust_owned_scope"
    const val MAX_STEPS_KEY = "max_steps"
}

/**
 * Snapshot reativo das configurações globais para a UI.
 */
data class AppSettingsSnapshot(
    val appTheme: String = "DEFAULT",
    val keepAliveEnabled: Boolean = false,
    val executionMode: ExecutionMode = ExecutionMode.HYBRID,
    val baseSystemPrompt: String = "",
    val defaultMaxSteps: Int = Constants.DEFAULT_MAX_STEPS,
    val defaultWorkspaceRoot: String = Constants.DEFAULT_WORKSPACE_ROOT,
    val outputTruncationLimit: Int = Constants.OUTPUT_TRUNCATION_LIMIT,
    val autoCheckpointEnabled: Boolean = true,
    val compactionEnabled: Boolean = true,
    val activeProviderId: Long? = null,
    val toolchainProvisioned: Boolean = false,
    val envBootstrapDone: Boolean = false,
    val firstSetupDone: Boolean = false,
    val preferredShell: String = "/bin/sh"
)

/**
 * Repositório tipado sobre a tabela chave-valor Room.
 * Todas as camadas (LLM, shell, orquestrador, UI) leem configuração por aqui.
 */
class SettingsRepository(private val settingsDao: SettingsDao) {

    // ---------------- Primitive KV API ----------------

    suspend fun getString(key: String): String? = settingsDao.get(key)?.value

    suspend fun putString(key: String, value: String) {
        settingsDao.put(SettingEntity(key, value))
    }

    suspend fun remove(key: String) = settingsDao.delete(key)

    suspend fun getInt(key: String, def: Int): Int =
        settingsDao.get(key)?.value?.trim()?.toIntOrNull() ?: def

    suspend fun putInt(key: String, value: Int) = putString(key, value.toString())

    suspend fun getLong(key: String, def: Long): Long =
        settingsDao.get(key)?.value?.trim()?.toLongOrNull() ?: def

    suspend fun putLong(key: String, value: Long) = putString(key, value.toString())

    suspend fun getBoolean(key: String, def: Boolean): Boolean =
        settingsDao.get(key)?.value?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: def

    suspend fun putBoolean(key: String, value: Boolean) =
        putString(key, if (value) "true" else "false")

    // ---------------- Typed accessors ----------------

    suspend fun executionMode(): ExecutionMode =
        when (getString(SettingsKeys.EXECUTION_MODE)?.uppercase()) {
            ExecutionMode.TERMUX_ONLY.name -> ExecutionMode.TERMUX_ONLY
            ExecutionMode.INTERNAL_ONLY.name -> ExecutionMode.INTERNAL_ONLY
            else -> ExecutionMode.HYBRID
        }

    suspend fun setExecutionMode(mode: ExecutionMode) =
        putString(SettingsKeys.EXECUTION_MODE, mode.name)

    suspend fun baseSystemPrompt(): String =
        getString(SettingsKeys.BASE_SYSTEM_PROMPT).orEmpty()

    suspend fun setBaseSystemPrompt(text: String) =
        putString(SettingsKeys.BASE_SYSTEM_PROMPT, text)

    suspend fun defaultMaxSteps(): Int =
        getInt(SettingsKeys.DEFAULT_MAX_STEPS, Constants.DEFAULT_MAX_STEPS)

    suspend fun setDefaultMaxSteps(steps: Int) =
        putInt(SettingsKeys.DEFAULT_MAX_STEPS, steps.coerceIn(1, 200))

    suspend fun defaultWorkspaceRoot(): String =
        getString(SettingsKeys.DEFAULT_WORKSPACE_ROOT) ?: Constants.DEFAULT_WORKSPACE_ROOT

    suspend fun setDefaultWorkspaceRoot(path: String) =
        putString(SettingsKeys.DEFAULT_WORKSPACE_ROOT, path.trim().trimEnd('/'))

    suspend fun outputTruncationLimit(): Int =
        getInt(SettingsKeys.OUTPUT_TRUNCATION_LIMIT, Constants.OUTPUT_TRUNCATION_LIMIT)

    suspend fun setOutputTruncationLimit(limit: Int) =
        putInt(SettingsKeys.OUTPUT_TRUNCATION_LIMIT, limit.coerceIn(1000, 200000))

    suspend fun autoCheckpointEnabled(): Boolean =
        getBoolean(SettingsKeys.AUTO_CHECKPOINT_ENABLED, true)

    suspend fun setAutoCheckpointEnabled(flag: Boolean) =
        putBoolean(SettingsKeys.AUTO_CHECKPOINT_ENABLED, flag)

    suspend fun compactionEnabled(): Boolean =
        getBoolean(SettingsKeys.COMPACTION_ENABLED, true)

    suspend fun setCompactionEnabled(flag: Boolean) =
        putBoolean(SettingsKeys.COMPACTION_ENABLED, flag)

    suspend fun activeProviderId(): Long? =
        getString(SettingsKeys.ACTIVE_PROVIDER_ID)?.trim()?.toLongOrNull()

    suspend fun setActiveProviderId(id: Long?) {
        if (id == null) {
            remove(SettingsKeys.ACTIVE_PROVIDER_ID)
        } else {
            putString(SettingsKeys.ACTIVE_PROVIDER_ID, id.toString())
        }
    }

    suspend fun toolchainProvisioned(): Boolean =
        getBoolean(SettingsKeys.TOOLCHAIN_PROVISIONED, false)

    suspend fun setToolchainProvisioned(flag: Boolean) =
        putBoolean(SettingsKeys.TOOLCHAIN_PROVISIONED, flag)

    suspend fun androidSdkProvisioned(): Boolean =
        getBoolean(SettingsKeys.ANDROID_SDK_PROVISIONED, false)

    suspend fun setAndroidSdkProvisioned(flag: Boolean) =
        putBoolean(SettingsKeys.ANDROID_SDK_PROVISIONED, flag)

    suspend fun envBootstrapDone(): Boolean =
        getBoolean(SettingsKeys.ENV_BOOTSTRAP_DONE, false)

    suspend fun setEnvBootstrapDone(flag: Boolean) =
        putBoolean(SettingsKeys.ENV_BOOTSTRAP_DONE, flag)

    suspend fun firstSetupDone(): Boolean =
        getBoolean(SettingsKeys.FIRST_SETUP_DONE, false)

    suspend fun setFirstSetupDone(flag: Boolean) =
        putBoolean(SettingsKeys.FIRST_SETUP_DONE, flag)

    suspend fun preferredShell(): String =
        getString(SettingsKeys.PREFERRED_SHELL) ?: "/bin/sh"

    suspend fun setPreferredShell(shell: String) =
        putString(SettingsKeys.PREFERRED_SHELL, shell)

    suspend fun biometricLockEnabled(): Boolean =
        getBoolean(SettingsKeys.BIOMETRIC_LOCK_ENABLED, false)

    suspend fun setBiometricLockEnabled(flag: Boolean) =
        putBoolean(SettingsKeys.BIOMETRIC_LOCK_ENABLED, flag)

    suspend fun trustWorkspaceApk(): Boolean =
        getBoolean(SettingsKeys.TRUST_WORKSPACE_APK, true)

    suspend fun setTrustWorkspaceApk(flag: Boolean) =
        putBoolean(SettingsKeys.TRUST_WORKSPACE_APK, flag)

    /** Escopo próprio declarado pelo usuário: APKs, sistemas e sites sob seu controle. */
    suspend fun trustOwnedScope(): Boolean =
        getBoolean(SettingsKeys.TRUST_OWNED_SCOPE, true)

    suspend fun setTrustOwnedScope(flag: Boolean) =
        putBoolean(SettingsKeys.TRUST_OWNED_SCOPE, flag)

    suspend fun appTheme(): String =
        getString(SettingsKeys.APP_THEME) ?: "DEFAULT"

    suspend fun setAppTheme(themeName: String) =
        putString(SettingsKeys.APP_THEME, themeName)

    suspend fun keepAliveEnabled(): Boolean =
        getBoolean(SettingsKeys.KEEP_ALIVE_ENABLED, false)

    suspend fun setKeepAliveEnabled(flag: Boolean) =
        putBoolean(SettingsKeys.KEEP_ALIVE_ENABLED, flag)

    // ---------------- Reactive ----------------

    fun observe(): Flow<AppSettingsSnapshot> = settingsDao.observeAll().map { list ->
        val map = list.associate { it.settingKey to it.value }
        AppSettingsSnapshot(
            appTheme = map[SettingsKeys.APP_THEME] ?: "DEFAULT",
            keepAliveEnabled = map[SettingsKeys.KEEP_ALIVE_ENABLED]?.let {
                it.equals("true", ignoreCase = true) || it == "1"
            } ?: false,
            executionMode = when (map[SettingsKeys.EXECUTION_MODE]?.uppercase()) {
                ExecutionMode.TERMUX_ONLY.name -> ExecutionMode.TERMUX_ONLY
                ExecutionMode.INTERNAL_ONLY.name -> ExecutionMode.INTERNAL_ONLY
                else -> ExecutionMode.HYBRID
            },
            baseSystemPrompt = map[SettingsKeys.BASE_SYSTEM_PROMPT].orEmpty(),
            defaultMaxSteps = map[SettingsKeys.DEFAULT_MAX_STEPS]?.toIntOrNull()
                ?: Constants.DEFAULT_MAX_STEPS,
            defaultWorkspaceRoot = map[SettingsKeys.DEFAULT_WORKSPACE_ROOT]
                ?: Constants.DEFAULT_WORKSPACE_ROOT,
            outputTruncationLimit = map[SettingsKeys.OUTPUT_TRUNCATION_LIMIT]?.toIntOrNull()
                ?: Constants.OUTPUT_TRUNCATION_LIMIT,
            autoCheckpointEnabled = map[SettingsKeys.AUTO_CHECKPOINT_ENABLED]?.let {
                it.equals("true", ignoreCase = true) || it == "1"
            } ?: true,
            compactionEnabled = map[SettingsKeys.COMPACTION_ENABLED]?.let {
                it.equals("true", ignoreCase = true) || it == "1"
            } ?: true,
            activeProviderId = map[SettingsKeys.ACTIVE_PROVIDER_ID]?.toLongOrNull(),
            toolchainProvisioned = map[SettingsKeys.TOOLCHAIN_PROVISIONED]?.let {
                it.equals("true", ignoreCase = true) || it == "1"
            } ?: false,
            envBootstrapDone = map[SettingsKeys.ENV_BOOTSTRAP_DONE]?.let {
                it.equals("true", ignoreCase = true) || it == "1"
            } ?: false,
            firstSetupDone = map[SettingsKeys.FIRST_SETUP_DONE]?.let {
                it.equals("true", ignoreCase = true) || it == "1"
            } ?: false,
            preferredShell = map[SettingsKeys.PREFERRED_SHELL] ?: "/bin/sh"
        )
    }

    /**
     * Garante valores padrão na primeira execução (idempotente).
     */
    suspend fun ensureDefaults() {
        val defaults: Map<String, String> = mapOf(
            SettingsKeys.APP_THEME to "DEFAULT",
            SettingsKeys.KEEP_ALIVE_ENABLED to "false",
            SettingsKeys.EXECUTION_MODE to ExecutionMode.HYBRID.name,
            SettingsKeys.DEFAULT_MAX_STEPS to Constants.DEFAULT_MAX_STEPS.toString(),
            SettingsKeys.DEFAULT_WORKSPACE_ROOT to Constants.DEFAULT_WORKSPACE_ROOT,
            SettingsKeys.OUTPUT_TRUNCATION_LIMIT to Constants.OUTPUT_TRUNCATION_LIMIT.toString(),
            SettingsKeys.AUTO_CHECKPOINT_ENABLED to "true",
            SettingsKeys.COMPACTION_ENABLED to "true",
            SettingsKeys.PREFERRED_SHELL to "/bin/sh",
            SettingsKeys.TRUST_WORKSPACE_APK to "true",
            SettingsKeys.TRUST_OWNED_SCOPE to "true"
        )
        defaults.forEach { (key, value) ->
            if (settingsDao.get(key) == null) {
                settingsDao.put(SettingEntity(key, value))
            }
        }
    }
}
