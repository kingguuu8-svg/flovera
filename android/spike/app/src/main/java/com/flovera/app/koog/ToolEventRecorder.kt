package com.flovera.app.koog

import com.flovera.app.session.ToolEvent

class ToolEventRecorder(
  private val onChanged: (List<ToolEvent>) -> Unit = {},
) {
  private val events = mutableListOf<ToolEvent>()

  @Synchronized
  fun record(name: String, args: String, result: String) {
    events += ToolEvent(name = name, args = args, result = result.take(4_000))
    onChanged(events.toList())
  }

  @Synchronized
  fun snapshot(): List<ToolEvent> = events.toList()
}
