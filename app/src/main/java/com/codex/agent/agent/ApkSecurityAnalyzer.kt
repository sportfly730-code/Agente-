package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.shell.ShellExecutor
import com.codex.agent.data.repository.WorkspaceRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Análise estática de segurança para APKs (foco em auditoria do próprio app).
 * Detecção de: componentes exportados sem proteção, debuggable, allowBackup,
 * cleartext traffic, secrets hardcoded, WebView inseguro, SQL injection em ContentProviders.
 */
class ApkSecurityAnalyzer(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository
) {
    data class Finding(
        val severity: String, // CRITICAL, HIGH, MEDIUM, LOW, INFO
        val category: String,
        val description: String,
        val location: String = ""
    )

    suspend fun analyze(apkPath: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val workDir = workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT
        val analysisDir = File(workDir, "${Constants.APK_ANALYSIS_DIR}/security_${System.currentTimeMillis()}")
        analysisDir.mkdirs()

        // 1. Decompile resources (manifest)
        val decompileResult = shellExecutor.execute(
            "apktool d -f -o \"${analysisDir.absolutePath}\" \"$apkPath\" 2>&1 | tail -n 20",
            timeoutSec = 300
        )
        if (decompileResult.exitCode != 0) {
            findings.add(Finding("HIGH", "Decompile", "Falha ao decompilar APK: ${decompileResult.output.take(500)}"))
            return findings
        }

        val manifest = File(analysisDir, "AndroidManifest.xml")
        if (manifest.exists()) {
            analyzeManifest(manifest, findings)
        }

        // 2. Search for hardcoded secrets in smali/decompiled code
        analyzeHardcodedSecrets(analysisDir, findings)

        // 3. Analyze WebView usage
        analyzeWebViewUsage(analysisDir, findings)

        // 4. Check for SQL injection in ContentProviders
        analyzeContentProviders(analysisDir, findings)

        // 5. Check native libraries
        analyzeNativeLibs(analysisDir, findings)

        return findings
    }

    private fun analyzeManifest(manifest: File, findings: MutableList<Finding>) {
        val content = manifest.readText()

        // Debuggable
        if (content.contains("android:debuggable=\"true\"")) {
            findings.add(Finding("HIGH", "Manifest", "android:debuggable=true — app pode ser depurado em produção", "AndroidManifest.xml"))
        }

        // AllowBackup
        if (content.contains("android:allowBackup=\"true\"")) {
            findings.add(Finding("MEDIUM", "Manifest", "android:allowBackup=true — dados podem ser extraídos via backup", "AndroidManifest.xml"))
        }

        // Cleartext traffic
        if (content.contains("android:usesCleartextTraffic=\"true\"")) {
            findings.add(Finding("HIGH", "Manifest", "android:usesCleartextTraffic=true — comunicação HTTP não criptografada permitida", "AndroidManifest.xml"))
        }

        // Exported components without permission
        val exportedRegex = Regex("""<(\w+)\s+[^>]*android:exported="true"[^>]*>""")
        val exportedMatches = exportedRegex.findAll(content)
        for (match in exportedMatches) {
            val tag = match.groupValues[1]
            val snippet = match.value
            if (!snippet.contains("android:permission=")) {
                findings.add(Finding("HIGH", "Manifest", "Componente <$tag> exportado sem permissão de proteção", "AndroidManifest.xml"))
            }
        }

        // Check for dangerous permissions
        val dangerousPerms = listOf(
            "READ_CONTACTS", "READ_SMS", "RECEIVE_SMS", "CAMERA", "RECORD_AUDIO",
            "ACCESS_FINE_LOCATION", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
            "READ_PHONE_STATE", "CALL_PHONE", "SEND_SMS"
        )
        for (perm in dangerousPerms) {
            if (content.contains("android.permission.$perm")) {
                findings.add(Finding("INFO", "Permissions", "Permissão perigosa declarada: $perm", "AndroidManifest.xml"))
            }
        }

        // Check minSdkVersion
        val minSdkMatch = Regex("""android:minSdkVersion="(\d+)"""").find(content)
        val minSdk = minSdkMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (minSdk > 0 && minSdk < 24) {
            findings.add(Finding("LOW", "Manifest", "minSdkVersion=$minSdk — versões antigas do Android têm vulnerabilidades conhecidas", "AndroidManifest.xml"))
        }
    }

    private fun analyzeHardcodedSecrets(analysisDir: File, findings: MutableList<Finding>) {
        val secretPatterns = listOf(
            Regex("""(?i)(api[_-]?key|apikey|secret|password|passwd|token|auth)\s*[:=]\s*["']([^"']{8,})["']"""),
            Regex("""(?i)AIza[0-9A-Za-z_-]{35}"""), // Google API key
            Regex("""(?i)sk-[0-9A-Za-z]{20,}"""), // OpenAI key
            Regex("""(?i)ghp_[0-9A-Za-z]{36}"""), // GitHub token
            Regex("""(?i)AKIA[0-9A-Z]{16}"""), // AWS access key
        )

        analysisDir.walkTopDown()
            .filter { it.isFile && (it.extension == "smali" || it.extension == "xml" || it.extension == "json" || it.extension == "properties") }
            .forEach { file ->
                val content = runCatching { file.readText() }.getOrDefault("")
                for (pattern in secretPatterns) {
                    val matches = pattern.findAll(content)
                    for (match in matches) {
                        val secret = match.groupValues.lastOrNull()?.take(20) ?: match.value.take(20)
                        findings.add(Finding("CRITICAL", "Secrets", "Possível secret hardcoded: ${secret}...", file.relativeTo(analysisDir).path))
                    }
                }
            }
    }

    private fun analyzeWebViewUsage(analysisDir: File, findings: MutableList<Finding>) {
        analysisDir.walkTopDown()
            .filter { it.isFile && it.extension == "smali" }
            .forEach { file ->
                val content = runCatching { file.readText() }.getOrDefault("")
                if (content.contains("setJavaScriptEnabled") && content.contains("true")) {
                    findings.add(Finding("MEDIUM", "WebView", "JavaScript habilitado em WebView — risco de XSS", file.relativeTo(analysisDir).path))
                }
                if (content.contains("addJavascriptInterface")) {
                    findings.add(Finding("HIGH", "WebView", "addJavascriptInterface usado — risco de RCE (CVE-2013-4710 em API < 17)", file.relativeTo(analysisDir).path))
                }
                if (content.contains("setAllowFileAccess") && content.contains("true")) {
                    findings.add(Finding("MEDIUM", "WebView", "File access habilitado em WebView", file.relativeTo(analysisDir).path))
                }
            }
    }

    private fun analyzeContentProviders(analysisDir: File, findings: MutableList<Finding>) {
        analysisDir.walkTopDown()
            .filter { it.isFile && it.extension == "smali" }
            .forEach { file ->
                val content = runCatching { file.readText() }.getOrDefault("")
                // Look for rawQuery with string concatenation
                if (content.contains("rawQuery") && content.contains("concat") || content.contains("+")) {
                    findings.add(Finding("MEDIUM", "SQL", "Possível SQL injection em ContentProvider (rawQuery com concatenação)", file.relativeTo(analysisDir).path))
                }
            }
    }

    private fun analyzeNativeLibs(analysisDir: File, findings: MutableList<Finding>) {
        val libDir = File(analysisDir, "lib")
        if (!libDir.exists()) return
        libDir.walkTopDown().filter { it.isFile && it.extension == "so" }.forEach { so ->
            findings.add(Finding("INFO", "Native", "Biblioteca nativa encontrada: ${so.name} — analise com radare2/ghidra", so.relativeTo(analysisDir).path))
        }
    }

    fun formatReport(findings: List<Finding>): String {
        if (findings.isEmpty()) return "✅ Nenhum problema de segurança encontrado."
        val grouped = findings.groupBy { it.severity }
        return buildString {
            appendLine("## Relatório de Segurança APK")
            appendLine("Total de achados: ${findings.size}")
            appendLine()
            for (severity in listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")) {
                val items = grouped[severity] ?: continue
                appendLine("### $severity (${items.size})")
                items.forEach { f ->
                    appendLine("- [${f.category}] ${f.description}${if (f.location.isNotBlank()) " — ${f.location}" else ""}")
                }
                appendLine()
            }
        }
    }
}