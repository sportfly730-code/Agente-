package com.codex.agent.shell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.codex.agent.data.db.AppDatabase
import com.codex.agent.data.entity.BackgroundJobEntity
import com.codex.agent.data.model.Constants
import com.codex.agent.data.model.ExecutionMode
import com.codex.agent.data.model.JobStatus
import com.codex.agent.data.repository.SettingsRepository
import com.codex.agent.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Resultado consolidado de uma execução de shell. */
data class ShellResult(
    val exitCode: Int,
    val output: String,
    val fullLogPath: String? = null,
    val isBackground: Boolean = false,
    val pid: String? = null,
    val engineUsed: String = ShellExecutor.ENGINE_INTERNAL,
    /** True quando o motor Termux falhou (ex.: allow-external-apps=false) — permite fallback. */
    val bridgeFailure: Boolean = false
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Motor de execução híbrido e anti-travamento:
 *
 * 1) Termux Bridge (prioritário): dispara o comando via Intent RUN_COMMAND em background,
 *    captura stdout/stderr/exit_code por arquivos em /sdcard/.codex_agent_bridge/<job_id>/,
 *    injeta variáveis não-interativas (CI, DEBIAN_FRONTEND, PIP_NO_INPUT, npm_config_yes,
 *    GIT_TERMINAL_PROMPT) e limites de heap JVM (-Xmx1536m).
 *
 * 2) Shell interno (fallback): ProcessBuilder("/system/bin/sh") com HOME/TMPDIR/PREFIX/PATH
 *    apontando para filesDir/usr, para o modo standalone (sem Termux).
 *
 * 3) Processos em background: retorna PID + log imediatamente; jobs persistidos em Room e
 *    visíveis no Terminal Live (Parte 4) com botão Kill.
 *
 * 4) Smart Output Truncator: limita a saída enviada à LLM (settings.outputTruncationLimit,
 *    padrão 8000 chars preservando início/fim) e grava o log integral em /sdcard/.codex_logs.
 *
 * 5) Trava de segurança inegociável contra comandos destrutivos em raiz (/sdcard, mkfs, dd).
 */
