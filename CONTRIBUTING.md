# Contribuindo para o Codex Agent 🇧🇷

Obrigado por querer ajudar! Toda contribuição é bem-vinda — código, documentação, testes,
traduções, ideias ou até um ⭐ no repo.

## Formas de contribuir

### ⭐ Divulgue
Se o projeto te ajudou, dê uma **star** e compartilhe com outros devs. Isso ajuda muito a
comunidade a encontrar o projeto.

### 🐛 Reportar bugs
Use o template **🐛 Relatório de bug** nas Issues. Inclua:
- Dispositivo, versão do Android e commit do app
- Provedor de IA e modelo usado
- Passos para reproduzir
- Trecho do `logcat` (se houver)

### 💡 Sugerir funcionalidades
Use o template **💡 Sugestão de funcionalidade**. Descreva o problema que a ideia resolve,
não só a solução.

### 🔧 Contribuir com código
1. Faça um **fork** do repo.
2. Crie uma branch: `git checkout -b feature/minha-feature`
3. Faça suas mudanças seguindo o estilo do código existente.
4. Rode os testes:
   ```bash
   ./gradlew :app:testDebugUnitTest :app:assembleDebug
   ```
5. Commit: `git commit -m "feat: descrição curta"`
6. Push: `git push origin feature/minha-feature`
7. Abra um **Pull Request** descrevendo o que mudou e por quê.

## Regras importantes

- **Nunca commite API keys**, tokens, credenciais ou dados pessoais.
- **Não adicione** funcionalidades de ataque, invasão, pirataria, cheats, DDoS ou vigilância.
  O projeto é de engenharia reversa e automação legítima.
- Guardrails de segurança (`ShellSafetyGuard`, path traversal, anti-injeção) devem permanecer ativos.
- Mudanças em ferramentas devem atualizar `ToolDefinitions.kt` **e** `ToolExecutor.kt`.

## Estrutura rápida

| Pasta | O que faz |
|---|---|
| `app/src/main/java/com/codex/agent/agent/` | Loop do agente, ferramentas, ToolHub |
| `app/src/main/java/com/codex/agent/llm/` | Clientes de API (OpenAI-compatible + Anthropic) |
| `app/src/main/java/com/codex/agent/shell/` | Execução e guardrails de shell |
| `app/src/main/java/com/codex/agent/ui/` | Telas Compose |
| `app/src/test/` | Testes unitários |

## Dúvidas
Abra uma **Discussion** ou uma Issue. Respondo em português e em inglês. 🚀
