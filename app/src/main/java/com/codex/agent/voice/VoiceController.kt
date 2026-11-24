package com.codex.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Lógica pura de comandos de voz — extraída para ser testável sem Android. */
object VoiceCommandMatcher {
    /**
     * Comandos de PARADA exigem frases imperativas curtas e inequívocas.
     * Palavras isoladas como "para"/"sai" foram REMOVIDAS porque são muito comuns
     * em português ("para o projeto", "sai da pasta") e causavam interrupções
     * acidentais a cada frase do usuário.
     */
    private val STOP_PHRASES = setOf(
        "pare", "para agora", "pare agora", "para tudo", "pare tudo",
        "stop", "stop agora", "cancelar", "cancela", "cancelar tudo",
        "pode parar", "pare a execucao", "para a execucao", "pare o agente",
        "nao faz isso", "desiste"
    )

    fun normalize(s: String): String {
        val accented = "áàãâäéèêëíìîïóòõôöúùûüçñ"
        val plain = "aaaaaaeeeeiiiiooooouuuucn"
        var out = s.lowercase().trim()
        for (i in accented.indices) out = out.replace(accented[i], plain[i])
        return out.replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Só interrompe se a frase INTEIRA for um comando de parada curto e exato.
     * Frases longas (ex.: "para o projeto e lista os arquivos") NÃO interrompem —
     * são comandos normais para o agente.
     */
    fun isStopCommand(text: String, partial: Boolean): Boolean {
        if (partial) return false
        val lower = normalize(text)
        // Aceita apenas frases curtas (≤4 palavras) que batem exatamente com um comando
        if (lower.split(" ").size > 4) return false
        return lower in STOP_PHRASES
    }
}

/** Ponte global para o controle de voz (acessível por UI, tools e serviço). */
object VoiceControllerProvider {
    @Volatile private var instance: VoiceController? = null
    fun attach(controller: VoiceController) { instance = controller }
    fun get(): VoiceController? = instance
}

/**
 * Controle de voz FULL-DUPLEX para o agente:
 *  - Escuta contínua (via SpeechRecognizer em modo RECOGNIZE_SPEECH reiniciado) mesmo
 *    enquanto o agente executa tarefas.
 *  - Detecta comandos de INTERRUPÇÃO ("para", "pare", "stop", "cancelar", "sai") e dispara
 *    um callback para o orquestrador parar a execução.
 *  - Fala as respostas da IA via TextToSpeech (TTS) com fila de utterances.
 *
 * Privacidade: todo processamento de voz acontece no dispositivo (SpeechRecognizer do sistema);
 * nenhum áudio é enviado para servidores do app.
 */
class VoiceController(private val context: Context) {

    data class VoiceState(
        val listening: Boolean = false,
        val lastTranscript: String = "",
        val ttsSpeaking: Boolean = false,
        val ttsEnabled: Boolean = true,
        /** OFF | PUSH_TO_TALK | WAKE_WORD | LIVE (ligação contínua estilo Gemini) */
        val mode: String = "OFF",
        /** True quando o usuário está falando (detectado por RMS) — usado para barge-in. */
        val userSpeaking: Boolean = false
    )

    /** Callback disparado quando um comando de parada é reconhecido. */
    var onStopCommand: (() -> Unit)? = null
    /** Callback com o texto reconhecido (para enviar como prompt ao agente). */
    var onTranscript: ((String) -> Unit)? = null
    /** No modo LIVE: chamado quando o usuário interrompe a IA (barge-in). */
    var onBargeIn: (() -> Unit)? = null
    /** Callback de erro (ex.: SpeechRecognizer indisponível no dispositivo). */
    var onError: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var continuous = false
    private var wakeWord = "codex"
    private var ttsReady = false

    /** Barge-in: nível de ruído acima deste valor enquanto a IA fala = usuário interrompendo. */
    private val BARGE_IN_RMS_THRESHOLD = -45.0f
    private var bargeInArmed = false
    private var lastBargeInMs = 0L

    /** Preferência persistida: a IA pode falar ou não. */
    private val prefs = context.getSharedPreferences("codex_voice_prefs", Context.MODE_PRIVATE)
    private var ttsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, true)

    private val _state = MutableStateFlow(VoiceState(ttsEnabled = ttsEnabled))
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    companion object {
        private const val TAG = "VoiceController"
        private const val KEY_TTS_ENABLED = "tts_enabled"
    }

