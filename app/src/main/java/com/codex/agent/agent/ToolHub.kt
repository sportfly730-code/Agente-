package com.codex.agent.agent

import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.EnvironmentSetup
import com.codex.agent.shell.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Receita de instalação de uma ferramenta do catálogo. */
data class ToolRecipe(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val kind: String,                       // GITHUB_JAR | GITHUB_ARCHIVE | GITHUB_BINARY | PKG | PIP | NPM | GIT_CLONE
    val repo: String? = null,               // owner/repo (GitHub)
    val assetPattern: String? = null,       // regex para escolher o asset do release
    val pkgName: String? = null,            // pacote (pkg/pip/npm)
    val url: String? = null,                // URL direta / repo git
    val wrappers: Map<String, String> = emptyMap(), // binName → comando ({TOOL_DIR}, {JAR}, {FILE})
    val postCommand: String? = null,        // comando pós-download/extração (ex.: unxz, normalizar pasta)
    val verifyCommand: String? = null,      // comando de VERIFICAÇÃO REAL pós-instalação ({WRAPPER},{JAR},{FILE},{TOOL_DIR})
    val heavy: Boolean = false,
    val requiresRoot: Boolean = false
)

@Serializable
data class InstalledToolRecord(
    val id: String,
    val name: String,
    val version: String,
    val dir: String,
    val wrappers: Map<String, String> = emptyMap(),
    val installedAt: Long
)

/**
 * ToolHub — instalador/executador universal de ferramentas de engenharia reversa e dev
 * a partir do GitHub (releases via API), Termux (pkg), pip, npm e URLs arbitrárias.
 *
 * - Catálogo curado com receitas testadas para aarch64/Termux.
 * - Wrappers executáveis criados em $HOME/.local/bin (já no PATH do ambiente).
 * - Binários instalados em $HOME/.local/share/codex_tools (armazenamento executável —
 *   nunca /sdcard, que não permite bit de execução).
 * - Estado persistido em installed.json + registro em .codex/dependencies.md.
 * - Descoberta dinâmica: search_github e list_release_assets para QUALQUER ferramenta,
 *   instalável via install_custom (URL + pós-comando + wrapper).
 */
