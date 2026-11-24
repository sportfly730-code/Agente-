package com.codex.agent

import android.app.Application
import com.codex.agent.observability.Observability

/**
 * Application raiz. Configura observabilidade.
 * Modelo local (GGUF) foi removido — o app usa exclusivamente provedores cloud
 * (OpenAI, Gemini, Vyce, Groq, etc.).
 */
class CodexAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Observability.install(this)
    }
}
