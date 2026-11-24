package com.codex.agent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.codex.agent.data.entity.ProviderConfigEntity
import com.codex.agent.llm.DiscoveredModel
import com.codex.agent.llm.LlmClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.agent.agent.AgentOrchestrator
import com.codex.agent.data.entity.ChatMessageEntity
import com.codex.agent.data.model.MessageRole
import com.codex.agent.data.repository.ProviderRepository
import com.codex.agent.shell.EnvironmentSetup
import kotlinx.coroutines.launch

/**
 * Tela principal do agente: chat com streaming ao vivo, cards expansíveis de ferramentas,
 * anexo de imagens (visão), toggle YOLO/PLAN, contador de tokens e rollback.
 */
@Composable
fun AgentChatScreen(
    orchestrator: AgentOrchestrator,
    providerRepository: ProviderRepository,
    environmentSetup: EnvironmentSetup,
    llmClient: LlmClient
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val messages by orchestrator.observeMessages().collectAsState(initial = emptyList())
    val uiState by orchestrator.uiState.collectAsState()
    val streamingText by orchestrator.streamingText.collectAsState()
    val streamingReasoning by orchestrator.streamingReasoning.collectAsState()
    val activeProvider by providerRepository.observeDefault().collectAsState(initial = null)
    val providers by providerRepository.observeAll().collectAsState(initial = emptyList())

    var inputPrompt by remember { mutableStateOf("") }
    var attachedImageBase64 by remember { mutableStateOf<String?>(null) }
    var attachedApkPath by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showModePicker by remember { mutableStateOf(false) }
    var showEffortPicker by remember { mutableStateOf(false) }
    var selectedEffort by remember { mutableStateOf("Default") }
    val listState = rememberLazyListState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            attachedImageBase64 = encodeUriToBase64(context, uri)
            Toast.makeText(context, "Imagem anexada para análise visual.", Toast.LENGTH_SHORT).show()
        }
    }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val file = copyUriToWorkspaceTemp(context, uri)
            if (file != null && file.exists()) {
                attachedApkPath = file.absolutePath
                Toast.makeText(context, "APK anexado (${formatFileSize(file.length())}): ${file.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(messages.size, streamingText, uiState.isRunning) {
        if (messages.isNotEmpty()) {
            val target = if (uiState.isRunning && streamingText.isNotBlank()) messages.size else messages.size - 1
            runCatching { listState.animateScrollToItem(target) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IdeBackground)
    ) {
        // ---------------- Barra superior ----------------
        Surface(color = IdeSurface, border = BorderStroke(1.dp, IdeBorder), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeProvider?.let { "${it.name} · ${it.mainModel}" } ?: "Configure sua API (aba APIs)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = IdePrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "WS: ${uiState.workspacePath.substringAfterLast('/')}",
                            fontSize = 11.sp,
                            color = IdeTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = uiState.runMode == com.codex.agent.data.model.AgentRunMode.YOLO,
                            onClick = {
                                scope.launch {
                                    orchestrator.setRunMode(
                                        if (uiState.runMode == com.codex.agent.data.model.AgentRunMode.YOLO)
                                            com.codex.agent.data.model.AgentRunMode.PLAN_FIRST
                                        else com.codex.agent.data.model.AgentRunMode.YOLO
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = if (uiState.runMode == com.codex.agent.data.model.AgentRunMode.YOLO) "YOLO" else "PLAN",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val result = orchestrator.rollbackLastTurn()
                                    Toast.makeText(context, result.take(120), Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.History, contentDescription = "Rollback Git", tint = IdeWarning, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { scope.launch { orchestrator.clearConversation() } },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Limpar Chat", tint = IdeTextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                if (uiState.isRunning || uiState.totalTokensUsed > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (uiState.isRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = IdeSecondary)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = uiState.statusText,
                                fontSize = 11.sp,
                                color = if (uiState.isRunning) IdeSecondary else IdeTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "${String.format(java.util.Locale.getDefault(), "%,d", uiState.totalTokensUsed)} tokens",
                            fontSize = 11.sp,
                            color = IdeTextSecondary
                        )
                    }
                }
            }
        }

        PermissionCheckBanner(environmentSetup = environmentSetup)

        // ---------------- Mensagens ----------------
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item { EmptyWelcomeCard(onQuickPrompt = { inputPrompt = it }) }
            }
            items(messages, key = { it.id }) { message ->
                ChatBubbleItem(
                    message = message,
                    onCopyText = { text ->
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "Copiado!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            if (uiState.isRunning && streamingText.isNotBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = IdeSurface),
                        border = BorderStroke(1.dp, IdeSecondary.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (streamingReasoning.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Psychology, contentDescription = null, tint = IdeTextSecondary, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Raciocínio…", fontSize = 10.sp, color = IdeTextSecondary)
                                }
                                Text(
                                    text = streamingReasoning.takeLast(1200),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = IdeTextSecondary,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(
                                text = streamingText.takeLast(4000),
                                color = IdeTextPrimary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            }
        }

        // ---------------- Barra de aprovação de plano ----------------
        if (uiState.awaitingPlanApproval) {
            Surface(
                color = IdeSurfaceVariant,
                border = BorderStroke(1.dp, IdePrimary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Plano pronto. Autorizar execução autônoma?",
                        fontSize = 12.sp,
                        color = IdeTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { orchestrator.approveAndExecutePlan() },
                        colors = ButtonDefaults.buttonColors(containerColor = IdeSecondary)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Executar Plano", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ---------------- Input ----------------
        Surface(color = IdeSurface, border = BorderStroke(1.dp, IdeBorder), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (attachedImageBase64 != null || attachedApkPath != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                        if (attachedImageBase64 != null) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = IdeSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Imagem anexada (visão)", fontSize = 11.sp, color = IdeSecondary)
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Close, contentDescription = "Remover imagem",
                                tint = IdeError,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { attachedImageBase64 = null }
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        if (attachedApkPath != null) {
                            val apkFile = File(attachedApkPath!!)
                            Icon(Icons.Default.Android, contentDescription = null, tint = IdePrimary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "APK: ${apkFile.name} (${formatFileSize(apkFile.length())})",
                                fontSize = 11.sp,
                                color = IdePrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Close, contentDescription = "Remover APK",
                                tint = IdeError,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { attachedApkPath = null }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = inputPrompt,
                    onValueChange = { inputPrompt = it },
                    placeholder = {
                        Text(
                            "Pergunte qualquer coisa, / para comandos, @ para contexto...",
                            fontSize = 12.sp,
                            color = IdeTextSecondary
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5
                )

                // Control Bar (Build v, Model v, Default v, Send)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    IconButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Anexar Imagem", tint = IdeTextSecondary, modifier = Modifier.size(16.dp))
                    }

                    IconButton(
                        onClick = { apkPickerLauncher.launch("*/*") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Android, contentDescription = "Anexar APK", tint = IdePrimary, modifier = Modifier.size(16.dp))
                    }

                    // Mode Selector (Build / Plan)
                    Box {
                        Surface(
                            color = IdeSurfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, IdeBorder),
                            modifier = Modifier.clickable { showModePicker = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (uiState.runMode == com.codex.agent.data.model.AgentRunMode.YOLO) "Build" else "Plan",
                                    fontSize = 11.sp,
                                    color = IdeTextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = IdeTextSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                        DropdownMenu(expanded = showModePicker, onDismissRequest = { showModePicker = false }) {
                            DropdownMenuItem(
                                text = { Text("Build (Modo YOLO)", fontSize = 12.sp) },
                                onClick = {
                                    scope.launch { orchestrator.setRunMode(com.codex.agent.data.model.AgentRunMode.YOLO) }
                                    showModePicker = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Plan (Aprovação prévia)", fontSize = 12.sp) },
                                onClick = {
                                    scope.launch { orchestrator.setRunMode(com.codex.agent.data.model.AgentRunMode.PLAN_FIRST) }
                                    showModePicker = false
                                }
                            )
                        }
                    }

                    // Model Selector (Vyce AI / GLM 5.3 Flash)
                    Surface(
                        color = IdeSurfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, IdeBorder),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showModelPicker = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = activeProvider?.let { "${it.name} / ${it.mainModel}" } ?: "Configurar API",
                                fontSize = 11.sp,
                                color = IdeTextPrimary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(2.dp))
                            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = IdeTextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }

                    // Effort Level (Default)
                    Box {
                        Surface(
                            color = IdeSurfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, IdeBorder),
                            modifier = Modifier.clickable { showEffortPicker = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = selectedEffort,
                                    fontSize = 11.sp,
                                    color = IdeTextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = IdeTextSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                        DropdownMenu(expanded = showEffortPicker, onDismissRequest = { showEffortPicker = false }) {
                            listOf("Default", "Low", "Medium", "High").forEach { level ->
                                DropdownMenuItem(
                                    text = { Text(level, fontSize = 12.sp) },
                                    onClick = {
                                        selectedEffort = level
                                        showEffortPicker = false
                                    }
                                )
                            }
                        }
                    }

                    // Botão Enviar
                    if (uiState.isRunning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .height(28.dp)
                                .width(90.dp)
                                .background(IdeError, RoundedCornerShape(14.dp))
                                .clickable { orchestrator.stopExecution() }
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Parar", tint = IdeTextPrimary, modifier = Modifier.size(14.dp))
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .height(28.dp)
                                .width(90.dp)
                                .background(if (inputPrompt.isNotBlank() || attachedApkPath != null) IdePrimary else IdeBorder, RoundedCornerShape(14.dp))
                                .clickable {
                                    val rawText = inputPrompt.trim()
                                    val apkPath = attachedApkPath
                                    val image = attachedImageBase64

                                    inputPrompt = ""
                                    attachedImageBase64 = null
                                    attachedApkPath = null

                                    val finalPrompt = if (apkPath != null) {
                                        val apkFile = File(apkPath)
                                        val userInstruction = rawText.ifBlank { "Analise e faça engenharia reversa completa deste APK anexado." }
                                        "Inspecione e analise o APK localizado em '${apkFile.absolutePath}' (${apkFile.name}, tamanho: ${formatFileSize(apkFile.length())}).\nInstruções: $userInstruction"
                                    } else {
                                        rawText
                                    }

                                    if (finalPrompt.isNotBlank()) {
                                        orchestrator.submitUserPrompt(finalPrompt, image)
                                    }
                                }
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Enviar", tint = IdeBackground, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Enviar", fontSize = 11.sp, color = IdeBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerModal(
            providers = providers,
            activeProvider = activeProvider,
            llmClient = llmClient,
            onSelectModel = { selectedProvider, selectedModel ->
                scope.launch {
                    val updated = selectedProvider.copy(mainModel = selectedModel)
                    val id = providerRepository.upsert(updated)
                    providerRepository.setDefault(id)
                    orchestrator.setProvider(id)
                    Toast.makeText(context, "Modelo ativo: ${selectedProvider.name} / $selectedModel", Toast.LENGTH_SHORT).show()
                }
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }
}

@Composable
private fun ModelPickerModal(
    providers: List<ProviderConfigEntity>,
    activeProvider: ProviderConfigEntity?,
    llmClient: LlmClient,
    onSelectModel: (ProviderConfigEntity, String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var modelsMap by remember { mutableStateOf<Map<Long, List<DiscoveredModel>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(providers) {
        isLoading = true
        val map = mutableMapOf<Long, List<DiscoveredModel>>()
        for (provider in providers) {
            if (provider.baseUrl.isNotBlank()) {
                val res = llmClient.fetchAvailableModels(provider)
                res.onSuccess { list ->
                    if (list.isNotEmpty()) map[provider.id] = list
                }
            }
        }
        modelsMap = map
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Buscar modelos", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = IdeTextPrimary)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = IdeTextSecondary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar modelos...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        text = {
            if (isLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = IdeSecondary)
                    Spacer(Modifier.width(10.dp))
                    Text("Consultando APIs...", fontSize = 12.sp, color = IdeTextSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Recentes / Ativo
                    if (activeProvider != null) {
                        item {
                            Text("Recentes", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = IdePrimary)
                        }
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = IdeSurfaceVariant),
                                border = BorderStroke(1.dp, IdeSecondary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectModel(activeProvider, activeProvider.mainModel) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(activeProvider.mainModel, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = IdeTextPrimary)
                                        Text(activeProvider.name, fontSize = 10.sp, color = IdeTextSecondary)
                                    }
                                    Text("Padrão >", fontSize = 10.sp, color = IdeSecondary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Provedores e modelos descobertos
                    for (provider in providers) {
                        val fetchedList = modelsMap[provider.id].orEmpty()
                        val filteredList = if (searchQuery.isBlank()) fetchedList
                        else fetchedList.filter {
                            it.id.contains(searchQuery, ignoreCase = true) || it.providerName.contains(searchQuery, ignoreCase = true)
                        }

                        if (filteredList.isNotEmpty() || (searchQuery.isBlank() && provider.mainModel.isNotBlank())) {
                            item {
                                Spacer(Modifier.height(4.dp))
                                Text(provider.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = IdePrimary)
                            }

                            val displayModels = if (filteredList.isNotEmpty()) filteredList
                            else listOf(DiscoveredModel(id = provider.mainModel, providerName = provider.name))

                            items(displayModels) { model ->
                                val isSelected = activeProvider?.id == provider.id && activeProvider.mainModel == model.id
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = if (isSelected) IdeSurfaceVariant else IdeSurface),
                                    border = BorderStroke(1.dp, if (isSelected) IdeSecondary else IdeBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectModel(provider, model.id) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(model.id, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = IdeTextPrimary)
                                            if (model.providerName.isNotBlank()) {
                                                Text(model.providerName, fontSize = 10.sp, color = IdeTextSecondary)
                                            }
                                        }
                                        if (model.isFree) {
                                            Surface(color = IdeSecondary.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                                Text("Grátis", fontSize = 9.sp, color = IdeSecondary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            Surface(color = IdePrimary.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                                Text("Pago", fontSize = 9.sp, color = IdePrimary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ChatBubbleItem(message: ChatMessageEntity, onCopyText: (String) -> Unit) {
    when (message.role) {
        MessageRole.USER -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = IdePrimary.copy(alpha = 0.18f)),
                    border = BorderStroke(1.dp, IdePrimary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(0.88f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        SelectionContainer {
                            Text(
                                text = if (message.imageBase64 != null) "📷 [Imagem anexada]\n${message.content}" else message.content,
                                color = IdeTextPrimary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
        MessageRole.ASSISTANT -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = IdeSurface),
                    border = BorderStroke(1.dp, IdeBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        SelectionContainer {
                            Column {
                                Text(
                                    text = MarkdownRenderer.render(message.content),
                                    color = IdeTextPrimary,
                                    fontSize = 13.sp
                                )
                                if (message.reasoning != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "💭 Raciocínio:\n${message.reasoning}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = IdeTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
                // Action bar (copiar)
                Row(modifier = Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onCopyText(message.content) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = IdeTextSecondary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        MessageRole.TOOL -> {
            var expanded by remember(message.id) { mutableStateOf(false) }
            Card(
                colors = CardDefaults.cardColors(containerColor = IdeSurfaceVariant),
                border = BorderStroke(1.dp, if (message.isError) IdeError.copy(alpha = 0.7f) else IdeBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Build, contentDescription = null,
                                tint = if (message.isError) IdeError else IdeWarning,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Tool: ${message.toolName ?: "exec"}${if (message.isError) " (erro)" else ""}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (message.isError) IdeError else IdeWarning
                            )
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = IdeTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = message.content,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = IdeTextPrimary
                        )
                    } else {
                        Text(
                            text = message.content.lineSequence().firstOrNull().orEmpty().take(120),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = IdeTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        MessageRole.SYSTEM_INJECTION -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = IdeBackground),
                border = BorderStroke(1.dp, IdeBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚙️ ${message.content}",
                    fontSize = 11.sp,
                    color = IdeTextSecondary,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        else -> {
            var showReasoning by remember(message.id) { mutableStateOf(false) }
            Card(
                colors = CardDefaults.cardColors(containerColor = IdeSurface),
                border = BorderStroke(1.dp, IdeBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (!message.reasoning.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showReasoning = !showReasoning }
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = IdeTextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (showReasoning) "Ocultar raciocínio" else "Ver raciocínio",
                                fontSize = 10.sp,
                                color = IdeTextSecondary
                            )
                        }
                        if (showReasoning) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = message.reasoning.orEmpty(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = IdeTextSecondary
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            color = IdeTextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = { onCopyText(message.content) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = IdeTextSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    if (!message.toolArgsJson.isNullOrBlank()) {
                        Text(
                            text = "⚡ Invocando ferramentas autônomas…",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = IdeSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWelcomeCard(onQuickPrompt: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = IdeSurface),
        border = BorderStroke(1.dp, IdeBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Codex Agent — Autonomia Total",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = IdePrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Configure sua API na aba APIs e dê qualquer missão de engenharia, automação ou análise de APKs. " +
                    "O agente executa ferramentas, instala pacotes, compila e testa sem interrupções (modo YOLO).",
                fontSize = 12.sp,
                color = IdeTextSecondary
            )
            Spacer(Modifier.height(10.dp))
            AssistChip(
                onClick = {
                    onQuickPrompt(
                        "Inspecione o ambiente do meu celular, verifique se jadx, apktool, python, node e git estão " +
                            "instalados e instale o que estiver faltando. Depois atualize o AGENT.md com o resultado."
                    )
                },
                label = { Text("Verificar & Instalar Ferramentas", fontSize = 11.sp) }
            )
            Spacer(Modifier.height(4.dp))
            AssistChip(
                onClick = {
                    onQuickPrompt(
                        "Crie um projeto completo de API REST em Python FastAPI nesta pasta, instale as dependências, " +
                            "inicie o servidor em background e teste os endpoints com curl."
                    )
                },
                label = { Text("Criar & Rodar API Python", fontSize = 11.sp) }
            )
        }
    }
}

private fun encodeUriToBase64(context: android.content.Context, uri: Uri): String? = runCatching {
    val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
    Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}.getOrNull()

private fun copyUriToWorkspaceTemp(context: Context, uri: Uri): File? = runCatching {
    val name = getUriFileName(context, uri) ?: "upload_${System.currentTimeMillis()}.apk"
    val tempDir = File(context.filesDir, "apk_uploads").apply { mkdirs() }
    val outFile = File(tempDir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        java.io.FileOutputStream(outFile).use { output ->
            input.copyTo(output, bufferSize = 128 * 1024)
        }
    }
    outFile
}.getOrNull()

private fun getUriFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1) name = cursor.getString(idx)
            }
        }
    }
    if (name == null) {
        name = uri.path?.substringAfterLast('/')
    }
    return name
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}
