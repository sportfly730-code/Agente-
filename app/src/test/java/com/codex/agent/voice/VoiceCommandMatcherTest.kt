package com.codex.agent.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes da lógica de comandos de voz (sem Android — classe pura). */
class VoiceCommandMatcherTest {

    @Test
    fun detectsStopCommands() {
        assertTrue(VoiceCommandMatcher.isStopCommand("pare", false))
        assertTrue(VoiceCommandMatcher.isStopCommand("stop", false))
        assertTrue(VoiceCommandMatcher.isStopCommand("para agora", false))
        assertTrue(VoiceCommandMatcher.isStopCommand("para tudo", false))
        assertTrue(VoiceCommandMatcher.isStopCommand("pode parar", false))
        assertTrue(VoiceCommandMatcher.isStopCommand("cancelar", false))
        assertTrue(VoiceCommandMatcher.isStopCommand("pare a execucao", false))
    }

    @Test
    fun doesNotTriggerOnCommonPortugueseWords() {
        // REGRESSÃO: frases normais com "para"/"sai" NÃO devem parar a execução.
        assertFalse(VoiceCommandMatcher.isStopCommand("parabéns", false))
        assertFalse(VoiceCommandMatcher.isStopCommand("para o projeto", false))
        assertFalse(VoiceCommandMatcher.isStopCommand("para você", false))
        assertFalse(VoiceCommandMatcher.isStopCommand("sai da pasta", false))
        assertFalse(VoiceCommandMatcher.isStopCommand("lista os arquivos para mim", false))
        assertFalse(VoiceCommandMatcher.isStopCommand("parede", false))
        assertFalse(VoiceCommandMatcher.isStopCommand("cria um app para tv", false))
    }

    @Test
    fun ignoresPartialResults() {
        assertFalse(VoiceCommandMatcher.isStopCommand("pa", true))
        assertFalse(VoiceCommandMatcher.isStopCommand("pare", true))
        assertFalse(VoiceCommandMatcher.isStopCommand("stop", true))
    }

    @Test
    fun normalizesAccentsAndCase() {
        assertTrue(VoiceCommandMatcher.normalize("PÁRA AGORA") == "para agora")
        assertTrue(VoiceCommandMatcher.isStopCommand("PARE", false))
        assertTrue(VoiceCommandMatcher.isStopCommand("CANCELAR", false))
    }
}
