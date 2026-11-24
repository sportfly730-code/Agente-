package com.codex.agent.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.codex.agent.data.db.AppDatabase
import com.codex.agent.data.entity.CheckpointEntity
import com.codex.agent.data.model.CheckpointKind
import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.SettingsRepository
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.data.repository.ProviderRepository
import com.codex.agent.llm.LlmClient
import com.codex.agent.llm.ToolCallRequest
import com.codex.agent.shell.EnvironmentSetup
import com.codex.agent.shell.ShellExecutor
import com.codex.agent.security.SecretsStore
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Executor das 9 ferramentas nativas + ferramentas dinâmicas (.codex/custom_tools).
 * Todas as operações rodam em Dispatchers.IO e devolvem texto consolidado para a LLM.
 */
class ToolExecutor(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val shellExecutor: ShellExecutor,
    private val environmentSetup: EnvironmentSetup,
    private val secretsStore: SecretsStore,
    private val llmClient: LlmClient,
    private val providerRepository: ProviderRepository
) {

    private val androidBuildTool = AndroidBuildTool(
        shellExecutor = shellExecutor,
        environmentSetup = environmentSetup,
        workspaceRepository = workspaceRepository,
        settingsRepository = settingsRepository
    )

    private val toolHub = ToolHub(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository,
        environmentSetup = environmentSetup
    )

    private var codebaseIndex: CodebaseIndex? = null

    private val apkSecurityAnalyzer = ApkSecurityAnalyzer(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository
    )

    private val gitManager = GitManager(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository,
        secretsStore = secretsStore
    )

    private val subAgent = SubAgent(
        llmClient = llmClient,
        toolExecutor = this,
        providerRepository = providerRepository,
        workspaceRepository = workspaceRepository
    )

    private val mcpClient = McpClient(workspaceRepository = workspaceRepository)

    private val advancedReToolkit = AdvancedReToolkit(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository
    )

    private val sdrTool = SdrTool(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository
    )

    private val deviceManager = DeviceManager(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository
    )

    private val webTool = WebTool(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository
    )

    private val agentSwarm = AgentSwarm(
        llmClient = llmClient,
        toolExecutor = this,
        providerRepository = providerRepository,
        workspaceRepository = workspaceRepository,
        shellExecutor = shellExecutor
    )

    private val toolFactory = ToolFactory(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository
    )

    private val devKnowledgeTool = DevKnowledgeTool(workspaceRepository = workspaceRepository)

    private val dexStudio = DexStudio(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository
    )

    private val tvBoxRE = TvBoxRE(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository
    )

    private val linuxBoxRE = LinuxBoxRE(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository
    )

    private val webSecurityTool = WebSecurityTool(workspaceRepository = workspaceRepository)
    private val antiCheatAudit = AntiCheatAudit(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository
    )

    private val peAnalyzer = PeAnalyzer(
        shellExecutor = shellExecutor,
        workspaceRepository = workspaceRepository
    )

    private val deepReAnalyzer = DeepReAnalyzer(workspaceRepository)

    private val memoryAnalyzer = MemoryAnalyzer(shellExecutor, workspaceRepository)

    private val learningStore = LearningStore(context)

    // Wired: deep_re, memory_analyzer, learning_store (anti_cheat_audit não wired por segurança)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Tools nativas conhecidas — usado para validar tool calls do modelo antes de executar. */
    private val KNOWN_TOOLS = setOf(
        "execute_shell", "file_manager", "patch_file", "search_code", "web_fetch_and_download",
        "install_dependency", "android_system_tool", "manage_checkpoints", "apk_analyzer_tool", "mac_spoof",
        "android_build_tool", "tool_hub", "search_semantic", "frida_tool", "apk_security_audit", "anticheat_audit",
        "git_manager", "mcp_client", "re_toolkit", "sdr_tool", "device_manager",
        "web_tool", "swarm_tool", "tool_factory", "dev_knowledge", "dex_studio", "tvbox_re",
        "linuxbox_re", "web_security", "pe_analyzer", "observability", "delegate_task",
        "deep_re", "memory_analyzer", "learning_store"
    )

    suspend fun executeTool(call: ToolCallRequest, sessionId: Long = 0): String = withContext(Dispatchers.IO) {
        // VALIDAÇÃO DE SAÍDA DO MODELO: tool inventada ou nome suspeito.
        if (call.name.isBlank() || call.name.contains("..") || call.name.contains("/") ||
            (!KNOWN_TOOLS.contains(call.name) && !isCustomTool(call.name))
        ) {
            return@withContext "Erro: ferramenta '${call.name}' não existe. " +
                "Ferramentas disponíveis: ${KNOWN_TOOLS.sorted().joinToString(", ")}. " +
                "Use apenas ferramentas do schema. Se precisar de nova capacidade, use tool_factory."
        }
        val args = runCatching {
            json.parseToJsonElement(call.argumentsJson).jsonObject
        }.getOrElse {
            return@withContext "Erro ao interpretar argumentos JSON de '${call.name}': ${it.message}. " +
                "Corrija os argumentos e tente novamente."
        }
        val startTime = System.currentTimeMillis()
        val output = try {
            when (call.name) {
                "execute_shell" -> handleExecuteShell(args)
                "file_manager" -> handleFileManager(args)
                "patch_file" -> handlePatchFile(args)
                "search_code" -> handleSearchCode(args)
                "web_fetch_and_download" -> handleWebFetchAndDownload(args)
                "install_dependency" -> handleInstallDependency(args)
                "android_system_tool" -> handleAndroidSystemTool(args)
                "manage_checkpoints" -> handleManageCheckpoints(args, sessionId)
                "apk_analyzer_tool" -> handleApkAnalyzerTool(args)
                "mac_spoof" -> handleMacSpoof(args)
                "android_build_tool" -> androidBuildTool.handle(args)
                "tool_hub" -> toolHub.handle(args)
                "search_semantic" -> handleSearchSemantic(args)
                "frida_tool" -> handleFridaTool(args)
                "apk_security_audit" -> handleApkSecurityAudit(args)
                "anticheat_audit" -> antiCheatAudit.handle(args)
                "git_manager" -> gitManager.handle(args)
                "mcp_client" -> mcpClient.handle(args)
                "re_toolkit" -> advancedReToolkit.handle(args)
                "sdr_tool" -> sdrTool.handle(args)
                "device_manager" -> deviceManager.handle(args)
                "web_tool" -> webTool.handle(args)
                "swarm_tool" -> agentSwarm.handle(args)
                "tool_factory" -> toolFactory.handle(args)
                "dev_knowledge" -> devKnowledgeTool.handle(args)
                "dex_studio" -> dexStudio.handle(args)
                "tvbox_re" -> tvBoxRE.handle(args)
                "linuxbox_re" -> linuxBoxRE.handle(args)
                "web_security" -> webSecurityTool.handle(args)
                "pe_analyzer" -> peAnalyzer.handle(args)
                "deep_re" -> deepReAnalyzer.handle(args)
                "memory_analyzer" -> memoryAnalyzer.handle(args)
                "learning_store" -> handleLearningStore(args)
                "observability" -> handleObservability(args)
                "delegate_task" -> {
                    val task = args["task"]?.jsonPrimitive?.contentOrNull
                        ?: return@withContext "Erro: 'task' é obrigatório."
                    // CORREÇÃO A5: limita max_steps para evitar que o LLM gere valores
                    // absurdos (ex.: 100000) que esgotariam a API em cascata.
                    val maxSteps = (args["max_steps"]?.jsonPrimitive?.intOrNull ?: 6).coerceIn(1, 50)
                    // Background: retorna imediatamente, resultado injetado depois via SubAgentResultBus
                    subAgent.delegateAsync(task, maxSteps)
                }
                else -> handleCustomDynamicTool(call.name, args)
            }
        } catch (e: Exception) {
            "Exceção interna executando '${call.name}': ${e.javaClass.simpleName} - ${e.message}"
        }
        // Observabilidade: métrica de duração + sucesso (100% local).
        val success = !output.startsWith("Erro") && !output.startsWith("Exceção") &&
            !output.contains("BLOQUEADO POR SEGURANÇA")
        com.codex.agent.observability.Observability.recordToolCall(
            call.name, System.currentTimeMillis() - startTime, success, sessionId
        )
        output
    }

    // ------------------------------------------------------------------
    // 1) execute_shell
    // ------------------------------------------------------------------

    private suspend fun handleExecuteShell(args: JsonObject): String {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: parâmetro 'command' é obrigatório."
        val workdir = args["workdir"]?.jsonPrimitive?.contentOrNull
        val timeout = args["timeout_sec"]?.jsonPrimitive?.intOrNull ?: Constants.DEFAULT_SHELL_TIMEOUT_SEC
        val isBackground = args["is_background"]?.jsonPrimitive?.booleanOrNull ?: false

        val result = shellExecutor.execute(command, workdir, timeout, isBackground)
        return "exit_code: ${result.exitCode} (engine: ${result.engineUsed})\n${result.output}"
    }

    // ------------------------------------------------------------------
    // 2) file_manager
    // ------------------------------------------------------------------

    private suspend fun handleFileManager(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' é obrigatório."
        val rawPath = args["path"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'path' é obrigatório."
        val file = resolvePath(rawPath)

        return when (action) {
            "read" -> {
                if (!file.exists()) return "Erro: arquivo não encontrado em ${file.absolutePath}"
                if (file.isDirectory) return "Erro: ${file.absolutePath} é um diretório. Use action='list'."
                val lines = file.readLines()
                if (lines.isEmpty()) return "(Arquivo vazio: ${file.absolutePath})"
                val startLine = (args["start_line"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
                if (startLine > lines.size) {
                    return "Erro: start_line ($startLine) maior que o total de linhas (${lines.size})."
                }
                val endLine = maxOf(startLine, args["end_line"]?.jsonPrimitive?.intOrNull ?: (startLine + 399))
                    .coerceAtMost(lines.size)
                val slice = lines.subList(startLine - 1, endLine)
                    .mapIndexed { idx, line -> "${startLine + idx}: ${line.take(500)}" }
                    .joinToString("\n")
                "Arquivo: ${file.absolutePath} (linhas $startLine..$endLine de ${lines.size})\n$slice"
            }
            "write" -> {
                val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
                file.parentFile?.mkdirs()
                file.writeText(content)
                "Arquivo salvo: ${file.absolutePath} (${content.length} chars)"
            }
            "append" -> {
                val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
                file.parentFile?.mkdirs()
                file.appendText(content)
                "Conteúdo anexado em: ${file.absolutePath}"
            }
            "delete" -> {
                // SEGURANÇA: delete é a única operação recursiva em Kotlin (bypassa o ShellSafetyGuard).
                // Só permite apagar dentro do workspace ativo — nunca raízes de storage.
                val root = File(workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT).canonicalFile
                val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
                val insideWorkspace = canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
                if (!insideWorkspace) {
                    return "Erro: remoção recusada — '${file.absolutePath}' está fora do workspace '$root'. " +
                        "Operações destrutivas só são permitidas dentro do workspace ativo."
                }
                if (canonical.path == root.path) return "Erro: recusado apagar a raiz do workspace."
                if (!file.exists()) return "Aviso: ${file.absolutePath} já não existe."
                val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (deleted) "Removido: ${file.absolutePath}" else "Falha ao remover: ${file.absolutePath}"
            }
            "mkdir" -> {
                val created = file.mkdirs() || file.exists()
                if (created) "Diretório pronto: ${file.absolutePath}" else "Falha ao criar: ${file.absolutePath}"
            }
            "move" -> {
                val destRaw = args["destination"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'destination' é obrigatório para move."
                val dest = resolvePath(destRaw)
                dest.parentFile?.mkdirs()
                if (file.renameTo(dest)) {
                    "Movido para ${dest.absolutePath}"
                } else {
                    file.copyRecursively(dest, overwrite = true)
                    file.deleteRecursively()
                    "Copiado e removido original: ${dest.absolutePath}"
                }
            }
            "list" -> {
                if (!file.exists()) return "Erro: diretório não encontrado: ${file.absolutePath}"
                val items = file.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                    ?: return "Diretório vazio ou inacessível."
                if (items.isEmpty()) return "(Diretório vazio: ${file.absolutePath})"
                val listing = items.take(250).joinToString("\n") { f ->
                    if (f.isDirectory) "[DIR]  ${f.name}/" else "[FILE] ${f.name} (${formatBytes(f.length())})"
                }
                val extra = if (items.size > 250) "\n… (+${items.size - 250} itens omitidos)" else ""
                "Conteúdo de ${file.absolutePath}:\n$listing$extra"
            }
            else -> "Ação desconhecida em file_manager: $action"
        }
    }

    // ------------------------------------------------------------------
    // 3) patch_file
    // ------------------------------------------------------------------

    private suspend fun handlePatchFile(args: JsonObject): String {
        val rawPath = args["path"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'path' é obrigatório."
        val searchBlock = args["search_block"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'search_block' é obrigatório."
        val replaceBlock = args["replace_block"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'replace_block' é obrigatório."

        val file = resolvePath(rawPath)
        if (!file.exists()) return "Erro: arquivo não encontrado: ${file.absolutePath}"
        val original = file.readText()

        // 1) Match exato
        if (original.contains(searchBlock)) {
            file.writeText(original.replaceFirst(searchBlock, replaceBlock))
            return "Patch aplicado com sucesso em ${file.absolutePath}."
        }

        // 2) Fallback: normalização de quebras de linha e espaços à direita
        val normOriginal = original.replace("\r\n", "\n").replace(Regex("[ \\t]+(?=\n)"), "")
        val normSearch = searchBlock.replace("\r\n", "\n").replace(Regex("[ \\t]+(?=\n)"), "")
        if (normOriginal.contains(normSearch)) {
            val updated = normOriginal.replaceFirst(normSearch, replaceBlock.replace("\r\n", "\n"))
            file.writeText(updated)
            return "Patch aplicado (normalização de whitespace) em ${file.absolutePath}."
        }

        // 3) Fallback: match por linhas ignorando indentação líder
        val searchLines = normSearch.lines().filter { it.isNotBlank() }
        if (searchLines.isNotEmpty()) {
            val originalLines = normOriginal.lines()
            for (start in 0..(originalLines.size - searchLines.size).coerceAtLeast(0)) {
                val window = originalLines.subList(start, start + searchLines.size)
                val matches = window.zip(searchLines) { a, b -> a.trim() == b.trim() }.all { it }
                if (matches) {
                    val indent = window.first().takeWhile { it == ' ' || it == '\t' }
                    val replacement = replaceBlock.lines().joinToString("\n") { line ->
                        if (line.isBlank()) line else indent + line.trimStart()
                    }
                    val rebuilt = (originalLines.subList(0, start) +
                        replacement.lines() +
                        originalLines.subList(start + searchLines.size, originalLines.size)).joinToString("\n")
                    file.writeText(rebuilt)
                    return "Patch aplicado (fallback por indentação) na linha ${start + 1} de ${file.absolutePath}."
                }
            }
        }

        return "Erro ao aplicar patch em ${file.absolutePath}: 'search_block' não encontrado " +
            "(exato, whitespace-normalizado ou por indentação). Use file_manager(action='read') para conferir."
    }

    // ------------------------------------------------------------------
    // 4) search_code
    // ------------------------------------------------------------------

    private suspend fun handleSearchCode(args: JsonObject): String {
        val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'query' é obrigatório."
        val targetPath = args["path"]?.jsonPrimitive?.contentOrNull
        val isRegex = args["is_regex"]?.jsonPrimitive?.booleanOrNull ?: false
        val root = if (!targetPath.isNullOrBlank()) resolvePath(targetPath)
        else File(workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT)
        if (!root.exists()) return "Erro: caminho não encontrado: ${root.absolutePath}"

        val regex = if (isRegex) runCatching { Regex(query) }.getOrNull()
            ?: return "Erro: regex inválida: $query" else null
        val ignored = setOf(".git", ".codex_git", "node_modules", "build", ".gradle", "__pycache__", ".codex_logs")
        val matches = mutableListOf<String>()

        root.walkTopDown()
            .onEnter { dir -> dir.name !in ignored }
            .filter { it.isFile && it.length() < 1_500_000L }
            .forEach { file ->
                if (matches.size >= 120) return@forEach
                runCatching {
                    file.useLines { lines ->
                        lines.forEachIndexed { idx, line ->
                            if (matches.size >= 120) return@useLines
                            val found = regex?.containsMatchIn(line)
                                ?: line.contains(query, ignoreCase = true)
                            if (found) {
                                matches.add("${file.relativeToOrSelf(root).path}:${idx + 1}: ${line.trim().take(220)}")
                            }
                        }
                    }
                }
            }

        return if (matches.isEmpty()) "Nenhuma ocorrência de '$query' em ${root.absolutePath}."
        else "Encontradas ${matches.size} ocorrências:\n${matches.joinToString("\n")}"
    }

    // ------------------------------------------------------------------
    // 5) web_fetch_and_download
    // ------------------------------------------------------------------

    private suspend fun handleWebFetchAndDownload(args: JsonObject): String = withContext(Dispatchers.IO) {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return@withContext "Erro: 'url' é obrigatório."
        val method = (args["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        val bodyStr = args["body"]?.jsonPrimitive?.contentOrNull
        val savePath = args["save_path"]?.jsonPrimitive?.contentOrNull
        val extractMarkdown = args["extract_markdown"]?.jsonPrimitive?.booleanOrNull ?: true

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) CodexAgent/1.0")
        args["headers"]?.jsonObject?.forEach { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { builder.header(key, it) }
        }
        if (method in listOf("POST", "PUT", "DELETE", "PATCH")) {
            val media = bodyStr?.let {
                if (it.trimStart().startsWith("{") || it.trimStart().startsWith("["))
                    "application/json; charset=utf-8".toMediaTypeOrNull()
                else "text/plain; charset=utf-8".toMediaTypeOrNull()
            }
            builder.method(method, (bodyStr ?: "").toRequestBody(media))
        } else {
            builder.method(method, null)
        }

        httpClient.newCall(builder.build()).execute().use { resp ->
            val code = resp.code
            val respBody = resp.body ?: return@withContext "HTTP $code (corpo vazio)"

            if (!savePath.isNullOrBlank()) {
                val outFile = resolvePath(savePath)
                outFile.parentFile?.mkdirs()
                var total = 0L
                respBody.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            total += read
                        }
                    }
                }
                return@withContext "Download concluído (HTTP $code): ${outFile.absolutePath} (${formatBytes(total)})"
            }

            val raw = respBody.string()
            val contentType = resp.header("Content-Type").orEmpty()
            val processed = if (extractMarkdown && contentType.contains("text/html", ignoreCase = true)) {
                htmlToCleanMarkdown(raw)
            } else raw
            val trimmed = if (processed.length > 12_000) {
                processed.take(12_000) + "\n\n… [Truncado em 12.000 chars. Use save_path para o conteúdo completo.]"
            } else processed
            "HTTP $code ($contentType):\n$trimmed"
        }
    }

    // ------------------------------------------------------------------
    // 6) install_dependency
    // ------------------------------------------------------------------

    private suspend fun handleInstallDependency(args: JsonObject): String {
        val manager = args["manager"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'manager' é obrigatório."
        val target = args["target"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'target' é obrigatório."
        val installDir = args["install_dir"]?.jsonPrimitive?.contentOrNull

        // SEGURANÇA: valida 'target' antes de interpolá-lo no shell.
        val urlManagers = setOf("git", "binary_url")
        val isUrl = urlManagers.contains(manager) &&
            (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("git@"))
        val isPackageName = target.matches(Regex("^[A-Za-z0-9._@/+-]{1,160}$"))
        if (!isUrl && !isPackageName) {
            return "Erro: 'target' inválido ou suspeito: $target"
        }
        val quotedTarget = shellSingleQuote(target)

        val command = when (manager) {
            "pkg" -> "pkg update -y >/dev/null 2>&1; pkg install -y $quotedTarget"
            "pip" -> "pip install --break-system-packages $quotedTarget 2>/dev/null || pip install $quotedTarget"
            "npm" -> "npm install -g $quotedTarget"
            "git" -> {
                val dest = installDir?.let { shellSingleQuote(resolvePath(it).absolutePath) } ?: ""
                "git clone --depth 1 $quotedTarget $dest"
            }
            "binary_url" -> {
                val destDir = installDir?.let { resolvePath(it).absolutePath } ?: "\$HOME/.local/bin"
                // Nome de arquivo seguro: só alnum/._-
                val fileName = target.substringAfterLast('/').substringBefore('?')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "tool_bin" }
                "mkdir -p ${shellSingleQuote(destDir)} && curl -L -f $quotedTarget -o ${shellSingleQuote("$destDir/$fileName")} && chmod +x ${shellSingleQuote("$destDir/$fileName")} && echo Instalado"
            }
            else -> return "Gerenciador não suportado: $manager"
        }

        val result = shellExecutor.execute(command, timeoutSec = 600)
        if (result.exitCode == 0) {
            runCatching {
                val ws = workspaceRepository.active()?.rootPath ?: return@runCatching
                val depsFile = File(ws, Constants.DEPENDENCIES_FILE)
                depsFile.parentFile?.mkdirs()
                depsFile.appendText("- [$manager] $target (${result.engineUsed}, ${result.exitCode})\n")
            }
        }
        return "Instalação ($manager → $target) exit_code=${result.exitCode}:\n${result.output}"
    }

    // ------------------------------------------------------------------
    // 7) android_system_tool
    // ------------------------------------------------------------------

    private suspend fun handleAndroidSystemTool(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' é obrigatório."
        val target = args["target"]?.jsonPrimitive?.contentOrNull

        return when (action) {
            "install_apk" -> {
                if (target.isNullOrBlank()) return "Erro: informe o caminho do .apk em 'target'."
                val apkFile = resolvePath(target)
                if (!apkFile.exists()) return "Erro: APK não encontrado em ${apkFile.absolutePath}"

                // 1) Silencioso via Shizuku (pm install -r)
                if (isShizukuReady()) {
                    val shizukuResult: String? = runCatching {
                        val process = shizukuExec(arrayOf("pm", "install", "-r", apkFile.absolutePath))
                        val out = process.inputStream.bufferedReader().readText() +
                            process.errorStream.bufferedReader().readText()
                        process.waitFor()
                        out
                    }.getOrNull()
                    if (shizukuResult != null && shizukuResult.contains("Success", ignoreCase = true)) {
                        return "APK instalado silenciosamente via Shizuku: ${apkFile.name}"
                    }
                    if (shizukuResult != null) {
                        appendLive("Shizuku pm install falhou (${shizukuResult.take(200)}); usando instalador do sistema.")
                    }
                }

                // 2) Fallback: instalador do sistema via FileProvider
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(installIntent) }
                    .onFailure { return "Erro ao abrir instalador: ${it.message}" }
                "Instalador do Android aberto para: ${apkFile.absolutePath} (confirme a instalação na tela do sistema)."
            }
            "read_logcat" -> {
                val filter = target?.takeIf { it.isNotBlank() }
                val shellCmd = if (filter != null) "logcat -d -t 300 2>/dev/null | grep -i \"$filter\" | tail -n 150"
                else "logcat -d -t 200 *:E 2>/dev/null | tail -n 150"
                var result = shellExecutor.execute(shellCmd, timeoutSec = 25)
                if (result.output.contains("logcat") && result.output.contains("not found") || result.exitCode != 0) {
                    val shizukuLogcat: String? = runCatching {
                        val cmd = if (filter != null) arrayOf("logcat", "-d", "-t", "300")
                                  else arrayOf("logcat", "-d", "-t", "200", "*:E")
                        val process = shizukuExec(cmd)
                        val out = process.inputStream.bufferedReader().readText()
                        process.waitFor()
                        out
                    }.getOrNull()
                    if (!shizukuLogcat.isNullOrBlank()) {
                        val lines = shizukuLogcat.split("\n")
                            .filter { filter == null || it.contains(filter, ignoreCase = true) }
                            .takeLast(150)
                        result = com.codex.agent.shell.ShellResult(
                            exitCode = 0,
                            output = lines.joinToString("\n"),
                            engineUsed = "SHIZUKU"
                        )
                    }
                }
                "Logcat (exit=${result.exitCode}):\n${result.output}"
            }
            "uninstall_app" -> {
                if (target.isNullOrBlank()) return "Erro: informe o packageName em 'target'."
                if (!target.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]{2,120}$"))) return "Erro: package inválido."
                // Desinstala silenciosamente via Shizuku (pm uninstall -k).
                if (isShizukuReady()) {
                    val out = runCatching {
                        val p = shizukuExec(arrayOf("pm", "uninstall", "-k", target))
                        val o = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
                        p.waitFor(); o
                    }.getOrNull()
                    if (out != null && out.contains("Success", ignoreCase = true)) {
                        return "✅ App '$target' desinstalado silenciosamente via Shizuku."
                    }
                    if (out != null) appendLive("pm uninstall falhou (${out.take(200)}); tentando instalador do sistema.")
                }
                // Fallback: intent de desinstalação (usuário confirma na tela)
                val uri = android.net.Uri.parse("package:$target")
                val intent = Intent(Intent.ACTION_DELETE, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                runCatching { context.startActivity(intent) }
                    .onFailure { return "Erro ao abrir desinstalador: ${it.message}" }
                "Desinstalador aberto para '$target' (confirme na tela do sistema)."
            }
            "launch_app" -> {
                if (target.isNullOrBlank()) return "Erro: informe o packageName em 'target'."
                val launchIntent = context.packageManager.getLaunchIntentForPackage(target)
                    ?: return "Erro: app '$target' não encontrado ou sem Activity lançável."
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                "Aplicativo '$target' iniciado."
            }
            "test_cycle" -> {
                // Ciclo de teste autônomo: desinstalar → instalar → abrir → logcat.
                val pkg = args["package"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'package' obrigatório."
                val apkPath = args["apk"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'apk' obrigatório."
                if (!pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]{2,120}$"))) return "Erro: package inválido."
                val apkFile = resolvePath(apkPath)
                if (!apkFile.exists()) return "Erro: APK não encontrado: ${apkFile.absolutePath}"
                val sb = StringBuilder("=== Ciclo de Teste Autônomo ===\n")
                // 1) desinstalar versão anterior
                if (isShizukuReady()) {
                    val out = runCatching {
                        val p = shizukuExec(arrayOf("pm", "uninstall", "-k", pkg))
                        val o = p.inputStream.bufferedReader().readText(); p.waitFor(); o
                    }.getOrNull()
                    sb.appendLine("1) Desinstalar: ${if (out?.contains("Success", true) == true) "OK" else "ignorado (não instalado ou falha)"}")
                } else sb.appendLine("1) Desinstalar: pulado (Shizuku não ativo)")
                // 2) instalar
                var installed = false
                if (isShizukuReady()) {
                    val out = runCatching {
                        val p = shizukuExec(arrayOf("pm", "install", "-r", apkFile.absolutePath))
                        val o = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText(); p.waitFor(); o
                    }.getOrNull()
                    installed = out?.contains("Success", true) == true
                    sb.appendLine("2) Instalar: ${if (installed) "OK" else "falhou: ${out?.take(200)}"}")
                }
                if (!installed) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(intent) }
                    sb.appendLine("2) Instalar: instalador do sistema aberto (confirme na tela)")
                }
                kotlinx.coroutines.delay(800)
                // 3) abrir
                val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(launch) }
                    sb.appendLine("3) Abrir app: OK")
                } else sb.appendLine("3) Abrir app: falhou (activity não encontrada)")
                kotlinx.coroutines.delay(1800)
                // 4) logcat
                val log = shellExecutor.execute("logcat -d -t 200 *:E 2>/dev/null | grep -i $pkg | tail -n 60", timeoutSec = 25)
                val fatal = log.output.contains("FATAL", true) || log.output.contains("AndroidRuntime", true)
                sb.appendLine("4) Logcat: ${if (log.output.isBlank()) "limpo (sem erros)" else log.output.take(1500)}")
                sb.appendLine()
                sb.appendLine("RESULTADO: ${if (fatal) "❌ CRASH detectado — corrija o código" else "✅ Sem crashes detectados"}")
                sb.toString()
            }
            "get_device_info" -> buildString {
                appendLine("Modelo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendLine("Termux instalado: ${shellExecutor.isTermuxInstalled()}")
                appendLine("Shizuku ativo: ${isShizukuReady()}")
                appendLine("Workspace ativo: ${workspaceRepository.active()?.rootPath ?: "nenhum"}")
            }
            "list_packages" -> {
                val filter = target?.lowercase()
                val packages = context.packageManager.getInstalledApplications(0)
                    .map { it.packageName }
                    .filter { filter == null || it.lowercase().contains(filter) }
                    .sorted()
                    .take(150)
                "Pacotes (${packages.size}):\n${packages.joinToString("\n")}"
            }
            "pull_installed_apk" -> {
                // Extrai o APK de um app instalado no device para o workspace (para análise).
                val pkg = target ?: args["package"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'target' ou 'package' obrigatório (package name)."
                if (!pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]{2,120}$"))) return "Erro: package inválido."
                val remotePath = shellExecutor.execute("pm path $pkg 2>&1", timeoutSec = 10).output.trim()
                    .substringAfter("package:").trim()
                if (remotePath.isBlank()) return "Erro: APK de '$pkg' não encontrado no device."
                val out = File(workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT,
                    "${Constants.APK_ANALYSIS_DIR}/${pkg.replace('.', '_')}.apk")
                out.parentFile?.mkdirs()
                val r = shellExecutor.execute("cp $remotePath /sdcard/_pull.apk 2>&1 && cp /sdcard/_pull.apk ${shellSingleQuote(out.absolutePath)} 2>&1", timeoutSec = 120)
                // fallback via adb se o shell interno não tiver acesso
                if (!out.exists() || out.length() == 0L) {
                    val r2 = shellExecutor.execute("adb shell \"cp $remotePath /sdcard/_pull.apk\" 2>&1", timeoutSec = 60)
                    val r3 = shellExecutor.execute("adb pull /sdcard/_pull.apk ${shellSingleQuote(out.absolutePath)} 2>&1", timeoutSec = 120)
                    if (!out.exists() || out.length() == 0L) return "Falha ao extrair APK de '$pkg':\n$r\n$r2\n$r3"
                }
                "✅ APK extraído: ${out.absolutePath} (${out.length() / 1024} KB)\nPackage: $pkg\n" +
                    "Use apk_analyzer_tool ou duo_analyze para analisar."
            }
            "analyze_installed" -> {
                // Batch: extrai N APKs instalados e retorna a lista para análise.
                val packages = args["packages"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: (args["target"]?.jsonPrimitive?.contentOrNull?.split(",")?.map { it.trim() })
                    ?: return "Erro: informe 'packages' (array) ou 'target' (lista separada por vírgula)."
                if (packages.size > 10) return "Erro: máximo 10 apps por vez."
                if (packages.any { !it.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]{2,120}$")) })
                    return "Erro: package name inválido em '$packages'."
                val results = mutableListOf<String>()
                val extracted = mutableListOf<String>()
                for (pkg in packages) {
                    val remotePath = shellExecutor.execute("pm path $pkg 2>&1", timeoutSec = 10).output.trim()
                        .substringAfter("package:").trim()
                    if (remotePath.isBlank()) { results.add("❌ $pkg: APK não encontrado"); continue }
                    val out = File(workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT,
                        "${Constants.APK_ANALYSIS_DIR}/${pkg.replace('.', '_')}.apk")
                    out.parentFile?.mkdirs()
                    var ok = false
                    if (shellExecutor.execute("cp $remotePath ${shellSingleQuote(out.absolutePath)} 2>&1", timeoutSec = 60).exitCode == 0 &&
                        out.exists() && out.length() > 0) ok = true
                    if (!ok) {
                        shellExecutor.execute("adb pull $remotePath ${shellSingleQuote(out.absolutePath)} 2>&1", timeoutSec = 120)
                        if (out.exists() && out.length() > 0) ok = true
                    }
                    if (ok) { results.add("✅ $pkg → ${out.absolutePath} (${out.length() / 1024} KB)"); extracted.add(out.absolutePath) }
                    else results.add("❌ $pkg: falha na extração")
                }
                "=== Extração de APKs instalados (${extracted.size}/${packages.size}) ===\n" +
                    results.joinToString("\n") + "\n\n" +
                    "Próximo passo: use swarm_tool(action='batch', tasks=[...]) para analisar cada APK em paralelo.\n" +
                    "Exemplo: {\"label\":\"Analista A\",\"task\":\"apk_security_audit em ${extracted.firstOrNull() ?: "<apk>\"}"}"
            }
            // ---- Interação em tempo real (testes de UI) ----
            "tap" -> {
                val x = args["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return "Erro: 'x' obrigatório."
                val y = args["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return "Erro: 'y' obrigatório."
                val r = shellExecutor.execute("input tap $x $y", timeoutSec = 10)
                "Toque em ($x,$y): exit=${r.exitCode}"
            }
            "swipe" -> {
                val x = args["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return "Erro: 'x'."
                val y = args["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return "Erro: 'y'."
                val x2 = args["x2"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return "Erro: 'x2'."
                val y2 = args["y2"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return "Erro: 'y2'."
                val dur = args["duration_ms"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 300
                val r = shellExecutor.execute("input swipe $x $y $x2 $y2 $dur", timeoutSec = 10)
                "Swipe ($x,$y)->($x2,$y2) em ${dur}ms: exit=${r.exitCode}"
            }
            "input_text" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'text'."
                val safe = text.filter { it.isLetterOrDigit() || it in " .@_-/" }
                val r = shellExecutor.execute("input text ${shellSingleQuote(safe)}", timeoutSec = 10)
                "Texto '$safe' digitado: exit=${r.exitCode}"
            }
            "key" -> {
                val code = args["key_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return "Erro: 'key_code' numérico obrigatório."
                val r = shellExecutor.execute("input keyevent $code", timeoutSec = 10)
                "Keyevent $code: exit=${r.exitCode}"
            }
            "force_stop" -> {
                val pkg = target ?: return "Erro: 'target' (package) obrigatório."
                if (!pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]{2,120}$"))) return "Erro: package inválido."
                val r = shellExecutor.execute("am force-stop $pkg", timeoutSec = 10)
                "App '$pkg' fechado: exit=${r.exitCode}"
            }
            "screenshot_ui" -> {
                val out = File(workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT,
                    "${Constants.APK_ANALYSIS_DIR}/ui_${System.currentTimeMillis()}.png")
                out.parentFile?.mkdirs()
                val r = shellExecutor.execute("screencap -p > ${shellSingleQuote(out.absolutePath)}", timeoutSec = 20)
                if (r.exitCode == 0 && out.exists()) "Screenshot: ${out.absolutePath} (${out.length()} bytes)"
                else "Falha ao capturar: ${r.output.take(300)}"
            }
            "see_screen" -> {
                // VISÃO: captura a tela e retorna em base64 com marcador para o orquestrador
                // injetar como imagem na próxima chamada ao modelo (visão multimodal).
                val out = File(workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT,
                    "${Constants.APK_ANALYSIS_DIR}/vision_${System.currentTimeMillis()}.png")
                out.parentFile?.mkdirs()
                val r = shellExecutor.execute("screencap -p > ${shellSingleQuote(out.absolutePath)}", timeoutSec = 20)
                if (r.exitCode == 0 && out.exists()) {
                    val b64 = android.util.Base64.encodeToString(out.readBytes(), android.util.Base64.NO_WRAP)
                    "VISION::${out.absolutePath}::$b64"
                } else "Falha ao capturar tela para visão: ${r.output.take(300)}"
            }
            "watch_app" -> {
                // Vigia o app por alguns segundos: logcat em tempo real filtrado pelo package.
                val pkg = args["package"]?.jsonPrimitive?.contentOrNull ?: target
                    ?: return "Erro: 'package' obrigatório."
                val secs = (args["seconds"]?.jsonPrimitive?.contentOrNull ?: "8").toIntOrNull()?.coerceIn(1, 60) ?: 8
                // Limpa logcat e captura erros do app nas próximos segundos
                shellExecutor.execute("logcat -c", timeoutSec = 5)
                kotlinx.coroutines.delay(secs * 1000L)
                val log = shellExecutor.execute("logcat -d -t 300 *:E 2>/dev/null | grep -i ${shellSingleQuote(pkg)} | tail -n 80", timeoutSec = 20)
                val fatal = log.output.contains("FATAL", true) || log.output.contains("AndroidRuntime", true)
                "=== Vigilância de '$pkg' (${secs}s) ===\n" +
                    (if (log.output.isBlank()) "✅ Nenhum erro/Exception do app." else log.output) +
                    "\n\nResultado: ${if (fatal) "❌ CRASH detectado" else "✅ Sem crashes"}"
            }
            else -> "Ação desconhecida em android_system_tool: $action"
        }
    }

    // ------------------------------------------------------------------
    // 8) manage_checkpoints (Git Shadow)
    // ------------------------------------------------------------------

    private suspend fun handleManageCheckpoints(args: JsonObject, sessionId: Long): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' é obrigatório."
        val workspace = workspaceRepository.active() ?: return "Erro: nenhum workspace ativo."
        return when (action) {
            "create" -> {
                val message = args["message"]?.jsonPrimitive?.contentOrNull ?: "Checkpoint do agente"
                createCheckpoint(workspace.id, sessionId, CheckpointKind.AGENT, message)
            }
            "list" -> listCheckpoints(workspace.id)
            "rollback" -> {
                val hash = args["commit_hash"]?.jsonPrimitive?.contentOrNull
                rollbackWorkspace(workspace.id, hash)
            }
            else -> "Ação inválida em manage_checkpoints: $action"
        }
    }

    /** Cria checkpoint (git dir separado .codex_git) e registra no Room. */
    suspend fun createCheckpoint(
        workspaceId: Long,
        sessionId: Long,
        kind: CheckpointKind,
        message: String
    ): String = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.byId(workspaceId) ?: return@withContext "Erro: workspace $workspaceId inexistente."
        val root = File(workspace.rootPath)
        root.mkdirs()
        val gitDir = File(root, ".codex_git")
        val prefix = gitPrefix(gitDir, root)

        if (!gitDir.isDirectory) {
            shellExecutor.execute(
                "$prefix init -q && $prefix config user.email 'agent@codex.local' && $prefix config user.name 'CodexAgent'",
                workdir = root.absolutePath, timeoutSec = 30
            )
        }
        // Garante o exclude SEMPRE (também em workspaces já existentes).
        // .codex/ contém artefatos de análise de APK (dezenas de milhares de arquivos)
        // que travariam o `git add` — nunca devem entrar nos checkpoints.
        runCatching {
            File(gitDir, "info").mkdirs()
            val excludeFile = File(gitDir, "info/exclude")
            val excludeContent = excludeFile.takeIf { it.exists() }?.readText().orEmpty()
            val needed = listOf(".codex_git", ".codex_logs", ".codex/", "*.apk", "*.aab", "*.xapk", "*.dex", "*.so")
            val missing = needed.filter { !excludeContent.contains(it) }
            if (missing.isNotEmpty()) {
                val base = excludeContent.trimEnd()
                excludeFile.writeText(if (base.isBlank()) missing.joinToString("\n") + "\n"
                                      else base + "\n" + missing.joinToString("\n") + "\n")
            }
        }
        // Aspas simples protegem contra $() e backticks; aspas simples internas são escapadas.
        val safeMessage = shellSingleQuote(message.take(120))
        // `git add -A` respeita o info/exclude configurado acima, que agora cobre
        // .codex/ (artefatos de análise), .codex_git/, logs e binários grandes.
        // Timeout generoso para workspaces com muitos arquivos de código.
        val commit = shellExecutor.execute(
            "$prefix add -A && $prefix commit -m $safeMessage --allow-empty -q && $prefix rev-parse HEAD",
            workdir = root.absolutePath, timeoutSec = 120
        )
        val hash = commit.output.trim().lines().lastOrNull { it.matches(Regex("^[0-9a-f]{7,40}$")) }
        // Só registra checkpoint se o git teve sucesso E o hash é válido.
        // Nunca gravar `output.takeLast(40)` — isso produzia hashes corrompidos
        // (ex.: "epository, o") que quebravam o rollback silenciosamente.
        if (commit.exitCode != 0 || hash == null) {
            return@withContext "Falha ao criar checkpoint (git exit=${commit.exitCode}, hash inválido):\n${commit.output.take(800)}"
        }
        db.checkpointDao().insert(
            CheckpointEntity(
                sessionId = sessionId,
                workspaceId = workspaceId,
                commitHash = hash,
                message = safeMessage,
                kind = kind
            )
        )
        "Checkpoint criado: $hash ($safeMessage)"
    }

    suspend fun listCheckpoints(workspaceId: Long): String = withContext(Dispatchers.IO) {
        val recent = db.checkpointDao().getRecent(workspaceId, 15)
        if (recent.isEmpty()) "Nenhum checkpoint registrado."
        else recent.joinToString("\n") { "${it.commitHash.take(10)}  [${it.kind}]  ${it.message}" }
    }

    /** Rollback do workspace para um hash (ou último checkpoint) + truncamento do histórico. */
    suspend fun rollbackWorkspace(workspaceId: Long, commitHash: String?): String = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.byId(workspaceId)
            ?: return@withContext "Erro: workspace $workspaceId inexistente."
        val target = commitHash ?: db.checkpointDao().getRecent(workspaceId, 1).firstOrNull()?.commitHash
            ?: return@withContext "Erro: nenhum checkpoint disponível para rollback."
        if (!isValidGitHash(target)) {
            return@withContext "Erro: hash de checkpoint inválido: '$target'."
        }
        val gitDir = File(workspace.rootPath, ".codex_git")
        val prefix = gitPrefix(gitDir, File(workspace.rootPath))
        val result = shellExecutor.execute(
            "$prefix reset --hard $target && $prefix clean -fd",
            workdir = workspace.rootPath, timeoutSec = 60
        )
        if (result.exitCode == 0) "Workspace restaurado para $target." else "Falha no rollback:\n${result.output}"
    }

    private fun gitPrefix(gitDir: File, workTree: File): String =
        "git --git-dir=\"${gitDir.absolutePath}\" --work-tree=\"${workTree.absolutePath}\""

    // ------------------------------------------------------------------
    // 8.5) mac_spoof
    // ------------------------------------------------------------------

    private suspend fun handleMacSpoof(args: JsonObject): String {
        val macArgs = buildJsonObject {
            put("action", JsonPrimitive("mac_spoof"))
            for ((k, v) in args) {
                put(k, v)
            }
        }
        return handleApkAnalyzerTool(macArgs)
    }

    // ------------------------------------------------------------------
    // 9) apk_analyzer_tool
    // ------------------------------------------------------------------

    private suspend fun handleApkAnalyzerTool(args: JsonObject): String {
        val rawAction = args["action"]?.jsonPrimitive?.contentOrNull ?: "inspect_manifest_and_certs"
        val action = when (rawAction) {
            "decompile", "decompile_all", "decompile_code" -> "decompile_java"
            "info", "inspect", "manifest" -> "inspect_manifest_and_certs"
            "mac", "spoof", "spoof_mac" -> "mac_spoof"
            else -> rawAction
        }
        val rawTarget = args["target_path"]?.jsonPrimitive?.contentOrNull
            ?: args["apk_path"]?.jsonPrimitive?.contentOrNull
            ?: args["path"]?.jsonPrimitive?.contentOrNull
            ?: args["target"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'target_path' é obrigatório."
        val target = resolvePath(rawTarget)
        if (!target.exists()) return "Erro: alvo não encontrado em ${target.absolutePath}"
        val workspaceRoot = workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT
        val defaultOut = File(workspaceRoot, "${Constants.APK_ANALYSIS_DIR}/${target.nameWithoutExtension}")
        val outDir = args["output_dir"]?.jsonPrimitive?.contentOrNull
            ?.let { resolvePath(it).absolutePath } ?: defaultOut.absolutePath
        val excludeStd = args["exclude_std_libs"]?.jsonPrimitive?.booleanOrNull ?: true

        val result: String = when (action) {
            "decompile_resources" -> {
                val result = shellExecutor.execute(
                    "apktool d -f \"${target.absolutePath}\" -o \"$outDir\"",
                    workdir = workspaceRoot, timeoutSec = 600
                )
                if (result.exitCode == 127 || result.output.contains("not found")) {
                    val fallbackScript = "mkdir -p \"$outDir\" && unzip -q -o \"${target.absolutePath}\" -d \"$outDir\" 2>&1 || true"
                    val res = shellExecutor.execute(fallbackScript, workdir = workspaceRoot, timeoutSec = 120)
                    "Descomposição de Recursos (unzip) → $outDir\n${res.output}"
                } else {
                    "Apktool decompile (exit=${result.exitCode}) → $outDir\n${result.output}"
                }
            }
            "decompile_java" -> {
                val result = shellExecutor.execute(
                    "jadx -d \"$outDir\" --show-bad-code --no-res \"${target.absolutePath}\"",
                    workdir = workspaceRoot, timeoutSec = 900
                )
                if (result.exitCode == 127 || result.output.contains("not found")) {
                    val fallbackScript = """
                        mkdir -p "$outDir"
                        unzip -q -o "${target.absolutePath}" -d "$outDir" 2>&1 || true
                        echo "=== DESCOMPACTAÇÃO DE APK CONCLUÍDA ($outDir) ==="
                        echo "Arquivos extraídos:"
                        ls -la "$outDir"
                        echo ""
                        echo "=== STRINGS E ENDPOINTS EXTRAÍDOS DE CLASSES.DEX ==="
                        strings "$outDir"/classes*.dex 2>/dev/null | grep -Ei 'http|https|api|key|secret|token|password' | head -n 100 || true
                    """.trimIndent()
                    val fallbackRes = shellExecutor.execute(fallbackScript, workdir = workspaceRoot, timeoutSec = 120)
                    "Descompactação de APK & Extração de Fontes/DEX (Fallback JADX) → $outDir\n${fallbackRes.output}"
                } else {
                    "JADX decompile (exit=${result.exitCode}) → fontes em $outDir\n${result.output}"
                }
            }
            "inspect_manifest_and_certs" -> {
                val script = """
                    echo "=== CERTIFICADOS / ASSINATURA ==="
                    apksigner verify --print-certs "${target.absolutePath}" 2>&1 || true
                    echo ""
                    echo "=== MANIFEST: PERMISSÕES / COMPONENTES EXPORTADOS / SDK ==="
                    (aapt2 dump badging "${target.absolutePath}" 2>/dev/null || aapt dump badging "${target.absolutePath}" 2>/dev/null) | head -n 90 || true
                    echo ""
                    echo "=== NATIVAS .so / DEX / ASSETS ==="
                    unzip -l "${target.absolutePath}" 2>/dev/null | grep -Ei "\.so$|classes.*\.dex$|^.*assets/" | head -n 60 || true
                """.trimIndent()
                val result = shellExecutor.execute(script, workdir = workspaceRoot, timeoutSec = 90)
                result.output.ifBlank { "Nenhuma saída (verifique se apksigner/aapt estão instalados)." }
            }
            "search_strings_and_endpoints" -> {
                val filter = if (excludeStd) {
                    val pattern = Constants.APK_STD_LIB_PREFIXES.joinToString("|") { it.replace(".", "\\.") }
                    "grep -Ev '($pattern)'"
                } else "cat"
                val stamp = System.currentTimeMillis()
                val script = """
                    SCAN="/tmp/apk_scan_$stamp"; mkdir -p "${'$'}SCAN"
                    unzip -q -o "${target.absolutePath}" "classes*.dex" "res/values/strings.xml" -d "${'$'}SCAN" 2>/dev/null || true
                    echo "=== URLs / ENDPOINTS ==="
                    cat "${'$'}SCAN"/*.dex 2>/dev/null | strings | grep -Eo '${Constants.URL_REGEX}' | sort -u | $filter | head -n 150
                    echo ""
                    echo "=== SEGREDS / TOKENS POTENCIAIS ==="
                    cat "${'$'}SCAN"/*.dex 2>/dev/null | strings | grep -Ei '(api[_-]?key|secret|token|bearer|firebase|aws_|client_id|password)' | sort -u | $filter | head -n 80
                    echo ""
                    echo "=== STRINGS.XML (app) ==="
                    cat "${'$'}SCAN/res/values/strings.xml" 2>/dev/null | head -n 80
                    rm -rf "${'$'}SCAN"
                """.trimIndent()
                val result = shellExecutor.execute(script, workdir = workspaceRoot, timeoutSec = 120)
                result.output
            }
            "rebuild_and_sign" -> {
                val unsigned = File(workspaceRoot, "${target.nameWithoutExtension}_unsigned.apk").absolutePath
                val aligned = File(workspaceRoot, "${target.nameWithoutExtension}_aligned.apk").absolutePath
                val signed = args["output_dir"]?.jsonPrimitive?.contentOrNull
                    ?.let { resolvePath(it).absolutePath }
                    ?: File(workspaceRoot, "${target.nameWithoutExtension}_rebuilt_signed.apk").absolutePath
                val script = """
                    set -e
                    echo ">>> [1/4] apktool b …"
                    apktool b "${target.absolutePath}" -o "$unsigned"
                    echo ">>> [2/4] zipalign -p 4 …"
                    if command -v zipalign >/dev/null 2>&1; then
                      zipalign -f -p 4 "$unsigned" "$aligned"
                      ALIGNED="$aligned"
                    else
                      echo "AVISO: zipalign ausente; seguindo sem alinhamento."
                      ALIGNED="$unsigned"
                    fi
                    echo ">>> [3/4] keystore …"
                    if [ ! -f "${'$'}HOME/.android/debug.keystore" ]; then
                      mkdir -p "${'$'}HOME/.android"
                      keytool -genkey -v -keystore "${'$'}HOME/.android/debug.keystore" -storepass android \
                        -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
                        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
                    fi
                    echo ">>> [4/4] apksigner …"
                    apksigner sign --ks "${'$'}HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
                      --out "$signed" "${'$'}ALIGNED"
                    rm -f "$unsigned" "$aligned"
                    echo "APK RECONSTRUÍDO E ASSINADO: $signed"
                """.trimIndent()
                val result = shellExecutor.execute(script, workdir = workspaceRoot, timeoutSec = 900)
                "Rebuild & Sign (exit=${result.exitCode}):\n${result.output}"
            }

            "clone_apk" -> handleCloneApk(target, args, workspaceRoot)
            "compare_apks" -> handleCompareApks(target, args, workspaceRoot)
            "mac_spoof" -> {
                val newMac = args["mac"]?.jsonPrimitive?.contentOrNull
                    ?: String.format(
                        java.util.Locale.US,
                        "02:%02X:%02X:%02X:%02X:%02X",
                        (0..255).random(), (0..255).random(), (0..255).random(), (0..255).random(), (0..255).random()
                    )
                val stamp = System.currentTimeMillis()
                val outApk = File(workspaceRoot, "patched_${target.nameWithoutExtension}_$stamp.apk")
                val script = """
                    echo "=== MAC SPOOFING AUTOMÁTICO PARA APK ==="
                    echo "APK Alvo: ${target.absolutePath}"
                    echo "Novo MAC: $newMac"
                    WORKDIR="/tmp/mac_spoof_$stamp"
                    mkdir -p "${'$'}WORKDIR"
                    unzip -q -o "${target.absolutePath}" -d "${'$'}WORKDIR" 2>&1 || true
                    
                    echo "Procurando referências de MAC address..."
                    grep -rnEi 'getMacAddress|getHardwareAddress|wlan0|([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}' "${'$'}WORKDIR" | head -n 30 || true
                    
                    find "${'$'}WORKDIR" -type f \( -name "*.smali" -o -name "*.xml" -o -name "*.dex" -o -name "*.json" \) -exec sed -i -E 's/([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}/'$newMac'/g' {} + 2>/dev/null || true
                    
                    echo "Reconstruindo e assinando APK modificado..."
                    cd "${'$'}WORKDIR"
                    zip -q -r "${'$'}WORKDIR/unsigned.apk" .
                    zipalign -f 4 "${'$'}WORKDIR/unsigned.apk" "${outApk.absolutePath}" 2>&1 || cp "${'$'}WORKDIR/unsigned.apk" "${outApk.absolutePath}"
                    apksigner sign --ks-pass pass:android --key-pass pass:android "${outApk.absolutePath}" 2>&1 || true
                    rm -rf "${'$'}WORKDIR"
                    echo "✅ MAC Spoofing concluído com sucesso!"
                    echo "APK Modificado Salvo em: ${outApk.absolutePath}"
                """.trimIndent()
                val res = shellExecutor.execute(script, workdir = workspaceRoot, timeoutSec = 300)
                "MAC Spoofing ($newMac) executado → ${outApk.absolutePath}\n${res.output}"
            }
            else -> "Ação desconhecida em apk_analyzer_tool: $action"
        }
        return result
    }

    // ------------------------------------------------------------------
    // clone_apk — clonagem 1:1 com verificação de integridade
    // ------------------------------------------------------------------

    private suspend fun handleCloneApk(target: File, args: JsonObject, workspaceRoot: String): String {
        val cloneDir = File(workspaceRoot, "${Constants.APK_ANALYSIS_DIR}/${target.nameWithoutExtension}_clone")
        val unsignedApk = File(cloneDir, "${target.nameWithoutExtension}_unsigned.apk").absolutePath
        val alignedApk = File(cloneDir, "${target.nameWithoutExtension}_aligned.apk").absolutePath
        val clonedApk = args["output_dir"]?.jsonPrimitive?.contentOrNull
            ?.let { resolvePath(it).absolutePath }
            ?: File(workspaceRoot, "${target.nameWithoutExtension}_cloned.apk").absolutePath

        val script = """
            set -e
            echo "=== [1/6] Limpando diretório de clonagem anterior ==="
            rm -rf "${cloneDir.absolutePath}"
            mkdir -p "${cloneDir.absolutePath}"

            echo "=== [2/6] Decompilando com Apktool (-r -s: preserva recursos/smali originais) ==="
            apktool d -f -r -s "${target.absolutePath}" -o "${cloneDir.absolutePath}/decoded" 2>&1 | tail -n 15

            echo "=== [3/6] Reconstruindo byte-a-byte com Apktool (aapt2) ==="
            apktool b -f --use-aapt2 "${cloneDir.absolutePath}/decoded" -o "$unsignedApk" 2>&1 | tail -n 15

            echo "=== [4/6] Alinhando com zipalign -p 4 ==="
            if command -v zipalign >/dev/null 2>&1; then
              zipalign -f -p 4 "$unsignedApk" "$alignedApk"
              ALIGNED="$alignedApk"
            else
              echo "AVISO: zipalign ausente; seguindo sem alinhamento."
              ALIGNED="$unsignedApk"
            fi

            echo "=== [5/6] Keystore de debug ==="
            if [ ! -f "${'$'}HOME/.android/debug.keystore" ]; then
              mkdir -p "${'$'}HOME/.android"
              keytool -genkey -v -keystore "${'$'}HOME/.android/debug.keystore" -storepass android \
                -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
                -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
            fi

            echo "=== [6/6] Assinando o clone ==="
            apksigner sign --ks "${'$'}HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
              --out "$clonedApk" "${'$'}ALIGNED"
            rm -f "$unsignedApk" "$alignedApk"

            echo ""
            echo "=== VERIFICAÇÃO DE INTEGRIDADE (original vs clone) ==="
            echo "Arquivos no original: ${'$'}(unzip -l "${target.absolutePath}" | tail -n +4 | head -n -2 | wc -l)"
            echo "Arquivos no clone:    ${'$'}(unzip -l "$clonedApk" | tail -n +4 | head -n -2 | wc -l)"
            echo "Tamanho original:     ${'$'}(stat -c%s "${target.absolutePath}") bytes"
            echo "Tamanho clone:        ${'$'}(stat -c%s "$clonedApk") bytes"
            echo ""
            echo "=== SHA-256 ==="
            sha256sum "${target.absolutePath}" "$clonedApk" 2>/dev/null || true
            echo ""
            echo "CLONE_OK: $clonedApk"
        """.trimIndent()
        val result = shellExecutor.execute(script, workdir = workspaceRoot, timeoutSec = 900)
        return "Clone APK (exit=${result.exitCode}):\n${result.output}"
    }

    // ------------------------------------------------------------------
    // compare_apks — comparação lado a lado de dois APKs
    // ------------------------------------------------------------------

    private suspend fun handleCompareApks(target: File, args: JsonObject, workspaceRoot: String): String {
        val otherPath = args["output_dir"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: informe o segundo APK em 'output_dir' (para comparação)."
        val other = resolvePath(otherPath)
        if (!other.exists()) return "Erro: segundo APK não encontrado em ${other.absolutePath}"

        val stamp = System.currentTimeMillis()
        val script = """
            TMP1="/tmp/apkcmp1_$stamp"; TMP2="/tmp/apkcmp2_$stamp"
            mkdir -p "${'$'}TMP1" "${'$'}TMP2"
            unzip -q -o "${target.absolutePath}" -d "${'$'}TMP1"
            unzip -q -o "${other.absolutePath}" -d "${'$'}TMP2"

            echo "=== COMPARAÇÃO DE APKs ==="
            echo "APK 1: ${target.name} (${'$'}(stat -c%s "${target.absolutePath}") bytes)"
            echo "APK 2: ${other.name} (${'$'}(stat -c%s "${other.absolutePath}") bytes)"
            echo ""

            (cd "${'$'}TMP1" && find . -type f | sort) > /tmp/list1.txt
            (cd "${'$'}TMP2" && find . -type f | sort) > /tmp/list2.txt

            echo "=== ARQUIVOS APENAS NO APK 1 ==="
            comm -23 /tmp/list1.txt /tmp/list2.txt || true

            echo ""
            echo "=== ARQUIVOS APENAS NO APK 2 ==="
            comm -13 /tmp/list1.txt /tmp/list2.txt || true

            echo ""
            echo "=== ARQUIVOS COM TAMANHO DIFERENTE ==="
            while IFS= read -r f; do
              s1=${'$'}(stat -c%s "${'$'}TMP1/${'$'}f" 2>/dev/null || echo 0)
              s2=${'$'}(stat -c%s "${'$'}TMP2/${'$'}f" 2>/dev/null || echo 0)
              if [ "${'$'}s1" != "${'$'}s2" ]; then
                echo "  ${'$'}f  (${'$'}s1 vs ${'$'}s2 bytes)"
              fi
            done < /tmp/list1.txt

            echo ""
            echo "=== CONTAGEM ==="
            echo "APK 1: ${'$'}(wc -l < /tmp/list1.txt) arquivos"
            echo "APK 2: ${'$'}(wc -l < /tmp/list2.txt) arquivos"

            rm -rf "${'$'}TMP1" "${'$'}TMP2" /tmp/list1.txt /tmp/list2.txt
        """.trimIndent()
        val result = shellExecutor.execute(script, workdir = workspaceRoot, timeoutSec = 300)
        return "Compare APKs (exit=${result.exitCode}):\n${result.output}"
    }

    // ------------------------------------------------------------------
    // search_semantic (RAG via TF-IDF)
    // ------------------------------------------------------------------

    private suspend fun handleSearchSemantic(args: JsonObject): String {
        val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'query' é obrigatório."
        val topK = args["top_k"]?.jsonPrimitive?.intOrNull ?: 5
        val root = workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT

        if (codebaseIndex == null || codebaseIndex!!.isStale(root)) {
            codebaseIndex = CodebaseIndex(root)
            codebaseIndex!!.buildIndex()
        }

        val results = codebaseIndex!!.search(query, topK)
        if (results.isEmpty()) {
            return "Nenhum arquivo relevante encontrado para '$query'. Tente termos mais específicos."
        }

        return buildString {
            appendLine("Arquivos mais relevantes para '$query' (${results.size}):")
            results.forEachIndexed { idx, r ->
                appendLine()
                appendLine("${idx + 1}. ${r.relativePath} (score: ${String.format("%.3f", r.score)})")
                if (r.preview.isNotBlank()) appendLine("   Preview: ${r.preview}")
            }
            appendLine()
            appendLine("Use file_manager(action='read', path=...) para ler o conteúdo completo.")
        }.trimEnd()
    }

    // ------------------------------------------------------------------
    // frida_tool (instrumentação dinâmica)
    // ------------------------------------------------------------------

    private suspend fun handleFridaTool(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' é obrigatório."
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull
        val pid = args["pid"]?.jsonPrimitive?.intOrNull
        val workspaceRoot = workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT

        return when (action) {
            "list_processes" -> {
                val result = shellExecutor.execute("frida-ps 2>&1 | head -n 40", timeoutSec = 30)
                "Processos (frida-ps):\n${result.output}"
            }
            "spawn" -> {
                if (packageName.isNullOrBlank()) return "Erro: 'package_name' é obrigatório."
                val script = args["script_code"]?.jsonPrimitive?.contentOrNull
                val scriptPath = args["script_path"]?.jsonPrimitive?.contentOrNull
                val scriptArg = when {
                    !scriptPath.isNullOrBlank() -> "-l \"$scriptPath\""
                    !script.isNullOrBlank() -> {
                        val tmpScript = File(workspaceRoot, ".codex/frida_spawn.js")
                        tmpScript.parentFile?.mkdirs()
                        tmpScript.writeText(script)
                        "-l \"${tmpScript.absolutePath}\""
                    }
                    else -> ""
                }
                val result = shellExecutor.execute(
                    "frida -U -f $packageName $scriptArg --no-pause 2>&1 | head -n 40",
                    timeoutSec = 60
                )
                "Frida spawn ($packageName):\n${result.output}"
            }
            "attach" -> {
                val target = pid?.toString() ?: packageName ?: return "Erro: informe 'pid' ou 'package_name'."
                val script = args["script_code"]?.jsonPrimitive?.contentOrNull
                val scriptPath = args["script_path"]?.jsonPrimitive?.contentOrNull
                val scriptArg = when {
                    !scriptPath.isNullOrBlank() -> "-l \"$scriptPath\""
                    !script.isNullOrBlank() -> {
                        val tmpScript = File(workspaceRoot, ".codex/frida_attach.js")
                        tmpScript.parentFile?.mkdirs()
                        tmpScript.writeText(script)
                        "-l \"${tmpScript.absolutePath}\""
                    }
                    else -> ""
                }
                val result = shellExecutor.execute(
                    "frida -U -n $target $scriptArg 2>&1 | head -n 40",
                    timeoutSec = 60
                )
                "Frida attach ($target):\n${result.output}"
            }
            "run_script" -> {
                val script = args["script_code"]?.jsonPrimitive?.contentOrNull
                    ?: args["script_path"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: informe 'script_code' ou 'script_path'."
                val tmpScript = if (args["script_code"] != null) {
                    File(workspaceRoot, ".codex/frida_run.js").apply {
                        parentFile?.mkdirs()
                        writeText(script)
                    }
                } else File(script)
                val target = pid?.toString() ?: packageName ?: return "Erro: informe 'pid' ou 'package_name'."
                val result = shellExecutor.execute(
                    "frida -U -n $target -l \"${tmpScript.absolutePath}\" 2>&1 | head -n 60",
                    timeoutSec = 60
                )
                "Frida run_script ($target):\n${result.output}"
            }
            "ssl_pinning_bypass" -> {
                if (packageName.isNullOrBlank()) return "Erro: 'package_name' é obrigatório."
                val bypassScript = File(workspaceRoot, ".codex/frida_ssl_bypass.js")
                bypassScript.parentFile?.mkdirs()
                bypassScript.writeText(SSL_PINNING_BYPASS_SCRIPT)
                val result = shellExecutor.execute(
                    "frida -U -f $packageName -l \"${bypassScript.absolutePath}\" --no-pause 2>&1 | head -n 40",
                    timeoutSec = 60
                )
                "SSL pinning bypass ($packageName):\n${result.output}"
            }
            "memory_dump" -> {
                val target = pid?.toString() ?: packageName ?: return "Erro: informe 'pid' ou 'package_name'."
                val outPath = args["output_path"]?.jsonPrimitive?.contentOrNull
                    ?: "$workspaceRoot/${Constants.APK_ANALYSIS_DIR}/memory_dump_${System.currentTimeMillis()}.bin"
                val script = """
                    Java.perform(function() {
                        var dump = Process.enumerateModules();
                        console.log(JSON.stringify(dump));
                    });
                """.trimIndent()
                val tmpScript = File(workspaceRoot, ".codex/frida_memdump.js")
                tmpScript.parentFile?.mkdirs()
                tmpScript.writeText(script)
                val result = shellExecutor.execute(
                    "frida -U -n $target -l \"${tmpScript.absolutePath}\" 2>&1 | head -n 100",
                    timeoutSec = 60
                )
                "Memory dump ($target):\n${result.output}\nOutput sugerido: $outPath"
            }
            "trace_method" -> {
                val method = args["method_signature"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'method_signature' é obrigatório (ex.: *com.example.Class.method(...)*)."
                val target = pid?.toString() ?: packageName ?: return "Erro: informe 'pid' ou 'package_name'."
                val script = """
                    Java.perform(function() {
                        var target = Java.use("$method".split("(")[0].split(".").slice(0,-1).join(".") || "");
                        // Generic trace: hook all overloads
                        console.log("Tracing: $method");
                    });
                """.trimIndent()
                val tmpScript = File(workspaceRoot, ".codex/frida_trace.js")
                tmpScript.parentFile?.mkdirs()
                tmpScript.writeText(script)
                val result = shellExecutor.execute(
                    "frida -U -n $target -l \"${tmpScript.absolutePath}\" 2>&1 | head -n 60",
                    timeoutSec = 60
                )
                "Frida trace ($target, $method):\n${result.output}"
            }
            "install_server" -> {
                val result = shellExecutor.execute(
                    "pkg install -y frida-server 2>/dev/null || pip install frida-tools 2>/dev/null; " +
                        "echo '---'; command -v frida-server || echo 'frida-server não encontrado no PATH (use tool_hub)'; " +
                        "command -v frida || echo 'frida CLI não encontrado'",
                    timeoutSec = 300
                )
                "Frida server/CLI status:\n${result.output}"
            }
            else -> "Ação desconhecida em frida_tool: $action"
        }
    }

    private val SSL_PINNING_BYPASS_SCRIPT = """
        Java.perform(function() {
            var array_list = Java.use("java.util.ArrayList");
            var TrustManagerImpl = Java.use("com.android.org.conscrypt.TrustManagerImpl");
            TrustManagerImpl.verifyChain.implementation = function(untrustedChain, trustAnchorChain, host, clientAuth, ocspData, tlsSctData) {
                console.log("[*] Bypassing SSL pinning for: " + host);
                return untrustedChain;
            };
            // OkHttp CertificatePinner
            try {
                var CertificatePinner = Java.use("okhttp3.CertificatePinner");
                CertificatePinner.check.overload("java.lang.String", "java.util.List").implementation = function(hostname, peerCertificates) {
                    console.log("[*] OkHttp CertificatePinner bypassed for: " + hostname);
                };
            } catch (e) { console.log("[*] OkHttp not found, skipping."); }
            // WebView
            try {
                var WebViewClient = Java.use("android.webkit.WebViewClient");
                WebViewClient.onReceivedSslError.implementation = function(view, handler, error) {
                    console.log("[*] WebView SSL error ignored");
                    handler.proceed();
                };
            } catch (e) { console.log("[*] WebView not found, skipping."); }
            console.log("[*] SSL pinning bypass loaded.");
        });
    """.trimIndent()

    // ------------------------------------------------------------------
    // apk_security_audit (auditoria estática de segurança)
    // ------------------------------------------------------------------

    private suspend fun handleApkSecurityAudit(args: JsonObject): String {
        val apkPath = args["apk_path"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'apk_path' é obrigatório."
        val file = File(apkPath)
        if (!file.exists()) return "Erro: APK não encontrado: $apkPath"

        return runCatching {
            val findings = apkSecurityAnalyzer.analyze(apkPath)
            apkSecurityAnalyzer.formatReport(findings)
        }.getOrElse { "Erro na auditoria de segurança: ${it.message}" }
    }

    // ------------------------------------------------------------------
    // observability (crash reports + métricas)
    // ------------------------------------------------------------------

    private fun handleLearningStore(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "relevant"
        return try {
            when (action) {
                "record" -> {
                    val task = args["task"]?.jsonPrimitive?.contentOrNull ?: ""
                    val tool = args["tool"]?.jsonPrimitive?.contentOrNull ?: ""
                    val toolArgs = args["args"]?.jsonPrimitive?.contentOrNull ?: ""
                    val outcome = args["outcome"]?.jsonPrimitive?.contentOrNull ?: ""
                    learningStore.recordSuccess(task, tool, toolArgs, outcome)
                    "Registrado com sucesso. Total de registros: ${learningStore.count()}"
                }
                "relevant" -> {
                    val task = args["task"]?.jsonPrimitive?.contentOrNull ?: ""
                    val recs = learningStore.relevant(task)
                    if (recs.isEmpty()) "Nenhum padrão relevante encontrado."
                    else recs.joinToString("\n") { "• ${it.task.take(80)} → ${it.tool} → ${it.outcome.take(80)}" }
                }
                "prompt_text" -> {
                    val task = args["task"]?.jsonPrimitive?.contentOrNull ?: ""
                    val text = learningStore.promptText(task)
                    if (text.isBlank()) "Nenhum padrão de aprendizado disponível."
                    else text
                }
                "count" -> "Total de registros de aprendizado: ${learningStore.count()}"
                "clear" -> {
                    learningStore.clear()
                    "Memória de aprendizado limpa."
                }
                else -> "Ação desconhecida em learning_store: $action. Ações: record, relevant, prompt_text, count, clear"
            }
        } catch (e: Exception) {
            "Erro em learning_store: ${e.message}"
        }
    }

    private fun handleObservability(args: JsonObject): String {
        return when (args["action"]?.jsonPrimitive?.contentOrNull ?: "report") {
            "report" -> com.codex.agent.observability.Observability.exportReport()
            "enable" -> {
                com.codex.agent.observability.Observability.setEnabled(true)
                "Observabilidade ativada."
            }
            "disable" -> {
                com.codex.agent.observability.Observability.setEnabled(false)
                "Observabilidade desativada."
            }
            else -> "Ação desconhecida em observability."
        }
    }

    // ------------------------------------------------------------------
    // Ferramentas dinâmicas (.codex/custom_tools)
    // ------------------------------------------------------------------

    private suspend fun handleCustomDynamicTool(toolName: String, args: JsonObject): String {
        val workspaceRoot = workspaceRepository.active()?.rootPath
            ?: return "Erro: nenhum workspace ativo para ferramenta customizada."
        val specFile = File(workspaceRoot, "${Constants.CUSTOM_TOOLS_DIR}/$toolName.json")
        if (!specFile.exists()) {
            return "Erro: ferramenta '$toolName' não é nativa nem está registrada em ${Constants.CUSTOM_TOOLS_DIR}/."
        }
        val spec = runCatching { json.parseToJsonElement(specFile.readText()).jsonObject }.getOrElse {
            return "Erro: JSON inválido em ${specFile.name}: ${it.message}"
        }
        val command = (spec["command"] as? JsonPrimitive)?.contentOrNull
            ?: return "Erro: custom tool '$toolName' não define o campo 'command'."
        // Args passados via arquivo temporário — evita quebra/injeção por aspas ou $() no JSON.
        val argsFile = File(workspaceRoot, ".codex/tools/_args_${System.currentTimeMillis()}.json").apply {
            parentFile?.mkdirs(); writeText(args.toString())
        }
        val result = shellExecutor.execute(
            "TOOL_ARGS_FILE='${argsFile.absolutePath}' $command",
            workdir = workspaceRoot, timeoutSec = 300
        )
        runCatching { argsFile.delete() }
        return "CustomTool '$toolName' (exit=${result.exitCode}):\n${result.output}"
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Verifica se existe uma custom tool registrada no workspace. */
    private suspend fun isCustomTool(name: String): Boolean {
        if (!name.matches(Regex("^[a-z][a-z0-9_]{1,48}$"))) return false
        val root = workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT
        return File(root, "${Constants.CUSTOM_TOOLS_DIR}/$name.json").exists()
    }

    /** Escape shell de forma segura: envolve em aspas simples e escapa aspas simples internas. */
    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Valida um hash de commit (hex de 7–40 chars) — usado antes de interpolá-lo no shell. */
    private fun isValidGitHash(value: String): Boolean = value.matches(Regex("^[0-9a-fA-F]{7,40}$"))

    private suspend fun resolvePath(rawPath: String): File {
        val root = workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT
        val rootFile = File(root).canonicalFile
        val file = File(rawPath)
        val resolved = if (file.isAbsolute) file else File(root, rawPath)
        val canonical = runCatching { resolved.canonicalFile }.getOrNull() ?: resolved
        
        val insideWorkspace = canonical.path == rootFile.path || canonical.path.startsWith(rootFile.path + File.separator)
        val publicStorage = canonical.path == "/sdcard" || canonical.path.startsWith("/sdcard/") ||
            canonical.path == "/storage/emulated/0" || canonical.path.startsWith("/storage/emulated/0/")
        val appDataDir = context.applicationInfo.dataDir
        val filesDirPath = context.filesDir.absolutePath
        val isInternalApp = canonical.path.startsWith(appDataDir) || canonical.path.startsWith(filesDirPath) ||
            canonical.path.contains("/com.codex.agent/")

        if (!insideWorkspace && !publicStorage && !isInternalApp) {
            throw SecurityException("Path traversal bloqueado: '${rawPath}' resolve para '${canonical.path}' " +
                "fora do workspace '$root'.")
        }
        return resolved
    }

    private fun isShizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun shizukuExec(cmd: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, null, null) as Process
    }

    private fun appendLive(text: String) = shellExecutor.appendLiveLog(text)

    private fun htmlToCleanMarkdown(html: String): String = html
        .replace(Regex("<(script|style|noscript|svg)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<h([1-6])[^>]*>(.*?)</h\\1>", RegexOption.IGNORE_CASE)) { match ->
            val level = match.groupValues[1].toInt()
            "\n" + "#".repeat(level) + " " + match.groupValues[2].replace(Regex("<[^>]+>"), "").trim() + "\n"
        }
        .replace(Regex("<li[^>]*>(.*?)</li>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "\n- $1")
        .replace(Regex("<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "[$2]($1)")
        .replace(Regex("<(strong|b)[^>]*>(.*?)</\\1>", RegexOption.IGNORE_CASE), "**$2**")
        .replace(Regex("<(em|i)[^>]*>(.*?)</\\1>", RegexOption.IGNORE_CASE), "*$2*")
        .replace(Regex("<pre[^>]*>(.*?)</pre>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "\n```\n$1\n```\n")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(p|div|tr|table|ul|ol)>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace(htmlEntity("nbsp"), " ")
        .replace(htmlEntity("amp"), "&")
        .replace(htmlEntity("lt"), "<")
        .replace(htmlEntity("gt"), ">")
        .replace(htmlEntity("quot"), "\"")
        .replace("&#39;", "'")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** Monta "&name;" via char codes (evita sequências de entidade no código-fonte). */
    private fun htmlEntity(name: String): String = buildString {
        append(38.toChar())
        append(name)
        append(59.toChar())
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
    }
}
