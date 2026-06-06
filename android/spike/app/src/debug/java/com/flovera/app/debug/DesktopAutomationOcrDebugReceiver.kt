package com.flovera.app.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.koog.WorkspaceCommandRunTool
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class DesktopAutomationOcrDebugActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val appContext = applicationContext
    Thread {
      try {
        runBlocking {
          DesktopAutomationOcrDebugVerifier.runVerification(appContext)
        }
      } catch (error: Throwable) {
        DesktopAutomationOcrDebugVerifier.writeResult(
          appContext,
          JSONObject()
            .put("status", "failed")
            .put("error", error.stackTraceToString().take(4_000)),
        )
      } finally {
        finish()
      }
    }.start()
  }
}

class DesktopAutomationOcrDebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_RUN) return
    val pendingResult = goAsync()
    val appContext = context.applicationContext
    Thread {
      try {
        runBlocking {
          DesktopAutomationOcrDebugVerifier.runVerification(appContext)
        }
      } catch (error: Throwable) {
        DesktopAutomationOcrDebugVerifier.writeResult(
          appContext,
          JSONObject()
            .put("status", "failed")
            .put("error", error.stackTraceToString().take(4_000)),
        )
      } finally {
        pendingResult.finish()
      }
    }.start()
  }

  companion object {
    const val ACTION_RUN = "com.flovera.app.debug.RUN_DESKTOP_AUTOMATION_OCR"
  }
}

object DesktopAutomationOcrDebugVerifier {
  const val RESULT_PATH = "debug/desktop-automation-ocr-result.json"

  suspend fun runVerification(context: Context) {
    context.startActivity(
      Intent().apply {
        component = ComponentName("com.flovera.app.test", "com.flovera.app.DesktopAutomationFixtureActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
      },
    )
    Thread.sleep(1_500)

    val workspace = WorkspaceManager(context, "desktop-ocr-debug-${System.currentTimeMillis()}").also {
      it.ensureSeedFiles()
    }
    val tool = WorkspaceCommandRunTool(workspace, ToolEventRecorder())
    val outputs = JSONArray()
    fun record(name: String, output: String): String {
      outputs.put(JSONObject().put("name", name).put("output", output.take(20_000)))
      return output
    }

    val task = record(
      "task-start",
      tool.execute(
        WorkspaceCommandRunTool.Args(
          argv = listOf("android", "ui", "task", "start", "--goal", "debug OCR desktop automation"),
          snapshotBeforeRun = false,
        ),
      ),
    )
    val wait = record(
      "wait-package",
      tool.execute(
        WorkspaceCommandRunTool.Args(
          argv = listOf("android", "ui", "wait", "--package", "com.flovera.app.test", "--timeout-ms", "15000"),
          snapshotBeforeRun = false,
          timeoutMs = 20_000,
        ),
      ),
    )
    val inspect = record(
      "inspect-ocr",
      tool.execute(
        WorkspaceCommandRunTool.Args(
          argv = listOf(
            "android",
            "ui",
            "inspect",
            "--with-ocr",
            "--filter-ocr-text",
            "OCR TARGET",
            "--max-nodes",
            "80",
          ),
          snapshotBeforeRun = false,
          timeoutMs = 30_000,
        ),
      ),
    )
    val click = record(
      "click-ocr",
      tool.execute(
        WorkspaceCommandRunTool.Args(
          argv = listOf(
            "android",
            "ui",
            "click",
            "--ocr-text",
            "OCR TARGET",
            "--action-id",
            "debug-ocr-target-${System.currentTimeMillis()}",
            "--expect-text",
            "OCR target clicked",
            "--verify-timeout-ms",
            "15000",
          ),
          snapshotBeforeRun = false,
          timeoutMs = 45_000,
        ),
      ),
    )

    val passed = listOf(task, wait, inspect, click).all { it.contains("status=ok") } &&
      wait.contains("\"matched\": true") &&
      inspect.contains("\"withOcr\": true") &&
      inspect.contains("\"ocrTextMatched\": true") &&
      click.contains("\"matched\": true") &&
      click.contains("\"strategy\": \"ocr_")

    writeResult(
      context,
      JSONObject()
        .put("status", if (passed) "passed" else "failed")
        .put("outputs", outputs),
    )
  }

  fun writeResult(context: Context, result: JSONObject) {
    val file = File(context.filesDir, RESULT_PATH)
    file.parentFile?.mkdirs()
    file.writeText(result.toString(2), Charsets.UTF_8)
  }
}
