package com.codex.agent.agent

import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Analisador de executáveis PE (.exe/.dll) do PRÓPRIO usuário.
 * Parsing real do formato PE em Kotlin + patching + geração de scripts de RE.
 */
class PeAnalyzer(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository
) {
    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'action' obrigatório."
        return try {
            when (action) {
                "pe_info", "pe_sections", "pe_imports", "pe_exports", "pe_strings",
                "pe_resources", "pe_entropy", "pe_analyze" -> analyze(action, args)
                "pe_patch_bytes" -> patchBytes(args)
                "pe_patch_string" -> patchString(args)
                "pe_rebuild" -> rebuild(args)
                else -> "Ação desconhecida em pe_analyzer: $action"
            }
        } catch (e: Exception) {
            "Erro em pe_analyzer ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: com.codex.agent.data.model.Constants.DEFAULT_WORKSPACE_ROOT

    private data class Section(
        val name: String, val virtualSize: Long, val virtualAddr: Long,
        val rawSize: Long, val rawPtr: Long, val characteristics: Long
    )

    private data class PeInfo(
        val machine: Int, val numSections: Int, val timestamp: Long,
        val characteristics: Long, val optionalMagic: Int, val entryPoint: Long,
        val imageBase: Long, val sections: List<Section>, val peOffset: Long, val optOffset: Long
    )

    private fun parsePe(raf: java.io.RandomAccessFile): PeInfo {
        raf.seek(0x3C)
        val peOffset = raf.readInt().toLong() and 0xFFFFFFFFL
        raf.seek(peOffset)
        val sig = raf.readInt()
        if (sig != 0x00004550) throw IllegalArgumentException("Assinatura PE inválida (não é .exe/.dll).")
        val machine = raf.readUnsignedShort()
        val numSections = raf.readUnsignedShort()
        val timestamp = raf.readInt().toLong() and 0xFFFFFFFFL
        raf.readInt(); raf.readInt() // PointerToSymbolTable, NumberOfSymbols
        val sizeOpt = raf.readUnsignedShort()
        val characteristics = raf.readUnsignedShort().toLong() and 0xFFFFL
        val optOffset = raf.filePointer
        val magic = raf.readUnsignedShort()
        var entryPoint = 0L
        var imageBase = 0L
        if (magic == 0x10B) { // PE32
            raf.seek(optOffset + 16); entryPoint = raf.readInt().toLong() and 0xFFFFFFFFL
            raf.seek(optOffset + 28); imageBase = raf.readInt().toLong() and 0xFFFFFFFFL
        } else if (magic == 0x20B) { // PE32+
            raf.seek(optOffset + 16); entryPoint = raf.readInt().toLong() and 0xFFFFFFFFL
            raf.seek(optOffset + 24); imageBase = raf.readLong()
        }
        raf.seek(optOffset + sizeOpt)
        val sections = mutableListOf<Section>()
        repeat(numSections) {
            val nameBytes = ByteArray(8); raf.readFully(nameBytes)
            val name = String(nameBytes).trim('\u0000', ' ')
            val virtualSize = raf.readInt().toLong() and 0xFFFFFFFFL
            val virtualAddr = raf.readInt().toLong() and 0xFFFFFFFFL
            val rawSize = raf.readInt().toLong() and 0xFFFFFFFFL
            val rawPtr = raf.readInt().toLong() and 0xFFFFFFFFL
            raf.readInt(); raf.readInt(); raf.readInt(); raf.readInt() // reloc, linenum, counts
            val chars = raf.readInt().toLong() and 0xFFFFFFFFL
            sections.add(Section(name, virtualSize, virtualAddr, rawSize, rawPtr, chars))
        }
        return PeInfo(machine, numSections, timestamp, characteristics, magic, entryPoint, imageBase, sections, peOffset, optOffset)
    }

    private suspend fun analyze(action: String, args: JsonObject): String {
        val raw = args["file"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'file' obrigatório."
        val f = File(raw)
        val file = if (f.isAbsolute) f else File(workRoot(), raw)
        if (!file.exists()) return "Erro: arquivo não encontrado: ${file.absolutePath}"

        return java.io.RandomAccessFile(file, "r").use { raf ->
            val info = parsePe(raf)
            val machine = when (info.machine) {
                0x14C -> "x86 (32-bit)"
                0x8664 -> "x64 (64-bit)"
                0x1C0 -> "ARM"
                0xAA64 -> "ARM64"
                else -> "0x${info.machine.toString(16)}"
            }
            val sb = StringBuilder()
            when (action) {
                "pe_info", "pe_analyze" -> {
                    sb.appendLine("=== PE Info: ${file.name} (${file.length() / 1024} KB) ===")
                    sb.appendLine("Arquitetura: $machine")
                    sb.appendLine("Entry point: 0x${info.entryPoint.toString(16)}")
                    sb.appendLine("Image base: 0x${info.imageBase.toString(16)}")
                    sb.appendLine("Seções: ${info.numSections}")
                    sb.appendLine(" PE header @ 0x${info.peOffset.toString(16)}")
                    val dll = (info.characteristics and 0x2000L) != 0L
                    sb.appendLine("Tipo: ${if (dll) "DLL" else "EXE"}")
                    val stripped = (info.characteristics and 0x200L) != 0L
                    sb.appendLine("Símbolos removidos (stripped): ${if (stripped) "sim" else "não"}")
                    val aslr = (info.characteristics and 0x40L) != 0L
                    sb.appendLine("ASLR/DLL moving: ${if (aslr) "sim" else "não"}")
                }
            }
            when (action) {
                "pe_sections", "pe_analyze" -> {
                    sb.appendLine()
                    sb.appendLine("=== Seções ===")
                    info.sections.forEach { s ->
                        val flags = buildString {
                            if (s.characteristics and 0x20L != 0L) append("C")
                            if (s.characteristics and 0x40L != 0L) append("I")
                            if (s.characteristics and 0x80L != 0L) append("U")
                            if (s.characteristics and 0x20000000L != 0L) append("E")
                            if (s.characteristics and 0x40000000L != 0L) append("R")
                            if (s.characteristics and 0x80000000L != 0L) append("W")
                        }
                        sb.appendLine("  ${s.name.padEnd(8)} VS=${String.format("%08X", s.virtualSize)} " +
                            "VA=${String.format("%08X", s.virtualAddr)} " +
                            "RS=${String.format("%08X", s.rawSize)} [$flags]")
                    }
                }
            }
            when (action) {
                "pe_imports", "pe_analyze" -> {
                    sb.appendLine()
                    sb.appendLine("=== DLLs importadas (strings na seção .idata) ===")
                    val idata = info.sections.firstOrNull { it.name.startsWith(".idata") }
                    if (idata != null && idata.rawSize > 0 && idata.rawSize < 50_000_000) {
                        val buf = ByteArray(minOf(idata.rawSize, 2_000_000).toInt())
                        raf.seek(idata.rawPtr); raf.read(buf)
                        val dlls = Regex("[A-Za-z0-9_-]+\\.dll", RegexOption.IGNORE_CASE)
                            .findAll(String(buf, Charsets.ISO_8859_1)).map { it.value }.distinct().take(40)
                        dlls.forEach { sb.appendLine("  • $it") }
                    } else sb.appendLine("  (seção .idata não encontrada)")
                }
            }
            when (action) {
                "pe_exports", "pe_analyze" -> {
                    sb.appendLine()
                    sb.appendLine("=== Strings de export (seção .edata) ===")
                    val edata = info.sections.firstOrNull { it.name.startsWith(".edata") }
                    if (edata != null && edata.rawSize > 0 && edata.rawSize < 50_000_000) {
                        val buf = ByteArray(minOf(edata.rawSize, 2_000_000).toInt())
                        raf.seek(edata.rawPtr); raf.read(buf)
                        val names = Regex("[A-Za-z_][A-Za-z0-9_]{3,80}")
                            .findAll(String(buf, Charsets.ISO_8859_1)).map { it.value }.distinct().take(40)
                        names.forEach { sb.appendLine("  • $it") }
                    } else sb.appendLine("  (nenhuma seção de export)")
                }
            }
            when (action) {
                "pe_strings", "pe_analyze" -> {
                    sb.appendLine()
                    sb.appendLine("=== Strings encontradas ===")
                    val text = info.sections.firstOrNull { it.name == ".text" || it.name == ".rdata" || it.name == ".rodata" }
                    if (text != null && text.rawSize > 0) {
                        val n = minOf(text.rawSize, 3_000_000).toInt()
                        val buf = ByteArray(n); raf.seek(text.rawPtr); raf.read(buf)
                        val ascii = Regex("[\\x20-\\x7E]{6,}", RegexOption.IGNORE_CASE)
                            .findAll(String(buf, Charsets.ISO_8859_1)).map { it.value }.distinct().take(50)
                        val utf16 = Regex("(?:[\\x20-\\x7E]\\x00){6,}")
                            .findAll(String(buf, Charsets.ISO_8859_1))
                            .map { it.value.replace("\u0000", "") }.distinct().take(30)
                        sb.appendLine("ASCII:"); ascii.forEach { sb.appendLine("  • ${it.take(120)}") }
                        sb.appendLine("UTF-16:"); utf16.forEach { sb.appendLine("  • ${it.take(120)}") }
                    } else sb.appendLine("  (nenhuma seção de texto)")
                }
            }
            when (action) {
                "pe_entropy", "pe_analyze" -> {
                    sb.appendLine()
                    sb.appendLine("=== Entropia por seção (0=uniforme, 8=aleatório/compactado) ===")
                    info.sections.forEach { s ->
                        if (s.rawSize <= 0) return@forEach
                        val n = minOf(s.rawSize, 1_000_000).toInt()
                        val buf = ByteArray(n); raf.seek(s.rawPtr); raf.read(buf)
                        val freq = IntArray(256); buf.forEach { freq[it.toInt() and 0xFF]++ }
                        var h = 0.0
                        freq.filter { it > 0 }.forEach { c ->
                            val p = c / n.toDouble(); h -= p * kotlin.math.log2(p)
                        }
                        sb.appendLine("  ${s.name.padEnd(8)} H=${String.format("%.2f", h)} " +
                            "${if (h > 7.2) "(compactado/ofuscado)" else if (h > 6.0) "(dados mistos)" else "(código/dados)"}")
                    }
                }
            }
            if (action == "pe_analyze") {
                sb.appendLine()
                sb.appendLine("Para executar/editar assembly, use no PC: radare2 (r2 -A arquivo.exe), ")
                sb.appendLine("objdump -d, Ghidra, x64dbg. O app Android analisa e patcha o arquivo, não o executa.")
            }
            sb.toString()
        }
    }

    /** Patch de bytes em offset específico, com backup automático. */
    private suspend fun patchBytes(args: JsonObject): String {
        val raw = args["file"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'file' obrigatório."
        val f = File(raw)
        val file = if (f.isAbsolute) f else File(workRoot(), raw)
        if (!file.exists()) return "Erro: arquivo não encontrado."
        val offsetStr = args["offset"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'offset' obrigatório."
        val offset = offsetStr.removePrefix("0x").toLongOrNull(16)
            ?: offsetStr.toLongOrNull()
            ?: return "Erro: offset inválido (use decimal ou 0xHEX)."
        val bytesHex = args["bytes"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'bytes' obrigatório."
        val bytes = bytesHex.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull(16) }
        if (bytes.isEmpty() || bytes.any { it !in 0..255 }) return "Erro: bytes inválidos."
        if (offset < 0 || offset + bytes.size > file.length()) return "Erro: offset fora do arquivo."

        val backup = File(file.parentFile, "${file.nameWithoutExtension}_bak_${System.currentTimeMillis()}.exe")
        file.copyTo(backup)
        val outName = args["output"]?.jsonPrimitive?.contentOrNull
            ?: "${file.nameWithoutExtension}_patched.exe"
        val out = File(file.parentFile, outName)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.write(bytes.map { it.toByte() }.toByteArray())
        }
        file.copyTo(out, overwrite = true)
        // restaura o original (para não destruir o arquivo do usuário)
        backup.copyTo(file, overwrite = true)
        return "✅ Patch aplicado: $offset (0x${offset.toString(16)}) ← ${bytes.joinToString(" ") { "%02X".format(it) }}\n" +
            "Arquivo modificado: ${out.absolutePath}\nOriginal preservado, backup: ${backup.absolutePath}"
    }

    /** Substitui uma string ASCII no binário (mesmo tamanho ou menor, com padding). */
    private suspend fun patchString(args: JsonObject): String {
        val raw = args["file"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'file' obrigatório."
        val f = File(raw)
        val file = if (f.isAbsolute) f else File(workRoot(), raw)
        if (!file.exists()) return "Erro: arquivo não encontrado."
        val find = args["find"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'find' obrigatório."
        val replace = args["replace"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'replace' obrigatório."
        val data = file.readBytes()
        val needle = find.toByteArray(Charsets.ISO_8859_1)
        val idx = indexOf(data, needle)
        if (idx < 0) return "Erro: string '$find' não encontrada no arquivo."
        var repl = replace.toByteArray(Charsets.ISO_8859_1)
        if (repl.size > needle.size) return "Erro: string substituta é maior que a original (use patch de bytes/offset)."
        if (repl.size < needle.size) repl = repl + ByteArray(needle.size - repl.size) // padding com zeros
        val backup = File(file.parentFile, "${file.nameWithoutExtension}_bak_${System.currentTimeMillis()}.exe")
        file.copyTo(backup)
        val outName = args["output"]?.jsonPrimitive?.contentOrNull ?: "${file.nameWithoutExtension}_patched.exe"
        val out = File(file.parentFile, outName)
        repl.copyInto(data, idx)
        out.writeBytes(data)
        return "✅ String substituída em offset $idx (0x${idx.toString(16)}): '$find' → '$replace'\n" +
            "Arquivo: ${out.absolutePath}\nOriginal preservado, backup: ${backup.absolutePath}"
    }

    /** Gera um script para ferramentas de RE de nível profissional no PC. */
    private suspend fun rebuild(args: JsonObject): String {
        val raw = args["file"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'file' obrigatório."
        val f = File(raw)
        val file = if (f.isAbsolute) f else File(workRoot(), raw)
        if (!file.exists()) return "Erro: arquivo não encontrado."
        val dir = File(workRoot(), "${com.codex.agent.data.model.Constants.APK_ANALYSIS_DIR}/pe_scripts").apply { mkdirs() }
        val safe = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val script = File(dir, "analyze_${safe}_${System.currentTimeMillis()}.sh")
        script.writeText("""
            #!/bin/sh
            # Script de RE avançada de PE — execute no PC (Linux/WSL) com as ferramentas instaladas
            # Uso: sh ${'$'}{script.name} /caminho/para/${file.name}
            ALVO="${'$'}{1:-${file.name}}"
            echo "=== radare2 ==="
            r2 -q -A -c 'iI; iS; ii; ie; izz~..; afl' "${'$'}ALVO" | head -n 200
            echo "=== objdump ==="
            objdump -x "${'$'}ALVO" | head -n 100
            echo "=== strings ==="
            strings -el "${'$'}ALVO" | head -n 50
            echo "=== file/hash ==="
            file "${'$'}ALVO"; sha256sum "${'$'}ALVO"
        """.trimIndent())
        return "✅ Script de RE profissional gerado: ${script.absolutePath}\n" +
            "Ferramentas usadas: radare2, objdump, strings, sha256sum.\n" +
            "Execute no seu PC: sh \"${script.absolutePath}\" /caminho/do/arquivo.exe\n" +
            "Para edição de assembly/interativa: Ghidra, x64dbg, radare2 (r2 -w para write mode)."
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > data.size) return -1
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}