package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Análise de memória do PRÓPRIO app (heap dump, monitoramento, strings).
 * Funciona via Shizuku/ADB ou internamente no app.
 *
 * Ações:
 *  - heap_dump: captura heap dump do processo do seu app (via am dumpheap)
 *  - heap_summary: analisa um heap dump (hprof) e mostra classes com mais objetos
 *  - strings: extrai strings legíveis de um dump (secrets, endpoints, etc.)
 *  - meminfo: uso de memória por processo (RSS, PSS, heap)
 *  - watch: monitora memória ao longo do tempo
 *  - gc: força GC e reporta heap antes/depois
 */
class MemoryAnalyzer(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository
) {
    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' obrigatório."
        return try {
            when (action) {
                "heap_dump" -> heapDump(args)
                "heap_summary" -> heapSummary(args)
                "strings" -> extractStrings(args)
                "meminfo" -> meminfo(args)
                "watch" -> watch(args)
                "gc" -> gc(args)
                else -> "Ação desconhecida em memory_analyzer: $action"
            }
        } catch (e: Exception) {
            "Erro em memory_analyzer ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT

    private suspend fun run(cmd: String, timeout: Int = 120): String {
        val r = shellExecutor.execute(cmd, timeoutSec = timeout)
        return if (r.exitCode == 0) r.output.trim().ifBlank { "OK (sem saída)" }
        else "exit=${r.exitCode}:\n${r.output.trim()}"
    }

    private suspend fun available(name: String): Boolean =
        shellExecutor.execute("command -v $name >/dev/null 2>&1 && echo yes || echo no", timeoutSec = 15)
            .output.trim() == "yes"

    private fun sq(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    private suspend fun heapDump(args: JsonObject): String {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'package' obrigatório (package do seu app)."
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/heap_${pkg}_${System.currentTimeMillis()}.hprof")
        out.parentFile?.mkdirs()
        val pid = shellExecutor.execute("pidof $pkg 2>/dev/null", timeoutSec = 10).output.trim()
        if (pid.isBlank()) return "Erro: app '$pkg' não está rodando. Abra o app primeiro."
        val r = run("am dumpheap $pid ${sq(out.absolutePath)} 2>&1", 60)
        kotlinx.coroutines.delay(2000)
        return if (out.exists() && out.length() > 0) {
            "✅ Heap dump capturado: ${out.absolutePath} (${out.length() / 1024} KB)\n" +
                "Use action='heap_summary' ou 'strings' para analisar."
        } else "Falha ao capturar heap: $r"
    }

    private suspend fun heapSummary(args: JsonObject): String {
        val file = args["file"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'file' obrigatório."
        val f = File(file)
        if (!f.exists()) return "Erro: arquivo não encontrado: ${f.absolutePath}"
        val sb = StringBuilder("=== Resumo de Heap (análise por strings) ===\nArquivo: ${f.name} (${f.length() / 1024} KB)\n\n")
        // Sem hprof parsing library disponível on-device, extraímos strings de classes.
        val strings = run("strings -n 8 ${sq(f.absolutePath)} 2>/dev/null | sort | uniq -c | sort -rn | head -n 60")
        sb.appendLine("Top strings (frequência):")
        sb.appendLine(strings)
        sb.appendLine()
        sb.appendLine("Dica: para análise detalhada de heap (classes/objetos/retainers), use Android Studio Profiler")
        sb.appendLine("ou converta com 'hprof-conv' e abra no Eclipse MAT / VisualVM no PC.")
        return sb.toString()
    }

    private suspend fun extractStrings(args: JsonObject): String {
        val file = args["file"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'file' obrigatório (heap dump ou .so)."
        val f = File(file)
        if (!f.exists()) return "Erro: arquivo não encontrado."
        val min = (args["min_len"]?.jsonPrimitive?.contentOrNull ?: "6").toIntOrNull()?.coerceIn(2, 200) ?: 6
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull?.filter { it.isLetterOrDigit() || it in "._-/" }.orEmpty()
        var cmd = "strings -n $min ${sq(f.absolutePath)}"
        if (filter.isNotBlank()) cmd += " | grep -i ${sq(filter)}"
        cmd += " | sort -u | head -n 200"
        val out = run(cmd, 120)
        return "=== Strings extraídas (min=$min, filtro='$filter') ===\n$out\n\n" +
            "Use para detectar secrets, endpoints, tokens ou dados sensíveis na memória do seu app."
    }

    private suspend fun meminfo(args: JsonObject): String {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull
        val pid = if (pkg != null) {
            shellExecutor.execute("pidof $pkg 2>/dev/null", timeoutSec = 10).output.trim()
        } else args["pid"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sb = StringBuilder("=== Informação de Memória ===\n")
        if (pid.isNotBlank()) {
            sb.appendLine("PID: $pid")
            sb.appendLine(run("dumpsys meminfo $pid 2>&1 | head -n 40", 30))
        } else {
            sb.appendLine("Uso geral do dispositivo:")
            sb.appendLine(run("cat /proc/meminfo 2>/dev/null | head -n 8", 10))
            sb.appendLine()
            sb.appendLine("Top processos por memória:")
            sb.appendLine(run("ps -A -o pid,rss,comm 2>/dev/null | sort -rn -k2 | head -n 20", 20))
        }
        return sb.toString()
    }

    private suspend fun watch(args: JsonObject): String {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'package' obrigatório."
        val secs = (args["seconds"]?.jsonPrimitive?.contentOrNull ?: "10").toIntOrNull()?.coerceIn(1, 60) ?: 10
        val sb = StringBuilder("=== Monitoramento de Memória ($secs s) ===\n")
        val pid = shellExecutor.execute("pidof $pkg 2>/dev/null", timeoutSec = 10).output.trim()
        if (pid.isBlank()) return "Erro: app '$pkg' não está rodando."
        sb.appendLine("PID: $pid")
        val interval = 2
        var t = 0
        while (t < secs) {
            val heap = run("dumpsys meminfo $pid 2>/dev/null | grep -i 'Java Heap\\|Native Heap\\|Total RSS' | head -n 4", 10)
            sb.appendLine("[${t}s] $heap".replace("\n", " | "))
            kotlinx.coroutines.delay(interval * 1000L)
            t += interval
        }
        return sb.toString()
    }

    private suspend fun gc(args: JsonObject): String {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'package' obrigatório."
        val pid = shellExecutor.execute("pidof $pkg 2>/dev/null", timeoutSec = 10).output.trim()
        if (pid.isBlank()) return "Erro: app '$pkg' não está rodando."
        val before = run("dumpsys meminfo $pid 2>/dev/null | grep -i 'Java Heap' | head -n 1", 10)
        // Força GC via am (disponível em apps debugáveis ou com permissão)
        run("am force-stop $pkg 2>/dev/null; sleep 1", 10)
        val after = run("dumpsys meminfo $pid 2>/dev/null | grep -i 'Java Heap' | head -n 1", 10)
        return "=== GC do app $pkg ===\nAntes: $before\nDepois: $after\n" +
            "Nota: force-stop reinicia o app. Para GC sem reinício, use Frida (Runtime.getRuntime().gc())."
    }
}
