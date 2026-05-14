package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.agent.AgentRunController
import com.flovera.app.config.AppSettings
import com.flovera.app.koog.AgentRuntime
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSession
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.SessionController
import com.flovera.app.session.SessionMessage
import com.flovera.app.workspace.WorkspaceManager
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
    assertTrue(startedSession?.contextRecords?.single()?.approximateTokens ?: 0 > 0)
    assertEquals("user", startedSession?.messages?.single()?.role)
    assertEquals("create file", startedSession?.messages?.single()?.content)
    assertEquals("fake_tool", drafts.single().toolEvents.single().name)
    assertEquals(true, succeeded)
    assertEquals(2, finishedSession?.messages?.size)
    assertEquals("assistant output", finishedSession?.messages?.last()?.content)
    assertTrue(finishedSession?.messages?.last()?.toolEvents?.any { it.name == "fake_tool" } == true)
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

  private class FakeAgentRuntime : AgentRuntime {
    var inputSeen: String = ""

    override suspend fun run(
      input: String,
      settings: AppSettings,
      session: AgentSession,
      workspace: WorkspaceManager,
      recorder: ToolEventRecorder,
    ): String {
      inputSeen = input
      recorder.record("fake_tool", "{}", "ok")
      return "assistant output"
    }
  }
}
