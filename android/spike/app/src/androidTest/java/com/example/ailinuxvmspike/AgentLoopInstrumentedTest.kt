package com.example.ailinuxvmspike

import androidx.test.platform.app.InstrumentationRegistry
import com.example.ailinuxvmspike.config.AppSettings
import com.example.ailinuxvmspike.koog.KoogAgentRuntime
import com.example.ailinuxvmspike.koog.ToolEventRecorder
import com.example.ailinuxvmspike.session.AgentSessionStore
import com.example.ailinuxvmspike.session.SessionMessage
import com.example.ailinuxvmspike.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AgentLoopInstrumentedTest {
  @Test
  fun deepSeekAgentCreatesWorkspaceFileAndPersistsSession() = runBlocking {
    val arguments = InstrumentationRegistry.getArguments()
    val apiKey = arguments.getString("deepseekApiKey").orEmpty()
    assumeTrue("Pass -e deepseekApiKey to run the live DeepSeek loop test.", apiKey.isNotBlank())

    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "instrumented-${System.currentTimeMillis()}"
    val workspace = WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
    val sessionStore = AgentSessionStore(context)
    val session = sessionStore.create("Instrumented agent loop")
    val settings = AppSettings(
      apiKey = apiKey,
      activeWorkspaceId = workspaceId,
      activeSessionId = session.id,
      maxAgentIterations = 8,
    )
    workspace.writeFile(
      path = "AGENT.md",
      content = "# Agent Rules\n\n- For this test, create files exactly as requested and keep responses concise.",
    )
    val recorder = ToolEventRecorder()

    val withUser = sessionStore.appendMessage(
      session,
      SessionMessage(role = "user", content = "Create hello.txt with exact content OK using workspace tools."),
    )
    val output = KoogAgentRuntime().run(
      input = "Create hello.txt with exact content OK using workspace tools.",
      settings = settings,
      session = withUser,
      workspace = workspace,
      recorder = recorder,
    )
    val persisted = sessionStore.appendMessage(
      withUser,
      SessionMessage(role = "assistant", content = output, toolEvents = recorder.snapshot()),
    )

    val helloFile = File(workspace.root, "hello.txt")
    assertTrue("hello.txt should be created in the Android workspace", helloFile.exists())
    assertEquals("OK", helloFile.readText().trim())
    assertTrue("Koog should call write_file", recorder.snapshot().any { it.name == "write_file" })

    val loaded = sessionStore.load(persisted.id)
    assertNotNull("Session should be loadable after persistence", loaded)
    assertEquals(2, loaded?.messages?.size)
    assertTrue("Assistant message should include tool events", loaded?.messages?.last()?.toolEvents?.isNotEmpty() == true)
  }
}
