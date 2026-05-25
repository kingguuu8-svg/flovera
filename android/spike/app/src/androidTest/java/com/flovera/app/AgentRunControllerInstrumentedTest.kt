package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.agent.AgentContextBudget
import com.flovera.app.agent.AgentRunEvent
import com.flovera.app.agent.AgentRunController
import com.flovera.app.agent.AgentRunEventSink
import com.flovera.app.agent.AgentRunEventType
import com.flovera.app.agent.HANDOFF_SOURCE_LLM
import com.flovera.app.agent.SessionHandoffCompression
import com.flovera.app.agent.SessionHandoffCompressor
import com.flovera.app.config.AppSettings
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.SESSION_ROLE_COMPRESSION
import com.flovera.app.session.SessionController
import com.flovera.app.session.SessionMessage
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.CompletableDeferred
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
    val runEvents = mutableListOf<AgentRunEvent>()

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
      onSessionUpdated = { _, _ -> },
      onFinished = { updated, success ->
        finishedSession = updated
        succeeded = success
      },
      onRunEvent = { event -> runEvents += event },
    )

    assertNotNull(job)
    job!!.join()

    assertEquals("create file", runtime.inputSeen)
    assertEquals("Working...", startedDraft?.content)
    assertTrue(startedDraft?.runEvents?.any { it.type == AgentRunEventType.RUN_STARTED } == true)
    assertTrue(startedDraft?.runEvents?.any { it.title == "Context checkpoint" } == true)
    assertTrue(startedDraft?.runEvents?.any { it.title == "Thinking" } == true)
    assertTrue(startedDraft?.transcriptEvents?.any { it.type == "thinking" } == true)
    assertEquals(1, startedSession?.messages?.size)
    assertEquals(1, startedSession?.contextRecords?.size)
    val contextRecord = startedSession?.contextRecords?.single()
    assertTrue(contextRecord?.approximateTokens ?: 0 > 0)
    assertEquals("deepseek", contextRecord?.provider)
    assertEquals("deepseek-v4-pro", contextRecord?.model)
    assertEquals(1_000_000, contextRecord?.modelContextWindowTokens)
    assertEquals("deepseek_catalog", contextRecord?.modelContextSource)
    assertTrue(contextRecord?.toolSchemaChars ?: 0 > 0)
    assertTrue(contextRecord?.providerOverheadChars ?: 0 > 0)
    assertEquals(
      (contextRecord?.inputChars ?: 0) +
        (contextRecord?.historyChars ?: 0) +
        (contextRecord?.rulesChars ?: 0) +
        (contextRecord?.workspaceListingChars ?: 0) +
        (contextRecord?.toolSchemaChars ?: 0) +
        (contextRecord?.providerOverheadChars ?: 0),
      contextRecord?.estimatedRequestChars,
    )
    assertNotNull(contextRecord?.contextUsagePermille)
    assertEquals(AgentContextBudget.STATUS_SAFE, contextRecord?.contextBudgetStatus)
    assertEquals("user", startedSession?.messages?.single()?.role)
    assertEquals("create file", startedSession?.messages?.single()?.content)
    assertEquals("fake_tool", drafts.single().toolEvents.single().name)
    assertTrue(drafts.single().runEvents.any { it.title == "Tool: fake_tool" })
    assertTrue(drafts.single().transcriptEvents.any { it.type == "tool_call" && it.title == "Tool: fake_tool" })
    assertEquals(true, succeeded)
    assertEquals(2, finishedSession?.messages?.size)
    assertEquals("assistant output", finishedSession?.messages?.last()?.content)
    assertTrue(finishedSession?.messages?.last()?.toolEvents?.any { it.name == "fake_tool" } == true)
    assertTrue(finishedSession?.messages?.last()?.runEvents?.any { it.title == "Final response ready" } == true)
    assertTrue(finishedSession?.messages?.last()?.runEvents?.any { it.type == AgentRunEventType.RUN_COMPLETED } == true)
    val transcript = finishedSession?.messages?.last()?.transcriptEvents.orEmpty()
    assertTrue(transcript.any { it.type == "thinking" })
    assertTrue(transcript.any { it.type == "tool_call" && it.title == "Tool: fake_tool" })
    assertEquals("assistant_text", transcript.last().type)
    assertEquals("assistant output", transcript.last().content)
    assertTrue(runEvents.any { it.type == AgentRunEventType.RUN_STARTED })
    assertTrue(runEvents.any { it.type == AgentRunEventType.TOOL_EVENTS_CHANGED })
    assertTrue(runEvents.any { it.type == AgentRunEventType.RUN_COMPLETED })
    val checkpoint = workspace.readFile(".flovera/runs/latest.json")
    assertTrue(checkpoint, checkpoint.contains("\"status\": \"completed\""))
    assertTrue(checkpoint, checkpoint.contains("\"fake_tool\""))
  }

  @Test
  fun runControllerShowsCompressionDraftAndAddsDividerWhenBudgetRequiresIt() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Compression run ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "compression-run-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val runtime = FakeAgentRuntime()
    val compressor = FakeHandoffCompressor("LLM handoff summary")
    val controller = AgentRunController(
      runtime = runtime,
      handoffCompressor = compressor,
      scope = this,
      shouldCompressContext = { true },
    )
    val drafts = mutableListOf<SessionMessage>()
    var startedSession: AgentSession? = null
    var startedDraft: SessionMessage? = null
    var preparedSession: AgentSession? = null
    var preparedDraft: SessionMessage? = null
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
      onSessionUpdated = { updated, draft ->
        preparedSession = updated
        preparedDraft = draft
      },
      onFinished = { updated, _ -> finishedSession = updated },
    )

    assertNotNull(job)
    job!!.join()

    assertEquals("Compressing context...", startedDraft?.content)
    assertTrue(startedDraft?.runEvents?.any { it.title == "Context compression started" } == true)
    assertTrue(startedSession?.messages?.any { it.role == SESSION_ROLE_COMPRESSION } == false)
    assertEquals("Working...", preparedDraft?.content)
    assertTrue(preparedDraft?.runEvents?.any { it.title == "Context compressed" } == true)
    assertTrue(preparedDraft?.runEvents?.any { it.title == "Thinking" } == true)
    assertTrue(preparedSession?.messages?.any { it.role == SESSION_ROLE_COMPRESSION } == true)
    assertTrue(runtime.sessionSeen?.messages?.any { it.role == SESSION_ROLE_COMPRESSION } == true)
    assertTrue(compressor.called)
    assertTrue(runtime.sessionSeen?.messages?.any { it.content.contains("LLM handoff summary") } == true)
    assertEquals(HANDOFF_SOURCE_LLM, runtime.sessionSeen?.contextRecords?.lastOrNull()?.summarySource)
    assertEquals(true, runtime.sessionSeen?.contextRecords?.lastOrNull()?.compressed)
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
  fun runControllerStreamsFinalResponseDraftsAndPersistsOneAssistantMessage() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Streaming final ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "streaming-final-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val controller = AgentRunController(runtime = StreamingFinalAgentRuntime(), scope = this)
    val drafts = mutableListOf<SessionMessage>()
    var finishedSession: AgentSession? = null

    val job = controller.submit(
      input = "stream final answer",
      settings = AppSettings(),
      session = session,
      workspace = workspace,
      appendUserPrompt = sessions::appendUserPrompt,
      appendContextRecord = sessions::appendContextRecord,
      appendCompressionDivider = sessions::appendCompressionDivider,
      appendMessage = sessions::appendMessage,
      onStarted = { _, _ -> },
      onDraft = { drafts += it },
      onSessionUpdated = { _, _ -> },
      onFinished = { updated, _ -> finishedSession = updated },
    )

    assertNotNull(job)
    job!!.join()

    assertTrue(drafts.any { it.content == "assistant " })
    assertTrue(drafts.any { it.content == "assistant streamed " })
    assertTrue(drafts.any { it.content == "assistant streamed output" })
    assertTrue(drafts.last().runEvents.any { it.title == "Final response streaming" })
    assertTrue(drafts.last().transcriptEvents.any { it.type == "final_response_streaming" })
    assertEquals("assistant_text", drafts.last().transcriptEvents.last().type)
    assertEquals("assistant streamed output", drafts.last().transcriptEvents.last().content)
    assertEquals(2, finishedSession?.messages?.size)
    val assistantMessage = finishedSession?.messages?.lastOrNull()
    assertEquals("assistant", assistantMessage?.role)
    assertEquals("assistant streamed output", assistantMessage?.content)
    assertTrue(assistantMessage?.runEvents?.any { it.title == "Final response streamed" } == true)
    assertTrue(assistantMessage?.runEvents?.any { it.title == "Final response ready" } == true)
    assertEquals("assistant_text", assistantMessage?.transcriptEvents?.lastOrNull()?.type)
    assertEquals("assistant streamed output", assistantMessage?.transcriptEvents?.lastOrNull()?.content)
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
      appendCompressionDivider = { current, _, _ ->
        callbackCalled = true
        current
      },
      appendMessage = { current, _ ->
        callbackCalled = true
        current
      },
      onStarted = { _, _ -> callbackCalled = true },
      onDraft = { callbackCalled = true },
      onSessionUpdated = { _, _ -> callbackCalled = true },
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
    val runEvents = mutableListOf<AgentRunEvent>()

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
      onSessionUpdated = { _, _ -> },
      onFinished = { updated, success ->
        finishedSession = updated
        succeeded = success
      },
      onRunEvent = { event -> runEvents += event },
    )

    assertNotNull(job)
    job!!.join()

    assertEquals(false, succeeded)
    val errorMessage = finishedSession?.messages?.lastOrNull()
    assertEquals("error", errorMessage?.role)
    assertTrue(errorMessage?.content?.contains("Error category: provider") == true)
    assertTrue(errorMessage?.content?.contains("Error log saved: .flovera/logs/agent-error-") == true)
    assertTrue(errorMessage?.content?.contains("Checkpoint saved: .flovera/runs/") == true)
    assertTrue(errorMessage?.content?.contains("Run stopped after 1 completed tool call") == true)
    assertTrue(errorMessage?.runEvents?.any { it.title == "Tool: fake_tool_before_failure" } == true)
    assertTrue(errorMessage?.runEvents?.any { it.type == AgentRunEventType.RUN_FAILED } == true)
    assertTrue(errorMessage?.runEvents?.any { it.detail.contains("category=provider") } == true)
    assertTrue(errorMessage?.transcriptEvents?.any { it.type == AgentRunEventType.RUN_FAILED } == true)
    assertTrue(errorMessage?.transcriptEvents?.any {
      it.type == "error_text" && it.content.contains("Error category: provider")
    } == true)
    assertTrue(runEvents.any { it.type == AgentRunEventType.RUN_STARTED })
    assertTrue(runEvents.any { it.type == AgentRunEventType.RUN_FAILED && it.detail.contains("category=provider") })
    val logs = File(workspace.root, ".flovera/logs").listFiles().orEmpty()
    assertEquals(1, logs.size)
    val logText = logs.single().readText()
    assertTrue(logText.contains("DeepSeekLLMClient"))
    assertTrue(logText.contains("fake_tool_before_failure"))
    assertTrue(logText.contains("networkEnabled: true"))
    assertTrue(logText.contains("errorCategory: provider"))
    assertTrue(logText.contains("agentRunId: ${session.id}-"))
    assertFalse(logText.contains("secret-must-not-be-logged"))
    val checkpoint = workspace.readFile(".flovera/runs/latest.json")
    assertTrue(checkpoint, checkpoint.contains("\"status\": \"failed\""))
    assertTrue(checkpoint, checkpoint.contains("\"errorCategory\": \"provider\""))
    assertTrue(checkpoint, checkpoint.contains("\"fake_tool_before_failure\""))
    assertTrue(checkpoint, checkpoint.contains("\"resumePrompt\""))
  }

  @Test
  fun runControllerClassifiesNetworkFailures() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Network failure ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "network-failure-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val controller = AgentRunController(runtime = NetworkFailingAgentRuntime(), scope = this)
    var finishedSession: AgentSession? = null

    val job = controller.submit(
      input = "trigger network failure",
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
      onFinished = { updated, _ -> finishedSession = updated },
    )

    assertNotNull(job)
    job!!.join()

    val errorMessage = finishedSession?.messages?.lastOrNull()
    assertEquals("error", errorMessage?.role)
    assertTrue(errorMessage?.content?.contains("Error category: network") == true)
    assertTrue(errorMessage?.runEvents?.any { it.type == AgentRunEventType.RUN_FAILED && it.detail.contains("category=network") } == true)
    val checkpoint = workspace.readFile(".flovera/runs/latest.json")
    assertTrue(checkpoint, checkpoint.contains("\"errorCategory\": \"network\""))
  }

  @Test
  fun runControllerCancellationDoesNotPersistFailureMessage() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Cancel run ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "cancel-run-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val runtime = BlockingAgentRuntime()
    val controller = AgentRunController(runtime = runtime, scope = this)
    var finishedCalled = false

    val job = controller.submit(
      input = "long task",
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
      onFinished = { _, _ -> finishedCalled = true },
    )

    assertNotNull(job)
    runtime.started.await()
    job!!.cancel()
    job.join()

    assertFalse(finishedCalled)
    assertEquals(1, store.load(session.id)?.messages?.size)
    assertEquals("user", store.load(session.id)?.messages?.single()?.role)
  }

  private class BlockingAgentRuntime : AgentRuntime {
    val started = CompletableDeferred<Unit>()
    private val never = CompletableDeferred<Unit>()

    override suspend fun run(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
    ): String {
      started.complete(Unit)
      never.await()
      return "unreachable"
    }
  }

  private class FakeHandoffCompressor(private val summary: String) : SessionHandoffCompressor {
    var called: Boolean = false

    override suspend fun compress(
      settings: AppSettings,
      session: AgentSession,
      record: ContextUsageRecord,
      workspace: WorkspaceManager,
    ): SessionHandoffCompression {
      called = true
      return SessionHandoffCompression(
        summary = summary,
        source = HANDOFF_SOURCE_LLM,
      )
    }
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

  private class StreamingFinalAgentRuntime : AgentRuntime {
    override suspend fun run(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
    ): String {
      return "fallback output"
    }

    override suspend fun runStreaming(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
      eventSink: AgentRunEventSink,
    ): String {
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "assistant "))
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "streamed "))
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "output"))
      return "assistant streamed output"
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

  private class NetworkFailingAgentRuntime : AgentRuntime {
    override suspend fun run(
      input: String,
      agentRunId: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
    ): String {
      error("Software caused connection abort")
    }
  }

  @Test
  fun transcriptEventsOrdersTextBeforeToolAndCoalescesAdjacentDeltas() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Chrono text-tool ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "chrono-text-tool-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val controller = AgentRunController(runtime = InterleavedStreamingAgentRuntime(), scope = this)
    val drafts = mutableListOf<SessionMessage>()
    var finishedSession: AgentSession? = null

    val job = controller.submit(
      input = "interleaved test",
      settings = AppSettings(),
      session = session,
      workspace = workspace,
      appendUserPrompt = sessions::appendUserPrompt,
      appendContextRecord = sessions::appendContextRecord,
      appendCompressionDivider = sessions::appendCompressionDivider,
      appendMessage = sessions::appendMessage,
      onStarted = { _, _ -> },
      onDraft = { drafts += it },
      onSessionUpdated = { _, _ -> },
      onFinished = { updated, _ -> finishedSession = updated },
    )

    assertNotNull(job)
    job!!.join()

    // Verify the final persisted transcript has correct order:
    // text "I will " -> tool write_file -> text "created file"
    val transcript = finishedSession?.messages?.lastOrNull()?.transcriptEvents.orEmpty()
    assertTrue("transcript should have assistant_text before tool",
      transcript.any { it.type == "assistant_text" && it.content.contains("I will") })
    val toolIdx = transcript.indexOfFirst { it.type == "tool_call" }
    assertTrue("transcript should have a tool_call event", toolIdx >= 0)
    val textBeforeIdx = transcript.indexOfFirst { it.type == "assistant_text" && it.content.contains("I will") }
    val textAfterIdx = transcript.indexOfFirst { it.type == "assistant_text" && it.content.contains("created") }
    assertTrue("first text should be before tool", textBeforeIdx < toolIdx)
    assertTrue("second text should be after tool", textAfterIdx > toolIdx)
    assertEquals("Tool: write_file", transcript[toolIdx].title)

    // Verify adjacent text deltas before the tool are coalesced
    assertEquals(2, transcript.count { it.type == "assistant_text" })
    assertTrue(transcript.any { it.content.contains("I will create it.") })

    // Legacy backward compatibility
    assertTrue(finishedSession?.messages?.lastOrNull()?.toolEvents?.any { it.name == "write_file" } == true)
    assertTrue(finishedSession?.messages?.lastOrNull()?.runEvents?.any { it.title == "Tool: write_file" } == true)
  }

  @Test
  fun transcriptEventsPreservesTwoToolTwoTextInterleaving() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Chrono double ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "chrono-double-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val controller = AgentRunController(runtime = DoubleToolInterleavedAgentRuntime(), scope = this)
    var finishedSession: AgentSession? = null

    val job = controller.submit(
      input = "double tool test",
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
      onFinished = { updated, _ -> finishedSession = updated },
    )

    assertNotNull(job)
    job!!.join()

    val transcript = finishedSession?.messages?.lastOrNull()?.transcriptEvents.orEmpty()
    // Expected order: thinking, text "pre ", tool read_file, text "mid ", tool write_file, text "post"

    val types = transcript.map { it.type }
    val textContents = transcript.filter { it.type == "assistant_text" }.map { it.content }
    val toolTitles = transcript.filter { it.type == "tool_call" }.map { it.title }

    assertTrue("should have at least 3 assistant_text entries", textContents.size >= 3)
    assertTrue("should have 2 tool_call entries", toolTitles.size == 2)

    // Verify interleaving: text before first tool, text between tools, text after last tool
    val idxPre = transcript.indexOfFirst { it.type == "assistant_text" && it.content.contains("pre") }
    val idxRead = transcript.indexOfFirst { it.type == "tool_call" && it.title.contains("read_file") }
    val idxMid = transcript.indexOfFirst { it.type == "assistant_text" && it.content.contains("mid") }
    val idxWrite = transcript.indexOfFirst { it.type == "tool_call" && it.title.contains("write_file") }
    val idxPost = transcript.indexOfFirst { it.type == "assistant_text" && it.content.contains("post") }

    assertTrue("pre text before read_file tool", idxPre < idxRead)
    assertTrue("read_file tool before mid text", idxRead < idxMid)
    assertTrue("mid text before write_file tool", idxMid < idxWrite)
    assertTrue("write_file tool before post text", idxWrite < idxPost)

    // Legacy backward compatibility
    assertEquals(2, finishedSession?.messages?.lastOrNull()?.toolEvents?.size)
  }

  @Test
  fun transcriptEventsKeepsNonStreamingOrderWithToolThenText() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Non-streaming ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "non-streaming-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val controller = AgentRunController(runtime = FakeAgentRuntime(), scope = this)
    var finishedSession: AgentSession? = null

    val job = controller.submit(
      input = "non streaming test",
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
      onFinished = { updated, _ -> finishedSession = updated },
    )

    assertNotNull(job)
    job!!.join()

    val transcript = finishedSession?.messages?.lastOrNull()?.transcriptEvents.orEmpty()
    val types = transcript.map { it.type }

    // Tool should appear before the final assistant_text
    val toolIdx = transcript.indexOfFirst { it.type == "tool_call" }
    val textIdx = transcript.indexOfLast { it.type == "assistant_text" }
    assertTrue("tool_call should be before assistant_text in non-streaming", toolIdx < textIdx)
    assertEquals("assistant output", transcript[textIdx].content)
  }

  @Test
  fun transcriptEventsDraftReflectsChronologicalOrderDuringStreaming() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Draft chrono ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "draft-chrono-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val controller = AgentRunController(runtime = InterleavedStreamingAgentRuntime(), scope = this)
    val drafts = mutableListOf<SessionMessage>()

    val job = controller.submit(
      input = "draft order test",
      settings = AppSettings(),
      session = session,
      workspace = workspace,
      appendUserPrompt = sessions::appendUserPrompt,
      appendContextRecord = sessions::appendContextRecord,
      appendCompressionDivider = sessions::appendCompressionDivider,
      appendMessage = sessions::appendMessage,
      onStarted = { _, _ -> },
      onDraft = { drafts += it },
      onSessionUpdated = { _, _ -> },
      onFinished = { _, _ -> },
    )

    assertNotNull(job)
    job!!.join()

    // At least one draft should show tool event in transcript after text started
    val draftWithTool = drafts.firstOrNull { draft ->
      draft.transcriptEvents.any { it.type == "tool_call" } &&
      draft.transcriptEvents.any { it.type == "assistant_text" }
    }
    assertNotNull("should have a draft with both tool and assistant_text", draftWithTool)

    val draftTranscript = draftWithTool!!.transcriptEvents
    val draftTextIdx = draftTranscript.indexOfFirst { it.type == "assistant_text" }
    val draftToolIdx = draftTranscript.indexOfFirst { it.type == "tool_call" }
    assertTrue("in draft, text should appear before tool", draftTextIdx < draftToolIdx)
  }

  @Test
  fun transcriptEventsKeepsFailureStatusAndErrorTextAfterStreamingStarted() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = AgentSessionStore(context)
    val sessions = SessionController(store)
    val session = store.create("Streaming failure ${System.currentTimeMillis()}")
    val workspace = WorkspaceManager(context, "streaming-failure-${System.currentTimeMillis()}").also { it.ensureSeedFiles() }
    val controller = AgentRunController(runtime = StreamingThenFailingAgentRuntime(), scope = this)
    var finishedSession: AgentSession? = null

    val job = controller.submit(
      input = "stream then fail",
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
      onFinished = { updated, _ -> finishedSession = updated },
    )

    assertNotNull(job)
    job!!.join()

    val transcript = finishedSession?.messages?.lastOrNull()?.transcriptEvents.orEmpty()
    val textIdx = transcript.indexOfFirst { it.type == "assistant_text" && it.content.contains("partial streamed text") }
    val failedIdx = transcript.indexOfFirst { it.type == AgentRunEventType.RUN_FAILED }
    val errorIdx = transcript.indexOfFirst { it.type == "error_text" && it.content.contains("stream failed after text") }

    assertTrue("streamed assistant text should be kept", textIdx >= 0)
    assertTrue("run_failed should be kept after streaming text", failedIdx > textIdx)
    assertTrue("error_text should be kept after run_failed", errorIdx > failedIdx)
  }

  private class InterleavedStreamingAgentRuntime : AgentRuntime {
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
    ): String {
      // Simulate: text "I will " -> tool completes -> text "created file"
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "I will "))
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "create it."))
      recorder.record("write_file", """{"path":"test.txt","content":"OK"}""", "wrote test.txt")
      // The recorder triggers TOOL_EVENTS_CHANGED through the controller
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "created "))
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "file"))
      return "created file"
    }
  }

  private class DoubleToolInterleavedAgentRuntime : AgentRuntime {
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
    ): String {
      // Simulate: text "pre " -> tool1 read_file -> text "mid " -> tool2 write_file -> text "post"
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "pre "))
      recorder.record("read_file", """{"path":"source.txt"}""", "content from source")
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "mid "))
      recorder.record("write_file", """{"path":"out.txt","content":"result"}""", "wrote out.txt")
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "post"))
      return "post"
    }
  }

  private class StreamingThenFailingAgentRuntime : AgentRuntime {
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
    ): String {
      eventSink.emit(AgentRunEvent(type = AgentRunEventType.MODEL_TEXT_DELTA, modelTextDelta = "partial streamed text"))
      error("stream failed after text")
    }
  }
}
