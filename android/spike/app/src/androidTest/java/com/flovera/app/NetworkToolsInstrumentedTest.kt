package com.flovera.app

import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.koog.DownloadFileTool
import com.flovera.app.koog.FetchUrlTool
import com.flovera.app.koog.NetworkHttpClient
import com.flovera.app.koog.NetworkResponse
import com.flovera.app.koog.ToolEventRecorder
import com.flovera.app.koog.WebSearchTool
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NetworkToolsInstrumentedTest {
  @Test
  fun fetchUrlReturnsBoundedTextAndRecordsToolEvent() = runBlocking {
    val recorder = ToolEventRecorder()
    val client = FakeNetworkHttpClient(
      NetworkResponse(
        statusCode = 200,
        contentType = "text/plain",
        finalUrl = "https://93.184.216.34/example.txt",
        body = "hello from web".encodeToByteArray(),
        truncated = false,
      ),
    )

    val result = FetchUrlTool(recorder, client).execute(
      FetchUrlTool.Args("https://93.184.216.34/example.txt"),
    )

    assertTrue(result.contains("status: 200"))
    assertTrue(result.contains("hello from web"))
    assertTrue(result.contains("truncated: false"))
    assertEquals("https://93.184.216.34/example.txt", client.requestedUrl)
    assertEquals(64 * 1024, client.maxBytesSeen)
    assertTrue(recorder.snapshot().any { it.name == "fetch_url" })
  }

  @Test
  fun downloadFileWritesInsideWorkspace() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "network-${System.currentTimeMillis()}")
    val recorder = ToolEventRecorder()
    val client = FakeNetworkHttpClient(
      NetworkResponse(
        statusCode = 200,
        contentType = "text/html",
        finalUrl = "https://93.184.216.34/page.html",
        body = "<!doctype html><title>Downloaded</title>".encodeToByteArray(),
        truncated = false,
      ),
    )

    val result = DownloadFileTool(workspace, recorder, client).execute(
      DownloadFileTool.Args(
        url = "https://93.184.216.34/page.html",
        path = "downloads/page.html",
      ),
    )

    assertTrue(result.contains("Downloaded"))
    assertEquals("<!doctype html><title>Downloaded</title>", File(workspace.root, "downloads/page.html").readText())
    assertEquals(null, client.maxBytesSeen)
    assertTrue(recorder.snapshot().any { it.name == "download_file" })
  }

  @Test
  fun networkToolsAllowLocalTargetsButRejectEscapingPaths() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = WorkspaceManager(context, "network-reject-${System.currentTimeMillis()}")
    val recorder = ToolEventRecorder()
    val client = FakeNetworkHttpClient(
      NetworkResponse(
        statusCode = 200,
        contentType = "text/plain",
        finalUrl = "https://93.184.216.34/file.txt",
        body = "outside".encodeToByteArray(),
        truncated = false,
      ),
    )

    val localResult = FetchUrlTool(recorder, client).execute(
      FetchUrlTool.Args("http://127.0.0.1:8080/private"),
    )
    assertTrue(localResult.contains("status: 200"))

    val escapeName = "escape-${System.currentTimeMillis()}.txt"
    val pathResult = DownloadFileTool(workspace, recorder, client).execute(
      DownloadFileTool.Args(
        url = "https://93.184.216.34/file.txt",
        path = "../$escapeName",
      ),
    )
    assertTrue(pathResult.contains("Path escapes workspace"))
    assertFalse(File(workspace.root.parentFile, escapeName).exists())
  }

  @Test
  fun webSearchUsesBraveApiHeadersAndFormatsResults() = runBlocking {
    val recorder = ToolEventRecorder()
    val client = FakeNetworkHttpClient(
      NetworkResponse(
        statusCode = 200,
        contentType = "application/json",
        finalUrl = "https://api.search.brave.com/res/v1/web/search?q=flovera",
        body = """
          {
            "web": {
              "results": [
                {
                  "title": "Flovera",
                  "url": "https://example.com/flovera",
                  "description": "A workspace agent result"
                }
              ]
            }
          }
        """.trimIndent().encodeToByteArray(),
        truncated = false,
      ),
    )

    val result = WebSearchTool("brave-key", recorder, client).execute(
      WebSearchTool.Args(query = "flovera workspace", count = 3),
    )

    assertEquals("brave-key", client.headersSeen["X-Subscription-Token"])
    assertTrue(client.requestedUrl?.contains("api.search.brave.com") == true)
    assertTrue(result.contains("Flovera"))
    assertTrue(result.contains("https://example.com/flovera"))
    assertTrue(recorder.snapshot().any { it.name == "web_search" })
  }

  @Test
  fun webSearchExtractsBraveKeyFromPastedTextBeforeSendingHeader() = runBlocking {
    val recorder = ToolEventRecorder()
    val client = FakeNetworkHttpClient(
      NetworkResponse(
        statusCode = 200,
        contentType = "application/json",
        finalUrl = "https://api.search.brave.com/res/v1/web/search?q=flovera",
        body = """{"web":{"results":[{"title":"Flovera","url":"https://example.com","description":"ok"}]}}"""
          .encodeToByteArray(),
        truncated = false,
      ),
    )
    val pasted = """
      BSAmkdXRBkbVDqD6mHralmPbYtSY5JH
      unrelated pasted notification text
      brave key
      BSAmkdXRBkbVDqD6mHralmPbYtSY5JH
    """.trimIndent()

    val result = WebSearchTool(pasted, recorder, client).execute(
      WebSearchTool.Args(query = "flovera", count = 1),
    )

    assertEquals("BSAmkdXRBkbVDqD6mHralmPbYtSY5JH", client.headersSeen["X-Subscription-Token"])
    assertTrue(result.contains("status: 200"))
  }

  @Test
  fun liveBraveWebSearchReturnsResultsWhenApiKeyProvided() = runBlocking {
    val apiKey = InstrumentationRegistry.getArguments().getString("braveSearchApiKey").orEmpty()
    assumeTrue("Pass -e braveSearchApiKey to run the live Brave Search test.", apiKey.isNotBlank())

    val recorder = ToolEventRecorder()
    val result = WebSearchTool(apiKey, recorder).execute(
      WebSearchTool.Args(query = "flovera android agent", count = 3),
    )

    val reachedBrave = result.contains("status: 200") ||
      result.contains("USAGE_LIMIT_EXCEEDED") ||
      result.contains("status: 429")
    assertTrue(result, reachedBrave)
    if (result.contains("status: 200")) {
      assertTrue(result, result.contains("results:"))
    }
    assertFalse(result, result.contains("Unexpected char"))
    assertFalse(result, result.contains("header value", ignoreCase = true))
    assertTrue(recorder.snapshot().any { it.name == "web_search" })
  }

  private class FakeNetworkHttpClient(
    private val response: NetworkResponse,
  ) : NetworkHttpClient {
    var requestedUrl: String? = null
    var maxBytesSeen: Int? = null
    var headersSeen: Map<String, String> = emptyMap()

    override suspend fun get(url: URL, maxBytes: Int?, headers: Map<String, String>): NetworkResponse {
      requestedUrl = url.toString()
      maxBytesSeen = maxBytes
      headersSeen = headers
      return response
    }
  }
}
