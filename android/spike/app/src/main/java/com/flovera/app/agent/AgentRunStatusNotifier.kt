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
import com.flovera.app.R

interface AgentRunStatusNotifier {
  fun running(message: String)
  fun finished(succeeded: Boolean)
  fun interrupted()
}

class AndroidAgentRunStatusNotifier(context: Context) : AgentRunStatusNotifier {
  private val appContext = context.applicationContext

  override fun running(message: String) {
    post(
      title = "Flovera agent is running",
      body = message.ifBlank { "Working in the current workspace." },
      ongoing = true,
      priority = NotificationCompat.PRIORITY_LOW,
    )
  }

  override fun finished(succeeded: Boolean) {
    post(
      title = if (succeeded) "Flovera agent completed" else "Flovera agent failed",
      body = if (succeeded) "The latest agent run finished." else "Open Flovera to view the error log.",
      ongoing = false,
      priority = NotificationCompat.PRIORITY_DEFAULT,
    )
  }

  override fun interrupted() {
    post(
      title = "Flovera agent interrupted",
      body = "The active agent run was stopped.",
      ongoing = false,
      priority = NotificationCompat.PRIORITY_DEFAULT,
    )
  }

  private fun post(title: String, body: String, ongoing: Boolean, priority: Int) {
    if (!canPostNotifications()) return
    ensureChannel()
    val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setContentIntent(openAppIntent())
      .setOngoing(ongoing)
      .setAutoCancel(!ongoing)
      .setPriority(priority)
      .build()
    NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = appContext.getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(CHANNEL_ID, "flovera agent", NotificationManager.IMPORTANCE_LOW)
    manager.createNotificationChannel(channel)
  }

  private fun openAppIntent(): PendingIntent? {
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

  private fun canPostNotifications(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  }

  private companion object {
    const val CHANNEL_ID = "flovera_agent_runs"
    const val NOTIFICATION_ID = 7104
  }
}
