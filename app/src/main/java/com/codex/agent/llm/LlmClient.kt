package com.codex.agent.llm

import com.codex.agent.data.entity.ProviderConfigEntity
import com.codex.agent.data.model.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente universal de LLM:
 * - Endpoints OpenAI-compatible (OpenAI, Gemini /v1beta/openai/, DeepSeek, OpenRouter, Groq,
 *   Ollama, LM Studio, proxies customizados) e protocolo nativo Anthropic /v1/messages.
 * - Dual-model: modelo principal (Coder/Architect) e modelo rápido (utility) para compactação.
 * - Streaming SSE com conteúdo, raciocínio (reasoning) e acumulação de tool calls.
 * - Vision multimodal (Base64) em mensagens de usuário.
 * - Retry automático com backoff exponencial (429/500/502/503/504/529 + IOException).
 * - Cabeçalhos extras por provedor (extraHeadersJson).
 */
class LlmClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------------
    // API pública
    // ------------------------------------------------------------------

    /** Chamada bloqueante (não-stream), usada para compactação/sumarização com o modelo utility. */
    suspend fun chatCompletion(
        provider: ProviderConfigEntity,
        messages: List<LlmMessage>,
        toolsSchema: JsonArray? = null,
        useUtilityModel: Boolean = false,
        temperature: Double? = null
    ): Result<LlmResponse> = withContext(Dispatchers.IO) {
        val authError = checkAuth(provider)
        if (authError != null) return@withContext Result.failure(authError)

        val model = selectModel(provider, useUtilityModel)
        val effectiveTemperature = temperature ?: provider.temperature

        try {
            val response = withRetry {
                if (provider.kind == ProviderKind.ANTHROPIC_NATIVE) {
                    executeAnthropicBlocking(provider, model, messages, toolsSchema, effectiveTemperature)
                } else {
                    executeOpenAiBlocking(provider, model, messages, toolsSchema, effectiveTemperature)
                }
            }
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streaming SSE. Emite deltas de conteúdo/reasoning nos callbacks e devolve a resposta
     * final consolidada (com todas as tool calls acumuladas).
     */
    suspend fun streamChat(
        provider: ProviderConfigEntity,
        messages: List<LlmMessage>,
        toolsSchema: JsonArray? = null,
        useUtilityModel: Boolean = false,
        callbacks: LlmStreamCallbacks = LlmStreamCallbacks()
    ): Result<LlmResponse> = withContext(Dispatchers.IO) {
        val authError = checkAuth(provider)
        if (authError != null) return@withContext Result.failure(authError)

        val model = selectModel(provider, useUtilityModel)
        val isAnthropic = provider.kind == ProviderKind.ANTHROPIC_NATIVE

        var emitted = false
        val guarded = LlmStreamCallbacks(
            onContent = { emitted = true; callbacks.onContent(it) },
            onReasoning = { emitted = true; callbacks.onReasoning(it) },
            onStatus = { callbacks.onStatus(it) }
        )

        var lastError: Throwable? = null
        for (attempt in 0 until MAX_RETRIES) {
            val accum = StreamAccumulator()
            try {
                guarded.onStatus(if (attempt == 0) "Conectando ao modelo $model…" else "Retry $attempt após falha transitória…")
                val usage = if (isAnthropic) {
                    streamAnthropic(provider, model, messages, toolsSchema, accum, guarded)
                } else {
                    streamOpenAiCompatible(provider, model, messages, toolsSchema, accum, guarded)
                }
                val response = accum.toResponse(usage)
                guarded.onStatus("Resposta recebida (${response.totalTokens} tokens).")
                return@withContext Result.success(response)
            } catch (e: RetryableHttpException) {
                lastError = e
                if (emitted) break
                delay(backoffMs(attempt))
            } catch (e: IOException) {
                lastError = e
                if (emitted) break
                delay(backoffMs(attempt))
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
        Result.failure(lastError ?: IOException("Streaming falhou após $MAX_RETRIES tentativas."))
    }

    /**
     * Streaming com FALLBACK entre múltiplos provedores.
     * Tenta cada provedor na ordem; se falhar (antes de emitir conteúdo), tenta o próximo.
     * Usa os callbacks do primeiro provedor que responder — o status informa a troca.
     */
    suspend fun streamChatWithFallback(
        providers: List<ProviderConfigEntity>,
        messages: List<LlmMessage>,
        toolsSchema: JsonArray? = null,
        useUtilityModel: Boolean = false,
        callbacks: LlmStreamCallbacks = LlmStreamCallbacks()
    ): Result<LlmResponse> = withContext(Dispatchers.IO) {
        var primaryError: Throwable? = null
        var lastError: Throwable? = null
        // CORREÇÃO A4: rastreia se algum provedor já emitiu conteúdo. Se emitiu e falhou,
        // NÃO tenta o próximo — isso duplicaria o texto já enviado à UI/histórico.
        var anyEmitted = false
        for ((index, provider) in providers.withIndex()) {
            if (index > 0) {
                callbacks.onStatus("Trocando para provedor alternativo: ${provider.name}…")
            }
            var emittedThisProvider = false
            val guardedCallbacks = LlmStreamCallbacks(
                onContent = { delta ->
                    if (delta.isNotBlank()) { anyEmitted = true; emittedThisProvider = true }
                    callbacks.onContent(delta)
                },
                onReasoning = { delta -> callbacks.onReasoning(delta) },
                onStatus = { s -> callbacks.onStatus(s) }
            )
            val result = streamChat(provider, messages, toolsSchema, useUtilityModel, guardedCallbacks)
            if (result.isSuccess) return@withContext result
            if (index == 0) primaryError = result.exceptionOrNull()
            lastError = result.exceptionOrNull()
            if (anyEmitted) {
                // Conteúdo parcial já foi enviado — não reemite do início em outro provedor.
                return@withContext result
            }
        }
        // Reporta o erro do provedor PRINCIPAL — o do último fallback (ex.: Ollama offline)
        // costuma ser enganoso e esconde a causa real (ex.: 503 da Vyce).
        Result.failure(
            primaryError ?: lastError ?: IOException("Todos os provedores falharam.")
        )
    }

    // ------------------------------------------------------------------
    // Utilitários
    // ------------------------------------------------------------------

    fun selectModel(provider: ProviderConfigEntity, useUtilityModel: Boolean): String {
        val rawModel = if (useUtilityModel && provider.utilityModel.isNotBlank()) provider.utilityModel
        else provider.mainModel.ifBlank { provider.utilityModel.ifBlank { "glm-5.3-flash" } }

        var cleanModel = rawModel.trim()
        if (cleanModel.startsWith("models/")) {
            cleanModel = cleanModel.removePrefix("models/")
        }

        return when (cleanModel.lowercase()) {
            "glm 5.3 flash", "glm-5.3 flash", "glm 5.3" -> "glm-5.3-flash"
            // NÃO remapeia modelos Gemini aqui: respeita exatamente o modelo configurado
            // (gemini-2.5-pro, gemini-3-pro, gemini-2.5-flash, etc.) — o usuário escolhe.
            else -> cleanModel
        }
    }

    private fun checkAuth(provider: ProviderConfigEntity): Exception? {
        // (modelo local removido)
        val base = provider.baseUrl.lowercase()
        val name = provider.name.lowercase()
        val local = base.contains("localhost") || base.contains("127.0.0.1") || base.contains("10.0.2.2") || base.contains("192.168.")
        val isVyce = base.contains("vyceai.com") || name.contains("vyce")
        return if (provider.apiKey.isBlank() && !local && !isVyce) {
            IllegalStateException("API Key não configurada para '${provider.name}'. Configure na tela de Provedores.")
        } else null
    }

    private fun backoffMs(attempt: Int): Long =
        BACKOFF_BASE_MS * (1L shl attempt) + (0..120).random()

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastError: Throwable? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                return block()
            } catch (e: RetryableHttpException) {
                lastError = e
                delay(backoffMs(attempt))
            } catch (e: IOException) {
                lastError = e
                if (attempt == MAX_RETRIES - 1) throw e
                delay(backoffMs(attempt))
            }
        }
        throw lastError ?: IOException("Falha desconhecida após $MAX_RETRIES tentativas.")
    }

    private fun applyExtraHeaders(builder: Request.Builder, extraHeadersJson: String) {
        if (extraHeadersJson.isBlank() || extraHeadersJson.trim() == "{}") return
        runCatching {
            val map = json.parseToJsonElement(extraHeadersJson).jsonObject
            for ((key, value) in map) {
                val valueStr = (value as? JsonPrimitive)?.contentOrNull
                if (!valueStr.isNullOrBlank()) builder.addHeader(key, valueStr)
            }
        }
    }

    private fun buildRequest(
        provider: ProviderConfigEntity,
        url: String,
        payloadJson: String
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .post(payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
        when (provider.kind) {
            ProviderKind.ANTHROPIC_NATIVE -> {
                builder.addHeader("x-api-key", provider.apiKey.trim())
                builder.addHeader("anthropic-version", "2023-06-01")
            }
            ProviderKind.OPENAI_COMPATIBLE -> {
                val key = provider.apiKey.trim().ifBlank {
                    val base = provider.baseUrl.lowercase()
                    val name = provider.name.lowercase()
                    if (base.contains("vyceai.com") || name.contains("vyce")) "vyce_free_access" else ""
                }
                if (key.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer $key")
                }
            }
            else -> {}  // (modelo local removido)
        }
        applyExtraHeaders(builder, provider.extraHeadersJson)
        return builder.build()
    }

    private fun httpError(resp: Response, prefix: String): IOException {
        val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
        val hint = when (resp.code) {
            401 -> " (API Key inválida ou ausente — verifique a chave e o header de autenticação)"
            403 -> " (Acesso negado — verifique permissões da key e o endpoint)"
            404 -> " (Endpoint não encontrado — verifique se a Base URL inclui /v1, ex.: https://api.openai.com/v1)"
            429 -> " (Rate limit — aguarde ou troque de modelo/provedor)"
            else -> ""
        }
        val message = "$prefix HTTP ${resp.code}$hint: ${body.take(1500)}"
        return if (resp.code in RETRYABLE_HTTP_CODES) RetryableHttpException(message) else IOException(message)
    }

    // ------------------------------------------------------------------
    // OpenAI-compatible — bloqueante
    // ------------------------------------------------------------------

    /**
     * Normaliza a Base URL do provedor para a URL completa do endpoint de chat.
     * Lida com URLs sem /v1, trailing slashes, e endpoints do Gemini.
     * Se a URL já contém o endpoint completo, retorna como está.
     */
    private fun resolveOpenAiUrl(baseUrl: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return base

        // Gemini: garantir /openai no final
        if (base.contains("generativelanguage.googleapis.com") && !base.contains("/openai")) {
            base = if (base.endsWith("/v1beta")) "$base/openai" else "$base/v1beta/openai"
        }

        // Se já tem o endpoint completo, não mexer
        if (base.endsWith("/chat/completions")) return base

        // Se não termina com uma versão conhecida, adicionar /v1
        // (a maioria dos provedores OpenAI-compatible usa /v1)
        val lastSegment = base.substringAfterLast('/')
        val knownVersionSegments = setOf("v1", "v1beta", "v2", "v3", "openai", "api", "v4")
        if (lastSegment !in knownVersionSegments && !base.contains("/chat/completions")) {
            base = "$base/v1"
        }

        return "$base/chat/completions"
    }

    /**
     * Normaliza a Base URL para o endpoint de mensagens da Anthropic.
     * Garante /v1 no final se ausente.
     */
    private fun resolveAnthropicUrl(baseUrl: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return base

        if (base.endsWith("/messages")) return base

        val lastSegment = base.substringAfterLast('/')
        if (lastSegment != "v1") {
            base = "$base/v1"
        }

        return "$base/messages"
    }

    private fun executeOpenAiBlocking(
        provider: ProviderConfigEntity,
        model: String,
        messages: List<LlmMessage>,
        toolsSchema: JsonArray?,
        temperature: Double
    ): LlmResponse {
        val url = resolveOpenAiUrl(provider.baseUrl)
        val payload = buildJsonObject {
            put("model", model)
            put("messages", buildOpenAiMessages(messages))
            put("temperature", temperature)
            put("max_tokens", provider.maxOutputTokens)
            if (!toolsSchema.isNullOrEmpty()) {
                put("tools", toolsSchema)
                put("tool_choice", "auto")
            }
        }
        val request = buildRequest(provider, url, json.encodeToString(JsonObject.serializer(), payload))
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw httpError(resp, "Erro API")
            val raw = resp.body?.string().orEmpty()
            val root = json.parseToJsonElement(raw).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw IOException("Resposta sem 'choices': ${raw.take(500)}")
            val message = choice["message"]?.jsonObject ?: throw IOException("Resposta sem 'message'.")
            val content = message["content"]?.let { el ->
                when (el) {
                    is JsonPrimitive -> el.contentOrNull.orEmpty()
                    is JsonArray -> el.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
                    else -> el.toString()
                }
            }.orEmpty()
            val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
                ?: message["reasoning"]?.jsonPrimitive?.contentOrNull
            val toolCalls = parseOpenAiToolCalls(message["tool_calls"]?.jsonArray)
            val usage = root["usage"]?.jsonObject
            val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.intOrNull
                ?: (promptTokens + completionTokens)
            return LlmResponse(
                content = content,
                reasoning = reasoning?.takeIf { it.isNotBlank() },
                toolCalls = toolCalls,
                promptTokens = promptTokens.takeIf { it > 0 } ?: TokenEstimator.estimate(content),
                completionTokens = completionTokens.takeIf { it > 0 } ?: TokenEstimator.estimate(content),
                totalTokens = totalTokens.takeIf { it > 0 } ?: TokenEstimator.estimate(content)
            )
        }
    }

    private fun parseOpenAiToolCalls(array: JsonArray?): List<ToolCallRequest> {
        if (array == null) return emptyList()
        val result = mutableListOf<ToolCallRequest>()
        for (element in array) {
            val obj = element.jsonObject
            val function = obj["function"]?.jsonObject ?: continue
            val name = function["name"]?.jsonPrimitive?.contentOrNull ?: continue
            result.add(
                ToolCallRequest(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "call_${System.nanoTime()}",
                    name = name,
                    argumentsJson = function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                )
            )
        }
        return result
    }

    // ------------------------------------------------------------------
    // OpenAI-compatible — streaming SSE
    // ------------------------------------------------------------------

    private fun streamOpenAiCompatible(
        provider: ProviderConfigEntity,
        model: String,
        messages: List<LlmMessage>,
        toolsSchema: JsonArray?,
        accum: StreamAccumulator,
        callbacks: LlmStreamCallbacks
    ): Usage {
        val url = resolveOpenAiUrl(provider.baseUrl)
        val payload = buildJsonObject {
            put("model", model)
            put("messages", buildOpenAiMessages(messages))
            put("temperature", provider.temperature)
            put("max_tokens", provider.maxOutputTokens)
            put("stream", true)
            if (!toolsSchema.isNullOrEmpty()) {
                put("tools", toolsSchema)
                put("tool_choice", "auto")
            }
        }
        val request = buildRequest(provider, url, json.encodeToString(JsonObject.serializer(), payload))
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw httpError(resp, "Erro API (stream)")
            val source = resp.body?.source() ?: throw IOException("Corpo vazio no stream.")
            var promptUsage = 0
            var completionUsage = 0
            try {
                while (true) {
                    val line = try {
                        source.readUtf8LineStrict()
                    } catch (e: EOFException) {
                        break
                    }
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    // CORREÇÃO: alguns provedores (ex.: Vyce) retornam HTTP 200 com um objeto
                    // de erro dentro do stream SSE. Detectar aqui para gerar erro claro e
                    // permitir o fallback de provedor funcionar.
                    chunk["error"]?.let { errObj ->
                        val msg = errObj.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                            ?: errObj.toString()
                        val code = errObj.jsonObject["code"]?.jsonPrimitive?.contentOrNull ?: ""
                        throw RetryableHttpException("Erro no stream ($code): $msg")
                    }
                    chunk["usage"]?.jsonObject?.let { usage ->
                        promptUsage = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: promptUsage
                        completionUsage = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: completionUsage
                    }
                    val delta = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: continue
                    delta["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                        accum.content.append(it)
                        callbacks.onContent(it)
                    }
                    (delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                        ?: delta["reasoning"]?.jsonPrimitive?.contentOrNull)?.takeIf { it.isNotEmpty() }?.let {
                        accum.reasoning.append(it)
                        callbacks.onReasoning(it)
                    }
                    delta["tool_calls"]?.jsonArray?.forEach { element ->
                        val obj = element.jsonObject
                        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val builder = accum.toolCalls.getOrPut(index) {
                            ToolCallAccum(
                                id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                name = obj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                            )
                        }
                        obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { builder.id = it }
                        obj["function"]?.jsonObject?.let { function ->
                            function["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { builder.name = it }
                            function["arguments"]?.jsonPrimitive?.contentOrNull?.let { builder.args.append(it) }
                        }
                    }
                }
            } finally {
                runCatching { source.close() }
            }
            return Usage(promptUsage, completionUsage)
        }
    }

    /** Monta o array de mensagens no formato OpenAI (com visão e tool results). */
    private fun buildOpenAiMessages(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        for (message in messages) {
            add(buildJsonObject {
                put("role", message.role)
                when {
                    message.role == "tool" -> {
                        put("tool_call_id", message.toolCallId ?: "unknown")
                        message.toolName?.let { put("name", it) }
                        put("content", message.content)
                    }
                    message.role == "assistant" && message.toolCalls.isNotEmpty() -> {
                        put("content", message.content)
                        put("tool_calls", buildJsonArray {
                            for (toolCall in message.toolCalls) {
                                add(buildJsonObject {
                                    put("id", toolCall.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", toolCall.name)
                                        put("arguments", toolCall.argumentsJson)
                                    })
                                })
                            }
                        })
                    }
                    !message.imageBase64.isNullOrBlank() && message.role == "user" -> {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            })
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:image/jpeg;base64,${message.imageBase64}")
                                })
                            })
                        })
                    }
                    else -> {
                        put("content", message.content)
                    }
                }
            })
        }
    }

    // ------------------------------------------------------------------
    // Anthropic nativo — bloqueante
    // ------------------------------------------------------------------

    private fun executeAnthropicBlocking(
        provider: ProviderConfigEntity,
        model: String,
        messages: List<LlmMessage>,
        toolsSchema: JsonArray?,
        temperature: Double
    ): LlmResponse {
        val url = resolveAnthropicUrl(provider.baseUrl)
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", provider.maxOutputTokens.coerceIn(1024, 64000))
            put("temperature", temperature)
            val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
            if (system.isNotBlank()) put("system", system)
            put("messages", buildAnthropicMessages(messages))
            val anthropicTools = convertToolsToAnthropic(toolsSchema)
            if (anthropicTools.isNotEmpty()) put("tools", buildJsonArray {
                anthropicTools.forEach { add(it) }
            })
        }
        val request = buildRequest(provider, url, json.encodeToString(JsonObject.serializer(), payload))
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw httpError(resp, "Erro Anthropic")
            val raw = resp.body?.string().orEmpty()
            val root = json.parseToJsonElement(raw).jsonObject
            val content = root["content"]?.jsonArray ?: JsonArray(emptyList())
            val textBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            val toolCalls = mutableListOf<ToolCallRequest>()
            for (block in content) {
                val obj = block.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> textBuilder.append(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "thinking" -> reasoningBuilder.append(obj["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "tool_use" -> toolCalls.add(
                        ToolCallRequest(
                            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "toolu_${System.nanoTime()}",
                            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue,
                            argumentsJson = obj["input"]?.toString() ?: "{}"
                        )
                    )
                }
            }
            val usage = root["usage"]?.jsonObject
            val inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
            return LlmResponse(
                content = textBuilder.toString(),
                reasoning = reasoningBuilder.toString().takeIf { it.isNotBlank() },
                toolCalls = toolCalls,
                promptTokens = inputTokens,
                completionTokens = outputTokens,
                totalTokens = inputTokens + outputTokens
            )
        }
    }

    // ------------------------------------------------------------------
    // Anthropic nativo — streaming SSE
    // ------------------------------------------------------------------

    private fun streamAnthropic(
        provider: ProviderConfigEntity,
        model: String,
        messages: List<LlmMessage>,
        toolsSchema: JsonArray?,
        accum: StreamAccumulator,
        callbacks: LlmStreamCallbacks
    ): Usage {
        val url = resolveAnthropicUrl(provider.baseUrl)
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", provider.maxOutputTokens.coerceIn(1024, 64000))
            put("temperature", provider.temperature)
            put("stream", true)
            val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
            if (system.isNotBlank()) put("system", system)
            put("messages", buildAnthropicMessages(messages))
            val anthropicTools = convertToolsToAnthropic(toolsSchema)
            if (anthropicTools.isNotEmpty()) put("tools", buildJsonArray {
                anthropicTools.forEach { add(it) }
            })
        }
        val request = buildRequest(provider, url, json.encodeToString(JsonObject.serializer(), payload))
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw httpError(resp, "Erro Anthropic (stream)")
            val source = resp.body?.source() ?: throw IOException("Corpo vazio no stream.")
            var promptUsage = 0
            var completionUsage = 0
            try {
                while (true) {
                    val line = try {
                        source.readUtf8LineStrict()
                    } catch (e: EOFException) {
                        break
                    }
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    val event = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    when (event["type"]?.jsonPrimitive?.contentOrNull) {
                        "message_start" -> {
                            promptUsage = event["message"]?.jsonObject?.get("usage")?.jsonObject
                                ?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                        }
                        "content_block_start" -> {
                            val block = event["content_block"]?.jsonObject ?: continue
                            if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                                val index = event["index"]?.jsonPrimitive?.intOrNull ?: 0
                                accum.toolCalls.getOrPut(index) {
                                    ToolCallAccum(
                                        id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                        name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    )
                                }
                            }
                        }
                        "content_block_delta" -> {
                            val delta = event["delta"]?.jsonObject ?: continue
                            val index = event["index"]?.jsonPrimitive?.intOrNull ?: 0
                            when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                                    accum.content.append(it)
                                    callbacks.onContent(it)
                                }
                                "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                                    accum.reasoning.append(it)
                                    callbacks.onReasoning(it)
                                }
                                "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.contentOrNull?.let {
                                    accum.toolCalls[index]?.args?.append(it)
                                }
                            }
                        }
                        "message_delta" -> {
                            completionUsage = event["usage"]?.jsonObject?.get("output_tokens")
                                ?.jsonPrimitive?.intOrNull ?: completionUsage
                        }
                        "message_stop" -> break
                    }
                }
            } finally {
                runCatching { source.close() }
            }
            return Usage(promptUsage, completionUsage)
        }
    }

    /** Converte o JSON Schema (formato OpenAI) em input_schema Anthropic. */
    private fun convertToolsToAnthropic(toolsSchema: JsonArray?): List<JsonObject> {
        if (toolsSchema.isNullOrEmpty()) return emptyList()
        val result = mutableListOf<JsonObject>()
        for (tool in toolsSchema) {
            val function = tool.jsonObject["function"]?.jsonObject ?: continue
            val name = function["name"]?.jsonPrimitive?.contentOrNull ?: continue
            result.add(buildJsonObject {
                put("name", name)
                put("description", function["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("input_schema", function["parameters"] ?: buildJsonObject { put("type", "object") })
            })
        }
        return result
    }

    /** Monta mensagens no formato Anthropic (user/assistant com blocos + tool_result). */
    private fun buildAnthropicMessages(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        // CORREÇÃO M1: Anthropic só aceita system no topo (via parâmetro "system").
        // Injeções de sistema no meio do array ([CONTINUE], anti-loop, relatórios de
        // sub-agente) eram descartadas. Convertemos para mensagem "user" com prefixo
        // para que o modelo ainda as veja.
        var systemSeen = false
        for (message in messages) {
            if (message.role == "system") {
                if (!systemSeen) { systemSeen = true; continue }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "[INSTRUÇÃO DO SISTEMA] ${message.content}")
                })
                continue
            }
            when {
                message.role == "tool" -> add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", message.toolCallId ?: "unknown")
                            put("content", message.content)
                        })
                    })
                })
                message.role == "assistant" -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        if (!message.reasoning.isNullOrBlank()) {
                            add(buildJsonObject {
                                put("type", "thinking")
                                put("thinking", message.reasoning)
                            })
                        }
                        if (message.content.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            })
                        }
                        for (toolCall in message.toolCalls) {
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", toolCall.id)
                                put("name", toolCall.name)
                                put(
                                    "input",
                                    runCatching { json.parseToJsonElement(toolCall.argumentsJson) }
                                        .getOrDefault(buildJsonObject {})
                                )
                            })
                        }
                    })
                })
                else -> add(buildJsonObject {
                    put("role", "user")
                    if (!message.imageBase64.isNullOrBlank()) {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", message.imageBase64)
                                })
                            })
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            })
                        })
                    } else {
                        put("content", message.content)
                    }
                })
            }
        }
    }

    // ------------------------------------------------------------------
    // Acumuladores internos
    // ------------------------------------------------------------------

    private class ToolCallAccum(
        var id: String,
        var name: String
    ) {
        val args = StringBuilder()
    }

    private class StreamAccumulator {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = sortedMapOf<Int, ToolCallAccum>()

        fun toResponse(usage: Usage): LlmResponse {
            val calls = toolCalls.map { (index, accumulator) ->
                ToolCallRequest(
                    id = accumulator.id.ifBlank { "call_${index}_${System.nanoTime()}" },
                    name = accumulator.name,
                    argumentsJson = accumulator.args.toString().ifBlank { "{}" }
                )
            }
            val prompt = usage.prompt.takeIf { it > 0 }
                ?: TokenEstimator.estimate(content.toString() + reasoning.toString())
            val completion = usage.completion.takeIf { it > 0 }
                ?: TokenEstimator.estimate(content.toString())
            return LlmResponse(
                content = content.toString(),
                reasoning = reasoning.toString().takeIf { it.isNotBlank() },
                toolCalls = calls,
                promptTokens = prompt,
                completionTokens = completion,
                totalTokens = prompt + completion
            )
        }
    }

    /** Busca a lista de modelos disponíveis na API (OpenAI / Vyce AI / OpenRouter / Ollama / Gemini / etc). */
    suspend fun fetchAvailableModels(provider: ProviderConfigEntity): Result<List<DiscoveredModel>> = withContext(Dispatchers.IO) {
        val rawBase = provider.baseUrl.trim()
        if (rawBase.isBlank()) return@withContext Result.failure(IllegalArgumentException("Base URL não pode estar em branco."))

        var base = rawBase.trimEnd('/')
        if (base.contains("generativelanguage.googleapis.com") && !base.contains("/openai")) {
            base = if (base.endsWith("/v1beta")) "$base/openai" else "$base/v1beta/openai"
        }

        // Normalizar /v1 se ausente
        val lastSegment = base.substringAfterLast('/')
        val knownVersionSegments = setOf("v1", "v1beta", "v2", "v3", "openai", "api", "v4", "models")
        if (lastSegment !in knownVersionSegments && !base.endsWith("/models")) {
            base = "$base/v1"
        }

        val url = when {
            base.endsWith("/models") -> base
            base.endsWith("/") -> "${base}models"
            else -> "$base/models"
        }

        try {
            val requestBuilder = Request.Builder().url(url).get()
            val key = provider.apiKey.trim().ifBlank {
                val name = provider.name.lowercase()
                if (base.contains("vyceai.com") || name.contains("vyce")) "vyce_free_access" else ""
            }
            if (key.isNotBlank()) {
                when (provider.kind) {
                    ProviderKind.ANTHROPIC_NATIVE -> requestBuilder.addHeader("x-api-key", key)
                    ProviderKind.OPENAI_COMPATIBLE -> requestBuilder.addHeader("Authorization", "Bearer $key")
                    else -> {}
                }
            }
            applyExtraHeaders(requestBuilder, provider.extraHeadersJson)

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Erro HTTP ${response.code} ao consultar modelos em $url."))
            }

            val jsonElement = json.parseToJsonElement(body)
            val jsonObj = runCatching { jsonElement.jsonObject }.getOrNull()
            val jsonArray = jsonObj?.get("data")?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: jsonObj?.get("models")?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: (jsonElement as? JsonArray)

            val models = mutableListOf<DiscoveredModel>()
            if (jsonArray != null) {
                for (elem in jsonArray) {
                    val obj = runCatching { elem.jsonObject }.getOrNull() ?: continue
                    var id = obj["id"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: continue
                    if (id.startsWith("models/")) id = id.removePrefix("models/")
                    val owner = obj["owned_by"]?.jsonPrimitive?.contentOrNull
                        ?: obj["provider"]?.jsonPrimitive?.contentOrNull
                        ?: obj["vendor"]?.jsonPrimitive?.contentOrNull
                        ?: provider.name
                    val isFree = id.contains("free", ignoreCase = true)
                        || obj["is_free"]?.jsonPrimitive?.booleanOrNull == true
                        || obj["pricing"]?.jsonObject?.get("prompt")?.jsonPrimitive?.contentOrNull == "0"
                        || obj["free"]?.jsonPrimitive?.booleanOrNull == true

                    models.add(DiscoveredModel(id = id, name = id, providerName = owner, isFree = isFree))
                }
            }
            Result.success(models)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class Usage(val prompt: Int, val completion: Int)

    private class RetryableHttpException(message: String) : IOException(message)

    companion object {
        const val MAX_RETRIES = 3
        const val BACKOFF_BASE_MS = 400L
        private val RETRYABLE_HTTP_CODES = listOf(408, 429, 500, 502, 503, 504, 529)
    }
}

/** Modelo descoberto a partir do endpoint /models de qualquer API. */
data class DiscoveredModel(
    val id: String,
    val name: String = id,
    val providerName: String = "",
    val isFree: Boolean = id.contains("free", ignoreCase = true)
)

