package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * RE AVANÇADA DE TV BOXES / ANDROID TV / FIRE TV / CINEBOX (aparelhos do próprio usuário).
 *
 * Conecta via ADB (USB ou WiFi) e aplica:
 *  - fingerprint: detecta fabricante/modelo/chipset/Android/ABI
 *  - specs: CPU, RAM, armazenamento, resolução, codecs
 *  - partitions: lista partições e montagens
 *  - buildprop: extrai build.prop completo
 *  - system_apps: lista APKs do sistema (/system, /vendor, /product)
 *  - native_libs: lista bibliotecas .so do sistema
 *  - fw_extract: extrai imagem de firmware via dd (se autorizado no device)
 *  - dump_packages: informações detalhadas de pacotes instalados
 *  - screenshot / ui_dump: captura e hierarquia da UI (uiautomator)
 *
 * Tudo executado via ADB no aparelho conectado (que pertence ao usuário).
 */
class TvBoxRE(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository
) {
    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'action' obrigatório."
        val serial = args["serial"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val adb = if (serial.isBlank()) "adb" else "adb -s ${sq(serial)}"
        return try {
            when (action) {
                "detect" -> detect(adb)
                "specs" -> specs(adb)
                "partitions" -> partitions(adb)
                "buildprop" -> buildProp(adb)
                "system_apps" -> systemApps(adb)
                "native_libs" -> nativeLibs(adb)
                "fw_extract" -> fwExtract(adb, args)
                "dump_packages" -> dumpPackages(adb, args)
                "ui_dump" -> uiDump(adb)
                "reboot" -> reboot(adb, args)
                "root_check" -> rootCheck(adb)
                "remount" -> remount(adb, args)
                "edit_buildprop" -> editBuildProp(adb, args)
                "install_system_app" -> installSystemApp(adb, args)
                "push_file" -> pushFile(adb, args)
                "pull_file" -> pullFile(adb, args)
                "exec_root" -> execRoot(adb, args)
                "install_recovery" -> installRecovery(adb, args)
                "flash_firmware" -> flashFirmware(adb, args)
                "list_downloads" -> listFirmwareDownloads()
                else -> "Ação desconhecida em tvbox_re: $action"
            }
        } catch (e: Exception) {
            "Erro em tvbox_re ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    private fun sq(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    private suspend fun run(cmd: String, timeout: Int = 60): String {
        val r = shellExecutor.execute(cmd, timeoutSec = timeout)
        return if (r.exitCode == 0) r.output.trim().ifBlank { "OK (sem saída)" }
        else "exit=${r.exitCode}:\n${r.output.trim()}"
    }

    private suspend fun adbOk(): Boolean =
        shellExecutor.execute("command -v adb >/dev/null 2>&1 && echo yes || echo no", timeoutSec = 15)
            .output.trim() == "yes"

    /** Detecta modelo, fabricante, chipset e tipo de aparelho. */
    private suspend fun detect(adb: String): String {
        if (!adbOk()) return "adb não instalado. Instale android-tools (pkg install android-tools)."
        val props = run("$adb shell getprop 2>&1", 30)
        fun p(key: String) = props.lines().firstOrNull { it.contains("[$key]") }
            ?.substringAfter(":")?.trim()?.trim('[', ']') ?: "?"
        val brand = p("ro.product.brand")
        val model = p("ro.product.model")
        val device = p("ro.product.device")
        val mfg = p("ro.product.manufacturer")
        val android = p("ro.build.version.release")
        val sdk = p("ro.build.version.sdk")
        val abi = p("ro.product.cpu.abi")
        val abi2 = p("ro.product.cpu.abilist")
        val cpu = run("$adb shell 'cat /proc/cpuinfo 2>/dev/null | grep -m1 \"Hardware\\|Processor\"'", 20)
        val chipset = when {
            props.contains("amlogic", true) || cpu.contains("Amlogic", true) -> "Amlogic"
            props.contains("rockchip", true) || cpu.contains("Rockchip", true) -> "Rockchip"
            props.contains("allwinner", true) || cpu.contains("Allwinner", true) -> "Allwinner"
            props.contains("realtek", true) || cpu.contains("Realtek", true) -> "Realtek"
            props.contains("mediatek", true) || cpu.contains("MT", true) -> "MediaTek"
            model.contains("Fire TV", true) || brand.contains("Amazon", true) -> "Amazon/MediaTek"
            else -> "Desconhecido"
        }
        val type = when {
            model.contains("Fire", true) || brand.contains("Amazon", true) -> "Amazon Fire TV / Fire Stick"
            model.contains("Cinebox", true) || brand.contains("Cinebox", true) -> "Cinebox"
            props.contains("android.tv", true) || sdk.toIntOrNull()?.let { it >= 21 } == true ->
                if (brand.contains("Xiaomi", true)) "Xiaomi TV Box" else "Android TV / TV Box genérico"
            else -> "Dispositivo Android"
        }
        return buildString {
            appendLine("=== Dispositivo Detectado ===")
            appendLine("Tipo: $type")
            appendLine("Fabricante: $mfg ($brand)")
            appendLine("Modelo: $model")
            appendLine("Device: $device")
            appendLine("Chipset: $chipset")
            appendLine("Android: $android (SDK $sdk)")
            appendLine("ABI: $abi")
            appendLine("ABIs: $abi2")
            appendLine("CPU: ${cpu.take(120)}")
            appendLine()
            appendLine("Use: tvbox_re(action='specs'|'partitions'|'buildprop'|'system_apps'|'native_libs')")
        }
    }

    private suspend fun specs(adb: String): String {
        if (!adbOk()) return "adb não instalado."
        val mem = run("$adb shell 'cat /proc/meminfo 2>/dev/null | head -3'", 20)
        val cpu = run("$adb shell 'cat /proc/cpuinfo 2>/dev/null | grep -E \"model name|processor|Hardware\" | head -8'", 20)
        val storage = run("$adb shell 'df -h /data /system 2>/dev/null'", 20)
        val display = run("$adb shell wm size 2>&1; $adb shell wm density 2>&1", 20)
        val codecs = run("$adb shell 'ls /system/lib*/libcodec* /vendor/lib*/libcodec* 2>/dev/null | head -20'", 20)
        return "=== Especificações ===\nMEMÓRIA:\n$mem\n\nCPU:\n$cpu\n\nARMAZENAMENTO:\n$storage\n\nTELA:\n$display\n\nCODECS:\n$codecs"
    }

    private suspend fun partitions(adb: String): String {
        if (!adbOk()) return "adb não instalado."
        val mounts = run("$adb shell 'cat /proc/mounts 2>/dev/null | grep -E \"/system|/vendor|/data|/product|/cache|/recovery|/boot\" | head -60'", 30)
        val blocks = run("$adb shell 'ls -l /dev/block/ 2>/dev/null | head -40'", 20)
        return "=== Partições ===\nMOUNTS:\n$mounts\n\nBLOCK DEVICES:\n$blocks"
    }

    private suspend fun buildProp(adb: String): String {
        if (!adbOk()) return "adb não instalado."
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/build_prop_${System.currentTimeMillis()}.txt")
        out.parentFile?.mkdirs()
        run("$adb shell 'cat /system/build.prop /vendor/build.prop /product/build.prop 2>/dev/null' > ${sq(out.absolutePath)}", 60)
        if (!out.exists() || out.length() == 0L) return "build.prop não encontrado (acesso restrito no aparelho)."
        return "=== build.prop extraído: ${out.absolutePath} (${out.length()} bytes) ===\n${out.readText().take(4000)}"
    }

    private suspend fun systemApps(adb: String): String {
        if (!adbOk()) return "adb não instalado."
        val apps = run("$adb shell 'pm list packages -s 2>/dev/null | head -150'", 40)
        return "=== APKs do Sistema (${apps.lines().size}) ===\n$apps"
    }

    private suspend fun nativeLibs(adb: String): String {
        if (!adbOk()) return "adb não instalado."
        val libs = run("$adb shell 'find /system/lib /system/lib64 /vendor/lib /vendor/lib64 -name \"*.so\" 2>/dev/null | head -100'", 40)
        return "=== Bibliotecas nativas do sistema ===\n$libs"
    }

    private suspend fun fwExtract(adb: String, args: JsonObject): String {
        if (!adbOk()) return "adb não instalado."
        val partition = args["partition"]?.jsonPrimitive?.contentOrNull ?: "boot"
        val allowed = setOf("boot", "recovery", "system", "vendor", "product")
        if (partition !in allowed) return "Partição inválida. Use: ${allowed.joinToString(", ")}"
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/fw_${partition}_${System.currentTimeMillis()}.img")
        out.parentFile?.mkdirs()
        // Tenta via /dev/block/by-name/<partition>; requer permissão root/adb root no aparelho
        val dev = "/dev/block/by-name/$partition"
        val r = shellExecutor.execute(
            "$adb shell \"dd if=$dev of=/sdcard/fw_$partition.img bs=1M 2>/dev/null && echo OK\" " +
                "&& $adb pull /sdcard/fw_$partition.img ${sq(out.absolutePath)} 2>&1 | tail -n 3",
            timeoutSec = 300
        )
        return if (out.exists() && out.length() > 0) {
            "✅ Firmware '$partition' extraído: ${out.absolutePath} (${out.length() / 1024} KB)"
        } else "Falha ao extrair '$partition' (pode exigir root no aparelho):\n${r.output.take(800)}"
    }

    private suspend fun dumpPackages(adb: String, args: JsonObject): String {
        if (!adbOk()) return "adb não instalado."
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull
            ?: return "Erro: 'package' obrigatório."
        if (!pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]{2,120}$"))) return "Erro: package inválido."
        val info = run("$adb shell dumpsys package $pkg 2>&1 | head -100", 40)
        val path = run("$adb shell pm path $pkg 2>&1", 20)
        return "=== Pacote $pkg ===\nCAMINHO:\n$path\n\nINFO:\n$info"
    }

    private suspend fun uiDump(adb: String): String {
        if (!adbOk()) return "adb não instalado."
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/ui_dump_${System.currentTimeMillis()}.xml")
        out.parentFile?.mkdirs()
        run("$adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && $adb pull /sdcard/ui.xml ${sq(out.absolutePath)} 2>&1 | tail -n 2", 60)
        return if (out.exists()) "UI dump: ${out.absolutePath}\n${out.readText().take(3000)}"
        else "Falha no UI dump (uiautomator pode não estar disponível)."
    }

    private suspend fun reboot(adb: String, args: JsonObject): String {
        if (!adbOk()) return "adb não instalado."
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "normal"
        val cmd = when (mode) {
            "recovery" -> "$adb reboot recovery"
            "bootloader" -> "$adb reboot bootloader"
            else -> "$adb reboot"
        }
        run(cmd, 20)
        return "Comando de reboot '$mode' enviado ao aparelho."
    }

    // ==================================================================
    // MODIFICAÇÃO DE SISTEMA / FIRMWARE (aparelhos do próprio usuário)
    // ==================================================================

    private suspend fun rootCheck(adb: String): String {
        if (!adbOk()) return "adb não instalado."
        val su = run("$adb shell 'which su 2>/dev/null; su -c id 2>&1' | head -5", 20)
        val adbRoot = run("$adb root 2>&1 | head -3", 20)
        val rw = run("$adb shell 'mount | grep \" /system \" | head -1'", 20)
        return "=== Status de Root ===\nSU:\n$su\n\nADB ROOT:\n$adbRoot\n\nMONTAGEM /system:\n$rw"
    }

    private suspend fun remount(adb: String, args: JsonObject): String {
        if (!adbOk()) return "adb não instalado."
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "rw"
        if (mode !in setOf("rw", "ro")) return "Erro: mode deve ser 'rw' ou 'ro'."
        val r = run("$adb root >/dev/null 2>&1; $adb remount 2>&1 || $adb shell 'mount -o remount,$mode /system 2>&1'", 30)
        return "Remount '$mode':\n$r"
    }

    private suspend fun editBuildProp(adb: String, args: JsonObject): String {
        if (!adbOk()) return "adb não instalado."
        val key = args["key"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'key' obrigatório."
        val value = args["value"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'value' obrigatório."
        if (!key.matches(Regex("^[a-zA-Z0-9._-]+$"))) return "Erro: chave inválida."
        val safeValue = value.filter { it.isLetterOrDigit() || it in "._-/: " }
        val backup = "/sdcard/build.prop.bak"
        val r = run(
            "$adb root >/dev/null 2>&1; $adb remount >/dev/null 2>&1; " +
                "$adb shell \"[ -f /system/build.prop ] && cp /system/build.prop $backup; " +
                "sed -i 's/^$key=.*/$key=$safeValue/' /system/build.prop || echo '$key=$safeValue' >> /system/build.prop\" 2>&1",
            40
        )
        return "build.prop: $key=$safeValue\nBackup em $backup no aparelho.\n$r"
    }

    private suspend fun installSystemApp(adb: String, args: JsonObject): String {
        if (!adbOk()) return "adb não instalado."
        val apkPath = args["apk"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'apk' obrigatório (caminho no aparelho ou local)."
        val local = File(apkPath)
        if (local.exists()) {
            val remote = "/data/local/tmp/${local.name}"
            run("$adb push ${sq(local.absolutePath)} $remote 2>&1 | tail -n 2", 120)
            val r = run("$adb root >/dev/null 2>&1; $adb remount >/dev/null 2>&1; $adb shell \"cp $remote /system/app/${local.name} && chmod 644 /system/app/${local.name}\" 2>&1", 90)
            return "App instalado como sistema:\n$r"
        }
        val r = run("$adb root >/dev/null 2>&1; $adb remount >/dev/null 2>&1; $adb shell \"cp $apkPath /system/app/ && chmod 644 /system/app/$(basename $apkPath)\" 2>&1", 90)
        return "App instalado como sistema:\n$r"
    }

    private suspend fun pushFile(adb: String, args: JsonObject): String {
        if (!adbOk()) return "adb não instalado."
        val localPath = args["local"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'local' obrigatório."
        val remotePath = args["remote"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'remote' obrigatório."
        val local = File(workRoot(), localPath)
        if (!local.exists()) return "Erro: arquivo local não encontrado: ${local.absolutePath}"
        if (!remotePath.startsWith("/")) return "Erro: 'remote' deve ser caminho absoluto."
        val r = run("$adb push ${sq(local.absolutePath)} ${sq(remotePath)} 2>&1 | tail -n 3", 180)
        return "Push concluído: ${local.name} → $remotePath\n$r"
    }

    private suspend fun pullFile(adb: String, args: JsonObject): String {
        if (!adbOk()) return "adb não instalado."
        val remotePath = args["remote"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'remote' obrigatório."
        val localName = args["local_name"]?.jsonPrimitive?.contentOrNull
            ?: remotePath.substringAfterLast('/').ifBlank { "pulled_file" }
        if (!localName.matches(Regex("^[A-Za-z0-9._-]+$"))) return "Erro: nome inválido."
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/$localName")
        out.parentFile?.mkdirs()
        val r = run("$adb pull ${sq(remotePath)} ${sq(out.absolutePath)} 2>&1 | tail -n 3", 180)
        return if (out.exists()) "Pull concluído: ${out.absolutePath} (${out.length()} bytes)\n$r"
        else "Falha no pull:\n$r"
    }

    private suspend fun execRoot(adb: String, args: JsonObject): String {
        if (!adbOk()) return "adb não instalado."
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'command' obrigatório."
        val block = com.codex.agent.shell.ShellSafetyGuard.analyze(command)
        if (block != null) return "BLOQUEADO POR SEGURANÇA: $block"
        val r = run("$adb root >/dev/null 2>&1; $adb shell ${sq(command)} 2>&1 | head -n 100", 60)
        return "=== Comando root no aparelho ===\n$r"
    }

    private suspend fun installRecovery(adb: String, args: JsonObject): String {
        if (!adbOk()) return "adb não instalado."
        val imgPath = args["image"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'image' obrigatório (caminho da imagem recovery)."
        val img = File(workRoot(), imgPath)
        if (!img.exists()) return "Erro: imagem não encontrada: ${img.absolutePath}"
        val r = run(
            "$adb push ${sq(img.absolutePath)} /data/local/tmp/recovery.img >/dev/null 2>&1 && " +
                "$adb shell \"dd if=/data/local/tmp/recovery.img of=/dev/block/by-name/recovery bs=1M 2>&1\" 2>&1 | tail -n 10",
            300
        )
        return "Flash de recovery:\n$r\n\n⚠️ Use apenas recovery compatível com seu modelo."
    }

    private suspend fun flashFirmware(adb: String, args: JsonObject): String {
        if (!adbOk()) return "adb não instalado."
        val imgPath = args["image"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'image' obrigatório."
        val partition = args["partition"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'partition' obrigatório."
        val allowed = setOf("boot", "recovery", "system", "vendor", "product", "logo", "misc")
        if (partition !in allowed) return "Erro: partição inválida. Use: ${allowed.joinToString(", ")}"
        val img = File(workRoot(), imgPath)
        if (!img.exists()) return "Erro: imagem não encontrada: ${img.absolutePath}"
        val r = run(
            "$adb push ${sq(img.absolutePath)} /data/local/tmp/fw.img >/dev/null 2>&1 && " +
                "$adb shell \"dd if=/data/local/tmp/fw.img of=/dev/block/by-name/$partition bs=1M 2>&1\" 2>&1 | tail -n 10",
            600
        )
        return "Flash de '$partition':\n$r\n\n⚠️ Certifique-se de que a imagem é compatível com seu aparelho — flash errado pode brickar o device."
    }

    private suspend fun listFirmwareDownloads(): String {
        val dir = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}")
        val files = dir.listFiles()?.filter { it.extension in setOf("img", "bin", "zip", "tar", "md5") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) return "Nenhuma imagem de firmware no workspace. Use fw_extract ou faça upload."
        return "=== Imagens de firmware no workspace ===\n" +
            files.joinToString("\n") { "  • ${it.name} (${it.length() / 1024} KB)" }
    }

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT
}
