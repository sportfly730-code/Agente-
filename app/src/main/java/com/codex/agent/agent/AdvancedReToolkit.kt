package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.log2

/**
 * Toolkit de engenharia reversa AVANÇADA (finalidade: pesquisa de segurança, malware analysis,
 * CTF, pentest autorizado e firmware que você possui).
 *
 * Todos os grupos exigem AUTORIZAÇÃO sobre o alvo. O toolkit não contém automação de
 * burla de licenças/pagamentos nem clonagem de apps de terceiros — apenas análise,
 * instrumentação e depuração de binários/artefatos que você fornece.
 *
 * Grupos de ação (parâmetro `action`):
 *  - Debugger nativo:  debug_check, debug_start_server, debug_batch, debug_stop_server
 *  - Análise estática: static_taint, static_cfg, static_obfuscation, static_crypto, static_owasp_score
 *  - Desofuscação:     deobf_rename_smali, deobf_mapping, deobf_encrypted_strings
 *  - Binário nativo:   native_symbols, native_imports, native_jni, native_disasm, native_strings, native_checksec
 *  - Firmware:         fw_identify, fw_extract, fw_entropy, fw_keys
 *  - Frida avançado:   frida_enum_classes, frida_hook_method, frida_trace_jni, frida_intercept_http, frida_dump_dex
 *  - Captura de rede:  net_check, net_start_mitm, net_stop, net_flows
 *  - Sandbox:          sandbox_prepare, sandbox_run, sandbox_report
 */
