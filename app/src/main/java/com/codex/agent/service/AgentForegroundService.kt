package com.codex.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.codex.agent.ui.MainActivity

/**
 * Serviço em primeiro plano: mantém o agente baixando ferramentas, decompilando APKs e
 * compilando código com a tela apagada (PARTIAL_WAKE_LOCK) e oferece a ação "Parar Agente"
 * direto na notificação.
 */
class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                // Chamado APENAS quando o usuário toca em "Parar Agente" na notificação.
                onStopRequestedCallback?.invoke()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FINISH_SERVICE -> {
                // Agente terminou (sucesso/erro): apenas encerra o foreground, SEM cancelar.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_UPDATE_STATUS (ou null): atualiza notificação, NÃO para nada.
                val status = intent?.getStringExtra(EXTRA_STATUS_TEXT)
                    ?: "Agente autônomo em execução…"
                startAsForeground(buildNotification(status))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CodexAgent::AutonomousWakeLock"
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire(60 * 60 * 1000L) }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Codex Agent — Execução Autônoma",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o agente executando comandos, downloads e builds com a tela desligada."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Codex Agent ativo")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Parar Agente", stopPending)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "codex_agent_fg_channel"
        const val NOTIFICATION_ID = 4041
        const val ACTION_UPDATE_STATUS = "com.codex.agent.UPDATE_STATUS"
        const val ACTION_STOP_SERVICE = "com.codex.agent.STOP_SERVICE"
        const val ACTION_FINISH_SERVICE = "com.codex.agent.FINISH_SERVICE"
        const val EXTRA_STATUS_TEXT = "status_text"

        /** Registrado pelo MainActivity para cancelar o loop do orquestrador. */
        @Volatile
        var onStopRequestedCallback: (() -> Unit)? = null

        fun syncServiceState(context: Context, isRunning: Boolean, statusText: String) {
            runCatching {
                if (isRunning) {
                    val intent = Intent(context, AgentForegroundService::class.java).apply {
                        action = ACTION_UPDATE_STATUS
                        putExtra(EXTRA_STATUS_TEXT, statusText)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    // Conclusão normal: encerra o foreground SEM disparar o callback de parada.
                    val intent = Intent(context, AgentForegroundService::class.java).apply {
                        action = ACTION_FINISH_SERVICE
                    }
                    context.startService(intent)
                }
            }
        }
    }
}
