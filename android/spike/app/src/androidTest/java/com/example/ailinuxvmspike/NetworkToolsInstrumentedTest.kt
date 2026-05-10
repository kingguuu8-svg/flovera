package com.example.ailinuxvmspike

import androidx.test.platform.app.InstrumentationRegistry
import com.example.ailinuxvmspike.koog.DownloadFileTool
import com.example.ailinuxvmspike.koog.FetchUrlTool
import com.example.ailinuxvmspike.koog.NetworkHttpClient
import com.example.ailinuxvmspike.koog.NetworkResponse
import com.example.ailinuxvmspike.koog.ToolEventRecorder
import com.example.ailinuxvmspike.workspace.WorkspaceManager
import java.io.File
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    assertEquals("https://93.184.216.34/example.txt", client.requestedUrl)
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

  private class FakeNetworkHttpClient(
    private val response: NetworkResponse,
  ) : NetworkHttpClient {
    var requestedUrl: String? = null

    override suspend fun get(url: URL): NetworkResponse {
      requestedUrl = url.toString()
      return response
    }
  }
}
