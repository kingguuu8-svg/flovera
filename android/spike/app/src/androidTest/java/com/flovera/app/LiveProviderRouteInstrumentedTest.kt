package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.agent.AgentRunEvent
import com.flovera.app.agent.AgentRunEventSink
import com.flovera.app.agent.AgentRunEventType
import com.flovera.app.config.AppSettings
import com.flovera.app.koog.KoogAgentRuntime
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveProviderRouteInstrumentedTest {
  @Test
  fun zaiStreamingHiUsesConfiguredProviderRoute() = runBlocking {
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue(arguments.getString("liveProviderRouteSmoke") == "true")
    val apiKey = arguments.getString("zaiApiKey").orEmpty()
    assumeTrue("zaiApiKey instrumentation argument is required", apiKey.isNotBlank())

    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "live-provider-zai-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    val session = AgentSessionStore(context).create("Live Z.AI provider route")
    val events = mutableListOf<AgentRunEvent>()
    val settings = AppSettings(
      provider = "zai",
      model = "glm-5",
      activeWorkspaceId = workspaceId,
      activeSessionId = session.id,
      networkEnabled = true,
      webSearchEnabled = false,
      language = "zh",
    ).withApiKey("zai", apiKey)

    val output = withTimeout(120_000) {
      KoogAgentRuntime().runStreaming(
        input = "Reply exactly with: hi",
        agentRunId = "${session.id}-live-zai-route",
        settings = settings,
        session = session,
        workspace = workspace,
        recorder = ToolEventRecorder(),
        eventSink = AgentRunEventSink { event -> events += event },
      )
    }

    assertTrue("provider output should not be blank", output.isNotBlank())
    assertTrue(
      "provider should emit at least one model text event",
      events.any { event ->
        event.type == AgentRunEventType.MODEL_TEXT_DELTA && event.modelTextDelta.isNotBlank()
      },
    )
  }
}
