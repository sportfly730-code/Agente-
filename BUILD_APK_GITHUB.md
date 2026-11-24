# Como gerar o APK pelo GitHub Actions

Este projeto usa Gradle 9.0, Android Gradle Plugin 8.7.2 e Java 17. O AIDE que executa o build com Java 8 não consegue iniciar essa configuração. O workflow `.github/workflows/android-apk.yml` compila o projeto em um runner Ubuntu com JDK 17 e instala Android SDK 35, NDK 27.2.12479018 e CMake 3.22.1.

## Passos pelo celular

1. Crie um repositório no GitHub.
2. Envie **todo o conteúdo desta pasta ZIP**, mantendo a pasta `.github/workflows` e seus arquivos. Não envie apenas a pasta `app`.
3. No repositório, abra a aba **Actions**.
4. Se aparecer a solicitação, confirme **I understand my workflows, go ahead and enable them**.
5. Selecione **Build Android APK** e toque em **Run workflow** (ou envie um commit para `main`/`master`).
6. Abra a execução mais recente. Aguarde o trabalho **Build debug APK** terminar com marca verde.
7. Na parte inferior da execução, em **Artifacts**, toque em **CodexAgent-debug-apk** para baixar o ZIP que contém `app-debug.apk`.
8. Extraia o ZIP e instale `app-debug.apk` no Android. Se solicitado, permita a instalação pelo navegador/gerenciador de arquivos.

## Observações

- O workflow não altera os fontes do aplicativo; apenas adiciona a automação de compilação.
- O primeiro build pode demorar porque compila a biblioteca nativa C++ `llama.cpp` para `arm64-v8a`.
- O artefato é uma versão **debug**, assinada para testes pelo processo padrão do Android Gradle Plugin. Para distribuição pública, configure uma chave de assinatura release separadamente.
- Se o build falhar, abra a execução no GitHub Actions e copie o primeiro erro real da etapa **Build APK**; a última linha `Process completed with exit code 1` sozinha não identifica a causa.
