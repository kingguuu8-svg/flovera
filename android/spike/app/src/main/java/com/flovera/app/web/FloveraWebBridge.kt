package com.flovera.app.web

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

class FloveraWebBridge(
  private val context: Context,
  private val artifactActions: ArtifactActions? = null,
) {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())

  @JavascriptInterface
  fun postEvent(json: String): String {
    val payload = runCatching { JSONObject(json) }.getOrNull() ?: return "invalid json"
    return when (payload.optString("type")) {
      "toast" -> toast(payload.optString("message"))
      "notification" -> notify(payload.toString())
      else -> "unsupported event type"
    }
  }

  @JavascriptInterface
  fun toast(message: String): String {
    val text = message.take(MAX_TOAST_CHARS).ifBlank { return "missing message" }
    mainHandler.post {
      Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }
    return "ok"
  }

  @JavascriptInterface
  fun notify(json: String): String {
    val payload = runCatching { JSONObject(json) }.getOrNull() ?: return "invalid json"
    val title = payload.optString("title").take(MAX_TITLE_CHARS).ifBlank { "flovera" }
    val body = payload.optString("body").take(MAX_BODY_CHARS).ifBlank { return "missing body" }
    if (needsNotificationPermission()) {
      val activity = context.findActivity() ?: return "notification permission not granted"
      pendingNotification = PendingNotification(title = title, body = body)
      mainHandler.post {
        ActivityCompat.requestPermissions(
          activity,
          arrayOf(Manifest.permission.POST_NOTIFICATIONS),
          NOTIFICATION_PERMISSION_REQUEST_CODE,
        )
      }
      return "notification permission requested"
    }

    ensureNotificationChannel()
    val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .build()

    NotificationManagerCompat.from(appContext).notify(nextNotificationId(), notification)
    return "ok"
  }

  @JavascriptInterface
  fun runAction(actionId: String, inputJson: String): String {
    return artifactActions?.runAction(actionId, inputJson) ?: """{"status":"unsupported","error":"workspace artifact actions are not available"}"""
  }

  @JavascriptInterface
  fun getJob(jobId: String): String {
    return artifactActions?.getJob(jobId) ?: """{"status":"unsupported","error":"workspace artifact jobs are not available"}"""
  }

  @JavascriptInterface
  fun cancelJob(jobId: String): String {
    return artifactActions?.cancelJob(jobId) ?: """{"status":"unsupported","error":"workspace artifact job cancellation is not available"}"""
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = appContext.getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(CHANNEL_ID, "flovera workspace", NotificationManager.IMPORTANCE_DEFAULT)
    manager.createNotificationChannel(channel)
  }

  private fun nextNotificationId(): Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

  private fun postNotification(title: String, body: String) {
    ensureNotificationChannel()
    val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .build()

    NotificationManagerCompat.from(appContext).notify(nextNotificationId(), notification)
  }

  private fun needsNotificationPermission(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
  }

  private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
      if (current is Activity) return current
      current = current.baseContext
    }
    return null
  }

  companion object {
    const val CHANNEL_ID = "flovera_workspace_events"
    const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    const val MAX_TITLE_CHARS = 80
    const val MAX_BODY_CHARS = 500
    const val MAX_TOAST_CHARS = 160

    private var pendingNotification: PendingNotification? = null

    fun flushPendingNotification(context: Context) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
      ) {
        return
      }
      val pending = pendingNotification ?: return
      pendingNotification = null
      FloveraWebBridge(context).postNotification(pending.title, pending.body)
    }
  }

  interface ArtifactActions {
    fun runAction(actionId: String, inputJson: String): String
    fun getJob(jobId: String): String
    fun cancelJob(jobId: String): String
  }

  private data class PendingNotification(
    val title: String,
    val body: String,
  )
}
