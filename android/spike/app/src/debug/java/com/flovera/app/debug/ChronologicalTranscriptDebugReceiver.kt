package com.flovera.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flovera.app.agent.AgentRunController
import com.flovera.app.config.SettingsStore
import com.flovera.app.koog.KoogAgentRuntime
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.SessionController
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class ChronologicalTranscriptDebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_RUN) return
    val pendingResult = goAsync()
    val appContext = context.applicationContext
    Thread {
      try {
        runBlocking {
          runVerification(appContext, this)
        }
      } catch (error: Throwable) {
        writeResult(
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

  private suspend fun runVerification(context: Context, scope: CoroutineScope) {
    val settingsFromApp = SettingsStore(context).load()
    val apiKey = settingsFromApp.apiKeyFor("deepseek")
    require(apiKey.isNotBlank()) { "DeepSeek API key is not configured in app settings." }

    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val workspaceId = "chrono-debug-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    workspace.writeFile(
      path = "AGENT.md",
      content = """
        # Agent Rules

        - For this verification, call workspace tools multiple times.
        - Write visible assistant text between tool calls.
        - Keep the final answer concise.
      """.trimIndent(),
      createAutoSnapshot = false,
    )
    val session = store.create("Chronological transcript debug ${System.currentTimeMillis()}")
    val settings = settingsFromApp.copy(
      provider = "deepseek",
      providerApiKeys = settingsFromApp.providerApiKeys + ("deepseek" to apiKey),
      apiKey = apiKey,
      activeWorkspaceId = workspaceId,
      activeSessionId = session.id,
      maxAgentIterations = 12,
    )

    val controller = AgentRunController(runtime = KoogAgentRuntime(), scope = scope)
    val job = controller.submit(
      input = PROMPT,
      settings = settings,
      session = session,
      workspace = workspace,
      appendUserPrompt = sessions::appendUserPrompt,
      appendContextRecord = sessions::appendContextRecord,
      appendCompressionDivider = sessions::appendCompressionDivider,
      appendPromptContextBlocks = sessions::appendPromptContextBlocks,
      appendMessage = sessions::appendMessage,
      onStarted = { _, _ -> },
      onDraft = { },
      onSessionUpdated = { _, _ -> },
      onFinished = { updated, succeeded -> writeVerificationResult(context, updated, succeeded) },
    )
    require(job != null) { "Verification prompt was blank." }
    job.join()
  }

  private fun writeVerificationResult(context: Context, session: AgentSession, succeeded: Boolean) {
    val assistant = session.messages.lastOrNull()
    val transcript = assistant?.transcriptEvents.orEmpty()
    val firstTool = transcript.indexOfFirst { it.type == "tool_call" }
    val textAfterTool = transcript.indexOfFirstAfter(firstTool) {
      it.type == "assistant_text" && it.content.isNotBlank()
    }
    val toolAfterText = transcript.indexOfFirstAfter(textAfterTool) {
      it.type == "tool_call"
    }
    val pass = succeeded && firstTool >= 0 && textAfterTool >= 0 && toolAfterText > textAfterTool
    writeResult(
      context,
      JSONObject()
        .put("status", if (pass) "passed" else "failed")
        .put("sessionId", session.id)
        .put("prompt", PROMPT)
        .put("succeeded", succeeded)
        .put("firstToolIndex", firstTool)
        .put("textAfterToolIndex", textAfterTool)
        .put("toolAfterTextIndex", toolAfterText)
        .put("types", JSONArray(transcript.map { it.type })),
    )
  }

  private fun writeResult(context: Context, result: JSONObject) {
    val file = File(context.filesDir, RESULT_PATH)
    file.parentFile?.mkdirs()
    file.writeText(result.toString(2), Charsets.UTF_8)
  }

  private inline fun <T> List<T>.indexOfFirstAfter(startIndex: Int, predicate: (T) -> Boolean): Int {
    if (startIndex < 0) return -1
    for (index in (startIndex + 1)..lastIndex) {
      if (predicate(this[index])) return index
    }
    return -1
  }

  companion object {
    const val ACTION_RUN = "com.flovera.app.debug.RUN_CHRONOLOGICAL_TRANSCRIPT"
    const val RESULT_PATH = "debug/chronological-transcript-result.json"
    const val PROMPT = "尝试多次调用工具，并在调用工具之间输出文本"
  }
}
