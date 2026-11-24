package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ferramenta de ENGENHARIA REVERSA WEB / análise de sites — uso LEGÍTIMO:
 * seu próprio site, sites que você tem autorização para auditar, aprendizado e depuração.
 *
 * NÃO realiza ataques: sem XSS/injeção em sites alheios, sem phishing, sem coleta de
 * credenciais. `web_console_eval` só avalia JS em PÁGINAS LOCAIS geradas pelo próprio app.
 *
 * Ações: web_fetch, web_analyze, web_js_beautify, web_generate, web_console_eval.
 */
class WebTool(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true).build()

    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' é obrigatório."
        return try {
            when (action) {
                "web_fetch" -> fetch(args)
                "web_analyze" -> analyze(args)
                "web_js_beautify" -> beautify(args)
                "web_generate" -> generate(args)
                "web_console_eval" -> consoleEval(args)
                "web_inject" -> inject(args)
                else -> "Ação desconhecida em web_tool: $action"
            }
        } catch (e: Exception) {
            "Erro em web_tool ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT

    private fun resolve(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    private suspend fun fetch(args: JsonObject): String {
        val url = resolve(args["url"]?.jsonPrimitive?.contentOrNull)
            ?: return "Erro: 'url' obrigatório."
        val save = args["save"]?.jsonPrimitive?.contentOrNull != "false"
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) CodexAgent-RE/1.0").build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val headers = resp.headers.toString()
            var savedInfo = ""
            if (save) {
                val name = url.substringAfter("://").substringBefore("/").replace(Regex("[^a-zA-Z0-9.]"), "_")
                val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/web_${name}_${System.currentTimeMillis()}.html")
                out.parentFile?.mkdirs()
                out.writeText(body)
                savedInfo = "\nSalvo em: ${out.absolutePath}"
            }
            return "=== HTTP ${resp.code} $url ===\n$headers\n\n=== BODY (${body.length} chars) ===\n${body.take(12_000)}$savedInfo"
        }
    }

    private suspend fun analyze(args: JsonObject): String {
        val html = args["html"]?.jsonPrimitive?.contentOrNull
            ?: args["url"]?.jsonPrimitive?.contentOrNull?.let { u ->
                val url = resolve(u) ?: return "Erro: URL inválida."
                http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
            }
            ?: return "Erro: informe 'html' (conteúdo) ou 'url'."
        val scripts = Regex("<script[^>]*src=[\"']([^\"']+)", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] }.toList()
        val links = Regex("<a[^>]*href=[\"']([^\"']+)", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] }.toList()
        val forms = Regex("<form[^>]*action=[\"']([^\"']*)", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] }.toList()
        val frames = Regex("<iframe[^>]*src=[\"']([^\"']+)", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] }.toList()
        val techs = mutableListOf<String>()
        mapOf(
            "React" to "react", "Vue" to "vue", "Angular" to "angular", "jQuery" to "jquery",
            "Next.js" to "__next", "WordPress" to "wp-content", "Google Analytics" to "gtag/ga.js",
            "Bootstrap" to "bootstrap", "Tailwind" to "tailwind", "Webpack" to "webpack"
        ).forEach { (name, token) -> if (html.contains(token, ignoreCase = true)) techs.add(name) }
        val comments = mutableListOf<String>()
        Regex("<!--(.*?)-->", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
            val c = m.groupValues[1].trim()
            if (c.isNotBlank() && comments.size < 20) comments.add(c)
        }
        return buildString {
            appendLine("=== Análise de página (${html.length} chars) ===")
            appendLine("Tecnologias detectadas: ${if (techs.isEmpty()) "nenhuma óbvia" else techs.joinToString(", ")}")
            appendLine("Scripts externos (${scripts.size}): ${scripts.take(30).joinToString(", ")}")
            appendLine("Links (${links.size}): ${links.take(30).joinToString(", ")}")
            appendLine("Formulários (${forms.size}): ${forms.take(20).joinToString(", ")}")
            appendLine("iframes (${frames.size}): ${frames.take(20).joinToString(", ")}")
            appendLine("Comentários HTML (${comments.size}):")
            comments.forEach { appendLine("  • ${it.take(160)}") }
        }
    }

    private suspend fun beautify(args: JsonObject): String {
        val code = args["code"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'code' obrigatório (JS a formatar)."
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/js_beautify_${System.currentTimeMillis()}.js")
        out.parentFile?.mkdirs()
        out.writeText(code)
        return if (shellExecutor.execute("command -v prettier >/dev/null 2>&1 && echo yes || echo no", timeoutSec = 10).output.trim() == "yes") {
            val r = shellExecutor.execute("prettier --parser babel \"${out.absolutePath}\" 2>&1 | head -n 400", timeoutSec = 60)
            "=== JS formatado ===\n${r.output.take(20_000)}"
        } else {
            // Fallback: inserção simples de quebras após ; e { (sem dependência)
            val pretty = code.replace(";", ";\n").replace("{", "{\n").replace("}", "\n}")
            "prettier não instalado (use tool_hub). Formatação simples:\n\n${pretty.take(12_000)}"
        }
    }

    private suspend fun generate(args: JsonObject): String {
        val kind = args["kind"]?.jsonPrimitive?.contentOrNull ?: "html"
        val rawTitle = args["title"]?.jsonPrimitive?.contentOrNull ?: "Página Codex"
        // Escapa para HTML e para string JS (evita quebra de sintaxe/injeção no código gerado)
        val title = rawTitle.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val jsTitle = rawTitle.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        val content = when (kind.lowercase()) {
            "html" -> """
                <!DOCTYPE html>
                <html lang="pt-BR">
                <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>$title</title>
                <style>body{font-family:system-ui;max-width:800px;margin:40px auto;padding:0 16px;background:#0f1115;color:#e6e6e6}h1{color:#7aa2f7}pre{background:#1a1d24;padding:12px;border-radius:8px;overflow:auto}</style>
                </head>
                <body><h1>$title</h1><p>Página gerada pelo Codex Agent.</p><pre>// seu código aqui</pre></body>
                </html>
            """.trimIndent()
            "js" -> "// $title\nfunction main() {\n  console.log('$jsTitle');\n}\nmain();\n"
            "css" -> "/* $title */\nbody {\n  margin: 0;\n  font-family: system-ui, sans-serif;\n}\n"
            else -> return "Kind desconhecido: $kind (use html, js, css)."
        }
        val ext = when (kind.lowercase()) { "js" -> "js"; "css" -> "css"; else -> "html" }
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/generated_${System.currentTimeMillis()}.$ext")
        out.parentFile?.mkdirs()
        out.writeText(content)
        return "Arquivo gerado: ${out.absolutePath}\n\n${content.take(3000)}"
    }

    /**
     * Injeção de JS/HTML em PÁGINA LOCAL DO USUÁRIO (seu próprio arquivo HTML no workspace).
     * Insere um <script> antes de </body> e salva como novo arquivo — nunca toca sites remotos.
     */
    private suspend fun inject(args: JsonObject): String {
        val file = args["file"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'file' obrigatório (seu arquivo HTML local no workspace)."
        val code = args["code"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'code' obrigatório (JS a injetar)."
        val f = File(file)
        val target = if (f.isAbsolute) f else File(workRoot(), file)
        // Path traversal guard: canonical path deve ficar dentro do workspace
        val root = File(workRoot()).canonicalFile
        val canonical = runCatching { target.canonicalFile }.getOrDefault(target)
        if (canonical.path != root.path && !canonical.path.startsWith(root.path + File.separator)) {
            return "Erro: caminho fora do workspace: ${target.absolutePath}"
        }
        if (!target.exists()) return "Erro: arquivo não encontrado: ${target.absolutePath}"
        val html = target.readText()
        val injected = if (html.contains("</body>", ignoreCase = true)) {
            html.replace(Regex("</body>", RegexOption.IGNORE_CASE), "<script>\n$code\n</script>\n</body>")
        } else {
            "$html\n<script>\n$code\n</script>"
        }
        val out = File(target.parentFile, "${target.nameWithoutExtension}_injected_${System.currentTimeMillis()}.${target.extension}")
        out.writeText(injected)
        return "JS injetado na sua página local.\nArquivo salvo: ${out.absolutePath}\n\nTrecho injetado:\n<script>\n${code.take(2000)}\n</script>"
    }

    /** Avalia JS apenas em HTML LOCAL gerado pelo app (sandbox) — nunca em site alheio. */
    private suspend fun consoleEval(args: JsonObject): String {
        val code = args["code"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'code' obrigatório."
        // Usa node/qjs se disponível, em diretórioisolado do workspace
        val runtime = when {
            shellExecutor.execute("command -v node >/dev/null 2>&1 && echo yes || echo no", timeoutSec = 10).output.trim() == "yes" -> "node"
            shellExecutor.execute("command -v qjs >/dev/null 2>&1 && echo yes || echo no", timeoutSec = 10).output.trim() == "yes" -> "qjs"
            else -> return "Nenhum runtime JS local (node/qjs). Instale: pkg install nodejs-lts"
        }
        val tmp = File(workRoot(), ".codex/web_sandbox_eval.js").apply { parentFile?.mkdirs(); writeText(code) }
        val r = shellExecutor.execute("cd \"${workRoot()}\" && timeout 20 $runtime \"${tmp.absolutePath}\" 2>&1 | head -n 100", timeoutSec = 30)
        return "=== console (JS local) ===\n${r.output.take(10_000)}"
    }
}
