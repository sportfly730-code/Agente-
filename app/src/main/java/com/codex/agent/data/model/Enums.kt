package com.codex.agent.data.model

/**
 * Enumerações centrais do domínio, compartilhadas por toda a aplicação.
 * Room converte automaticamente enums para String (nome) nas entidades.
 */

/** Tipo de protocolo que o [com.codex.agent.llm.LlmClient] usará para falar com o provedor. */
enum class ProviderKind {
    /** Endpoint compatível com OpenAI Chat Completions (OpenAI, Gemini-openai, DeepSeek, OpenRouter, Groq, Ollama, LM Studio, proxies). */
    OPENAI_COMPATIBLE,

    /** Protocolo nativo /v1/messages da Anthropic (adaptador próprio, com tools nativas). */
    ANTHROPIC_NATIVE,

    /** Motor de inferência local GGUF via JNI / llama.kt (Qwen 2.5 0.5B/1.5B). */
    LOCAL_LLAMA
}

/** Modo de operação do agente para cada tarefa. */
enum class AgentRunMode {
    /** Executa ferramentas, instala pacotes e compila sem pedir confirmação (padrão). */
    YOLO,

    /** Gera primeiro o plano técnico e aguarda o clique em "Executar Plano". */
    PLAN_FIRST
}

/** Motor de execução de comandos shell. */
enum class ExecutionMode {
    /** Prioriza Termux Bridge; cai para ProcessBuilder interno quando indisponível. */
    HYBRID,

    /** Força apenas o Termux Bridge (falha se Termux não estiver instalado/ativado). */
    TERMUX_ONLY,

    /** Força apenas o shell interno (filesDir/usr), ignorando o Termux. */
    INTERNAL_ONLY
}

/** Papéis das mensagens trocadas com o LLM e persistidas na sessão. */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    /** Resultado de tool call (role "tool" no protocolo OpenAI). */
    TOOL,
    /** Mensagem injetada pelo orquestrador (anti-loop, guidance, resultado de compaction). */
    SYSTEM_INJECTION
}

/** Ciclo de vida da sessão do agente. */
enum class SessionStatus {
    IDLE,
    RUNNING,
    /** PLAN_FIRST: plano gerado, aguardando aprovação do usuário. */
    AWAITING_PLAN_APPROVAL,
    DONE,
    STOPPED,
    ERROR
}

/** Estado de um processo em background gerenciado pelo Terminal Live. */
enum class JobStatus {
    RUNNING,
    DONE,
    FAILED,
    KILLED
}

/** Origem do checkpoint (Git Shadow). */
enum class CheckpointKind {
    /** Commit oculto automático antes de cada nova solicitação do usuário. */
    AUTO_SHADOW,

    /** Criado manualmente pelo usuário na UI. */
    MANUAL,

    /** Criado pela própria IA via ferramenta manage_checkpoints. */
    AGENT
}
