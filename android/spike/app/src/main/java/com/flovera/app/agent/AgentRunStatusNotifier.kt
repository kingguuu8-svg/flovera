package com.flovera.app.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.flovera.app.R

interface AgentRunStatusNotifier {
  fun running(message: String)
  fun finished(succeeded: Boolean)
  fun interrupted()
}

class AndroidAgentRunStatusNotifier(context: Context) : AgentRunStatusNotifier {
  private val appContext = context.applicationContext

  override fun running(message: String) {
    val body = message.ifBlank { "Working in the current workspace." }
    val started = runCatching {
      ContextCompat.startForegroundService(appContext, AgentRunForegroundService.runningIntent(appContext, body))
    }.isSuccess
    if (!started) {
      AgentRunNotifications.postNormal(
        context = appContext,
        title = "Flovera agent is running",
        body = body,
        ongoing = true,
        priority = NotificationCompat.PRIORITY_LOW,
      )
    }
  }

  override fun finished(succeeded: Boolean) {
    val title = if (succeeded) "Flovera agent completed" else "Flovera agent failed"
    val body = if (succeeded) "The latest agent run finished." else "Open Flovera to view the error log."
    val stopped = runCatching {
      appContext.startService(AgentRunForegroundService.finishedIntent(appContext, title, body))
    }.isSuccess
    if (!stopped) {
      AgentRunNotifications.postNormal(appContext, title, body, ongoing = false, priority = NotificationCompat.PRIORITY_DEFAULT)
    }
  }

  override fun interrupted() {
    val title = "Flovera agent interrupted"
    val body = "The active agent run was stopped."
    val stopped = runCatching {
      appContext.startService(AgentRunForegroundService.interruptedIntent(appContext, title, body))
    }.isSuccess
    if (!stopped) {
      AgentRunNotifications.postNormal(appContext, title, body, ongoing = false, priority = NotificationCompat.PRIORITY_DEFAULT)
    }
  }
}

internal object AgentRunNotifications {
  const val CHANNEL_ID = "flovera_agent_runs"
  const val NOTIFICATION_ID = 7104

  fun build(context: Context, title: String, body: String, ongoing: Boolean, priority: Int) =
    NotificationCompat.Builder(context.applicationContext, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setContentIntent(openAppIntent(context))
      .setOngoing(ongoing)
      .setAutoCancel(!ongoing)
      .setPriority(priority)
      .build()

  fun postNormal(context: Context, title: String, body: String, ongoing: Boolean, priority: Int) {
    val appContext = context.applicationContext
    if (!canPostNotifications(appContext)) return
    ensureChannel(appContext)
    val notification = build(appContext, title, body, ongoing, priority)
    NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
  }

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val appContext = context.applicationContext
    val manager = appContext.getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(CHANNEL_ID, "flovera agent", NotificationManager.IMPORTANCE_LOW)
    manager.createNotificationChannel(channel)
  }

  private fun openAppIntent(context: Context): PendingIntent? {
    val appContext = context.applicationContext
    val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
      ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      ?: return null
    return PendingIntent.getActivity(
      appContext,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun canPostNotifications(context: Context): Boolean {
    val appContext = context.applicationContext
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  }
}
