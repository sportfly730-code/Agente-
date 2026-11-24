package com.codex.agent.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Armazenamento criptografado (AndroidX Security Crypto / Android Keystore) para segredos
 * (API keys, tokens, senhas). Substitui o armazenamento em texto puro no Room.
 *
 * A chave mestra é gerada e protegida pelo Android Keystore (hardware-backed quando disponível).
 */
class SecretsStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun putSecret(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getSecret(key: String): String? = prefs.getString(key, null)

    fun removeSecret(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun hasSecret(key: String): Boolean = prefs.contains(key)

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "codex_secrets"
        const val PREFIX_PROVIDER_KEY = "provider_apikey_"
    }
}
