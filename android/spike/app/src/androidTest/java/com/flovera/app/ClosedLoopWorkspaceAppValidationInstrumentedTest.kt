package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.SettingsStore
import com.flovera.app.koog.KoogAgentRuntime
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.session.AgentSessionStore
import com.flovera.app.session.SessionMessage
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ClosedLoopWorkspaceAppValidationInstrumentedTest {
  @Test
  fun printExistingSettingsSummary() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val settings = SettingsStore(context).load()
    println(
      "FLOVERA_SETTINGS_SUMMARY " +
        "provider=${settings.provider} " +
        "model=${settings.model} " +
        "apiKeyConfigured=${settings.apiKeyFor(settings.provider).isNotBlank()} " +
        "workspace=${settings.activeWorkspaceId} " +
        "network=${settings.networkEnabled} " +
        "webSearch=${settings.webSearchEnabled} " +
        "authority=${settings.agentAuthorityMode} " +
        "deepSeekThinking=${settings.deepSeekThinkingEffort} " +
        "maxIterations=${settings.maxAgentIterations}",
    )
  }

  @Test
  fun deepSeekBuildsInteractiveWorkspaceAppFromExistingSettings() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val settingsStore = SettingsStore(context)
    val originalSettings = settingsStore.load()
    val providerApiKey = originalSettings.apiKeyFor(originalSettings.provider)
    assumeTrue("Existing Flovera settings must contain the selected provider API key.", providerApiKey.isNotBlank())
    println(
      "FLOVERA_CLOSED_LOOP_START provider=${originalSettings.provider} " +
        "model=${originalSettings.model} workspace=${originalSettings.activeWorkspaceId}",
    )

    val workspace = WorkspaceManager(context, originalSettings.activeWorkspaceId).also { it.ensureSeedFiles() }
    val sessionStore = AgentSessionStore(context)
    val session = sessionStore.create("Closed-loop workspace app validation ${System.currentTimeMillis()}")
    val runtimeSettings = originalSettings.copy(activeSessionId = session.id)
    val recorder = ToolEventRecorder()
    val prompt = """
      You are running inside Flovera as the workspace AI agent.

      Build a complete local web AI chat app in the folder `closed-loop-ai-chat/`.
      Do not edit `.flovera/`, `AGENT.md`, app settings, API keys, provider settings, search settings, or files outside `closed-loop-ai-chat/`.

      Create these files:
      - `closed-loop-ai-chat/index.html`
      - `closed-loop-ai-chat/style.css`
      - `closed-loop-ai-chat/app.js`
      - `closed-loop-ai-chat/README.md`

      Product requirements:
      - The first screen should feel like a usable web chat AI app, not a form demo.
      - Include a conversation area, message composer, send button, clear-history action, status/error display, model input with default `deepseek-chat`, and API key input for users who want direct API calls.
      - Store only browser-side preferences and conversation history in `localStorage`; never write API keys into workspace files.
      - Use standard browser APIs. Prefer Flovera's same-origin endpoint `/__flovera__/api/deepseek/stream` when available. If it is not available, fall back to direct DeepSeek HTTPS API only when the user enters a key.
      - Keep the UI responsive on a phone WebView.

      Verification requirements:
      - Read back the files you created.
      - If something cannot be fully verified, state exactly what remains unverified.
      - Finish with a concise summary and the open path `closed-loop-ai-chat/index.html`.
    """.trimIndent()

    val withUser = sessionStore.appendMessage(session, SessionMessage(role = "user", content = prompt))
    println("FLOVERA_CLOSED_LOOP_RUNTIME_BEGIN session=${session.id}")
    val output = withTimeout(10 * 60 * 1000L) {
      KoogAgentRuntime().run(
        input = prompt,
        agentRunId = "${session.id}-closed-loop-app",
        settings = runtimeSettings,
        session = withUser,
        workspace = workspace,
        recorder = recorder,
      )
    }
    println("FLOVERA_CLOSED_LOOP_RUNTIME_END session=${session.id}")
    sessionStore.appendMessage(withUser, SessionMessage(role = "assistant", content = output, toolEvents = recorder.snapshot()))

    val afterSettings = settingsStore.load()
    assertEquals(originalSettings.provider, afterSettings.provider)
    assertEquals(originalSettings.model, afterSettings.model)
    assertEquals(originalSettings.apiKeyFor(originalSettings.provider), afterSettings.apiKeyFor(afterSettings.provider))
    assertEquals(originalSettings.braveSearchApiKey, afterSettings.braveSearchApiKey)
    assertEquals(originalSettings.agentAuthorityMode, afterSettings.agentAuthorityMode)

    val appRoot = File(workspace.root, "closed-loop-ai-chat")
    val index = File(appRoot, "index.html")
    val style = File(appRoot, "style.css")
    val script = File(appRoot, "app.js")
    val readme = File(appRoot, "README.md")
    listOf(index, style, script, readme).forEach { file ->
      assertTrue("${file.name} should exist in closed-loop-ai-chat", file.isFile)
      assertTrue("${file.name} should not be empty", file.length() > 80)
    }
    val indexText = index.readText()
    val scriptText = script.readText()
    assertTrue("index.html should load app.js", indexText.contains("app.js"))
    assertTrue("index.html should load style.css", indexText.contains("style.css"))
    assertTrue("app.js should implement browser-side history", scriptText.contains("localStorage"))
    assertTrue("app.js should include the Flovera local DeepSeek endpoint", scriptText.contains("/__flovera__/api/deepseek/stream"))
    assertTrue("Runtime should use workspace tools", recorder.snapshot().any { it.name in setOf("write_file", "edit_file", "python_run") })

    println("FLOVERA_CLOSED_LOOP_SESSION=${session.id}")
    println("FLOVERA_CLOSED_LOOP_WORKSPACE=${originalSettings.activeWorkspaceId}")
    println("FLOVERA_CLOSED_LOOP_OPEN_PATH=closed-loop-ai-chat/index.html")
    println("FLOVERA_CLOSED_LOOP_TOOL_CALLS=${recorder.snapshot().joinToString(",") { it.name }}")
    println("FLOVERA_CLOSED_LOOP_OUTPUT=${output.take(1200).replace('\n', ' ')}")
  }
}
