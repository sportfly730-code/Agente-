package com.codex.agent.agent

import com.codex.agent.data.repository.WorkspaceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cliente MCP (Model Context Protocol) sobre Streamable HTTP / JSON-RPC 2.0.
 * Permite ao agente consumir ferramentas expostas por servidores MCP externos
 * (GitHub, bancos de dados, filesystem, etc.) de forma padronizada.
 *
 * Configurações dos servidores são persistidas em `.codex/mcp_servers.json` no workspace.
 * Suporta: add_server, list_servers, remove_server, list_tools, call_tool, ping.
 */
class McpClient(
    private val workspaceRepository: WorkspaceRepository
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ServerConfig(val name: String, val url: String, val headersJson: String = "{}")

    private suspend fun configFile(): File {
        val root = workspaceRepository.active()?.rootPath
            ?: com.codex.agent.data.model.Constants.DEFAULT_WORKSPACE_ROOT
        return File(root, ".codex/mcp_servers.json")
    }

    private suspend fun loadServers(): MutableList<ServerConfig> {
        val file = configFile()
        if (!file.exists()) return mutableListOf()
        return runCatching {
            val arr = json.parseToJsonElement(file.readText()).jsonArray
            arr.map { el ->
                val obj = el.jsonObject
                ServerConfig(
                    name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    headersJson = obj["headers"]?.jsonPrimitive?.contentOrNull ?: "{}"
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private suspend fun saveServers(servers: List<ServerConfig>) {
        val file = configFile()
        file.parentFile?.mkdirs()
        val arr = kotlinx.serialization.json.buildJsonArray {
            servers.forEach { s ->
                add(buildJsonObject {
                    put("name", s.name)
                    put("url", s.url)
                    put("headers", s.headersJson)
                })
            }
        }
        file.writeText(arr.toString())
    }

    suspend fun handle(args: JsonObject): String = runCatching { handleInner(args) }
        .getOrElse { "Erro MCP: ${it.javaClass.simpleName} - ${it.message}" }

    private suspend fun handleInner(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' é obrigatório."
        return when (action) {
            "add_server" -> {
                val name = args["name"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'name' é obrigatório."
                val url = args["url"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'url' é obrigatório."
                val headers = args["headers"]?.jsonPrimitive?.contentOrNull ?: "{}"
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return "Erro: URL do servidor MCP deve começar com http:// ou https://"
                }
                val servers = loadServers()
                servers.removeAll { it.name == name }
                servers.add(ServerConfig(name, url, headers))
                saveServers(servers)
                "Servidor MCP '$name' registrado: $url"
            }
            "list_servers" -> {
                val servers = loadServers()
                if (servers.isEmpty()) "Nenhum servidor MCP configurado. Use action='add_server'." else
                    servers.joinToString("\n") { "• ${it.name}: ${it.url}" }
            }
            "remove_server" -> {
                val name = args["name"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'name' é obrigatório."
                val servers = loadServers()
                val removed = servers.removeAll { it.name == name }
                saveServers(servers)
                if (removed) "Servidor MCP '$name' removido." else "Servidor '$name' não encontrado."
            }
            "list_tools" -> {
                val name = args["server"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'server' é obrigatório."
                val server = loadServers().firstOrNull { it.name == name }
                    ?: return "Erro: servidor '$name' não encontrado."
                val response = rpc(server, "tools/list", buildJsonObject {}, initialized = true)
                formatToolsResponse(response)
            }
            "call_tool" -> {
                val name = args["server"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'server' é obrigatório."
                val toolName = args["tool_name"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'tool_name' é obrigatório."
                val arguments = runCatching {
                    json.parseToJsonElement(args["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}").jsonObject
                }.getOrDefault(buildJsonObject {})
                val server = loadServers().firstOrNull { it.name == name }
                    ?: return "Erro: servidor '$name' não encontrado."
                val params = buildJsonObject {
                    put("name", toolName)
                    put("arguments", arguments)
                }
                val response = rpc(server, "tools/call", params, initialized = true)
                formatCallResponse(response)
            }
            "ping" -> {
                val name = args["server"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'server' é obrigatório."
                val server = loadServers().firstOrNull { it.name == name }
                    ?: return "Erro: servidor '$name' não encontrado."
                val response = rpc(server, "ping", buildJsonObject {}, initialized = false)
                if (response != null) "Servidor '$name' respondeu (MCP OK)." else "Servidor '$name' não respondeu."
            }
            else -> "Ação desconhecida em mcp_client: $action"
        }
    }

    /** Executa uma chamada JSON-RPC 2.0. Faz initialize primeiro se necessário. */
    private fun rpc(server: ServerConfig, method: String, params: JsonObject, initialized: Boolean): JsonObject? {
        if (initialized) {
            // Handshake initialize (MCP requer antes de tools/list e tools/call)
            val initParams = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "CodexAgent")
                    put("version", "1.0")
                })
            }
            sendRequest(server, "initialize", initParams)
            // notificação initialized
            sendRequest(server, "notifications/initialized", buildJsonObject {}, notification = true)
        }
        return sendRequest(server, method, params)
    }

    private fun sendRequest(server: ServerConfig, method: String, params: JsonObject, notification: Boolean = false): JsonObject? {
        val id = if (notification) null else System.currentTimeMillis()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            put("params", params)
        }
        val builder = Request.Builder()
            .url(server.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        runCatching {
            val headers = json.parseToJsonElement(server.headersJson).jsonObject
            headers.forEach { (k, v) ->
                (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { builder.addHeader(k, it) }
            }
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return buildJsonObject {
                put("error", "HTTP ${response.code}: ${body.take(500)}")
            }
            // Streamable HTTP pode devolver SSE; extrair o primeiro "data:" com JSON
            val jsonLine = body.lines().firstOrNull { it.trim().startsWith("data:") }
                ?.removePrefix("data:")?.trim()
                ?: body.trim()
            return runCatching { json.parseToJsonElement(jsonLine).jsonObject }.getOrNull()
        }
    }

    private fun formatToolsResponse(response: JsonObject?): String {
        if (response == null) return "Erro: resposta inválida do servidor MCP."
        response["error"]?.jsonPrimitive?.contentOrNull?.let { return "Erro MCP: $it" }
        val tools = response["result"]?.jsonObject?.get("tools")?.jsonArray
            ?: return "Nenhuma ferramenta listada ou resposta inesperada: ${response.toString().take(500)}"
        if (tools.isEmpty()) return "Servidor MCP não expõe ferramentas."
        return buildString {
            appendLine("Ferramentas MCP disponíveis (${tools.size}):")
            tools.forEach { el ->
                val obj = el.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "?"
                val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                appendLine("• $name — $desc")
            }
            appendLine()
            appendLine("Use mcp_client(action='call_tool', server=..., tool_name=..., arguments={...}).")
        }
    }

    private fun formatCallResponse(response: JsonObject?): String {
        if (response == null) return "Erro: resposta inválida do servidor MCP."
        response["error"]?.jsonPrimitive?.contentOrNull?.let { return "Erro MCP: $it" }
        val result = response["result"]?.jsonObject ?: return "Resposta inesperada: ${response.toString().take(500)}"
        val content = result["content"]?.jsonArray
        if (content != null) {
            return buildString {
                content.forEach { el ->
                    val text = el.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: el.toString()
                    appendLine(text)
                }
            }.trimEnd()
        }
        return result.toString()
    }
}
