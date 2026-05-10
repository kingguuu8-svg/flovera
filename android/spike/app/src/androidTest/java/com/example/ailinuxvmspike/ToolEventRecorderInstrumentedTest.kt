package com.example.ailinuxvmspike

import com.example.ailinuxvmspike.koog.ToolEventRecorder
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
}
