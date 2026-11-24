package com.codex.agent.shell

import java.io.File

/**
 * Guardrail real de segurança para comandos shell.
 *
 * Tokeniza o comando respeitando aspas, extrai TODOS os subcomandos
 * (separados por ; && || | newline $() backticks <(...) >(...)) e verifica cada um contra padrões perigosos.
 *
 * Correções de segurança (auditoria):
 *  - Interpreters (bash/sh/python/perl/ruby/node/php) com -c/-e/-r: analisa o argumento.
 *  - Process substitution <(...)/>(...): analisado recursivamente.
 *  - Command substitution aninhado: parser balanceado.
 *  - Mid-word quoting (r'm', r"m"): aspas removidas de qualquer posição.
 *  - truncate: usa normalizeDots (corrige bypass via ..).
 *  - hasSubstitution: analisado por span, não boolean global (evita falsos positivos).
 *  - find -delete/-exec, xargs com rm, rsync --delete: bloqueados.
 *  - Truncate prefix: separator-aware (/systematic não é mais bloqueado).
 *
 * Retorna null se seguro, ou a razão do bloqueio.
 */
object ShellSafetyGuard {

    private val DANGEROUS_COMMANDS = setOf(
        "rm", "rmdir", "unlink", "mkfs", "mkfs.ext2", "mkfs.ext3", "mkfs.ext4", "mkfs.vfat",
        "dd", "fdisk", "parted", "wipefs", "shred", "truncate"
    )

    /** Interpretadores que recebem código via -c/-e/-r (analisados recursivamente). */
    private val INTERPRETERS = setOf(
        "bash", "sh", "zsh", "dash", "ksh", "csh", "tcsh", "fish",
        "python", "python2", "python3", "python3.9", "python3.10", "python3.11",
        "perl", "ruby", "node", "nodejs", "php", "lua"
    )

    /** Wrappers que executam outro comando como argumento. */
    private val COMMAND_WRAPPERS = setOf(
        "command", "env", "builtin", "nice", "nohup", "exec", "sudo", "time",
        "bash", "sh", "zsh", "dash", "ksh", "csh", "tcsh", "fish",
        "python", "python2", "python3", "python3.9", "python3.10", "python3.11",
        "perl", "ruby", "node", "nodejs", "php", "lua"
    )

    private val DANGEROUS_TARGETS = setOf(
        "/", "/*", "/.", "/..",
        "/sdcard", "/sdcard/", "/sdcard/*",
        "/storage", "/storage/", "/storage/*",
        "/storage/emulated", "/storage/emulated/", "/storage/emulated/0", "/storage/emulated/0/",
        "/data", "/data/", "/system", "/system/", "/vendor", "/vendor/",
        "/proc", "/proc/", "/dev", "/dev/", "/dev/block", "/dev/block/"
    )

    /** Raízes de sistema exatas (bloqueia só a raiz, não subdiretórios legítimos). */
    private val SYSTEM_ROOTS = setOf("/data", "/system", "/vendor", "/proc", "/dev", "/boot")

    private val FORK_BOMB_PATTERNS = listOf(
        ":(){", ":(){:|:&};:", "bomb", "forkbomb"
    )

    private val PIPE_TO_SHELL = Regex(
        """\|\s*(sh|bash|zsh|dash|/bin/sh|/bin/bash|/system/bin/sh)\b""",
        RegexOption.IGNORE_CASE
    )

    private const val MAX_RECURSION = 8

    fun analyze(command: String): String? = analyzeInternal(command, 0)

