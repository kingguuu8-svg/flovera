package com.flovera.app

import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.ToolContextRetentionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEventRecorderInstrumentedTest {
  @Test
  fun recorderPublishesSnapshotsWhenToolsFinish() {
    val snapshots = mutableListOf<Int>()
    val recorder = ToolEventRecorder { events -> snapshots += events.size }

    recorder.record("fetch_url", "url=https://example.com", "ok")
    recorder.record("write_file", "path=index.html", "wrote")

    assertEquals(listOf(1, 2), snapshots)
    assertEquals(2, recorder.snapshot().size)
    assertTrue(recorder.snapshot().last().result.contains("wrote"))
  }

  @Test
  fun recorderAnnotatesToolEventsForContextRetention() {
    val recorder = ToolEventRecorder()
    recorder.record("workspace_command_run", "argv=[python, broken.py]", "Traceback: boom")
    recorder.record("write_file", "path=index.html", "wrote index.html")

    val failedCommand = recorder.snapshot().first()
    assertEquals(false, failedCommand.success)
    assertEquals("command", failedCommand.resultKind)
    assertEquals(ToolContextRetentionPolicy.RETENTION_ACTIVE_CRITICAL, failedCommand.retentionPriority)

    val writeFile = recorder.snapshot().last()
    assertEquals(true, writeFile.success)
    assertEquals("file_write", writeFile.resultKind)
    assertEquals(ToolContextRetentionPolicy.RETENTION_STRUCTURED_MEMORY, writeFile.retentionPriority)
  }

  @Test
  fun recorderTreatsSkillReadsAsActiveContext() {
    val recorder = ToolEventRecorder()
    recorder.record(
      "read_file",
      "path=.flovera/skills/flovera-android-webview-app/SKILL.md",
      "# Flovera Android WebView App",
    )

    val event = recorder.snapshot().single()
    assertEquals(true, event.success)
    assertEquals("skill_read", event.resultKind)
    assertEquals(ToolContextRetentionPolicy.RETENTION_ACTIVE_CRITICAL, event.retentionPriority)
    assertTrue(event.retentionReason.contains("skill body read"))
  }
}
