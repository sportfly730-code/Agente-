package com.codex.agent.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Testes unitários do indexador TF-IDF (RAG offline).
 */
class CodebaseIndexTest {

    @Test
    fun buildIndexAndSearchFindsRelevantFile() {
        val tempDir = createTempDir("codex-index-test")
        try {
            File(tempDir, "auth.kt").writeText(
                """
                package com.example
                class AuthManager {
                    fun login(username: String, password: String): Boolean {
                        return checkCredentials(username, password)
                    }
                    fun logout() { session.clear() }
                }
                """.trimIndent()
            )
            File(tempDir, "player.kt").writeText(
                """
                package com.example
                class VideoPlayer {
                    fun play(url: String) { exoPlayer.setMediaItem(url) }
                    fun pause() { exoPlayer.pause() }
                }
                """.trimIndent()
            )

            val index = CodebaseIndex(tempDir.absolutePath)
            index.buildIndex()

            val authResults = index.search("login authentication credentials password", 5)
            assertTrue("auth.kt deve ser o mais relevante para query de login",
                authResults.firstOrNull()?.relativePath?.contains("auth") == true)

            val playerResults = index.search("video player play pause media", 5)
            assertTrue("player.kt deve ser o mais relevante para query de vídeo",
                playerResults.firstOrNull()?.relativePath?.contains("player") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun chunkingFindsRelevantSectionInLargeFile() {
        val tempDir = createTempDir("codex-chunk-test")
        try {
            // Arquivo grande: a parte relevante está no MEIO/FIM, não no início.
            val sb = StringBuilder()
            repeat(100) { sb.appendLine("// filler line $it unrelated content here") }
            sb.appendLine("fun decryptPayload(cipherText: ByteArray, secretKey: SecretKeySpec): String {")
            sb.appendLine("    val cipher = Cipher.getInstance(\"AES/GCM/NoPadding\")")
            sb.appendLine("    return decrypt(cipher, cipherText, secretKey)")
            sb.appendLine("}")
            repeat(100) { sb.appendLine("// more filler $it nothing relevant") }
            File(tempDir, "crypto.kt").writeText(sb.toString())

            val index = CodebaseIndex(tempDir.absolutePath)
            index.buildIndex()
            val results = index.search("decrypt cipher secret key payload", 3)
            assertTrue("deve encontrar crypto.kt", results.firstOrNull()?.relativePath?.contains("crypto") == true)
            val found = results.first()
            assertTrue("preview deve conter a seção relevante, não o início", found.preview.contains("decrypt", ignoreCase = true))
            assertTrue("endLine deve ser > 1 (chunking real)", found.endLine > 1)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun searchWithEmptyQueryReturnsEmpty() {
        val tempDir = createTempDir("codex-index-empty")
        try {
            File(tempDir, "a.kt").writeText("class A { fun foo() {} }")
            val index = CodebaseIndex(tempDir.absolutePath)
            index.buildIndex()
            assertTrue(index.search("", 5).isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