    private fun analyzeInternal(command: String, depth: Int): String? {
        val normalized = command.trim()
        if (normalized.isBlank()) return null
        if (depth > MAX_RECURSION) return "Comando aninhado demais (possível ofuscação)."

        // 1. Fork bomb
        for (pattern in FORK_BOMB_PATTERNS) {
            if (normalized.contains(pattern, ignoreCase = true)) {
                return "Fork bomb detectado: padrão '$pattern'"
            }
        }

        // 2. Pipe para shell
        if (PIPE_TO_SHELL.containsMatchIn(normalized)) {
            return "Pipe para shell detectado (| sh/bash). Salve o script em arquivo e execute explicitamente."
        }

        // 2b. Substituição de comando: ANALISA o conteúdo internamente (recursivo, balanceado)
        val substitutionSpans = findSubstitutionSpans(normalized)
        for (span in substitutionSpans) {
            val inner = span.trim()
            if (inner.isNotBlank()) {
                val reason = analyzeInternal(inner, depth + 1)
                if (reason != null) return "Substituição de comando perigosa: $reason"
            }
        }

        // 2c. Comando destrutivo com alvo dinâmico: checa no TEXTO BRUTO, antes de
        //     extractSubcommands() remover as substituições. O alvo real só é conhecido
        //     em runtime, então bloqueamos conservadoramente
        //     (ex.: rm -rf $(echo /sdcard), rm -rf `/sdcard`).
        val rawSubcommands = normalized.split(Regex(";|&&|\\|\\||\\||\\n|\\r"))
            .map { it.trim() }.filter { it.isNotBlank() }
        for (raw in rawSubcommands) {
            if (!(raw.contains("$") || raw.contains("`") || raw.contains("<(") || raw.contains(">("))) continue
            val rawTokens = tokenize(raw)
            val rawCmd = dequote(rawTokens.firstOrNull() ?: "").substringAfterLast('/').lowercase()
            if (rawCmd in DANGEROUS_COMMANDS) {
                return "Comando destrutivo '$rawCmd' com alvo dinâmico (substituição de comando) — bloqueado."
            }
        }

        // 3. Extrai todos os subcomandos (inclui newline como separador)
        val subcommands = extractSubcommands(normalized)

        for (sub in subcommands) {
            val tokens = tokenize(sub)
            if (tokens.isEmpty()) continue

            // cmdName desquotado (remove aspas de QUALQUER posição, não só bordas)
            val cmdName = dequote(tokens.first()).substringAfterLast('/').lowercase()

            // CORREÇÃO C1: interpreters com -c/-e/-r são analisados ANTES do wrapper
            // loop e do continue. Antes este bloco ficava depois do `continue`, o que
            // permitia bypass如 `sh -c 'rm -rf /sdcard/DCIM'` e `python3 -c '...'`.
            if (cmdName in INTERPRETERS) {
                val rawArgs = tokens.drop(1)
                val flagIdx = rawArgs.indexOfFirst { it in setOf("-c", "-e", "-r", "--command", "--eval") }
                if (flagIdx >= 0 && flagIdx + 1 < rawArgs.size) {
                    val inner = dequote(rawArgs[flagIdx + 1])
                    val reason = analyzeInternal(inner, depth + 1)
                    if (reason != null) return "Comando perigoso dentro de interpreter: $reason"
                    // APIs de linguagem que deletam/executam — não são comandos shell,
                    // então analyzeInternal() não as pega. Detectamos pelos padrões.
                    val dangerousApi = listOf(
                        "rmtree", "os.remove", "os.unlink", "shutil.move", "send2trash",
                        "subprocess.call", "subprocess.run", "subprocess.Popen", "os.system",
                        "os.popen", "exec(", "eval(", "unlinkSync", "rmSync", "fs.rm",
                        "File.delete", "deleteRecursively", "child_process", "runtime.exec"
                    ).firstOrNull { inner.contains(it, ignoreCase = true) }
                    if (dangerousApi != null) {
                        return "API perigosa dentro de interpreter: $dangerousApi"
                    }
                }
            }

            var tokenIndex = 1
            var effectiveCmd = cmdName
            while (effectiveCmd in COMMAND_WRAPPERS && tokenIndex < tokens.size) {
                effectiveCmd = dequote(tokens[tokenIndex]).substringAfterLast('/').lowercase()
                tokenIndex++
            }

            if (effectiveCmd !in DANGEROUS_COMMANDS) continue
            val args = tokens.drop(tokenIndex)

            // rm/rmdir: verificar targets
            if (effectiveCmd == "rm" || effectiveCmd == "rmdir") {
                val hasRecursive = args.any { it == "-r" || it == "-rf" || it == "-fr" || it == "-R" || (it.startsWith("-") && it.contains('r')) }
                val targets = args.filter { !it.startsWith("-") }

                for (target in targets) {
                    if (target.contains("$") || target.contains("`") || target.contains("<(") || target.contains(">(")) {
                        val spans = findSubstitutionSpans(target)
                        for (span in spans) {
                            val reason = analyzeInternal(span, depth + 1)
                            if (reason != null) return "Target destrutivo ofuscado: $reason"
                        }
                    }
                    val resolved = normalizeDots(resolvePath(target))

                    val isSystemRoot = resolved in SYSTEM_ROOTS
                    val sdcardChild = resolved.removePrefix("/sdcard/")
                    val storageChild = resolved.removePrefix("/storage/emulated/0/")
                    val isTopLevelUserData =
                        (resolved.startsWith("/sdcard/") && sdcardChild.isNotEmpty() && !sdcardChild.contains("/")) ||
                        (resolved.startsWith("/storage/emulated/0/") && storageChild.isNotEmpty() && !storageChild.contains("/"))

                    if (resolved in DANGEROUS_TARGETS || resolved == "/" || isSystemRoot || isTopLevelUserData) {
                        return "Comando destrutivo bloqueado: '$effectiveCmd' visando '$target' (resolvido: '$resolved')"
                    }
                    if (resolved == ".." || resolved.startsWith("../") || resolved == ".") {
                        return "Comando destrutivo bloqueado: '$effectiveCmd' com caminho relativo '$target' (resolvido: '$resolved')"
                    }
                    if (hasRecursive && (target == "*" || target == "/*" || target.startsWith("/*"))) {
                        return "Comando destrutivo bloqueado: '$effectiveCmd -r' com wildcard na raiz"
                    }
                }
            }

            // dd: verificar of= para block devices (dest desquotado)
            if (effectiveCmd == "dd") {
                for (token in args) {
                    if (token.startsWith("of=")) {
                        val dest = dequote(token.removePrefix("of="))
                        if (dest.startsWith("/dev/") || dest == "/dev" || dest.startsWith("/dev/block")) {
                            return "Comando dd bloqueado: escrita em device '$dest'"
                        }
                    }
                }
            }

            // mkfs/fdisk/parted/wipefs: sempre bloqueado
            if (effectiveCmd in setOf("mkfs", "mkfs.ext2", "mkfs.ext3", "mkfs.ext4", "mkfs.vfat",
                    "fdisk", "parted", "wipefs", "shred")) {
                return "Comando de formatação/destruição bloqueado: '$effectiveCmd'"
            }

            // truncate: bloqueado se visando raízes de sistema (separador-aware)
            if (effectiveCmd == "truncate") {
                for (target in args.filter { !it.startsWith("-") }) {
                    val resolved = normalizeDots(resolvePath(target))
                    if (SYSTEM_ROOTS.any { resolved == it || resolved.startsWith(it + "/") } || resolved == "/") {
                        return "truncate bloqueado em caminho de sistema: '$target'"
                    }
                }
            }

            // find ... -delete / -exec rm
            if (effectiveCmd == "find") {
                val joined = args.joinToString(" ")
                if (joined.contains("-delete") || (joined.contains("-exec") && joined.contains("rm"))) {
                    return "Comando find com -delete/-exec rm bloqueado (destrutivo)."
                }
            }

            // xargs com rm
            if (effectiveCmd == "xargs") {
                val joined = args.joinToString(" ")
                if (joined.contains("rm") || joined.contains("rmdir") || joined.contains("unlink")) {
                    return "xargs com comando destrutivo bloqueado."
                }
            }

            // rsync --delete
            if (effectiveCmd == "rsync") {
                if (args.any { it == "--delete" || it.startsWith("--delete") }) {
                    return "rsync --delete bloqueado (destrutivo)."
                }
            }
        }

        return null
    }

