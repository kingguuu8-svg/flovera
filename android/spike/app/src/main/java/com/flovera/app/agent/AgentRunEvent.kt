package com.flovera.app.agent

import com.flovera.app.session.AgentRunTimelineEvent
import com.flovera.app.session.ToolEvent

data class AgentRunEvent(
  val type: String,
  val title: String = "",
  val detail: String = "",
  val status: String = "",
  val finalTextDelta: String = "",
  val toolEvents: List<ToolEvent> = emptyList(),
  val timelineEvent: AgentRunTimelineEvent? = null,
  val timestampMillis: Long = System.currentTimeMillis(),
)

fun interface AgentRunEventSink {
  fun emit(event: AgentRunEvent)

  companion object {
    val Noop = AgentRunEventSink { }
  }
}

object AgentRunEventType {
  const val RUN_STARTED = "run_started"
  const val CONTEXT_CHECKED = "context_checked"
  const val COMPRESSION_STARTED = "compression_started"
  const val COMPRESSION_COMPLETED = "compression_completed"
  const val TOOL_EVENTS_CHANGED = "tool_events_changed"
  const val FINAL_TEXT_DELTA = "final_text_delta"
  const val RUN_COMPLETED = "run_completed"
  const val RUN_FAILED = "run_failed"
  const val RUN_INTERRUPTED = "run_interrupted"
}
