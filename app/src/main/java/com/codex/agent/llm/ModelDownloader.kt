package com.codex.agent.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object ModelDownloader {
    private const val TAG = "ModelDownloader"
    const val MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val MODEL_FILE_NAME = "qwen2.5.gguf"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun downloadModelIfNeeded(
        context: Context,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val finalFile = File(modelsDir, MODEL_FILE_NAME)
        val tmpFile = File(modelsDir, "$MODEL_FILE_NAME.tmp")

        if (finalFile.exists() && verifyGgufHeader(finalFile)) {
            onProgress(1.0f, "Modelo já baixado e verificado.")
            return@withContext finalFile
        }

        var downloadedBytes = if (tmpFile.exists()) tmpFile.length() else 0L

        onProgress(0f, "Iniciando download do Qwen 2.5 GGUF...")

        for (attempt in 0 until 5) {
            try {
                val requestBuilder = Request.Builder().url(MODEL_URL)
                if (downloadedBytes > 0) {
                    requestBuilder.addHeader("Range", "bytes=$downloadedBytes-")
                    Log.i(TAG, "Retomando download a partir de byte $downloadedBytes")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                if (response.code == 416) {
                    Log.w(TAG, "Range não satisfazível (HTTP 416), reiniciando download do byte 0")
                    tmpFile.delete()
                    downloadedBytes = 0L
                    response.close()
                    continue
                }
                val body = response.body ?: throw java.io.IOException("ResponseBody vazio")
                val contentLength = body.contentLength()
                val totalBytes = if (response.code == 206) contentLength + downloadedBytes else contentLength

                val raf = RandomAccessFile(tmpFile, "rw")
                raf.seek(downloadedBytes)

                val buffer = ByteArray(8192)
                val inputStream = body.byteStream()
                var bytesRead: Int

                try {
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress, "Baixando: ${(progress * 100).toInt()}% (${downloadedBytes / (1024 * 1024)}MB)")
                        }
                    }
                } finally {
                    raf.close()
                    inputStream.close()
                    response.close()
                }

                if (verifyGgufHeader(tmpFile)) {
                    if (finalFile.exists()) finalFile.delete()
                    if (tmpFile.renameTo(finalFile)) {
                        onProgress(1.0f, "Download concluído com sucesso!")
                        return@withContext finalFile
                    }
                }
                throw IllegalStateException("Cabeçalho GGUF inválido após download.")
            } catch (e: Exception) {
                Log.w(TAG, "Tentativa ${attempt + 1} de download falhou: ${e.message}")
                if (attempt == 4) throw e
                kotlinx.coroutines.delay(2000L * (attempt + 1))
            }
        }
        throw IllegalStateException("Falha ao baixar modelo GGUF após múltiplas tentativas.")
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
