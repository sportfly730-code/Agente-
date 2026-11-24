package com.codex.agent.agent

import com.codex.agent.data.model.Constants
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Ferramenta SDR (Software Defined Radio) para recepção de TRANSMISSÕES ANALÓGICAS
 * e bandas públicas/licenciadas — uso legítimo: rádio FM, aviação, amador, clima,
 * ISM, varredura de espectro e gravação de sinal.
 *
 * NÃO inclui: celular, conversas privadas (cordless/interfone/monitor de bebê),
 * tráfego criptografado ou ativação de microfone remoto.
 *
 * Requer: dongle RTL-SDR conectado via USB OTG e pacote `rtl-sdr` no Termux
 * (`pkg install rtl-sdr`). O dongle precisa ser visível para o Termux
 * (/dev/bus/usb) — em alguns dispositivos é necessário concessão de permissão USB.
 */
class SdrTool(
    private val shellExecutor: ShellExecutor,
    private val workspaceRepository: WorkspaceRepository
) {

    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' é obrigatório."
        return try {
            when (action) {
                "sdr_check" -> check()
                "sdr_install" -> install()
                "sdr_devices" -> devices()
                "sdr_scan" -> scan(args)
                "sdr_fm" -> fm(args)
                "sdr_record" -> record(args)
                "sdr_iq_fft" -> iqFft(args)
                else -> "Ação SDR desconhecida: $action\nUse: sdr_check, sdr_install, sdr_devices, sdr_scan, sdr_fm, sdr_record, sdr_iq_fft"
            }
        } catch (e: Exception) {
            "Erro em sdr_tool ($action): ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    private suspend fun workRoot(): String =
        workspaceRepository.active()?.rootPath ?: Constants.DEFAULT_WORKSPACE_ROOT

    private suspend fun run(cmd: String, timeout: Int = 120): String {
        val r = shellExecutor.execute(cmd, timeoutSec = timeout)
        return if (r.exitCode == 0) r.output.trim().ifBlank { "OK (sem saída)" }
        else "exit=${r.exitCode}:\n${r.output.trim()}"
    }

    private suspend fun available(name: String): Boolean =
        shellExecutor.execute("command -v $name >/dev/null 2>&1 && echo yes || echo no", timeoutSec = 15)
            .output.trim() == "yes"

    private suspend fun check(): String {
        val tools = listOf("rtl_test", "rtl_fm", "rtl_power", "rtl_sdr", "sox", "ffmpeg")
        val sb = StringBuilder("=== SDR / RTL-SDR ===\n")
        tools.forEach { sb.appendLine("${if (available(it)) "✅" else "❌"} $it") }
        val usb = shellExecutor.execute("ls /dev/bus/usb 2>/dev/null | head -n 5", timeoutSec = 15).output.trim()
        sb.appendLine()
        sb.appendLine("Dispositivos USB visíveis: ${if (usb.isBlank()) "nenhum" else usb.replace("\n", ", ")}")
        sb.appendLine()
        sb.appendLine("Bandas permitidas (recepção): FM 88–108 MHz, aviação 108–137 MHz, " +
            "amador, NOAA 162 MHz, ISM 433/868/915 MHz.")
        sb.appendLine("Instale: action='sdr_install'  e conecte o dongle via USB OTG.")
        sb.appendLine("ESCopo: apenas transmissões abertas/licenciadas — sem conversas privadas.")
        return sb.toString()
    }

    private suspend fun install(): String {
        if (available("rtl_fm")) return "rtl-sdr já instalado."
        val r = shellExecutor.execute(
            "pkg install -y rtl-sdr sox 2>&1 | tail -n 20",
            timeoutSec = 300
        )
        return "Instalação rtl-sdr:\n${r.output.trim()}\n\nVerifique com action='sdr_check'."
    }

    private suspend fun devices(): String {
        if (!available("rtl_test")) return "rtl_test não instalado. Use action='sdr_install'."
        return run("rtl_test -t 2>&1 | head -n 30", 30)
    }

    private suspend fun scan(args: JsonObject): String {
        if (!available("rtl_power")) return "rtl_power não instalado. Use action='sdr_install'."
        val start = args["start_mhz"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'start_mhz' obrigatório."
        val end = args["end_mhz"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'end_mhz' obrigatório."
        val gain = args["gain"]?.jsonPrimitive?.contentOrNull ?: "auto"
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/sdr_scan_${System.currentTimeMillis()}.csv")
        out.parentFile?.mkdirs()
        val r = shellExecutor.execute(
            "timeout 40 rtl_power -f ${start}M:${end}M:50k -g $gain -i 2 -1 \"${out.absolutePath}\" 2>&1 | tail -n 10",
            timeoutSec = 60
        )
        val lines = runCatching { out.readLines() }.getOrDefault(emptyList())
        return buildString {
            appendLine("Varredura ${start}–${end} MHz concluída.")
            appendLine("Arquivo CSV (potência por frequência/tempo): ${out.absolutePath}")
            appendLine("Linhas: ${lines.size}")
            if (r.output.isNotBlank()) appendLine("Saída: ${r.output.trim()}")
            appendLine("Dica: abra o CSV com um heatmap (ex.: GNU Plot, Python matplotlib) para ver occupying spectrum.")
        }
    }

    private suspend fun fm(args: JsonObject): String {
        if (!available("rtl_fm")) return "rtl_fm não instalado. Use action='sdr_install'."
        val freq = args["freq_mhz"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'freq_mhz' obrigatório (ex.: 98.5 para rádio FM)."
        val seconds = args["seconds"]?.jsonPrimitive?.contentOrNull ?: "15"
        val gain = args["gain"]?.jsonPrimitive?.contentOrNull ?: "auto"
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/sdr_fm_${freq}MHz_${System.currentTimeMillis()}.wav")
        out.parentFile?.mkdirs()
        val r = shellExecutor.execute(
            "timeout ${seconds.toIntOrNull()?.plus(3) ?: 18} rtl_fm -f ${freq}M -M fm -s 22050 -g $gain -l 0 2>/dev/null | " +
                "sox -t raw -r 22050 -e signed -b 16 -c 1 - \"${out.absolutePath}\" rate 44100 2>&1 | tail -n 5",
            timeoutSec = (seconds.toIntOrNull() ?: 15) + 20
        )
        return buildString {
            appendLine("Recepção FM em ${freq} MHz por ${seconds}s.")
            appendLine("Áudio salvo: ${out.absolutePath} (${out.length()} bytes)")
            if (r.output.isNotBlank() && !r.output.contains("OK")) appendLine("Saída: ${r.output.trim()}")
            if (available("termux-media-player")) {
                appendLine("Ouvir: termux-media-player play \"${out.absolutePath}\"")
            } else {
                appendLine("Abra o arquivo com um player de áudio ou: ffmpeg -i \"${out.absolutePath}\" -f null -")
            }
        }
    }

    private suspend fun record(args: JsonObject): String {
        if (!available("rtl_sdr")) return "rtl_sdr não instalado. Use action='sdr_install'."
        val freq = args["freq_mhz"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'freq_mhz' obrigatório."
        val rate = args["rate_msps"]?.jsonPrimitive?.contentOrNull ?: "2.4"
        val seconds = args["seconds"]?.jsonPrimitive?.contentOrNull ?: "10"
        val gain = args["gain"]?.jsonPrimitive?.contentOrNull ?: "auto"
        val out = File(workRoot(), "${Constants.APK_ANALYSIS_DIR}/sdr_iq_${freq}MHz_${System.currentTimeMillis()}.iq")
        out.parentFile?.mkdirs()
        val bytes = (rate.toDoubleOrNull() ?: 2.4) * 1_000_000.0 * 2.0 * 2.0 * (seconds.toDoubleOrNull() ?: 10.0)
        val count = bytes.toLong().coerceAtMost(200_000_000L).toString()
        run("timeout ${(seconds.toIntOrNull() ?: 10) + 5} rtl_sdr -f ${freq}M -s ${rate}M -g $gain -n $count \"${out.absolutePath}\" 2>&1 | tail -n 8", 90)
        return "Amostras IQ salvas: ${out.absolutePath} (${out.length()} bytes)\nUse action='sdr_iq_fft' com 'file' para analisar o espectro."
    }

    /** FFT simples (Cooley-Tukey radix-2) sobre amostras IQ gravadas → picos de frequência. */
    private suspend fun iqFft(args: JsonObject): String {
        val file = args["file"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'file' obrigatório (arquivo .iq)."
        val f = File(file)
        if (!f.exists()) return "Erro: arquivo não encontrado: ${f.absolutePath}"
        val n = 4096
        val need = n * 2
        if (f.length() < need) return "Arquivo muito pequeno para FFT ($n amostras)."
        // Lê apenas os primeiros `need` bytes — nunca carrega a gravação inteira (evita OOM).
        val raw = ByteArray(need)
        java.io.FileInputStream(f).use { it.read(raw, 0, need) }

        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until n) {
            re[i] = (raw[i * 2].toInt() and 0xFF) - 128.0
            im[i] = (raw[i * 2 + 1].toInt() and 0xFF) - 128.0
        }
        // Janela de Hann
        for (i in 0 until n) {
            val w = 0.5 * (1 - kotlin.math.cos(2 * PI * i / (n - 1)))
            re[i] *= w; im[i] *= w
        }
        fft(re, im)
        val mag = DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val mhzStr = args["freq_mhz"]?.jsonPrimitive?.contentOrNull
        val mhz = mhzStr?.toDoubleOrNull()
        val peaks = mag.withIndex().sortedByDescending { it.value }.take(8).map {
            val delta = it.index * 2.4 / (n / 2)
            val abs = if (mhz != null) " | freq≈${String.format("%.2f", mhz + delta)} MHz" else ""
            "bin ${it.index} (Δ=${String.format("%.1f", delta)} MHz$abs) magnitude=${String.format("%.1f", it.value)}"
        }
        return buildString {
            appendLine("=== FFT de amostras IQ (n=$n) ===")
            peaks.forEach { appendLine("• $it") }
            appendLine("Pico dominante: bin ${mag.indices.maxByOrNull { mag[it] } ?: 0}")
        }
    }

    /** FFT radix-2 in-place. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wlenRe = kotlin.math.cos(ang)
            val wlenIm = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0; var wIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vi = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + half] = ur - vr; im[i + k + half] = ui - vi
                    val nwRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nwRe
                }
                i += len
            }
            len *= 2
        }
    }
}
