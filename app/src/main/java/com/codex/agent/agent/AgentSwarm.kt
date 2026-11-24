package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.ProviderRepository
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.llm.LlmClient
import com.codex.agent.llm.LlmMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SISTEMA DE AGENTES HIERÁRQUICOS (swarm).
 *
 * O agente mestre (root, depth=0) pode criar sub-agentes, que podem criar seus próprios
 * sub-agentes, formando uma ÁRVORE. O mestre comanda:
 *  - broadcast: envia um comando para TODOS os agentes de uma vez (paralelo)
 *  - comando individual: envia para um agente específico pelo ID
 *  - collect: reúne os resultados de todos
 *
 * Segurança/robustez:
 *  - Profundidade máxima (MAX_DEPTH) evita recursão infinita.
 *  - Cada nó executa em contexto isolado com ferramentas de leitura/análise.
 *  - Resultados são entregues via [SubAgentResultBus] para injeção no chat.
 */
class AgentSwarm(
    private val llmClient: LlmClient,
    private val toolExecutor: ToolExecutor,
    private val providerRepository: ProviderRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val shellExecutor: com.codex.agent.shell.ShellExecutor
) {
    data class AgentNode(
        val id: Int,
        val parentId: Int,
        val depth: Int,
        val label: String,
        @Volatile var status: String = "IDLE", // IDLE | RUNNING | DONE | ERROR
        @Volatile var lastResult: String = "",
        @Volatile var failures: Int = 0
    )

    /** Mensagem entre agentes (caixa de entrada por agente). */
    data class AgentMessage(
        val fromId: Int,
        val toId: Int,
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** Blackboard: conhecimento compartilhado por todos os agentes do enxame. */
    private val blackboard = ConcurrentHashMap<String, String>()
    /** Caixas de entrada: agenteID -> mensagens pendentes. */
    private val inboxes = ConcurrentHashMap<Int, MutableList<AgentMessage>>()

    companion object {
        const val MAX_DEPTH = 5
        const val MAX_AGENTS = 64
        // Sub-agentes rodam em paralelo — cada um em seu próprio contexto/isolamento.
        // Testes de UI (install + abrir + logcat) são serializados para não colidir.
        const val MAX_CONCURRENT_AGENTS = 10
        const val SUB_AGENT_MAX_STEPS = 12
    }

    /** Fila de testes de UI serializados: evita que dois agentes instalem/abram APK ao mesmo tempo. */
    private val uiTestQueue = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val uiTestLock = java.util.concurrent.locks.ReentrantLock(true)
    @Volatile private var uiTestRunning: Int? = null

    /** Executa uma ação de UI test serializada: um agente por vez. */
    private suspend fun uiTest(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: return "Erro: 'id' obrigatório."
        val node = nodes[id] ?: return "Erro: agente #$id não existe."
        val action = args["ui_action"]?.jsonPrimitive?.contentOrNull ?: "test_cycle"
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val apk = args["apk"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val serial = args["serial"]?.jsonPrimitive?.contentOrNull.orEmpty()

        // Enfileira: espera a vez
        uiTestQueue.offer(id)
        while (uiTestRunning != null && uiTestRunning != id) {
            kotlinx.coroutines.delay(500)
        }
        uiTestRunning = id
        try {
            val result = runUiTest(id, node, action, pkg, apk, serial, args)
            node.lastResult = result
            node.status = "DONE"
            SubAgentResultBus.emit("[UI-Test #${id} ${node.label}] $pkg", result)
            return result
        } finally {
            uiTestRunning = null
            uiTestQueue.poll()
        }
    }

    private suspend fun runUiTest(id: Int, node: AgentNode, action: String, pkg: String, apk: String, serial: String, args: JsonObject): String {
        val adbPrefix = if (serial.isBlank()) "" else "-s $serial "
        val sb = StringBuilder("=== UI Test do Agente #$id (${node.label}) ===\nPackage: $pkg\n")
        sb.appendLine("Aguardou a vez na fila (serializado — sem misturar com outros agentes).")
        when (action) {
            "test_cycle" -> {
                if (pkg.isBlank() || apk.isBlank()) return sb.appendLine("Erro: 'package' e 'apk' obrigatórios.").toString()
                // 1) desinstala
                val un = shellExecutor.execute("adb ${adbPrefix}uninstall $pkg 2>&1", timeoutSec = 60).output.trim()
                sb.appendLine("1) Uninstall: ${if (un.contains("Success", true)) "OK" else "n/a"}")
                // 2) instala
                val inst = shellExecutor.execute("adb ${adbPrefix}install -r \"$apk\" 2>&1", timeoutSec = 180).output.trim()
                val ok = inst.contains("Success", true)
                sb.appendLine("2) Install: ${if (ok) "OK" else inst.take(120)}")
                if (!ok) return sb.toString()
                // 3) abre
                val launch = shellExecutor.execute("adb ${adbPrefix}shell monkey -p $pkg 1 2>&1", timeoutSec = 30).output.trim()
                sb.appendLine("3) Launch: $launch")
                kotlinx.coroutines.delay(3000)
                // 4) logcat
                val log = shellExecutor.execute("adb ${adbPrefix}logcat -d -t 200 *:E 2>/dev/null | grep -i $pkg | tail -n 60", timeoutSec = 20).output.trim()
                val fatal = log.contains("FATAL", true) || log.contains("AndroidRuntime", true)
                sb.appendLine("4) Logcat: ${if (log.isBlank()) "limpo (sem erros)" else log.take(1200)}")
                sb.appendLine()
                sb.appendLine("RESULTADO: ${if (fatal) "❌ CRASH detectado" else "✅ Sem crashes"}")
            }
            "tap" -> {
                val x = args["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val y = args["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (x == null || y == null) return sb.appendLine("Erro: 'x' e 'y' obrigatórios.").toString()
                val r = shellExecutor.execute("adb ${adbPrefix}shell input tap $x $y", timeoutSec = 10).output.trim()
                sb.appendLine("Tap ($x,$y): $r")
            }
            "screenshot" -> {
                val out = "${workRoot()}/${Constants.APK_ANALYSIS_DIR}/ui_${System.currentTimeMillis()}.png"
                val r = shellExecutor.execute("adb ${adbPrefix}exec-out screencap -p > '$out'", timeoutSec = 20).output.trim()
                sb.appendLine("Screenshot: $out (${runCatching { File(out).length() }.getOrNull() ?: 0} bytes) — $r")
            }
            "force_stop" -> {
                val r = shellExecutor.execute("adb ${adbPrefix}shell am force-stop $pkg", timeoutSec = 10).output.trim()
                sb.appendLine("Force-stop $pkg: $r")
            }
            else -> sb.appendLine("UI action desconhecida: $action")
        }
        return sb.toString()
    }

    /** Batch: cria N agentes e envia um comando para cada um, em paralelo. */
    private fun batch(args: JsonObject): String {
        val tasksRaw = args["tasks"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'tasks' obrigatório (JSON array: [{\"label\":\"...\",\"task\":\"...\"}])."
        val tasks = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(tasksRaw).jsonArray
        }.getOrElse { return "Erro: 'tasks' deve ser JSON array: ${it.message}" }
        if (tasks.size > MAX_CONCURRENT_AGENTS) return "Erro: máximo $MAX_CONCURRENT_AGENTS agentes simultâneos."

        val sb = StringBuilder("📦 Batch: ${tasks.size} agentes criados e disparados em paralelo.\n")
        val created = mutableListOf<Int>()
        tasks.forEach { el ->
            val obj = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
            val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: "Agente ${created.size + 1}"
            val task = obj["task"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val id = idCounter.getAndIncrement()
            val node = AgentNode(id = id, parentId = 0, depth = 1, label = label)
            nodes[id] = node
            children.computeIfAbsent(0) { mutableListOf() }.add(id)
            created.add(id)
            launchAgent(node, task)
            sb.appendLine("  #${id} $label → disparado")
        }
        sb.appendLine("\nUse action='collect' para reunir os resultados, ou 'status' para acompanhar.")
        sb.appendLine("Testes de UI serão serializados automaticamente (um por vez) para não colidir.")
        return sb.toString()
    }

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicInteger(1)
    private val nodes = ConcurrentHashMap<Int, AgentNode>()
    private val children = ConcurrentHashMap<Int, MutableList<Int>>()

    init {
        // Nó root = agente mestre
        nodes[0] = AgentNode(id = 0, parentId = -1, depth = 0, label = "MESTRE", status = "ACTIVE")
    }

    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' é obrigatório."
        return try {
            when (action) {
                "spawn" -> spawn(args)
                "command" -> command(args)
                "broadcast" -> broadcast(args)
                "collect" -> collect()
                "tree" -> tree()
                "status" -> status(args)
                "send_message" -> sendMessage(args)
                "inbox" -> inbox(args)
                "write_memory" -> writeMemory(args)
                "read_memory" -> readMemory(args)
                "memory" -> memoryDump()
                "batch" -> batch(args)
                "ui_test" -> uiTest(args)
                "reset" -> reset()
                else -> "Ação desconhecida em swarm_tool: $action"
            }
        } catch (e: Exception) {
            "Erro em swarm_tool ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    /** Cria um novo agente filho. `parent` omesso = raiz (mestre). */
    private fun spawn(args: JsonObject): String {
        val parentId = args["parent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val label = args["label"]?.jsonPrimitive?.contentOrNull ?: "Agente"
        val parent = nodes[parentId] ?: return "Erro: agente pai $parentId não existe."
        if (nodes.size >= MAX_AGENTS) return "Erro: limite de $MAX_AGENTS agentes atingido."
        if (parent.depth + 1 > MAX_DEPTH) return "Erro: profundidade máxima ($MAX_DEPTH) atingida — não é possível criar mais sub-agentes."

        val id = idCounter.getAndIncrement()
        val node = AgentNode(id = id, parentId = parentId, depth = parent.depth + 1, label = label)
        nodes[id] = node
        children.computeIfAbsent(parentId) { mutableListOf() }.add(id)
        return "✅ Agente #$id '$label' criado sob ${if (parentId == 0) "MESTRE" else "#$parentId"} (profundidade ${node.depth})."
    }

    /** Envia um comando para UM agente específico (background, retorna imediatamente). */
    private fun command(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: return "Erro: 'id' obrigatório."
        val task = args["task"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'task' obrigatório."
        val node = nodes[id] ?: return "Erro: agente #$id não existe."
        return launchAgent(node, task)
    }

    /** Envia um comando para TODOS os agentes (ou todos os filhos de um pai) em PARALELO. */
    private fun broadcast(args: JsonObject): String {
        val task = args["task"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'task' obrigatório."
        val parentFilter = args["parent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val targets = if (parentFilter == null) {
            nodes.values.filter { it.id != 0 }
        } else {
            descendantsOf(parentFilter)
        }
        if (targets.isEmpty()) return "Nenhum agente alvo para broadcast."
        val sb = StringBuilder("📢 Broadcast para ${targets.size} agente(s):\n")
        targets.forEach { sb.appendLine("  #${it.id} ${it.label} (depth ${it.depth})") }
        targets.forEach { launchAgent(it, task) }
        sb.appendLine("Comandos disparados em paralelo. Use action='collect' para ver os resultados.")
        return sb.toString()
    }

    /** Coleta os resultados de todos os agentes que já executaram. */
    private fun collect(): String = buildString {
        appendLine("=== Resultados do Swarm ===")
        nodes.values.filter { it.id != 0 && it.lastResult.isNotBlank() }
            .sortedBy { it.id }
            .forEach { n ->
                appendLine()
                appendLine("--- #${n.id} ${n.label} [${n.status}] (depth ${n.depth}, pai ${n.parentId}) ---")
                appendLine(n.lastResult.take(2500))
            }
        val pending = nodes.values.count { it.id != 0 && it.status == "RUNNING" }
        if (pending > 0) appendLine("\n⏳ $pending agente(s) ainda executando.")
    }

    /** Mostra a árvore hierárquica de agentes. */
    private fun tree(): String = buildString {
        appendLine("🌳 Árvore de Agentes (${nodes.size - 1} sub-agentes):")
        appendLine("👑 #0 MESTRE [${nodes[0]?.status ?: "?"}]")
        fun render(parentId: Int, prefix: String) {
            val kids = children[parentId] ?: return
            kids.forEach { id ->
                val n = nodes[id] ?: return@forEach
                appendLine("$prefix├─ #${id} ${n.label} [${n.status}] d${n.depth}")
                render(id, "$prefix   ")
            }
        }
        render(0, "")
        if (nodes.size == 1) appendLine("   (nenhum sub-agente criado ainda — use action='spawn')")
    }

    private fun status(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return tree()
        val n = nodes[id] ?: return "Erro: agente #$id não existe."
        return "Agente #${n.id} '${n.label}': status=${n.status}, profundidade=${n.depth}, pai=${n.parentId}\n" +
            "Último resultado: ${n.lastResult.take(500).ifBlank { "(nenhum)" }}"
    }

    /** Envia uma mensagem de um agente para outro (ou broadcast com toId=-1). */
    private fun sendMessage(args: JsonObject): String {
        val fromId = args["from"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val toId = args["to"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: return "Erro: 'to' obrigatório (ID do agente, ou -1 para todos)."
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'text' obrigatório."
        val targets = if (toId == -1) nodes.keys.toList() else listOf(toId)
        var delivered = 0
        targets.forEach { id ->
            if (nodes.containsKey(id)) {
                inboxes.computeIfAbsent(id) { mutableListOf() }.add(AgentMessage(fromId, id, text))
                delivered++
            }
        }
        return "✉️ Mensagem entregue a $delivered agente(s)."
    }

    /** Lê a caixa de entrada de um agente (ou a do mestre, id=0). */
    private fun inbox(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val msgs = inboxes[id].orEmpty()
        if (msgs.isEmpty()) return "📭 Caixa de entrada do agente #$id está vazia."
        return buildString {
            appendLine("📬 Mensagens para agente #$id (${msgs.size}):")
            msgs.take(50).forEach { m ->
                appendLine("  [de #${m.fromId}] ${m.text.take(300)}")
            }
        }
    }

    /** Escreve no blackboard compartilhado (memória do enxame). */
    private fun writeMemory(args: JsonObject): String {
        val key = args["key"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'key' obrigatório."
        val value = args["value"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'value' obrigatório."
        blackboard[key] = value
        return "🧠 Memória compartilhada gravada: '$key'."
    }

    /** Lê do blackboard compartilhado. */
    private fun readMemory(args: JsonObject): String {
        val key = args["key"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'key' obrigatório."
        return blackboard[key]?.let { "🧠 '$key': $it" } ?: "Chave '$key' não encontrada na memória."
    }

    /** Despeja toda a memória compartilhada. */
    private fun memoryDump(): String {
        if (blackboard.isEmpty()) return "🧠 Memória compartilhada vazia."
        return buildString {
            appendLine("=== Memória Compartilhada do Enxame (${blackboard.size}) ===")
            blackboard.entries.sortedBy { it.key }.forEach { (k, v) ->
                appendLine("• $k: ${v.take(400)}")
            }
        }
    }

    private fun reset(): String {
        val count = nodes.size - 1
        nodes.clear()
        children.clear()
        blackboard.clear()
        inboxes.clear()
        idCounter.set(1)
        nodes[0] = AgentNode(id = 0, parentId = -1, depth = 0, label = "MESTRE", status = "ACTIVE")
        return "Swarm reiniciado. $count agente(s) removido(s)."
    }

    private fun descendantsOf(parentId: Int): List<AgentNode> {
        val result = mutableListOf<AgentNode>()
        fun walk(id: Int) {
            children[id]?.forEach { childId ->
                nodes[childId]?.let { child ->
                    result.add(child)
                    walk(childId)
                }
            }
        }
        walk(parentId)
        return result
    }

    /** Lança a execução de um agente em background com AUTO-CURA (retry + escalonamento). */
    private fun launchAgent(node: AgentNode, task: String): String {
        if (node.status == "RUNNING") return "Agente #${node.id} já está ocupado."
        node.status = "RUNNING"
        scope.launch {
            val depthInfo = "Você é o SUB-AGENTE #${node.id} '${node.label}' (profundidade ${node.depth}/${MAX_DEPTH}) " +
                "do enxame hierárquico do Codex Agent."

            val executeTask: suspend () -> String = {
                var res = runAgent(depthInfo, task, node.depth)
                var attempt = 0
                while (isFailure(res) && attempt < 2) {
                    attempt++
                    node.failures++
                    val retryTask = "$task\n\n[SELF-HEAL tentativa $attempt] A tentativa anterior falhou: " +
                        "${res.take(300)}\nMude de estratégia: use outras ferramentas."
                    res = runAgent("$depthInfo (auto-cura $attempt)", retryTask, node.depth)
                }
                res
            }

            val result = executeTask()

            // Escalonamento ao MESTRE se ainda falhou: registra no blackboard + mensagem.
            if (isFailure(result)) {
                blackboard["failure_agent_${node.id}"] = result.take(1000)
                inboxes.computeIfAbsent(0) { mutableListOf() }.add(
                    AgentMessage(node.id, 0, "🚨 ESCALONAMENTO: agente #${node.id} falhou após auto-cura: ${result.take(300)}")
                )
                node.status = "ERROR"
            } else {
                node.status = "DONE"
                node.failures = 0
                // Auto-cura: publica descoberta útil no blackboard para os outros agentes
                if (result.length > 40) blackboard["result_agent_${node.id}"] = result.take(1500)
            }
            node.lastResult = result
            SubAgentResultBus.emit(
                "[Swarm #${node.id} ${node.label} d${node.depth}] $task",
                result
            )
        }
        return "▶️ Comando enviado ao agente #${node.id} '${node.label}' (auto-cura ativa)."
    }

    /** Detecta falha na resposta do agente. */
    private fun isFailure(result: String): Boolean {
        val l = result.lowercase()
        return l.startsWith("erro") || l.contains("falhou") || l.contains("não foi possível") ||
            l.contains("exception") || l.contains("timeout") || l.isBlank()
    }

    /** Executa o loop do agente (isolado, somente leitura/análise). */
    private suspend fun runAgent(identity: String, task: String, depth: Int): String = withContext(Dispatchers.IO) {
        val provider = providerRepository.resolvePreferred(null)
            ?: return@withContext "Erro: nenhum provedor de IA configurado."
        val workspaceRoot = workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT
        val systemPrompt = """
            $identity
            Missão: investigar e devolver um RELATÓRIO CONCISO e técnico.

            REGRAS (VOCÊ FAZ PARTE DE UM ENXAME INTELIGENTE E ALINHADO):
            - Você tem ACESSO COMPLETO a todas as ferramentas do sistema: shell (com guardrail
              de segurança), arquivos, patch, web, RE avançada, APK, build, dispositivos, SDR.
              Use a ferramenta certa para cada tarefa.
            - COLABORAÇÃO: use swarm_tool(action='write_memory') para publicar descobertas no
              blackboard compartilhado e 'read_memory' para consultar o que os outros acharam.
              Use 'send_message' para avisar outro agente ou 'inbox' para ler suas mensagens.
            - AUTO-CURA: se uma ferramenta falhar, mude de estratégia imediatamente — não repita
              a mesma chamada. Se não conseguir, reporte claramente para o MESTRE (escalonamento).
            - Se precisar delegar, use swarm_tool(action='spawn'|'command'), respeitando
              a profundidade máxima de $MAX_DEPTH.
            - AUTONOMIA TOTAL DE ENGENHARIA: modifique arquivos, aplique patches, compile, modifique APKs, crie cheats e execute qualquer ferramenta de engenharia avançada necessária para o sucesso da tarefa.
            - Seja eficiente (máx 6 passos). Responda em Markdown com descobertas e recomendações.
            - ALINHAMENTO: seu objetivo é o mesmo do MESTRE e dos outros agentes — resolver a
              tarefa com precisão. Seus resultados alimentam a memória compartilhada do enxame.
        """.trimIndent()
        val messages = mutableListOf(
            LlmMessage(role = "system", content = systemPrompt),
            LlmMessage(role = "user", content = task)
        )
        // Agentes têm ACESSO COMPLETO às ferramentas reais (guardrails do ToolExecutor ativos).
        val tools = ToolDefinitions.getFullAgentToolsSchema(workspaceRoot)
        for (step in 1..6) {
            val response = llmClient.chatCompletion(provider, messages.toList(), tools, temperature = 0.2)
                .getOrElse { return@withContext "Agente falhou: ${it.message}" }
            messages.add(LlmMessage(role = "assistant", content = response.content, toolCalls = response.toolCalls))
            if (response.toolCalls.isEmpty()) return@withContext response.content.ifBlank { "Sem descobertas." }
            for (call in response.toolCalls) {
                val result = toolExecutor.executeTool(call, 0L)
                messages.add(LlmMessage(role = "tool", content = result.take(8000), toolCallId = call.id, toolName = call.name))
            }
        }
        "Agente atingiu limite de passos. Resumo: ${messages.lastOrNull { it.role == "assistant" }?.content.orEmpty().take(2000)}"
    }
}
