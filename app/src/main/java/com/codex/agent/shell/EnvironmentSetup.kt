package com.codex.agent.shell

import android.content.Context
import android.os.Build
import com.codex.agent.data.model.Constants
import com.codex.agent.data.model.ExecutionMode
import com.codex.agent.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

/** Resultado da inspeção do ambiente (usado pelo Setup Wizard da Parte 4). */
data class ToolchainStatus(
    val termuxInstalled: Boolean,
    val termuxBridgeWorking: Boolean,
    val deviceArch: String,
    val androidSdk: Int,
    val installedTools: Map<String, Boolean>,
    val internalUsrReady: Boolean,
    val debugKeystoreReady: Boolean
)

/**
 * Auto-provisionamento da toolchain de desenvolvimento e engenharia reversa de APKs:
 * openjdk-17, git, python, nodejs, apktool, jadx, zipalign, apksigner, binutils (objdump/readelf),
 * radare2 e geração automática da debug.keystore (~/.android).
 *
 * O provisionamento rola via [ShellExecutor] — Termux Bridge quando disponível, shell interno
 * como fallback — sem pedir confirmação manual de passo (YOLO).
 */
class EnvironmentSetup(
    private val context: Context,
    private val shellExecutor: ShellExecutor,
    private val settingsRepository: SettingsRepository
) {

    private val internalUsrDir = File(context.filesDir, "usr")
    private val internalAndroidDir = File(context.filesDir, ".android")

    // ------------------------------------------------------------------
    // Inspeção
    // ------------------------------------------------------------------

    suspend fun inspectEnvironment(): ToolchainStatus = withContext(Dispatchers.IO) {
        val termuxInstalled = shellExecutor.isTermuxInstalled()
        // A ponte só está "funcionando" se a flag .ready existe (allow-external-apps ativo).
        // Testar via execute() é enganoso no modo HYBRID: cairia no shell interno e
        // reportaria BRIDGE_OK mesmo sem a ponte configurada.
        val bridgeWorking = shellExecutor.isTermuxBridgeReady()

        val tools = listOf(
            "git", "python3", "node", "java", "apktool", "jadx",
            "zipalign", "apksigner", "objdump", "readelf", "radare2", "r2"
        )
        val statusMap = linkedMapOf<String, Boolean>()
        if (bridgeWorking) {
            val check = tools.joinToString("; ") { tool ->
                "command -v $tool >/dev/null 2>&1 && echo \"$tool:YES\" || echo \"$tool:NO\""
            }
            val result = shellExecutor.execute(check, timeoutSec = 30)
            tools.forEach { tool -> statusMap[tool] = result.output.contains("$tool:YES") }
        } else {
            tools.forEach { statusMap[it] = false }
        }

        val internalReady = File(internalUsrDir, "bin").isDirectory &&
            File(internalUsrDir, "bin").listFiles()?.isNotEmpty() == true

        ToolchainStatus(
            termuxInstalled = termuxInstalled,
            termuxBridgeWorking = bridgeWorking,
            deviceArch = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            androidSdk = Build.VERSION.SDK_INT,
            installedTools = statusMap,
            internalUsrReady = internalReady,
            debugKeystoreReady = termuxKeystoreFile().exists() || internalKeystoreFile().exists()
        )
    }

    // ------------------------------------------------------------------
    // Provisionamento completo (Dev + APK RE)
    // ------------------------------------------------------------------

    /**
     * Instala/atualiza toda a suíte no ambiente Termux (ou interno ativo), gera a
     * debug.keystore e garante PATH permanente. Progresso é reportado para a UI.
     */
    suspend fun provisionFullToolchain(onProgress: (String) -> Unit): ShellResult =
        withContext(Dispatchers.IO) {
            onProgress("Iniciando provisionamento da Toolchain (Dev + Engenharia Reversa de APK)…")
            val script = buildProvisionScript()
            val result = shellExecutor.execute(command = script, timeoutSec = 900)
            val success = result.exitCode == 0
            onProgress(
                if (success) "Toolchain instalada com sucesso!"
                else "Provisionamento terminou com código ${result.exitCode}; verifique o log do terminal."
            )
            settingsRepository.setToolchainProvisioned(success)
            result
        }

    private fun buildProvisionScript(): String {
        val apktoolJarUrl = "https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.10.0.jar"
        val apktoolScriptUrl = "https://raw.githubusercontent.com/iBotPeaches/Apktool/master/scripts/linux/apktool"
        val jadxUrl = "https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip"

        return """
            set -u
            echo "=== [1/5] Análise do ambiente (Delta Check) ==="
            MISSING_PKGS=""

            command -v java >/dev/null 2>&1 && echo "  [OK] OpenJDK 17 já instalado." || MISSING_PKGS="${'$'}MISSING_PKGS openjdk-17"
            command -v git >/dev/null 2>&1 && echo "  [OK] Git já instalado." || MISSING_PKGS="${'$'}MISSING_PKGS git"
            command -v python3 >/dev/null 2>&1 && echo "  [OK] Python 3 já instalado." || MISSING_PKGS="${'$'}MISSING_PKGS python"
            command -v node >/dev/null 2>&1 && echo "  [OK] Node.js já instalado." || MISSING_PKGS="${'$'}MISSING_PKGS nodejs-lts"
            command -v apksigner >/dev/null 2>&1 && echo "  [OK] apksigner já instalado." || MISSING_PKGS="${'$'}MISSING_PKGS apksigner"
            command -v zipalign >/dev/null 2>&1 && echo "  [OK] zipalign já instalado." || MISSING_PKGS="${'$'}MISSING_PKGS zipalign"
            command -v radare2 >/dev/null 2>&1 && echo "  [OK] radare2 já instalado." || MISSING_PKGS="${'$'}MISSING_PKGS radare2"
            command -v objdump >/dev/null 2>&1 && echo "  [OK] binutils já instalado." || MISSING_PKGS="${'$'}MISSING_PKGS binutils"

            if [ -n "${'$'}MISSING_PKGS" ]; then
              echo "=== [2/5] Baixando e instalando apenas pacotes ausentes:${'$'}MISSING_PKGS ==="
              (yes | pkg update -y) >/dev/null 2>&1 || true
              for P in ${'$'}MISSING_PKGS; do
                echo "  -> Baixando e instalando: ${'$'}P"
                pkg install -y "${'$'}P" >/dev/null 2>&1 || echo "  -> AVISO: falha ao instalar ${'$'}P"
              done
            else
              echo "=== [2/5] Todos os pacotes base já estão instalados! Ignorando download. ==="
            fi

            TMP_DIR="${'$'}HOME/tmp"
            mkdir -p "${'$'}HOME/.local/bin" "${'$'}HOME/.local/jadx" "${'$'}HOME/.android" "${'$'}TMP_DIR" 2>/dev/null || true

            echo "=== [3/5] Verificação do Apktool (GitHub/Bitbucket) ==="
            if command -v apktool >/dev/null 2>&1 || [ -f "${'$'}HOME/.local/bin/apktool.jar" ]; then
              echo "  [OK] Apktool já instalado. Ignorando download."
            else
              echo "  -> Baixando Apktool via streaming..."
              curl -sL --connect-timeout 15 "$apktoolScriptUrl" -o "${'$'}HOME/.local/bin/apktool" || wget -q "$apktoolScriptUrl" -O "${'$'}HOME/.local/bin/apktool"
              curl -sL --connect-timeout 15 "$apktoolJarUrl" -o "${'$'}HOME/.local/bin/apktool.jar" || wget -q "$apktoolJarUrl" -O "${'$'}HOME/.local/bin/apktool.jar"
              chmod +x "${'$'}HOME/.local/bin/apktool" 2>/dev/null || true
            fi

            echo "=== [4/5] Verificação do JADX CLI (GitHub Release) ==="
            if command -v jadx >/dev/null 2>&1 || [ -f "${'$'}HOME/.local/jadx/bin/jadx" ]; then
              echo "  [OK] JADX CLI já instalado. Ignorando download."
            else
              echo "  -> Baixando JADX CLI via streaming..."
              curl -sL --connect-timeout 20 "$jadxUrl" -o "${'$'}TMP_DIR/jadx.zip" || wget "$jadxUrl" -O "${'$'}TMP_DIR/jadx.zip"
              if [ -f "${'$'}TMP_DIR/jadx.zip" ]; then
                unzip -qo "${'$'}TMP_DIR/jadx.zip" -d "${'$'}HOME/.local/jadx" || echo "Extração do JADX falhou"
                chmod +x "${'$'}HOME/.local/jadx/bin/jadx" 2>/dev/null || true
                ln -sf "${'$'}HOME/.local/jadx/bin/jadx" "${'$'}HOME/.local/bin/jadx" 2>/dev/null || true
                rm -f "${'$'}TMP_DIR/jadx.zip"
              fi
            fi

            echo "=== [5/5] Keystore e Configuração de PATH ==="
            if [ ! -f "${'$'}HOME/.android/debug.keystore" ]; then
              keytool -genkey -v \
                -keystore "${'$'}HOME/.android/debug.keystore" \
                -storepass android -alias androiddebugkey -keypass android \
                -keyalg RSA -keysize 2048 -validity 10000 \
                -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 || true
            fi

            grep -qxF 'export PATH="${'$'}HOME/.local/bin:${'$'}PATH"' "${'$'}HOME/.bashrc" 2>/dev/null \
              || echo 'export PATH="${'$'}HOME/.local/bin:${'$'}PATH"' >> "${'$'}HOME/.bashrc"
            grep -qxF 'export PATH="${'$'}HOME/.local/bin:${'$'}PATH"' "${'$'}HOME/.profile" 2>/dev/null \
              || echo 'export PATH="${'$'}HOME/.local/bin:${'$'}PATH"' >> "${'$'}HOME/.profile"

            echo "--- RESUMO DAS FERRAMENTAS ---"
            java -version 2>&1 | head -n 1
            python3 --version 2>&1
            node --version 2>&1
            apktool --version 2>&1
            jadx --version 2>&1
            for T in git zipalign apksigner objdump readelf radare2; do
              command -v "${'$'}T" >/dev/null 2>&1 && echo "${'$'}T: OK" || echo "${'$'}T: AUSENTE"
            done
            echo "=== PROVISIONAMENTO DELTA CONCLUÍDO ==="
        """.trimIndent()
    }

    // ------------------------------------------------------------------
    // Keystore
    // ------------------------------------------------------------------

    fun termuxKeystoreFile(): File =
        File("/data/data/com.termux/files/home/.android/debug.keystore")

    fun internalKeystoreFile(): File =
        File(internalAndroidDir, "debug.keystore").apply { parentFile?.mkdirs() }

    /** Keystore usada para assinar APKs reconstruídos, conforme o motor ativo. */
    suspend fun debugKeystorePath(): String = withContext(Dispatchers.IO) {
        val mode = settingsRepository.executionMode()
        if (mode == ExecutionMode.INTERNAL_ONLY || !shellExecutor.isTermuxInstalled()) {
            internalKeystoreFile().absolutePath
        } else {
            termuxKeystoreFile().absolutePath
        }
    }

    /** Gera a keystore interna (modo standalone) com keytool do usuário, se disponível. */
    suspend fun generateInternalKeystore(onProgress: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val keystore = internalKeystoreFile()
            if (keystore.exists()) return@withContext true
            // Resolve keytool: caminhos conhecidos primeiro, depois procura no PATH.
            // Usa canExecute() (não exists()) e lida com nomes bypass PATH.
            val candidates = mutableListOf(
                File(internalUsrDir, "bin/keytool").absolutePath,
                File(internalUsrDir, "openjdk/bin/keytool").absolutePath
            )
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
                candidates.add(File(dir, "keytool").absolutePath)
            }
            val javaBin = candidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
                ?: return@withContext false.also { onProgress("keytool não encontrado no ambiente interno.") }

            onProgress("Gerando keystore interna…")
            val code = runCatching {
                val pb = ProcessBuilder(
                    javaBin, "-genkey", "-v",
                    "-keystore", keystore.absolutePath,
                    "-storepass", "android", "-alias", "androiddebugkey", "-keypass", "android",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                    "-dname", "CN=Android Debug,O=Android,C=US"
                ).redirectErrorStream(true)
                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText()
                val c = process.waitFor()
                if (c != 0) onProgress("Falha: ${output.take(400)}")
                c
            }.getOrElse { e ->
                onProgress("Falha ao gerar keystore: ${e.message}")
                -1
            }
            if (code == 0) onProgress("Keystore interna criada.")
            code == 0
        }

    /** Diretório HOME do shell ativo (Termux ou interno) — base para SDK/keystore. */
    suspend fun shellHome(): String = withContext(Dispatchers.IO) {
        val mode = settingsRepository.executionMode()
        if (mode == ExecutionMode.INTERNAL_ONLY || !shellExecutor.isTermuxInstalled()) {
            File(context.filesDir, "home").absolutePath
        } else {
            "/data/data/com.termux/files/home"
        }
    }

    fun androidSdkDir(home: String): File = File(home, "android-sdk")

    fun androidJarFile(home: String, api: Int = 34): File =
        File(androidSdkDir(home), "platforms/android-$api/android.jar")

    suspend fun androidSdkReady(api: Int = 34): Boolean = withContext(Dispatchers.IO) {
        androidJarFile(shellHome(), api).isFile
    }

    /**
     * Provisiona o SDK mínimo para compilar APKs no dispositivo: ferramentas de build
     * (aapt2/aapt/d8/apksigner/zipalign) + android.jar oficial da plataforma (dl.google.com).
     */
    suspend fun provisionAndroidSdk(
        api: Int = 34,
        customUrl: String? = null,
        onProgress: (String) -> Unit
    ): ShellResult = withContext(Dispatchers.IO) {
        onProgress("Provisionando SDK Android (ferramentas + android.jar API $api)…")
        val script = buildSdkProvisionScript(api, customUrl)
        val result = shellExecutor.execute(script, timeoutSec = 1800)
        val ready = androidJarFile(shellHome(), api).isFile
        settingsRepository.setAndroidSdkProvisioned(ready)
        onProgress(
            if (ready) "SDK Android pronto: ${androidJarFile(shellHome(), api).absolutePath}"
            else "Provisionamento do SDK terminou com código ${result.exitCode}."
        )
        result
    }

    private fun buildSdkProvisionScript(api: Int, customUrl: String?): String {
        val primaryUrl = customUrl ?: "https://dl.google.com/android/repository/platform-${api}_r02.zip"
        val fallbackUrl = "https://dl.google.com/android/repository/platform-${api}_r01.zip"
        return """
            set -u
            fail() { echo "SDK_FAILED: ${'$'}1"; exit 1; }
            echo "=== [1/3] Ferramentas de build (falhas individuais toleradas) ==="
            for P in openjdk-17 aapt2 aapt d8 apksigner zipalign zip unzip wget; do
              pkg install -y "${'$'}P" >/dev/null 2>&1 || echo "AVISO: pkg ${'$'}P indisponível"
            done

            SDK="${'$'}HOME/android-sdk"
            AJAR="${'$'}SDK/platforms/android-$api/android.jar"
            mkdir -p "${'$'}SDK/platforms/android-$api"

            echo "=== [2/3] android.jar (platform-$api) ==="
            if [ ! -s "${'$'}AJAR" ]; then
              TMP="${'$'}SDK/tmp"; rm -rf "${'$'}TMP"; mkdir -p "${'$'}TMP"
              wget -q "$primaryUrl" -O "${'$'}SDK/platform.zip" \
                || wget -q "$fallbackUrl" -O "${'$'}SDK/platform.zip" \
                || fail download_platform
              unzip -qo "${'$'}SDK/platform.zip" -d "${'$'}TMP" || fail unzip_platform
              FOUND=${'$'}(find "${'$'}TMP" -name android.jar | head -n 1)
              [ -n "${'$'}FOUND" ] && cp "${'$'}FOUND" "${'$'}AJAR" || fail android_jar_nao_encontrado
              rm -rf "${'$'}TMP" "${'$'}SDK/platform.zip"
            fi
            ls -la "${'$'}AJAR"

            echo "=== [3/3] Verificação ==="
            for T in aapt2 aapt d8 apksigner zipalign javac keytool; do
              command -v "${'$'}T" >/dev/null 2>&1 && echo "${'$'}T: OK" || echo "${'$'}T: AUSENTE"
            done
            echo "SDK_PROVISION_OK"
        """.trimIndent()
    }

    /** Baixa e extrai um pacote (.zip/.tar.gz) para filesDir/usr — bootstrap aarch64. */
    suspend fun downloadAndExtractInternalPackage(
        url: String,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val targetDir = internalUsrDir
        targetDir.mkdirs()
        val tmp = File(context.cacheDir, "pkg_${System.currentTimeMillis()}")
        try {
            onProgress("Baixando $url…")
            val connection = URL(url).openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "CodexAgent/1.0")
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            onProgress("Extraindo para ${targetDir.absolutePath}…")
            val ok = when {
                url.endsWith(".zip") || tmp.name.endsWith(".zip") -> extractZip(tmp, targetDir)
                else -> extractTarViaShell(tmp, targetDir)
            }
            if (ok) {
                markBinariesExecutable(File(targetDir, "bin"))
                markBinariesExecutable(File(targetDir, "local/bin"))
                settingsRepository.setEnvBootstrapDone(true)
            }
            onProgress(if (ok) "Pacote instalado com sucesso." else "Falha ao extrair pacote.")
            ok
        } catch (e: Exception) {
            onProgress("Erro no download/extração: ${e.message}")
            false
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun extractZip(zip: File, targetDir: File): Boolean = runCatching {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name).canonicalFile
                val targetCanonical = targetDir.canonicalFile
                // Zip-Slip: separator-aware — não aceita irmãos como ".../usr_evil".
                if (outFile.path != targetCanonical.path && !outFile.path.startsWith(targetCanonical.path + File.separator)) {
                    entry = zis.nextEntry; continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                    if (entry.name.contains("/bin/")) outFile.setExecutable(true)
                }
                entry = zis.nextEntry
            }
        }
        true
    }.getOrDefault(false)

    private fun extractTarViaShell(archive: File, targetDir: File): Boolean = runCatching {
        val process = ProcessBuilder(
            "/system/bin/sh", "-c",
            "tar -xzf '${archive.absolutePath}' -C '${targetDir.absolutePath}' 2>&1 || tar -xf '${archive.absolutePath}' -C '${targetDir.absolutePath}' 2>&1"
        ).redirectErrorStream(true).start()
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun markBinariesExecutable(dir: File?) {
        if (dir?.isDirectory != true) return
        dir.listFiles()?.forEach { file ->
            if (file.isFile) file.setExecutable(true, false)
        }
    }

    // ------------------------------------------------------------------
    // Comandos de setup para o usuário (botão copiar do Setup Wizard)
    // ------------------------------------------------------------------

    /**
     * Habilita allow-external-apps no Termux (permitindo a ponte RUN_COMMAND).
     * Remove linhas duplicadas (idempotente), recarrega as settings e cria um arquivo-flag
     * em /sdcard que o app usa para detectar que a ponte está pronta sem precisar testar
     * (evitando erros RUN_COMMAND repetidos no logcat no modo HYBRID).
     */
    fun termuxBridgeSetupCommand(): String =
        "mkdir -p ~/.termux " +
            "&& grep -v 'allow-external-apps' ~/.termux/termux.properties > /tmp/.tp 2>/dev/null || true; " +
            "cp /tmp/.tp ~/.termux/termux.properties 2>/dev/null || true; " +
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties " +
            "&& termux-reload-settings " +
            "&& mkdir -p /sdcard/.codex_agent_bridge " +
            "&& echo 'ready' > /sdcard/.codex_agent_bridge/.ready " +
            "&& echo 'PONTE TERMUX PRONTA'"

    /** Comando para ativar o servidor Shizuku via ADB (copiar para o usuário). */
    fun shizukuSetupCommand(): String =
        "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"

    /** Aliases usados pelo apk_analyzer_tool (Parte 3) para localizar as ferramentas. */
    fun toolPathsForTermux(): Map<String, String> = mapOf(
        "apktool" to "/data/data/com.termux/files/home/.local/bin/apktool",
        "apktool_jar" to "/data/data/com.termux/files/home/.local/bin/apktool.jar",
        "jadx" to "/data/data/com.termux/files/home/.local/bin/jadx",
        "keystore" to termuxKeystoreFile().absolutePath,
        "bridge_dir" to Constants.TERMUX_BRIDGE_DIR,
        "logs_dir" to Constants.WORKSPACE_LOGS_DIR,
        "apk_analysis_dir" to Constants.APK_ANALYSIS_DIR
    )
}
