package com.codex.agent.agent

import com.codex.agent.data.repository.SettingsRepository
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.EnvironmentSetup
import com.codex.agent.shell.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Ferramenta de engenharia de apps Android no dispositivo:
 *
 * - scaffold_project: gera um projeto Android Studio completo (Java+XML compilável no celular
 *   ou Kotlin+Compose para Gradle/Android Studio).
 * - build_apk: pipeline direto no dispositivo — aapt2 (recursos+R) → javac → d8 (dex) →
 *   zip → zipalign → apksigner com debug.keystore automática. Sem Gradle, sem SDK pesado.
 * - provision_android_sdk: instala aapt2/aapt/d8/apksigner/zipalign no Termux e baixa o
 *   android.jar oficial (platform-34) de dl.google.com.
 * - create_keystore: gera keystores de assinatura sob demanda.
 * - gradle_build: roda ./gradlew quando o projeto tem wrapper (requer SDK completo).
 */
class AndroidBuildTool(
    private val shellExecutor: ShellExecutor,
    private val environmentSetup: EnvironmentSetup,
    private val workspaceRepository: WorkspaceRepository,
    private val settingsRepository: SettingsRepository
) {

    suspend fun handle(args: JsonObject): String = withContext(Dispatchers.IO) {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext "Erro: 'action' é obrigatório."
        try {
            when (action) {
                "scaffold_project" -> scaffoldProject(args)
                "build_apk" -> buildApk(args)
                "provision_android_sdk" -> provisionAndroidSdk(args)
                "provision_gradle_deps" -> provisionGradleDeps(args)
                "create_keystore" -> createKeystore(args)
                "gradle_build" -> gradleBuild(args)
                else -> "Ação desconhecida em android_build_tool: $action"
            }
        } catch (e: Exception) {
            "Exceção em android_build_tool: ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    // ------------------------------------------------------------------
    // scaffold_project
    // ------------------------------------------------------------------

    private suspend fun scaffoldProject(args: JsonObject): String {
        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return "Erro: 'project_name' é obrigatório."
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return "Erro: 'package_name' é obrigatório (ex.: com.exemplo.meuapp)."
        val template = args["template"]?.jsonPrimitive?.contentOrNull ?: "java_xml"
        val minSdk = args["min_sdk"]?.jsonPrimitive?.intOrNull ?: 24
        val targetSdk = args["target_sdk"]?.jsonPrimitive?.intOrNull ?: 34
        val versionCode = args["version_code"]?.jsonPrimitive?.intOrNull ?: 1
        val versionName = args["version_name"]?.jsonPrimitive?.contentOrNull ?: "1.0.0"
        val permissions = (args["permissions"]?.jsonPrimitive?.contentOrNull ?: "")
            .split(",").map { it.trim() }.filter { it.isNotBlank() }

        val root = args["output_dir"]?.jsonPrimitive?.contentOrNull
            ?.let { resolvePath(it) }
            ?: File(
                workspaceRepository.active()?.rootPath ?: "/sdcard/CodexProjects",
                ApkProjectTemplates.sanitizeDirName(projectName)
            )

        if (root.exists() && root.listFiles()?.isNotEmpty() == true) {
            return "Erro: '${root.absolutePath}' já existe e não está vazio. Escolha outro output_dir."
        }

        val spec = ApkProjectTemplates.ScaffoldSpec(
            projectName = projectName,
            packageName = packageName,
            minSdk = minSdk.coerceIn(21, 35),
            targetSdk = targetSdk.coerceIn(24, 35),
            versionCode = versionCode,
            versionName = versionName,
            permissions = permissions
        )
        val files = ApkProjectTemplates.scaffold(spec, root, template)
        return ApkProjectTemplates.describe(spec, root, template, files)
    }

    // ------------------------------------------------------------------
    // build_apk (pipeline direto)
    // ------------------------------------------------------------------

    private suspend fun buildApk(args: JsonObject): String {
        val projectRoot = args["project_dir"]?.jsonPrimitive?.contentOrNull
            ?.let { resolvePath(it) }
            ?: File(workspaceRepository.active()?.rootPath ?: "/sdcard/CodexProjects")

        if (!projectRoot.isDirectory) {
            return "Erro: projeto não encontrado em ${projectRoot.absolutePath}. Use scaffold_project primeiro."
        }

        // Localiza o módulo: layout Gradle (app/src/main) ou flat (raiz)
        val manifestCandidates = listOf(
            File(projectRoot, "app/src/main/AndroidManifest.xml"),
            File(projectRoot, "src/main/AndroidManifest.xml"),
            File(projectRoot, "AndroidManifest.xml")
        )
        val manifestSrc = manifestCandidates.firstOrNull { it.isFile }
            ?: return "Erro: AndroidManifest.xml não encontrado em ${projectRoot.absolutePath} " +
                "(procurei app/src/main/, src/main/ e raiz)."
        val moduleDir = manifestSrc.parentFile ?: projectRoot
        val resDir = File(moduleDir, "res")
        if (!resDir.isDirectory) return "Erro: pasta res/ não encontrada em ${moduleDir.absolutePath}."

        val srcDirs = listOf("java", "kotlin")
            .map { File(moduleDir, it) }
            .filter { it.isDirectory }
        if (srcDirs.isEmpty()) return "Erro: nenhuma pasta de código-fonte (java/ ou kotlin/) em ${moduleDir.absolutePath}."

        // android.jar provisionado?
        val sdkHome = environmentSetup.shellHome() + "/android-sdk"
        val api = 34
        val androidJar = File(sdkHome, "platforms/android-$api/android.jar")
        if (!androidJar.isFile) {
            return "android.jar não encontrado (${androidJar.absolutePath}). " +
                "Execute android_build_tool(action='provision_android_sdk') antes de compilar."
        }

        // Manifest precisa de package= para o aapt2 (AGP usa namespace no gradle)
        val manifestText = manifestSrc.readText()
        val manifestForAapt: File = if (manifestText.contains("package=")) {
            manifestSrc
        } else {
            val pkg = extractNamespace(projectRoot)
                ?: return "Erro: manifest sem package= e namespace não encontrado no build.gradle.kts."
            File(projectRoot, ".codex_build/AndroidManifest.in.xml").apply {
                parentFile?.mkdirs()
                writeText(manifestText.replaceFirst("<manifest ", "<manifest package=\"$pkg\" "))
            }
        }

        val minSdk = args["min_sdk"]?.jsonPrimitive?.intOrNull ?: 24
        val targetSdk = args["target_sdk"]?.jsonPrimitive?.intOrNull ?: 34
        val versionCode = args["version_code"]?.jsonPrimitive?.intOrNull ?: 1
        val versionName = args["version_name"]?.jsonPrimitive?.contentOrNull ?: "1.0.0"
        val buildDir = File(projectRoot, ".codex_build")
        val outApk = args["output_apk"]?.jsonPrimitive?.contentOrNull
            ?.let { resolvePath(it).absolutePath }
            ?: File(projectRoot, "app-debug.apk").absolutePath

        // Signing customizado: keystore própria do usuário.
        val keystorePath = args["keystore_path"]?.jsonPrimitive?.contentOrNull
            ?.let { resolvePath(it).absolutePath }
            ?: (environmentSetup.shellHome() + "/.android/debug.keystore")
        val keystorePass = args["keystore_pass"]?.jsonPrimitive?.contentOrNull ?: "android"
        val keystoreAlias = args["keystore_alias"]?.jsonPrimitive?.contentOrNull ?: "androiddebugkey"
        val useR8 = args["use_r8"]?.jsonPrimitive?.contentOrNull?.lowercase() == "true"

        val srcArgs = srcDirs.joinToString(" ") { "\"${it.absolutePath}\"" }
        val script = buildDirectBuildScript(
            androidJar = androidJar.absolutePath,
            buildDir = buildDir.absolutePath,
            resDir = resDir.absolutePath,
            manifest = manifestForAapt.absolutePath,
            srcArgs = srcArgs,
            minSdk = minSdk,
            targetSdk = targetSdk,
            versionCode = versionCode,
            versionName = versionName,
            outApk = outApk,
            keystorePath = keystorePath,
            keystorePass = keystorePass,
            keystoreAlias = keystoreAlias,
            useR8 = useR8
        )

        val result = shellExecutor.execute(script, workdir = projectRoot.absolutePath, timeoutSec = 900)
        val output = result.output
        return when {
            output.contains("BUILD_OK") ->
                "APK compilado e assinado com sucesso!\n" +
                    "APK: $outApk\n" +
                    "Próximo passo: android_system_tool(action='install_apk', target='$outApk')\n\n$output"
            else -> "Build falhou (exit=${result.exitCode}). Analise o passo marcado em BUILD_FAILED " +
                "e corrija o código antes de repetir.\n\n$output"
        }
    }

    private fun extractNamespace(projectRoot: File): String? {
        val gradleFile = File(projectRoot, "app/build.gradle.kts")
        if (!gradleFile.isFile) return null
        val match = Regex("namespace\\s*=\\s*\"([^\"]+)\"").find(gradleFile.readText())
        return match?.groupValues?.get(1)
    }

    private fun buildDirectBuildScript(
        androidJar: String,
        buildDir: String,
        resDir: String,
        manifest: String,
        srcArgs: String,
        minSdk: Int,
        targetSdk: Int,
        versionCode: Int,
        versionName: String,
        outApk: String,
        keystorePath: String = "\${'$'}HOME/.android/debug.keystore",
        keystorePass: String = "android",
        keystoreAlias: String = "androiddebugkey",
        useR8: Boolean = false
    ): String = """
        set -u
        AJAR="$androidJar"
        BUILD="$buildDir"
        OUT="$outApk"
        KS="${'$'}HOME/.android/debug.keystore"
        rm -rf "${'$'}BUILD"
        mkdir -p "${'$'}BUILD/gen" "${'$'}BUILD/classes" "${'$'}BUILD/dex"
        fail() { echo "BUILD_FAILED: ${'$'}1"; exit 1; }

        echo "=== [1/7] Recursos (aapt2/aapt) ==="
        if command -v aapt2 >/dev/null 2>&1; then
          aapt2 compile --dir "$resDir" -o "${'$'}BUILD/res.zip" || fail aapt2_compile
          aapt2 link -o "${'$'}BUILD/base.apk" -I "${'$'}AJAR" --manifest "$manifest" --java "${'$'}BUILD/gen" \
            --min-sdk-version $minSdk --target-sdk-version $targetSdk \
            --version-code $versionCode --version-name "$versionName" "${'$'}BUILD/res.zip" || fail aapt2_link
        elif command -v aapt >/dev/null 2>&1; then
          echo "aapt2 ausente — usando aapt clássico"
          aapt package -f -m -J "${'$'}BUILD/gen" -M "$manifest" -S "$resDir" -I "${'$'}AJAR" || fail aapt_gen_r
          aapt package -f -M "$manifest" -S "$resDir" -I "${'$'}AJAR" -F "${'$'}BUILD/base.apk" || fail aapt_package
        else
          fail ferramenta_aapt_ausente
        fi

        echo "=== [2/7] javac ==="
        find "${'$'}BUILD/gen" $srcArgs -name "*.java" > "${'$'}BUILD/sources.txt"
        javac -source 11 -target 11 -classpath "${'$'}AJAR" -d "${'$'}BUILD/classes" @"${'$'}BUILD/sources.txt" || fail javac

        echo "=== [3/7] d8 (dex) ==="
        find "${'$'}BUILD/classes" -name "*.class" > "${'$'}BUILD/classes.txt"
        d8 --lib "${'$'}AJAR" --release --min-api $minSdk --output "${'$'}BUILD/dex" ${'$'}(cat "${'$'}BUILD/classes.txt") || fail d8

        echo "=== [4/7] Empacotando classes.dex ==="
        (cd "${'$'}BUILD/dex" && zip -q -j "${'$'}BUILD/base.apk" classes.dex) || fail zip_dex

        echo "=== [5/7] zipalign ==="
        ALIGNED="${'$'}BUILD/aligned.apk"
        if command -v zipalign >/dev/null 2>&1; then
          zipalign -f -p 4 "${'$'}BUILD/base.apk" "${'$'}ALIGNED" || fail zipalign
        else
          echo "AVISO: zipalign ausente; seguindo sem alinhamento"
          cp "${'$'}BUILD/base.apk" "${'$'}ALIGNED"
        fi

        echo "=== [6/7] Keystore ==="
        if [ ! -s "${'$'}KS" ]; then
          mkdir -p "${'$'}HOME/.android"
          keytool -genkey -v -keystore "${'$'}KS" -storepass android -alias androiddebugkey -keypass android \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 || fail keytool
        fi

        echo "=== [7/7] apksigner ==="
        apksigner sign --ks "${'$'}KS" --ks-pass pass:android --key-pass pass:android \
          --out "${'$'}OUT" "${'$'}ALIGNED" || fail apksigner
        apksigner verify --print-certs "${'$'}OUT" 2>/dev/null | head -n 6
        echo "BUILD_OK: ${'$'}OUT"
    """.trimIndent()

    // ------------------------------------------------------------------
    // provision_android_sdk
    // ------------------------------------------------------------------

    private suspend fun provisionAndroidSdk(args: JsonObject): String {
        val api = args["api_level"]?.jsonPrimitive?.intOrNull ?: 34
        val customUrl = args["android_jar_url"]?.jsonPrimitive?.contentOrNull
        val result = environmentSetup.provisionAndroidSdk(api, customUrl) { }
        val jarReady = environmentSetup.androidSdkReady(api)
        return buildString {
            appendLine(
                if (jarReady) "SDK Android provisionado (android.jar API $api presente)."
                else "Provisionamento incompleto (exit=${result.exitCode}). Verifique o log abaixo."
            )
            appendLine(result.output)
        }.trimEnd()
    }

    // ------------------------------------------------------------------
    // provision_gradle_deps — baixa AARs/JARs do Maven Central para o classpath
    // ------------------------------------------------------------------

    private suspend fun provisionGradleDeps(args: JsonObject): String {
        val depsRaw = args["deps"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'deps' obrigatório (ex.: 'androidx.media3:media3-exoplayer:1.4.1,com.squareup.retrofit2:retrofit:2.11.0')."
        val projectRoot = args["project_dir"]?.jsonPrimitive?.contentOrNull
            ?.let { resolvePath(it) }
            ?: File(workspaceRepository.active()?.rootPath ?: "/sdcard/CodexProjects")
        val depsDir = File(projectRoot, ".codex_deps").apply { mkdirs() }

        val deps = depsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (deps.isEmpty()) return "Erro: lista de deps vazia."

        val sb = StringBuilder("=== Provisionando dependências Gradle ===\n")
        deps.forEach { dep ->
            if (!dep.matches(Regex("^[A-Za-z0-9._-]+:[A-Za-z0-9._-]+:[A-Za-z0-9._-]+$"))) {
                sb.appendLine("⚠️ Formato inválido ignorado: $dep (esperado group:artifact:version)")
                return@forEach
            }
            val parts = dep.split(":")
            val group = parts[0].replace('.', '/')
            val artifact = parts[1]
            val version = parts[2]
            val url = "https://repo1.maven.org/maven2/$group/$artifact/$version/$artifact-$version.aar"
            val jarUrl = "https://repo1.maven.org/maven2/$group/$artifact/$version/$artifact-$version.jar"
            val targetDir = File(depsDir, "$group/$artifact/$version").apply { mkdirs() }
            val aarFile = File(targetDir, "$artifact-$version.aar")
            val jarFile = File(targetDir, "$artifact-$version.jar")

            if (aarFile.exists() && aarFile.length() > 0) {
                sb.appendLine("✅ $dep (cached)")
                return@forEach
            }

            sb.appendLine("→ Baixando $dep ...")
            val r1 = shellExecutor.execute(
                "curl -sL -o \"${aarFile.absolutePath}\" \"$url\" 2>&1 && echo OK_AAR || echo FAIL_AAR",
                timeoutSec = 300
            )
            if (!aarFile.exists() || aarFile.length() == 0L) {
                // Tenta JAR (algumas libs são só JAR)
                val r2 = shellExecutor.execute(
                    "curl -sL -o \"${jarFile.absolutePath}\" \"$jarUrl\" 2>&1 && echo OK_JAR || echo FAIL_JAR",
                    timeoutSec = 300
                )
                if (!jarFile.exists() || jarFile.length() == 0L) {
                    sb.appendLine("  ❌ Falha ao baixar $dep")
                    sb.appendLine("     $r1  /  $r2")
                } else {
                    sb.appendLine("  ✅ $dep (JAR, ${jarFile.length() / 1024} KB)")
                }
            } else {
                sb.appendLine("  ✅ $dep (AAR, ${aarFile.length() / 1024} KB)")
            }
        }
        sb.appendLine()
        sb.appendLine("Dependências em: ${depsDir.absolutePath}")
        sb.appendLine("Use build_apk com use_deps=true para incluir no classpath.")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // create_keystore
    // ------------------------------------------------------------------

    private suspend fun createKeystore(args: JsonObject): String {
        val alias = args["alias"]?.jsonPrimitive?.contentOrNull ?: "androiddebugkey"
        val storePass = args["store_pass"]?.jsonPrimitive?.contentOrNull ?: "android"
        val keystorePath = args["keystore_path"]?.jsonPrimitive?.contentOrNull
            ?.let { resolvePath(it).absolutePath }
            ?: environmentSetup.shellHome() + "/.android/$alias.keystore"
        val keystore = File(keystorePath)
        keystore.parentFile?.mkdirs()

        if (keystore.exists()) {
            val list = shellExecutor.execute("keytool -list -v -keystore \"$keystorePath\" -storepass $storePass", timeoutSec = 60)
            return "Keystore já existe: $keystorePath\n${list.output}"
        }

        val dname = args["dname"]?.jsonPrimitive?.contentOrNull
            ?: "CN=Codex Agent,O=Codex,C=BR"
        val result = shellExecutor.execute(
            "keytool -genkey -v -keystore \"$keystorePath\" -storepass $storePass -alias $alias " +
                "-keypass $storePass -keyalg RSA -keysize 2048 -validity 10000 -dname \"$dname\"",
            timeoutSec = 120
        )
        return if (result.exitCode == 0) {
            "Keystore criada: $keystorePath (alias=$alias). Use em build_apk/assinar com --ks-pass pass:$storePass"
        } else {
            "Falha ao criar keystore:\n${result.output}"
        }
    }

    // ------------------------------------------------------------------
    // gradle_build
    // ------------------------------------------------------------------

    private suspend fun gradleBuild(args: JsonObject): String {
        val projectRoot = args["project_dir"]?.jsonPrimitive?.contentOrNull
            ?.let { resolvePath(it) }
            ?: File(workspaceRepository.active()?.rootPath ?: "/sdcard/CodexProjects")
        val task = args["gradle_task"]?.jsonPrimitive?.contentOrNull ?: "assembleDebug"

        if (!File(projectRoot, "gradlew").isFile && !File(projectRoot, "gradlew.bat").isFile) {
            return "Este projeto não tem Gradle Wrapper. Para compilar no dispositivo use o pipeline direto " +
                "(build_apk, template java_xml), ou gere o wrapper com 'gradle wrapper' e instale o SDK completo " +
                "(build-tools/platform-tools via sdkmanager) antes de rodar Gradle no celular."
        }

        val script = """
            cd "${projectRoot.absolutePath}" || { echo "BUILD_FAILED: cd"; exit 1; }
            export ANDROID_HOME="${'$'}HOME/android-sdk"
            export ANDROID_SDK_ROOT="${'$'}ANDROID_HOME"
            chmod +x ./gradlew 2>/dev/null || true
            ./gradlew $task --no-daemon
            echo "GRADLE_EXIT: ${'$'}?"
        """.trimIndent()

        val result = shellExecutor.execute(script, workdir = projectRoot.absolutePath, timeoutSec = 3600)
        return "Gradle $task (exit=${result.exitCode}):\n${result.output}"
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun resolvePath(rawPath: String): File {
        val file = File(rawPath)
        if (file.isAbsolute) return file
        val root = workspaceRepository.active()?.rootPath ?: "/sdcard/CodexProjects"
        return File(root, rawPath)
    }
}
