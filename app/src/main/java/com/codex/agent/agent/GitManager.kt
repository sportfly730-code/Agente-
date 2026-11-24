package com.codex.agent.agent

import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import com.codex.agent.security.SecretsStore
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
 * Git real no workspace: branches, diff, stash, merge, log, push para GitHub e abertura de PR
 * via GitHub API. Opera sobre o git dir separado `.codex_git` (usado pelos checkpoints)
 * ou sobre um repositório `.git` padrão se existir.
 *
 * O token do GitHub é lido do SecretsStore (nunca em texto puro).
 */
class GitManager(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository,
    private val secretsStore: SecretsStore
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        const val SECRET_GITHUB_TOKEN = "github_token"
    }

    private fun gitDirFor(root: File): File {
        val standard = File(root, ".git")
        return if (standard.isDirectory) standard else File(root, ".codex_git")
    }

    private fun prefix(root: File): String {
        val gitDir = gitDirFor(root)
        return "git --git-dir=\"${gitDir.absolutePath}\" --work-tree=\"${root.absolutePath}\""
    }

    private fun sq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' é obrigatório."
        val workspace = workspaceRepository.active()
            ?: return "Erro: nenhum workspace ativo."
        val root = File(workspace.rootPath).apply { mkdirs() }
        val p = prefix(root)

        return when (action) {
            "status" -> run(p + " status --short --branch", root)
            "log" -> {
                val n = (args["limit"]?.jsonPrimitive?.contentOrNull ?: "15").toIntOrNull()
                    ?: return "Erro: 'limit' deve ser numérico."
                run("$p log --oneline --decorate -n $n", root)
            }
            "branches" -> run("$p branch -a", root)
            "create_branch" -> {
                val name = args["branch"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'branch' é obrigatório."
                if (!name.matches(Regex("^[A-Za-z0-9._/-]{1,120}$"))) return "Erro: nome de branch inválido."
                run("$p checkout -b ${sq(name)}", root)
            }
            "checkout" -> {
                val name = args["branch"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'branch' é obrigatório."
                if (!name.matches(Regex("^[A-Za-z0-9._/-]{1,120}$"))) return "Erro: nome de branch inválido."
                run("$p checkout ${sq(name)}", root)
            }
            "diff" -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                run("$p diff -- ${sq(path)}", root)
            }
            "add_commit" -> {
                val message = args["message"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'message' é obrigatório."
                run("$p add -A && $p commit -m ${sq(message.take(200))}", root)
            }
            "stash" -> run("$p stash push -m \"stash do agente\"", root)
            "stash_pop" -> run("$p stash pop", root)
            "merge" -> {
                val branch = args["branch"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'branch' é obrigatório."
                run("$p merge \"$branch\"", root)
            }
            "set_remote" -> {
                val url = args["remote_url"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'remote_url' é obrigatório."
                run("$p remote remove origin 2>/dev/null; $p remote add origin \"$url\" && $p remote -v", root)
            }
            "push" -> {
                val branch = args["branch"]?.jsonPrimitive?.contentOrNull ?: "main"
                val setUpstream = args["set_upstream"]?.jsonPrimitive?.contentOrNull != "false"
                val suffix = if (setUpstream) " -u origin \"$branch\"" else " origin \"$branch\""
                run("$p push$suffix", root)
            }
            "pull" -> {
                val branch = args["branch"]?.jsonPrimitive?.contentOrNull ?: "main"
                run("$p pull origin \"$branch\"", root)
            }
            "create_pr" -> createPullRequest(args, root, p)
            "set_token" -> {
                val token = args["token"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'token' é obrigatório."
                secretsStore.putSecret(SECRET_GITHUB_TOKEN, token)
                "Token do GitHub salvo com segurança no Android Keystore."
            }
            "has_token" -> {
                val t = secretsStore.getSecret(SECRET_GITHUB_TOKEN).orEmpty()
                if (t.isBlank()) "Nenhum token do GitHub configurado. Use action='set_token'." else "Token do GitHub configurado (***${t.takeLast(4)})."
            }
            else -> "Ação desconhecida em git_manager: $action"
        }
    }

    private suspend fun run(command: String, root: File): String {
        val result = shellExecutor.execute(command, workdir = root.absolutePath, timeoutSec = 120)
        val out = result.output.trim()
        return if (result.exitCode == 0) {
            if (out.isBlank()) "Comando executado com sucesso (sem saída)." else out
        } else "git falhou (exit ${result.exitCode}):\n$out"
    }

    /**
     * Cria PR via GitHub REST API: POST /repos/{owner}/{repo}/pulls
     * Requer token em SecretsStore e remote_url no formato https://github.com/owner/repo(.git).
     */
    private suspend fun createPullRequest(args: JsonObject, root: File, p: String): String {
        val token = secretsStore.getSecret(SECRET_GITHUB_TOKEN).orEmpty()
        if (token.isBlank()) return "Erro: token do GitHub não configurado. Use git_manager(action='set_token', token=...)."

        val repo = args["repo"]?.jsonPrimitive?.contentOrNull
            ?: run {
                val remoteOut = shellExecutor.execute("$p remote get-url origin", root.absolutePath, 30).output.trim()
                parseRepoFromRemote(remoteOut)
            }
            ?: return "Erro: informe 'repo' (owner/repo) ou configure o remote origin."
        // CORREÇÃO A3: aceita 'title' (schema) e 'message' (legado) para o título do PR
        val title = args["title"]?.jsonPrimitive?.contentOrNull
            ?: args["message"]?.jsonPrimitive?.contentOrNull
            ?: "PR automático do Codex Agent"
        // 'head' é o branch de origem; 'base' é o destino. Se só 'head' vier sem 'base',
        // usa 'main' como destino; se 'base' vier mas não 'head', origem = base.
        val head = args["head"]?.jsonPrimitive?.contentOrNull ?: "main"
        val base = args["base"]?.jsonPrimitive?.contentOrNull ?: "main"
        val body = args["body"]?.jsonPrimitive?.contentOrNull ?: "Pull request criado automaticamente pelo Codex Agent."

        val payload = buildJsonObject {
            put("title", title)
            put("head", head)
            put("base", base)
            put("body", body)
        }
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/pulls")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "CodexAgent")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val obj = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull()
                    val htmlUrl = obj?.get("html_url")?.jsonPrimitive?.contentOrNull ?: "(sem URL)"
                    "✅ Pull request criado: $htmlUrl"
                } else {
                    "Erro ao criar PR (HTTP ${response.code}): ${responseBody.take(600)}"
                }
            }
        } catch (e: Exception) {
            "Erro ao criar PR: ${e.message}"
        }
    }

    private fun parseRepoFromRemote(url: String): String? {
        val cleaned = url.trim()
            .removePrefix("git@github.com:")
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix(".git")
            .trimEnd('/')
        val parts = cleaned.split("/")
        return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else null
    }
}
