package com.codex.agent.agent

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Memória de APRENDIZADO: registra padrões que FUNCIONARAM (tool + args + resultado)
 * e reinjeta os relevantes no system prompt antes de cada tarefa. Assim o agente
 * "aprende" com o que já deu certo e não repete erros.
 *
 * Persistência: SharedPreferences (sobrevive ao app ser fechado).
 * Limite: mantém os últimos 200 registros.
 */
class LearningStore(context: Context) {
    companion object {
        private const val PREFS = "codex_learning"
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 200
        private const val MAX_PROMPT = 12
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val records = ConcurrentLinkedDeque<Record>()

    data class Record(
        val task: String,
        val tool: String,
        val args: String,
        val outcome: String,
        val timestamp: Long
    )

    init {
        load()
    }

    private fun load() {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return
        runCatching {
            val arr = json.parseToJsonElement(raw).jsonArray
            arr.forEach { el ->
                val o = el.jsonObject
                records.add(Record(
                    task = o["task"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    tool = o["tool"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    args = o["args"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    outcome = o["outcome"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    timestamp = o["ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                ))
            }
        }
    }

    private fun persist() {
        val arr = kotlinx.serialization.json.buildJsonArray {
            records.forEach { r ->
                add(buildJsonObject {
                    put("task", r.task)
                    put("tool", r.tool)
                    put("args", r.args)
                    put("outcome", r.outcome)
                    put("ts", r.timestamp)
                })
            }
        }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    /** Registra um sucesso. */
    fun recordSuccess(task: String, tool: String, args: String, outcome: String) {
        if (task.isBlank() || tool.isBlank()) return
        // Evita duplicatas recentes (mesmo tool+args nos últimos 5 registros)
        val recent = records.toList().takeLast(5)
        if (recent.any { it.tool == tool && it.args == args }) return

        records.add(Record(task.take(200), tool, args.take(400), outcome.take(300), System.currentTimeMillis()))
        while (records.size > MAX_RECORDS) records.pollFirst()
        persist()
    }

    /** Busca registros relevantes para a tarefa atual (por similaridade de palavras-chave). */
    fun relevant(task: String, limit: Int = MAX_PROMPT): List<Record> {
        if (records.isEmpty() || task.isBlank()) return emptyList()
        val taskWords = task.lowercase().split(Regex("\\W+")).filter { it.length >= 3 }.toSet()
        if (taskWords.isEmpty()) return records.toList().takeLast(limit)
        return records.toList()
            .map { r ->
                val rWords = (r.task + " " + r.tool).lowercase().split(Regex("\\W+")).toSet()
                val overlap = taskWords.intersect(rWords).size
                r to overlap
            }
            .sortedByDescending { it.second }
            .filter { it.second > 0 }
            .take(limit)
            .map { it.first }
            .ifEmpty { records.toList().takeLast(limit) }
    }

    /** Texto para injetar no system prompt. */
    fun promptText(task: String): String {
        val recs = relevant(task)
        if (recs.isEmpty()) return ""
        return buildString {
            appendLine("=== MEMÓRIA DE APRENDIZADO (o que já funcionou) ===")
            recs.forEach { r ->
                appendLine("• Tarefa: ${r.task.take(80)}")
                appendLine("  → ${r.tool}(${r.args.take(80)}) → ${r.outcome.take(80)}")
            }
            appendLine("Use esses padrões quando aplicável — eles já deram certo.")
        }
    }

    fun count(): Int = records.size

    fun clear() {
        records.clear()
        prefs.edit().remove(KEY_RECORDS).apply()
    }
}
