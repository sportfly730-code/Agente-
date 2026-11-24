package com.codex.agent.shell

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * Ponte centralizada para executar comandos shell via Shizuku (privilégio ADB, sem root).
 * Usada para automação do Termux (input text, am start, dumpsys) e outras tarefas de sistema.
 */
object ShizukuBridge {

    fun isReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Executa um comando via Shizuku com timeout REAL e sem deadlock.
     * - stdout e stderr são lidos em threads separadas (evita enchimento do pipe).
     * - waitFor(timeout) roda ANTES de join das threads; no timeout mata o processo.
     */
    fun exec(cmd: Array<String>, timeoutMs: Long = 8000): String? = runCatching {
        // newProcess é privado nesta versão do Shizuku — usa reflection (como o resto do app).
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, cmd, null, null) as Process
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread {
            try { process.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                var line: String?; while (r.readLine().also { line = it } != null) { out.appendLine(line) }
            } } catch (_: Exception) {}
        }
        val t2 = Thread {
            try { process.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                var line: String?; while (r.readLine().also { line = it } != null) { err.appendLine(line) }
            } } catch (_: Exception) {}
        }
        t1.isDaemon = true; t2.isDaemon = true
        t1.start(); t2.start()
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { process.destroyForcibly() }
        }
        t1.join(2000); t2.join(2000)
        val combined = out.toString() + err.toString()
        combined.ifBlank { null }
    }.getOrNull()
}