    /** Inicializa o TTS. Chamado uma vez. */
    fun initTts(onReady: (() -> Unit)? = null) {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("pt", "BR"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _state.value = _state.value.copy(ttsSpeaking = true)
                    }
                    override fun onDone(utteranceId: String?) {
                        _state.value = _state.value.copy(ttsSpeaking = false)
                    }
                    override fun onError(utteranceId: String?) {
                        _state.value = _state.value.copy(ttsSpeaking = false)
                    }
                })
                onReady?.invoke()
            } else {
                Log.e(TAG, "TTS init falhou: $status")
            }
        }
    }

    /** Ativa/desativa a fala da IA (persistido). */
    fun setTtsEnabled(enabled: Boolean) {
        ttsEnabled = enabled
        prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
        _state.value = _state.value.copy(ttsEnabled = enabled)
        if (!enabled) stopSpeaking()
    }

    /** Fala um texto (fila de utterances) — respeita a preferência DO USUÁRIO. */
    fun speak(text: String) {
        if (!ttsEnabled || !ttsReady || text.isBlank()) return
        // Arma o barge-in: enquanto a IA fala, ruído do usuário interrompe.
        bargeInArmed = _state.value.mode == "LIVE"
        // Remove markdown/emojis para TTS limpo
        val clean = text.replace(Regex("[*_`#>]"), "").replace(Regex("[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]"), "").take(600)
        tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "utt_${System.currentTimeMillis()}")
    }

    /** Para de falar imediatamente (ex.: quando o usuário interrompe). */
    fun stopSpeaking() {
        tts?.stop()
        _state.value = _state.value.copy(ttsSpeaking = false)
    }

    /**
     * Inicia escuta contínua. `mode` define push-to-talk ou wake word.
     * O reconhecedor é reiniciado automaticamente a cada resultado/erro (loop full-duplex).
     */
    fun startListening(mode: String = "PUSH_TO_TALK", wake: String = "codex") {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val msg = "Reconhecimento de voz não disponível neste dispositivo. " +
                "Verifique se o Google Play Services (serviço de voz) está instalado e ativo."
            Log.w(TAG, msg)
            onError?.invoke(msg)
            return
        }
        wakeWord = wake.lowercase()
        continuous = true
        _state.value = _state.value.copy(listening = true, mode = mode)
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                _state.value = _state.value.copy(userSpeaking = true)
            }
            override fun onRmsChanged(rmsdB: Float) {
                // BARGE-IN (modo LIVE): se a IA está falando e o usuário faz barulho,
                // ele está interrompendo — para a fala e o agente imediatamente.
                if (_state.value.mode == "LIVE" && bargeInArmed && rmsdB > BARGE_IN_RMS_THRESHOLD) {
                    val now = System.currentTimeMillis()
                    if (now - lastBargeInMs > 1200) {
                        lastBargeInMs = now
                        bargeInArmed = false
                        Log.d(TAG, "Barge-in detectado (RMS $rmsdB)")
                        stopSpeaking()
                        onBargeIn?.invoke()
                    }
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                _state.value = _state.value.copy(userSpeaking = false)
                if (continuous) restart()
            }
            override fun onError(error: Int) {
                _state.value = _state.value.copy(userSpeaking = false)
                if (continuous) restart()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                if (text.isNotBlank()) handleTranscript(text)
                if (continuous) restart()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                if (text.isNotBlank()) handleTranscript(text, partial = true)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        startOnce()
    }

    private fun startOnce() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
    }

    private fun restart() {
        recognizer?.cancel()
        startOnce()
    }

    private fun handleTranscript(text: String, partial: Boolean = false) {
        val lower = VoiceCommandMatcher.normalize(text)
        _state.value = _state.value.copy(lastTranscript = text)

        // 0) ECO DO TTS: se a IA está falando, o microfone pode captar a PRÓPRIA voz
        // da IA (ex.: ela diz "para o processo...") e interpretar como comando de parada,
        // interrompendo a execução "toda hora". Ignoramos TUDO enquanto ela fala.
        if (_state.value.ttsSpeaking) return

        // 1) Comando de parada tem prioridade máxima (mesmo em modo wake word).
        // Correspondência por PALAVRA EXATA — "parabéns" NÃO dispara (testado).
        if (VoiceCommandMatcher.isStopCommand(text, partial)) {
            stopSpeaking()
            onStopCommand?.invoke()
            return
        }

        // 2) LIVE (ligação) e PUSH_TO_TALK: qualquer fala final vira comando.
        // No LIVE não precisa de wake word — é uma conversa contínua.
        if (_state.value.mode == "LIVE" || _state.value.mode == "PUSH_TO_TALK") {
            if (!partial) {
                bargeInArmed = false
                onTranscript?.invoke(text)
            }
            return
        }

        // 3) Wake word: só reage se começar com a palavra-chave
        if (_state.value.mode == "WAKE_WORD" && lower.startsWith(wakeWord)) {
            val command = text.removePrefix(wakeWord).trim()
            if (command.isNotBlank() && !partial) onTranscript?.invoke(command)
        }
    }

    /** Inicia uma ligação contínua (modo LIVE estilo Gemini). */
    fun startLive() = startListening("LIVE")

    fun stopListening() {
        continuous = false
        bargeInArmed = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _state.value = _state.value.copy(listening = false, mode = "OFF")
    }

    fun shutdown() {
        stopListening()
        stopSpeaking()
        tts?.shutdown()
        tts = null
    }
}
