package com.flovera.app.agent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AgentRunForegroundService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_RUNNING -> startRunning(intent.getStringExtra(EXTRA_BODY).orEmpty())
      ACTION_FINISHED, ACTION_INTERRUPTED -> {
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        stopForeground(STOP_FOREGROUND_REMOVE)
        AgentRunNotifications.postNormal(
          context = this,
          title = title,
          body = body,
          ongoing = false,
          priority = NotificationCompat.PRIORITY_DEFAULT,
        )
        stopSelf()
      }
      else -> stopSelf()
    }
    return START_NOT_STICKY
  }

  private fun startRunning(body: String) {
    AgentRunNotifications.ensureChannel(this)
    val notification = AgentRunNotifications.build(
      context = this,
      title = "Flovera agent is running",
      body = body.ifBlank { "Working in the current workspace." },
      ongoing = true,
      priority = NotificationCompat.PRIORITY_LOW,
    )
    startForeground(
      AgentRunNotifications.NOTIFICATION_ID,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  companion object {
    private const val ACTION_RUNNING = "com.flovera.app.agent.RUNNING"
    private const val ACTION_FINISHED = "com.flovera.app.agent.FINISHED"
    private const val ACTION_INTERRUPTED = "com.flovera.app.agent.INTERRUPTED"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_BODY = "body"

    fun runningIntent(context: Context, body: String): Intent {
      return Intent(context, AgentRunForegroundService::class.java)
        .setAction(ACTION_RUNNING)
        .putExtra(EXTRA_BODY, body)
    }

    fun finishedIntent(context: Context, title: String, body: String): Intent {
      return Intent(context, AgentRunForegroundService::class.java)
        .setAction(ACTION_FINISHED)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_BODY, body)
    }

    fun interruptedIntent(context: Context, title: String, body: String): Intent {
      return Intent(context, AgentRunForegroundService::class.java)
        .setAction(ACTION_INTERRUPTED)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_BODY, body)
    }
  }
}
