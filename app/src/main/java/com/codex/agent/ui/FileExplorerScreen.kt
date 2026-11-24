package com.codex.agent.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.agent.agent.AgentOrchestrator
import com.codex.agent.data.repository.WorkspaceRepository
import com.codex.agent.shell.ShellExecutor
import kotlinx.coroutines.launch
import java.io.File

/**
 * Explorador de arquivos do workspace com visualizador de código, Diff Viewer (git vs último
 * checkpoint) e APK Inspector (menu rápido que dispara missões autônomas no agente).
 */
@Composable
fun FileExplorerScreen(
    workspaceRepository: WorkspaceRepository,
    shellExecutor: ShellExecutor,
    orchestrator: AgentOrchestrator,
    onNavigateToChat: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val activeWorkspace by workspaceRepository.observeActive().collectAsState(initial = null)
    val workspaceRoot = activeWorkspace?.rootPath ?: "/sdcard/CodexProjects"

    var currentDir by remember(workspaceRoot) { mutableStateOf(File(workspaceRoot).apply { mkdirs() }) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var diffText by remember { mutableStateOf<String?>(null) }
    var selectedApkFile by remember { mutableStateOf<File?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val files = remember(currentDir, refreshTrigger) {
        currentDir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IdeBackground)
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(
                    onClick = {
                        currentDir.parentFile?.let { parent ->
                            if (parent.canRead() && (
                                    parent.absolutePath.startsWith("/storage") ||
                                    parent.absolutePath == "/sdcard" ||
                                    parent.absolutePath.startsWith("/sdcard/")
                                )
                            ) {
                                currentDir = parent
                            }
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Subir pasta", tint = IdePrimary)
                }
                Text(
                    text = currentDir.absolutePath,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = IdeTextPrimary,
                    maxLines = 2
                )
            }
            Row {
                IconButton(onClick = { showNewFolderDialog = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "Nova Pasta", tint = IdeSecondary)
                }
                IconButton(onClick = { refreshTrigger++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = IdePrimary)
                }
            }
        }

        if (currentDir.absolutePath != workspaceRoot) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val workspace = workspaceRepository.create(
                            name = currentDir.name,
                            rootPath = currentDir.absolutePath
                        )
                        workspaceRepository.setActive(workspace.id)
                        orchestrator.switchToActiveWorkspace()
                        refreshTrigger++
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text("Definir esta pasta como Workspace Ativo do Agente", fontSize = 11.sp)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(files, key = { it.absolutePath }) { file ->
                val isApk = file.extension.lowercase() in listOf("apk", "aab", "xapk")
                Card(
                    colors = CardDefaults.cardColors(containerColor = IdeSurface),
                    border = BorderStroke(1.dp, if (isApk) IdeSecondary else IdeBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            when {
                                file.isDirectory -> currentDir = file
                                isApk -> selectedApkFile = file
                                else -> viewingFile = file
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when {
                                file.isDirectory -> Icons.Default.Folder
                                isApk -> Icons.Default.Android
                                else -> Icons.Default.Code
                            },
                            contentDescription = null,
                            tint = when {
                                file.isDirectory -> IdeWarning
                                isApk -> IdeSecondary
                                else -> IdePrimary
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(file.name, fontSize = 13.sp, color = IdeTextPrimary, fontWeight = FontWeight.Medium)
                            if (file.isFile) {
                                Text("${file.length() / 1024} KB", fontSize = 10.sp, color = IdeTextSecondary)
                            }
                        }
                        if (isApk) {
                            Text("APK Inspector", fontSize = 10.sp, color = IdeSecondary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ---------------- APK Inspector ----------------
    if (selectedApkFile != null) {
        val apk = selectedApkFile!!
        AlertDialog(
            onDismissRequest = { selectedApkFile = null },
            title = { Text("APK Inspector: ${apk.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Escolha uma ação autônoma para o agente executar neste pacote:", fontSize = 12.sp)
                    Button(
                        onClick = {
                            selectedApkFile = null
                            orchestrator.submitUserPrompt(
                                "Execute uma auditoria completa no APK '${apk.absolutePath}' usando " +
                                    "apk_analyzer_tool: inspect_manifest_and_certs (permissões, componentes " +
                                    "exportados, certificados) e search_strings_and_endpoints com " +
                                    "exclude_std_libs=true. Resuma os riscos de segurança."
                            )
                            onNavigateToChat()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("1. Auditar Segurança, Manifesto & Endpoints", fontSize = 12.sp) }

                    Button(
                        onClick = {
                            selectedApkFile = null
                            orchestrator.submitUserPrompt(
                                "Decompile o APK '${apk.absolutePath}' com " +
                                    "apk_analyzer_tool(action='decompile_java') e mapeie as classes " +
                                    "principais, Activities e lógica de rede."
                            )
                            onNavigateToChat()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("2. Decompilar Código Java (JADX)", fontSize = 12.sp) }

                    Button(
                        onClick = {
                            selectedApkFile = null
                            orchestrator.submitUserPrompt(
                                "Extraia recursos e smali do APK '${apk.absolutePath}' com " +
                                    "apk_analyzer_tool(action='decompile_resources') e liste a estrutura gerada."
                            )
                            onNavigateToChat()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("3. Extrair Smali & Recursos (Apktool)", fontSize = 12.sp) }

                    Button(
                        onClick = {
                            selectedApkFile = null
                            orchestrator.submitUserPrompt(
                                "Reconstrua e assine o pacote a partir do diretório descompilado " +
                                    "'${apk.absolutePath}' usando apk_analyzer_tool(action='rebuild_and_sign') " +
                                    "e informe o caminho do APK final assinado."
                            )
                            onNavigateToChat()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("4. Recompilar & Assinar Pasta", fontSize = 12.sp) }

                    Button(
                        onClick = {
                            selectedApkFile = null
                            orchestrator.submitUserPrompt(
                                "Clone o APK '${apk.absolutePath}' de forma 100% idêntica usando " +
                                    "apk_analyzer_tool(action='clone_apk', target_path='${apk.absolutePath}'). " +
                                    "Decompile com -r -s, reconstrua com aapt2, alinhe, assine e VERIFIQUE " +
                                    "integridade (contagem de arquivos, tamanhos, SHA-256). Reporte qualquer divergência."
                            )
                            onNavigateToChat()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("5. Clonar APK 1:1 (decompile→rebuild→verify)", fontSize = 12.sp) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selectedApkFile = null }) { Text("Fechar") } }
        )
    }

    // ---------------- Visualizador de código ----------------
    if (viewingFile != null) {
        val file = viewingFile!!
        val content = remember(file.absolutePath) {
            runCatching { file.readText().take(25_000) }.getOrElse { "Não foi possível ler o arquivo como texto." }
        }
        AlertDialog(
            onDismissRequest = { viewingFile = null },
            title = { Text(file.name, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .height(380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = SyntaxHighlighter.highlight(content),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch { diffText = buildWorkspaceDiff(workspaceRepository, shellExecutor, file) }
                    }) { Text("Diff (Git)") }
                    TextButton(onClick = { viewingFile = null }) { Text("Fechar") }
                }
            }
        )
    }

    // ---------------- Diff Viewer ----------------
    if (diffText != null) {
        AlertDialog(
            onDismissRequest = { diffText = null },
            title = { Text("Diff vs último checkpoint", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .height(380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = highlightDiff(diffText.orEmpty()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = { TextButton(onClick = { diffText = null }) { Text("Fechar") } }
        )
    }

    // ---------------- Nova pasta / workspace ----------------
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Nova Pasta de Projeto") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Nome da pasta") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            val created = File(currentDir, newFolderName.trim()).apply { mkdirs() }
                            scope.launch {
                                val workspace = workspaceRepository.create(
                                    name = created.name,
                                    rootPath = created.absolutePath
                                )
                                workspaceRepository.setActive(workspace.id)
                                orchestrator.switchToActiveWorkspace()
                            }
                            currentDir = created
                            newFolderName = ""
                            showNewFolderDialog = false
                            refreshTrigger++
                        }
                    }
                ) { Text("Criar e Ativar") }
            },
            dismissButton = { TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancelar") } }
        )
    }
}

/** Colore as linhas de um diff: verde=adição, vermelho=remoção, ciano=cabeçalho. */
private fun highlightDiff(diff: String): AnnotatedString = buildAnnotatedString {
    for (line in diff.lines()) {
        val color = when {
            line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF50FA7B)
            line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFF5555)
            line.startsWith("@@") || line.startsWith("diff ") || line.startsWith("index ") -> Color(0xFF8BE9FD)
            else -> IdeTextPrimary
        }
        pushStyle(SpanStyle(color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
        append(line)
        pop()
        append("\n")
    }
}

/** Diff do arquivo contra o último checkpoint Git do workspace (.codex_git). */
private suspend fun buildWorkspaceDiff(
    workspaceRepository: WorkspaceRepository,
    shellExecutor: ShellExecutor,
    file: File
): String {
    val workspace = workspaceRepository.active() ?: return "Nenhum workspace ativo."
    val gitDir = File(workspace.rootPath, ".codex_git")
    if (!gitDir.isDirectory) {
        return "Nenhum checkpoint Git ainda. O agente cria um snapshot automaticamente ao iniciar uma tarefa."
    }
    val relative = file.absolutePath.removePrefix(workspace.rootPath).trimStart('/')
    val result = shellExecutor.execute(
        command = "git --git-dir=\"${gitDir.absolutePath}\" --work-tree=\"${workspace.rootPath}\" diff -- \"$relative\"",
        workdir = workspace.rootPath,
        timeoutSec = 30
    )
    return if (result.output.isBlank()) "Sem diferenças em relação ao último checkpoint."
    else result.output
}
