package com.flovera.app.koog

import com.flovera.app.session.ToolEvent
import com.flovera.app.session.ToolContextRetentionPolicy

class ToolEventRecorder(
  private val onChanged: (List<ToolEvent>) -> Unit = {},
) {
  private val events = mutableListOf<ToolEvent>()

  @Synchronized
  fun record(name: String, args: String, result: String) {
    val decision = ToolContextRetentionPolicy.classify(name, args, result)
    val storedResult = result.take(storageLimit(decision.retentionPriority))
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

  private fun storageLimit(priority: String): Int {
    return when (priority) {
      ToolContextRetentionPolicy.RETENTION_ACTIVE_CRITICAL -> 16_000
      ToolContextRetentionPolicy.RETENTION_RECENT_FULL,
      ToolContextRetentionPolicy.RETENTION_STRUCTURED_MEMORY -> 8_000
      else -> 4_000
    }
  }
}
