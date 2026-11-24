package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.WorkspaceRepository
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
 * Teste de segurança AUTORIZADO de sites/sistemas do próprio usuário.
 * Language-agnostic: trabalha via HTTP (caixa-preta) + análise de código (caixa-branca).
 *
 * NÃO faz DoS, phishing, fraude real ou ataque a terceiros — apenas detecção de
 * vulnerabilidades e testes controlados em ambiente de teste/sandbox do usuário.
 */
class WebSecurityTool(
    private val workspaceRepository: WorkspaceRepository
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' obrigatório."
        return try {
            when (action) {
                "detect_stack" -> detectStack(args)
                "audit" -> audit(args)
                "sast" -> sast(args)
                "deps" -> deps(args)
                "test_auth" -> testAuth(args)
                "test_payment_sandbox" -> testPaymentSandbox(args)
                else -> "Ação desconhecida em web_security: $action"
            }
        } catch (e: Exception) {
            "Erro em web_security ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    /** Detecta linguagem/framework pelos headers e HTML (funciona em qualquer stack). */
    private suspend fun detectStack(args: JsonObject): String {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'url' obrigatório."
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val h = resp.headers
            val body = resp.body?.string().orEmpty()
            val server = h["server"] ?: "?"
            val powered = h["x-powered-by"] ?: "?"
            val framework = when {
                h["set-cookie"]?.contains("PHPSESSID", true) == true || server.contains("apache", true) -> "PHP (provável)"
                powered.contains("Express", true) || powered.contains("Node", true) -> "Node.js/Express"
                powered.contains("ASP.NET", true) || h["x-aspnet-version"] != null -> "ASP.NET (C#)"
                body.contains("__next", true) -> "Next.js (React)"
                body.contains("wp-content", true) -> "WordPress (PHP)"
                body.contains("django", true) || h["set-cookie"]?.contains("csrftoken", true) == true -> "Django (Python)"
                body.contains("laravel", true) -> "Laravel (PHP)"
                body.contains("__cf_bm", true) -> "Cloudflare (proxy)"
                else -> "Não identificado (teste via HTTP funciona de todo modo)"
            }
            return "=== Stack detectado: $url ===\n" +
                "Status: ${resp.code}\nServidor: $server\nX-Powered-By: $powered\n" +
                "Framework: $framework\nContent-Type: ${h["content-type"] ?: "?"}\n" +
                "Cookies: ${h["set-cookie"]?.take(120) ?: "nenhum"}\n\n" +
                "O audit via HTTP funciona em QUALQUER linguagem — a linguagem só muda a forma de corrigir."
        }
    }

    /** Auditoria passiva via HTTP — detecta problemas sem explorar. */
    private suspend fun audit(args: JsonObject): String {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'url' obrigatório."
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val h = resp.headers
            val body = resp.body?.string().orEmpty()
            val findings = mutableListOf<String>()
            if (h["strict-transport-security"] == null) findings.add("[MÉDIO] Falta HSTS (Strict-Transport-Security)")
            if (h["x-content-type-options"] == null) findings.add("[BAIXO] Falta X-Content-Type-Options: nosniff")
            if (h["x-frame-options"] == null && h["content-security-policy"]?.contains("frame-ancestors") != true)
                findings.add("[MÉDIO] Falta X-Frame-Options / frame-ancestors (clickjacking)")
            if (h["content-security-policy"] == null) findings.add("[MÉDIO] Falta Content-Security-Policy")
            if (h["set-cookie"]?.contains("Secure", true) != true) findings.add("[ALTO] Cookie sem flag Secure")
            if (h["set-cookie"]?.contains("HttpOnly", true) != true) findings.add("[ALTO] Cookie sem flag HttpOnly")
            val forms = Regex("<form[^>]*>", RegexOption.IGNORE_CASE).findAll(body).count()
            val inputsSemType = Regex("<input(?![^>]*type=)[^>]*>", RegexOption.IGNORE_CASE).findAll(body).count()
            if (inputsSemType > 0) findings.add("[BAIXO] $inputsSemType input(s) sem atributo type")
            // Indícios de XSS refletido: parâmetro da URL aparece no HTML sem escape
            val query = url.substringAfter("?", "")
            if (query.isNotBlank()) {
                val param = query.split("&").firstOrNull()?.substringAfter("=").orEmpty()
                if (param.length > 2 && body.contains(param) && !body.contains("&lt;")) {
                    findings.add("[ALTO] Parâmetro '$param' refletido no HTML sem codificação — possível XSS")
                }
            }
            if (body.contains("mysql_", true) || body.contains("Warning: mysql", true))
                findings.add("[CRÍTICO] Erro MySQL exposto no HTML")
            return "=== Auditoria de $url ===\nStatus: ${resp.code}\n" +
                (if (findings.isEmpty()) "✅ Nenhum problema óbvio detectado." else findings.joinToString("\n") { "• $it" }) +
                "\n\nFormulários encontrados: $forms\n" +
                "Nota: auditoria passiva. Use test_auth/test_payment_sandbox para testes controlados."
        }
    }

    /** Análise estática multi-linguagem: padrões de vulnerabilidade no código-fonte. */
    private suspend fun sast(args: JsonObject): String {
        val dirRaw = args["dir"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'dir' obrigatório."
        val root = File(dirRaw)
        if (!root.isDirectory) return "Erro: diretório não encontrado."
        // Padrões por linguagem (genéricos o suficiente para funcionar em qualquer stack)
        val rules = listOf(
            "XSS: innerHTML/dangerouslySetInnerHTML" to Regex("innerHTML\\s*=|dangerouslySetInnerHTML", RegexOption.IGNORE_CASE),
            "SQLi: concatenação em query" to Regex("(SELECT|INSERT|UPDATE|DELETE)[^;]{0,80}(\\+|\\$\\{|%s|f\")", RegexOption.IGNORE_CASE),
            "Command injection" to Regex("(exec|system|shell_exec|subprocess|Runtime\\.getRuntime)[^;]{0,60}(\\+|\\$|%)", RegexOption.IGNORE_CASE),
            "Senha/secret hardcoded" to Regex("(password|passwd|secret|api[_-]?key)\\s*[:=]\\s*[\"'][^\"']{6,}[\"']", RegexOption.IGNORE_CASE),
            "eval/unsafe deserialize" to Regex("\\beval\\s*\\(|pickle\\.loads|unserialize\\s*\\(", RegexOption.IGNORE_CASE),
            "CORS irrestrito" to Regex("Access-Control-Allow-Origin[^\"']{0,20}\\*", RegexOption.IGNORE_CASE),
            "Desvio de auth" to Regex("if\\s*\\([^)]*(isAdmin|isAuthenticated)[^)]*==?\\s*false", RegexOption.IGNORE_CASE)
        )
        val extLang = mapOf(
            "php" to "PHP", "js" to "JS/Node", "ts" to "TS", "py" to "Python", "java" to "Java",
            "kt" to "Kotlin", "go" to "Go", "rb" to "Ruby", "cs" to "C#", "jsx" to "React", "tsx" to "React"
        )
        val results = mutableListOf<String>()
        val langCount = mutableMapOf<String, Int>()
        root.walkTopDown().filter { it.isFile && it.extension.lowercase() in extLang }.take(3000).forEach { f ->
            val lang = extLang[f.extension.lowercase()] ?: "?"
            langCount[lang] = (langCount[lang] ?: 0) + 1
            val text = runCatching { f.readText() }.getOrDefault("")
            rules.forEach { (name, regex) ->
                val n = regex.findAll(text).count()
                if (n > 0) results.add("[${f.relativeTo(root)}:$lang] $name ($n)")
            }
        }
        return "=== SAST multi-linguagem ===\n" +
            "Linguagens: ${langCount.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" }}\n" +
            "Arquivos analisados: ${langCount.values.sum()}\n\n" +
            (if (results.isEmpty()) "✅ Nenhum padrão de risco detectado." else results.take(100).joinToString("\n") { "• $it" })
    }

    /** Checa CVEs de dependências via API OSV (pública, gratuita). */
    private suspend fun deps(args: JsonObject): String {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'package' obrigatório."
        val ver = args["version"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'version' obrigatório."
        val payload = buildJsonObject {
            put("package", buildJsonObject {
                put("name", pkg)
                put("ecosystem", args["ecosystem"]?.jsonPrimitive?.contentOrNull ?: "npm")
            })
            put("version", ver)
        }
        val req = Request.Builder().url("https://api.osv.dev/v1/query")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return "Erro ao consultar OSV (HTTP ${resp.code}): ${body.take(300)}"
            val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }.getOrNull()
            val vulns = obj?.get("vulns")?.jsonArray
            if (vulns == null || vulns.isEmpty()) return "✅ Nenhuma vulnerabilidade conhecida para $pkg@$ver (OSV)."
            return buildString {
                appendLine("⚠️ ${vulns.size} vulnerabilidade(s) em $pkg@$ver:")
                vulns.take(10).forEach { v ->
                    val o = v.jsonObject
                    appendLine("• ${o["id"]?.jsonPrimitive?.contentOrNull ?: "?"}: " +
                        (o["summary"]?.jsonPrimitive?.contentOrNull ?: "").take(120))
                }
            }
        }
    }

    /** Teste de autenticação com credenciais DE TESTE fornecidas pelo usuário. */
    private suspend fun testAuth(args: JsonObject): String {
        val url = args["login_url"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'login_url' obrigatório."
        val user = args["test_user"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'test_user' obrigatório."
        val pass = args["test_pass"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'test_pass' obrigatório."
        val payload = "username=$user&password=$pass&email=$user"
        val req = Request.Builder().url(url)
            .post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val cookie = resp.headers["set-cookie"].orEmpty()
            val sb = StringBuilder("=== Teste de Auth (credenciais de teste) ===\n")
            sb.appendLine("Status: ${resp.code}")
            sb.appendLine("Sessão criada: ${if (cookie.contains("session", true) || cookie.contains("token", true)) "sim" else "não detectada"}")
            sb.appendLine("Cookie seguro: ${if (cookie.contains("HttpOnly", true) && cookie.contains("Secure", true)) "sim" else "NÃO — corrigir"}")
            sb.appendLine("Mensagem de erro genérica: ${if (!body.contains("usuário inexistente", true) && !body.contains("senha incorreta", true)) "sim (bom)" else "NÃO — vaza quali usuário existe"}")
            sb.appendLine("Redirecionou após login: ${if (resp.code in 300..399) "sim" else "verificar"}")
            return sb.toString()
        }
    }

    /** Teste de fluxo de pagamento em SANDBOX (nunca produção). */
    private suspend fun testPaymentSandbox(args: JsonObject): String {
        val url = args["sandbox_url"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'sandbox_url' obrigatório (use o gateway em modo SANDBOX/TESTE)."
        if (!url.contains("sandbox", true) && !url.contains("test", true) && !url.contains("sandbox", true)) {
            return "⚠️ A URL não parece ser sandbox. Por segurança, só testo gateways em modo SANDBOX/TESTE."
        }
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val sb = StringBuilder("=== Teste de Gateway Sandbox ===\n")
            sb.appendLine("Status: ${resp.code}")
            sb.appendLine("Ambiente: sandbox/teste ✅")
            sb.appendLine("Formulário de pagamento presente: ${if (body.contains("card", true) || body.contains("payment", true)) "sim" else "verificar"}")
            sb.appendLine("Validação de valor negativo: teste manual com valor -1 no seu formulário.")
            sb.appendLine("Validação de valor zero: teste manual com valor 0.")
            sb.appendLine("Idempotência: verifique se chave única evita dupla cobrança.")
            return sb.toString()
        }
    }
}