class AdvancedReToolkit(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository
) {

    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'action' é obrigatório."
        return try {
            when {
                action.startsWith("debug_") -> debugger(action, args)
                action.startsWith("static_") -> staticAnalysis(action, args)
                action.startsWith("deobf_") -> deobfuscation(action, args)
                action.startsWith("native_") -> nativeBinary(action, args)
                action.startsWith("fw_") -> firmware(action, args)
                action.startsWith("frida_") -> fridaAdvanced(action, args)
                action.startsWith("net_") -> network(action, args)
                action.startsWith("sandbox_") -> sandbox(action, args)
                else -> "Ação desconhecida em re_toolkit: $action\nUse uma ação dos grupos: debug_, static_, deobf_, native_, fw_, frida_, net_, sandbox_."
            }
        } catch (e: Exception) {
            "Erro em re_toolkit ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT

    private suspend fun pathOf(raw: String?): File {
        val root = workRoot()
        if (raw.isNullOrBlank()) return File(root)
        val f = File(raw)
        return if (f.isAbsolute) f else File(root, raw)
    }

    private suspend fun run(cmd: String, timeout: Int = 120): String {
        val r = shellExecutor.execute(cmd, timeoutSec = timeout)
        return if (r.exitCode == 0) r.output.trim().ifBlank { "OK (sem saída)" }
        else "exit=${r.exitCode}:\n${r.output.trim()}"
    }

    private suspend fun toolAvailable(name: String): Boolean =
        shellExecutor.execute("command -v $name >/dev/null 2>&1 && echo yes || echo no", timeoutSec = 15)
            .output.trim() == "yes"

    // ==================================================================
    // 1) DEBUGGER NATIVO (gdb / gdbserver / lldb)
    // ==================================================================

    private suspend fun debugger(action: String, args: JsonObject): String {
      return when (action) {
        "debug_check" -> {
            val tools = listOf("gdb", "gdbserver", "lldb", "lldb-server", "strace")
            buildString {
                appendLine("=== Debuggers / instrumentação ===")
                tools.forEach { appendLine("${if (toolAvailable(it)) "✅" else "❌"} $it") }
                appendLine()
                appendLine("Instale no Termux: pkg install gdb strace  (lldb nem sempre disponível).")
                appendLine("Uso autorizado: depure apenas binários/apps que você possui ou tem permissão.")
            }
        }
        "debug_start_server" -> {
            val pid = args["pid"]?.jsonPrimitive?.contentOrNull
                ?: return "Erro: 'pid' é obrigatório para debug_start_server."
            val port = args["port"]?.jsonPrimitive?.contentOrNull ?: "23946"
            if (!toolAvailable("gdbserver")) return "gdbserver não instalado. Use action='debug_check' e instale com o tool_hub/install_dependency."
            val work = workRoot()
            val log = File(work, "${Constants.WORKSPACE_LOGS_DIR}/gdbserver_$port.log")
            log.parentFile?.mkdirs()
            shellExecutor.execute(
                "nohup gdbserver :$port --attach $pid > \"${log.absolutePath}\" 2>&1 & echo started",
                timeoutSec = 20
            )
            "gdbserver iniciado na porta $port (PID alvo $pid). Log: ${log.absolutePath}\nConecte: target remote :$port"
        }
        "debug_batch" -> {
            if (!toolAvailable("gdb")) return "gdb não instalado. Instale com pkg install gdb."
            val binary = args["binary"]?.jsonPrimitive?.contentOrNull
                ?: return "Erro: 'binary' é obrigatório (caminho do executável com símbolos)."
            val port = args["port"]?.jsonPrimitive?.contentOrNull ?: "23946"
            val commands = args["commands"]?.jsonPrimitive?.contentOrNull
                ?: "info registers; bt; info breakpoints"
            val exArgs = commands.split(";").joinToString(" ") { " -ex \"${it.trim()}\"" }
            run("gdb -q -batch -ex \"target remote :$port\"$exArgs \"$binary\"", 120)
        }
        "debug_stop_server" -> {
            val r = shellExecutor.execute("pkill -f 'gdbserver' >/dev/null 2>&1; echo stopped", timeoutSec = 20)
            "gdbserver encerrado: ${r.output.trim()}"
        }
        else -> "Ação de debugger desconhecida: $action"
      }
    }

    // ==================================================================
    // 2) ANÁLISE ESTÁTICA PROFUNDA (Kotlin puro sobre código descompilado)
    // ==================================================================

    private suspend fun staticAnalysis(action: String, args: JsonObject): String {
        val dir = pathOf(args["dir"]?.jsonPrimitive?.contentOrNull)
        if (!dir.isDirectory) return "Erro: diretório não encontrado: ${dir.absolutePath}. Decompile antes (apk_analyzer_tool)."

        return when (action) {
            "static_taint" -> {
                // Fontes (entry points) e sinks (saídas sensíveis) em smali.
                val sources = listOf("getIntent", "getExtras", "getStringExtra", "getExternalStorage", "getSystemService",
                    "Environment.getExternalStorage", "getDeviceId", "getImei", "getMacAddress", "getLastKnownLocation")
                val sinks = listOf("Log.d", "Log.e", "Log.v", "System.out.println", "loadUrl", "addJavascriptInterface",
                    "openConnection", "HttpURLConnection", "OkHttpClient", "execute(", "rawQuery", "execSQL",
                    "Runtime.getRuntime", "ProcessBuilder", "Cipher.getInstance", "SecretKeySpec", " FileOutputStream")
                val hits = mutableListOf<String>()
                dir.walkTopDown().filter { it.isFile && it.extension in setOf("smali", "java", "kt") }
                    .take(4000).forEach { f ->
                        val text = f.readText()
                        val foundSrc = sources.count { text.contains(it, ignoreCase = true) }
                        val foundSink = sinks.count { text.contains(it, ignoreCase = true) }
                        if (foundSrc > 0 && foundSink > 0) {
                            hits.add("${f.relativeTo(dir).path} — fontes=$foundSrc sinks=$foundSink")
                        }
                    }
                if (hits.isEmpty()) "Nenhum fluxo fonte→sink evidente encontrado (heurística)."
                else buildString {
                    appendLine("=== Possíveis fluxos de dados (taint heurístico) ===")
                    hits.take(60).forEach { appendLine("• $it") }
                    appendLine()
                    appendLine("Revise manualmente cada arquivo: dados de entrada chegando a logs/rede/SQL/cripto.")
                }
            }
            "static_cfg" -> {
                // Grafo de fluxo de controle simples a partir de smali (blocos por labels/gotos).
                val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/cfg_${System.currentTimeMillis()}.dot")
                out.parentFile?.mkdirs()
                val sb = StringBuilder("digraph CFG {\n  rankdir=TB;\n")
                var nodeId = 0
                dir.walkTopDown().filter { it.isFile && it.extension == "smali" }.take(200).forEach { f ->
                    val cls = f.nameWithoutExtension
                    val lines = f.readLines()
                    var current = "entry_${cls}"
                    sb.appendLine("  \"$current\" [label=\"$cls: entry\"];")
                    lines.forEach { line ->
                        val t = line.trim()
                        when {
                            t.startsWith(":") -> {
                                val next = "${cls}_${t.removePrefix(":")}"
                                sb.appendLine("  \"$next\" [label=\"$t\"];")
                                sb.appendLine("  \"$current\" -> \"$next\";")
                                current = next
                            }
                            t.startsWith("if-") || t.startsWith("goto") -> {
                                val target = t.substringAfterLast(' ' , "").trim().removePrefix(":")
                                if (target.isNotBlank()) {
                                    sb.appendLine("  \"$current\" -> \"${cls}_$target\" [style=dashed];")
                                }
                            }
                        }
                        nodeId++
                    }
                }
                sb.appendLine("}")
                out.writeText(sb.toString())
                "CFG (Graphviz DOT) gerado: ${out.absolutePath}\nNós aproximados: $nodeId\nAbra com: dot -Tpng cfg.dot -o cfg.png"
            }
            "static_obfuscation" -> {
                var totalClasses = 0
                var shortNames = 0
                var highEntropy = 0
                var reflection = 0
                var nativeLoad = 0
                dir.walkTopDown().filter { it.isFile && it.extension == "smali" }.take(4000).forEach { f ->
                    totalClasses++
                    val name = f.nameWithoutExtension
                    if (name.length <= 2) shortNames++
                    val text = f.readText()
                    if (text.contains("Ljava/lang/reflect/", ignoreCase = true) ||
                        text.contains("Class.forName", ignoreCase = true)) reflection++
                    if (text.contains("System.loadLibrary", ignoreCase = true) ||
                        text.contains("Ljava/lang/System;->load", ignoreCase = true)) nativeLoad++
                    // Entropia de nomes de método (heurística de ofuscação)
                    val methodNames = Regex("->(\\w+)\\(").findAll(text).map { it.groupValues[1] }.toList()
                    if (methodNames.isNotEmpty()) {
                        val avgLen = methodNames.map { it.length }.average()
                        if (avgLen <= 3.0) highEntropy++
                    }
                }
                val pct = if (totalClasses == 0) 0.0 else shortNames * 100.0 / totalClasses
                buildString {
                    appendLine("=== Detecção de ofuscação ===")
                    appendLine("Classes analisadas: $totalClasses")
                    appendLine("Nomes de classe curtos (≤2): $shortNames (${String.format("%.1f", pct)}%)")
                    appendLine("Classes com métodos muito curtos: $highEntropy")
                    appendLine("Classes com reflexão: $reflection")
                    appendLine("Classes com carregamento nativo: $nativeLoad")
                    appendLine()
                    appendLine("Interpretação: >40% nomes curtos + reflexão + loadLibrary sugere ProGuard/R8/Packer.")
                }
            }
            "static_crypto" -> {
                val patterns: Map<String, String> = mapOf(
                    "AES" to "AES/", "DES" to "DES/", "RSA" to "RSA/", "HMAC" to "Hmac",
                    "Cipher" to "Ljavax/crypto/Cipher", "SecretKey" to "SecretKeySpec",
                    "Base64" to "Base64", "KeyStore" to "KeyStore", "CertificatePinner" to "CertificatePinner"
                )
                val files = dir.walkTopDown().filter { it.isFile && it.extension in setOf("smali", "java", "kt") }
                    .take(4000).toList()
                buildString {
                    appendLine("=== Uso de cripto / pinning (contagem de arquivos) ===")
                    for ((key, token) in patterns) {
                        var count = 0
                        for (f in files) {
                            if (runCatching { f.readText().contains(token, ignoreCase = true) }.getOrDefault(false)) count++
                        }
                        appendLine("• $key: $count")
                    }
                }
            }
            "static_owasp_score" -> {
                var score = 100
                val findings = mutableListOf<String>()
                val manifest = dir.walkTopDown().filter { it.name == "AndroidManifest.xml" }.firstOrNull()
                val man = manifest?.readText().orEmpty()
                if (man.contains("android:debuggable=\"true\"", true)) { score -= 15; findings.add("debuggable=true") }
                if (man.contains("android:allowBackup=\"true\"", true)) { score -= 10; findings.add("allowBackup=true") }
                if (man.contains("android:usesCleartextTraffic=\"true\"", true)) { score -= 15; findings.add("cleartext=true") }
                val exported = Regex("android:exported=\"true\"").findAll(man).count()
                if (exported > 0) { score -= minOf(20, exported * 5); findings.add("$exported componentes exportados") }
                val webviewJs = dir.walkTopDown().filter { it.isFile && it.extension == "smali" }.take(2000)
                    .any { it.readText().contains("setJavaScriptEnabled", true) }
                if (webviewJs) { score -= 10; findings.add("WebView com JavaScript") }
                val crypto = dir.walkTopDown().filter { it.isFile && it.extension == "smali" }.take(2000)
                    .any { it.readText().contains("DES/", true) || it.readText().contains("ECB", true) }
                if (crypto) { score -= 10; findings.add("Cripto fraca (DES/ECB)") }
                buildString {
                    appendLine("=== Score de risco OWASP Mobile (heurístico) ===")
                    appendLine("Score: $score/100 (maior = melhor)")
                    appendLine("Achados: ${if (findings.isEmpty()) "nenhum" else findings.joinToString(", ")}")
                }
            }
            else -> "Ação de análise estática desconhecida: $action"
        }
    }

    // ==================================================================
    // 3) DESOFUSCAÇÃO ASSISTIDA
    // ==================================================================

    private suspend fun deobfuscation(action: String, args: JsonObject): String {
        val dir = pathOf(args["dir"]?.jsonPrimitive?.contentOrNull)
        return when (action) {
            "deobf_encrypted_strings" -> {
                if (!dir.isDirectory) return "Erro: diretório não encontrado: ${dir.absolutePath}"
                val out = mutableListOf<String>()
                dir.walkTopDown().filter { it.isFile && it.extension == "smali" }.take(3000).forEach { f ->
                    f.readLines().forEachIndexed { idx, line ->
                        val m = Regex("const-string[^,]*, \"([A-Za-z0-9+/=_-]{24,})\"").find(line)
                        if (m != null) {
                            val s = m.groupValues[1]
                            val entropy = shannonEntropy(s.toByteArray(Charsets.UTF_8))
                            if (entropy > 4.0) out.add("${f.relativeTo(dir).path}:${idx + 1} (H=${
                                String.format("%.2f", entropy)}) ${s.take(60)}...")
                        }
                    }
                }
                if (out.isEmpty()) "Nenhuma string de alta entropia encontrada."
                else buildString {
                    appendLine("=== Strings possivelmente ofuscadas/criptografadas (alta entropia) ===")
                    out.take(50).forEach { appendLine("• $it") }
                }
            }
            "deobf_mapping" -> {
                val mapping = args["mapping"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'mapping' é obrigatório (caminho do mapping.txt do ProGuard/R8)."
                val f = pathOf(mapping)
                if (!f.exists()) return "Erro: arquivo de mapping não encontrado: ${f.absolutePath}"
                val lines = f.readLines().filter { it.contains(" -> ") }
                val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/mapping_retrace_${System.currentTimeMillis()}.json")
                out.parentFile?.mkdirs()
                out.writeText(lines.take(5000).joinToString("\n", prefix = "[\n", postfix = "\n]") { "\"$it\"" })
                "Mapping processado: ${lines.size} entradas. Saída: ${out.absolutePath}\nUse as entradas para renomear símbolos em deobf_rename_smali."
            }
            "deobf_rename_smali" -> {
                val mapping = args["mapping"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'mapping' é obrigatório."
                val target = pathOf(args["target"]?.jsonPrimitive?.contentOrNull)
                val mf = pathOf(mapping)
                if (!mf.exists() || !target.exists()) return "Erro: mapping ou alvo não encontrado."
                val renames = mf.readLines().mapNotNull { line ->
                    val parts = line.trim().split(" -> ")
                    if (parts.size != 2) return@mapNotNull null
                    val obf = parts[1].removeSuffix(":").trim()
                    // Para métodos ("void foo(args) -> a"), pega só o nome antes de '('.
                    // Para classes ("com.X -> a"), pega o último segmento.
                    val original = parts[0].substringBefore('(').trim().substringAfterLast(' ')
                    if (obf.isBlank() || original.isBlank() || obf.length > 3) null else obf to original
                }.toMap()
                var changed = 0
                target.walkTopDown().filter { it.isFile && it.extension == "smali" }.take(2000).forEach { f ->
                    var text = f.readText()
                    renames.forEach { (obf, original) ->
                        if (obf.length <= 3 && original.length > obf.length) {
                            val newText = text.replace("->$obf(", "->$original(")
                            if (newText != text) changed++
                            text = newText
                        }
                    }
                    f.writeText(text)
                }
                "Renomeação concluída: $changed arquivos modificados com base no mapping."
            }
            else -> "Ação de desofuscação desconhecida: $action"
        }
    }

    // ==================================================================
    // 4) ANÁLISE DE BINÁRIO NATIVO (.so) — radare2 / binutils
    // ==================================================================

    private suspend fun nativeBinary(action: String, args: JsonObject): String {
        val so = args["so"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'so' é obrigatório (caminho do .so)."
        val f = pathOf(so)
        if (!f.exists()) return "Erro: .so não encontrado: ${f.absolutePath}"
        val quoted = "\"${f.absolutePath}\""

        return when (action) {
            "native_symbols" -> {
                if (toolAvailable("nm")) run("nm -C $quoted 2>&1 | head -n 120")
                else if (toolAvailable("r2")) run("r2 -q -c 'is~more' $quoted 2>&1 | head -n 120")
                else "Instale nm (binutils) ou radare2: pkg install binutils radare2"
            }
            "native_imports" -> {
                if (toolAvailable("readelf")) run("readelf -Ws $quoted 2>&1 | grep -i 'UND' | head -n 120")
                else if (toolAvailable("objdump")) run("objdump -T $quoted 2>&1 | grep -i '\\*UND\\*' | head -n 120")
                else "Instale binutils: pkg install binutils"
            }
            "native_jni" -> {
                if (toolAvailable("nm")) run("nm -D $quoted 2>&1 | grep -i 'Java_' | head -n 120")
                else if (toolAvailable("strings")) run("strings $quoted 2>&1 | grep -i 'Java_' | head -n 120")
                else "Instale binutils/strings."
            }
            "native_disasm" -> {
                val func = args["function"]?.jsonPrimitive?.contentOrNull
                if (!toolAvailable("r2")) return "radare2 não instalado. Use tool_hub(action='install', tool_id='radare2')."
                if (!func.isNullOrBlank() && !func.matches(Regex("^[A-Za-z0-9_.@$]{1,120}$"))) {
                    return "Erro: nome de função inválido."
                }
                val cmd = if (func.isNullOrBlank()) "pdf" else "pdf @ sym.$func"
                run("r2 -q -A -c '$cmd' $quoted 2>&1 | head -n 200", 300)
            }
            "native_strings" -> {
                val min = (args["min_len"]?.jsonPrimitive?.contentOrNull ?: "6").toIntOrNull()
                    ?.coerceIn(1, 1000) ?: 6
                if (toolAvailable("strings")) run("strings -n $min $quoted 2>&1 | head -n 200")
                else "strings não disponível."
            }
            "native_checksec" -> {
                val sb = StringBuilder("=== Hardening do binário ===\n")
                if (toolAvailable("readelf")) {
                    val hdr = run("readelf -hW $quoted 2>&1 | grep -E 'Type|Machine|Entry'")
                    sb.appendLine(hdr)
                    val relro = run("readelf -lW $quoted 2>&1 | grep -i 'GNU_RELRO' | head -n 2")
                    sb.appendLine("RELRO: ${if (relro.contains("GNU_RELRO")) "presente ✅" else "ausente ❌"}")
                    val stack = run("readelf -lW $quoted 2>&1 | grep -i 'GNU_STACK' | head -n 2")
                    sb.appendLine("STACK: $stack")
                    val canary = run("readelf -sW $quoted 2>&1 | grep -i 'stack_chk' | head -n 2")
                    sb.appendLine("Stack canary: ${if (canary.contains("stack_chk")) "presente ✅" else "ausente/indeterminado"}")
                } else sb.appendLine("Instale binutils: pkg install binutils")
                sb.toString()
            }
            else -> "Ação de binário nativo desconhecida: $action"
        }
    }

    // ==================================================================
    // 5) FIRMWARE (binwalk-like) — apenas firmware que você possui
    // ==================================================================

    private suspend fun firmware(action: String, args: JsonObject): String {
        val image = args["image"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'image' é obrigatório (caminho da imagem de firmware)."
        val f = pathOf(image)
        if (!f.exists()) return "Erro: imagem não encontrada: ${f.absolutePath}"
        val quoted = "\"${f.absolutePath}\""

        return when (action) {
            "fw_identify" -> {
                val sb = StringBuilder("=== Identificação de firmware ===\n")
                sb.appendLine("Arquivo: ${f.name} (${f.length()} bytes)")
                if (toolAvailable("binwalk")) {
                    sb.appendLine(run("binwalk $quoted 2>&1 | head -n 100", 300))
                } else {
                    // Assinaturas mágicas em Kotlin (funciona mesmo sem binwalk).
                    // Lê apenas os primeiros 1024 bytes — nunca carrega o firmware todo.
                    val headBytes = java.io.RandomAccessFile(f, "r").use { raf ->
                        val buf = ByteArray(minOf(1024L, raf.length()).toInt())
                        raf.readFully(buf)
                        buf
                    }
                    val head = String(headBytes, Charsets.ISO_8859_1)
                    sb.appendLine("Assinaturas no início:")
                    if (head.contains("hsqs")) sb.appendLine("  ✅ squashfs")
                    if (head.contains("UBI#")) sb.appendLine("  ✅ ubifs")
                    if (head.contains("ANDROID!")) sb.appendLine("  ✅ android_boot")
                    if (headBytes.size >= 2 && headBytes[0] == 0x1f.toByte() && headBytes[1] == 0x8b.toByte()) sb.appendLine("  ✅ gzip")
                    if (head.startsWith("\u00fd7zXZ")) sb.appendLine("  ✅ xz")
                    if (head.contains("070701")) sb.appendLine("  ✅ cpio")
                    // ext4: magic 0x53EF no offset 0x438
                    if (headBytes.size > 0x439 && (headBytes[0x438].toInt() and 0xFF) == 0x53 &&
                        (headBytes[0x439].toInt() and 0xFF) == 0xEF) sb.appendLine("  ✅ ext4")
                    sb.appendLine("Instale binwalk para varredura completa: pip install binwalk")
                }
                sb.toString()
            }
            "fw_extract" -> {
                if (!toolAvailable("binwalk")) return "binwalk não instalado. Instale: pip install binwalk (ou pkg install binwalk)."
                val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/fw_extract_${System.currentTimeMillis()}")
                out.mkdirs()
                run("binwalk -e -C \"${out.absolutePath}\" $quoted 2>&1 | tail -n 60", 600)
            }
            "fw_entropy" -> {
                // Lê blocos sob demanda com RandomAccessFile — nunca carrega o firmware todo (evita OOM).
                val block = 4096
                val sb = StringBuilder("=== Mapa de entropia (Shannon, blocos de $block bytes) ===\n")
                java.io.RandomAccessFile(f, "r").use { raf ->
                    var idx = 0
                    var offset = 0L
                    while (offset < raf.length() && idx < 80) {
                        val len = minOf(block.toLong(), raf.length() - offset).toInt()
                        val buf = ByteArray(len)
                        raf.seek(offset)
                        raf.readFully(buf)
                        val h = shannonEntropy(buf)
                        val bar = "#".repeat((h * 8).toInt().coerceAtMost(40))
                        sb.appendLine("${String.format("%08x", offset)}  H=${String.format("%.2f", h)} $bar")
                        offset += block
                        idx++
                    }
                }
                sb.appendLine()
                sb.appendLine("H≈8 → comprimido/criptografado. H baixo → código/strings em claro.")
                sb.toString()
            }
            "fw_keys" -> {
                val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/fw_extract")
                val base = if (out.isDirectory) out else f.parentFile ?: File(workRoot())
                val found = mutableListOf<String>()
                base.walkTopDown().filter { it.isFile }.take(20000).forEach { file ->
                    val name = file.name.lowercase()
                    if (name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".crt") ||
                        name.contains("privkey") || name.contains("cert") || name == "build.prop" ||
                        name.contains("dhcpcd")) {
                        found.add(file.relativeTo(base).path)
                    }
                }
                if (found.isEmpty()) "Nenhum certificado/chave óbvio encontrado em ${base.absolutePath}."
                else buildString {
                    appendLine("=== Possíveis material criptográfico / config ===")
                    found.take(100).forEach { appendLine("• $it") }
                }
            }
            else -> "Ação de firmware desconhecida: $action"
        }
    }

    // ==================================================================
    // 6) FRIDA AVANÇADO (hooking, tracing, interceptação — uso autorizado)
    // ==================================================================

    private suspend fun fridaAdvanced(action: String, args: JsonObject): String {
        val target = args["package"]?.jsonPrimitive?.contentOrNull
            ?: args["pid"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: informe 'package' ou 'pid'."
        val work = workRoot()
        val scripts = File(work, ".codex/frida_scripts").apply { mkdirs() }

        val script = when (action) {
            "frida_enum_classes" -> """
                Java.perform(function(){
                    var classes = Java.enumerateLoadedClassesSync();
                    classes.slice(0, 400).forEach(function(c){
                        if (c.indexOf('${target}') >= 0 || c.indexOf('com.') === 0) console.log(c);
                    });
                    console.log('[*] total classes: ' + classes.length);
                });
            """.trimIndent()
            "frida_hook_method" -> {
                val cls = args["class"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'class' é obrigatório."
                val method = args["method"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'method' é obrigatório."
                """
                Java.perform(function(){
                    var C = Java.use('$cls');
                    C.$method.overloads.forEach(function(ov){
                        ov.implementation = function(){
                            console.log('[CALL] $cls.$method args=' + JSON.stringify(Array.prototype.slice.call(arguments)));
                            var r = ov.apply(this, arguments);
                            console.log('[RET ] ' + r);
                            return r;
                        };
                    });
                    console.log('[*] hooked $cls.$method');
                });
                """.trimIndent()
            }
            "frida_trace_jni" -> """
                Java.perform(function(){
                    ['RegisterNatives','GetStringUTFChars','NewStringUTF','FindClass','GetMethodID'].forEach(function(fn){
                        var addr = Module.findExportByName(null, fn);
                        if (addr) {
                            Interceptor.attach(addr, { onEnter: function(a){ console.log('[JNI] '+fn+' '+a[1]); }});
                        }
                    });
                    console.log('[*] JNI tracing ativo');
                });
            """.trimIndent()
            "frida_intercept_http" -> """
                Java.perform(function(){
                    try {
                        var CP = Java.use('okhttp3.OkHttpClient');
                        CP.newCall.overloads.forEach(function(ov){
                            ov.implementation = function(req){
                                try { console.log('[HTTP] ' + req.request().url().toString()); } catch(e){}
                                return ov.apply(this, arguments);
                            };
                        });
                    } catch(e){ console.log('[*] OkHttp não encontrado'); }
                    try {
                        var H = Java.use('java.net.HttpURLConnection');
                        H.getURL.implementation = function(){ var u=this.getURL(); console.log('[HTTP] '+u.toString()); return u; };
                    } catch(e){}
                    console.log('[*] interceptação HTTP ativa');
                });
            """.trimIndent()
            "frida_dump_dex" -> """
                Java.perform(function(){
                    Java.enumerateClassLoadersSync().forEach(function(loader){
                        try {
                            var f = loader.findResource('classes.dex');
                            if (f) console.log('[DEX] ' + f.getAbsolutePath ? f.getAbsolutePath() : f.toString());
                        } catch(e){}
                    });
                    console.log('[*] DEX enumeration concluída');
                });
            """.trimIndent()
            else -> return "Ação Frida avançada desconhecida: $action"
        }

        val scriptFile = File(scripts, "${action}_${System.currentTimeMillis()}.js")
        scriptFile.writeText(script)
        if (!toolAvailable("frida")) return "Frida CLI não instalado. Use tool_hub(action='install', tool_id='frida-tools'). Script salvo em: ${scriptFile.absolutePath}"
        val flag = if (target.all { it.isDigit() }) "-p $target" else "-U -n $target"
        return run("frida $flag -l \"${scriptFile.absolutePath}\" --no-pause 2>&1 | head -n 120", 90)
    }

    // ==================================================================
    // 7) CAPTURA DE REDE LOCAL (mitmproxy) — tráfego de apps autorizadas
    // ==================================================================

    private suspend fun network(action: String, args: JsonObject): String {
        val work = workRoot()
        val log = File(work, "${Constants.WORKSPACE_LOGS_DIR}/mitm_flows.txt")
        return when (action) {
            "net_check" -> {
                buildString {
                    appendLine("=== Ferramentas de captura ===")
                    listOf("mitmdump", "mitmproxy", "tcpdump", "tshark").forEach {
                        appendLine("${if (toolAvailable(it)) "✅" else "❌"} $it")
                    }
                    appendLine()
                    appendLine("Instale: pip install mitmproxy")
                }
            }
            "net_start_mitm" -> {
                if (!toolAvailable("mitmdump")) return "mitmdump não instalado. Instale: pip install mitmproxy"
                log.parentFile?.mkdirs()
                val addon = File(work, ".codex/mitm_addon.py").apply {
                    parentFile?.mkdirs()
                    writeText("""
from mitmproxy import http
def request(flow: http.HTTPFlow):
    with open("$log", "a") as f:
        f.write(flow.request.method + " " + flow.request.pretty_url + "\n")
""".trimIndent())
                }
                val port = args["port"]?.jsonPrimitive?.contentOrNull ?: "8080"
                shellExecutor.execute(
                    "nohup mitmdump -p $port -s \"${addon.absolutePath}\" > \"${work}/${Constants.WORKSPACE_LOGS_DIR}/mitm.log\" 2>&1 & echo started",
                    timeoutSec = 20
                )
                "mitmdump iniciado na porta $port. Configure o dispositivo com proxy 127.0.0.1:$port e instale o CA do mitmproxy. Log de fluxos: ${log.absolutePath}"
            }
            "net_stop" -> {
                shellExecutor.execute("pkill -f 'mitmdump' >/dev/null 2>&1; echo stopped", timeoutSec = 20)
                "mitmdump encerrado."
            }
            "net_flows" -> {
                if (!log.exists()) return "Nenhum fluxo capturado ainda."
                val lines = log.readLines().distinct()
                buildString {
                    appendLine("=== Fluxos capturados (${lines.size} únicos) ===")
                    lines.take(150).forEach { appendLine("• $it") }
                }
            }
            else -> "Ação de rede desconhecida: $action"
        }
    }

    // ==================================================================
    // 8) SANDBOX DE COMPORTAMENTO (strace) — análise de malware
    // ==================================================================

    private suspend fun sandbox(action: String, args: JsonObject): String {
        val work = workRoot()
        val sandboxDir = File(work, ".codex/sandbox").apply { mkdirs() }
        val traceLog = File(sandboxDir, "strace.log")

        return when (action) {
            "sandbox_prepare" -> {
                if (!toolAvailable("strace")) return "strace não instalado. Instale: pkg install strace"
                sandboxDir.mkdirs()
                "Sandbox preparada em ${sandboxDir.absolutePath}. strace disponível ✅"
            }
            "sandbox_run" -> {
                val command = args["command"]?.jsonPrimitive?.contentOrNull
                    ?: return "Erro: 'command' é obrigatório (comando a executar sob strace)."
                if (!toolAvailable("strace")) return "strace não instalado."
                val block = com.codex.agent.shell.ShellSafetyGuard.analyze(command)
                if (block != null) return "BLOQUEADO POR SEGURANÇA: $block"
                // Escreve o comando em um script e executa o arquivo — evita que $/backticks
                // sejam expandidos no shell externo antes do sh -c.
                val script = File(sandboxDir, "cmd_${System.currentTimeMillis()}.sh").apply {
                    writeText("#!/system/bin/sh\n" + command + "\n")
                }
                shellExecutor.execute(
                    "strace -f -tt -e trace=file,network,process,signal -o \"${traceLog.absolutePath}\" sh \"${script.absolutePath}\" 2>&1 | head -n 60",
                    workdir = sandboxDir.absolutePath, timeoutSec = 180
                ).output.trim().ifBlank { "Execução concluída. Log: ${traceLog.absolutePath}" }
            }
            "sandbox_report" -> {
                if (!traceLog.exists()) return "Nenhum trace disponível. Execute sandbox_run primeiro."
                val lines = traceLog.readLines()
                fun count(prefix: String) = lines.count { it.contains(prefix, ignoreCase = true) }
                buildString {
                    appendLine("=== Relatório de comportamento (sandbox) ===")
                    appendLine("Total de syscalls: ${lines.size}")
                    appendLine("Aberturas de arquivo: ${count("openat")} / ${count("open(")}")
                    appendLine("Conexões de rede: ${count("connect(")} / ${count("socket(")}")
                    appendLine("Criação de processos: ${count("execve")} / ${count("clone")} / ${count("fork")}")
                    appendLine("Sinais: ${count("--- SIG")}")
                    appendLine()
                    appendLine("Arquivos mais tocados:")
                    lines.mapNotNull { Regex("\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                        .take(20).forEach { appendLine("  ${it.value}x  ${it.key}") }
                }
            }
            else -> "Ação de sandbox desconhecida: $action"
        }
    }

    /** Entropia de Shannon (bits/símbolo) — usada em detecção de ofuscação e mapa de firmware. */
    private fun shannonEntropy(bytes: ByteArray): Double {
        if (bytes.isEmpty()) return 0.0
        val freq = IntArray(256)
        bytes.forEach { freq[it.toInt() and 0xFF]++ }
        val n = bytes.size.toDouble()
        return freq.filter { it > 0 }.sumOf { c ->
            val p = c / n
            -p * log2(p)
        }
    }
}
