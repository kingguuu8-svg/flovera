package com.flovera.app.agent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.flovera.app.config.SettingsStore

class AgentRunForegroundService : Service() {
  override fun onCreate() {
    super.onCreate()
    running = true
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return when (intent?.action) {
      ACTION_RUNNING -> {
        mode = "agent_run"
        startRunning(intent.getStringExtra(EXTRA_BODY).orEmpty())
        START_NOT_STICKY
      }
      ACTION_KEEP_ALIVE -> {
        mode = "keep_alive"
        startKeepAlive()
        START_STICKY
      }
      ACTION_STOP_KEEP_ALIVE -> {
        mode = "stopping"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        START_NOT_STICKY
      }
      ACTION_FINISHED, ACTION_INTERRUPTED -> {
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        if (shouldRestoreKeepAlive()) {
          AgentRunNotifications.postNormal(
            context = this,
            title = title,
            body = body,
            ongoing = false,
            priority = NotificationCompat.PRIORITY_DEFAULT,
          )
          startKeepAlive()
          START_STICKY
        } else {
          stopForeground(STOP_FOREGROUND_REMOVE)
          AgentRunNotifications.postNormal(
            context = this,
            title = title,
            body = body,
            ongoing = false,
            priority = NotificationCompat.PRIORITY_DEFAULT,
          )
          stopSelf()
          START_NOT_STICKY
        }
      }
      null -> {
        if (shouldRestoreKeepAlive()) {
          startKeepAlive()
          START_STICKY
        } else {
          stopSelf()
          START_NOT_STICKY
        }
      }
      else -> {
        stopSelf()
        START_NOT_STICKY
      }
    }
  }

  override fun onDestroy() {
    running = false
    mode = "stopped"
    super.onDestroy()
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
      AgentRunNotifications.FOREGROUND_NOTIFICATION_ID,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  private fun startKeepAlive() {
    AgentRunNotifications.ensureChannel(this)
    val notification = AgentRunNotifications.build(
      context = this,
      title = "Flovera background keep-alive is on",
      body = "Keeping Flovera available for background workspace work.",
      ongoing = true,
      priority = NotificationCompat.PRIORITY_LOW,
    )
    startForeground(
      AgentRunNotifications.FOREGROUND_NOTIFICATION_ID,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  private fun shouldRestoreKeepAlive(): Boolean {
    return runCatching { SettingsStore(this).load().backgroundKeepAliveEnabled }.getOrDefault(false)
  }

  companion object {
    @Volatile private var running: Boolean = false
    @Volatile private var mode: String = "stopped"
    private const val ACTION_RUNNING = "com.flovera.app.agent.RUNNING"
    private const val ACTION_FINISHED = "com.flovera.app.agent.FINISHED"
    private const val ACTION_INTERRUPTED = "com.flovera.app.agent.INTERRUPTED"
    private const val ACTION_KEEP_ALIVE = "com.flovera.app.agent.KEEP_ALIVE"
    private const val ACTION_STOP_KEEP_ALIVE = "com.flovera.app.agent.STOP_KEEP_ALIVE"
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

    fun keepAliveIntent(context: Context): Intent {
      return Intent(context, AgentRunForegroundService::class.java)
        .setAction(ACTION_KEEP_ALIVE)
    }

    fun stopKeepAliveIntent(context: Context): Intent {
      return Intent(context, AgentRunForegroundService::class.java)
        .setAction(ACTION_STOP_KEEP_ALIVE)
    }

    fun isServiceRunning(): Boolean = running

    fun currentMode(): String = mode
  }
}
