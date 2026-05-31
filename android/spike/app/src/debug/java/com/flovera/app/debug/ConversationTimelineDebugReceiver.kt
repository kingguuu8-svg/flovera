package com.flovera.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flovera.app.agent.AgentRunController
import com.flovera.app.agent.AgentRunEvent
import com.flovera.app.agent.AgentRunEventSink
import com.flovera.app.agent.AgentRunEventType
import com.flovera.app.agent.AgentRunGuidanceProvider
import com.flovera.app.config.AppSettings
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.ConversationTranscriptEvent
import com.flovera.app.session.SessionController
import com.flovera.app.session.SessionMessage
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class ConversationTimelineDebugReceiver : BroadcastReceiver() {
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
    val workspace = WorkspaceManager(context, "timeline-debug-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val session = store.create("Conversation timeline debug ${System.currentTimeMillis()}")
    val extraTranscriptEvents = mutableListOf<ConversationTranscriptEvent>()
    val controller = AgentRunController(
      runtime = TimelineRuntime(extraTranscriptEvents),
      scope = scope,
    )
    val job = controller.submit(
      input = "timeline debug",
      settings = AppSettings(),
      session = session,
      workspace = workspace,
      appendUserPrompt = sessions::appendUserPrompt,
      appendContextRecord = sessions::appendContextRecord,
      appendCompressionDivider = sessions::appendCompressionDivider,
      appendMessage = sessions::appendMessage,
      onStarted = { _, _ -> },
      onDraft = { },
      onSessionUpdated = { _, _ -> },
      onFinished = { updated, succeeded -> writeVerificationResult(context, store, session, updated, succeeded) },
      additionalTranscriptEvents = { extraTranscriptEvents.toList() },
    )
    require(job != null) { "Verification prompt was blank." }
    job.join()
  }

  private fun writeVerificationResult(
    context: Context,
    store: AgentSessionStore,
    staleSession: AgentSession,
    updated: AgentSession,
    succeeded: Boolean,
  ) {
    val first = store.appendMessage(staleSession, SessionMessage(role = "user", content = "mid-run insert"))
    val preserved = store.appendMessage(staleSession, SessionMessage(role = "assistant", content = "stale final"))
    val appendPreservedLatest = preserved.messages.map { it.content }.containsAll(
      listOf("timeline debug", "mid-run insert", "stale final"),
    ) && first.messages.any { it.content == "mid-run insert" }

    val assistant = updated.messages.lastOrNull()
    val transcript = assistant?.transcriptEvents.orEmpty()
    val textBefore = transcript.indexOfFirst { it.type == "assistant_text" && it.content.contains("before guidance") }
    val tool = transcript.indexOfFirstAfter(textBefore) { it.type == "tool_call" }
    val guidanceBubble = transcript.indexOfFirstAfter(tool) { it.type == "user_guidance" && it.content == GUIDANCE_TEXT }
    val guidanceStatus = transcript.indexOfFirstAfter(guidanceBubble) { it.type == "guidance" }
    val textAfter = transcript.indexOfFirstAfter(guidanceStatus) { it.type == "assistant_text" && it.content.contains("after tool") }
    val pass = succeeded &&
      appendPreservedLatest &&
      textBefore >= 0 &&
      tool > textBefore &&
      guidanceBubble > tool &&
      guidanceStatus > guidanceBubble &&
      textAfter > guidanceStatus

    writeResult(
      context,
      JSONObject()
        .put("status", if (pass) "passed" else "failed")
        .put("sessionId", updated.id)
        .put("succeeded", succeeded)
        .put("appendPreservedLatest", appendPreservedLatest)
        .put("textBeforeIndex", textBefore)
        .put("guidanceBubbleIndex", guidanceBubble)
        .put("guidanceStatusIndex", guidanceStatus)
        .put("toolIndex", tool)
        .put("textAfterIndex", textAfter)
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

  private class TimelineRuntime(
    private val extraTranscriptEvents: MutableList<ConversationTranscriptEvent>,
  ) : AgentRuntime {
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
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "before guidance "))
      recorder.record("read_file", "path=README.md", "# Android Agent Workspace")
      val now = System.currentTimeMillis()
      extraTranscriptEvents += ConversationTranscriptEvent(
        type = "user_guidance",
        role = "user",
        content = GUIDANCE_TEXT,
        timestampMillis = now,
      )
      extraTranscriptEvents += ConversationTranscriptEvent(
        type = "guidance",
        title = "Guidance applied",
        detail = "This guidance was inserted after the completed tool result and before the next model request.",
        timestampMillis = now,
        status = "applied",
      )
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "after tool"))
      return "before guidance after tool"
    }
  }

  companion object {
    const val ACTION_RUN = "com.flovera.app.debug.RUN_CONVERSATION_EVENT_TIMELINE"
    const val RESULT_PATH = "debug/conversation-event-timeline-result.json"
    private const val GUIDANCE_TEXT = "keep the UI compact"
  }
}
