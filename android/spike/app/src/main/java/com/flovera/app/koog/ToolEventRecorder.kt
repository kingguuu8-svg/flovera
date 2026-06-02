package com.flovera.app.koog

import com.flovera.app.session.ToolEvent
import com.flovera.app.session.ToolContextRetentionPolicy

class ToolEventRecorder(
  private val onChanged: (List<ToolEvent>) -> Unit = {},
) {
  private val events = mutableListOf<ToolEvent>()

  @Synchronized
  fun record(name: String, args: String, result: String) {
    val storedResult = result.take(4_000)
    val decision = ToolContextRetentionPolicy.classify(name, args, result)
    events += ToolEvent(
      name = name,
      args = args,
      result = storedResult,
      success = decision.success,
      resultKind = decision.resultKind,
      outputChars = result.length,
      outputTruncated = result.length > storedResult.length,
      retentionPriority = decision.retentionPriority,
      retentionReason = decision.retentionReason,
    )
    onChanged(events.toList())
  }

  @Synchronized
  fun snapshot(): List<ToolEvent> = events.toList()
}
