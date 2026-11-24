package com.codex.agent.llm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

object BundledModelExtractor {
    private const val TAG = "BundledModelExtractor"
    private const val MODEL_NAME = "qwen2.5.gguf"

    fun extractBundledModelIfNeeded(context: Context): File {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val targetFile = File(modelsDir, MODEL_NAME)

        if (targetFile.exists() && verifyGgufHeader(targetFile)) {
            Log.i(TAG, "Modelo embutido já extraído e válido em ${targetFile.absolutePath}")
            return targetFile
        }

        Log.i(TAG, "Extraindo modelo embutido de assets/models/$MODEL_NAME...")
        try {
            context.assets.open("models/$MODEL_NAME").use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (verifyGgufHeader(targetFile)) {
                Log.i(TAG, "Modelo extraído e verificado com sucesso.")
            } else {
                Log.w(TAG, "Aviso: arquivo extraído não possui cabeçalho GGUF válido.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao extrair modelo dos assets (verifique se foi colocado em assets/models/): ${e.message}")
        }

        return targetFile
    }

    private fun verifyGgufHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                magic[0] == 'G'.code.toByte() &&
                magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() &&
                magic[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }
}
