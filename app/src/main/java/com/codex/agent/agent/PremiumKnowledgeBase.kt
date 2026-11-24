package com.codex.agent.agent

/**
 * Base de conhecimento técnica especializada (nível premium) para desenvolvimento Android,
 * com foco em IPTV/streaming legítimo. Este conteúdo é injetado no system prompt do agente
 * e serve como referência autoritativa para geração de código.
 *
 * Quatro pilares:
 *  1. Arquitetura de Prompts
 *  2. Base de Conhecimento (RAG)
 *  3. Stack Tecnológica Especializada
 *  4. Fluxo de Validação
 */
object PremiumKnowledgeBase {

    val SYSTEM_ADDENDUM = """
        === ESPECIALISTA ANDROID / IPTV (NÍVEL PREMIUM) ===
        Você é um engenheiro Android sênior especializado em mídia e apps de streaming legítimos
        (players, agregadores de conteúdo próprio, soluções white-label).

        [PILAR 1 — ARQUITETURA MODERNA DE CÓDIGO]
        - Kotlin primeiro. Jetpack Compose para UI moderna; XML otimizado para Android TV.
        - MVVM + Clean Architecture: data (repository/remote/local), domain (use cases), presentation (ViewModel/UiState).
        - Injeção de dependência com Dagger Hilt (@HiltAndroidApp, @AndroidEntryPoint, @Inject, @Module/@Provides).
        - Coroutines + Flow para assincronia; StateFlow para UiState; sealed class para eventos.
        - Room para persistência local; DataStore para preferências; coil para imagens.
        - Convenções: nomes claros, funções curtas, single responsibility, tratamento de erro tipado.

        [PILAR 2 — RAG / CONHECIMENTO]
        - Use search_semantic antes de gerar código para consultar docs no workspace (.codex/knowledge).
        - Prefira APIs estáveis e documentadas; nunca invente classes ou métodos de bibliotecas.
        - Se não tem certeza de uma API, verifique com web_tool em documentação oficial.

        [PILAR 3 — STACK DE MÍDIA (CORE IPTV)]
        - Motor: androidx.media3:media3-exoplayer + media3-exoplayer-hls + media3-exoplayer-dash
          + media3-ui + media3-session. Media3 é o padrão-ouro (sucessor do ExoPlayer legado).
        - Protocolos: HLS (.m3u8) via HlsMediaSource, MPEG-DASH (.mpd) via DashMediaSource,
          RTMP via media3-exoplayer-rtmp (quando disponível), Progressive via ProgressiveMediaSource.
        - Adaptive Bitrate (ABR): DefaultTrackSelector com adaptiveTrackSelectionFactory;
          ExoPlayer ajusta qualidade automaticamente conforme largura de banda.
        - Decodificação: hardware por padrão (MediaCodec); fallback software quando necessário.
          Use MediaCodecVideoRenderer com ajuste de buffer (setTargetBufferMs / LivePlaybackSpeedControl).
        - Buffers: DefaultLoadControl.Builder() com setBufferDurationsMs(min, max, playback, rebuffer)
          para streams ao vivo (menos latência) vs VOD (mais buffer).
        - Formatos de playlist: M3U/M3U8 (parsing de #EXTM3U, #EXTINF, #EXT-X-STREAM-INF),
          Xtream Codes API (login via /player_api.php?username=&password=; VOD, séries, EPG),
          XMLTV (parse de <programme> com tag <epg>, canal por <channel id>).
        - EPG: guia de programação; mapear tvg-id do M3U para <channel id> do XMLTV.

        [PILAR 4 — MULTI-TELA: MOBILE + ANDROID TV / FIRE TV]
        - Android TV / Fire TV: use androidx.leanback (LeanbackPreferenceFragmentCompat, BrowseFragment)
          OU Compose for TV (androidx.tv:tv-foundation, tv-material).
        - Navegação por D-Pad: foco visual claro (Modifier.focusable(), onFocusChanged, indicador de foco),
          direções de foco explícitas (focusRestorer / custom focus order).
        - Layouts responsivos: resource qualifiers (values-sw600dp, layout-television),
          fontes maiores, safe-area (overscan) com android:fitsSystemWindows e margens de 5% (TV overscan).
        - Controle remoto: tratar KEYCODE_DPAD_*, KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_BACK via onKeyDown;
          suporte a Google Assistant / voice search (Leanback).
        - Manifesto TV: <uses-feature android:name="android.software.leanback" android:required="true"/>,
          <uses-feature android:name="android.hardware.touchscreen" android:required="false"/>,
          category LAUNCHER + LEANBACK_LAUNCHER.

        [FLUXO DE VALIDAÇÃO — OBRIGATÓRIO]
        1. Gerar código com as libs corretas e versões estáveis (Media3 1.x).
        2. Gradle sync / build_apk no dispositivo — corrigir erros de compilação hingga sucesso.
        3. Instalar APK (android_system_tool install_apk) e abrir.
        4. read_logcat para capturar crashes (FATAL/Exception) e corrigir.
        5. Testar funcionalmente: lista de canais carrega, player toca HLS/DASH, EPG aparece, D-Pad funciona.
        6. Só declarar CONCLUÍDO após build + install + logcat limpo + teste funcional.
    """.trimIndent()

