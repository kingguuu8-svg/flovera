package com.flovera.app

import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.view.View
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
import kotlinx.coroutines.runBlocking
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
      val workspace = com.flovera.app.workspace.WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
      workspace.writeFile("static-demo/index.html", "<!doctype html><title>Static Runtime</title><main>Static Runtime</main>", createAutoSnapshot = false)
      workspace.writeFile(
        "static-demo/flovera.app.json",
        """
        {
          "schemaVersion": 1,
          "name": "static-runtime",
          "entrypoints": {
            "preview": { "kind": "local_http", "path": "index.html" }
          }
        }
        """.trimIndent(),
        createAutoSnapshot = false,
      )
      val controller = AgentController(context, settingsStore = settingsStore)
      runBlocking { controller.refreshWorkspaceFiles().join() }
      controller.selectHtmlFile("static-demo/index.html")

      val selectedUrl = waitForSelectedHtmlUrl(controller)
      assertTrue(selectedUrl.startsWith("http://127.0.0.1:"))
      assertTrue(URL(selectedUrl).readText().contains("Static Runtime"))

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
      val workspace = com.flovera.app.workspace.WorkspaceManager(context, workspaceId).also { it.ensureSeedFiles() }
      workspace.writeFile("static-demo/index.html", "<!doctype html><title>Static Runtime</title><main>Static Runtime</main>", createAutoSnapshot = false)
      workspace.writeFile(
        "static-demo/flovera.app.json",
        """
        {
          "schemaVersion": 1,
          "name": "static-runtime",
          "entrypoints": {
            "preview": { "kind": "local_http", "path": "index.html" }
          }
        }
        """.trimIndent(),
        createAutoSnapshot = false,
      )
      val controller = AgentController(context, settingsStore = settingsStore)
      runBlocking { controller.refreshWorkspaceFiles().join() }
      controller.selectHtmlFile("static-demo/index.html")
      val selectedUrl = waitForSelectedHtmlUrl(controller)
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

  @Test
  fun webViewHardeningPublishesViewportAndDetectsVisibleContent() {
    val result = evaluateWebViewHardening(
      """
      <!doctype html>
      <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
        <body><main style="display:block;width:240px;height:180px">Visible app</main></body>
      </html>
      """.trimIndent(),
    )

    assertTrue("Viewport helper did not publish CSS height: $result", result.contains("px"))
    assertTrue("Visible content was not detected: $result", WorkspaceWebViewHardening.isVisibleResult(result))
  }

  @Test
  fun webViewHardeningRejectsHiddenContent() {
    val result = evaluateWebViewHardening(
      """
      <!doctype html>
      <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
        <body><main style="display:none;width:240px;height:180px">Hidden app</main></body>
      </html>
      """.trimIndent(),
    )

    assertFalse("Hidden content should not be accepted as visible: $result", WorkspaceWebViewHardening.isVisibleResult(result))
    assertTrue("Hidden content result should include visibility diagnostics: $result", result.contains("visible"))
    assertTrue(
      WorkspaceWebViewHardening.visibilityFailureMessage(result).contains("No visible main content"),
    )
  }

  @Test
  fun webViewCanFetchWorkspaceOwnedPythonHttpSseWithUserApiKey() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val workspaceId = "runtime-python-http-webview-${System.currentTimeMillis()}"
    val settingsFile = File(context.filesDir, "$workspaceId-settings.json")
    val webViewRef = AtomicReference<WebView?>()
    try {
      val settingsStore = SettingsStore(context, settingsFile).also {
        it.save(AppSettings(activeWorkspaceId = workspaceId, provider = "", model = ""))
      }
      val workspace = com.flovera.app.workspace.WorkspaceManager(context, workspaceId)
      workspace.writeFile("own-api/src/web/index.html", "<!doctype html><title>Own API</title><main>Own API Chat</main>", createAutoSnapshot = false)
      workspace.writeFile(
        "own-api/src/server.py",
        """
        import argparse
        import json
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


        class Handler(BaseHTTPRequestHandler):
            def log_message(self, _format, *args):
                return

            def send_body(self, status, content_type, body):
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                if self.path == "/":
                    self.send_body(200, "text/html; charset=utf-8", b"Own API Chat")
                else:
                    self.send_body(404, "text/plain; charset=utf-8", b"not found")

            def do_POST(self):
                if self.path != "/api/chat/stream":
                    self.send_body(404, "text/plain; charset=utf-8", b"not found")
                    return
                length = int(self.headers.get("Content-Length") or "0")
                payload = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
                api_key = payload.get("apiKey", "")
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(("data: " + json.dumps({"content": "key=" + api_key[-4:]}) + "\n\n").encode("utf-8"))
                self.wfile.write(b"data: [DONE]\n\n")


        parser = argparse.ArgumentParser()
        parser.add_argument("--host", default="127.0.0.1")
        parser.add_argument("--port", type=int, required=True)
        args = parser.parse_args()
        ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
        """.trimIndent(),
        createAutoSnapshot = false,
      )
      workspace.writeFile(
        "own-api/flovera.app.json",
        """
        {
          "schemaVersion": 1,
          "name": "own-api",
          "kind": "interactive",
          "entrypoints": {
            "preview": {
              "kind": "local_http",
              "path": "src/web/index.html",
              "urlPath": "/"
            },
            "server": {
              "kind": "python_http",
              "command": "python src/server.py --host 127.0.0.1 --port 8765"
            }
          }
        }
        """.trimIndent(),
        createAutoSnapshot = false,
      )
      val controller = AgentController(context, settingsStore = settingsStore)
      runBlocking { controller.refreshWorkspaceFiles().join() }
      controller.selectHtmlFile("own-api/src/web/index.html")
      val selectedUrl = waitForSelectedHtmlUrl(controller)
      assertTrue(selectedUrl.startsWith("http://127.0.0.1:"))
      assertFalse(selectedUrl.contains("/__flovera__/"))

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
                fetch('/api/chat/stream', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ apiKey: 'user-owned-apikey' })
                })
                  .then(response => response.text())
                  .then(text => FloveraProbe.done(text.indexOf('key=ikey') >= 0 ? 'OK' : text))
                  .catch(error => FloveraProbe.done('ERROR:' + error.message))
              """.trimIndent(),
              null,
            )
          }
        }
        webView.loadUrl(selectedUrl)
      }

      assertTrue("WebView workspace python_http fetch did not finish; result=${result.get()}", latch.await(30, TimeUnit.SECONDS))
      assertEquals("OK", result.get())
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

  private fun evaluateWebViewHardening(markup: String): String {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val webViewRef = AtomicReference<WebView?>()
    val latch = CountDownLatch(1)
    val result = AtomicReference("")
    instrumentation.runOnMainSync {
      val webView = WebView(context)
      webViewRef.set(webView)
      webView.settings.javaScriptEnabled = true
      webView.measure(
        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
      )
      webView.layout(0, 0, 1080, 1920)
      webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
          val script = """
            ${WorkspaceWebViewHardening.viewportHelperJs}
            (function () {
              var cssHeight = window.getComputedStyle(document.documentElement).getPropertyValue('--flovera-viewport-height');
              var viewport = window.FloveraViewport ? (window.FloveraViewport.width + 'x' + window.FloveraViewport.height) : 'missing';
              var visible = ${WorkspaceWebViewHardening.visibleContentCheckJs}
              return cssHeight + '|' + viewport + '|' + visible;
            })();
          """.trimIndent()
          view.evaluateJavascript(script) { value ->
            result.set(value.orEmpty())
            latch.countDown()
          }
        }
      }
      webView.loadDataWithBaseURL("http://127.0.0.1/", markup, "text/html", "UTF-8", null)
    }
    try {
      assertTrue("WebView hardening evaluation did not finish; result=${result.get()}", latch.await(30, TimeUnit.SECONDS))
      return result.get()
    } finally {
      instrumentation.runOnMainSync {
        webViewRef.getAndSet(null)?.destroy()
      }
    }
  }

  private fun waitForSelectedHtmlUrl(controller: AgentController, timeoutMillis: Long = 20_000): String {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadline) {
      val url = controller.state.value.selectedHtmlUrl.orEmpty()
      if (url.isNotBlank()) return url
      val error = controller.state.value.selectedHtmlError
      if (error.isNotBlank()) error("Selected HTML failed: $error")
      SystemClock.sleep(50)
    }
    error("Timed out waiting for selectedHtmlUrl; state=${controller.state.value.status}")
  }
}
