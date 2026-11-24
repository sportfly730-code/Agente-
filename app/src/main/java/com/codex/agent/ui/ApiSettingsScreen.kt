package com.codex.agent.ui

import android.widget.Toast
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.agent.agent.AgentOrchestrator
import com.codex.agent.data.entity.ProviderConfigEntity
import com.codex.agent.data.model.ExecutionMode
import com.codex.agent.data.model.ProviderKind
import com.codex.agent.data.repository.ProviderRepository
import com.codex.agent.data.repository.SettingsRepository
import com.codex.agent.llm.DiscoveredModel
import com.codex.agent.llm.LlmClient
import com.codex.agent.llm.LlmMessage
import kotlinx.coroutines.launch

/**
 * Configuração de provedores (multi-API), dual-model, modo de execução,
 * limite de passos autônomos e system prompt base — com teste de conexão 1-clique.
 */
@Composable
fun ApiSettingsScreen(
    settingsRepository: SettingsRepository,
    providerRepository: ProviderRepository,
    llmClient: LlmClient,
    orchestrator: AgentOrchestrator
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val providers by providerRepository.observeAll().collectAsState(initial = emptyList())
    val settings by settingsRepository.observe().collectAsState(initial = null)

    var editingProvider by remember { mutableStateOf<ProviderConfigEntity?>(null) }
    var promptDraft by remember(settings?.baseSystemPrompt) { mutableStateOf(settings?.baseSystemPrompt.orEmpty()) }
    var customStepsDraft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val current = settingsRepository.baseSystemPrompt()
        if (promptDraft.isBlank() && current.isNotBlank()) promptDraft = current
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(IdeBackground)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Provedores de IA & Modelos", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = IdePrimary)
                Button(onClick = {
                    editingProvider = ProviderConfigEntity(
                        name = "Custom API",
                        baseUrl = "https://api.openai.com/v1",
                        mainModel = "gpt-4o",
                        utilityModel = "gpt-4o-mini"
                    )
                }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Nova API", fontSize = 12.sp)
                }
            }
        }

        items(providers, key = { it.id }) { provider ->
            Card(
                colors = CardDefaults.cardColors(containerColor = IdeSurface),
                border = BorderStroke(1.dp, if (provider.isDefault) IdeSecondary else IdeBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            providerRepository.setDefault(provider.id)
                            settingsRepository.setActiveProviderId(provider.id)
                            Toast.makeText(context, "'${provider.name}' definido como padrão.", Toast.LENGTH_SHORT).show()
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (provider.isDefault) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = IdeSecondary, modifier = Modifier.height(16.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(provider.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = IdeTextPrimary)
                        }
                        Text("Principal: ${provider.mainModel}", fontSize = 11.sp, color = IdePrimary)
                        Text("Econômico: ${provider.utilityModel}", fontSize = 11.sp, color = IdeTextSecondary)
                        Text(
                            text = if (provider.apiKey.isBlank()) "Sem API Key (edite para configurar)" else "Key configurada · ${provider.kind.name}",
                            fontSize = 11.sp,
                            color = if (provider.apiKey.isBlank()) IdeWarning else IdeSecondary
                        )
                    }
                    Row {
                        IconButton(onClick = { editingProvider = provider }) {
                            Icon(Icons.Default.Edit, contentDescription = "Editar", tint = IdePrimary)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                providerRepository.deleteById(provider.id)
                                if (settings?.activeProviderId == provider.id) settingsRepository.setActiveProviderId(null)
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = IdeError)
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = IdeSurface),
                border = BorderStroke(1.dp, IdeBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Motor de Execução no Celular", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = IdePrimary)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExecutionMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings?.executionMode == mode,
                                onClick = { scope.launch { settingsRepository.setExecutionMode(mode) } },
                                label = { Text(mode.name, fontSize = 11.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Limite máximo de passos autônomos por tarefa: ${settings?.defaultMaxSteps ?: 50}",
                        fontSize = 12.sp,
                        color = IdeTextPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(50, 100, 200, 300, 2000).forEach { steps ->
                            FilterChip(
                                selected = settings?.defaultMaxSteps == steps,
                                onClick = { scope.launch { settingsRepository.setDefaultMaxSteps(steps) } },
                                label = { Text("$steps", fontSize = 11.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customStepsDraft,
                            onValueChange = { customStepsDraft = it.filter { c -> c.isDigit() }.take(4) },
                            label = { Text("Personalizado (1–2000)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            val value = customStepsDraft.toIntOrNull()
                            if (value != null && value in 1..2000) {
                                scope.launch { settingsRepository.setDefaultMaxSteps(value) }
                                customStepsDraft = ""
                            }
                        }) { Text("Aplicar", fontSize = 11.sp) }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = IdeSurface),
                border = BorderStroke(1.dp, IdeBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("System Prompt Autônomo (Diretrizes da IA)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = IdePrimary)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        placeholder = { Text("Vazio = usa o prompt padrão embutido do Codex Agent.", fontSize = 11.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                settingsRepository.setBaseSystemPrompt(promptDraft)
                                Toast.makeText(context, "System Prompt salvo!", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Salvar Prompt", fontSize = 12.sp) }
                        OutlinedButton(onClick = {
                            promptDraft = ""
                            scope.launch {
                                settingsRepository.setBaseSystemPrompt("")
                                Toast.makeText(context, "Restaurado ao padrão embutido.", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Restaurar Padrão", fontSize = 12.sp) }
                    }
                }
            }
        }
    }

    // ---------------- Modal de edição / teste ----------------
    if (editingProvider != null) {
        val current = editingProvider!!
        var name by remember { mutableStateOf(current.name) }
        var baseUrl by remember { mutableStateOf(current.baseUrl) }
        var apiKey by remember { mutableStateOf(current.apiKey) }
        var mainModel by remember { mutableStateOf(current.mainModel) }
        var utilityModel by remember { mutableStateOf(current.utilityModel) }
        var extraHeaders by remember { mutableStateOf(current.extraHeadersJson) }
        var kind by remember { mutableStateOf(current.kind) }
        var fetchedModels by remember { mutableStateOf<List<com.codex.agent.llm.DiscoveredModel>>(emptyList()) }
        var isFetching by remember { mutableStateOf(false) }

        fun fetchModelsNow() {
            if (baseUrl.isBlank()) return
            val draft = current.copy(
                name = name.trim(),
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                extraHeadersJson = extraHeaders.trim().ifBlank { "{}" },
                kind = kind
            )
            scope.launch {
                isFetching = true
                val res = llmClient.fetchAvailableModels(draft)
                res.fold(
                    onSuccess = { list ->
                        fetchedModels = list
                        if (list.isNotEmpty()) {
                            if (mainModel.isBlank() || list.none { it.id == mainModel }) {
                                mainModel = list.first().id
                                utilityModel = list.first().id
                            }
                            Toast.makeText(context, "${list.size} modelos liberados na API!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFailure = { err ->
                        Toast.makeText(context, "Erro ao listar modelos: ${err.message?.take(100)}", Toast.LENGTH_SHORT).show()
                    }
                )
                isFetching = false
            }
        }

        LaunchedEffect(baseUrl, apiKey) {
            if (baseUrl.isNotBlank() && (apiKey.isNotBlank() || baseUrl.contains("vyceai.com"))) {
                fetchModelsNow()
            }
        }

        AlertDialog(
            onDismissRequest = { editingProvider = null },
            title = { Text("Configurar Provedor de IA", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("ex.: https://api.openai.com/v1", fontSize = 11.sp, color = IdeTextSecondary) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    OutlinedTextField(value = mainModel, onValueChange = { mainModel = it }, label = { Text("Modelo Principal (Coder)") }, singleLine = true)
                    OutlinedTextField(value = utilityModel, onValueChange = { utilityModel = it }, label = { Text("Modelo Econômico (Resumo)") }, singleLine = true)

                    if (fetchedModels.isNotEmpty()) {
                        Text("Modelos liberados nesta API (${fetchedModels.size}):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = IdePrimary)
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(fetchedModels, key = { it.id }) { model ->
                                FilterChip(
                                    selected = mainModel == model.id,
                                    onClick = {
                                        mainModel = model.id
                                        utilityModel = model.id
                                    },
                                    label = { Text(model.id, fontSize = 10.sp) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = extraHeaders,
                        onValueChange = { extraHeaders = it },
                        label = { Text("Headers extras (JSON, ex.: {\"X-Title\":\"Codex\"})") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = kind == ProviderKind.OPENAI_COMPATIBLE,
                            onClick = { kind = ProviderKind.OPENAI_COMPATIBLE },
                            label = { Text("OpenAI", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = kind == ProviderKind.ANTHROPIC_NATIVE,
                            onClick = { kind = ProviderKind.ANTHROPIC_NATIVE },
                            label = { Text("Anthropic", fontSize = 11.sp) }
                        )
                    }
                    OutlinedButton(
                        onClick = { fetchModelsNow() },
                        enabled = !isFetching,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isFetching) "Consultando API..." else "🔍 Puxar Modelos da API", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            val draft = current.copy(
                                name = name.trim(),
                                baseUrl = baseUrl.trim(),
                                apiKey = apiKey.trim(),
                                mainModel = mainModel.trim(),
                                utilityModel = utilityModel.trim(),
                                extraHeadersJson = extraHeaders.trim().ifBlank { "{}" },
                                kind = kind
                            )
                            scope.launch {
                                val result = llmClient.chatCompletion(
                                    provider = draft,
                                    messages = listOf(LlmMessage(role = "user", content = "Responda apenas: OK"))
                                )
                                result.fold(
                                    onSuccess = {
                                        Toast.makeText(context, "Conexão OK (${it.totalTokens} tokens).", Toast.LENGTH_LONG).show()
                                    },
                                    onFailure = {
                                        Toast.makeText(context, "Falha: ${it.message?.take(160)}", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        }
                    ) { Text("Testar") }

                    Button(
                        onClick = {
                            scope.launch {
                                val savedId = providerRepository.upsert(
                                    current.copy(
                                        name = name.trim(),
                                        baseUrl = baseUrl.trim(),
                                        apiKey = apiKey.trim(),
                                        mainModel = mainModel.trim(),
                                        utilityModel = utilityModel.trim(),
                                        extraHeadersJson = extraHeaders.trim().ifBlank { "{}" },
                                        kind = kind
                                    )
                                )
                                if (current.id == 0L) {
                                    providerRepository.setDefault(savedId)
                                    settingsRepository.setActiveProviderId(savedId)
                                    orchestrator.setProvider(savedId)
                                }
                                editingProvider = null
                                Toast.makeText(context, "Provedor salvo.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) { Text("Salvar") }
                }
            },
            dismissButton = { TextButton(onClick = { editingProvider = null }) { Text("Cancelar") } }
        )
    }
}
