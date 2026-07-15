package com.flovera.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class FloveraApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    FloveraCrashReporter.install(this)
  }
}

object FloveraCrashReporter {
  private const val PREFS = "flovera_crash_reporter"
  private const val LAST_EXIT_KEY = "last_exit_timestamp"

  fun install(app: Application) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      writeCrashRecord(
        app = app,
        kind = "uncaught_exception",
        fields = mapOf(
          "thread" to thread.name,
          "exception" to throwable::class.java.name,
          "message" to throwable.message.orEmpty(),
          "stack" to stackTrace(throwable),
        ),
      )
      previous?.uncaughtException(thread, throwable)
    }
    Thread {
      runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
      runCatching { recordHistoricalExitReasons(app) }
      writeCrashRecord(
        app = app,
        kind = "process_start",
        fields = mapOf(
          "pid" to Process.myPid().toString(),
          "uid" to Process.myUid().toString(),
        ),
      )
    }.apply {
      name = "flovera-crash-reporter"
      isDaemon = true
    }.start()
  }

  private fun recordHistoricalExitReasons(app: Application) {
    val manager = app.getSystemService(ActivityManager::class.java) ?: return
    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val lastTimestamp = prefs.getLong(LAST_EXIT_KEY, 0L)
    val exits = runCatching {
      manager.getHistoricalProcessExitReasons(app.packageName, 0, 8)
    }.getOrDefault(emptyList())
    var newestTimestamp = lastTimestamp
    exits.sortedBy { it.timestamp }.forEach { info ->
      if (info.timestamp <= lastTimestamp) return@forEach
      writeCrashRecord(
        app = app,
        kind = "historical_process_exit",
        fields = mapOf(
          "pid" to info.pid.toString(),
          "processName" to info.processName.orEmpty(),
          "reason" to exitReasonName(info.reason),
          "reasonCode" to info.reason.toString(),
          "status" to info.status.toString(),
          "importance" to info.importance.toString(),
          "pssKb" to info.pss.toString(),
          "rssKb" to info.rss.toString(),
          "description" to info.description.orEmpty(),
          "trace" to traceText(info),
        ),
        timestampMillis = info.timestamp,
      )
      newestTimestamp = maxOf(newestTimestamp, info.timestamp)
    }
    if (newestTimestamp != lastTimestamp) {
      prefs.edit().putLong(LAST_EXIT_KEY, newestTimestamp).apply()
    }
  }

  private fun writeCrashRecord(
    app: Application,
    kind: String,
    fields: Map<String, String>,
    timestampMillis: Long = System.currentTimeMillis(),
  ) {
    val payload = JSONObject()
      .put("ts", timestampMillis)
      .put("kind", kind)
    fields.forEach { (key, value) -> payload.put(key, value) }
    listOf(
      File(app.filesDir, ".flovera/logs/app-crash.jsonl"),
      File(app.filesDir, "workspaces/default/.flovera/logs/app-crash.jsonl"),
    ).forEach { file ->
      runCatching {
        file.parentFile?.mkdirs()
        file.appendText(payload.toString() + "\n")
      }
    }
  }

  private fun stackTrace(throwable: Throwable): String {
    val writer = StringWriter()
    throwable.printStackTrace(PrintWriter(writer))
    return writer.toString().take(32_000)
  }

  private fun traceText(info: ApplicationExitInfo): String {
    return runCatching {
      info.traceInputStream?.bufferedReader()?.use { it.readText().take(32_000) }.orEmpty()
    }.getOrDefault("")
  }

  private fun exitReasonName(reason: Int): String {
    return when (reason) {
      ApplicationExitInfo.REASON_ANR -> "ANR"
      ApplicationExitInfo.REASON_CRASH -> "CRASH"
      ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
      ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
      ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
      ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
      ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
      ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
      ApplicationExitInfo.REASON_OTHER -> "OTHER"
      ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
      ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
      ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
      ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
      16 -> "PACKAGE_UPDATED"
      else -> "UNRECOGNIZED"
    }
  }
}