class ToolHub(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository,
    private val environmentSetup: EnvironmentSetup
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------
    // Catálogo curado
    // ------------------------------------------------------------------

    val registry: List<ToolRecipe> = listOf(
        ToolRecipe(
            id = "apkeditor", name = "APKEditor (REAndroid)", category = "apk-re",
            description = "Editor moderno de APKs: mescla splits (xapk/apks/apkm), decodifica/encoda " +
                "recursos sem apktool, edita manifest/assets. Uso: apkeditor m -i entrada.xapk -o saida.apk",
            kind = KIND_GITHUB_JAR, repo = "REAndroid/APKEditor", assetPattern = "APKEditor.*\\.jar",
            wrappers = mapOf("apkeditor" to "exec java -Xmx1536m -jar \"{JAR}\" \"${'$'}@\""),
            verifyCommand = "{WRAPPER} 2>&1 | head -n 5"
        ),
        ToolRecipe(
            id = "apktool", name = "Apktool", category = "apk-re",
            description = "Decode/encode de recursos e smali (iBotPeaches). Padrão da indústria para rebuild de APKs.",
            kind = KIND_GITHUB_JAR, repo = "iBotPeaches/Apktool", assetPattern = "apktool_.*\\.jar",
            wrappers = mapOf("apktool" to "exec java -Xmx1536m -jar \"{JAR}\" \"${'$'}@\""),
            verifyCommand = "{WRAPPER} --version 2>&1 | head -n 2"
        ),
        ToolRecipe(
            id = "jadx", name = "jadx", category = "apk-re",
            description = "Decompilador dex → Java (skylot). O zip extrai em jadx-<ver>/ — normalizado para {TOOL_DIR}/jadx.",
            kind = KIND_GITHUB_ARCHIVE, repo = "skylot/jadx", assetPattern = "jadx-.*\\.zip",
            postCommand = "p=${'$'}(find \"{TOOL_DIR}\" -maxdepth 1 -type d -name \"jadx-*\" | head -n 1); " +
                "[ -n \"${'$'}p\" ] && mv \"${'$'}p\" \"{TOOL_DIR}/jadx\"",
            wrappers = mapOf(
                "jadx" to "exec \"{TOOL_DIR}/jadx/bin/jadx\" \"${'$'}@\"",
                "jadx-gui" to "exec \"{TOOL_DIR}/jadx/bin/jadx-gui\" \"${'$'}@\""
            ),
            verifyCommand = "{WRAPPER} --version 2>&1 | head -n 2"
        ),
        ToolRecipe(
            id = "smali", name = "smali", category = "apk-re",
            description = "Assembler smali → dex (google/smali). O maior asset correspondente é escolhido (jar runnable/fat).",
            kind = KIND_GITHUB_JAR, repo = "google/smali", assetPattern = "smali-\\d.*\\.jar",
            wrappers = mapOf("smali" to "exec java -Xmx1536m -jar \"{JAR}\" \"${'$'}@\""),
            verifyCommand = "{WRAPPER} 2>&1 | head -n 4"
        ),
        ToolRecipe(
            id = "baksmali", name = "baksmali", category = "apk-re",
            description = "Disassembler dex → smali (google/smali).",
            kind = KIND_GITHUB_JAR, repo = "google/smali", assetPattern = "baksmali-\\d.*\\.jar",
            wrappers = mapOf("baksmali" to "exec java -Xmx1536m -jar \"{JAR}\" \"${'$'}@\""),
            verifyCommand = "{WRAPPER} 2>&1 | head -n 4"
        ),
        ToolRecipe(
            id = "dex2jar", name = "dex2jar", category = "apk-re",
            description = "Converte dex ↔ jar (d2j-dex2jar/d2j-jar2dex) para análise com ferramentas Java.",
            kind = KIND_GITHUB_ARCHIVE, repo = "pxb1988/dex2jar", assetPattern = "dex-tools.*\\.zip",
            wrappers = mapOf(
                "d2j-dex2jar" to "exec \"{TOOL_DIR}/d2j-dex2jar.sh\" \"${'$'}@\"",
                "d2j-jar2dex" to "exec \"{TOOL_DIR}/d2j-jar2dex.sh\" \"${'$'}@\""
            ),
            verifyCommand = "{WRAPPER} 2>&1 | head -n 4"
        ),
        ToolRecipe(
            id = "uber-apk-signer", name = "uber-apk-signer", category = "build",
            description = "Assina (v1+v2+v3), alinha e verifica APKs; debug keystore automática. Uso: uber-apk-signer -a app.apk",
            kind = KIND_GITHUB_JAR, repo = "patrickfav/uber-apk-signer", assetPattern = "uber-apk-signer-.*\\.jar",
            wrappers = mapOf("uber-apk-signer" to "exec java -Xmx1536m -jar \"{JAR}\" \"${'$'}@\""),
            verifyCommand = "{WRAPPER} --help 2>&1 | head -n 4"
        ),
        ToolRecipe(
            id = "bundletool", name = "bundletool", category = "build",
            description = "Compila .aab → .apks, instala/extraia split APKs, gera APK universal (google/bundletool).",
            kind = KIND_GITHUB_JAR, repo = "google/bundletool", assetPattern = "bundletool.*\\.jar",
            wrappers = mapOf("bundletool" to "exec java -Xmx1536m -jar \"{JAR}\" \"${'$'}@\""),
            verifyCommand = "{WRAPPER} 2>&1 | head -n 4"
        ),
        ToolRecipe(
            id = "revanced-cli", name = "ReVanced CLI", category = "patching",
            description = "Aplica patches em APKs (ReVanced). Use com revanced-patches: " +
                "revanced-cli patch -p patches.rvp app.apk -o saida.apk",
            kind = KIND_GITHUB_JAR, repo = "ReVanced/revanced-cli", assetPattern = "revanced-cli-.*-all\\.jar",
            wrappers = mapOf("revanced-cli" to "exec java -Xmx1536m -jar \"{JAR}\" \"${'$'}@\""),
            verifyCommand = "{WRAPPER} 2>&1 | head -n 6"
        ),
        ToolRecipe(
            id = "revanced-patches", name = "ReVanced Patches", category = "patching",
            description = "Bundle oficial de patches do ReVanced (asset .rvp usado pelo revanced-cli).",
            kind = KIND_GITHUB_JAR, repo = "ReVanced/revanced-patches",
            assetPattern = "revanced-patches-.*\\.(jar|rvp)",
            wrappers = emptyMap(),
            verifyCommand = "ls -la \"{TOOL_DIR}\" | head -n 6"
        ),
        ToolRecipe(
            id = "apk-mitm", name = "apk-mitm", category = "patching",
            description = "Patcha APK para inspeção de tráfego HTTPS (remove pinning, debugável). Requer node; " +
                "usa apktool/uber-apk-signer internamente.",
            kind = KIND_NPM, pkgName = "apk-mitm",
            wrappers = emptyMap(),
            verifyCommand = "apk-mitm 2>&1 | head -n 6"
        ),
        ToolRecipe(
            id = "enjarify", name = "enjarify", category = "apk-re",
            description = "Conversão dex → jar alternativa (Google), mais tolerante com dexs modernos.",
            kind = KIND_GIT_CLONE, url = "https://github.com/Google/enjarify",
            wrappers = mapOf("enjarify" to "exec env PYTHONPATH=\"{TOOL_DIR}\" python3 -m enjarify \"${'$'}@\""),
            verifyCommand = "{WRAPPER} 2>&1 | head -n 4"
        ),
        ToolRecipe(
            id = "androguard", name = "AndroGuard", category = "analysis",
            description = "Análise estática de APKs em Python (manifest, dex, permissões, strings).",
            kind = KIND_PIP, pkgName = "androguard",
            wrappers = emptyMap(),
            verifyCommand = "python3 -c \"import androguard; print('androguard OK')\" 2>&1"
        ),
        ToolRecipe(
            id = "apkleaks", name = "apkleaks", category = "analysis",
            description = "Exposição de segredos/URLs/tokens em APKs (usa jadx internamente).",
            kind = KIND_PIP, pkgName = "apkleaks",
            wrappers = emptyMap(),
            verifyCommand = "python3 -c \"import apkleaks; print('apkleaks OK')\" 2>&1"
        ),
        ToolRecipe(
            id = "frida-tools", name = "Frida Tools", category = "dynamic",
            description = "Instrumentação dinâmica: frida, frida-ps, frida-trace, frida-dump. Requer frida-server rodando.",
            kind = KIND_PIP, pkgName = "frida-tools",
            wrappers = emptyMap(),
            verifyCommand = "python3 -c \"import frida; print('frida', frida.__version__)\" 2>&1"
        ),
        ToolRecipe(
            id = "frida-server", name = "frida-server (arm64)", category = "dynamic",
            description = "Daemon frida para Android. Executar como root: su -c 'frida-server &' " +
                "(ou via Shizuku/root do ambiente).",
            kind = KIND_GITHUB_BINARY, repo = "frida/frida",
            assetPattern = "frida-server-\\d+\\.\\d+\\.\\d+-android-arm64\\.xz",
            postCommand = "pkg install -y xz-utils >/dev/null 2>&1; unxz -f \"{FILE}\"",
            requiresRoot = true,
            wrappers = mapOf("frida-server" to "exec \"{FILE}\" \"${'$'}@\""),
            verifyCommand = "{FILE} --version 2>&1 | head -n 2"
        ),
        ToolRecipe(
            id = "objection", name = "objection", category = "dynamic",
            description = "Toolkit de exploração runtime sobre Frida (bypass SSL pinning, dumps, hooks).",
            kind = KIND_PIP, pkgName = "objection",
            wrappers = emptyMap(),
            verifyCommand = "python3 -c \"import objection; print('objection OK')\" 2>&1"
        ),
        ToolRecipe(
            id = "android-tools", name = "android-tools (adb/fastboot)", category = "build",
            description = "adb/fastboot no Termux: depuração wireless, Shizuku via adb, instalação em outros dispositivos.",
            kind = KIND_PKG, pkgName = "android-tools",
            wrappers = emptyMap(),
            verifyCommand = "adb version 2>&1 | head -n 2"
        ),
        ToolRecipe(
            id = "radare2", name = "radare2", category = "analysis",
            description = "Framework de engenharia reversa binária (r2, rabin2, rahash2) — análise de .so/ELF.",
            kind = KIND_PKG, pkgName = "radare2",
            wrappers = emptyMap(),
            verifyCommand = "r2 -v 2>&1 | head -n 2"
        ),
        ToolRecipe(
            id = "ghidra-headless", name = "Ghidra Headless", category = "analysis",
            description = "Decompilador da NSA em modo headless (analyzeHeadless) para .so/ELF. " +
                "PESADO: ~1GB+ de disco e muita RAM (recomendado apenas com Termux+openjdk-17).",
            kind = KIND_GITHUB_ARCHIVE, repo = "NationalSecurityAgency/ghidra",
            assetPattern = "ghidra_.*_PUBLIC_.*\\.zip", heavy = true,
            postCommand = "p=${'$'}(find \"{TOOL_DIR}\" -maxdepth 1 -type d -name \"ghidra_*_PUBLIC\" | head -n 1); " +
                "[ -n \"${'$'}p\" ] && mv \"${'$'}p\" \"{TOOL_DIR}/ghidra\"",
            wrappers = mapOf("analyzeHeadless" to "exec \"{TOOL_DIR}/ghidra/support/analyzeHeadless\" \"${'$'}@\""),
            verifyCommand = "ls -la \"{TOOL_DIR}/ghidra/support/analyzeHeadless\" 2>&1"
        ),
        ToolRecipe(
            id = "mobsf", name = "MobSF", category = "analysis",
            description = "Mobile Security Framework (análise estática automatizada). EXPERIMENTAL no Termux: " +
                "dependências nativas pesadas; prefira apkleaks/androguard no celular.",
            kind = KIND_GIT_CLONE, url = "https://github.com/MobSF/Mobile-Security-Framework-MobSF",
            postCommand = "pip install --break-system-packages -r \"{TOOL_DIR}/requirements.txt\" || pip install -r \"{TOOL_DIR}/requirements.txt\"",
            heavy = true,
            wrappers = mapOf("mobsf" to "exec env PYTHONPATH=\"{TOOL_DIR}\" python3 \"{TOOL_DIR}/manage.py\" runserver 127.0.0.1:8000"),
            verifyCommand = "ls \"{TOOL_DIR}/manage.py\" 2>&1"
        ),
        ToolRecipe(
            id = "cfr", name = "CFR (Java Decompiler)", category = "apk-re",
            description = "Decompilador Java moderno (lee-b/CFR). Excelente para código ofuscado/lambda.",
            kind = KIND_GITHUB_JAR, repo = "lee-b/CFR", assetPattern = "cfr-.*\\.jar",
            wrappers = mapOf("cfr" to "exec java -Xmx1536m -jar \"{JAR}\" \"${'$'}@\""),
            verifyCommand = "{WRAPPER} 2>&1 | head -n 4"
        ),
        ToolRecipe(
            id = "procyon", name = "Procyon (Java Decompiler)", category = "apk-re",
            description = "Decompilador Java (mstrobel/procyon). Bom para código genérico/anônimo.",
            kind = KIND_GITHUB_JAR, repo = "mstrobel/procyon", assetPattern = "procyon-decompiler-.*\\.jar",
            wrappers = mapOf("procyon" to "exec java -Xmx1536m -cp \"{JAR}\" com.strobel.decompiler.DecompilerDriver \"${'$'}@\""),
            verifyCommand = "{WRAPPER} 2>&1 | head -n 4"
        ),
        ToolRecipe(
            id = "bytecode-viewer", name = "Bytecode Viewer", category = "analysis",
            description = "GUI multi-decompiler (CFR/FernFlower/Procyon) + análise de bytecode (Konloch).",
            kind = KIND_GITHUB_JAR, repo = "Konloch/bytecode-viewer", assetPattern = "Bytecode-Viewer-.*\\.jar",
            wrappers = mapOf("bytecode-viewer" to "exec java -Xmx1536m -jar \"{JAR}\" \"${'$'}@\""),
            verifyCommand = "{WRAPPER} 2>&1 | head -n 4"
        ),
        ToolRecipe(
            id = "recaf", name = "Recaf (Bytecode Editor)", category = "analysis",
            description = "Editor de bytecode Java (Col-E/Recaf) — análise e patch de classes .class/.jar.",
            kind = KIND_GITHUB_JAR, repo = "Col-E/Recaf", assetPattern = "recaf-.*\\.jar",
            wrappers = mapOf("recaf" to "exec java -Xmx1536m -jar \"{JAR}\" \"${'$'}@\""),
            verifyCommand = "{WRAPPER} 2>&1 | head -n 4"
        ),
        ToolRecipe(
            id = "il2cppdumper", name = "Il2CppDumper (Unity)", category = "game-re",
            description = "Extrai metadados de jogos Unity (libil2cpp.so) — classes, métodos, strings.",
            kind = KIND_GITHUB_ARCHIVE, repo = "Perfare/Il2CppDumper",
            assetPattern = "Il2CppDumper-.*\\.zip",
            postCommand = "p=$(find \"{TOOL_DIR}\" -maxdepth 1 -name \"Il2CppDumper*\" -type f | head -n 1); " +
                "[ -n \"${'$'}p\" ] && chmod +x \"${'$'}p\"",
            wrappers = mapOf("il2cppdumper" to "exec \"{TOOL_DIR}/Il2CppDumper\" \"${'$'}@\""),
            verifyCommand = "ls \"{TOOL_DIR}\" 2>&1 | head -n 5"
        ),
        ToolRecipe(
            id = "lief", name = "LIEF (Binary Analysis)", category = "analysis",
            description = "Biblioteca Python para análise/patch de ELF/PE/Mach-O/.so (pip install lief).",
            kind = KIND_PIP, pkgName = "lief",
            wrappers = emptyMap(),
            verifyCommand = "python3 -c \"import lief; print('lief', lief.__version__)\" 2>&1"
        ),

        // ---- Ferramentas adicionais integradas (repositórios públicos) ----
        ToolRecipe(
            id = "debloat", name = "DoAide Debloat", category = "device",
            description = "Debloat Android via ADB, reversível e SEM root: desativa ads/tracking/bloatware " +
                "com pm disable-user, protege apps essenciais, backup + restore de 1 linha. " +
                "Uso: debloat.py probe|backup|plan|apply --plan <arquivo> --yes|restore --record <arquivo>|check.",
            kind = KIND_GIT_CLONE, url = "https://github.com/DoAideAi/DoAide-Debloat",
            wrappers = mapOf(
                "debloat" to "exec python3 \"{TOOL_DIR}/tools/debloat.py\" \"${'$'}@\""
            ),
            verifyCommand = "{WRAPPER} --help 2>&1 | head -n 5"
        ),
        ToolRecipe(
            id = "phonedriver", name = "PhoneDriver (Qwen3-VL)", category = "automation",
            description = "Controle de celular Android por visão (Qwen3-VL): observa tela, planeja ações " +
                "e executa taps/swipes/input via ADB. Requer API de visão. Uso: python3 phone_agent.py.",
            kind = KIND_GIT_CLONE, url = "https://github.com/OminousIndustries/PhoneDriver",
            wrappers = mapOf(
                "phonedriver" to "exec python3 \"{TOOL_DIR}/phone_agent.py\" \"${'$'}@\""
            ),
            verifyCommand = "ls \"{TOOL_DIR}\" 2>&1 | head -n 5"
        ),
        ToolRecipe(
            id = "gstack", name = "gStack Skills (Garry Tan)", category = "agent-skills",
            description = "23 skills/personas de agente (CEO, Designer, Eng Manager, Release Manager, " +
                "Doc Engineer, QA). São prompts/skills para Claude Code — usar como referência de " +
                "system prompts e fluxos de trabalho.",
            kind = KIND_GIT_CLONE, url = "https://github.com/garrytan/gstack",
            verifyCommand = "ls \"{TOOL_DIR}\" 2>&1 | head -n 5",
            heavy = true
        ),
        ToolRecipe(
            id = "iptv", name = "IPTV-org (listas M3U)", category = "iptv",
            description = "Coleção pública de canais IPTV do mundo todo em M3U. Baixa as listas " +
                "por país/categoria (streams/*.m3u) para uso em players IPTV. " +
                "Uso: copiar arquivo .m3u desejado para o workspace.",
            kind = KIND_GIT_CLONE, url = "https://github.com/iptv-org/iptv",
            verifyCommand = "ls \"{TOOL_DIR}/streams\" 2>&1 | head -n 5"
        ),
        ToolRecipe(
            id = "openmaic", name = "OpenMAIC (Multi-Agent Classroom)", category = "agent-skills",
            description = "Sala de aula multi-agente (THU-MAIC): padrões de orquestração de múltiplos " +
                "agentes interativos. Referência para o AgentSwarm. É app web completo (Next.js).",
            kind = KIND_GIT_CLONE, url = "https://github.com/THU-MAIC/OpenMAIC",
            verifyCommand = "ls \"{TOOL_DIR}\" 2>&1 | head -n 5",
            heavy = true
        )
    )

    companion object {
        const val KIND_GITHUB_JAR = "GITHUB_JAR"
        const val KIND_GITHUB_ARCHIVE = "GITHUB_ARCHIVE"
        const val KIND_GITHUB_BINARY = "GITHUB_BINARY"
        const val KIND_PKG = "PKG"
        const val KIND_PIP = "PIP"
        const val KIND_NPM = "NPM"
        const val KIND_GIT_CLONE = "GIT_CLONE"
    }

    // ------------------------------------------------------------------
    // Dispatcher
    // ------------------------------------------------------------------

    suspend fun handle(args: JsonObject): String = withContext(Dispatchers.IO) {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull
            ?: return@withContext "Erro: 'action' é obrigatório."
        try {
            when (action) {
                "list_available" -> listAvailable(args)
                "info" -> info(args)
                "list_installed" -> listInstalled()
                "install" -> install(args)
                "install_custom" -> installCustom(args)
                "run" -> runTool(args)
                "remove" -> remove(args)
                "search_github" -> searchGithub(args)
                "list_release_assets" -> listReleaseAssets(args)
                else -> "Ação desconhecida em tool_hub: $action"
            }
        } catch (e: Exception) {
            "Exceção em tool_hub: ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    // ------------------------------------------------------------------
    // Catálogo / estado
    // ------------------------------------------------------------------

    private suspend fun toolsRoot(): File =
        File(environmentSetup.shellHome(), ".local/share/codex_tools")

    private suspend fun binDir(): File =
        File(environmentSetup.shellHome(), ".local/bin")

    private suspend fun stateFile(): File = File(toolsRoot(), "installed.json")

    private suspend fun loadState(): MutableMap<String, InstalledToolRecord> {
        val file = stateFile()
        if (!file.isFile) return mutableMapOf()
        return runCatching {
            json.decodeFromString<Map<String, InstalledToolRecord>>(file.readText()).toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private suspend fun saveState(state: Map<String, InstalledToolRecord>) {
        val file = stateFile()
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(state))
    }

    private suspend fun listAvailable(args: JsonObject): String {
        val category = (args["category"] as? JsonPrimitive)?.contentOrNull
        val filtered = registry.filter { category == null || it.category.equals(category, ignoreCase = true) }
        return buildString {
            appendLine("Ferramentas disponíveis no catálogo (${filtered.size}):")
            filtered.forEach { recipe ->
                appendLine("• ${recipe.id} [$recipe.category/${recipe.kind}] ${recipe.name}")
                appendLine("    ${recipe.description}")
                val flags = buildList {
                    if (recipe.heavy) add("PESADA")
                    if (recipe.requiresRoot) add("requer root")
                }
                if (flags.isNotEmpty()) appendLine("    ⚠ ${flags.joinToString(", ")}")
            }
            appendLine()
            appendLine("Instale com: tool_hub(action='install', tool_id='<id>')")
            appendLine("Toda instalação executa uma VERIFICAÇÃO REAL (roda a ferramenta e mostra a saída).")
            appendLine("Para baixar APKs da web use web_fetch_and_download (APKMirror/APKPure) — não há")
            appendLine("downloader de loja no catálogo porque não há binário aarch64 garantido para Termux.")
            appendLine("Para ferramentas fora do catálogo: search_github / list_release_assets + install_custom")
        }.trimEnd()
    }

    private fun info(args: JsonObject): String {
        val id = (args["tool_id"] as? JsonPrimitive)?.contentOrNull
            ?: return "Erro: 'tool_id' é obrigatório."
        val recipe = registry.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: return "Ferramenta '$id' não está no catálogo. Use list_available ou install_custom."
        return buildString {
            appendLine("=== ${recipe.name} (${recipe.id}) ===")
            appendLine("Categoria: ${recipe.category} | Tipo: ${recipe.kind}")
            appendLine(recipe.description)
            recipe.repo?.let { appendLine("Repo: https://github.com/$it") }
            recipe.url?.let { appendLine("URL: $it") }
            recipe.pkgName?.let { appendLine("Pacote: $it") }
            recipe.assetPattern?.let { appendLine("Asset: $it") }
            if (recipe.heavy) appendLine("⚠ Ferramenta pesada (RAM/disco).")
            if (recipe.requiresRoot) appendLine("⚠ Requer root para executar.")
            recipe.wrappers.forEach { (bin, cmd) -> appendLine("Wrapper: $bin → $cmd") }
        }.trimEnd()
    }

    private suspend fun listInstalled(): String {
        val state = loadState()
        if (state.isEmpty()) return "Nenhuma ferramenta instalada via tool_hub ainda."
        return buildString {
            appendLine("Instaladas (${state.size}):")
            state.values.sortedBy { it.name }.forEach { record ->
                appendLine("• ${record.id} — ${record.name} ${record.version}")
                appendLine("    dir: ${record.dir}")
                if (record.wrappers.isNotEmpty()) appendLine("    bins: ${record.wrappers.keys.joinToString(", ")}")
            }
        }.trimEnd()
    }

    // ------------------------------------------------------------------
    // Instalação
    // ------------------------------------------------------------------

    private suspend fun install(args: JsonObject): String {
        val id = (args["tool_id"] as? JsonPrimitive)?.contentOrNull
            ?: return "Erro: 'tool_id' é obrigatório."
        val recipe = registry.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: return "Ferramenta '$id' não está no catálogo. Use list_available ou install_custom."
        return installRecipe(recipe)
    }

    private suspend fun installRecipe(recipe: ToolRecipe): String {
        if (recipe.heavy) {
            shellExecutor.appendLiveLog("[tool_hub] Instalando ferramenta PESADA: ${recipe.name}\n")
        }
        return when (recipe.kind) {
            KIND_PKG -> installPkg(recipe)
            KIND_PIP -> installPip(recipe)
            KIND_NPM -> installNpm(recipe)
            KIND_GIT_CLONE -> installGitClone(recipe)
            KIND_GITHUB_JAR -> installFromGithubRelease(recipe, archive = false)
            KIND_GITHUB_ARCHIVE -> installFromGithubRelease(recipe, archive = true)
            KIND_GITHUB_BINARY -> installFromGithubRelease(recipe, archive = false, binary = true)
            else -> "Tipo de instalação não suportado: ${recipe.kind}"
        }
    }

    // ------------------------------------------------------------------
    // Verificação REAL pós-instalação (executa a ferramenta de verdade)
    // ------------------------------------------------------------------

    private suspend fun verifyInstall(
        recipe: ToolRecipe,
        toolDir: File?,
        wrappers: Map<String, String>,
        jarFile: File?,
        binaryFile: File?
    ): String {
        val template = recipe.verifyCommand ?: return ""
        val firstWrapper = wrappers.entries.firstOrNull()?.value
        val rendered = template
            .replace("{WRAPPER}", firstWrapper.orEmpty())
            .replace("{JAR}", jarFile?.absolutePath.orEmpty())
            .replace("{FILE}", binaryFile?.absolutePath.orEmpty())
            .replace("{TOOL_DIR}", toolDir?.absolutePath.orEmpty())
        if (rendered.isBlank()) return ""
        val result = shellExecutor.execute(rendered, timeoutSec = 180)
        val healthy = result.exitCode == 0 || result.output.isNotBlank()
        return "\nVERIFICAÇÃO REAL (exit=${result.exitCode}):\n" +
            result.output.take(900).ifBlank { "(sem saída)" } +
            if (healthy) "" else "\n⚠ A verificação indicou problema — teste manualmente antes de usar."
    }

    private suspend fun installPkg(recipe: ToolRecipe): String {
        val pkg = recipe.pkgName ?: return "Erro: pkgName ausente."
        val result = shellExecutor.execute("pkg install -y $pkg", timeoutSec = 600)
        if (result.exitCode == 0) {
            recordInstall(recipe, version = "pkg:$pkg", dir = "system", wrappers = emptyMap())
            val verification = verifyInstall(recipe, null, emptyMap(), null, null)
            return "✅ '$pkg' instalado via pkg.\n${result.output.take(1200)}$verification"
        }
        return "Falha ao instalar '$pkg' via pkg (exit=${result.exitCode}):\n${result.output.take(2000)}"
    }

    private suspend fun installPip(recipe: ToolRecipe): String {
        val pkg = recipe.pkgName ?: return "Erro: pkgName ausente."
        val result = shellExecutor.execute(
            "pip install --break-system-packages $pkg 2>/dev/null || pip install $pkg",
            timeoutSec = 900
        )
        if (result.exitCode == 0) {
            recordInstall(recipe, version = "pip:$pkg", dir = "python-site", wrappers = emptyMap())
            val verification = verifyInstall(recipe, null, emptyMap(), null, null)
            return "✅ '$pkg' instalado via pip.\n${result.output.take(1200)}$verification"
        }
        return "Falha ao instalar '$pkg' via pip (exit=${result.exitCode}):\n${result.output.take(2000)}"
    }

    private suspend fun installNpm(recipe: ToolRecipe): String {
        val pkg = recipe.pkgName ?: return "Erro: pkgName ausente."
        val deps = shellExecutor.execute("command -v npm >/dev/null 2>&1 || pkg install -y nodejs-lts", timeoutSec = 600)
        if (deps.exitCode != 0) return "Node/npm indisponível: ${deps.output.take(500)}"
        val result = shellExecutor.execute("npm install -g $pkg", timeoutSec = 900)
        if (result.exitCode == 0) {
            recordInstall(recipe, version = "npm:$pkg", dir = "npm-global", wrappers = emptyMap())
            val verification = verifyInstall(recipe, null, emptyMap(), null, null)
            return "✅ '$pkg' instalado via npm.\n${result.output.take(1200)}$verification"
        }
        return "Falha ao instalar '$pkg' via npm (exit=${result.exitCode}):\n${result.output.take(2000)}"
    }

    private suspend fun installGitClone(recipe: ToolRecipe): String {
        val url = recipe.url ?: return "Erro: url ausente."
        val dir = File(toolsRoot(), recipe.id)
        if (dir.isDirectory) {
            val pull = shellExecutor.execute(
                "cd \"${dir.absolutePath}\" && git pull --ff-only", timeoutSec = 120
            )
            if (pull.exitCode == 0) return "✅ '${recipe.id}' já instalado — atualizado (git pull)."
        }
        val result = shellExecutor.execute(
            "git clone --depth 1 \"$url\" \"${dir.absolutePath}\"", timeoutSec = 900
        )
        if (result.exitCode != 0) {
            return "Falha ao clonar $url (exit=${result.exitCode}):\n${result.output.take(2000)}"
        }
        recipe.postCommand?.let { post ->
            val rendered = post.replace("{TOOL_DIR}", dir.absolutePath)
            shellExecutor.execute(rendered, workdir = dir.absolutePath, timeoutSec = 1800)
        }
        val wrappers = createWrappers(recipe, dir, jarFile = null, binaryFile = null)
        recordInstall(recipe, version = "git:${System.currentTimeMillis()}", dir = dir.absolutePath, wrappers = wrappers)
        val verification = verifyInstall(recipe, dir, wrappers, null, null)
        return "✅ '${recipe.name}' clonado em ${dir.absolutePath}.\n${describeWrappers(wrappers)}$verification"
    }

    private suspend fun installFromGithubRelease(
        recipe: ToolRecipe,
        archive: Boolean,
        binary: Boolean = false
    ): String {
        val repo = recipe.repo ?: return "Erro: repo ausente."
        val release = fetchLatestRelease(repo)
            ?: return "Não foi possível obter o release latest de $repo (API do GitHub). " +
                "Alternativa: tool_hub(action='list_release_assets', repo='$repo') e install_custom."

        val assets = release.second
        val pattern = recipe.assetPattern
        val asset = pickAsset(assets, pattern)
            ?: return "Nenhum asset de '${release.first}' casa com o padrão '$pattern'.\n" +
                "Assets disponíveis:\n${assets.joinToString("\n") { "  - ${it.first} (${it.second} bytes)" }}\n" +
                "Use install_custom com a URL desejada."

        val toolDir = File(toolsRoot(), recipe.id)
        toolDir.deleteRecursively()
        toolDir.mkdirs()

        val targetName = asset.first.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(toolDir, targetName)
        val downloadError = downloadToFile(asset.third, target)
        if (downloadError != null) {
            return "Falha no download de ${asset.first}: $downloadError"
        }

        var jarFile: File? = null
        var binaryFile: File? = null

        if (archive) {
            val extract = shellExecutor.execute(
                "unzip -qo \"${target.absolutePath}\" -d \"${toolDir.absolutePath}\" 2>/dev/null || " +
                    "tar -xzf \"${target.absolutePath}\" -C \"${toolDir.absolutePath}\"",
                timeoutSec = 600
            )
            if (extract.exitCode != 0) {
                return "Falha ao extrair ${asset.first}:\n${extract.output.take(1500)}"
            }
            target.delete()
            // Torna todos os scripts/binários executáveis
            shellExecutor.execute(
                "find \"${toolDir.absolutePath}\" -type f \\( -name \"*.sh\" -o -perm -u+x \\) -exec chmod +x {} \\; 2>/dev/null; " +
                    "find \"${toolDir.absolutePath}\" -maxdepth 2 -type d -name bin -exec chmod -R +x {} \\; 2>/dev/null",
                timeoutSec = 60
            )
        } else if (binary) {
            binaryFile = target
            recipe.postCommand?.let { post ->
                val rendered = post.replace("{FILE}", target.absolutePath)
                shellExecutor.execute(rendered, timeoutSec = 120)
            }
            shellExecutor.execute("chmod +x \"${target.absolutePath}\"", timeoutSec = 30)
        } else {
            jarFile = target
        }

        val wrappers = createWrappers(recipe, toolDir, jarFile, binaryFile)
        recordInstall(recipe, version = release.first, dir = toolDir.absolutePath, wrappers = wrappers)
        val verification = verifyInstall(recipe, toolDir, wrappers, jarFile, binaryFile)
        return buildString {
            appendLine("✅ '${recipe.name}' ${release.first} instalado em ${toolDir.absolutePath}")
            appendLine("Asset: ${asset.first}")
            append(describeWrappers(wrappers))
            append(verification)
            if (recipe.requiresRoot) appendLine("⚠ Execução requer root (su).")
            if (recipe.heavy) appendLine("⚠ Ferramenta pesada: monitore RAM/disco.")
        }.trimEnd()
    }

    private suspend fun installCustom(args: JsonObject): String {
        val name = (args["name"] as? JsonPrimitive)?.contentOrNull
            ?: return "Erro: 'name' é obrigatório (ex.: minha-ferramenta)."
        val url = (args["url"] as? JsonPrimitive)?.contentOrNull
            ?: return "Erro: 'url' é obrigatório (URL do asset/binário/release)."
        val id = ApkProjectTemplates.sanitizeDirName(name).lowercase()
        val postCommand = (args["post_command"] as? JsonPrimitive)?.contentOrNull
        val wrapperBin = (args["wrapper_bin"] as? JsonPrimitive)?.contentOrNull
        val wrapperCommand = (args["wrapper_command"] as? JsonPrimitive)?.contentOrNull

        val toolDir = File(toolsRoot(), id)
        toolDir.mkdirs()
        val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "tool.bin" }
        val target = File(toolDir, fileName)
        val error = downloadToFile(url, target)
        if (error != null) return "Falha no download: $error"

        var binaryFile: File? = target
        if (fileName.endsWith(".jar")) {
            binaryFile = null
        } else if (fileName.endsWith(".zip") || fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
            val extract = shellExecutor.execute(
                "unzip -qo \"${target.absolutePath}\" -d \"${toolDir.absolutePath}\" 2>/dev/null || " +
                    "tar -xzf \"${target.absolutePath}\" -C \"${toolDir.absolutePath}\"",
                timeoutSec = 600
            )
            target.delete()
            if (extract.exitCode != 0) return "Falha ao extrair $fileName:\n${extract.output.take(1500)}"
            binaryFile = null
        } else {
            shellExecutor.execute("chmod +x \"${target.absolutePath}\"", timeoutSec = 30)
        }

        postCommand?.let { post ->
            shellExecutor.execute(
                post.replace("{TOOL_DIR}", toolDir.absolutePath).replace("{FILE}", target.absolutePath),
                workdir = toolDir.absolutePath, timeoutSec = 900
            )
        }

        val wrappers = if (wrapperBin != null && wrapperCommand != null) {
            createWrappers(
                ToolRecipe(
                    id = id, name = name, category = "custom", description = "",
                    kind = "CUSTOM", wrappers = mapOf(wrapperBin to wrapperCommand)
                ),
                toolDir,
                jarFile = target.takeIf { it.extension == "jar" && it.exists() },
                binaryFile = binaryFile
            )
        } else emptyMap()

        val record = InstalledToolRecord(
            id = id, name = name, version = "custom",
            dir = toolDir.absolutePath, wrappers = wrappers,
            installedAt = System.currentTimeMillis()
        )
        val state = loadState()
        state[id] = record
        saveState(state)
        appendDependencies("- [custom] $name ← $url")

        val jarForVerify = target.takeIf { it.extension == "jar" && it.exists() }
        val verification = verifyInstall(
            ToolRecipe(
                id = id, name = name, category = "custom", description = "", kind = "CUSTOM",
                verifyCommand = if (wrappers.isNotEmpty()) "{WRAPPER} 2>&1 | head -n 6" else null
            ),
            toolDir, wrappers, jarForVerify, binaryFile
        )

        return buildString {
            appendLine("✅ '$name' instalado (custom) em ${toolDir.absolutePath}")
            append(describeWrappers(wrappers))
            append(verification)
            if (wrappers.isEmpty()) {
                appendLine("Dica: reinstale com wrapper_bin/wrapper_command para criar um comando no PATH.")
            }
        }.trimEnd()
    }

    // ------------------------------------------------------------------
    // Execução / remoção
    // ------------------------------------------------------------------

    private suspend fun runTool(args: JsonObject): String {
        val id = (args["tool_id"] as? JsonPrimitive)?.contentOrNull
            ?: return "Erro: 'tool_id' é obrigatório."
        val runArgs = (args["run_args"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val state = loadState()
        val record = state[id]
            ?: return "Ferramenta '$id' não está instalada. Use tool_hub(action='install', tool_id='$id')."

        val wrapper = record.wrappers.entries.firstOrNull()
        val command = if (wrapper != null) {
            "\"${wrapper.value}\" $runArgs"
        } else {
            "${record.dir} $runArgs"
        }
        val result = shellExecutor.execute(command, timeoutSec = 900)
        return "tool_hub run '$id' (exit=${result.exitCode}):\n${result.output}"
    }

    private suspend fun remove(args: JsonObject): String {
        val id = (args["tool_id"] as? JsonPrimitive)?.contentOrNull
            ?: return "Erro: 'tool_id' é obrigatório."
        val state = loadState()
        val record = state.remove(id)
            ?: return "Ferramenta '$id' não está registrada."
        // Só apaga diretórios absolutos dentro do root do ToolHub (segurança).
        val rootPath = toolsRoot().absolutePath
        val dir = File(record.dir)
        if (dir.isAbsolute && dir.absolutePath.startsWith(rootPath)) {
            dir.deleteRecursively()
        }
        record.wrappers.keys.forEach { bin ->
            File(binDir(), bin).delete()
        }
        saveState(state)
        return "✅ '$id' removido (${record.dir})."
    }

    // ------------------------------------------------------------------
    // Descoberta dinâmica (GitHub API)
    // ------------------------------------------------------------------

    private suspend fun searchGithub(args: JsonObject): String {
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull
            ?: return "Erro: 'query' é obrigatório (ex.: apk decompiler, frida scripts)."
        val limit = (args["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.coerceIn(1, 20) ?: 10
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val body = httpGetJson("https://api.github.com/search/repositories?q=$encoded&per_page=$limit&sort=stars")
            ?: return "Falha na busca do GitHub (rate limit ou rede)."
        val items = body["items"]?.jsonArray ?: return "Busca sem resultados."
        return buildString {
            appendLine("Resultados GitHub para '$query':")
            items.forEach { element ->
                val repo = element.jsonObject
                val fullName = (repo["full_name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val stars = (repo["stargazers_count"] as? JsonPrimitive)?.longOrNull ?: 0
                val description = (repo["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                appendLine("• $fullName ⭐$stars")
                if (description.isNotBlank()) appendLine("    $description".take(200))
                appendLine("    releases: tool_hub(action='list_release_assets', repo='$fullName')")
            }
        }.trimEnd()
    }

    private suspend fun listReleaseAssets(args: JsonObject): String {
        val repo = (args["repo"] as? JsonPrimitive)?.contentOrNull
            ?: return "Erro: 'repo' é obrigatório (owner/repo)."
        val release = fetchLatestRelease(repo)
            ?: return "Nenhum release/latest encontrado para $repo."
        return buildString {
            appendLine("Release ${release.first} de $repo — assets:")
            release.second.forEach { (name, size) ->
                appendLine("• $name (${size / 1024 / 1024} MB)")
                appendLine("    $name → use install_custom(name='…', url='<browser_download_url>')")
            }
        }.trimEnd()
    }

    // ------------------------------------------------------------------
    // HTTP / GitHub
    // ------------------------------------------------------------------

    private fun httpGetJson(url: String): JsonObject? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CodexAgent/1.0")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            json.parseToJsonElement(body).jsonObject
        }
    }.getOrNull()

    /**
     * Retorna (tag, lista de (nome do asset, tamanho, url de download)).
     */
    private fun fetchLatestRelease(repo: String): Pair<String, List<Triple<String, Long, String>>>? {
        val root = httpGetJson("https://api.github.com/repos/$repo/releases/latest") ?: return null
        val tag = (root["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val assets = root["assets"]?.jsonArray?.mapNotNull { element ->
            val obj = element.jsonObject
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val size = (obj["size"] as? JsonPrimitive)?.longOrNull ?: 0L
            val url = (obj["browser_download_url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Triple(name, size, url)
        } ?: emptyList()
        return tag to assets
    }

    private fun pickAsset(
        assets: List<Triple<String, Long, String>>,
        pattern: String?
    ): Triple<String, Long, String>? {
        if (pattern == null) return assets.firstOrNull()
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        val matched = assets.filter { regex.containsMatchIn(it.first) }
        // Preferência por builds arm64 quando existirem
        val arm64 = matched.filter { it.first.contains("arm64", true) || it.first.contains("aarch64", true) }
        return (arm64.ifEmpty { matched }).maxByOrNull { it.second } ?: assets.firstOrNull()
    }

    private fun downloadToFile(url: String, target: File): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CodexAgent/1.0")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use "HTTP ${response.code}"
            val body = response.body ?: return@use "Corpo vazio"
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        total += read
                        if (total % (1024 * 1024 * 8) < 64 * 1024) {
                            shellExecutor.appendLiveLog("[tool_hub] ${target.name}: ${total / 1024 / 1024} MB\r\n")
                        }
                    }
                }
            }
            null
        }
    }.getOrElse { it.message ?: "erro desconhecido" }

    // ------------------------------------------------------------------
    // Wrappers / registro
    // ------------------------------------------------------------------

    /**
     * Cria scripts executáveis em $HOME/.local/bin (já no PATH) apontando para a ferramenta.
     * Templates suportam {TOOL_DIR}, {JAR} (jar baixado) e {FILE} (binário baixado).
     */
    private suspend fun createWrappers(
        recipe: ToolRecipe,
        toolDir: File,
        jarFile: File?,
        binaryFile: File?
    ): Map<String, String> {
        if (recipe.wrappers.isEmpty()) return emptyMap()
        val bin = binDir()
        bin.mkdirs()
        // Shebang conforme o motor ativo (Termux usa bash próprio; shell interno usa /system/bin/sh)
        val shebang = if (environmentSetup.shellHome().endsWith("com.termux/files/home")) {
            "#!/data/data/com.termux/files/usr/bin/sh"
        } else {
            "#!/system/bin/sh"
        }
        val created = mutableMapOf<String, String>()
        recipe.wrappers.forEach { (binName, template) ->
            val rendered = template
                .replace("{TOOL_DIR}", toolDir.absolutePath)
                .replace("{JAR}", jarFile?.absolutePath ?: "")
                .replace("{FILE}", binaryFile?.absolutePath ?: "")
            val wrapperFile = File(bin, binName)
            wrapperFile.writeText(
                "$shebang\n" +
                    "export JAVA_OPTS=\"-Xmx1536m\"\n" +
                    "export _JAVA_OPTIONS=\"-Xmx1536m\"\n" +
                    rendered + "\n"
            )
            shellExecutor.execute("chmod +x \"${wrapperFile.absolutePath}\"", timeoutSec = 30)
            created[binName] = wrapperFile.absolutePath
        }
        return created
    }

    private fun describeWrappers(wrappers: Map<String, String>): String =
        if (wrappers.isEmpty()) ""
        else "Comandos disponíveis no PATH:\n" +
            wrappers.entries.joinToString("\n") { "  • ${it.key} → ${it.value}" } + "\n"

    private suspend fun recordInstall(
        recipe: ToolRecipe,
        version: String,
        dir: String,
        wrappers: Map<String, String>
    ) {
        val record = InstalledToolRecord(
            id = recipe.id,
            name = recipe.name,
            version = version,
            dir = dir,
            wrappers = wrappers,
            installedAt = System.currentTimeMillis()
        )
        val state = loadState()
        state[recipe.id] = record
        saveState(state)
        appendDependencies("- [${recipe.category}] ${recipe.name} ($version)")
    }

    private suspend fun appendDependencies(line: String) {
        runCatching {
            val root = workspaceRepository.active()?.rootPath ?: return@runCatching
            val file = File(root, ".codex/dependencies.md")
            file.parentFile?.mkdirs()
            file.appendText("$line\n")
        }
    }
}