class ShellExecutor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val db: AppDatabase
) {

    private val bridgeRootDir = File(Constants.TERMUX_BRIDGE_DIR)
    private val logsRootDir = File(Environment.getExternalStorageDirectory(), ".codex_logs")
    private val internalUsrDir = File(context.filesDir, "usr")

    /**
     * O RUN_COMMAND do Termux executa UM comando por vez. Sem este mutex, comandos
     * concorrentes (ex.: provisionamento longo + checkpoint git) ficam enfileirados
     * e estouram timeout (exit 124). Serializamos o acesso à bridge.
     */
    private val bridgeMutex = Mutex()

    /** Scope declarado ANTES do init — usado por ensureTermuxBridgeAuto() no init. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Configuração automática da ponte Termux via Shizuku (sem intervenção do usuário). */
    private val termuxAutoSetup: TermuxAutoSetup by lazy {
        TermuxAutoSetup(context) { cmd -> ShizukuBridge.exec(cmd) }
    }

    private val _liveTerminalOutput = MutableStateFlow("[Codex Terminal Ready]\n")
    val liveTerminalOutput: StateFlow<String> = _liveTerminalOutput.asStateFlow()

    private val activeInternalProcesses = ConcurrentHashMap<String, Process>()

    init {
        runCatching {
            bridgeRootDir.mkdirs()
            logsRootDir.mkdirs()
            File(internalUsrDir, "bin").mkdirs()
            File(internalUsrDir, "local/bin").mkdirs()
            File(internalUsrDir, "tmp").mkdirs()
            File(internalUsrDir, "home").mkdirs()
            // Limpa diretórios de job órfãos de execuções anteriores (falhas da bridge
            // deixavam um diretório por tentativa — 25+ acumulados em trabalho intenso).
            cleanStaleBridgeJobs()
        }
        // Auto-configura a ponte Termux via Shizuku (se concedido) — sem toque do usuário.
        ensureTermuxBridgeAuto()
    }

    /** Remove job dirs da bridge com mais de 1 hora (resultados já consumidos/expirados). */
    private fun cleanStaleBridgeJobs() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            bridgeRootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.lastModified() < cutoff) {
                    runCatching { dir.deleteRecursively() }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Termux helpers
    // ------------------------------------------------------------------

    fun isTermuxInstalled(): Boolean =
        try {
            context.packageManager.getPackageInfo(Constants.TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * True somente quando o Termux está instalado E a ponte RUN_COMMAND foi configurada
     * (flag `.ready` criada pelo comando de setup). Isso evita disparar RUN_COMMAND contra
     * um Termux sem `allow-external-apps`, que geraria erro `Error Code 2` no logcat e
     * exigiria fallback em toda chamada.
     */
    fun isTermuxBridgeReady(): Boolean =
        isTermuxInstalled() && File(Constants.TERMUX_BRIDGE_READY_FLAG).exists()

    /**
     * Configura a ponte Termux AUTOMATICAMENTE via Shizuku, se disponível.
     * Chamado em background na inicialização — o usuário não precisa fazer nada.
     */
    fun ensureTermuxBridgeAuto() {
        if (!isTermuxInstalled() || isTermuxBridgeReady() || !ShizukuBridge.isReady()) return
        scope.launch {
            val result = termuxAutoSetup.autoConfigure()
            appendLiveLog("[AUTO-SETUP] $result\n")
        }
    }

    // ------------------------------------------------------------------
    // Live terminal stream
    // ------------------------------------------------------------------

    fun appendLiveLog(text: String) {
        // update{} é atômico — evita perder linhas quando várias threads anexam ao mesmo tempo.
        _liveTerminalOutput.update { current ->
            val updated = current + text
            if (updated.length > LIVE_LOG_MAX_CHARS) updated.takeLast(LIVE_LOG_MAX_CHARS) else updated
        }
    }

    fun clearLiveLog() {
        _liveTerminalOutput.value = "[Terminal limpo]\n"
    }

    // ------------------------------------------------------------------
    // API principal
    // ------------------------------------------------------------------

    suspend fun execute(
        command: String,
        workdir: String? = null,
        timeoutSec: Int? = null,
        isBackground: Boolean = false
    ): ShellResult = withContext(Dispatchers.IO) {
        val effectiveTimeout = timeoutSec ?: Constants.DEFAULT_SHELL_TIMEOUT_SEC
        val effectiveWorkdir = resolveWorkdir(workdir)
        File(effectiveWorkdir).mkdirs()

        // Guardrail real (parser de tokens, não string match)
        val blockReason = ShellSafetyGuard.analyze(command)
        if (blockReason != null) {
            appendLiveLog("\n[BLOQUEADO] $blockReason\n")
            val blockedResult = ShellResult(
                exitCode = 126,
                output = "BLOQUEADO POR SEGURANÇA: $blockReason\n\nComando recusado. " +
                    "Se você tem certeza de que precisa executar isso, peça ao usuário explicitamente."
            )
            recordAuditLog(command, effectiveWorkdir, null, blockedResult.exitCode, true, blockReason, 0)
            return@withContext blockedResult
        }

        val mode = settingsRepository.executionMode()
        val useTermux = when (mode) {
            ExecutionMode.TERMUX_ONLY -> true
            ExecutionMode.INTERNAL_ONLY -> false
            // HYBRID: só usa a ponte se ela foi efetivamente configurada (flag .ready).
            // Termux instalado mas sem allow-external-apps → vai direto pro shell interno,
            // evitando erros RUN_COMMAND (Error Code 2) e fallback em toda chamada.
            ExecutionMode.HYBRID -> isTermuxBridgeReady()
        }

        if (mode == ExecutionMode.HYBRID && isTermuxInstalled() && !isTermuxBridgeReady()) {
            appendLiveLog("[TERMUX] Instalado mas ponte não configurada (allow-external-apps). " +
                "Use o botão 'Copiar Config Termux' no setup. Usando shell interno.\n")
        }

        appendLiveLog("\n$ [$effectiveWorkdir] $command\n")

        val startTime = System.currentTimeMillis()
        var result = if (useTermux) {
            // Serializa o acesso à bridge: o RUN_COMMAND do Termux executa um comando
            // por vez. Sem o mutex, comandos concorrentes (provisionamento longo + git)
            // ficam enfileirados e estouram timeout (exit 124).
            val termuxResult = bridgeMutex.withLock {
                executeViaTermuxBridge(command, effectiveWorkdir, effectiveTimeout, isBackground)
            }
            if (termuxResult.exitCode == 124 && mode == ExecutionMode.HYBRID) {
                // Timeout na bridge (fila ocupada) → fallback imediato para shell interno.
                appendLiveLog("[FALLBACK] Bridge ocupada/timeout — usando shell interno.\n")
                executeViaInternalProcessBuilder(command, effectiveWorkdir, effectiveTimeout, isBackground)
            } else if (termuxResult.bridgeFailure && mode == ExecutionMode.HYBRID) {
                appendLiveLog("[FALLBACK] Termux indisponível — usando shell interno.\n")
                executeViaInternalProcessBuilder(command, effectiveWorkdir, effectiveTimeout, isBackground)
            } else {
                termuxResult
            }
        } else {
            executeViaInternalProcessBuilder(command, effectiveWorkdir, effectiveTimeout, isBackground)
        }
        val duration = System.currentTimeMillis() - startTime

        // Smart Output Truncator: log integral em disco, versão encurtada para a LLM.
        if (!result.isBackground) {
            val logFile = File(logsRootDir, "job_${UUID.randomUUID().toString().take(8)}.log")
            runCatching { logFile.writeText(result.output) }
            val limit = settingsRepository.outputTruncationLimit()
            result = result.copy(
                output = truncateOutputSmartly(result.output, limit, logFile.absolutePath),
                fullLogPath = logFile.absolutePath
            )
        }

        // Audit log (rastreabilidade OWASP)
        recordAuditLog(command, effectiveWorkdir, result.engineUsed, result.exitCode, false, null, duration)

        result
    }

    private suspend fun resolveWorkdir(workdir: String?): String {
        if (!workdir.isNullOrBlank()) return workdir.trim()
        val active = workspaceRepository.active()?.rootPath
        return active ?: Constants.DEFAULT_WORKSPACE_ROOT
    }

    // ------------------------------------------------------------------
    // Modo 1 — Termux Bridge
    // ------------------------------------------------------------------

    private suspend fun executeViaTermuxBridge(
        command: String,
        workdir: String,
        timeoutSec: Int,
        isBackground: Boolean
    ): ShellResult = withContext(Dispatchers.IO) {
        if (!isTermuxInstalled()) {
            return@withContext ShellResult(
                exitCode = 127,
                output = "Termux (${Constants.TERMUX_PACKAGE}) não está instalado neste dispositivo.",
                engineUsed = ENGINE_TERMUX,
                bridgeFailure = true
            )
        }

        val jobId = UUID.randomUUID().toString().take(12)
        val jobDir = File(bridgeRootDir, jobId)
        val scriptFile = File(jobDir, Constants.BRIDGE_RUNNER_SCRIPT)
        val outFile = File(jobDir, Constants.BRIDGE_STDOUT_FILE)
        val errFile = File(jobDir, Constants.BRIDGE_STDERR_FILE)
        val exitFile = File(jobDir, "exit_code.txt")
        val pidFile = File(jobDir, "pid.txt")

        try {
            if (!jobDir.exists() && !jobDir.mkdirs()) {
                return@withContext ShellResult(
                    exitCode = 126,
                    output = "Falha ao criar diretório no Termux Bridge (${jobDir.absolutePath}). Permissão de armazenamento não concedida.",
                    engineUsed = ENGINE_TERMUX,
                    bridgeFailure = true
                )
            }
            scriptFile.writeText(buildBridgeScript(command, workdir, outFile, errFile, exitFile, pidFile, isBackground))

            val intent = Intent(Constants.TERMUX_RUN_COMMAND_ACTION).apply {
                setClassName(Constants.TERMUX_PACKAGE, "com.termux.app.RunCommandService")
                putExtra(Constants.TERMUX_EXTRA_COMMAND, "/data/data/com.termux/files/usr/bin/bash")
                putExtra(Constants.TERMUX_EXTRA_ARGUMENTS, arrayOf(scriptFile.absolutePath))
                putExtra(Constants.TERMUX_EXTRA_WORKDIR, workdir)
                putExtra(Constants.TERMUX_EXTRA_BACKGROUND, true)
            }
            context.startService(intent)
        } catch (e: Exception) {
            return@withContext ShellResult(
                exitCode = 126,
                output = "Falha ao acionar o Termux RUN_COMMAND (${e.message}). " +
                    "Verifique permissões de armazenamento ou 'allow-external-apps=true' em ~/.termux/termux.properties.",
                engineUsed = ENGINE_TERMUX,
                bridgeFailure = true
            )
        }

        if (isBackground) {
            // Aguarda apenas o PID ser emitido (o processo roda independente).
            val pidDeadline = System.currentTimeMillis() + 15_000
            while (!pidFile.exists() && System.currentTimeMillis() < pidDeadline) delay(200)
            val pid = if (pidFile.exists()) pidFile.readText().trim() else null
            val logPath = outFile.absolutePath
            db.backgroundJobDao().upsert(
                BackgroundJobEntity(
                    jobId = jobId,
                    command = command,
                    workdir = workdir,
                    pid = pid,
                    logPath = logPath,
                    status = JobStatus.RUNNING
                )
            )
            appendLiveLog("[BACKGROUND] Job $jobId iniciado. PID=$pid Log=$logPath\n")
            return@withContext ShellResult(
                exitCode = 0,
                output = "Processo iniciado em background.\nPID: ${pid ?: "n/d"}\nLog em tempo real: $logPath",
                isBackground = true,
                pid = pid,
                engineUsed = ENGINE_TERMUX
            )
        }

        // Foreground: stream incremental do stdout + espera do exit_code.
        val deadlineMs = System.currentTimeMillis() + timeoutSec * 1000L
        var stdoutOffset = 0L
        var stderrOffset = 0L
        while (!exitFile.exists() && System.currentTimeMillis() < deadlineMs) {
            stdoutOffset = streamFileDelta(outFile, stdoutOffset)
            stderrOffset = streamFileDelta(errFile, stderrOffset)
            delay(100)
        }

        if (!exitFile.exists()) {
            appendLiveLog("\n[TIMEOUT após ${timeoutSec}s]\n")
            val partial = listOf(outFile, errFile).filter { it.exists() }.joinToString("\n") { it.readText() }
            return@withContext ShellResult(
                exitCode = 124,
                output = "TIMEOUT (${timeoutSec}s) aguardando conclusão no Termux.\nSaída parcial:\n$partial",
                engineUsed = ENGINE_TERMUX
            )
        }

        val exitCode = runCatching { exitFile.readText().trim().toIntOrNull() }.getOrNull() ?: -1
        streamFileDelta(outFile, stdoutOffset)
        streamFileDelta(errFile, stderrOffset)

        val stdout = if (outFile.exists()) outFile.readText() else ""
        val stderr = if (errFile.exists()) errFile.readText() else ""
        runCatching { jobDir.deleteRecursively() }

        val combined = buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n--- STDERR ---\n")
                append(stderr)
            }
            if (isEmpty()) append("(Comando concluído sem saída textual; exit_code=$exitCode)")
        }
        ShellResult(exitCode = exitCode, output = combined, engineUsed = ENGINE_TERMUX)
    }

    /**
     * Script runner gravado no diretório de bridge: envs não-interativas, heap JVM,
     * captura de stdout/stderr/exit_code e PID para background (nohup).
     */
    private fun buildBridgeScript(
        command: String,
        workdir: String,
        outFile: File,
        errFile: File,
        exitFile: File,
        pidFile: File,
        isBackground: Boolean
    ): String {
        val envBlock = """
            export CI=true
            export DEBIAN_FRONTEND=noninteractive
            export PIP_NO_INPUT=1
            export npm_config_yes=true
            export GIT_TERMINAL_PROMPT=0
            export JAVA_OPTS="-Xmx1536m"
            export _JAVA_OPTIONS="-Xmx1536m"
            export PATH="/data/data/com.termux/files/usr/bin:${'$'}PATH"
            export TMPDIR="/data/data/com.termux/files/usr/tmp"
        """.trimIndent()

        return if (isBackground) {
            """
            #!/data/data/com.termux/files/usr/bin/bash
            $envBlock
            cd "$workdir" || exit 1
            nohup bash -c ${escapeBashSingleQuotes(command)} > "${outFile.absolutePath}" 2> "${errFile.absolutePath}" &
            echo "${'$'}!" > "${pidFile.absolutePath}"
            """.trimIndent()
        } else {
            """
            #!/data/data/com.termux/files/usr/bin/bash
            $envBlock
            cd "$workdir" || exit 1
            (
            $command
            ) > "${outFile.absolutePath}" 2> "${errFile.absolutePath}"
            echo "${'$'}?" > "${exitFile.absolutePath}"
            """.trimIndent()
        }
    }

    // ------------------------------------------------------------------
    // Modo 2 — Shell interno (ProcessBuilder)
    // ------------------------------------------------------------------

    private suspend fun executeViaInternalProcessBuilder(
        command: String,
        workdir: String,
        timeoutSec: Int,
        isBackground: Boolean
    ): ShellResult = withContext(Dispatchers.IO) {
        val jobId = UUID.randomUUID().toString().take(12)
        val logFile = File(logsRootDir, "bg_$jobId.log")

        val processBuilder = ProcessBuilder("/system/bin/sh", "-c", command).apply {
            directory(File(workdir))
            redirectErrorStream(false)
            val env = environment()
            env["HOME"] = File(internalUsrDir, "home").absolutePath
            env["PREFIX"] = internalUsrDir.absolutePath
            env["TMPDIR"] = File(internalUsrDir, "tmp").absolutePath
            env["PATH"] = listOf(
                File(internalUsrDir, "bin").absolutePath,
                File(internalUsrDir, "local/bin").absolutePath,
                env["PATH"] ?: "/system/bin:/system/xbin"
            ).joinToString(":")
            env["CI"] = "true"
            env["DEBIAN_FRONTEND"] = "noninteractive"
            env["PIP_NO_INPUT"] = "1"
            env["npm_config_yes"] = "true"
            env["GIT_TERMINAL_PROMPT"] = "0"
            env["JAVA_OPTS"] = "-Xmx1536m"
            env["_JAVA_OPTIONS"] = "-Xmx1536m"
        }

        try {
            val process = processBuilder.start()
            activeInternalProcesses[jobId] = process

            if (isBackground) {
                val stdoutThread = Thread {
                    runCatching {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { appendLiveLog("$it\n") }
                        }
                    }
                }.apply { isDaemon = true; start() }
                val stderrThread = Thread {
                    runCatching {
                        process.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { appendLiveLog("[stderr] $it\n") }
                        }
                    }
                }.apply { isDaemon = true; start() }

                val entity = BackgroundJobEntity(
                    jobId = jobId,
                    command = command,
                    workdir = workdir,
                    pid = "internal_$jobId",
                    logPath = logFile.absolutePath,
                    status = JobStatus.RUNNING
                )
                db.backgroundJobDao().upsert(entity)
                appendLiveLog("[BACKGROUND interno] Job $jobId iniciado. Log=${logFile.absolutePath}\n")
                return@withContext ShellResult(
                    exitCode = 0,
                    output = "Processo interno iniciado em background.\nJobID: internal_$jobId\nLog: ${logFile.absolutePath}",
                    isBackground = true,
                    pid = "internal_$jobId",
                    engineUsed = ENGINE_INTERNAL
                )
            }

            val outputBuilder = StringBuilder()
            val readerThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(outputBuilder) { outputBuilder.append(line).append('\n') }
                            appendLiveLog("$line\n")
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val errorBuilder = StringBuilder()
            val errorThread = Thread {
                runCatching {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(errorBuilder) { errorBuilder.append(line).append('\n') }
                            appendLiveLog("[stderr] $line\n")
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitForTimeoutSec(timeoutSec)
            readerThread.join(1500)
            errorThread.join(1500)
            activeInternalProcesses.remove(jobId)

            if (!finished) {
                process.destroyProcess()
                return@withContext ShellResult(
                    exitCode = 124,
                    output = "TIMEOUT (${timeoutSec}s) no shell interno.\nSaída parcial:\n$outputBuilder",
                    engineUsed = ENGINE_INTERNAL
                )
            }

            val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
            val text = buildString {
                append(outputBuilder.toString())
                if (errorBuilder.isNotBlank()) {
                    if (isNotEmpty()) append("\n--- STDERR ---\n")
                    append(errorBuilder)
                }
            }.ifBlank { "(Comando concluído sem saída textual; exit_code=$exitCode)" }
            ShellResult(exitCode = exitCode, output = text, engineUsed = ENGINE_INTERNAL)
        } catch (e: Exception) {
            ShellResult(
                exitCode = 1,
                output = "Erro no shell interno: ${e.message}",
                engineUsed = ENGINE_INTERNAL
            )
        }
    }

    // ------------------------------------------------------------------
    // Gerenciamento de processos em background
    // ------------------------------------------------------------------

    suspend fun listBackgroundJobs(): List<BackgroundJobEntity> = db.backgroundJobDao().getRunning()

    /** Verifica jobs RUNNING: internos via Process.isAlive; Termux via kill -0. Marca DONE/KILLED. */
    suspend fun pollBackgroundJobs(): List<BackgroundJobEntity> = withContext(Dispatchers.IO) {
        val running = db.backgroundJobDao().getRunning()
        for (job in running) {
            val alive = isJobAlive(job)
            if (!alive) {
                db.backgroundJobDao().finish(job.jobId, JobStatus.DONE, System.currentTimeMillis())
                appendLiveLog("\n[Job ${job.jobId} finalizado]\n")
            }
        }
        db.backgroundJobDao().getRunning()
    }

    private suspend fun isJobAlive(job: BackgroundJobEntity): Boolean {
        val pid = job.pid ?: return false
        return if (pid.startsWith("internal_")) {
            activeInternalProcesses[pid.removePrefix("internal_")]?.isProcessAlive() == true
        } else {
            val probe = bridgeMutex.withLock {
                executeViaTermuxBridge("kill -0 $pid", "/data/data/com.termux/files/home", 10, false)
            }
            probe.exitCode == 0 && !probe.bridgeFailure
        }
    }

    suspend fun killBackgroundJob(job: BackgroundJobEntity): Boolean = withContext(Dispatchers.IO) {
        val pid = job.pid ?: return@withContext false
        val killed = if (pid.startsWith("internal_")) {
            val process = activeInternalProcesses.remove(pid.removePrefix("internal_"))
            runCatching { process?.destroyProcess() }.isSuccess
        } else if (isTermuxInstalled()) {
            val result = bridgeMutex.withLock {
                executeViaTermuxBridge("kill -9 $pid", "/data/data/com.termux/files/home", 15, false)
            }
            result.exitCode == 0
        } else {
            false
        }
        db.backgroundJobDao().finish(job.jobId, JobStatus.KILLED, System.currentTimeMillis())
        appendLiveLog("\n[Job ${job.jobId} (PID $pid) encerrado]\n")
        killed
    }

    /** Cancela instantaneamente todo processo filho ativo (Stop/Interrupt da UI). */
    fun killAllChildProcesses() {
        activeInternalProcesses.values.forEach { runCatching { it.destroyProcess() } }
        activeInternalProcesses.clear()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Lê o delta do arquivo a partir do offset e envia ao terminal live. Retorna o novo offset. */
    private fun streamFileDelta(file: File, offset: Long): Long {
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= offset) return offset
        val delta = runCatching {
            file.inputStream().use { input ->
                input.skip(offset)
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrDefault("")
        if (delta.isNotEmpty()) appendLiveLog(delta)
        return length
    }

    private fun escapeBashSingleQuotes(input: String): String =
        "'" + input.replace("'", "'\"'\"'") + "'"

    /**
     * Smart truncator preservando início (30%) e fim (60%) do limite e marcando o corte.
     */
    fun truncateOutputSmartly(raw: String, limit: Int, fullLogPath: String): String {
        if (raw.length <= limit) return raw
        val headSize = (limit * 0.30).toInt()
        val tailSize = (limit * 0.60).toInt()
        val omitted = raw.length - headSize - tailSize
        return buildString {
            append(raw.take(headSize))
            append("\n\n… [TRUNCADO: $omitted caracteres omitidos para economizar contexto. " +
                "Log completo: $fullLogPath] …\n\n")
            append(raw.takeLast(tailSize))
        }
    }

    /**
     * Trava de segurança: apenas comandos de destruição em massa são bloqueados.
     */
    private fun isDangerousCommand(command: String): Boolean =
        ShellSafetyGuard.analyze(command) != null

    private suspend fun recordAuditLog(
        command: String,
        workdir: String,
        engine: String?,
        exitCode: Int,
        blocked: Boolean,
        blockReason: String?,
        durationMs: Long
    ) {
        runCatching {
            db.auditLogDao().insert(
                com.codex.agent.data.entity.AuditLogEntity(
                    sessionId = 0,
                    command = command.take(2000),
                    workdir = workdir,
                    engineUsed = engine,
                    exitCode = exitCode,
                    wasBlocked = blocked,
                    blockReason = blockReason,
                    durationMs = durationMs
                )
            )
        }
    }

    companion object {
        const val ENGINE_TERMUX = "TERMUX_BRIDGE"
        const val ENGINE_INTERNAL = "INTERNAL_SHELL"
        const val LIVE_LOG_MAX_CHARS = 40_000
    }
}

private fun Process.waitForTimeoutSec(timeoutSec: Int): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        this.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
    } else {
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSec * 1000L
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                this.exitValue()
                return true
            } catch (e: IllegalThreadStateException) {
                Thread.sleep(100)
            }
        }
        false
    }
}

private fun Process.destroyProcess() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        this.destroyForcibly()
    } else {
        this.destroy()
    }
}

private fun Process.isProcessAlive(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        this.isAlive
    } else {
        try {
            this.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }
}

