package com.flovera.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flovera.app.agent.AgentRunController
import com.flovera.app.agent.AgentRunEvent
import com.flovera.app.agent.AgentRunEventSink
import com.flovera.app.agent.AgentRunEventType
import com.flovera.app.agent.AgentRunGuidanceProvider
import com.flovera.app.agent.ContextOverflowDetection
import com.flovera.app.agent.ContextOverflowDetector
import com.flovera.app.agent.HANDOFF_SOURCE_LLM
import com.flovera.app.agent.InterruptedRunHandoff
import com.flovera.app.agent.SessionHandoffCompression
import com.flovera.app.agent.SessionHandoffCompressor
import com.flovera.app.config.AppSettings
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.SESSION_ROLE_COMPRESSION
import com.flovera.app.session.SessionController
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class ContextOverflowRecoveryDebugReceiver : BroadcastReceiver() {
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
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val workspace = WorkspaceManager(context, "overflow-debug-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val session = store.create("Context overflow recovery debug ${System.currentTimeMillis()}")
    val runtime = OverflowThenSuccessRuntime()
    val compressor = DebugHandoffCompressor()
    val controller = AgentRunController(
      runtime = runtime,
      handoffCompressor = compressor,
      contextOverflowDetector = DebugOverflowDetector(),
      scope = scope,
    )
    var finishedSession: AgentSession? = null
    var succeeded = false
    val job = controller.submit(
      input = "recover from context overflow",
      settings = AppSettings(),
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
      onFinished = { updated, success ->
        finishedSession = updated
        succeeded = success
      },
    )
    require(job != null) { "Verification prompt was blank." }
    job.join()

    val updated = finishedSession ?: store.load(session.id)
    val finalMessage = updated?.messages?.lastOrNull()
    val compressionMessage = updated?.messages?.firstOrNull { it.role == SESSION_ROLE_COMPRESSION }
    val pass = succeeded &&
      runtime.attempts == 2 &&
      compressor.interruptedRun?.assistantDraft?.contains("partial before overflow") == true &&
      compressor.interruptedRun?.toolEvents?.any { it.name == "read_file" } == true &&
      compressionMessage?.content?.contains("Recovered handoff summary") == true &&
      finalMessage?.role == "assistant" &&
      finalMessage.content == "retry output" &&
      finalMessage.toolEvents.any { it.name == "retry_tool" } &&
      updated?.contextRecords?.any { it.source == "agent_run_overflow_recovery" && it.compressed } == true

    writeResult(
      context,
      JSONObject()
        .put("status", if (pass) "passed" else "failed")
        .put("sessionId", updated?.id.orEmpty())
        .put("succeeded", succeeded)
        .put("attempts", runtime.attempts)
        .put("interruptedDraft", compressor.interruptedRun?.assistantDraft.orEmpty())
        .put("interruptedToolCount", compressor.interruptedRun?.toolEvents?.size ?: 0)
        .put("hasCompressionMessage", compressionMessage != null)
        .put("finalRole", finalMessage?.role.orEmpty())
        .put("finalContent", finalMessage?.content.orEmpty()),
    )
  }

  private fun writeResult(context: Context, result: JSONObject) {
    val file = File(context.filesDir, RESULT_PATH)
    file.parentFile?.mkdirs()
    file.writeText(result.toString(2), Charsets.UTF_8)
  }

  private class DebugOverflowDetector : ContextOverflowDetector {
    override suspend fun detect(settings: AppSettings, error: Throwable): ContextOverflowDetection {
      return ContextOverflowDetection(
        isOverflow = true,
        source = "debug",
        reason = error.message ?: error.toString(),
      )
    }
  }

  private class DebugHandoffCompressor : SessionHandoffCompressor {
    var interruptedRun: InterruptedRunHandoff? = null

    override suspend fun compress(
      settings: AppSettings,
      session: AgentSession,
      record: ContextUsageRecord,
      workspace: WorkspaceManager,
      interruptedRun: InterruptedRunHandoff?,
    ): SessionHandoffCompression {
      this.interruptedRun = interruptedRun
      return SessionHandoffCompression(
        summary = "Recovered handoff summary\n\n${interruptedRun?.assistantDraft.orEmpty()}",
        source = HANDOFF_SOURCE_LLM,
      )
    }

    override suspend fun summarizeAssistantFinal(
      settings: AppSettings,
      userContent: String,
      assistantContent: String,
      runContext: String,
    ): String {
      return "summary: ${assistantContent.take(240)}"
    }
  }

  private class OverflowThenSuccessRuntime : AgentRuntime {
    var attempts: Int = 0

    override suspend fun run(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
    ): String {
      return "fallback"
    }

    override suspend fun runStreaming(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
      eventSink: AgentRunEventSink,
      guidanceProvider: AgentRunGuidanceProvider,
    ): String {
      attempts += 1
      if (attempts == 1) {
        eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "partial before overflow"))
        recorder.record("read_file", "path=large.txt", "large tool result")
        error("context length exceeded: maximum number of tokens reached")
      }
      require(session.messages.any { it.role == SESSION_ROLE_COMPRESSION }) { "Retry session did not include compression handoff." }
      recorder.record("retry_tool", "{}", "ok")
      return "retry output"
    }
  }

  companion object {
    const val ACTION_RUN = "com.flovera.app.debug.RUN_CONTEXT_OVERFLOW_RECOVERY"
    const val RESULT_PATH = "debug/context-overflow-recovery-result.json"
  }
}
