package com.codex.agent.observability

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Observabilidade local (opt-in): crash reporting e métricas de uso exportáveis.
 * Nada é enviado para servidores externos — todos os dados ficam em arquivos no workspace
 * e podem ser exportados pelo usuário. Respeita a privacidade 100% on-device.
 */
object Observability {

    private const val TAG = "CodexObservability"
    private const val DIR = ".codex_observability"

    private var appContext: Context? = null
    @Volatile private var enabled = false

    /** Instala o handler de crashes. Chamado na Application.onCreate. */
    fun install(context: Context, enabledByDefault: Boolean = true) {
        appContext = context.applicationContext
        enabled = enabledByDefault
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (enabled) writeCrash(thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "Observability instalado (enabled=$enabled)")
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        Log.i(TAG, "Observability enabled=$value")
    }

    private fun dir(): File {
        val ctx = appContext ?: return File(DIR) // fallback se install() não foi chamado
        val base = File(ctx.filesDir, DIR)
        base.mkdirs()
        return base
    }

    /** Thread-safe: cria um SimpleDateFormat novo por chamada (o compartilhado não é thread-safe). */
    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        runCatching {
            val sb = StringBuilder()
            sb.appendLine("=== CRASH ${now()} ===")
            sb.appendLine("Thread: ${thread.name} (id=${thread.id})")
            sb.appendLine("Exception: ${throwable.javaClass.name}")
            sb.appendLine("Message: ${throwable.message}")
            sb.appendLine("Stack trace:")
            throwable.stackTrace.forEach { sb.appendLine("  at $it") }
            var cause = throwable.cause
            while (cause != null) {
                sb.appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
                cause.stackTrace.take(10).forEach { sb.appendLine("  at $it") }
                cause = cause.cause
            }
            sb.appendLine()
            File(dir(), "crashes.log").appendText(sb.toString())
        }
    }

    /** Registra uma métrica de execução de tool. */
    fun recordToolCall(toolName: String, durationMs: Long, success: Boolean, sessionId: Long = 0) {
        if (!enabled) return
        runCatching {
            val line = "${now()}\t$toolName\t${durationMs}ms\t$success\t$sessionId\n"
            File(dir(), "tool_metrics.tsv").appendText(line)
        }
    }

    /**
     * Preços estimados por 1M tokens (input/output) em USD — modelos comuns.
     * Valores aproximados para fins de estimativa; providers custom retornam 0.
     */
    private val PRICING = listOf(
        Triple("gpt-4o", 2.50, 10.00), Triple("gpt-4o-mini", 0.15, 0.60),
        Triple("claude-sonnet", 3.00, 15.00), Triple("claude-haiku", 0.25, 1.25),
        Triple("deepseek-chat", 0.27, 1.10), Triple("gemini-2.0-flash", 0.10, 0.40),
        Triple("glm-5.3-flash", 0.10, 0.30), Triple("ling-3.2", 0.10, 0.30)
    )

    /** Estima custo em USD a partir de tokens, com base no nome do modelo. */
    fun estimateCostUsd(model: String, promptTokens: Int, completionTokens: Int): Double {
        val m = model.lowercase()
        val price = PRICING.firstOrNull { m.contains(it.first) }
            ?: return 0.0
        return (promptTokens / 1_000_000.0) * price.second + (completionTokens / 1_000_000.0) * price.third
    }

    /** Registra consumo de tokens e custo estimado por sessão. */
    fun recordTokens(sessionId: Long, provider: String, model: String, prompt: Int, completion: Int) {
        if (!enabled) return
        runCatching {
            val cost = estimateCostUsd(model, prompt, completion)
            val line = "${now()}\t$sessionId\t$provider\t$model\t$prompt\t$completion\t${String.format("%.6f", cost)}\n"
            File(dir(), "cost.tsv").appendText(line)
        }
    }

    /** Custo total acumulado (USD) lendo cost.tsv. */
    fun totalCostUsd(): Double = runCatching {
        val f = File(dir(), "cost.tsv")
        if (!f.exists()) return@runCatching 0.0
        f.readLines().sumOf { line ->
            line.split("\t").getOrNull(6)?.toDoubleOrNull() ?: 0.0
        }
    }.getOrDefault(0.0)

    /** Registra um evento genérico (build, install, etc.). */
    fun recordEvent(event: String, detail: String = "") {
        if (!enabled) return
        runCatching {
            File(dir(), "events.tsv").appendText("${now()}\t$event\t${detail.take(300)}\n")
        }
    }

    /** Exporta todos os logs de observabilidade como texto consolidado. */
    fun exportReport(): String = buildString {
        appendLine("# Codex Agent — Relatório de Observabilidade")
        appendLine("Gerado em: ${now()}")
        appendLine()
        listOf("crashes.log", "tool_metrics.tsv", "events.tsv").forEach { name ->
            val f = File(dir(), name)
            appendLine("## $name")
            if (f.exists()) {
                // Lê só o tail (últimos ~20k chars) sem carregar arquivos gigantes.
                val size = f.length()
                val skip = maxOf(0L, size - 20_000L)
                appendLine(f.bufferedReader(Charsets.UTF_8).use { r ->
                    r.skip(skip)
                    r.readText()
                })
            } else appendLine("(vazio)")
            appendLine()
        }
    }
}
