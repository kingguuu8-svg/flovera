package com.example.ailinuxvmspike.koog

import com.example.ailinuxvmspike.session.ToolEvent

class ToolEventRecorder {
  private val events = mutableListOf<ToolEvent>()

  @Synchronized
  fun record(name: String, args: String, result: String) {
    events += ToolEvent(name = name, args = args, result = result.take(4_000))
  }

  @Synchronized
  fun snapshot(): List<ToolEvent> = events.toList()
}
