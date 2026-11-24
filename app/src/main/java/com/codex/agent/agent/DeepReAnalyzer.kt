package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.WorkspaceRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.log2

/**
 * DeepReAnalyzer — análise de engenharia reversa AVANÇADA (para APKs do próprio usuário).
 *
 * Ações:
 *  - anti_tamper: detecta anti-debug, root, emulator, integrity checks
 *  - vuln_chain: taint analysis cruzando arquivos (fonte → sink)
 *  - api_extract: reconstrói API/endpoints do app
 *  - obfuscation_classify: classifica técnica de ofuscação
 *  - pinning_detect: encontra implementação de certificate pinning
 *  - version_diff: compara duas versões do mesmo app
 *  - call_graph: grafo de chamadas (quem chama quem)
 *  - re_report: gera relatório profissional completo
 */
class DeepReAnalyzer(
    private val workspaceRepository: WorkspaceRepository
) {
    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' obrigatório."
        return try {
            when (action) {
                "anti_tamper" -> antiTamper(args)
                "vuln_chain" -> vulnChain(args)
                "api_extract" -> apiExtract(args)
                "obfuscation_classify" -> obfuscationClassify(args)
                "pinning_detect" -> pinningDetect(args)
                "version_diff" -> versionDiff(args)
                "call_graph" -> callGraph(args)
                "re_report" -> reReport(args)
                else -> "Ação desconhecida em deep_re: $action"
            }
        } catch (e: Exception) {
            "Erro em deep_re ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT

    private suspend fun safeDir(raw: String?): File {
        val root = File(workRoot()).canonicalFile
        val f = File(raw ?: workRoot())
        val resolved = if (f.isAbsolute) f else File(root, raw ?: "")
        val canonical = runCatching { resolved.canonicalFile }.getOrDefault(resolved)
        if (canonical.path != root.path && !canonical.path.startsWith(root.path + File.separator)) {
            throw SecurityException("Caminho fora do workspace: $raw")
        }
        return resolved
    }

    private fun allSourceFiles(dir: File, exts: Set<String> = setOf("smali", "java", "kt")): List<File> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in exts }
            .take(4000).toList()

    // ==================== 1. ANTI-TAMPERING DETECTION ====================

    private suspend fun antiTamper(args: JsonObject): String {
        val dir = safeDir(args["dir"]?.jsonPrimitive?.contentOrNull)
        if (!dir.isDirectory) return "Erro: diretório não encontrado."

        val findings = mutableListOf<String>()
        val files = allSourceFiles(dir)

        val antiDebug = mapOf(
            "isDebuggerConnected" to "Android Debug API (isDebuggerConnected)",
            "waitingForDebugger" to "waitingForDebugger",
            "TracerPid" to "TracerPid (Linux proc)",
            "ptrace" to "ptrace (native)",
            "SIGTRAP" to "SIGTRAP (native anti-debug)",
            "PTRACE_TRACEME" to "PTRACE_TRACEME (self-attach)",
            "checkDebugger" to "Custom debug check",
            "detectDebugger" to "Custom debugger detection",
            "isDebuggingEnabled" to "Debug flag check"
        )

        val rootDetection = mapOf(
            "Superuser" to "Superuser APK check",
            "busybox" to "Busybox check",
            "RootBeer" to "RootBeer library",
            "test-keys" to "test-keys build tag",
            "/system/xbin/su" to "su binary path",
            "/system/bin/su" to "su binary path",
            "isRooted" to "Custom root check",
            "checkRoot" to "Custom root check",
            "RootTools" to "RootTools library",
            "Chainfire" to "Chainfire root tool",
            "Magisk" to "Magisk detection",
            "KingRoot" to "KingRoot detection"
        )

        val emulatorDetection = mapOf(
            "goldfish" to "Android emulator (goldfish)",
            "generic" to "Generic emulator",
            "sdk_gphone" to "Google emulator",
            "emulator" to "Emulator detection",
            "BlueStacks" to "BlueStacks emulator",
            "Genymotion" to "Genymotion emulator",
            "Nox" to "Nox emulator",
            "LDPlayer" to "LDPlayer emulator",
            "Memu" to "MEmu emulator",
            "qemu" to "QEMU",
            "getSerial" to "Device serial check",
            "ro.kernel.qemu" to "QEMU kernel property",
            "ro.hardware" to "Hardware property check",
            "Build.FINGERPRINT" to "Build fingerprint check",
            "TelephonyManager" to "Telephony/IMEI check",
            "getDeviceId" to "Device ID check"
        )

        val integrityChecks = mapOf(
            "getPackageSignature" to "Package signature verification",
            "MessageDigest" to "Hash verification",
            "CRC32" to "CRC/checksum verification",
            "verify" to "Signature verification",
            "checkSignature" to "Custom signature check",
            "tamper" to "Tamper detection",
            "AppSignature" to "App signature validation",
            "dexCRC" to "DEX CRC check",
            "SHA-256" to "SHA-256 integrity check"
        )

        fun scan(patterns: Map<String, String>, category: String) {
            files.forEach { f ->
                val text = runCatching { f.readText() }.getOrDefault("")
                patterns.forEach { (token, desc) ->
                    if (text.contains(token, ignoreCase = true)) {
                        findings.add("[$category] $desc — ${f.relativeTo(dir).path}")
                    }
                }
            }
        }

        scan(antiDebug, "ANTI-DEBUG")
        scan(rootDetection, "ROOT DETECTION")
        scan(emulatorDetection, "EMULATOR DETECTION")
        scan(integrityChecks, "INTEGRITY CHECK")

        val unique = findings.distinct()

        return buildString {
            appendLine("=== Detecção de Anti-Tampering ===")
            appendLine("Arquivos analisados: ${files.size}")
            appendLine("Total de achados: ${unique.size}")
            appendLine()
            unique.groupBy { it.substringBefore("]") + "]" }.forEach { (cat, items) ->
                appendLine("### $cat (${items.size})")
                items.take(20).forEach { appendLine("  • ${it.substringAfter("] ").take(120)}") }
                appendLine()
            }
            if (unique.isEmpty()) appendLine("✅ Nenhum padrão de anti-tampering detectado.")
            else appendLine("💡 Para bypass em testes autorizados: use frida_tool com hooks específicos.")
        }
    }

    // ==================== 2. VULNERABILITY CHAIN (cross-file taint) ====================

    private suspend fun vulnChain(args: JsonObject): String {
        val dir = safeDir(args["dir"]?.jsonPrimitive?.contentOrNull)
        if (!dir.isDirectory) return "Erro: diretório não encontrado."

        val sources = listOf(
            "getIntent", "getExtras", "getStringExtra", "getBundleExtra",
            "SharedPreferences", "getSharedPreferences", "getString", "getExternalStorage",
            "getSystemService", "ContentResolver", "query(", "openFileInput",
            "System.getenv", "getArguments", "getSerializableExtra"
        )
        val sinks = listOf(
            "execSQL", "rawQuery", "loadUrl", "addJavascriptInterface", "Runtime.getRuntime",
            "ProcessBuilder", "openConnection", "HttpURLConnection", "OkHttpClient",
            "Cipher.getInstance", "SecretKeySpec", "FileOutputStream", "Log.d", "Log.e",
            "WebView", "eval(", "createTempFile", "renameTo", "delete("
        )

        val files = allSourceFiles(dir)
        val results = mutableListOf<String>()
        val sourceFiles = mutableSetOf<String>()
        val sinkFiles = mutableSetOf<String>()

        files.forEach { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            val rel = f.relativeTo(dir).path
            val hasSource = sources.any { text.contains(it, ignoreCase = true) }
            val hasSink = sinks.any { text.contains(it, ignoreCase = true) }
            if (hasSource) sourceFiles.add(rel)
            if (hasSink) sinkFiles.add(rel)
            if (hasSource && hasSink) {
                val srcs = sources.filter { text.contains(it, ignoreCase = true) }
                val snks = sinks.filter { text.contains(it, ignoreCase = true) }
                results.add("$rel — fontes: ${srcs.joinToString(",")} | sinks: ${snks.joinToString(",")}")
            }
        }

        val chains = mutableListOf<String>()
        files.forEach { f1 ->
            val t1 = runCatching { f1.readText() }.getOrDefault("")
            if (!sources.any { t1.contains(it, ignoreCase = true) }) return@forEach
            files.forEach { f2 ->
                if (f1 == f2) return@forEach
                val t2 = runCatching { f2.readText() }.getOrDefault("")
                if (!sinks.any { t2.contains(it, ignoreCase = true) }) return@forEach
                val f2Class = Regex("(?:class|interface)\\s+(\\w+)").find(t2)?.groupValues?.get(1)
                if (f2Class != null && t1.contains(f2Class)) {
                    chains.add("${f1.relativeTo(dir).path} → ${f2.relativeTo(dir).path}")
                }
            }
        }

        return buildString {
            appendLine("=== Análise de Cadeia de Vulnerabilidades (Taint) ===")
            appendLine("Arquivos com fontes de entrada: ${sourceFiles.size}")
            appendLine("Arquivos com sinks sensíveis: ${sinkFiles.size}")
            appendLine("Arquivos com AMBOS (risco direto): ${results.size}")
            appendLine("Cadeias cross-file detectadas: ${chains.size}")
            appendLine()
            if (results.isNotEmpty()) {
                appendLine("### Risco direto (fonte + sink no mesmo arquivo):")
                results.take(30).forEach { appendLine("  • $it") }
            }
            if (chains.isNotEmpty()) {
                appendLine()
                appendLine("### Cadeias cross-file:")
                chains.take(20).forEach { appendLine("  • $it") }
            }
            if (results.isEmpty() && chains.isEmpty()) appendLine("✅ Nenhuma cadeia de vulnerabilidade óbvia detectada.")
        }
    }

    // ==================== 3. API/PROTOCOL EXTRACTION ====================

    private suspend fun apiExtract(args: JsonObject): String {
        val dir = safeDir(args["dir"]?.jsonPrimitive?.contentOrNull)
        if (!dir.isDirectory) return "Erro: diretório não encontrado."

        val urls = mutableSetOf<String>()
        val httpMethods = mutableSetOf<String>()
        val headers = mutableSetOf<String>()
        val params = mutableSetOf<String>()
        val authPatterns = mutableListOf<String>()
        val endpoints = mutableListOf<String>()

        dir.walkTopDown().filter { it.isFile && it.extension in setOf("smali", "java", "kt", "xml", "json") }
            .take(4000).forEach { f ->
                val text = runCatching { f.readText() }.getOrDefault("")
                Regex("https?://[A-Za-z0-9._:/?&=%\\-+]+").findAll(text).forEach { urls.add(it.value.take(200)) }
                listOf("GET", "POST", "PUT", "DELETE", "PATCH").forEach { m ->
                    if (text.contains("\"$m\"", true) || text.contains("HttpMethod.$m", true) ||
                        text.contains("@$m", true) || text.contains("RequestMethod.$m", true)) {
                        httpMethods.add(m)
                    }
                }
                listOf("Authorization", "X-API-Key", "Bearer", "Content-Type", "Accept", "User-Agent",
                    "X-Requested-With", "Cookie", "X-CSRF", "X-Auth-Token").forEach { h ->
                    if (text.contains(h, true)) headers.add(h)
                }
                Regex("[?&](\\w+)=").findAll(text).forEach { params.add(it.groupValues[1]) }
                Regex("@(GET|POST|PUT|DELETE|PATCH)\\s*\\(\"([^\"]+)\"\\)").findAll(text).forEach {
                    endpoints.add("${it.groupValues[1]} ${it.groupValues[2]}")
                }
                if (text.contains("Bearer", true)) authPatterns.add("Bearer token")
                if (text.contains("Basic ", true)) authPatterns.add("Basic auth")
                if (text.contains("OAuth", true)) authPatterns.add("OAuth")
                if (text.contains("JWT", true) || text.contains("JsonWebToken", true)) authPatterns.add("JWT")
                if (text.contains("ApiKey", true) || text.contains("api_key", true)) authPatterns.add("API key")
            }

        return buildString {
            appendLine("=== Extração de API / Protocolo de Rede ===")
            appendLine("URLs únicas: ${urls.size}")
            urls.take(40).forEach { appendLine("  • $it") }
            appendLine()
            appendLine("Métodos HTTP: ${if (httpMethods.isEmpty()) "não identificado" else httpMethods.joinToString(", ")}")
            appendLine("Headers: ${if (headers.isEmpty()) "nenhum" else headers.joinToString(", ")}")
            appendLine("Parâmetros de query: ${if (params.isEmpty()) "nenhum" else params.take(30).joinToString(", ")}")
            appendLine("Autenticação: ${if (authPatterns.isEmpty()) "não identificada" else authPatterns.distinct().joinToString(", ")}")
            if (endpoints.isNotEmpty()) {
                appendLine()
                appendLine("Endpoints Retrofit/OkHttp (${endpoints.size}):")
                endpoints.take(40).forEach { appendLine("  • $it") }
            }
        }
    }

    // ==================== 4. OBFUSCATION CLASSIFICATION ====================

    private suspend fun obfuscationClassify(args: JsonObject): String {
        val dir = safeDir(args["dir"]?.jsonPrimitive?.contentOrNull)
        if (!dir.isDirectory) return "Erro: diretório não encontrado."

        val smaliFiles = dir.walkTopDown().filter { it.isFile && it.extension == "smali" }.take(5000).toList()
        val javaFiles = dir.walkTopDown().filter { it.isFile && it.extension in setOf("java", "kt") }.take(5000).toList()
        val totalClasses = smaliFiles.size + javaFiles.size

        val classNames = smaliFiles.map { it.nameWithoutExtension }
        val shortNames = classNames.count { it.length <= 2 }
        val singleChar = classNames.count { it.length == 1 }
        val obfuscatedRatio = if (totalClasses == 0) 0.0 else shortNames * 100.0 / totalClasses

        var reflectionCount = 0
        var nativeLoad = 0
        var stringEncrypt = 0
        var controlFlow = 0
        var dexGuard = 0

        (smaliFiles + javaFiles).take(3000).forEach { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            if (text.contains("reflect", ignoreCase = true) || text.contains("Class.forName", ignoreCase = true)) reflectionCount++
            if (text.contains("System.loadLibrary", true) || text.contains("System.load(", true)) nativeLoad++
            if (text.contains("decrypt", true) || text.contains("cipher", true) || text.contains("aes", true)) stringEncrypt++
            if (text.contains("switch(", true) && text.contains("case ", true) && text.count { it == '}' } > 20) controlFlow++
            if (text.contains("DexGuard", true) || text.contains("dexguard", true)) dexGuard++
        }

        val techniques = mutableListOf<String>()
        if (obfuscatedRatio > 40) techniques.add("ProGuard/R8 (nomes curtos: ${String.format("%.1f", obfuscatedRatio)}%)")
        if (reflectionCount > 5) techniques.add("Reflection intensiva ($reflectionCount classes)")
        if (nativeLoad > 0) techniques.add("Código nativo ($nativeLoad classes com loadLibrary)")
        if (stringEncrypt > 10) techniques.add("Possível string encryption ($stringEncrypt classes)")
        if (controlFlow > 5) techniques.add("Possível control flow flattening ($controlFlow classes)")
        if (dexGuard > 0) techniques.add("DexGuard detectado ($dexGuard classes)")
        if (techniques.isEmpty()) techniques.add("Nenhuma ofuscação óbvia detectada — pode estar não ofuscado")

        return buildString {
            appendLine("=== Classificação de Ofuscação ===")
            appendLine("Classes analisadas: $totalClasses (smali: ${smaliFiles.size}, java/kt: ${javaFiles.size})")
            appendLine("Nomes curtos (≤2): $shortNames ($singleChar de 1 char)")
            appendLine("Reflection: $reflectionCount | Native: $nativeLoad | String encrypt: $stringEncrypt")
            appendLine()
            appendLine("Técnicas detectadas:")
            techniques.forEach { appendLine("  • $it") }
            appendLine()
            appendLine("Sugestão: " + when {
                dexGuard > 0 -> "DexGuard — use deofuscação via mapeamento de strings ou Frida hooks."
                obfuscatedRatio > 60 -> "ProGuard/R8 agressivo — procure mapping.txt ou use retrace."
                reflectionCount > 10 -> "Reflection intensiva — analise os Class.forName dinâmicos."
                else -> "Análise manual ou Frida para descobrir a lógica."
            })
        }
    }

    // ==================== 5. CERTIFICATE PINNING DETECTION ====================

    private suspend fun pinningDetect(args: JsonObject): String {
        val dir = safeDir(args["dir"]?.jsonPrimitive?.contentOrNull)
        if (!dir.isDirectory) return "Erro: diretório não encontrado."

        val patterns = mapOf(
            "CertificatePinner" to "OkHttp CertificatePinner",
            "checkServerTrusted" to "TrustManager customizado",
            "onReceivedSslError" to "WebView SSL error handler",
            "TrustManagerImpl" to "TrustManagerImpl (Conscrypt)",
            "X509TrustManager" to "X509TrustManager customizado",
            "pinning" to "Referência explícita a pinning",
            "CertificatePinning" to "CertificatePinning (OkHttp3)",
            "SSLSocketFactory" to "SSLSocketFactory customizado",
            "HostnameVerifier" to "HostnameVerifier customizado",
            "verifyChain" to "verifyChain (Conscrypt)",
            "sha256/" to "Pinning por hash SHA-256",
            "sha1/" to "Pinning por hash SHA-1"
        )

        val files = allSourceFiles(dir)
        val findings = mutableListOf<String>()
        files.forEach { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            patterns.forEach { (token, desc) ->
                if (text.contains(token, ignoreCase = true)) {
                    findings.add("$desc — ${f.relativeTo(dir).path}")
                }
            }
        }

        return if (findings.isEmpty()) {
            "✅ Nenhum padrão de certificate pinning detectado."
        } else {
            buildString {
                appendLine("=== Detecção de Certificate Pinning ===")
                appendLine("Achados (${findings.size}):")
                findings.distinct().take(40).forEach { appendLine("  • $it") }
                appendLine()
                appendLine("Para bypass em testes autorizados do seu app: use frida_tool com 'ssl_pinning_bypass'.")
            }
        }
    }

    // ==================== 6. VERSION DIFF ====================

    private suspend fun versionDiff(args: JsonObject): String {
        val dirA = safeDir(args["dir_a"]?.jsonPrimitive?.contentOrNull)
        val dirB = safeDir(args["dir_b"]?.jsonPrimitive?.contentOrNull)
        if (!dirA.isDirectory || !dirB.isDirectory) return "Erro: diretórios não encontrados."

        val filesA = dirA.walkTopDown().filter { it.isFile }.map { it.relativeTo(dirA).path }.toSet()
        val filesB = dirB.walkTopDown().filter { it.isFile }.map { it.relativeTo(dirB).path }.toSet()
        val added = filesB - filesA
        val removed = filesA - filesB

        val common = filesA.intersect(filesB)
        val changed = mutableListOf<String>()
        common.forEach { rel ->
            val a = runCatching { File(dirA, rel).readText() }.getOrDefault("")
            val b = runCatching { File(dirB, rel).readText() }.getOrDefault("")
            if (a != b) changed.add(rel)
        }

        val manA = dirA.walkTopDown().filter { it.name == "AndroidManifest.xml" }.firstOrNull()?.readText().orEmpty()
        val manB = dirB.walkTopDown().filter { it.name == "AndroidManifest.xml" }.firstOrNull()?.readText().orEmpty()
        val permsA = Regex("android.permission.\\w+").findAll(manA).map { it.value }.toSet()
        val permsB = Regex("android.permission.\\w+").findAll(manB).map { it.value }.toSet()

        return buildString {
            appendLine("=== Diff de Versões ===")
            appendLine("Arquivos: A=${filesA.size}, B=${filesB.size}")
            appendLine("Adicionados em B: ${added.size}")
            added.take(20).forEach { appendLine("  + $it") }
            appendLine("Removidos em B: ${removed.size}")
            removed.take(20).forEach { appendLine("  - $it") }
            appendLine("Modificados: ${changed.size}")
            changed.take(20).forEach { appendLine("  - $it") }
            appendLine()
            appendLine("Permissões adicionadas: ${(permsB - permsA).joinToString(", ").ifBlank { "nenhuma" }}")
            appendLine("Permissões removidas: ${(permsA - permsB).joinToString(", ").ifBlank { "nenhuma" }}")
        }
    }

    // ==================== 7. CALL GRAPH ====================

    private suspend fun callGraph(args: JsonObject): String {
        val dir = safeDir(args["dir"]?.jsonPrimitive?.contentOrNull)
        if (!dir.isDirectory) return "Erro: diretório não encontrado."
        val targetClass = args["class"]?.jsonPrimitive?.contentOrNull

        val files = allSourceFiles(dir)
        val classMethods = mutableMapOf<String, MutableSet<String>>()
        val callEdges = mutableMapOf<String, MutableSet<String>>()

        files.forEach { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            val cls = Regex("(?:class|interface)\\s+(\\w+)").find(text)?.groupValues?.get(1) ?: f.nameWithoutExtension
            val methodRegex = Regex("\\.method\\s+(?:public|private|protected|static|final|\\s)*\\s*(\\w+)\\s*\\(")
            methodRegex.findAll(text).forEach { m ->
                val method = m.groupValues[1]
                classMethods.getOrPut(cls) { mutableSetOf() }.add(method)
                val fullName = "$cls.$method"
                val calls = Regex("->(\\w+)\\(").findAll(text).map { it.groupValues[1] }.distinct().toList()
                callEdges.getOrPut(fullName) { mutableSetOf() }.addAll(calls)
            }
        }

        val sb = StringBuilder("=== Grafo de Chamadas ===\n")
        sb.appendLine("Classes: ${classMethods.size}, Métodos: ${classMethods.values.sumOf { it.size }}")
        sb.appendLine()
        if (targetClass != null) {
            sb.appendLine("Filtrando por classe: $targetClass")
            val methods = classMethods[targetClass] ?: emptySet()
            methods.forEach { m ->
                val calls = callEdges["$targetClass.$m"] ?: emptySet()
                sb.appendLine("  $targetClass.$m → ${calls.joinToString(", ")}")
            }
        } else {
            classMethods.entries.take(30).forEach { (cls, methods) ->
                sb.appendLine("📦 $cls")
                methods.take(10).forEach { m ->
                    val calls = callEdges["$cls.$m"] ?: emptySet()
                    if (calls.isNotEmpty()) sb.appendLine("  └─ $m → ${calls.take(8).joinToString(", ")}")
                }
            }
        }
        return sb.toString()
    }

    // ==================== 7. RE REPORT ====================

    private suspend fun reReport(args: JsonObject): String {
        val dir = safeDir(args["dir"]?.jsonPrimitive?.contentOrNull)
        if (!dir.isDirectory) return "Erro: diretório não encontrado."
        val reportFile = File(workRoot(), "RE_REPORT_${System.currentTimeMillis()}.md")

        val antiTamper = antiTamper(args)
        val obfuscation = obfuscationClassify(args)
        val pinning = pinningDetect(args)
        val api = apiExtract(args)
        val chains = vulnChain(args)

        val report = """
            # Relatório de Engenharia Reversa
            Gerado: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}
            Alvo: ${dir.absolutePath}

            ## 1. Anti-Tampering
            $antiTamper

            ## 2. Classificação de Ofuscação
            $obfuscation

            ## 3. Certificate Pinning
            $pinning

            ## 4. Extração de API/Protocolo
            $api

            ## 5. Cadeia de Vulnerabilidades
            $chains

            ## 6. Grafo de Chamadas
            (use deep_re(action='call_graph', dir=...) para detalhes)

            ## Resumo
            - Use apk_security_audit para auditoria OWASP adicional.
            - Use frida_tool para instrumentação dinâmica.
            - Use re_toolkit para ferramentas avançadas (gdb, radare2, etc.).
        """.trimIndent()

        reportFile.writeText(report)
        return "✅ Relatório profissional gerado: ${reportFile.absolutePath}\n\n${report.take(4000)}"
    }
}
