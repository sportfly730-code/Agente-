package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * DEX Studio — engenharia de bytecode DEX/smali para APKs do PRÓPRIO usuário
 * (jogos/apps criados por ele, modding/debug autorizado do seu software).
 *
 * Ações (`action`):
 *  - dex_info        — lista classes/métodos de um APK (dexdump/jadx)
 *  - dex_strings     — extrai strings do DEX
 *  - dex_disasm      — desmonta uma classe específica para smali (baksmali)
 *  - dex_patch       — patch cirúrgico de smali com BACKUP automático + verificação
 *  - dex_hook        — gera template de hook Frida para um método do seu app
 *  - dex_diff        — compara classes/métodos entre dois APKs (versões diferentes)
 *  - dex_rebuild     — recompila diretório smali de volta em APK assinado e verifica
 *  - dex_backup_list — lista backups disponíveis
 *  - dex_restore     — restaura um backup de smali
 *
 * Segurança: todas as operações exigem que o arquivo esteja DENTRO do workspace ativo.
 * Nunca toca arquivos fora do workspace do usuário.
 */
class DexStudio(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository
) {
    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'action' obrigatório."
        return try {
            when (action) {
                "dex_info" -> dexInfo(args)
                "dex_strings" -> dexStrings(args)
                "dex_disasm" -> dexDisasm(args)
                "dex_patch" -> dexPatch(args)
                "dex_hook" -> dexHook(args)
                "dex_diff" -> dexDiff(args)
                "dex_rebuild" -> dexRebuild(args)
                "dex_backup_list" -> dexBackupList()
                "dex_restore" -> dexRestore(args)
                "game_detect_engine" -> gameDetectEngine(args)
                "game_il2cpp_info" -> gameIl2CppInfo(args)
                "game_il2cpp_dump" -> gameIl2CppDump(args)
                "game_mono_dlls" -> gameMonoDlls(args)
                "game_lua_extract" -> gameLuaExtract(args)
                "game_unreal_info" -> gameUnrealInfo(args)
                "game_smali_search" -> gameSmaliSearch(args)
                "game_multidex" -> gameMultidex(args)
                "modmenu_plan" -> modmenuPlan(args)
                "modmenu_generate" -> modmenuGenerate(args)
                "modmenu_autobuild" -> modmenuAutoBuild(args)
                "duo_analyze" -> duoAnalyze(args)
                // "hardening_report" -> hardeningReport(args)
                else -> "Ação desconhecida em dex_studio: $action"
            }
        } catch (e: Exception) {
            "Erro em dex_studio ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT

    /** Resolve path exigindo que esteja dentro do workspace (segurança). */
    private suspend fun safePath(raw: String?): File {
        val root = File(workRoot()).canonicalFile
        val f = File(raw ?: return root)
        val resolved = if (f.isAbsolute) f else File(root, raw)
        val canonical = runCatching { resolved.canonicalFile }.getOrDefault(resolved)
        if (canonical.path != root.path && !canonical.path.startsWith(root.path + File.separator)) {
            throw SecurityException("Caminho fora do workspace: ${resolved.absolutePath}")
        }
        return resolved
    }

    private suspend fun run(cmd: String, timeout: Int = 180): String {
        val r = shellExecutor.execute(cmd, timeoutSec = timeout)
        return if (r.exitCode == 0) r.output.trim().ifBlank { "OK (sem saída)" }
        else "exit=${r.exitCode}:\n${r.output.trim()}"
    }

    private suspend fun available(name: String): Boolean =
        shellExecutor.execute("command -v $name >/dev/null 2>&1 && echo yes || echo no", timeoutSec = 15)
            .output.trim() == "yes"

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Lista classes e métodos do APK via jadx/dexdump. */
    private suspend fun dexInfo(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado: ${apk.absolutePath}"
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/dex_info_${System.currentTimeMillis()}.txt")
        out.parentFile?.mkdirs()
        val cmd = when {
            available("dexdump") -> "dexdump -f ${quote(apk.absolutePath)} > ${quote(out.absolutePath)} 2>&1"
            available("jadx") -> "jadx -d ${quote(out.parentFile!!.absolutePath)} ${quote(apk.absolutePath)} > ${quote(out.absolutePath)} 2>&1"
            else -> return "dexdump/jadx não instalados. Use tool_hub(action='install', tool_id='jadx'/'smali')."
        }
        run(cmd, 300)
        val lines = runCatching { out.readLines() }.getOrDefault(emptyList())
        val classCount = lines.count { it.contains("Class descriptor") || it.contains("Class #") }
        return "Informações do DEX extraídas: ${out.absolutePath}\nLinhas: ${lines.size}, classes detectadas: $classCount\n\n${lines.take(80).joinToString("\n")}"
    }

    /** Extrai strings do DEX. */
    private suspend fun dexStrings(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado."
        val min = (args["min_len"]?.jsonPrimitive?.contentOrNull ?: "4").toIntOrNull()?.coerceIn(1, 200) ?: 4
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull?.filter { it.isLetterOrDigit() || it in "._-" }.orEmpty()
        var cmd = "unzip -p ${quote(apk.absolutePath)} 'classes*.dex' 2>/dev/null | strings -n $min"
        if (filter.isNotBlank()) cmd += " | grep -i ${quote(filter)}"
        cmd += " | head -n 200"
        return "=== Strings do DEX (min=$min, filtro='$filter') ===\n${run(cmd, 120)}"
    }

    /** Desmonta uma classe específica em smali via baksmali. */
    private suspend fun dexDisasm(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado."
        if (!available("baksmali")) return "baksmali não instalado. Use tool_hub(action='install', tool_id='smali')."
        val cls = args["class"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/smali_${System.currentTimeMillis()}")
        out.parentFile?.mkdirs()
        // Extrai o DEX do APK e roda baksmali
        val dex = File(out, "classes.dex")
        run("unzip -o -j ${quote(apk.absolutePath)} 'classes.dex' -d ${quote(out.absolutePath)} >/dev/null 2>&1")
        if (!dex.exists()) return "Erro: classes.dex não encontrado no APK (pode ser multidex — use jadx)."
        val classArg = if (cls.isNotBlank()) " -c ${quote(cls)}" else ""
        run("baksmali disassemble$classArg -o ${quote(out.absolutePath)}/smali ${quote(dex.absolutePath)} 2>&1 | head -n 60", 240)
        val smaliFiles = out.resolve("smali").walkTopDown().filter { it.extension == "smali" }.take(50).toList()
        return "Smali gerado em ${out.absolutePath}/smali (${smaliFiles.size} arquivos)\n" +
            smaliFiles.joinToString("\n") { "  • ${it.relativeTo(out)}" }
    }

    /** Patch cirúrgico de smali com backup automático e verificação de integridade. */
    private suspend fun dexPatch(args: JsonObject): String {
        val file = safePath(args["file"]?.jsonPrimitive?.contentOrNull)
        if (!file.exists()) return "Erro: arquivo smali não encontrado: ${file.absolutePath}"
        val find = args["find"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'find' obrigatório (texto a substituir)."
        val replace = args["replace"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'replace' obrigatório."
        val original = file.readText()
        if (!original.contains(find)) return "Erro: texto 'find' não encontrado no arquivo."

        // BACKUP antes de qualquer modificação
        val backupDir = File(workRoot(), ".codex/dex_backups").apply { mkdirs() }
        val backup = File(backupDir, "${file.nameWithoutExtension}_${System.currentTimeMillis()}.smali.bak")
        backup.writeText(original)

        val occurrences = original.split(find).size - 1
        val patched = original.replace(find, replace)
        file.writeText(patched)
        val verified = file.readText().contains(replace)

        return "✅ Patch aplicado em ${file.absolutePath}\n" +
            "Ocorrências substituídas: $occurrences\n" +
            "Verificação: ${if (verified) "OK" else "FALHOU — restaure o backup"}\n" +
            "Backup: ${backup.absolutePath}\n" +
            "Restaure com: dex_studio(action='dex_restore', backup='${backup.name}')"
    }

    /** Gera template de hook Frida para um método do seu app. */
    private suspend fun dexHook(args: JsonObject): String {
        val cls = args["class"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'class' obrigatório (ex.: com.meu.jogo.GameActivity)."
        val method = args["method"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'method' obrigatório."
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "log" // log | modify | trace
        val dir = File(workRoot(), ".codex/frida_scripts").apply { mkdirs() }
        val script = File(dir, "hook_${cls.substringAfterLast('.')}_${method}_${System.currentTimeMillis()}.js")
        val body = when (mode) {
            "modify" -> """
                Java.perform(function(){
                    var C = Java.use('$cls');
                    C.$method.overloads.forEach(function(ov){
                        ov.implementation = function(){
                            console.log('[HOOK] $cls.$method chamado — args: ' + JSON.stringify(Array.prototype.slice.call(arguments)));
                            var r = ov.apply(this, arguments);
                            console.log('[HOOK] retorno original: ' + r);
                            // TODO: ajuste o retorno conforme necessário para seu jogo
                            return r;
                        };
                    });
                });
            """.trimIndent()
            "trace" -> """
                Java.perform(function(){
                    var C = Java.use('$cls');
                    C.$method.overloads.forEach(function(ov){
                        ov.implementation = function(){
                            console.log('[TRACE] $cls.$method');
                            return ov.apply(this, arguments);
                        };
                    });
                });
            """.trimIndent()
            else -> """
                Java.perform(function(){
                    var C = Java.use('$cls');
                    C.$method.overloads.forEach(function(ov){
                        ov.implementation = function(){
                            console.log('[LOG] $cls.$method(' + Array.prototype.slice.call(arguments).join(', ') + ')');
                            return ov.apply(this, arguments);
                        };
                    });
                });
            """.trimIndent()
        }
        script.writeText(body)
        return "✅ Hook Frida gerado: ${script.absolutePath}\nModo: $mode\n\n${body.take(1500)}\n\n" +
            "Execute: frida -U -n <seu-app> -l \"${script.absolutePath}\" --no-pause"
    }

    /** Compara classes/métodos entre dois APKs (versões diferentes do seu jogo). */
    private suspend fun dexDiff(args: JsonObject): String {
        val a = safePath(args["apk_a"]?.jsonPrimitive?.contentOrNull)
        val b = safePath(args["apk_b"]?.jsonPrimitive?.contentOrNull)
        if (!a.exists() || !b.exists()) return "Erro: APKs não encontrados."
        if (!available("jadx")) return "jadx não instalado. Use tool_hub(action='install', tool_id='jadx')."
        val base = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/dexdiff_${System.currentTimeMillis()}")
        base.mkdirs()
        run("mkdir -p ${quote(base.absolutePath)}/a ${quote(base.absolutePath)}/b")
        run("jadx -d ${quote(base.absolutePath)}/a ${quote(a.absolutePath)} >/dev/null 2>&1", 300)
        run("jadx -d ${quote(base.absolutePath)}/b ${quote(b.absolutePath)} >/dev/null 2>&1", 300)
        val diff = run("diff -rq ${quote(base.absolutePath)}/a ${quote(base.absolutePath)}/b 2>&1 | head -n 120")
        return "=== Diff de código entre versões ===\n${if (diff == "OK (sem saída)") "Nenhuma diferença de arquivos." else diff}"
    }

    /** Recompila diretório smali de volta em APK assinado e verifica. */
    private suspend fun dexRebuild(args: JsonObject): String {
        val smaliDir = safePath(args["dir"]?.jsonPrimitive?.contentOrNull)
        if (!smaliDir.isDirectory) return "Erro: diretório smali não encontrado."
        if (!available("apktool")) return "apktool não instalado. Use tool_hub(action='install', tool_id='apktool')."
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/rebuilt_${System.currentTimeMillis()}.apk")
        out.parentFile?.mkdirs()
        val r = shellExecutor.execute(
            "apktool b ${quote(smaliDir.absolutePath)} -o ${quote(out.absolutePath)} 2>&1 | tail -n 30",
            timeoutSec = 300
        )
        if (r.exitCode != 0 || !out.exists()) return "Falha no rebuild:\n${r.output.take(1500)}"
        return "✅ APK reconstruído: ${out.absolutePath} (${out.length()} bytes)\n" +
            "Assine com apk_analyzer_tool(action='rebuild_and_sign') antes de instalar."
    }

    private suspend fun dexBackupList(): String {
        val dir = File(workRoot(), ".codex/dex_backups")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) return "Nenhum backup de smali ainda."
        return "=== Backups de smali (${files.size}) ===\n" +
            files.joinToString("\n") { "  • ${it.name} (${it.length()} bytes)" }
    }

    private suspend fun dexRestore(args: JsonObject): String {
        val name = args["backup"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'backup' obrigatório (nome do arquivo .bak)."
        if (!name.matches(Regex("^[A-Za-z0-9._-]+$"))) return "Erro: nome de backup inválido."
        val backup = File(workRoot(), ".codex/dex_backups/$name")
        if (!backup.exists()) return "Erro: backup não encontrado: $name"
        val originalName = backup.nameWithoutExtension.substringBeforeLast(".smali")
        val target = File(workRoot(), ".codex/apk_analysis/$originalName.smali")
        if (!target.exists()) {
            // Tenta encontrar o arquivo original pelo nome em TODO o workspace
            val found = File(workRoot()).walkTopDown().filter { it.name == "$originalName.smali" }.firstOrNull()
                ?: return "Erro: arquivo original '$originalName.smali' não encontrado para restauração."
            found.writeText(backup.readText())
            return "✅ Restaurado: ${found.absolutePath}"
        }
        target.writeText(backup.readText())
        return "✅ Restaurado: ${target.absolutePath}"
    }

    // ==================================================================
    // MOTORES DE GAME — detecção e ferramentas multi-engine (auto-nível)
    // ==================================================================

    /** Detecta o engine do jogo a partir da estrutura do APK (Unity, Unreal, Cocos, Godot, Lua). */
    private suspend fun gameDetectEngine(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado."
        val listing = run("unzip -l ${quote(apk.absolutePath)} 2>/dev/null | head -n 2000")
        val engines = mutableListOf<String>()
        if (listing.contains("libil2cpp.so") || listing.contains("global-metadata.dat")) engines.add("Unity IL2CPP")
        if (listing.contains("libunity.so") || listing.contains("classes.dex") && listing.contains("assets/bin/Data")) engines.add("Unity Mono")
        if (listing.contains("libUE4.so") || listing.contains("libUnreal.so") || listing.contains(".pak") || listing.contains(".utoc")) engines.add("Unreal Engine")
        if (listing.contains("libcocos2dcpp.so") || listing.contains("libcocos2d")) engines.add("Cocos2d-x")
        if (listing.contains("libgodot") || listing.contains("libgodot_android")) engines.add("Godot")
        if (listing.contains(".lua") || listing.contains("lua")) engines.add("Lua Script")
        if (listing.contains("libMono", ignoreCase = true)) engines.add("Mono/.NET")
        val abis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").filter { listing.contains(it) }
        return buildString {
            appendLine("=== Detecção de Engine ===")
            appendLine("APK: ${apk.name}")
            appendLine("Engines detectados: ${if (engines.isEmpty()) "não identificado (verifique manualmente)" else engines.joinToString(", ")}")
            appendLine("ABIs nativas: ${if (abis.isEmpty()) "nenhuma" else abis.joinToString(", ")}")
            appendLine("Tamanho: ${apk.length() / 1024} KB")
            appendLine()
            appendLine("Use: game_il2cpp_info / game_mono_dlls / game_unreal_info / game_lua_extract")
        }
    }

    /** Info de IL2CPP (Unity): metadados, versão, símbolos. Requer il2cppdumper via tool_hub. */
    private suspend fun gameIl2CppInfo(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado."
        val tmp = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/il2cpp_${System.currentTimeMillis()}").apply { mkdirs() }
        run("unzip -o -j ${quote(apk.absolutePath)} 'lib/*/libil2cpp.so' 'assets/bin/Data/Managed/Metadata/global-metadata.dat' -d ${quote(tmp.absolutePath)} >/dev/null 2>&1")
        val so = tmp.resolve("libil2cpp.so")
        val meta = tmp.resolve("global-metadata.dat")
        if (!so.exists() || !meta.exists()) return "Erro: libil2cpp.so ou global-metadata.dat não encontrados (jogo não é IL2CPP?)."
        val sb = StringBuilder("=== Unity IL2CPP ===\nlibil2cpp.so: ${so.length()} bytes\nglobal-metadata.dat: ${meta.length()} bytes\n")
        if (available("il2cppdumper")) {
            sb.appendLine("il2cppdumper disponível — use game_il2cpp_dump para gerar dump.cs / script.json.")
        } else {
            sb.appendLine("il2cppdumper não instalado. Instale: tool_hub(action='install', tool_id='il2cppdumper').")
        }
        sb.appendLine(run("strings ${quote(so.absolutePath)} | grep -iE 'il2cpp|unity [0-9]|metadata' | head -n 15"))
        sb.appendLine("Arquivos extraídos em: ${tmp.absolutePath}")
        return sb.toString()
    }

    /** Gera dump IL2CPP (dump.cs, script.json) via il2cppdumper. */
    private suspend fun gameIl2CppDump(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado."
        if (!available("il2cppdumper")) return "il2cppdumper não instalado. Use tool_hub(action='install', tool_id='il2cppdumper')."
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/il2cpp_dump_${System.currentTimeMillis()}").apply { mkdirs() }
        run("unzip -o -j ${quote(apk.absolutePath)} 'lib/*/libil2cpp.so' 'assets/bin/Data/Managed/Metadata/global-metadata.dat' -d ${quote(out.absolutePath)} >/dev/null 2>&1")
        val so = out.resolve("libil2cpp.so"); val meta = out.resolve("global-metadata.dat")
        if (!so.exists() || !meta.exists()) return "Erro: arquivos IL2CPP não extraídos."
        val r = run("cd ${quote(out.absolutePath)} && il2cppdumper ${quote(so.absolutePath)} ${quote(meta.absolutePath)} 2>&1 | tail -n 40", 300)
        val generated = out.listFiles()?.filter { it.name.contains("dump") || it.name.contains("script") || it.extension == "json" }?.map { it.name }.orEmpty()
        return "Dump IL2CPP em ${out.absolutePath}\nArquivos: ${if (generated.isEmpty()) "nenhum" else generated.joinToString(", ")}\n\n$r"
    }

    /** Lista DLLs gerenciadas (Unity Mono / .NET). */
    private suspend fun gameMonoDlls(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado."
        val dlls = run("unzip -l ${quote(apk.absolutePath)} 2>/dev/null | grep -iE '\\.dll$|Assembly-CSharp|Managed' | head -n 100")
        return "=== DLLs gerenciadas (Mono/.NET) ===\n${if (dlls == "OK (sem saída)") "Nenhuma DLL encontrada (jogo pode ser IL2CPP)." else dlls}\n\n" +
            "Dica: use jadx ou mono toolkit para analisar DLLs do seu jogo."
    }

    /** Extrai scripts Lua do APK. */
    private suspend fun gameLuaExtract(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado."
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/lua_${System.currentTimeMillis()}").apply { mkdirs() }
        run("unzip -o ${quote(apk.absolutePath)} '*.lua' 'assets/*lua*' -d ${quote(out.absolutePath)} >/dev/null 2>&1")
        val files = out.walkTopDown().filter { it.extension == "lua" }.take(80).toList()
        return if (files.isEmpty()) "Nenhum script .lua encontrado no APK." else
            "=== Scripts Lua (${files.size}) ===\n${files.joinToString("\n") { "  • ${it.relativeTo(out)} (${it.length()} bytes)" }}\nDiretório: ${out.absolutePath}"
    }

    /** Info de assets Unreal (.pak/.utoc/.ucas). */
    private suspend fun gameUnrealInfo(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado."
        val listing = run("unzip -l ${quote(apk.absolutePath)} 2>/dev/null | grep -iE '\\.pak$|\\.utoc$|\\.ucas$|libUE4|libUnreal|AssetRegistry' | head -n 80")
        return "=== Unreal Engine ===\n${if (listing == "OK (sem saída)") "Nenhum asset Unreal detectado." else listing}\n\n" +
            "Dica: use UnrealPak / repak para listar conteúdo de .pak do seu jogo."
    }

    /** Busca padrões em todo o smali descompilado (ex.: chamadas, constantes). */
    private suspend fun gameSmaliSearch(args: JsonObject): String {
        val dir = safePath(args["dir"]?.jsonPrimitive?.contentOrNull)
        if (!dir.isDirectory) return "Erro: diretório smali não encontrado."
        val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'pattern' obrigatório."
        val safePattern = pattern.filter { it.isLetterOrDigit() || it in "._-/\\\$\" " }
        val r = run("grep -rn --include='*.smali' ${quote(safePattern)} ${quote(dir.absolutePath)} 2>/dev/null | head -n 100")
        return "=== Busca em smali: '$pattern' ===\n${if (r == "OK (sem saída)") "Nenhuma ocorrência." else r}"
    }

    /** Lista e extrai todos os DEX de um APK multidex. */
    private suspend fun gameMultidex(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado."
        val listing = run("unzip -l ${quote(apk.absolutePath)} 2>/dev/null | grep -iE 'classes[0-9]*\\.dex'")
        val count = listing.lines().count { it.contains(".dex") }
        return "=== Multidex ===\nArquivos DEX: $count\n$listing"
    }

    // ==================================================================
    // OVERLAY DE DEBUG / MOD MENU — para o PRÓPRIO jogo do usuário
    // (painel de developedimento embutido, gerado a partir de métodos descobertos)
    // ==================================================================

    /** Planeja funções candidatas a toggles a partir do DEX do próprio jogo. */
    private suspend fun modmenuPlan(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado."
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull?.filter { it.isLetterOrDigit() || it in "._-" }.orEmpty()
        // Extrai strings de nomes de métodos/classe relevantes do DEX
        var cmd = "unzip -p ${quote(apk.absolutePath)} 'classes*.dex' 2>/dev/null | strings -n 5 | " +
            "grep -iE '(score|coin|life|health|ammo|speed|level|gold|money|time|jump|god|debug|toggle|set|get)'"
        if (filter.isNotBlank()) cmd += " | grep -i ${quote(filter)}"
        cmd += " | sort -u | head -n 120"
        val candidates = run(cmd, 120)
        return "=== Funções candidatas a toggle (detectadas no SEU jogo) ===\n$candidates\n\n" +
            "Use modmenu_generate com a lista de métodos (classe.método) para gerar o overlay.\n" +
            "Lembre: isto é um painel de debug para o seu próprio jogo."
    }

    /**
     * Gera um script Frida que cria um OVERLAY no processo do seu jogo com toggles
     * para os métodos informados. Cada toggle chama/hooka o método em runtime.
     * O overlay é adicionado à Activity do próprio app (não afeta outros apps).
     */
    private suspend fun modmenuGenerate(args: JsonObject): String {
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'package' obrigatório (package do SEU jogo)."
        if (!pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]{2,120}$"))) return "Erro: package inválido."
        val hooksRaw = args["hooks"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'hooks' obrigatório (JSON: [{\"label\":\"...\",\"class\":\"...\",\"method\":\"...\",\"value\":\"...\"}])."
        val hookArray = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(hooksRaw).jsonArray
        }.getOrElse { return "Erro: 'hooks' deve ser um JSON array válido: ${it.message}" }
        val hooks: List<kotlinx.serialization.json.JsonElement> = hookArray.toList()

        // Monta as entradas do menu a partir dos hooks
        val entries = StringBuilder()
        var idx = 0
        for (el in hooks) {
            val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
            val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: "Toggle $idx"
            val cls = obj["class"]?.jsonPrimitive?.contentOrNull ?: continue
            val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: continue
            val value = obj["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
            // Sanitiza para JS
            val jsLabel = label.replace("'", "\\'")
            val jsCls = cls.replace("'", "\\'")
            val jsMethod = method.replace("'", "\\'")
            val jsValue = value.replace("'", "\\'")
            entries.appendLine("        addToggle(\"$jsLabel\", function(on){\n" +
                "          Java.perform(function(){\n" +
                "            try {\n" +
                "              var C = Java.use('$jsCls');\n" +
                "              C.$jsMethod.overloads.forEach(function(ov){\n" +
                "                ov.implementation = function(){\n" +
                "                  if (on) { console.log('[MOD] $jsLabel ativo'); " +
                (if (jsValue.isNotBlank()) "return $jsValue;" else "return ov.apply(this, arguments);") +
                " }\n" +
                "                  return ov.apply(this, arguments);\n" +
                "                };\n" +
                "              });\n" +
                "            } catch(e){ console.log('[MOD] erro $jsLabel: '+e); }\n" +
                "          });\n" +
                "        });")
            idx++
        }

        val script = """
            /*
             * OVERLAY DE DEBUG — gerado pelo Codex Agent
             * Package alvo: $pkg (SEU próprio jogo, criado por você)
             * Use: frida -U -f $pkg -l <este-arquivo> --no-pause
             */
            Java.perform(function(){
              var toggles = [];
              function addToggle(label, fn){ toggles.push({label: label, on: false, fn: fn}); }

            $entries

              // Cria o overlay na Activity do próprio app
              function buildOverlay(activity){
                try {
                  var WManager = Java.use('android.view.WindowManager');
                  var LinearLayout = Java.use('android.widget.LinearLayout');
                  var Button = Java.use('android.widget.Button');
                  var Color = Java.use('android.graphics.Color');
                  var Gravity = Java.use('android.view.Gravity');

                  var panel = LinearLayout.${'$'}new(activity);
                  panel.setOrientation(1); // VERTICAL
                  var lp = WManager.LayoutParams.${'$'}new(-2, -2, 2002, 0x28, -1); // TYPE_APPLICATION_OVERLAY
                  lp.gravity.value = Gravity.TOP | Gravity.START;
                  lp.x.value = 20; lp.y.value = 120;
                  panel.setBackgroundColor(Color.argb(180, 20, 20, 30));

                  toggles.forEach(function(t){
                    var btn = Button.${'$'}new(activity);
                    btn.setText(t.label + ' : OFF');
                    btn.setTextColor(Color.WHITE);
                    btn.setOnClickListener(Java.use('android.view.View${'$'}OnClickListener').${'$'}new({
                      onClick: function(v){
                        t.on = !t.on;
                        btn.setText(t.label + ' : ' + (t.on ? 'ON' : 'OFF'));
                        t.fn(t.on);
                      }
                    }));
                    panel.addView(btn);
                  });
                  activity.getWindowManager().addView(panel, lp);
                  console.log('[MOD] Overlay adicionado com ' + toggles.length + ' toggles.');
                } catch(e){ console.log('[MOD] erro overlay: ' + e); }
              }

              var Activity = Java.use('android.app.Activity');
              Activity.onResume.implementation = function(){
                this.onResume();
                buildOverlay(this);
              };
              console.log('[MOD] ModMenu carregado para $pkg (' + toggles.length + ' toggles).');
            });
        """.trimIndent()

        val dir = File(workRoot(), ".codex/modmenu").apply { mkdirs() }
        val out = File(dir, "modmenu_${pkg.replace('.', '_')}_${System.currentTimeMillis()}.js")
        out.writeText(script)
        return "✅ Overlay de debug gerado: ${out.absolutePath}\nPackage: $pkg | Toggles: $idx\n\n" +
            "Execute no SEU jogo:\n  frida -U -f $pkg -l \"${out.absolutePath}\" --no-pause\n\n" +
            "Pré-requisito: frida-server no dispositivo (tool_hub: frida-server/frida-tools).\n" +
            "Escopo: este overlay hooks apenas o processo do seu próprio jogo."
    }

    /**
     * AUTO-BUILD: analisa TODO o código do seu jogo (via jadx/smali), descobre automaticamente
     * funções de gameplay candidatas a toggles, e gera o menu overlay com liga/desliga.
     * Correntes: analisar → extrair funções → gerar hooks → gerar overlay.
     */
    private suspend fun modmenuAutoBuild(args: JsonObject): String {
        val apk = safePath(args["apk"]?.jsonPrimitive?.contentOrNull)
        if (!apk.exists()) return "Erro: APK não encontrado: ${apk.absolutePath}"
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'package' obrigatório (package do SEU jogo)."
        if (!pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]{2,120}$"))) return "Erro: package inválido."
        val maxToggles = (args["max"]?.jsonPrimitive?.contentOrNull ?: "12").toIntOrNull()?.coerceIn(1, 30) ?: 12

        // 1) Descompila o código do jogo para smali/jadx
        val analysis = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/autobuild_${System.currentTimeMillis()}").apply { mkdirs() }
        var srcDir: File? = null
        if (available("apktool")) {
            run("apktool d -f -o ${quote(analysis.absolutePath)} ${quote(apk.absolutePath)} >/dev/null 2>&1", 300)
            val smali = File(analysis, "smali")
            if (smali.exists()) srcDir = smali
        }
        if (srcDir == null && available("jadx")) {
            run("jadx -d ${quote(analysis.absolutePath)} ${quote(apk.absolutePath)} >/dev/null 2>&1", 300)
            srcDir = analysis
        }
        if (srcDir == null) return "Erro: nem apktool nem jadx disponíveis. Instale via tool_hub."

        // 2) Varre TODO o código em busca de métodos de gameplay (padrões reais de jogos)
        val patterns = listOf(
            "getScore", "setScore", "addScore", "getCoins", "setCoins", "addCoins", "getMoney",
            "getHealth", "setHealth", "getLife", "setLife", "getLives", "addLife",
            "getAmmo", "setAmmo", "getBullets", "getSpeed", "setSpeed", "getVelocity",
            "isGodMode", "setGodMode", "getLevel", "setLevel", "addExperience", "getGold",
            "getTime", "getTimer", "getEnergy", "setEnergy", "getStamina", "jump", "doubleJump"
        )
        data class Candidate(val cls: String, val method: String, val label: String, val value: String)
        val found = mutableListOf<Candidate>()
        val methodRegex = Regex("""\.method\s+(?:public|private|protected|static|final|\s)*\s*(\w+)\s*\(""")

        srcDir.walkTopDown().filter { it.extension in setOf("smali", "java") }.take(4000).forEach { file ->
            val text = runCatching { file.readText() }.getOrDefault("")
            if (!patterns.any { text.contains(it, ignoreCase = true) }) return@forEach
            // Classe = caminho do arquivo (smali) ou package.class (java)
            val cls = if (file.extension == "smali") {
                file.relativeTo(srcDir).path.removeSuffix(".smali").replace(File.separatorChar, '.')
            } else {
                val pkgMatch = Regex("package\\s+([\\w.]+)").find(text)?.groupValues?.get(1).orEmpty()
                val name = file.nameWithoutExtension
                if (pkgMatch.isNotBlank()) "$pkgMatch.$name" else name
            }
            methodRegex.findAll(text).forEach { m ->
                val method = m.groupValues[1]
                if (patterns.any { method.contains(it, ignoreCase = true) }) {
                    val value = when {
                        method.contains("god", true) -> "true"
                        method.contains("score", true) || method.contains("coin", true) ||
                        method.contains("money", true) || method.contains("gold", true) ||
                        method.contains("ammo", true) || method.contains("level", true) -> "999999"
                        method.contains("health", true) || method.contains("life", true) ||
                        method.contains("energy", true) || method.contains("stamina", true) -> "9999"
                        else -> ""
                    }
                    if (found.none { it.cls == cls && it.method == method }) {
                        found.add(Candidate(cls, method, method, value))
                    }
                }
            }
        }

        if (found.isEmpty()) {
            // Fallback: extrai nomes de métodos do DEX e usa os primeiros relevantes
            val names = run("unzip -p ${quote(apk.absolutePath)} 'classes*.dex' 2>/dev/null | strings -n 5 | " +
                "grep -iE '(score|coin|health|life|ammo|gold|level|speed|god|energy)' | sort -u | head -n $maxToggles")
            if (names == "OK (sem saída)") return "Nenhuma função de gameplay candidata encontrada no seu jogo."
            return "Candidatos encontrados (sem classe resolvida — use modmenu_plan/manual):\n$names"
        }

        // 3) Monta o JSON de hooks e chama o gerador de overlay
        val hooksJson = StringBuilder("[")
        found.take(maxToggles).forEachIndexed { i, c ->
            if (i > 0) hooksJson.append(",")
            hooksJson.append("{\"label\":\"${c.label}\",\"class\":\"${c.cls.replace("'", "\\'")}\"," +
                "\"method\":\"${c.method}\",\"value\":\"${c.value}\"}")
        }
        hooksJson.append("]")

        val genArgs = kotlinx.serialization.json.buildJsonObject {
            put("action", kotlinx.serialization.json.JsonPrimitive("modmenu_generate"))
            put("package", kotlinx.serialization.json.JsonPrimitive(pkg))
            put("hooks", kotlinx.serialization.json.JsonPrimitive(hooksJson.toString()))
        }
        val result = modmenuGenerate(genArgs)
        return "🔍 ANÁLISE AUTOMÁTICA DO SEU JOGO CONCLUÍDA\n" +
            "Arquivos de código analisados: ${srcDir.walkTopDown().filter { it.isFile }.count()}\n" +
            "Funções candidatas descobertas: ${found.size}\n" +
            "Toggles gerados: ${minOf(maxToggles, found.size)}\n\n" +
            result + "\n\n" +
            "Atalhos: cada toggle aparece no overlay com ON/OFF para ativar/desativar em tempo real.\n" +
            "Uso legítimo: painel de debug/modificação do SEU próprio jogo (criado por você)."
    }

    /**
     * DUO ANALYZE — pega 2 APKs do workspace e analisa ambos em profundidade para descobrir
     * onde melhorar, onde corrigir, e aplicar uma correção pedida pelo usuário.
     * Cruza: manifest, DEX (classes/métodos), recursos, tamanhos, hashes.
     */
    private suspend fun duoAnalyze(args: JsonObject): String {
        val a = safePath(args["apk_a"]?.jsonPrimitive?.contentOrNull)
        val b = safePath(args["apk_b"]?.jsonPrimitive?.contentOrNull)
        if (!a.exists() || !b.exists()) return "Erro: um dos APKs não encontrado."
        val focus = args["focus"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val sb = StringBuilder("=== ANÁLISE COMPARATIVA DE 2 APKs ===\n")
        sb.appendLine("A: ${a.name} (${a.length() / 1024} KB)")
        sb.appendLine("B: ${b.name} (${b.length() / 1024} KB)")
        sb.appendLine()

        // 1) Hashes
        val hashA = run("sha256sum ${quote(a.absolutePath)} 2>/dev/null | cut -d' ' -f1")
        val hashB = run("sha256sum ${quote(b.absolutePath)} 2>/dev/null | cut -d' ' -f1")
        sb.appendLine("SHA-256 A: $hashA")
        sb.appendLine("SHA-256 B: $hashB")
        sb.appendLine("Arquivos idênticos: ${hashA == hashB}")
        sb.appendLine()

        // 2) Lista de arquivos internos (diferenças de recursos/native libs)
        val listA = run("unzip -l ${quote(a.absolutePath)} 2>/dev/null | awk '{print \$4}' | grep -v '^$' | sort")
        val listB = run("unzip -l ${quote(b.absolutePath)} 2>/dev/null | awk '{print \$4}' | grep -v '^$' | sort")
        val setA = listA.lines().filter { it.isNotBlank() }.toSet()
        val setB = listB.lines().filter { it.isNotBlank() }.toSet()
        val onlyA = setA - setB
        val onlyB = setB - setA
        sb.appendLine("--- Diferença de arquivos internos ---")
        sb.appendLine("Só no A (${onlyA.size}): ${onlyA.take(20).joinToString(", ")}")
        sb.appendLine("Só no B (${onlyB.size}): ${onlyB.take(20).joinToString(", ")}")
        sb.appendLine()

        // 3) Manifest (permissões, components, SDK)
        for ((label, apk) in listOf("A" to a, "B" to b)) {
            val manifest = run("apktool d -f -o ${quote(File(workRoot(), ".codex/apk_analysis/tmp_$label").absolutePath)} ${quote(apk.absolutePath)} >/dev/null 2>&1; " +
                "grep -oE 'android:exported=\"true\"|debuggable=\"true\"|allowBackup=\"true\"|usesCleartextTraffic=\"true\"' ${quote(File(workRoot(), ".codex/apk_analysis/tmp_$label/AndroidManifest.xml").absolutePath)} 2>/dev/null | sort | uniq -c")
            sb.appendLine("--- Manifesto $label ---\n$manifest")
        }
        sb.appendLine()

        // 4) Código (DEX diff via jadx)
        if (available("jadx")) {
            val base = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/duo_${System.currentTimeMillis()}").apply { mkdirs() }
            run("mkdir -p ${quote(base.absolutePath)}/a ${quote(base.absolutePath)}/b")
            run("jadx -d ${quote(base.absolutePath)}/a ${quote(a.absolutePath)} >/dev/null 2>&1", 300)
            run("jadx -d ${quote(base.absolutePath)}/b ${quote(b.absolutePath)} >/dev/null 2>&1", 300)
            val diff = run("diff -rq ${quote(base.absolutePath)}/a ${quote(base.absolutePath)}/b 2>&1 | head -n 60")
            sb.appendLine("--- Diff de código (jadx) ---\n${if (diff == "OK (sem saída)") "código idêntico" else diff}")
            sb.appendLine("Código extraído: ${base.absolutePath}")
        } else {
            sb.appendLine("--- Diff de código: jadx não instalado (use tool_hub) ---")
        }

        // 5) Sugestões automáticas de melhoria
        sb.appendLine()
        sb.appendLine("=== SUGESTÕES DE MELHORIA ===")
        val bigger = if (a.length() > b.length()) "A" else "B"
        val smaller = if (a.length() > b.length()) "B" else "A"
        val diffPct = kotlin.math.abs(a.length() - b.length()) * 100.0 / maxOf(a.length(), b.length())
        sb.appendLine("• $bigger é maior que $smaller por ${String.format("%.1f", diffPct)}% — investigue recursos/ABIs duplicados.")
        if (onlyB.isNotEmpty()) sb.appendLine("• $smaller tem ${onlyB.size} arquivos a menos — pode ser versão otimizada; compare o que foi removido.")
        if (onlyA.isNotEmpty()) sb.appendLine("• $bigger tem ${onlyA.size} arquivos exclusivos — verifique se são necessários.")
        if (focus.isNotBlank()) sb.appendLine("• Foco do usuário: '$focus' — use as ferramentas acima com este alvo para a correção específica.")
        sb.appendLine("• Para aplicar uma correção: use dex_patch no smali extraído, depois dex_rebuild + rebuild_and_sign + test_cycle.")

        return sb.toString()
    }
}
