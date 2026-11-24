package com.codex.agent.agent

import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Indexador de codebase baseado em TF-IDF com CHUNKING (sem dependência externa).
 * Indexa cada arquivo em janelas de ~40 linhas com sobreposição de 10 linhas,
 * permitindo que a busca encontre a parte RELEVANTE de arquivos grandes — não só o início.
 *
 * Funciona 100% offline, sem embeddings de LLM.
 */
class CodebaseIndex(private val workspaceRoot: String) {

    data class IndexedChunk(
        val path: String,
        val relativePath: String,
        val chunkIndex: Int,
        val startLine: Int,
        val endLine: Int,
        val text: String,
        val termFreq: Map<String, Int>,
        val norm: Double
    )

    private var index: List<IndexedChunk> = emptyList()
    private var idf: Map<String, Double> = emptyMap()
    private var totalDocs: Int = 0
    val root: String = workspaceRoot

    fun isStale(currentRoot: String): Boolean = currentRoot != root || index.isEmpty()

    private val ignoredDirs = setOf(
        ".git", ".codex", ".codex_git", "node_modules", "build", ".gradle", "__pycache__",
        ".codex_logs", ".codex_observability", ".idea", "target", "out", "dist",
        "apk_analysis", "shelby_apktool", "shelby_jadx", "platform34_extract"
    )

    private val MAX_INDEXED_FILES = 400
    private val CHUNK_LINES = 40
    private val OVERLAP_LINES = 10
    private val MAX_CHUNKS_PER_FILE = 80

    private val textExtensions = setOf(
        "kt", "java", "xml", "json", "yml", "yaml", "md", "txt", "gradle",
        "kts", "properties", "smali", "py", "js", "ts", "sh", "bash",
        "c", "cpp", "h", "hpp", "rs", "go", "swift", "m", "mm"
    )

    /** Reconstrói o índice (por chunk) do workspace. */
    fun buildIndex() {
        val chunks = mutableListOf<IndexedChunk>()
        val docFreq = mutableMapOf<String, Int>()

        File(workspaceRoot).walkTopDown()
            .onEnter { dir -> dir.name !in ignoredDirs && !dir.name.startsWith(".") }
            .filter { it.isFile && it.extension.lowercase() in textExtensions && it.length() < 500_000L }
            .take(MAX_INDEXED_FILES)
            .forEach { file ->
                val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
                if (lines.isEmpty()) return@forEach
                val relativePath = file.relativeTo(File(workspaceRoot)).path
                var start = 0
                var chunkIdx = 0
                while (start < lines.size && chunkIdx < MAX_CHUNKS_PER_FILE) {
                    val end = minOf(start + CHUNK_LINES, lines.size)
                    val chunkText = lines.subList(start, end).joinToString("\n")
                    val terms = tokenize(chunkText)
                    if (terms.isNotEmpty()) {
                        val tf = terms.groupingBy { it }.eachCount()
                        chunks.add(IndexedChunk(
                            path = file.absolutePath,
                            relativePath = relativePath,
                            chunkIndex = chunkIdx,
                            startLine = start + 1,
                            endLine = end,
                            text = chunkText.take(600),
                            termFreq = tf,
                            norm = 0.0
                        ))
                        tf.keys.forEach { term -> docFreq[term] = (docFreq[term] ?: 0) + 1 }
                    }
                    if (end >= lines.size) break
                    start = end - OVERLAP_LINES
                    chunkIdx++
                }
            }

        totalDocs = chunks.size
        if (totalDocs == 0) {
            index = emptyList()
            idf = emptyMap()
            return
        }

        idf = docFreq.mapValues { (_, df) -> ln(totalDocs.toDouble() / df) + 1.0 }

        index = chunks.map { chunk ->
            var sumSq = 0.0
            chunk.termFreq.forEach { (term, freq) ->
                val tfidf = freq.toDouble() * (idf[term] ?: 1.0)
                sumSq += tfidf * tfidf
            }
            chunk.copy(norm = sqrt(sumSq))
        }
    }

    /**
     * Busca os top-K CHUNKS mais relevantes (no máximo um por arquivo).
     * Retorna relativePath, score, trecho do código E intervalo de linhas.
     */
    fun search(query: String, topK: Int = 5): List<SearchResult> {
        if (index.isEmpty()) buildIndex()
        if (index.isEmpty()) return emptyList()

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return emptyList()

        val queryTf = queryTerms.groupingBy { it }.eachCount()
        val queryVector = queryTf.mapValues { (term, freq) -> freq.toDouble() * (idf[term] ?: 1.0) }
        val queryNorm = sqrt(queryVector.values.sumOf { it * it })
        if (queryNorm == 0.0) return emptyList()

        // Pontua todos os chunks
        val scored = index.map { chunk ->
            var dot = 0.0
            queryVector.forEach { (term, qWeight) ->
                val docWeight = (chunk.termFreq[term] ?: 0).toDouble() * (idf[term] ?: 1.0)
                dot += qWeight * docWeight
            }
            val similarity = if (chunk.norm == 0.0) 0.0 else dot / (queryNorm * chunk.norm)
            chunk to similarity
        }.filter { it.second > 0.05 }

        // No máximo um chunk por arquivo: pega o melhor de cada
        val bestPerFile = scored.groupBy { it.first.relativePath }
            .mapValues { (_, list) -> list.maxByOrNull { it.second }!! }
            .values
            .sortedByDescending { it.second }
            .take(topK)

        return bestPerFile.map { (chunk, score) ->
            SearchResult(
                relativePath = chunk.relativePath,
                absolutePath = chunk.path,
                score = score,
                preview = chunk.text,
                startLine = chunk.startLine,
                endLine = chunk.endLine
            )
        }
    }

    data class SearchResult(
        val relativePath: String,
        val absolutePath: String,
        val score: Double,
        val preview: String,
        val startLine: Int = 1,
        val endLine: Int = 0
    )

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9_./-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it.length <= 40 }
            .filter { it !in STOP_WORDS }
    }

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "had",
            "her", "was", "one", "our", "out", "has", "his", "how", "its", "may",
            "new", "now", "old", "see", "two", "who", "boy", "did", "get", "let",
            "put", "say", "she", "too", "use", "import", "package", "class", "fun",
            "val", "var", "return", "null", "true", "false", "this", "that", "with",
            "from", "into", "override", "private", "public", "protected", "abstract"
        )
    }
}
