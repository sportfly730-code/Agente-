package com.codex.agent.agent

import com.codex.agent.voice.VoiceControllerProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Tool para controlar o sistema de voz full-duplex (escuta, TTS, modo). */
class VoiceTool {
    suspend fun handle(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'action' é obrigatório."
        val vc = VoiceControllerProvider.get() ?: return "Erro: controle de voz não inicializado."
        return when (action) {
            "status" -> {
                val s = vc.state.value
                "Voz: ${s.mode} | ouvindo=${s.listening} | falando=${s.ttsSpeaking} | último: '${s.lastTranscript.take(80)}'"
            }
            "start_live" -> {
                vc.startLive()
                "🎙️ Ligação LIVE iniciada (modo Gemini). Fale naturalmente; posso interromper a qualquer momento."
            }
            "start_listening" -> {
                val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "PUSH_TO_TALK"
                val wake = args["wake_word"]?.jsonPrimitive?.contentOrNull ?: "codex"
                vc.startListening(mode, wake)
                "Escuta ativa (modo $mode). Diga 'para' para interromper a execução a qualquer momento."
            }
            "stop_listening" -> { vc.stopListening(); "Escuta encerrada." }
            "speak" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return "Erro: 'text' obrigatório."
                vc.speak(text); "Falando: ${text.take(80)}"
            }
            "stop_speaking" -> { vc.stopSpeaking(); "Fala interrompida." }
            "set_speak" -> {
                val enabled = args["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: return "Erro: 'enabled' obrigatório (true/false)."
                vc.setTtsEnabled(enabled)
                if (enabled) "Voz da IA ATIVADA." else "Voz da IA DESATIVADA — a IA não falará mais."
            }
            "toggle_speak" -> {
                val newVal = !vc.state.value.ttsEnabled
                vc.setTtsEnabled(newVal)
                if (newVal) "Voz da IA ATIVADA." else "Voz da IA DESATIVADA."
            }
            else -> "Ação desconhecida em voice_tool: $action"
        }
    }
}
