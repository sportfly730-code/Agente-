package com.codex.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.agent.data.entity.BackgroundJobEntity
import com.codex.agent.shell.ShellExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Terminal Live: stream bruto stdout/stderr, gerenciador de processos bg (Kill) e input manual. */
@Composable
fun TerminalScreen(shellExecutor: ShellExecutor) {
    val liveOutput by shellExecutor.liveTerminalOutput.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var manualCmd by remember { mutableStateOf("") }
    var backgroundJobs by remember { mutableStateOf<List<BackgroundJobEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            // Não deixa uma exceção eventual matar o loop de polling permanentemente.
            runCatching { backgroundJobs = shellExecutor.pollBackgroundJobs() }
            delay(1500)
        }
    }

    LaunchedEffect(liveOutput) {
        runCatching { scrollState.animateScrollTo(scrollState.maxValue) }
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
            Text(
                text = "Terminal Live (Termux / Interno)",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = IdePrimary
            )
            IconButton(onClick = { shellExecutor.clearLiveLog() }) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Limpar", tint = IdeTextSecondary)
            }
        }

        if (backgroundJobs.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = IdeSurfaceVariant),
                border = BorderStroke(1.dp, IdeWarning),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        "Processos em Background (${backgroundJobs.size}):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = IdeWarning
                    )
                    backgroundJobs.forEach { job ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "PID ${job.pid ?: "?"}: ${job.command.take(60)}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = IdeTextPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            IconButton(
                                onClick = { scope.launch { shellExecutor.killBackgroundJob(job) } },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Encerrar", tint = IdeError, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color = IdeSurface,
            border = BorderStroke(1.dp, IdeBorder),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(10.dp)
            ) {
                Text(
                    text = AnsiParser.parse(liveOutput),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = manualCmd,
                onValueChange = { manualCmd = it },
                placeholder = { Text("Digite um comando bash manual…", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    val command = manualCmd.trim()
                    if (command.isNotEmpty()) {
                        manualCmd = ""
                        scope.launch { shellExecutor.execute(command) }
                    }
                },
                modifier = Modifier.background(IdePrimary, RoundedCornerShape(8.dp))
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Rodar", tint = IdeBackground)
            }
        }
    }
}