    /** Documentação técnica gravada no workspace para consulta via RAG. */
    val TECH_DOCS: Map<String, String> = mapOf(
        "media3_setup" to """
            # Media3 / ExoPlayer — Setup (IPTV)
            dependencies:
              implementation("androidx.media3:media3-exoplayer:1.4.1")
              implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
              implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
              implementation("androidx.media3:media3-ui:1.4.1")
              implementation("androidx.media3:media3-session:1.4.1")
            Player:
              val player = ExoPlayer.Builder(context).build()
              val mediaItem = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
              player.setMediaItem(mediaItem)
              player.prepare(); player.playWhenReady = true
            HLS: HlsMediaSource.Factory(DefaultDataSource.Factory(context)).createMediaSource(mediaItem)
            DASH: DashMediaSource.Factory(DefaultDataSource.Factory(context)).createMediaSource(mediaItem)
            ABR: DefaultTrackSelector(context) — ajuste automático por largura de banda.
            Buffers live: DefaultLoadControl.Builder().setBufferDurationsMs(1500, 5000, 1000, 1500).build()
            Release: player.release() no onDestroy; PlayerView.player = player.
        """.trimIndent(),
        "m3u_parser" to """
            # Parser M3U / M3U8
            - Ignora linhas iniciadas com '#', exceto #EXTINF (nome) e #EXT-X-STREAM-INF (variant).
            - #EXTINF:-1 tvg-id="cnn.us" tvg-logo="url" group-title="News",CNN HD
              → proxima linha não-'#' é a URL do stream.
            - Extrair atributos com regex: tvg-id="([^"]*)" tvg-logo="([^"]*)" group-title="([^"]*)"
            - Modelo: Channel(id, name, url, logo, group, tvgId)
            - Suportar URLs relativas (resolver contra base do M3U).
        """.trimIndent(),
        "xtream_api" to """
            # Xtream Codes API
            Endpoint: http://host:port/player_api.php?username=U&password=P&action=...
            - action=get_live_streams → List<Channel> (live)
            - action=get_vod_streams → filmes (stream_id, title, cover, container)
            - action=get_series → séries
            - action=get_epg&stream_id=X → programação
            - get_series_info&series_id=X → temporadas/episódios
            Autenticação: user_info → {"user_info":{"active":true,...}}. Sempre validar active.
            URL de stream: http://host:port/live/U/P/stream_id.ts
        """.trimIndent(),
        "xmltv_epg" to """
            # XMLTV / EPG
            Estrutura:
              <tv><channel id="cnn.us"><display-name>CNN</display-name></channel>
              <programme start="20240101120000 +0000" stop="..." channel="cnn.us">
                <title>Notícias</title><desc>...</desc>
              </programme></tv>
            Parse: XmlPullParser (parser de streaming, eficiente em arquivos grandes).
            Mapear channel[@id] == M3U tvg-id. Ordenar programmes por start.
        """.trimIndent(),
        "android_tv" to """
            # Android TV / Fire TV
            Manifesto:
              <uses-feature android:name="android.software.leanback" android:required="true"/>
              <uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
              <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
            Compose for TV:
              implementation("androidx.tv:tv-foundation:1.0.0")
              implementation("androidx.tv:tv-material:1.0.0")
            Foco: Modifier.focusable().onFocusChanged{ if(it.isFocused) ... }
              Indicador: borda/escala quando focado. Definir focus order com FocusProperties.
              D-Pad: onKeyDown KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT, KEYCODE_MEDIA_PLAY_PAUSE.
            Overscan: margens ~5% (48dp) nas bordas; testar em TV emulator.
            Leanback alternativo: BrowseSupportFragment + CardPresenter (ImageViewCard).
        """.trimIndent(),
        "hilt_mvvm" to """
            # Hilt + MVVM + Clean Architecture
            @HiltAndroidApp class App : Application()
            @AndroidEntryPoint class MainActivity : ComponentActivity()
            @Module @InstallIn(SingletonComponent::class) object AppModule {
              @Provides @Singleton fun provideRepo(...): Repository = ...
            }
            @Inject lateinit var viewModel: MainViewModel  (ou @HiltViewModel)
            Camadas:
              data/remote (Api via Retrofit/OkHttp), data/local (Room), data/repository ( RepositoryImpl )
              domain/model, domain/usecase (GetChannelsUseCase)
              presentation (ViewModel com StateFlow<UiState> + events)
            UiState: data class UiState(val channels: List<Channel>, val loading: Boolean, val error: String?)
            ViewModel: viewModelScope.launch { getChannels().collect { _uiState.update {...} } }
        """.trimIndent()
    )

    /** Grava a base de conhecimento no workspace para o RAG indexar. */
    fun installDocs(workspaceRoot: String): List<String> {
        return runCatching {
            val dir = java.io.File(workspaceRoot, ".codex/knowledge").apply { mkdirs() }
            TECH_DOCS.map { (name, content) ->
                val f = java.io.File(dir, "$name.md")
                f.writeText("# $name\n\n$content")
                f.absolutePath
            }
        }.getOrDefault(emptyList())
    }
}
