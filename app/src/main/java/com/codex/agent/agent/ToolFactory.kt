package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * FÁBRICA DE FERRAMENTAS — permite que o agente CRIE novas ferramentas em runtime
 * quando nenhuma existente resolve o problema (auto-tooling).
 *
 * Uma ferramenta criada é um JSON em `.codex/custom_tools/<nome>.json`:
 * ```json
 * {
 *   "name": "meu_tool",
 *   "description": "...",
 *   "parameters": {"type":"object","properties":{...}},
 *   "command": "python3 .codex/tools/meu_tool.py"
 * }
 * ```
 *
 * SEGURANÇA:
 *  - Nome validado (identificador) — impede path traversal.
 *  - Comando passado pelo ShellSafetyGuard — bloqueia destrutivos.
 *  - Args passados via arquivo JSON temporário (não por interpolação de shell).
 */
class ToolFactory(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'action' é obrigatório."
        return try {
            when (action) {
                "create_tool" -> createTool(args)
                "auto_create" -> autoCreate(args)
                "list_tools" -> listTools()
                "delete_tool" -> deleteTool(args)
                "test_tool" -> testTool(args)
                else -> "Ação desconhecida em tool_factory: $action"
            }
        } catch (e: Exception) {
            "Erro em tool_factory ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    /**
     * AUTO-CREATE: gera uma ferramenta completa a partir de uma descrição em linguagem natural,
     * quando o agente não encontra ferramenta existente para a tarefa. Cria script Python
     * com argparse lendo TOOL_ARGS_FILE (JSON) e spec JSON automaticamente.
     */
    private suspend fun autoCreate(args: JsonObject): String {
        val nameRaw = args["name"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'name' obrigatório."
        val name = nameRaw.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        if (!validName(name)) return "Erro: nome inválido: $name"
        val description = args["description"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'description' obrigatório (o que a ferramenta faz)."
        val logic = args["logic"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'logic' obrigatório (código Python ou descrição do passo a passo)."
        val lang = (args["lang"]?.jsonPrimitive?.contentOrNull ?: "python").lowercase()

        val dir = toolsDir()
        val scriptsDir = File(workRoot(), ".codex/tools").apply { mkdirs() }
        val ext = if (lang == "shell") "sh" else "py"
        val scriptFile = File(scriptsDir, "$name.$ext")

        val script = if (lang == "python") {
            """
                #!/usr/bin/env python3
                # $description
                # Gerado automaticamente pelo Codex Agent (tool_factory auto_create)
                import os, json, sys

                def main():
                    args_file = os.environ.get("TOOL_ARGS_FILE")
                    args = {}
                    if args_file and os.path.exists(args_file):
                        try:
                            with open(args_file, encoding="utf-8") as f:
                                args = json.load(f)
                        except Exception as e:
                            print("ERRO ao ler argumentos:", e)
                            return 1

                    # ---- LÓGICA DA FERRAMENTA ----
                $logic

                if __name__ == "__main__":
                    sys.exit(main() or 0)
            """.trimIndent()
        } else {
            """
                #!/bin/sh
                # $description
                # Gerado automaticamente pelo Codex Agent
                ARGS_FILE="${'$'}{TOOL_ARGS_FILE:-}"
                $logic
            """.trimIndent()
        }
        scriptFile.writeText(script)
        scriptFile.setExecutable(true)

        val params = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("input", buildJsonObject {
                    put("type", "string")
                    put("description", "Entrada para a ferramenta")
                })
            })
        }
        val spec = buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", params)
            put("command", if (lang == "shell") "sh ${scriptFile.absolutePath}" else "python3 ${scriptFile.absolutePath}")
        }
        File(dir, "$name.json").writeText(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), spec))

        // Testa automaticamente a ferramenta criada
        val testResult = testTool(buildJsonObject {
            put("name", name)
            put("test_args", "{}")
        })

        return "✅ Ferramenta criada automaticamente: '$name'\n" +
            "Descrição: $description\n" +
            "Script: ${scriptFile.absolutePath}\n" +
            "Spec: ${File(dir, "$name.json").absolutePath}\n" +
            "Linguagem: $lang\n\n" +
            "Teste automático:\n$testResult\n\n" +
            "A ferramenta '$name' já está registrada e pode ser chamada pelo nome."
    }

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT

    private suspend fun toolsDir(): File {
        val root = workRoot()
        return File(root, Constants.CUSTOM_TOOLS_DIR).apply { mkdirs() }
    }

    private fun validName(name: String): Boolean =
        name.matches(Regex("^[a-z][a-z0-9_]{1,48}$"))

    /** Cria uma ferramenta: grava o spec JSON e (opcionalmente) um script de implementação. */
    private suspend fun createTool(args: JsonObject): String {
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: return "Erro: 'name' obrigatório."
        if (!validName(name)) return "Erro: nome inválido (use letras minúsculas, números e _, começando com letra)."

        val description = args["description"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'description' obrigatório."
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'command' obrigatório."
        val scriptCode = args["script"]?.jsonPrimitive?.contentOrNull
        val params = args["parameters"]?.jsonObject ?: buildJsonObject { put("type", "object") }

        // Guardrail no comando (nunca cria tool com comando destrutivo)
        val block = com.codex.agent.shell.ShellSafetyGuard.analyze(command)
        if (block != null) return "Erro: comando da ferramenta bloqueado pelo guardrail: $block"

        val dir = toolsDir()
        val root = dir.parentFile ?: dir

        // Se veio código de script, salva em .codex/tools/<name>.<ext>
        var effectiveCommand = command
        if (!scriptCode.isNullOrBlank()) {
            val isPython = command.contains("python", ignoreCase = true)
            val ext = if (isPython) "py" else "sh"
            val scriptsDir = File(root, ".codex/tools").apply { mkdirs() }
            val scriptFile = File(scriptsDir, "$name.$ext")
            scriptFile.writeText(scriptCode)
            effectiveCommand = command.replace("{script}", scriptFile.absolutePath)
        }

        val spec = buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", params)
            put("command", effectiveCommand)
        }
        val specFile = File(dir, "$name.json")
        specFile.writeText(json.encodeToString(JsonObject.serializer(), spec))

        return "✅ Ferramenta '$name' criada e registrada.\n" +
            "Arquivo: ${specFile.absolutePath}\n" +
            "Comando: $effectiveCommand\n" +
            "Use: chame a tool '$name' com os argumentos definidos em 'parameters'."
    }

    private suspend fun listTools(): String {
        val dir = toolsDir()
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        if (files.isEmpty()) return "Nenhuma ferramenta customizada criada ainda."
        return buildString {
            appendLine("=== Ferramentas criadas pelo agente (${files.size}) ===")
            files.forEach { f ->
                val spec = runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull()
                val desc = spec?.get("description")?.jsonPrimitive?.contentOrNull ?: "?"
                val cmd = spec?.get("command")?.jsonPrimitive?.contentOrNull ?: "?"
                appendLine("• ${f.nameWithoutExtension}: $desc")
                appendLine("    comando: ${cmd.take(120)}")
            }
        }
    }

    private suspend fun deleteTool(args: JsonObject): String {
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: return "Erro: 'name' obrigatório."
        if (!validName(name)) return "Erro: nome inválido."
        val file = File(toolsDir(), "$name.json")
        val script = File(workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT, ".codex/tools/$name.sh")
        val scriptPy = File(workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT, ".codex/tools/$name.py")
        var deleted = false
        if (file.exists()) { deleted = file.delete() || deleted }
        if (script.exists()) script.delete()
        if (scriptPy.exists()) scriptPy.delete()
        return if (deleted) "Ferramenta '$name' removida." else "Ferramenta '$name' não encontrada."
    }

    /** Testa uma ferramenta criada com args de exemplo. */
    private suspend fun testTool(args: JsonObject): String {
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: return "Erro: 'name' obrigatório."
        if (!validName(name)) return "Erro: nome inválido."
        val specFile = File(toolsDir(), "$name.json")
        if (!specFile.exists()) return "Erro: ferramenta '$name' não existe."
        val spec = runCatching { json.parseToJsonElement(specFile.readText()).jsonObject }
            .getOrElse { return "Erro: JSON inválido: ${it.message}" }
        val command = spec["command"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: spec sem 'command'."
        val block = com.codex.agent.shell.ShellSafetyGuard.analyze(command)
        if (block != null) return "Erro: comando bloqueado: $block"
        val root = workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT
        val testArgs = args["test_args"]?.jsonObject?.toString() ?: "{}"
        // Passa args por arquivo temporário (seguro, sem interpolação de shell)
        val argsFile = File(root, ".codex/tools/_test_args_${System.currentTimeMillis()}.json").apply {
            parentFile?.mkdirs(); writeText(testArgs)
        }
        val result = shellExecutor.execute("TOOL_ARGS_FILE='${argsFile.absolutePath}' $command", workdir = root, timeoutSec = 120)
        argsFile.delete()
        return "Teste de '$name' (exit=${result.exitCode}):\n${result.output.take(4000)}"
    }
}
