package com.codex.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.agent.shell.EnvironmentSetup
import com.codex.agent.shell.ToolchainStatus
import rikka.shizuku.Shizuku
import kotlinx.coroutines.launch

/**
 * Setup Wizard: verifica MANAGE_EXTERNAL_STORAGE, REQUEST_INSTALL_PACKAGES, a ponte Termux
 * (allow-external-apps=true), Shizuku e oferece instalação da toolchain com 1 toque.
 * Some automaticamente quando tudo está pronto.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionCheckBanner(environmentSetup: EnvironmentSetup) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasStoragePerm by remember { mutableStateOf(checkStoragePermission()) }
    var hasInstallPerm by remember { mutableStateOf(checkInstallPermission(context)) }
    var shizukuGranted by remember { mutableStateOf(checkShizuku()) }
    var toolchainStatus by remember { mutableStateOf<ToolchainStatus?>(null) }
    var isProvisioning by remember { mutableStateOf(false) }
    var provisionMsg by remember { mutableStateOf<String?>(null) }

    fun refreshAll() {
        hasStoragePerm = checkStoragePermission()
        hasInstallPerm = checkInstallPermission(context)
        shizukuGranted = checkShizuku()
        scope.launch { toolchainStatus = environmentSetup.inspectEnvironment() }
    }

    LaunchedEffect(Unit) {
        hasStoragePerm = checkStoragePermission()
        hasInstallPerm = checkInstallPermission(context)
        shizukuGranted = checkShizuku()
        val status = environmentSetup.inspectEnvironment()
        toolchainStatus = status
        
        // Auto-provisioning automático na primeira inicialização do APK!
        val apktoolMissing = status.installedTools["apktool"] != true
        val jadxMissing = status.installedTools["jadx"] != true
        if ((apktoolMissing || jadxMissing) && !isProvisioning) {
            isProvisioning = true
            provisionMsg = "⚙️ Baixando e instalando ferramentas automaticamente (JADX, Apktool, JDK 17, Python, Node)..."
            scope.launch {
                environmentSetup.provisionFullToolchain { msg -> provisionMsg = msg }
                isProvisioning = false
                refreshAll()
            }
        }
    }

    val bridgeOk = toolchainStatus?.termuxBridgeWorking == true
    val apktoolOk = toolchainStatus?.installedTools?.get("apktool") == true
    val jadxOk = toolchainStatus?.installedTools?.get("jadx") == true
    val allReady = hasStoragePerm && hasInstallPerm && bridgeOk && apktoolOk && jadxOk

    if (allReady && !isProvisioning && provisionMsg == null) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = IdeSurface),
        border = BorderStroke(1.dp, IdeBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (bridgeOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (bridgeOk) IdeSecondary else IdeWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Setup do Ambiente (Dev & APK RE)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = IdeTextPrimary
                    )
                }
                IconButton(onClick = { refreshAll() }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Refresh, contentDescription = "Atualizar",
                        tint = IdePrimary, modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!hasStoragePerm) {
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                    )
                                }.onFailure {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                    }
                                }
                            }
                        },
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Liberar Arquivos", fontSize = 11.sp)
                    }
                }

                if (!hasInstallPerm) {
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                    )
                                }
                            }
                        },
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Permitir Instalar APKs", fontSize = 11.sp)
                    }
                }

                if (!bridgeOk) {
                    OutlinedButton(
                        onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Termux Setup", environmentSetup.termuxBridgeSetupCommand())
                            )
                            Toast.makeText(
                                context,
                                "Comando copiado! Cole e rode no Termux para habilitar allow-external-apps.",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copiar Config Termux", fontSize = 11.sp)
                    }
                }

                if (!shizukuGranted && runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                    OutlinedButton(
                        onClick = { runCatching { Shizuku.requestPermission(1001) } },
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        Text("Autorizar Shizuku", fontSize = 11.sp)
                    }
                }

                if (bridgeOk && (!apktoolOk || !jadxOk)) {
                    OutlinedButton(
                        onClick = {
                            if (!isProvisioning) {
                                isProvisioning = true
                                scope.launch {
                                    environmentSetup.provisionFullToolchain { msg -> provisionMsg = msg }
                                    isProvisioning = false
                                    refreshAll()
                                }
                            }
                        },
                        enabled = !isProvisioning,
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        if (isProvisioning) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("Instalar Toolchain (JADX/Apktool/JDK)", fontSize = 11.sp)
                    }
                }
            }

            if (provisionMsg != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = provisionMsg.orEmpty(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

private fun checkStoragePermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else true

private fun checkInstallPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else true

private fun checkShizuku(): Boolean = runCatching {
    Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
}.getOrDefault(false)
