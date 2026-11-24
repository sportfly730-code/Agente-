package com.codex.agent.data.model

/**
 * Constantes globais compartilhadas por todas as camadas (LLM, shell, orquestrador, UI).
 */
object Constants {

    /** Raiz padrão dos projetos do usuário no armazenamento compartilhado. */
    const val DEFAULT_WORKSPACE_ROOT = "/sdcard/CodexProjects"

    /** Diretório de controle da ponte com o Termux (visível para os dois apps). */
    const val TERMUX_BRIDGE_DIR = "/sdcard/.codex_agent_bridge"

    /** Diretório, relativo à raiz do workspace, onde ficam os logs integrais dos jobs. */
    const val WORKSPACE_LOGS_DIR = ".codex_logs"

    /** Diretório, relativo à raiz do workspace, com ferramentas dinâmicas (*.json). */
    const val CUSTOM_TOOLS_DIR = ".codex/custom_tools"

    /** Diretório, relativo à raiz do workspace, para saída de decompilação de APKs. */
    const val APK_ANALYSIS_DIR = ".codex/apk_analysis"

    /** Nome do arquivo de memória persistente por workspace. */
    const val AGENT_MEMORY_FILE = "AGENT.md"

    /** Nome do arquivo onde o agente registra dependências instaladas. */
    const val DEPENDENCIES_FILE = ".codex/dependencies.md"

    /** Limite padrão de caracteres enviados ao LLM (início + fim preservados). */
    const val OUTPUT_TRUNCATION_LIMIT = 8000

    /** Limite padrão de passos autônomos por tarefa. */
    const val DEFAULT_MAX_STEPS = 50

    /** Falhas repetidas idênticas consecutivas antes do guardrail anti-loop atuar. */
    const val ANTI_LOOP_THRESHOLD = 3

    /** Proporção do context window em que dispara a auto-compactação. */
    const val COMPACTION_THRESHOLD = 0.75

    /** Aproximação de tokens por caractere usada na estimativa sem tokenizer. */
    const val CHARS_PER_TOKEN = 4.0

    /** Timeout padrão (segundos) para comandos shell quando não especificado. */
    const val DEFAULT_SHELL_TIMEOUT_SEC = 300

    /** Variáveis de ambiente não-interativas injetadas em toda execução. */
    const val NON_INTERACTIVE_ENV =
        "CI=true DEBIAN_FRONTEND=noninteractive PIP_NO_INPUT=1 npm_config_yes=true GIT_TERMINAL_PROMPT=0"

    /** Limites de heap JVM seguros para celular (javac/gradle/jadx dentro do Termux). */
    const val JAVA_HEAP_OPTS = "JAVA_OPTS=-Xmx1536m _JAVA_OPTIONS=-Xmx1536m"

    /** Pacote do Termux e action da RUN_COMMAND. */
    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"

    /** Chaves extras usadas no Intent da RUN_COMMAND. */
    const val TERMUX_EXTRA_COMMAND = "com.termux.RUN_COMMAND_PATH"
    const val TERMUX_EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val TERMUX_EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val TERMUX_EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    /** Nome do script wrapper criado no diretório de bridge para capturar stdout/stderr/exit_code. */
    const val BRIDGE_RUNNER_SCRIPT = "run_job.sh"

    /** Arquivos de resultado gerados pelo runner da ponte. */
    const val BRIDGE_STDOUT_FILE = "stdout.log"
    const val BRIDGE_STDERR_FILE = "stderr.log"
    const val BRIDGE_EXIT_FILE = "exit_code"

    /** Flag criada pelo setup do Termux para sinalizar que allow-external-apps foi ativado. */
    const val TERMUX_BRIDGE_READY_FLAG = "/sdcard/.codex_agent_bridge/.ready"

    /** Package name do Shizuku. */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Sufixo de authority exigido pelo ShizukuProvider. */
    const val SHIZUKU_AUTHORITY_SUFFIX = ".shizuku"

    /** Filtragem de bibliotecas padrão na análise de APKs (economia de tokens). */
    val APK_STD_LIB_PREFIXES = listOf(
        "androidx.", "com.google.", "kotlin.", "kotlinx.", "okhttp3.", "okio.",
        "org.jetbrains.", "org.intellij.", "org.jetbrains.kotlin.", "android.support.",
        "com.squareup.", "io.reactivex.", "org.reactivestreams.", "org.slf4j.",
        "javax.", "java.", "android."
    )

    /** Regex para extração de URLs/endpoints em strings de APKs (shell-safe, sem apóstrofo/cifrão). */
    const val URL_REGEX =
        "(?:https?|wss?|ftp)://[A-Za-z0-9\\-._~:/?#[\\]@!&()*+,;=%]+"
}
