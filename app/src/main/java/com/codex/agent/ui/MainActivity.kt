package com.codex.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.codex.agent.agent.AgentOrchestrator
import com.codex.agent.agent.ToolExecutor
import com.codex.agent.data.db.AppDatabase
import com.codex.agent.data.repository.ProviderRepository
import com.codex.agent.data.repository.SettingsRepository
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.security.SecretsStore
import com.codex.agent.llm.LlmClient
import com.codex.agent.service.AgentForegroundService
import com.codex.agent.shell.EnvironmentSetup
import com.codex.agent.shell.ShellExecutor

class MainActivity : ComponentActivity() {

    private lateinit var requestAudioPermission: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Permissão de microfone para comandos de voz (full-duplex).
        requestAudioPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                android.widget.Toast.makeText(this, "Permissão de microfone negada — comandos de voz desativados.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        val db = AppDatabase.get(applicationContext)
        val settingsRepository = SettingsRepository(db.settingsDao())
        val secretsStore = SecretsStore(applicationContext)
        val providerRepository = ProviderRepository(db, secretsStore)
        val workspaceRepository = WorkspaceRepository(db)
        val llmClient = LlmClient()
        val shellExecutor = ShellExecutor(applicationContext, settingsRepository, workspaceRepository, db)
        val environmentSetup = EnvironmentSetup(applicationContext, shellExecutor, settingsRepository)
        val toolExecutor = ToolExecutor(
            context = applicationContext,
            db = db,
            settingsRepository = settingsRepository,
            workspaceRepository = workspaceRepository,
            shellExecutor = shellExecutor,
            environmentSetup = environmentSetup,
            secretsStore = secretsStore,
            llmClient = llmClient,
            providerRepository = providerRepository
        )
        val orchestrator = AgentOrchestrator(
            context = applicationContext,
            db = db,
            settingsRepository = settingsRepository,
            workspaceRepository = workspaceRepository,
            providerRepository = providerRepository,
            llmClient = llmClient,
            shellExecutor = shellExecutor,
            toolExecutor = toolExecutor
        )

        AgentForegroundService.onStopRequestedCallback = { orchestrator.stopExecution() }
        orchestrator.onAgentStateChanged = { running, status ->
            AgentForegroundService.syncServiceState(applicationContext, running, status)
        }

        // Controle de voz full-duplex: escuta contínua + interrupção por voz + TTS.
        val voiceController = com.codex.agent.voice.VoiceController(applicationContext)
        com.codex.agent.voice.VoiceControllerProvider.attach(voiceController)
        voiceController.initTts()
        voiceController.onStopCommand = {
            orchestrator.stopExecution()
        }
        voiceController.onTranscript = { text ->
            orchestrator.submitUserPrompt(text)
        }
        // Barge-in (modo LIVE): se o usuário falar enquanto a IA responde, para tudo e escuta.
        voiceController.onBargeIn = {
            orchestrator.stopExecution()
        }
        voiceController.onError = { msg ->
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
        // A IA fala a resposta final em voz alta quando termina a tarefa.
        orchestrator.onFinalResponse = { text ->
            voiceController.speak(text)
        }

        setContent {
            val settingsSnapshot by settingsRepository.observe().collectAsState(
                initial = com.codex.agent.data.repository.AppSettingsSnapshot()
            )

            LaunchedEffect(settingsSnapshot.appTheme) {
                val theme = runCatching { AppTheme.valueOf(settingsSnapshot.appTheme) }.getOrDefault(AppTheme.DEFAULT)
                applyThemeColors(theme)
            }

            CodexAgentTheme {
                var unlocked by remember { mutableStateOf(false) }
                var biometricEnabled by remember { mutableStateOf(false) }
                var selectedTab by remember { mutableIntStateOf(0) }

                LaunchedEffect(Unit) {
                    biometricEnabled = settingsRepository.biometricLockEnabled()
                    if (!biometricEnabled) unlocked = true
                    settingsRepository.ensureDefaults()
                    providerRepository.ensureDefaultTemplates()
                    workspaceRepository.ensureDefaultWorkspace()
                    // Retoma tarefa órfã se o app foi morto no meio de uma execução.
                    val resumed = orchestrator.resumeOrphanedSession()
                    if (!resumed) orchestrator.ensureSession()
                    if (resumed) {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "Tarefa interrompida foi retomada automaticamente.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    // Instala a base de conhecimento técnica premium (Android/IPTV) para o RAG (com proteção contra EACCES).
                    runCatching {
                        com.codex.agent.agent.PremiumKnowledgeBase.installDocs(
                            workspaceRepository.active()?.rootPath
                                ?: com.codex.agent.data.model.Constants.DEFAULT_WORKSPACE_ROOT
                        )
                    }
                    settingsRepository.setFirstSetupDone(true)
                }

                if (!unlocked && biometricEnabled) {
                    BiometricLockScreen(enabled = true) { unlocked = true }
                } else {
                    Surface(color = IdeBackground, modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            containerColor = IdeBackground,
                            bottomBar = {
                                NavigationBar(containerColor = IdeSurface) {
                                    NavigationBarItem(
                                        selected = selectedTab == 0,
                                        onClick = { selectedTab = 0 },
                                        icon = { Icon(Icons.Default.SmartToy, contentDescription = "Agente") },
                                        label = { Text("Agente", fontSize = 11.sp) }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 1,
                                        onClick = { selectedTab = 1 },
                                        icon = { Icon(Icons.Default.Terminal, contentDescription = "Terminal") },
                                        label = { Text("Terminal", fontSize = 11.sp) }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 2,
                                        onClick = { selectedTab = 2 },
                                        icon = { Icon(Icons.Default.Folder, contentDescription = "Arquivos") },
                                        label = { Text("Arquivos/APK", fontSize = 11.sp) }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 3,
                                        onClick = { selectedTab = 3 },
                                        icon = { Icon(Icons.Default.Cloud, contentDescription = "APIs") },
                                        label = { Text("APIs", fontSize = 11.sp) }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 4,
                                        onClick = { selectedTab = 4 },
                                        icon = { Icon(Icons.Default.Settings, contentDescription = "Config.") },
                                        label = { Text("Config.", fontSize = 11.sp) }
                                    )
                                }
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier.padding(innerPadding)) {
                                when (selectedTab) {
                                    0 -> AgentChatScreen(
                                        orchestrator = orchestrator,
                                        providerRepository = providerRepository,
                                        environmentSetup = environmentSetup,
                                        llmClient = llmClient
                                    )
                                    1 -> TerminalScreen(shellExecutor = shellExecutor)
                                    2 -> FileExplorerScreen(
                                        workspaceRepository = workspaceRepository,
                                        shellExecutor = shellExecutor,
                                        orchestrator = orchestrator,
                                        onNavigateToChat = { selectedTab = 0 }
                                    )
                                    3 -> ApiSettingsScreen(
                                        settingsRepository = settingsRepository,
                                        providerRepository = providerRepository,
                                        llmClient = llmClient,
                                        orchestrator = orchestrator
                                    )
                                    4 -> AppSettingsScreen(
                                        settingsRepository = settingsRepository
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
