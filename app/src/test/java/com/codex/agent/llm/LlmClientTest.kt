package com.codex.agent.llm

import com.codex.agent.data.entity.ProviderConfigEntity
import com.codex.agent.data.model.ProviderKind
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de integração do LlmClient com MockWebServer:
 * - normalização de URL (/v1 adicionado quando ausente)
 * - parsing de resposta chat/completions
 * - descoberta de modelos (/models)
 * - tratamento de erro HTTP
 */
class LlmClientTest {

    private fun provider(url: String) = ProviderConfigEntity(
        name = "Test Provider",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = url,
        apiKey = "test-key",
        mainModel = "test-model",
        maxOutputTokens = 1024
    )

    @Test(timeout = 20000)
    fun addsV1ToBaseUrlAndParsesResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"choices":[{"message":{"role":"assistant","content":"Olá do teste"}}],
                     "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                    """.trimIndent()
                )
        )
        server.start()
        try {
            val url = server.url("/").toString().trimEnd('/')
            val result = LlmClient().chatCompletion(provider(url), listOf(LlmMessage("user", "oi")))
            assertTrue("esperado sucesso, obtido ${result.exceptionOrNull()?.message}", result.isSuccess)
            assertEquals("Olá do teste", result.getOrNull()?.content)
            assertEquals(15, result.getOrNull()?.totalTokens)

            val request = server.takeRequest()
            // Base URL sem /v1 → cliente deve adicionar /v1/chat/completions
            assertTrue("URL deveria conter /v1/chat/completions, foi ${request.path}",
                request.path?.contains("/v1/chat/completions") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 20000)
    fun fetchesAvailableModels() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"model-a","owned_by":"test"},{"id":"model-free","owned_by":"test"}]}""")
        )
        server.start()
        try {
            val url = server.url("/").toString().trimEnd('/') + "/v1"
            val result = LlmClient().fetchAvailableModels(provider(url))
            assertTrue(result.isSuccess)
            val models = result.getOrNull()!!
            assertEquals(2, models.size)
            assertEquals("model-a", models[0].id)
            assertTrue("model-free deve ser marcado como free", models[1].isFree)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 20000)
    fun reportsHttpError() = runBlocking {
        val server = MockWebServer()
        // O cliente faz retry (MAX_RETRIES=3) em erros — enfileirar 3 respostas 401
        // evita que o MockWebServer bloqueie esperando respostas adicionais.
        repeat(3) { server.enqueue(MockResponse().setResponseCode(401).setBody("Invalid API key")) }
        server.start()
        try {
            val url = server.url("/v1").toString()
            val result = LlmClient().chatCompletion(provider(url), listOf(LlmMessage("user", "oi")))
            assertTrue("esperado falha em 401", result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("401") == true)
        } finally {
            server.shutdown()
        }
    }
}