    /** Remove aspas de QUALQUER posição (não só bordas) — cobre r'm', r"m", 'rm'. */
    private fun dequote(s: String): String =
        s.trim().replace("'", "").replace("\"", "").replace("\\", "")

    private fun findSubstitutionSpans(command: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < command.length) {
            when {
                i < command.length - 1 && (command[i] == '<' || command[i] == '>') && command[i + 1] == '(' -> {
                    val close = findMatchingParen(command, i + 2)
                    if (close >= 0) { result.add(command.substring(i + 2, close)); i = close + 1; continue }
                }
                i < command.length - 1 && command[i] == '$' && command[i + 1] == '(' -> {
                    val close = findMatchingParen(command, i + 2)
                    if (close >= 0) { result.add(command.substring(i + 2, close)); i = close + 1; continue }
                }
                command[i] == '`' -> {
                    val close = command.indexOf('`', i + 1)
                    if (close >= 0) { result.add(command.substring(i + 1, close)); i = close + 1; continue }
                }
            }
            i++
        }
        return result
    }

    private fun findMatchingParen(s: String, start: Int): Int {
        var depth = 1; var i = start
        var inSingle = false; var inDouble = false
        while (i < s.length && depth > 0) {
            val c = s[i]
            when {
                inSingle -> { if (c == '\'') inSingle = false }
                inDouble -> { if (c == '"') inDouble = false }
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '(' -> depth++
                c == ')' -> depth--
            }
            if (depth == 0) return i
            i++
        }
        return -1
    }

    private fun extractSubcommands(command: String): List<String> {
        var cleaned = command
        cleaned = removeBalancedSubstitutions(cleaned)
        cleaned = cleaned.replace(Regex("`[^`]*`"), " ")
        cleaned = cleaned.replace(Regex("<\\([^)]*\\)"), " ").replace(Regex(">\\([^)]*\\)"), " ")

        // CORREÇÃO M3: separadores (; && || | \n) dentro de aspas NÃO devem dividir.
        // Antes isso fragmentava payloads de interpretador (ex.: python3 -c "a; b")
        // e gerava falsos positivos (echo "a; rm" era bloqueado).
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < cleaned.length) {
            val c = cleaned[i]
            when {
                c == '\'' && !inDouble -> { inSingle = !inSingle; sb.append(c) }
                c == '"' && !inSingle -> { inDouble = !inDouble; sb.append(c) }
                !inSingle && !inDouble && c == '\\' && i + 1 < cleaned.length &&
                    (cleaned[i + 1] == ';' || cleaned[i + 1] == '|' || cleaned[i + 1] == '&') -> {
                    sb.append(c); sb.append(cleaned[i + 1]); i++
                }
                !inSingle && !inDouble && (c == ';' || c == '\n' || c == '\r' ||
                    (c == '|' && i + 1 < cleaned.length && cleaned[i + 1] == '|') ||
                    (c == '&' && i + 1 < cleaned.length && cleaned[i + 1] == '&')) -> {
                    if (sb.isNotBlank()) parts.add(sb.toString())
                    sb.clear()
                    if (c == '|' || c == '&') i++ // consome o segundo caractere
                }
                !inSingle && !inDouble && c == '|' -> {
                    if (sb.isNotBlank()) parts.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotBlank()) parts.add(sb.toString())
        return parts.map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun removeBalancedSubstitutions(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            when {
                i < s.length - 1 && (s[i] == '<' || s[i] == '>') && s[i + 1] == '(' -> {
                    val close = findMatchingParen(s, i + 2)
                    if (close >= 0) { sb.append(' '); i = close + 1; continue }
                }
                i < s.length - 1 && s[i] == '$' && s[i + 1] == '(' -> {
                    val close = findMatchingParen(s, i + 2)
                    if (close >= 0) { sb.append(' '); i = close + 1; continue }
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    private fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false

        for (char in command) {
            when {
                escaped -> { current.append(char); escaped = false }
                char == '\\' -> { escaped = true; current.append(char) }
                char == '\'' && !inDoubleQuote -> { inSingleQuote = !inSingleQuote; current.append(char) }
                char == '"' && !inSingleQuote -> { inDoubleQuote = !inDoubleQuote; current.append(char) }
                (char == ' ' || char == '\t' || char == '\n' || char == '\r') && !inSingleQuote && !inDoubleQuote -> {
                    if (current.isNotEmpty()) { tokens.add(current.toString()); current.clear() }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun resolvePath(path: String): String =
        path.trim().trim('"').trim('\'').replace(Regex("\\s+"), " ").trim()

    private fun normalizeDots(path: String): String {
        val absolute = path.startsWith("/")
        val leadingDots = if (!absolute) {
            var count = 0
            for (part in path.split("/")) {
                if (part == "..") count++ else if (part.isNotBlank()) break
            }
            count
        } else 0

        val parts = mutableListOf<String>()
        for (part in path.split("/")) {
            when (part) {
                "", "." -> {}
                ".." -> { if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.lastIndex) else if (!absolute) parts.add("..") }
                else -> parts.add(part)
            }
        }
        val joined = parts.joinToString("/")
        val dots = "../".repeat(leadingDots)
        return when {
            absolute -> "/$joined"
            else -> "$dots$joined".ifEmpty { "." }.ifBlank { "." }
        }
    }
}
