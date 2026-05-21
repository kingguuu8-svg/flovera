package com.flovera.app

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import com.flovera.app.config.AppSettings
import com.flovera.app.config.SettingsStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAppRuntimeBaseInstrumentedTest {
  @Test
  fun localHttpRuntimeServesStaticApiCorsAndDeniesInternalFiles() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspaceId = "runtime-base-${System.currentTimeMillis()}"
    val settingsFile = File(context.filesDir, "$workspaceId-settings.json")
    try {
      val settingsStore = SettingsStore(context, settingsFile).also {
        it.save(AppSettings(activeWorkspaceId = workspaceId, provider = "deepseek", model = "deepseek-chat"))
      }
      val controller = AgentController(context, settingsStore = settingsStore).also {
        it.refreshWorkspaceFiles()
        it.selectHtmlFile("agent-demo/src/web/index.html")
      }

      val selectedUrl = controller.state.value.selectedHtmlUrl.orEmpty()
      assertTrue(selectedUrl.startsWith("http://127.0.0.1:"))
      assertTrue(URL(selectedUrl).readText().contains("Flovera Workspace Chat"))

      val origin = selectedUrl.substringBefore("/__flovera__/workspace/")
      val health = JSONObject(URL("$origin/__flovera__/api/health").readText())
      assertEquals("flovera-local-http", health.getString("runtime"))
      assertFalse(health.getBoolean("hasApiKey"))

      val preflight = (URL("$origin/__flovera__/api/health").openConnection() as HttpURLConnection).apply {
        requestMethod = "OPTIONS"
      }
      assertEquals(204, preflight.responseCode)
      assertEquals("*", preflight.getHeaderField("Access-Control-Allow-Origin"))

      val internal = (URL("$origin/__flovera__/workspace/.flovera/capabilities.json").openConnection() as HttpURLConnection)
      assertEquals(403, internal.responseCode)
    } finally {
      settingsFile.delete()
      File(File(context.filesDir, "workspaces"), workspaceId).deleteRecursively()
    }
  }

  @Test
  fun webViewCanFetchLocalHttpRuntimeWithoutBridge() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val workspaceId = "runtime-webview-${System.currentTimeMillis()}"
    val settingsFile = File(context.filesDir, "$workspaceId-settings.json")
    val webViewRef = AtomicReference<WebView?>()
    try {
      val settingsStore = SettingsStore(context, settingsFile).also {
        it.save(AppSettings(activeWorkspaceId = workspaceId, provider = "deepseek", model = "deepseek-chat"))
      }
      val controller = AgentController(context, settingsStore = settingsStore).also {
        it.refreshWorkspaceFiles()
        it.selectHtmlFile("agent-demo/src/web/index.html")
      }
      val selectedUrl = controller.state.value.selectedHtmlUrl.orEmpty()
      assertTrue(selectedUrl.startsWith("http://127.0.0.1:"))

      val latch = CountDownLatch(1)
      val result = AtomicReference("")
      instrumentation.runOnMainSync {
        val webView = WebView(context)
        webViewRef.set(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(WebViewFetchProbe(result, latch), "FloveraProbe")
        webView.webViewClient = object : WebViewClient() {
          override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(
              """
                fetch('/__flovera__/api/health', { cache: 'no-store' })
                  .then(response => response.json())
                  .then(value => FloveraProbe.done(value.runtime + ':' + value.hasApiKey))
                  .catch(error => FloveraProbe.done('ERROR:' + error.message))
              """.trimIndent(),
              null,
            )
          }
        }
        webView.loadUrl(selectedUrl)
      }

      assertTrue("WebView fetch did not finish; result=${result.get()}", latch.await(30, TimeUnit.SECONDS))
      assertTrue("Unexpected WebView fetch result: ${result.get()}", result.get().contains("flovera-local-http:false"))
    } finally {
      instrumentation.runOnMainSync {
        webViewRef.getAndSet(null)?.destroy()
      }
      settingsFile.delete()
      File(File(context.filesDir, "workspaces"), workspaceId).deleteRecursively()
    }
  }

  private class WebViewFetchProbe(
    private val result: AtomicReference<String>,
    private val latch: CountDownLatch,
  ) {
    @JavascriptInterface
    fun done(value: String) {
      result.set(value)
      latch.countDown()
    }
  }
}
