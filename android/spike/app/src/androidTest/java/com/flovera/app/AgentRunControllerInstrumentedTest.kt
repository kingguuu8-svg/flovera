package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.agent.AgentContextBudget
import com.flovera.app.agent.AgentRunController
import com.flovera.app.config.AppSettings
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.SESSION_ROLE_COMPRESSION
import com.flovera.app.session.SessionController
import com.flovera.app.session.SessionMessage
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunControllerInstrumentedTest {
  @Test
  fun runControllerPersistsUserDraftToolEventsAndAssistant() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Run controller ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "run-controller-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val runtime = FakeAgentRuntime()
    val controller = AgentRunController(runtime = runtime, scope = this)
    val drafts = mutableListOf<SessionMessage>()
    var startedSession: AgentSession? = null
    var startedDraft: SessionMessage? = null
    var finishedSession: AgentSession? = null
    var succeeded: Boolean? = null

    val job = controller.submit(
      input = "  create file  ",
      settings = AppSettings(),
      session = session,
      workspace = workspace,
      appendUserPrompt = sessions::appendUserPrompt,
      appendContextRecord = sessions::appendContextRecord,
      appendCompressionDivider = sessions::appendCompressionDivider,
      appendMessage = sessions::appendMessage,
      onStarted = { withUser, draft ->
        startedSession = withUser
        startedDraft = draft
      },
      onDraft = { draft -> drafts += draft },
      onFinished = { updated, success ->
        finishedSession = updated
        succeeded = success
      },
    )

    assertNotNull(job)
    job!!.join()

    assertEquals("create file", runtime.inputSeen)
    assertEquals("Working...", startedDraft?.content)
    assertEquals(1, startedSession?.messages?.size)
    assertEquals(1, startedSession?.contextRecords?.size)
    val contextRecord = startedSession?.contextRecords?.single()
    assertTrue(contextRecord?.approximateTokens ?: 0 > 0)
    assertEquals("deepseek", contextRecord?.provider)
    assertEquals("deepseek-v4-pro", contextRecord?.model)
    assertEquals(1_000_000, contextRecord?.modelContextWindowTokens)
    assertEquals("deepseek_catalog", contextRecord?.modelContextSource)
    assertNotNull(contextRecord?.contextUsagePermille)
    assertEquals(AgentContextBudget.STATUS_SAFE, contextRecord?.contextBudgetStatus)
    assertEquals("user", startedSession?.messages?.single()?.role)
    assertEquals("create file", startedSession?.messages?.single()?.content)
    assertEquals("fake_tool", drafts.single().toolEvents.single().name)
    assertEquals(true, succeeded)
    assertEquals(2, finishedSession?.messages?.size)
    assertEquals("assistant output", finishedSession?.messages?.last()?.content)
    assertTrue(finishedSession?.messages?.last()?.toolEvents?.any { it.name == "fake_tool" } == true)
  }

  @Test
  fun runControllerShowsCompressionDraftAndAddsDividerWhenBudgetRequiresIt() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Compression run ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "compression-run-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val runtime = FakeAgentRuntime()
    val controller = AgentRunController(
      runtime = runtime,
      scope = this,
      shouldCompressContext = { true },
    )
    val drafts = mutableListOf<SessionMessage>()
    var startedSession: AgentSession? = null
    var startedDraft: SessionMessage? = null
    var finishedSession: AgentSession? = null

    val job = controller.submit(
      input = "continue after compression",
      settings = AppSettings(),
      session = session,
      workspace = workspace,
      appendUserPrompt = sessions::appendUserPrompt,
      appendContextRecord = sessions::appendContextRecord,
      appendCompressionDivider = sessions::appendCompressionDivider,
      appendMessage = sessions::appendMessage,
      onStarted = { withContext, draft ->
        startedSession = withContext
        startedDraft = draft
      },
      onDraft = { drafts += it },
      onFinished = { updated, _ -> finishedSession = updated },
    )

    assertNotNull(job)
    job!!.join()

    assertEquals("Compressing context...", startedDraft?.content)
    assertTrue(startedSession?.messages?.any { it.role == SESSION_ROLE_COMPRESSION } == true)
    assertTrue(runtime.sessionSeen?.messages?.any { it.role == SESSION_ROLE_COMPRESSION } == true)
    assertTrue(drafts.any { it.content == "Working..." })
    assertEquals("assistant", finishedSession?.messages?.last()?.role)
  }

  @Test
  fun contextBudgetEvaluatorClassifiesThresholdStates() {
    val unknown = AgentContextBudget.evaluate(
      tokens = 1_000,
      contextWindowTokens = null,
      compressionThresholdPercent = 82,
    )
    val safe = AgentContextBudget.evaluate(
      tokens = 100,
      contextWindowTokens = 1_000,
      compressionThresholdPercent = 82,
    )
    val watch = AgentContextBudget.evaluate(
      tokens = 700,
      contextWindowTokens = 1_000,
      compressionThresholdPercent = 82,
    )
    val recommended = AgentContextBudget.evaluate(
      tokens = 830,
      contextWindowTokens = 1_000,
      compressionThresholdPercent = 82,
    )

    assertEquals(AgentContextBudget.STATUS_UNKNOWN, unknown.status)
    assertEquals(AgentContextBudget.STATUS_SAFE, safe.status)
    assertEquals(AgentContextBudget.STATUS_WATCH, watch.status)
    assertEquals(AgentContextBudget.STATUS_COMPRESSION_RECOMMENDED, recommended.status)
    assertEquals(830, recommended.usagePermille)
  }

  @Test
  fun runControllerRejectsBlankInputWithoutCallbacks() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val session = store.create("Blank run ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "blank-run-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val controller = AgentRunController(runtime = FakeAgentRuntime(), scope = this)
    var callbackCalled = false

    val job = controller.submit(
      input = "   ",
      settings = AppSettings(),
      session = session,
      workspace = workspace,
      appendUserPrompt = { current, _ ->
        callbackCalled = true
        current
      },
      appendContextRecord = { current, _ ->
        callbackCalled = true
        current
      },
      appendCompressionDivider = { current, _ ->
        callbackCalled = true
        current
      },
      appendMessage = { current, _ ->
        callbackCalled = true
        current
      },
      onStarted = { _, _ -> callbackCalled = true },
      onDraft = { callbackCalled = true },
      onFinished = { _, _ -> callbackCalled = true },
    )

    assertNull(job)
    assertFalse(callbackCalled)
  }

  @Test
  fun runControllerSavesWorkspaceErrorLogOnRuntimeFailure() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Failure ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "failure-run-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val controller = AgentRunController(runtime = FailingAgentRuntime(), scope = this)
    var finishedSession: AgentSession? = null
    var succeeded: Boolean? = null

    val job = controller.submit(
      input = "trigger failure",
      settings = AppSettings(apiKey = "secret-must-not-be-logged", networkEnabled = true, webSearchEnabled = true),
      session = session,
      workspace = workspace,
      appendUserPrompt = sessions::appendUserPrompt,
      appendContextRecord = sessions::appendContextRecord,
      appendCompressionDivider = sessions::appendCompressionDivider,
      appendMessage = sessions::appendMessage,
      onStarted = { _, _ -> },
      onDraft = { },
      onFinished = { updated, success ->
        finishedSession = updated
        succeeded = success
      },
    )

    assertNotNull(job)
    job!!.join()

    assertEquals(false, succeeded)
    val errorMessage = finishedSession?.messages?.lastOrNull()
    assertEquals("error", errorMessage?.role)
    assertTrue(errorMessage?.content?.contains("Error log saved: .flovera/logs/agent-error-") == true)
    val logs = File(workspace.root, ".flovera/logs").listFiles().orEmpty()
    assertEquals(1, logs.size)
    val logText = logs.single().readText()
    assertTrue(logText.contains("DeepSeekLLMClient"))
    assertTrue(logText.contains("fake_tool_before_failure"))
    assertTrue(logText.contains("networkEnabled: true"))
    assertTrue(logText.contains("agentRunId: ${session.id}-"))
    assertFalse(logText.contains("secret-must-not-be-logged"))
  }

  private class FakeAgentRuntime : AgentRuntime {
    var inputSeen: String = ""
    var sessionSeen: AgentSession? = null

    override suspend fun run(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
    ): String {
      inputSeen = input
      sessionSeen = session
      recorder.record("fake_tool", "{}", "ok")
      return "assistant output"
    }
  }

  private class FailingAgentRuntime : AgentRuntime {
    override suspend fun run(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
    ): String {
      recorder.record("fake_tool_before_failure", "{}", "ok")
      error("Error from client: DeepSeekLLMClient\nStatus code: 400\nreasoning_content must be passed back")
    }
  }
}
