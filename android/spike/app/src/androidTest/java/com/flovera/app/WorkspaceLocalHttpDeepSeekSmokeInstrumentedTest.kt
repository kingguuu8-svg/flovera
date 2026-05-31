package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.config.SettingsStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class WorkspaceLocalHttpDeepSeekSmokeInstrumentedTest {
  @Test
  fun localHttpDeepSeekSseStreamsLiveCompletion() {
    val arguments = InstrumentationRegistry.getArguments()
    val apiKey = arguments.getString("deepseekApiKey").orEmpty()
    assumeTrue("Pass -e deepseekApiKey to run the live local HTTP DeepSeek SSE test.", apiKey.isNotBlank())

    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "live-local-http-${System.currentTimeMillis()}"
    val settingsFile = File(context.filesDir, "$workspaceId-settings.json")
    val workspaceRoot = File(File(context.filesDir, "workspaces"), workspaceId)
    try {
      val settingsStore = SettingsStore(context, settingsFile).also {
        it.save(
          AppSettings(
            activeWorkspaceId = workspaceId,
            provider = "deepseek",
            model = arguments.getString("deepseekModel").orEmpty().ifBlank { "deepseek-chat" },
            apiKey = apiKey,
          ),
        )
      }
      val controller = AgentController(context, settingsStore = settingsStore).also {
        it.refreshWorkspaceFiles()
        it.selectHtmlFile("agent-demo/src/web/index.html")
      }

      val selectedUrl = controller.state.value.selectedHtmlUrl.orEmpty()
      assertTrue(selectedUrl.startsWith("http://127.0.0.1:"))
      val origin = selectedUrl.substringBefore("/__flovera__/workspace/")
      val health = JSONObject(URL("$origin/__flovera__/api/health").readText())
      assertTrue(health.getBoolean("hasApiKey"))

      val connection = (URL("$origin/__flovera__/api/deepseek/stream").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 180_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
      }
      val request = JSONObject()
        .put("model", health.getString("model"))
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with exactly FLOVERA_SSE_OK.")))
        .put("stream", true)
        .put("temperature", 0)
        .put("max_tokens", 32)
      connection.outputStream.use { stream ->
        stream.write(request.toString().toByteArray(StandardCharsets.UTF_8))
      }

      val sse = connection.inputStream.bufferedReader().use { it.readText() }
      assertTrue(sse.contains("[DONE]"))
      assertFalse(sse.contains("DeepSeek API key is not configured"))
      assertFalse("Live SSE returned an error event: ${sse.take(1_000)}", sse.contains("event: error"))

      val text = collectSseText(sse)
      assertTrue("Expected DeepSeek SSE content, got: $text; raw: ${sse.take(1_000)}", text.contains("FLOVERA_SSE_OK"))
    } finally {
      settingsFile.delete()
      workspaceRoot.deleteRecursively()
    }
  }

  private fun collectSseText(sse: String): String {
    val output = StringBuilder()
    for (line in sse.lineSequence()) {
      if (!line.startsWith("data:")) continue
      val data = line.removePrefix("data:").trim()
      if (data.isBlank() || data == "[DONE]") continue
      val parsed = runCatching { JSONObject(data) }.getOrElse { continue }
      if (parsed.has("error")) fail(parsed.optJSONObject("error")?.optString("message") ?: parsed.toString())
      val choices = parsed.optJSONArray("choices") ?: continue
      val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
      output.append(delta.optString("content"))
      output.append(delta.optString("reasoning_content"))
    }
    return output.toString()
  }
}
