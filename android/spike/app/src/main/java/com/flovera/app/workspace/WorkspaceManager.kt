package com.flovera.app.workspace

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.flovera.app.config.SettingsProposalChanges
import com.flovera.app.storage.readUtf8Text
import com.flovera.app.storage.writeBytesAtomically
import com.flovera.app.storage.writeStreamAtomically
import com.flovera.app.storage.writeUtf8TextAtomically
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class WorkspaceFileNode(
  val name: String,
  val path: String,
  val isDirectory: Boolean,
  val sizeBytes: Long,
  val children: List<WorkspaceFileNode> = emptyList(),
)

data class WorkspaceSearchHit(
  val path: String,
  val lineNumber: Int,
  val score: Int,
  val snippet: String,
  val context: List<WorkspaceSearchContextLine> = emptyList(),
)

data class WorkspaceSearchContextLine(
  val lineNumber: Int,
  val text: String,
  val isMatch: Boolean,
)

data class WorkspaceSearchOptions(
  val query: String,
  val path: String = ".",
  val topK: Int = 10,
  val scope: String = "workspace_public",
  val contextLines: Int = 0,
  val caseSensitive: Boolean = false,
  val mode: String = "literal",
  val includeGlob: String = "",
  val excludeGlob: String = "",
  val output: String = "matches",
  val respectIgnoreFiles: Boolean = true,
  val maxFiles: Int = 2000,
  val maxSnippetChars: Int = 200,
  val debug: Boolean = false,
)

@Serializable
data class FloveraWorkspaceArtifactManifest(
  val schema: String = "",
  val schemaVersion: Int = 1,
  val name: String = "",
  val kind: String = "app",
  val entrypoints: Map<String, FloveraArtifactEntrypoint> = emptyMap(),
  val actions: List<FloveraArtifactAction> = emptyList(),
  val outputs: List<String> = emptyList(),
)

@Serializable
data class FloveraArtifactEntrypoint(
  val kind: String = "",
  val path: String = "",
  val command: String = "",
  val cwd: String = ".",
  val urlPath: String = "",
  val label: String = "",
  val fallback: String = "",
)

@Serializable
data class FloveraArtifactAction(
  val id: String = "",
  val label: String = "",
  val kind: String = "",
  val command: String = "",
  val cwd: String = ".",
  val inputPath: String = "",
  val timeoutMs: Int = 30_000,
  val networkEnabled: Boolean = false,
  val environment: Map<String, String> = emptyMap(),
  val outputs: List<String> = emptyList(),
)

data class WorkspaceArtifact(
  val manifestPath: String,
  val rootPath: String,
  val name: String,
  val kind: String,
  val preview: WorkspaceArtifactEntrypoint?,
  val actions: List<WorkspaceArtifactAction>,
  val outputs: List<String>,
  val diagnostics: List<WorkspaceArtifactDiagnostic>,
  val valid: Boolean,
)

data class WorkspaceArtifactEntrypoint(
  val kind: String,
  val path: String,
  val label: String,
  val command: String = "",
  val cwd: String = ".",
  val urlPath: String = "",
)

data class WorkspaceArtifactAction(
  val id: String,
  val label: String,
  val kind: String,
  val command: String,
  val cwd: String,
  val inputPath: String,
  val timeoutMs: Int,
  val networkEnabled: Boolean,
  val environment: Map<String, String>,
  val outputs: List<String>,
)

data class WorkspaceArtifactActionTarget(
  val artifact: WorkspaceArtifact,
  val action: WorkspaceArtifactAction,
)

data class WorkspaceArtifactDiagnostic(
  val level: String,
  val path: String,
  val message: String,
)

@Serializable
data class WorkspaceArtifactJob(
  val id: String,
  val artifactManifestPath: String,
  val artifactRootPath: String,
  val actionId: String,
  val actionKind: String,
  val status: String,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val inputPath: String = "",
  val stdout: String = "",
  val stderr: String = "",
  val stdoutTruncated: Boolean = false,
  val stderrTruncated: Boolean = false,
  val exitCode: Int? = null,
  val elapsedMs: Int? = null,
  val outputPaths: List<String> = emptyList(),
  val error: String = "",
)

private data class WorkspaceIgnoreRule(
  val regex: Regex,
  val descendantRegex: Regex?,
  val negated: Boolean,
)

private val LEGACY_SEED_AGENT_RULES = """
  # Agent Rules

  - Keep all file paths relative to this workspace.
  - Prefer plain HTML, CSS, JavaScript, Markdown, and JSON files.
  - Do not assume npm, git, bash, or Linux tools exist on Android.
  - Do not use emoji unless the user explicitly asks for them.
  - For interactive HTML apps, prefer flovera.app.json preview kind `local_http`.
  - When an app needs its own backend, declare a `python_http` server command and use standard fetch/SSE routes owned by that backend.
  - Flovera WebView injects `--flovera-viewport-height`, `--flovera-viewport-width`, `--flovera-safe-bottom`, and `window.FloveraViewport`; keep first-screen content visible and avoid hidden or offscreen root layouts.
  - Use Flovera app-owned routes only when intentionally relying on built-in provider settings:
    - GET /__flovera__/api/health
    - POST /__flovera__/api/deepseek/stream
  - Legacy Workspace HTML can call controlled Android app events through window.Flovera:
    - window.Flovera.toast("message")
    - window.Flovera.notify(JSON.stringify({ title: "Title", body: "Body" }))
    - window.Flovera.postEvent(JSON.stringify({ type: "notification", title: "Title", body: "Body" }))
    - window.Flovera.runAction("action-id", JSON.stringify({ input: "..." }))
    - window.Flovera.getJob("job-id")
    - window.Flovera.cancelJob("job-id")
  - Always check window.Flovera exists before calling legacy bridge methods.
""".trimIndent()

class WorkspaceManager(context: Context, workspaceId: String = "default") {
  private val appContext = context.applicationContext
  private val workspacesRoot = File(context.filesDir, "workspaces")
  val root: File = File(workspacesRoot, workspaceId).apply { mkdirs() }
  val applicationContext: Context
    get() = appContext
  private val snapshotStore = WorkspaceSnapshotStore(appContext, workspaceId, root)
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private val compactJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private var staleArtifactJobsChecked = false

  fun ensureSeedFiles() {
    writeFile(
      path = "README.md",
      content = """
        # Android Agent Workspace

        This workspace is owned by the Android app. The agent can read, write, and edit files here through approved tools.
      """.trimIndent(),
      overwrite = false,
      createAutoSnapshot = false,
    )
    writeFile(
      path = "AGENT.md",
      content = "",
      overwrite = false,
      createAutoSnapshot = false,
    )
    clearLegacySeedAgentRules()
    writeFile(
      path = "index.html",
      content = """
        <!doctype html>
        <html>
          <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Android Workspace</title>
            <style>
              body {
                margin: 0;
                min-height: 100vh;
                display: grid;
                place-items: center;
                font-family: system-ui, sans-serif;
                color: #17202a;
                background: #f6f8fb;
              }
              main {
                width: min(720px, calc(100vw - 48px));
              }
              h1 {
                margin: 0 0 12px;
                font-size: 28px;
              }
              p {
                margin: 0;
                line-height: 1.6;
              }
            </style>
          </head>
          <body>
            <main>
              <h1>Android Workspace</h1>
              <p>Select an HTML file from the app menu, or ask the agent to create one in this workspace.</p>
            </main>
          </body>
        </html>
      """.trimIndent(),
      overwrite = false,
      createAutoSnapshot = false,
    )
    ensureWorkspaceArtifactDemo()
    ensureFloveraMetadata()
  }

  private fun ensureWorkspaceArtifactDemo() {
    writeWorkspaceArtifactDemoFile(
      path = "agent-demo/README.md",
      content = """
        # Flovera Workspace Chat Demo

        This is a portable local web chat app. Inside Flovera, the manifest declares a workspace-owned `python_http` backend. Flovera assigns the local port, starts `src/server.py`, and opens the backend URL in WebView. The browser code uses standard fetch/SSE and does not call `window.Flovera` or use a project-specific bridge.

        ## Inside Flovera

        Open the `Flovera Workspace Chat Demo` artifact from the HTML picker. Flovera starts the declared Python HTTP server and opens `http://127.0.0.1:<port>/`. The workspace backend exposes:

        - `GET /api/health`
        - `POST /api/chat/stream`

        The API key may be typed into the workspace app UI, or supplied through `OPENAI_API_KEY` or `DEEPSEEK_API_KEY` when running outside Flovera. The backend is not tied to Flovera's built-in provider route.

        ## Outside Flovera

        ```text
        set OPENAI_API_KEY=your-key
        python src/server.py --host 127.0.0.1 --port 8765
        ```

        Then open `http://127.0.0.1:8765`.
      """.trimIndent(),
    )
    writeWorkspaceArtifactDemoFile(
      path = "agent-demo/requirements.txt",
      content = """
        # The portable outside-Flovera server only uses the Python standard library.
      """.trimIndent(),
    )
    writeWorkspaceArtifactDemoFile(
      path = "agent-demo/data/input.json",
      content = """
        {
          "messages": [
            {
              "role": "user",
              "content": "Write a concise note about what this workspace chat demo proves."
            }
          ]
        }
      """.trimIndent(),
    )
    writeWorkspaceArtifactDemoFile(
      path = "agent-demo/src/agent.py",
      content = """
        import runpy
        from pathlib import Path


        if __name__ == "__main__":
            runpy.run_path(str(Path(__file__).with_name("server.py")), run_name="__main__")
      """.trimIndent(),
    )
    writeWorkspaceArtifactDemoFile(
      path = "agent-demo/src/server.py",
      content = """
        import argparse
        import json
        import os
        import urllib.request
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        from pathlib import Path


        PROJECT_ROOT = Path(__file__).resolve().parents[1]
        WEB_ROOT = PROJECT_ROOT / "src" / "web"


        def sse_event(event, payload):
            return "event: " + event + "\n" + "data: " + json.dumps(payload, ensure_ascii=False) + "\n\n"


        def chat_stream(payload):
            api_key = (
                str(payload.get("apiKey") or "").strip()
                or os.environ.get("OPENAI_API_KEY", "").strip()
                or os.environ.get("DEEPSEEK_API_KEY", "").strip()
            )
            if not api_key:
                yield sse_event("error", {"message": "Provide an API key in the workspace app or set OPENAI_API_KEY."}).encode("utf-8")
                yield b"data: [DONE]\n\n"
                return
            messages = payload.get("messages") or []
            if not isinstance(messages, list) or not messages:
                yield sse_event("error", {"message": "Request must include a non-empty messages array."}).encode("utf-8")
                yield b"data: [DONE]\n\n"
                return
            base_url = str(
                payload.get("baseUrl")
                or os.environ.get("OPENAI_COMPATIBLE_BASE_URL")
                or os.environ.get("DEEPSEEK_BASE_URL")
                or "https://api.deepseek.com"
            ).rstrip("/")
            model = payload.get("model") or os.environ.get("DEEPSEEK_MODEL", "deepseek-chat")
            path = "/chat/completions" if base_url.endswith("/v1") else "/v1/chat/completions"
            body = json.dumps({
                "model": model,
                "messages": messages,
                "stream": True,
                "temperature": payload.get("temperature", 0.3),
            }).encode("utf-8")
            request = urllib.request.Request(
                base_url + path,
                data=body,
                headers={
                    "Authorization": "Bearer " + api_key,
                    "Content-Type": "application/json",
                    "Accept": "text/event-stream",
                },
                method="POST",
            )
            try:
                with urllib.request.urlopen(request, timeout=180) as response:
                    for chunk in response:
                        yield chunk
            except Exception as error:
                yield sse_event("error", {"message": str(error)}).encode("utf-8")
            yield b"data: [DONE]\n\n"


        class Handler(BaseHTTPRequestHandler):
            def log_message(self, _format, *args):
                return

            def send_bytes(self, status, content_type, body):
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_OPTIONS(self):
                self.send_response(204)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                self.send_header("Access-Control-Allow-Headers", "content-type")
                self.end_headers()

            def do_GET(self):
                if self.path == "/api/health":
                    body = json.dumps({
                        "ok": True,
                        "runtime": "portable-python-http",
                        "provider": "openai-compatible",
                        "baseUrl": os.environ.get("OPENAI_COMPATIBLE_BASE_URL", os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")),
                        "hasServerApiKey": bool(os.environ.get("OPENAI_API_KEY", "").strip() or os.environ.get("DEEPSEEK_API_KEY", "").strip()),
                        "acceptsRequestApiKey": True,
                    }).encode("utf-8")
                    self.send_bytes(200, "application/json; charset=utf-8", body)
                    return
                path = self.path.split("?", 1)[0]
                if path == "/":
                    path = "/index.html"
                target = (WEB_ROOT / path.lstrip("/")).resolve()
                if WEB_ROOT not in target.parents and target != WEB_ROOT:
                    self.send_bytes(403, "text/plain; charset=utf-8", b"Forbidden")
                    return
                if not target.is_file():
                    self.send_bytes(404, "text/plain; charset=utf-8", b"Not found")
                    return
                mime = "text/html; charset=utf-8" if target.suffix == ".html" else "text/plain; charset=utf-8"
                if target.suffix == ".css":
                    mime = "text/css; charset=utf-8"
                if target.suffix == ".js":
                    mime = "text/javascript; charset=utf-8"
                self.send_bytes(200, mime, target.read_bytes())

            def do_POST(self):
                if self.path.split("?", 1)[0] != "/api/chat/stream":
                    self.send_bytes(404, "text/plain; charset=utf-8", b"Not found")
                    return
                length = int(self.headers.get("Content-Length") or "0")
                payload = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                for chunk in chat_stream(payload):
                    self.wfile.write(chunk)
                    self.wfile.flush()


        def main():
            parser = argparse.ArgumentParser(description="Portable Flovera workspace chat server")
            parser.add_argument("--host", default="127.0.0.1")
            parser.add_argument("--port", type=int, default=8765)
            parser.add_argument("--self-test", action="store_true")
            args = parser.parse_args()
            if args.self_test:
                print("portable-python-http ok")
                print(str(WEB_ROOT / "index.html"))
                return
            server = ThreadingHTTPServer((args.host, args.port), Handler)
            print("Serving http://" + args.host + ":" + str(args.port))
            server.serve_forever()


        if __name__ == "__main__":
            main()
      """.trimIndent(),
    )
    writeWorkspaceArtifactDemoFile(
      path = "agent-demo/src/web/index.html",
      content = """
        <!doctype html>
        <html lang="en">
          <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Flovera Workspace Chat</title>
            <link rel="stylesheet" href="styles.css">
          </head>
          <body>
            <main class="shell">
              <header class="topbar">
                <div>
                  <p class="eyebrow">Workspace AI App</p>
                  <h1>Flovera Workspace Chat</h1>
                </div>
                <div class="settings">
                  <label class="field">
                    <span>Model</span>
                    <input id="model" value="deepseek-v4-pro" autocomplete="off">
                  </label>
                  <label class="field">
                    <span>Base URL</span>
                    <input id="baseUrl" value="https://api.deepseek.com" autocomplete="off">
                  </label>
                  <label class="field">
                    <span>API key</span>
                    <input id="apiKey" type="password" placeholder="sk-..." autocomplete="off">
                  </label>
                </div>
              </header>
              <section id="messages" class="messages" aria-live="polite"></section>
              <form id="composer" class="composer">
                <textarea id="prompt" placeholder="Ask the model from this workspace app"></textarea>
                <div class="composerBar">
                  <span id="status">Checking runtime...</span>
                  <div class="buttons">
                    <button id="clear" type="button">Clear</button>
                    <button id="send" type="submit">Send</button>
                  </div>
                </div>
              </form>
            </main>
            <script src="app.js"></script>
          </body>
        </html>
      """.trimIndent(),
    )
    writeWorkspaceArtifactDemoFile(
      path = "agent-demo/src/web/styles.css",
      content = """
        :root {
          color-scheme: light;
          --bg: #f4f7f7;
          --ink: #172126;
          --muted: #607077;
          --line: #cfd9dc;
          --accent: #25636f;
          --surface: #ffffff;
          --assistant: #eef4f5;
          --user: #dcefe8;
        }

        * {
          box-sizing: border-box;
        }

        body {
          margin: 0;
          min-height: 100vh;
          font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
          background: var(--bg);
          color: var(--ink);
        }

        .shell {
          display: grid;
          grid-template-rows: auto 1fr auto;
          width: min(980px, 100vw);
          min-height: 100vh;
          margin: 0 auto;
          padding: 18px;
          gap: 14px;
        }

        .topbar {
          display: flex;
          align-items: end;
          justify-content: space-between;
          gap: 14px;
          border-bottom: 1px solid var(--line);
          padding-bottom: 12px;
        }

        .eyebrow {
          margin: 0 0 6px;
          color: var(--accent);
          font-size: 13px;
          font-weight: 700;
          text-transform: uppercase;
        }

        h1, p {
          margin-top: 0;
        }

        h1 {
          margin-bottom: 8px;
          font-size: 30px;
        }

        .settings {
          display: grid;
          gap: 8px;
          width: min(320px, 45vw);
        }

        .field {
          display: grid;
          gap: 6px;
          color: var(--muted);
          font-size: 12px;
          font-weight: 700;
        }

        .messages {
          display: grid;
          gap: 10px;
          overflow: auto;
          align-content: start;
          padding: 2px 0;
        }

        .message {
          max-width: 86%;
          white-space: pre-wrap;
          word-break: break-word;
          border-radius: 8px;
          padding: 10px 12px;
          line-height: 1.45;
          border: 1px solid var(--line);
        }

        .message.user {
          justify-self: end;
          background: var(--user);
        }

        .message.assistant {
          justify-self: start;
          background: var(--assistant);
        }

        .message.error {
          justify-self: start;
          background: #fff1f0;
          border-color: #e3aaa5;
        }

        textarea, input {
          width: 100%;
          border: 1px solid var(--line);
          border-radius: 6px;
          padding: 10px;
          color: var(--ink);
          font: inherit;
        }

        textarea {
          min-height: 92px;
          max-height: 180px;
          resize: vertical;
        }

        input {
          min-height: 38px;
        }

        .composer {
          display: grid;
          gap: 10px;
          border-top: 1px solid var(--line);
          padding-top: 12px;
        }

        .composerBar {
          display: flex;
          justify-content: space-between;
          align-items: center;
          gap: 12px;
          color: var(--muted);
          font-size: 14px;
        }

        .buttons {
          display: flex;
          gap: 8px;
        }

        button {
          min-height: 38px;
          border: 1px solid var(--accent);
          border-radius: 6px;
          padding: 0 14px;
          background: var(--accent);
          color: white;
          font-weight: 700;
        }

        #clear {
          border-color: var(--line);
          background: var(--surface);
          color: var(--ink);
        }

        button:disabled {
          border-color: var(--line);
          background: #dce5e6;
          color: var(--muted);
        }

        @media (max-width: 640px) {
          .shell {
            padding: 14px;
          }

          .topbar {
            display: grid;
            align-items: stretch;
          }

          .settings {
            width: 100%;
          }

          h1 {
            font-size: 25px;
          }

          .composerBar {
            display: grid;
          }

          .buttons {
            justify-content: end;
          }
        }
      """.trimIndent(),
    )
    writeWorkspaceArtifactDemoFile(
      path = "agent-demo/src/web/app.js",
      content = """
        const form = document.querySelector("#composer");
        const sendButton = document.querySelector("#send");
        const clearButton = document.querySelector("#clear");
        const promptInput = document.querySelector("#prompt");
        const modelInput = document.querySelector("#model");
        const baseUrlInput = document.querySelector("#baseUrl");
        const apiKeyInput = document.querySelector("#apiKey");
        const statusNode = document.querySelector("#status");
        const messagesNode = document.querySelector("#messages");

        const storageKey = "flovera-workspace-chat-messages";
        const endpoints = detectEndpoints();
        let messages = loadMessages();
        let busy = false;

        function detectEndpoints() {
          if (location.protocol === "file:") {
            return {
              health: "http://127.0.0.1:8765/api/health",
              stream: "http://127.0.0.1:8765/api/chat/stream"
            };
          }
          return {
            health: "/api/health",
            stream: "/api/chat/stream"
          };
        }

        function loadMessages() {
          try {
            const parsed = JSON.parse(localStorage.getItem(storageKey) || "[]");
            return Array.isArray(parsed) ? parsed : [];
          } catch (error) {
            return [];
          }
        }

        function saveMessages() {
          localStorage.setItem(storageKey, JSON.stringify(messages.slice(-40)));
        }

        function setStatus(text) {
          statusNode.textContent = text;
        }

        function addMessage(role, content, className) {
          const message = { role: role, content: content };
          messages.push(message);
          saveMessages();
          renderMessages();
          return message;
        }

        function renderMessages() {
          messagesNode.innerHTML = "";
          const visible = messages.length ? messages : [
            { role: "assistant", content: "Ready for a workspace chat." }
          ];
          for (const message of visible) {
            const node = document.createElement("div");
            node.className = "message " + (message.role === "user" ? "user" : "assistant");
            if (message.role === "error") node.className = "message error";
            node.textContent = message.content;
            messagesNode.appendChild(node);
          }
          messagesNode.scrollTop = messagesNode.scrollHeight;
        }

        function updateLastAssistant(text) {
          const last = messages[messages.length - 1];
          if (last && last.role === "assistant") {
            last.content = text;
          } else {
            messages.push({ role: "assistant", content: text });
          }
          saveMessages();
          renderMessages();
        }

        async function checkHealth() {
          try {
            const response = await fetch(endpoints.health, { cache: "no-store" });
            const health = await response.json();
            modelInput.value = health.model || modelInput.value;
            baseUrlInput.value = health.baseUrl || baseUrlInput.value;
            setStatus(health.hasServerApiKey ? "Connected with server API key" : "Ready - enter API key");
          } catch (error) {
            setStatus("Local HTTP runtime unavailable");
          }
        }

        function parseSseEvents(buffer, onData, onError) {
          const events = buffer.split("\n\n");
          const rest = events.pop() || "";
          for (const eventText of events) {
            const lines = eventText.split("\n");
            let event = "message";
            let data = "";
            for (const line of lines) {
              if (line.indexOf("event:") === 0) event = line.slice(6).trim();
              if (line.indexOf("data:") === 0) data += line.slice(5).trim();
            }
            if (!data) continue;
            if (data === "[DONE]") {
              onData("[DONE]");
              continue;
            }
            try {
              const parsed = JSON.parse(data);
              if (event === "error") {
                onError(parsed.message || JSON.stringify(parsed));
              } else {
                onData(parsed);
              }
            } catch (error) {
              onData(data);
            }
          }
          return rest;
        }

        function deltaFromOpenAiCompatible(payload) {
          if (typeof payload === "string") return payload === "[DONE]" ? "" : payload;
          const choice = payload.choices && payload.choices[0];
          const delta = choice && choice.delta;
          if (!delta) return "";
          return delta.content || delta.reasoning_content || "";
        }

        async function sendMessage(text) {
          if (busy) return;
          busy = true;
          sendButton.disabled = true;
          addMessage("user", text);
          messages.push({ role: "assistant", content: "" });
          renderMessages();
          let assistantText = "";
          setStatus("Streaming...");
          try {
            const response = await fetch(endpoints.stream, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({
                apiKey: apiKeyInput.value.trim(),
                baseUrl: baseUrlInput.value.trim(),
                model: modelInput.value.trim(),
                messages: messages.filter(function (item) {
                  return item.role === "user" || item.role === "assistant";
                }).filter(function (item) {
                  return item.content.trim();
                }),
                temperature: 0.3
              })
            });
            if (!response.ok) throw new Error("HTTP " + response.status);
            if (!response.body || !response.body.getReader) {
              const textBody = await response.text();
              updateLastAssistant(textBody);
              return;
            }
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = "";
            while (true) {
              const chunk = await reader.read();
              if (chunk.done) break;
              buffer += decoder.decode(chunk.value, { stream: true });
              buffer = parseSseEvents(buffer, function (payload) {
                const delta = deltaFromOpenAiCompatible(payload);
                if (!delta) return;
                assistantText += delta;
                updateLastAssistant(assistantText);
              }, function (message) {
                throw new Error(message);
              });
            }
            setStatus("Done");
          } catch (error) {
            const message = error.message || String(error);
            messages[messages.length - 1] = { role: "error", content: message };
            saveMessages();
            renderMessages();
            setStatus("Error");
          } finally {
            busy = false;
            sendButton.disabled = false;
          }
        }

        form.addEventListener("submit", function (event) {
          event.preventDefault();
          const text = promptInput.value.trim();
          if (!text) return;
          promptInput.value = "";
          sendMessage(text);
        });

        clearButton.addEventListener("click", function () {
          messages = [];
          saveMessages();
          renderMessages();
          setStatus("Cleared");
        });

        renderMessages();
        checkHealth();
      """.trimIndent(),
    )
    writeWorkspaceArtifactDemoFile(
      path = "agent-demo/flovera.app.json",
      content = """
        {
          "schema": "https://flovera.dev/schemas/app.v1.json",
          "schemaVersion": 1,
          "name": "Flovera Workspace Chat Demo",
          "kind": "interactive",
          "entrypoints": {
            "preview": {
              "kind": "local_http",
              "path": "src/web/index.html",
              "urlPath": "/",
              "fallback": "python src/server.py --host 127.0.0.1 --port 8765"
            },
            "server": {
              "kind": "python_http",
              "command": "python src/server.py --host 127.0.0.1 --port 8765"
            }
          },
          "actions": [],
          "outputs": []
        }
      """.trimIndent(),
      )
  }

  private fun clearLegacySeedAgentRules() {
    val current = runCatching { readFile("AGENT.md") }.getOrDefault("")
    if (current.trim() == LEGACY_SEED_AGENT_RULES.trim()) {
      writeFile(
        path = "AGENT.md",
        content = "",
        overwrite = true,
        createAutoSnapshot = false,
      )
    }
  }

  private fun writeWorkspaceArtifactDemoFile(path: String, content: String) {
    val current = readFile(path)
    val shouldOverwrite = current.startsWith("File does not exist:") ||
      current.contains("Flovera Portable Agent Demo") ||
      current.contains("Flovera Agent Demo") ||
      current.contains("Flovera Code Agent Demo") ||
      current.contains("bounded Python jobs") ||
      current.contains("def summarize(text):") ||
      current.contains("No input was provided.") ||
      current.contains("The demo only uses the Python standard library.") ||
      current.contains("Inspect agent-demo and improve the demo README") ||
      current.contains("\"wordCount\"") ||
      current.contains("Set DEEPSEEK_API_KEY to call DeepSeek") ||
      current.contains("through an explicit environment grant") ||
      current.contains("DEEPSEEK_API_KEY is not set") ||
      current.contains("Missing DeepSeek API key") ||
      current.contains("\"hasApiKey\"") ||
      current.contains("health.hasApiKey") ||
      current.contains("/__flovera__/api/deepseek/stream") ||
      current.contains("<label class=\"model\">") ||
      current.contains("flovera-code-agent-messages") ||
      current.contains("\"id\": \"run-code-agent\"") ||
      current.contains("runAction(\"run-code-agent\"") ||
      current.contains("app-owned localhost HTTP runtime") ||
      current.contains(".workbench") ||
      current.contains("runAction(\"summarize\"") ||
      current.contains("\"id\": \"summarize\"")
    writeFile(
      path = path,
      content = content,
      overwrite = shouldOverwrite,
      createAutoSnapshot = false,
    )
  }

  fun ensureFloveraMetadata(
    settingsView: FloveraSettingsView = FloveraSettingsView(),
    providerProfileCatalog: List<FloveraProviderProfileView> = emptyList(),
    providerApiModes: List<String> = listOf("chat_completions", "anthropic_messages"),
  ) {
    writeFile(
      path = ".flovera/manifest.json",
      content = json.encodeToString(
        FloveraWorkspaceManifest(
          workspaceId = root.name,
          settingsViewPath = ".flovera/settings-view.json",
          capabilitiesPath = ".flovera/capabilities.json",
          proposalsPath = ".flovera/proposals",
        ),
      ),
      overwrite = false,
      createAutoSnapshot = false,
    )
    writeFile(
      path = ".flovera/settings-view.json",
      content = json.encodeToString(settingsView),
      overwrite = true,
      createAutoSnapshot = false,
    )
    writeFile(
      path = ".flovera/capabilities.json",
      content = json.encodeToString(FloveraCapabilities.fromSettings(settingsView, providerProfileCatalog, providerApiModes)),
      overwrite = true,
      createAutoSnapshot = false,
    )
    safeFile(".flovera/proposals").mkdirs()
    safeFile(".flovera/tools").mkdirs()
    safeFile(".flovera/jobs").mkdirs()
    safeFile(".flovera/python/site-packages").mkdirs()
    safeFile(".flovera/python/wheels").mkdirs()
    if (!staleArtifactJobsChecked) {
      markStaleWorkspaceArtifactJobsInterrupted()
      staleArtifactJobsChecked = true
    }
    writeFile(
      path = ".flovera/tools/manifest.json",
      content = json.encodeToString(FloveraPythonToolsManifest()),
      overwrite = false,
      createAutoSnapshot = false,
    )
    writeFile(
      path = ".flovera/python/wheel-catalog.json",
      content = json.encodeToString(FloveraPythonWheelCatalog.default()),
      overwrite = true,
      createAutoSnapshot = false,
    )
  }

  fun readAgentRules(): String = readFile("AGENT.md")

  fun listSnapshots(): List<WorkspaceSnapshotRecord> = snapshotStore.list()

  fun listSettingsProposals(): List<WorkspaceSettingsProposal> {
    val proposalsDir = safeFile(".flovera/proposals")
    return proposalsDir.listFiles()
      ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
      ?.mapNotNull { file -> parseSettingsProposal(file) }
      ?.sortedByDescending { it.createdAtMillis }
      ?: emptyList()
  }

  fun listControlledToolProposals(): List<WorkspaceControlledToolProposal> {
    val proposalsDir = safeFile(".flovera/proposals")
    return proposalsDir.listFiles()
      ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
      ?.mapNotNull { file ->
        runCatching {
          val element = json.parseToJsonElement(readUtf8Text(file)).jsonObject
          val normalizedType = element["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return@mapNotNull null
          if (normalizedType !in setOf("tool", "mcp")) return@mapNotNull null
          val decoded = json.decodeFromJsonElement<WorkspaceControlledToolProposalFile>(element)
          WorkspaceControlledToolProposal(
            path = relativeToRoot(file),
            type = normalizedType,
            title = decoded.title.ifBlank { file.nameWithoutExtension },
            reason = decoded.reason,
            name = decoded.name,
            description = decoded.description,
            command = decoded.command,
            endpoint = decoded.endpoint,
            requestedCapabilities = decoded.requestedCapabilities,
            permissions = decoded.permissions,
            createdAtMillis = file.lastModified(),
          )
        }.getOrNull()
      }
      ?.sortedByDescending { it.createdAtMillis }
      ?: emptyList()
  }

  fun deleteSettingsProposal(path: String): Boolean {
    val file = safeFile(path)
    if (!file.isFile || !relativeToRoot(file).startsWith(".flovera/proposals/")) return false
    parseSettingsProposal(file) ?: return false
    return file.delete()
  }

  private fun parseSettingsProposal(file: File): WorkspaceSettingsProposal? {
    return runCatching {
      val element = json.parseToJsonElement(readUtf8Text(file)).jsonObject
      val explicitType = element["type"]?.jsonPrimitive?.contentOrNull
      val decoded = json.decodeFromJsonElement<WorkspaceSettingsProposalFile>(element)
      val rawChanges = if ("changes" in element) {
        SettingsProposalChanges()
      } else {
        runCatching { json.decodeFromJsonElement<SettingsProposalChanges>(element) }.getOrDefault(SettingsProposalChanges())
      }
      val changes = if (decoded.changes == SettingsProposalChanges() && rawChanges != SettingsProposalChanges()) {
        rawChanges
      } else {
        decoded.changes
      }
      val normalizedType = explicitType ?: if (rawChanges != SettingsProposalChanges()) "settings" else decoded.type
      if (!normalizedType.equals("settings", ignoreCase = true)) return@runCatching null
      WorkspaceSettingsProposal(
        path = relativeToRoot(file),
        title = decoded.title.ifBlank { file.nameWithoutExtension },
        reason = decoded.reason,
        changes = changes,
        createdAtMillis = file.lastModified(),
      )
    }.getOrNull()
  }

  fun appendFullAuthorityAudit(
    action: String,
    targetPath: String,
    title: String,
    reason: String,
    changes: SettingsProposalChanges,
  ): String {
    val file = safeFile(".flovera/logs/full-authority.jsonl")
    val existing = if (file.exists()) readUtf8Text(file).trimEnd() else ""
    val record = WorkspaceFullAuthorityAuditRecord(
      id = UUID.randomUUID().toString(),
      timestampMillis = System.currentTimeMillis(),
      action = action,
      targetPath = targetPath,
      title = title,
      reason = reason,
      changes = changes,
    )
    val updated = buildString {
      if (existing.isNotBlank()) {
        appendLine(existing)
      }
      appendLine(compactJson.encodeToString(record))
    }
    writeUtf8TextAtomically(file, updated)
    return relativeToRoot(file)
  }

  fun appendWorkspaceCommandAudit(
    command: String,
    cwd: String,
    authorityMode: String,
    riskCategory: String,
    permissions: List<String>,
    allowed: Boolean,
    reason: String,
    status: String,
    exitCode: Int,
    elapsedMs: Int,
  ): String {
    val file = safeFile(".flovera/logs/workspace-command.jsonl")
    val existing = if (file.exists()) readUtf8Text(file).trimEnd() else ""
    val record = WorkspaceCommandAuditRecord(
      id = UUID.randomUUID().toString(),
      timestampMillis = System.currentTimeMillis(),
      command = command,
      cwd = cwd,
      authorityMode = authorityMode,
      riskCategory = riskCategory,
      permissions = permissions,
      allowed = allowed,
      reason = reason,
      status = status,
      exitCode = exitCode,
      elapsedMs = elapsedMs,
    )
    val updated = buildString {
      if (existing.isNotBlank()) {
        appendLine(existing)
      }
      appendLine(compactJson.encodeToString(record))
    }
    writeUtf8TextAtomically(file, updated)
    return relativeToRoot(file)
  }

  fun deleteControlledToolProposal(path: String): Boolean {
    val file = safeFile(path)
    if (!file.isFile || !relativeToRoot(file).startsWith(".flovera/proposals/")) return false
    val decoded = runCatching {
      json.decodeFromString<WorkspaceControlledToolProposalFile>(readUtf8Text(file))
    }.getOrNull() ?: return false
    if (decoded.type.lowercase() !in setOf("tool", "mcp")) return false
    return file.delete()
  }

  fun createManualSnapshot(name: String, selectedHtmlPath: String = ""): WorkspaceSnapshotRecord {
    return snapshotStore.createManual(name, selectedHtmlPath)
  }

  fun createAutomaticSnapshot(reason: String) {
    snapshotStore.createAutomatic(reason)
  }

  fun restoreSnapshot(id: String): WorkspaceSnapshotRecord? = snapshotStore.restore(id)

  fun deleteSnapshot(id: String): Boolean = snapshotStore.delete(id)

  fun listHtmlFiles(): List<String> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
      .filter { it.isFile && it.extension.equals("html", ignoreCase = true) }
      .map { relativeToRoot(it) }
      .sorted()
      .toList()
  }

  fun fileTree(): WorkspaceFileNode {
    return toNode(root)
  }

  fun displayUrl(path: String): String? {
    val file = safeFile(path)
    if (!file.exists() || !file.isFile || !file.extension.equals("html", ignoreCase = true)) return null
    return file.toURI().toASCIIString()
  }

  fun listWorkspaceArtifacts(): List<WorkspaceArtifact> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
      .onEnter { dir ->
        val relative = relativeToRoot(dir).replace('\\', '/')
        relative == "." || relative != ".flovera" && !relative.startsWith(".flovera/")
      }
      .filter { it.isFile && it.name == WORKSPACE_ARTIFACT_MANIFEST_NAME }
      .take(MAX_WORKSPACE_ARTIFACT_MANIFESTS)
      .map { artifactFromManifestFile(it) }
      .sortedBy { it.manifestPath }
      .toList()
  }

  fun diagnoseWorkspaceArtifact(
    manifestPath: String = "",
    previewPath: String = "",
    includeReference: Boolean = false,
  ): String {
    val normalizedManifest = manifestPath.trim().replace('\\', '/')
    val normalizedPreview = previewPath.trim().replace('\\', '/')
    val artifacts = listWorkspaceArtifacts()
    val matches = artifacts.filter { artifact ->
      (normalizedManifest.isBlank() || artifact.manifestPath == normalizedManifest) &&
        (normalizedPreview.isBlank() || artifact.preview?.path == normalizedPreview)
    }
    return buildString {
      appendLine("Workspace artifact registration diagnostics")
      appendLine("- discoveredManifests=${artifacts.size}")
      if (normalizedManifest.isNotBlank()) appendLine("- requestedManifest=$normalizedManifest")
      if (normalizedPreview.isNotBlank()) appendLine("- requestedPreview=$normalizedPreview")
      if (includeReference) {
        appendLine()
        appendLine(referenceWorkspaceArtifactDemo())
      }
      if (matches.isEmpty()) {
        appendLine("- status=missing")
        appendLine()
        appendLine("No discovered artifact matched the request.")
        if (artifacts.isNotEmpty()) {
          appendLine()
          appendLine("Discovered manifests:")
          artifacts.take(20).forEach { artifact ->
            appendLine("- ${artifact.manifestPath} valid=${artifact.valid} preview=${artifact.preview?.path ?: "(none)"}")
          }
        }
        return@buildString
      }
      matches.take(20).forEach { artifact ->
        appendLine()
        appendLine("Artifact: ${artifact.name}")
        appendLine("- status=${if (artifact.valid) "registered" else "invalid"}")
        appendLine("- manifestPath=${artifact.manifestPath}")
        appendLine("- rootPath=${artifact.rootPath}")
        appendLine("- kind=${artifact.kind}")
        appendLine("- preview=${artifact.preview?.path ?: "(none)"}")
        appendLine("- previewKind=${artifact.preview?.kind ?: "(none)"}")
        appendLine("- serverCommand=${artifact.preview?.command?.ifBlank { "(none)" } ?: "(none)"}")
        appendLine("- serverCwd=${artifact.preview?.cwd ?: "(none)"}")
        appendLine("- urlPath=${artifact.preview?.urlPath?.ifBlank { "/" } ?: "(none)"}")
        appendLine("- actions=${artifact.actions.joinToString(", ") { "${it.id}:${it.kind}" }.ifBlank { "(none)" }}")
        appendLine("- outputs=${artifact.outputs.joinToString(", ").ifBlank { "(none)" }}")
        if (artifact.diagnostics.isEmpty()) {
          appendLine("- diagnostics=(none)")
        } else {
          appendLine("Diagnostics:")
          artifact.diagnostics.forEach { diagnostic ->
            appendLine("- ${diagnostic.level} ${diagnostic.path}: ${diagnostic.message}")
          }
        }
      }
      if (matches.size > 20) appendLine("\n... ${matches.size - 20} additional matching artifact(s) omitted.")
    }.trimEnd()
  }

  fun referenceWorkspaceArtifactDemo(): String {
    return """
      Hidden reference app demo
      - visibility=app-owned reference only; not discovered by the workspace picker
      - purpose=compare generated artifacts against a known-good mobile WebView + python_http shape

      Expected files:
      - README.md
      - flovera.app.json
      - src/server.py
      - src/web/index.html
      - src/web/app.js
      - src/web/styles.css

      Reference flovera.app.json:
      {
        "schema": "https://flovera.local/schemas/workspace-artifact-v1.json",
        "schemaVersion": 1,
        "name": "Reference Mobile Chat Demo",
        "kind": "app",
        "entrypoints": {
          "preview": {
            "kind": "local_http",
            "path": "src/web/index.html",
            "label": "Open",
            "urlPath": "/",
            "fallback": "src/web/index.html"
          },
          "server": {
            "kind": "python_http",
            "command": "python src/server.py --host 127.0.0.1 --port ${'$'}{PORT}",
            "cwd": "."
          }
        },
        "actions": [],
        "outputs": []
      }

      Frontend/backend contract:
      - index.html loads app.js and styles.css with relative paths.
      - app.js calls fetch('/api/health') for readiness.
      - app.js consumes POST /api/chat/stream as text/event-stream.
      - server.py binds HOST/PORT from CLI args, serves /, /src/web/*, /api/health, and /api/chat/stream.
      - HTML is mobile-first: uses viewport CSS variables, readable tap targets, safe bottom padding, and no autofocus.
    """.trimIndent()
  }

  fun resolveWorkspaceArtifactAction(previewPath: String, actionId: String): WorkspaceArtifactActionTarget? {
    val artifacts = listWorkspaceArtifacts().filter { it.valid }
    val scopedMatches = artifacts
      .filter { artifact -> previewPath.isNotBlank() && artifact.preview?.path == previewPath }
      .mapNotNull { artifact -> artifact.actions.firstOrNull { it.id == actionId }?.let { WorkspaceArtifactActionTarget(artifact, it) } }
    if (scopedMatches.size == 1) return scopedMatches.single()
    val globalMatches = artifacts
      .mapNotNull { artifact -> artifact.actions.firstOrNull { it.id == actionId }?.let { WorkspaceArtifactActionTarget(artifact, it) } }
    return globalMatches.singleOrNull()
  }

  fun resolveWorkspaceArtifactActionByManifest(manifestPath: String, actionId: String): WorkspaceArtifactActionTarget? {
    return listWorkspaceArtifacts()
      .firstOrNull { it.valid && it.manifestPath == manifestPath }
      ?.let { artifact -> artifact.actions.firstOrNull { it.id == actionId }?.let { WorkspaceArtifactActionTarget(artifact, it) } }
  }

  fun createWorkspaceArtifactJob(target: WorkspaceArtifactActionTarget, inputPath: String = ""): WorkspaceArtifactJob {
    val now = System.currentTimeMillis()
    val job = WorkspaceArtifactJob(
      id = UUID.randomUUID().toString(),
      artifactManifestPath = target.artifact.manifestPath,
      artifactRootPath = target.artifact.rootPath,
      actionId = target.action.id,
      actionKind = target.action.kind,
      status = WORKSPACE_ARTIFACT_JOB_QUEUED,
      createdAtMillis = now,
      updatedAtMillis = now,
      inputPath = inputPath,
      outputPaths = target.action.outputs,
    )
    return writeWorkspaceArtifactJob(job)
  }

  fun readWorkspaceArtifactJob(jobId: String): WorkspaceArtifactJob? {
    val file = workspaceArtifactJobFile(jobId) ?: return null
    if (!file.isFile) return null
    return runCatching { compactJson.decodeFromString<WorkspaceArtifactJob>(readUtf8Text(file)) }.getOrNull()
  }

  fun listWorkspaceArtifactJobs(): List<WorkspaceArtifactJob> {
    val jobsDir = safeFile(".flovera/jobs")
    return jobsDir.listFiles()
      ?.asSequence()
      ?.filter { it.isFile && it.extension == "json" }
      ?.mapNotNull { file -> runCatching { compactJson.decodeFromString<WorkspaceArtifactJob>(readUtf8Text(file)) }.getOrNull() }
      ?.sortedByDescending { it.updatedAtMillis }
      ?.toList()
      .orEmpty()
  }

  fun workspaceArtifactJobJson(jobId: String): String {
    return readWorkspaceArtifactJob(jobId)
      ?.let { compactJson.encodeToString(it) }
      ?: """{"status":"missing","error":"Workspace artifact job not found"}"""
  }

  fun writeWorkspaceArtifactJob(job: WorkspaceArtifactJob): WorkspaceArtifactJob {
    val file = workspaceArtifactJobFile(job.id) ?: error("Invalid artifact job id: ${job.id}")
    file.parentFile?.mkdirs()
    writeUtf8TextAtomically(file, compactJson.encodeToString(job.copy(updatedAtMillis = System.currentTimeMillis())))
    return readWorkspaceArtifactJob(job.id) ?: job
  }

  fun writeWorkspaceArtifactInput(jobId: String, artifactRootPath: String, inputPath: String, inputJson: String): String {
    val artifactRoot = safeFile(artifactRootPath)
    val normalizedInput = inputPath.replace('\\', '/')
    val normalizedRoot = artifactRootPath.replace('\\', '/')
    val relativePath = if (normalizedRoot == "." || normalizedInput == normalizedRoot || normalizedInput.startsWith("$normalizedRoot/")) {
      relativeToRoot(safeFile(inputPath))
    } else {
      artifactRelativePathOrThrow(artifactRoot, inputPath)
    }
    writeFile(relativePath, inputJson.ifBlank { "{}" }, overwrite = true, createAutoSnapshot = false)
    return relativePath
  }

  fun rootUrl(): String = root.toURI().toASCIIString()

  fun exportableFile(path: String): File? {
    val file = safeFile(path)
    if (!file.exists() || !file.isFile) return null
    return file
  }

  fun workspaceRuntimeDirectory(path: String = "."): File {
    val file = safeFile(path.ifBlank { "." })
    if (!file.exists()) return file
    return if (file.isFile) file.parentFile ?: root else file
  }

  fun workspaceRelativePath(file: File): String = relativeToRoot(file)

  fun mimeType(path: String): String {
    val extension = safeFile(path).extension.lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: when (extension) {
      "html", "htm" -> "text/html"
      "css" -> "text/css"
      "js" -> "text/javascript"
      "json" -> "application/json"
      "md", "txt" -> "text/plain"
      else -> "application/octet-stream"
    }
  }

  fun listFiles(path: String = "."): String {
    val dir = safeFile(path)
    if (!dir.exists()) return "Path does not exist: $path"
    if (!dir.isDirectory) return "${relativeToRoot(dir)} (${dir.length()} bytes)"
    return dir.listFiles()
      ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
      ?.joinToString("\n") { file ->
        val suffix = if (file.isDirectory) "/" else " (${file.length()} bytes)"
        relativeToRoot(file) + suffix
      }
      ?: ""
  }

  fun searchFiles(
    query: String,
    topK: Int = 10,
    scope: String = WORKSPACE_SEARCH_SCOPE_PUBLIC,
    path: String = ".",
    contextLines: Int = 0,
    caseSensitive: Boolean = false,
    mode: String = WORKSPACE_SEARCH_MODE_LITERAL,
    includeGlob: String = "",
    excludeGlob: String = "",
    output: String = WORKSPACE_SEARCH_OUTPUT_MATCHES,
    respectIgnoreFiles: Boolean = true,
    maxFiles: Int = DEFAULT_WORKSPACE_SEARCH_MAX_FILES,
    maxSnippetChars: Int = DEFAULT_WORKSPACE_SEARCH_SNIPPET_CHARS,
    debug: Boolean = false,
  ): String {
    return searchFiles(
      WorkspaceSearchOptions(
        query = query,
        path = path,
        topK = topK,
        scope = scope,
        contextLines = contextLines,
        caseSensitive = caseSensitive,
        mode = mode,
        includeGlob = includeGlob,
        excludeGlob = excludeGlob,
        output = output,
        respectIgnoreFiles = respectIgnoreFiles,
        maxFiles = maxFiles,
        maxSnippetChars = maxSnippetChars,
        debug = debug,
      ),
    )
  }

  fun searchFiles(options: WorkspaceSearchOptions): String {
    val normalizedQuery = options.query.trim()
    if (normalizedQuery.isBlank()) return "Search query is blank."
    val requested = runCatching { safeFile(options.path.ifBlank { "." }) }.getOrElse {
      return it.message ?: it.toString()
    }
    if (!requested.exists()) return "Path does not exist: ${options.path}"
    val limit = options.topK.coerceIn(1, MAX_WORKSPACE_SEARCH_RESULTS)
    val maxFiles = options.maxFiles.coerceIn(1, MAX_WORKSPACE_SEARCH_FILES)
    val normalizedScope = normalizeWorkspaceSearchScope(options.scope)
    val searchMode = normalizeWorkspaceSearchMode(options.mode)
    val output = normalizeWorkspaceSearchOutput(options.output)
    val regex = if (searchMode == WORKSPACE_SEARCH_MODE_REGEX) {
      runCatching {
        Regex(normalizedQuery, if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
      }.getOrElse { return "Invalid regex: ${it.message}" }
    } else {
      null
    }
    val tokens = workspaceSearchTokens(normalizedQuery, options.caseSensitive)
    val includeRegex = workspaceGlobRegex(options.includeGlob)
    val excludeRegex = workspaceGlobRegex(options.excludeGlob)
    val context = options.contextLines.coerceIn(0, MAX_WORKSPACE_SEARCH_CONTEXT_LINES)
    val maxSnippetChars = options.maxSnippetChars.coerceIn(MIN_WORKSPACE_SEARCH_SNIPPET_CHARS, MAX_WORKSPACE_SEARCH_SNIPPET_CHARS)
    val ignoreRules = if (options.respectIgnoreFiles) loadWorkspaceIgnoreRules() else emptyList()
    val hits = mutableListOf<WorkspaceSearchHit>()
    if (!root.exists()) return "No matches for \"$normalizedQuery\"."

    val startedAtMillis = System.currentTimeMillis()
    var scannedFiles = 0
    var skippedFiles = 0
    var stoppedEarly = false
    val candidates = workspaceSearchCandidates(requested, normalizedScope, ignoreRules)
    for (file in candidates) {
      if (Thread.currentThread().isInterrupted) {
        stoppedEarly = true
        break
      }
      if (!isWorkspaceSearchCandidate(file, normalizedScope, includeRegex, excludeRegex, ignoreRules)) {
        skippedFiles += 1
        continue
      }
      if (scannedFiles >= maxFiles) {
        stoppedEarly = true
        break
      }
      scannedFiles += 1
      hits += runCatching {
        searchFile(
          file = file,
          query = normalizedQuery,
          tokens = tokens,
          caseSensitive = options.caseSensitive,
          mode = searchMode,
          regex = regex,
          contextLines = context,
          maxSnippetChars = maxSnippetChars,
        )
      }.getOrDefault(emptyList())
    }

    val topHits = hits
      .sortedWith(compareByDescending<WorkspaceSearchHit> { it.score }.thenBy { it.path }.thenBy { it.lineNumber })
      .take(limit)
    val elapsedMillis = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0)

    if (topHits.isEmpty()) {
      return "No matches for \"$normalizedQuery\"${workspaceSearchHeaderSuffix(options.debug, scannedFiles, skippedFiles, stoppedEarly, maxFiles, elapsedMillis)}."
    }
    if (output == WORKSPACE_SEARCH_OUTPUT_FILES) {
      return workspaceSearchFilesOutput(
        query = normalizedQuery,
        requested = requested,
        scope = normalizedScope,
        mode = searchMode,
        hits = hits,
        limit = limit,
        scannedFiles = scannedFiles,
        skippedFiles = skippedFiles,
        stoppedEarly = stoppedEarly,
        maxFiles = maxFiles,
        elapsedMillis = elapsedMillis,
        debug = options.debug,
      )
    }
    if (output == WORKSPACE_SEARCH_OUTPUT_COUNT) {
      return workspaceSearchCountOutput(
        query = normalizedQuery,
        requested = requested,
        scope = normalizedScope,
        mode = searchMode,
        hits = hits,
        limit = limit,
        scannedFiles = scannedFiles,
        skippedFiles = skippedFiles,
        stoppedEarly = stoppedEarly,
        maxFiles = maxFiles,
        elapsedMillis = elapsedMillis,
        debug = options.debug,
      )
    }
    return workspaceSearchMatchesOutput(
      query = normalizedQuery,
      requestedPath = relativeToRoot(requested),
      scope = normalizedScope,
      mode = searchMode,
      hits = topHits,
      totalMatches = hits.size,
      scannedFiles = scannedFiles,
      skippedFiles = skippedFiles,
      stoppedEarly = stoppedEarly,
      maxFiles = maxFiles,
      elapsedMillis = elapsedMillis,
      debug = options.debug,
    )
  }

  fun readFile(path: String): String {
    val file = safeFile(path)
    if (!file.exists()) return "File does not exist: $path"
    if (!file.isFile) return "Path is not a file: $path"
    return readUtf8Text(file)
  }

  fun readFilePreview(path: String, maxChars: Int): String {
    val file = safeFile(path)
    if (!file.exists()) return "File does not exist: $path"
    if (!file.isFile) return "Path is not a file: $path"
    file.reader(Charsets.UTF_8).use { reader ->
      val buffer = CharArray(maxChars + 1)
      val count = reader.read(buffer)
      if (count <= maxChars) return String(buffer, 0, count.coerceAtLeast(0))
      return String(buffer, 0, maxChars) +
        "\n\n[truncated: showing first $maxChars chars of ${relativeToRoot(file)}; file is ${file.length()} bytes]"
    }
  }

  fun writeFile(
    path: String,
    content: String,
    overwrite: Boolean = true,
    createAutoSnapshot: Boolean = true,
  ): String {
    val file = safeFile(path)
    if (file.exists() && !overwrite) return "File already exists: ${relativeToRoot(file)}"
    if (createAutoSnapshot) {
      snapshotStore.createAutomatic("write_file:${relativeToRoot(file)}")
    }
    writeUtf8TextAtomically(file, content)
    return "Wrote ${content.length} chars to ${relativeToRoot(file)}"
  }

  fun writeBytes(
    path: String,
    content: ByteArray,
    overwrite: Boolean = true,
    createAutoSnapshot: Boolean = true,
  ): String {
    val file = safeFile(path)
    if (file.exists() && !overwrite) return "File already exists: ${relativeToRoot(file)}"
    if (createAutoSnapshot) {
      snapshotStore.createAutomatic("write_bytes:${relativeToRoot(file)}")
    }
    writeBytesAtomically(file, content)
    return "Wrote ${content.size} bytes to ${relativeToRoot(file)}"
  }

  fun importUriToRoot(uri: Uri): String {
    val name = uniqueRootFileName(sanitizeRootFileName(displayName(uri) ?: uri.lastPathSegment.orEmpty()))
    val target = safeFile(name)
    val input = appContext.contentResolver.openInputStream(uri) ?: return "Could not open shared file: $uri"
    snapshotStore.createAutomatic("import:${relativeToRoot(target)}")
    writeStreamAtomically(target, input)
    return "Imported ${relativeToRoot(target)}"
  }

  fun editFile(path: String, oldText: String, newText: String): String {
    val file = safeFile(path)
    if (!file.exists() || !file.isFile) return "File does not exist: $path"
    val current = readUtf8Text(file)
    if (!current.contains(oldText)) return "Old text was not found in $path"
    val updated = current.replace(oldText, newText, ignoreCase = false)
    snapshotStore.createAutomatic("edit_file:${relativeToRoot(file)}")
    writeUtf8TextAtomically(file, updated)
    return "Edited ${relativeToRoot(file)}"
  }

  fun rename(path: String, newName: String): String {
    val file = safeFile(path)
    if (!file.exists()) return "Path does not exist: $path"
    val normalized = newName.trim()
    if (normalized.isBlank() || normalized.contains("/") || normalized.contains("\\") || normalized == "." || normalized == "..") {
      return "Invalid file name: $newName"
    }
    val target = File(file.parentFile, normalized).canonicalFile
    val canonicalRoot = root.canonicalFile
    if (target.path != canonicalRoot.path && !target.path.startsWith(canonicalRoot.path + File.separator)) {
      return "Path escapes workspace: $newName"
    }
    if (target.exists()) return "Target already exists: ${relativeToRoot(target)}"
    snapshotStore.createAutomatic("rename:${relativeToRoot(file)}")
    return if (file.renameTo(target)) {
      "Renamed ${relativeToRoot(file)} to ${relativeToRoot(target)}"
    } else {
      "Failed to rename ${relativeToRoot(file)}"
    }
  }

  fun deletePath(path: String): String {
    val file = safeFile(path)
    if (!file.exists()) return "Path does not exist: $path"
    if (file.canonicalFile == root.canonicalFile) return "Cannot delete workspace root."
    val relative = relativeToRoot(file)
    snapshotStore.createAutomatic("delete:$relative")
    val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
    return if (deleted) {
      "Deleted $relative"
    } else {
      "Failed to delete $relative"
    }
  }

  private fun toNode(file: File): WorkspaceFileNode {
    val isDirectory = file.isDirectory
    val children = if (isDirectory) {
      file.listFiles()
        ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        ?.map { toNode(it) }
        ?: emptyList()
    } else {
      emptyList()
    }
    return WorkspaceFileNode(
      name = if (file == root) "workspace" else file.name,
      path = relativeToRoot(file).let { if (it == ".") "" else it },
      isDirectory = isDirectory,
      sizeBytes = if (isDirectory) 0L else file.length(),
      children = children,
    )
  }

  private fun searchFile(
    file: File,
    query: String,
    tokens: List<String>,
    caseSensitive: Boolean,
    mode: String,
    regex: Regex?,
    contextLines: Int,
    maxSnippetChars: Int,
  ): List<WorkspaceSearchHit> {
    val path = relativeToRoot(file)
    val pathScore = workspaceSearchPathScore(path, query, tokens, caseSensitive, mode, regex)
    val hits = mutableListOf<WorkspaceSearchHit>()
    var firstNonBlank: Pair<Int, String>? = null
    val lines = file.readLines(Charsets.UTF_8)
    lines.forEachIndexed { index, line ->
      if (firstNonBlank == null && line.isNotBlank()) {
        firstNonBlank = index + 1 to line
      }
      val score = pathScore + workspaceSearchLineScore(line, query, tokens, caseSensitive, mode, regex)
      if (score > 0) {
        hits += WorkspaceSearchHit(
          path = path,
          lineNumber = index + 1,
          score = score,
          snippet = workspaceSearchSnippet(lines, index, contextLines, maxSnippetChars),
          context = workspaceSearchContext(lines, index, contextLines, maxSnippetChars),
        )
      }
    }
    if (hits.isEmpty() && pathScore > 0) {
      val preview = firstNonBlank ?: (1 to "")
      hits += WorkspaceSearchHit(
        path = path,
        lineNumber = preview.first,
        score = pathScore,
        snippet = workspaceSearchSnippet(preview.second, maxSnippetChars),
        context = listOf(WorkspaceSearchContextLine(preview.first, workspaceSearchSnippet(preview.second, maxSnippetChars), isMatch = true)),
      )
    }
    return hits
  }

  private fun workspaceSearchCandidates(
    requested: File,
    scope: String,
    ignoreRules: List<WorkspaceIgnoreRule>,
  ): Sequence<File> = sequence {
    if (requested.isFile) {
      yield(requested)
      return@sequence
    }

    suspend fun SequenceScope<File>.visit(dir: File) {
      dir.listFiles()
        ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        ?.forEach { child ->
          if (Thread.currentThread().isInterrupted) return
          if (child.isDirectory) {
            if (isWorkspaceSearchDirectoryCandidate(child, scope, ignoreRules)) {
              visit(child)
            }
          } else {
            yield(child)
          }
        }
    }

    visit(requested)
  }

  private fun isWorkspaceSearchDirectoryCandidate(
    dir: File,
    scope: String,
    ignoreRules: List<WorkspaceIgnoreRule>,
  ): Boolean {
    val path = relativeToRoot(dir).replace('\\', '/')
    if (path == ".") return true
    if (path == ".flovera") return scope != WORKSPACE_SEARCH_SCOPE_PUBLIC
    if (path.startsWith(".flovera/retrieval") || path.startsWith(".flovera/cache")) return false
    if (path.startsWith(".flovera/")) {
      return scope == WORKSPACE_SEARCH_SCOPE_INTERNAL ||
        (scope == WORKSPACE_SEARCH_SCOPE_APP_METADATA && path.startsWith(".flovera/proposals"))
    }
    if (path.startsWith(".") || path.contains("/.")) return false
    if (isWorkspaceIgnored(path, isDirectory = true, ignoreRules = ignoreRules)) return false
    return true
  }

  private fun isWorkspaceSearchCandidate(
    file: File,
    scope: String,
    includeRegex: Regex?,
    excludeRegex: Regex?,
    ignoreRules: List<WorkspaceIgnoreRule>,
  ): Boolean {
    val path = relativeToRoot(file)
    if (!isWorkspaceSearchPathAllowed(path, scope)) return false
    val normalizedPath = path.replace('\\', '/')
    if (isWorkspaceIgnored(normalizedPath, isDirectory = false, ignoreRules = ignoreRules)) return false
    if (includeRegex != null && !includeRegex.matches(normalizedPath)) return false
    if (excludeRegex != null && excludeRegex.matches(normalizedPath)) return false
    if (file.length() > MAX_WORKSPACE_SEARCH_FILE_BYTES) return false
    if (!isLikelyTextFile(file)) return false
    return true
  }

  private fun isWorkspaceSearchPathAllowed(path: String, scope: String): Boolean {
    val normalized = path.replace('\\', '/')
    if (normalized == ".") return false
    if (normalized.startsWith(".") && !normalized.startsWith(".flovera/")) return false
    if (normalized.contains("/.") && !normalized.startsWith(".flovera/")) return false
    if (!normalized.startsWith(".flovera/")) return true
    if (normalized.startsWith(".flovera/retrieval/") || normalized.startsWith(".flovera/cache/")) return false
    return when (scope) {
      WORKSPACE_SEARCH_SCOPE_PUBLIC -> false
      WORKSPACE_SEARCH_SCOPE_APP_METADATA -> {
        normalized == ".flovera/manifest.json" ||
          normalized == ".flovera/settings-view.json" ||
          normalized == ".flovera/capabilities.json" ||
          normalized.startsWith(".flovera/proposals/")
      }
      WORKSPACE_SEARCH_SCOPE_INTERNAL -> true
      else -> false
    }
  }

  private fun isLikelyTextFile(file: File): Boolean {
    val allowedExtensions = setOf(
      "txt", "md", "markdown", "html", "htm", "css", "js", "mjs", "cjs", "ts", "tsx", "jsx",
      "json", "jsonl", "xml", "csv", "kt", "kts", "java", "gradle", "properties", "yml", "yaml",
      "toml", "ini", "sql", "sh", "ps1", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp",
    )
    if (file.extension.lowercase() in allowedExtensions) return true
    val sample = ByteArray(1024)
    val read = runCatching {
      file.inputStream().use { it.read(sample) }
    }.getOrDefault(0)
    if (read <= 0) return true
    return sample.take(read).none { byte ->
      val value = byte.toInt() and 0xff
      value == 0 || (value < 0x09) || (value in 0x0e..0x1f)
    }
  }

  private fun loadWorkspaceIgnoreRules(): List<WorkspaceIgnoreRule> {
    if (!root.exists()) return emptyList()
    val rules = mutableListOf<WorkspaceIgnoreRule>()
    workspaceSearchIgnoreFiles().forEach { ignoreFile ->
      val basePath = relativeToRoot(ignoreFile.parentFile ?: root).replace('\\', '/').let { if (it == ".") "" else "$it/" }
      readUtf8Text(ignoreFile).lineSequence().forEach { rawLine ->
        workspaceIgnoreRule(basePath, rawLine)?.let { rules += it }
      }
    }
    return rules
  }

  private fun workspaceSearchIgnoreFiles(): Sequence<File> = sequence {
    suspend fun SequenceScope<File>.visit(dir: File) {
      dir.listFiles()
        ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        ?.forEach { child ->
          val path = relativeToRoot(child).replace('\\', '/')
          if (child.isDirectory) {
            if (path != ".flovera" && !path.startsWith(".flovera/") && !path.startsWith(".") && !path.contains("/.")) {
              visit(child)
            }
          } else if (child.name == ".gitignore" || child.name == ".ignore") {
            yield(child)
          }
        }
    }

    visit(root)
  }

  private fun isWorkspaceIgnored(
    path: String,
    isDirectory: Boolean,
    ignoreRules: List<WorkspaceIgnoreRule>,
  ): Boolean {
    var ignored = false
    ignoreRules.forEach { rule ->
      val matches = rule.regex.matches(path) || rule.descendantRegex?.matches(path) == true
      if (matches || (isDirectory && rule.descendantRegex?.matches("$path/") == true)) {
        ignored = !rule.negated
      }
    }
    return ignored
  }

  private fun workspaceSearchFilesOutput(
    query: String,
    requested: File,
    scope: String,
    mode: String,
    hits: List<WorkspaceSearchHit>,
    limit: Int,
    scannedFiles: Int,
    skippedFiles: Int,
    stoppedEarly: Boolean,
    maxFiles: Int,
    elapsedMillis: Long,
    debug: Boolean,
  ): String {
    val allFiles = hits
      .sortedWith(compareByDescending<WorkspaceSearchHit> { it.score }.thenBy { it.path })
      .map { it.path }
      .distinct()
    val files = allFiles.take(limit)
    return buildString {
      appendLine(
        "Found ${allFiles.size} files for \"$query\" " +
          "(path=${relativeToRoot(requested)}, scope=$scope, mode=$mode)" +
          workspaceSearchHeaderSuffix(debug, scannedFiles, skippedFiles, stoppedEarly, maxFiles, elapsedMillis) +
          ":",
      )
      files.forEach { path -> appendLine(path) }
    }.trimEnd()
  }

  private fun workspaceSearchCountOutput(
    query: String,
    requested: File,
    scope: String,
    mode: String,
    hits: List<WorkspaceSearchHit>,
    limit: Int,
    scannedFiles: Int,
    skippedFiles: Int,
    stoppedEarly: Boolean,
    maxFiles: Int,
    elapsedMillis: Long,
    debug: Boolean,
  ): String {
    val allCounts = hits
      .groupingBy { it.path }
      .eachCount()
      .entries
      .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    val counts = allCounts.take(limit)
    return buildString {
      appendLine(
        "Found ${hits.size} matches in ${allCounts.size} files for \"$query\" " +
          "(path=${relativeToRoot(requested)}, scope=$scope, mode=$mode)" +
          workspaceSearchHeaderSuffix(debug, scannedFiles, skippedFiles, stoppedEarly, maxFiles, elapsedMillis) +
          ":",
      )
      counts.forEach { entry -> appendLine("${entry.key} count=${entry.value}") }
    }.trimEnd()
  }

  private fun safeFile(path: String): File {
    val requested = File(root, path).canonicalFile
    val canonicalRoot = root.canonicalFile
    require(requested.path == canonicalRoot.path || requested.path.startsWith(canonicalRoot.path + File.separator)) {
      "Path escapes workspace: $path"
    }
    return requested
  }

  private fun artifactFromManifestFile(file: File): WorkspaceArtifact {
    val manifestPath = relativeToRoot(file)
    val artifactRoot = file.parentFile?.canonicalFile ?: root.canonicalFile
    val rootPath = relativeToRoot(artifactRoot)
    return runCatching {
      val manifest = json.decodeFromString<FloveraWorkspaceArtifactManifest>(readUtf8Text(file))
      val diagnostics = mutableListOf<WorkspaceArtifactDiagnostic>()
      if (manifest.schemaVersion != 1) {
        diagnostics += WorkspaceArtifactDiagnostic(
          level = "error",
          path = "$manifestPath.schemaVersion",
          message = "Unsupported artifact manifest schemaVersion ${manifest.schemaVersion}. Supported schemaVersion: 1.",
        )
      }
      val name = manifest.name.trim().ifBlank {
        diagnostics += WorkspaceArtifactDiagnostic(
          level = "error",
          path = manifestPath,
          message = "Artifact manifest must declare a non-empty name.",
        )
        artifactRoot.name.ifBlank { root.name }
      }
      val preview = artifactPreview(manifest, artifactRoot, diagnostics)
      if (preview == null) {
        diagnostics += WorkspaceArtifactDiagnostic(
          level = "warning",
          path = "$manifestPath.entrypoints.preview",
          message = "No preview entrypoint declared; Flovera can discover the artifact but cannot open an app surface from the manifest.",
        )
      }
      val actions = manifest.actions.mapIndexedNotNull { index, action ->
        artifactAction(action, artifactRoot, "$manifestPath.actions[$index]", diagnostics)
      }
      actions
        .groupBy { it.id }
        .filterValues { it.size > 1 }
        .keys
        .forEach { duplicateId ->
          diagnostics += WorkspaceArtifactDiagnostic(
            level = "error",
            path = "$manifestPath.actions",
            message = "Duplicate artifact action id: $duplicateId",
          )
        }
      val outputs = manifest.outputs.mapNotNull { output ->
        artifactRelativePathOrDiagnostic(artifactRoot, output, "$manifestPath.outputs", diagnostics, mustExist = false)
      }
      WorkspaceArtifact(
        manifestPath = manifestPath,
        rootPath = rootPath,
        name = name,
        kind = manifest.kind.ifBlank { "app" },
        preview = preview,
        actions = actions,
        outputs = outputs,
        diagnostics = diagnostics.toList(),
        valid = diagnostics.none { it.level == "error" },
      )
    }.getOrElse { error ->
      WorkspaceArtifact(
        manifestPath = manifestPath,
        rootPath = rootPath,
        name = artifactRoot.name.ifBlank { root.name },
        kind = "invalid",
        preview = null,
        actions = emptyList(),
        outputs = emptyList(),
        diagnostics = listOf(
          WorkspaceArtifactDiagnostic(
            level = "error",
            path = manifestPath,
            message = "Artifact manifest is not valid JSON for schema v1: ${error.message ?: error::class.java.simpleName}",
          ),
        ),
        valid = false,
      )
    }
  }

  private fun artifactPreview(
    manifest: FloveraWorkspaceArtifactManifest,
    artifactRoot: File,
    diagnostics: MutableList<WorkspaceArtifactDiagnostic>,
  ): WorkspaceArtifactEntrypoint? {
    val preview = manifest.entrypoints["preview"] ?: return null
    val rawServer = manifest.entrypoints["server"]
    if (rawServer != null && rawServer.command.isNotBlank() && rawServer.kind !in WORKSPACE_ARTIFACT_SERVER_KINDS) {
      diagnostics += WorkspaceArtifactDiagnostic(
        level = "error",
        path = "${relativeToRoot(File(artifactRoot, WORKSPACE_ARTIFACT_MANIFEST_NAME))}.entrypoints.server.kind",
        message = "Unsupported server kind '${rawServer.kind}'. Supported server kinds: ${WORKSPACE_ARTIFACT_SERVER_KINDS.joinToString(", ")}.",
      )
      return null
    }
    val server = rawServer?.takeIf { it.kind == WORKSPACE_ARTIFACT_SERVER_PYTHON_HTTP }
    val serverCommand = listOf(preview.command, server?.command.orEmpty(), preview.fallback)
      .firstOrNull { it.isNotBlank() }
      .orEmpty()
    val diagnosticPath = "${relativeToRoot(File(artifactRoot, WORKSPACE_ARTIFACT_MANIFEST_NAME))}.entrypoints.preview"
    if (preview.kind !in WORKSPACE_ARTIFACT_PREVIEW_KINDS) {
      diagnostics += WorkspaceArtifactDiagnostic(
        level = "error",
        path = diagnosticPath,
        message = "Unsupported preview kind '${preview.kind}'. Supported preview kinds: ${WORKSPACE_ARTIFACT_PREVIEW_KINDS.joinToString(", ")}.",
      )
      return null
    }
    val relativePath = artifactRelativePathOrDiagnostic(
      artifactRoot = artifactRoot,
      path = preview.path,
      diagnosticPath = "$diagnosticPath.path",
      diagnostics = diagnostics,
      mustExist = true,
    ) ?: return null
    val file = safeFile(relativePath)
    if (!file.isFile) {
      diagnostics += WorkspaceArtifactDiagnostic(
        level = "error",
        path = "$diagnosticPath.path",
        message = "Preview path must point to a file: ${preview.path}",
      )
      return null
    }
    if (!file.extension.equals("html", ignoreCase = true)) {
      diagnostics += WorkspaceArtifactDiagnostic(
        level = "error",
        path = "$diagnosticPath.path",
        message = "Preview path must be an HTML file: ${preview.path}",
      )
      return null
    }
    return WorkspaceArtifactEntrypoint(
      kind = preview.kind,
      path = relativePath,
      label = preview.label.ifBlank { "Preview" },
      command = serverCommand,
      cwd = artifactRelativePathOrDiagnostic(
        artifactRoot = artifactRoot,
        path = server?.cwd?.ifBlank { "." } ?: preview.cwd.ifBlank { "." },
        diagnosticPath = "${relativeToRoot(File(artifactRoot, WORKSPACE_ARTIFACT_MANIFEST_NAME))}.entrypoints.server.cwd",
        diagnostics = diagnostics,
        mustExist = true,
      ).orEmpty(),
      urlPath = preview.urlPath.ifBlank { "/" },
    )
  }

  private fun artifactAction(
    action: FloveraArtifactAction,
    artifactRoot: File,
    diagnosticPath: String,
    diagnostics: MutableList<WorkspaceArtifactDiagnostic>,
  ): WorkspaceArtifactAction? {
    val id = action.id.trim()
    if (id.isBlank()) {
      diagnostics += WorkspaceArtifactDiagnostic(
        level = "error",
        path = "$diagnosticPath.id",
        message = "Artifact action id must be non-empty.",
      )
      return null
    }
    if (action.kind != WORKSPACE_ARTIFACT_ACTION_PYTHON_JOB) {
      diagnostics += WorkspaceArtifactDiagnostic(
        level = "error",
        path = "$diagnosticPath.kind",
        message = "Unsupported action kind '${action.kind}'. Supported action kinds: $WORKSPACE_ARTIFACT_ACTION_PYTHON_JOB.",
      )
      return null
    }
    if (action.command.isBlank()) {
      diagnostics += WorkspaceArtifactDiagnostic(
        level = "error",
        path = "$diagnosticPath.command",
        message = "python_job actions must declare a command.",
      )
    }
    val cwd = artifactRelativePathOrDiagnostic(
      artifactRoot = artifactRoot,
      path = action.cwd.ifBlank { "." },
      diagnosticPath = "$diagnosticPath.cwd",
      diagnostics = diagnostics,
      mustExist = true,
    ) ?: return null
    if (!safeFile(cwd).isDirectory) {
      diagnostics += WorkspaceArtifactDiagnostic(
        level = "error",
        path = "$diagnosticPath.cwd",
        message = "Action cwd must point to a directory: ${action.cwd}",
      )
    }
    val inputPath = action.inputPath.takeIf { it.isNotBlank() }?.let { input ->
      artifactRelativePathOrDiagnostic(artifactRoot, input, "$diagnosticPath.inputPath", diagnostics, mustExist = false)
    }.orEmpty()
    val outputs = action.outputs.mapNotNull { output ->
      artifactRelativePathOrDiagnostic(artifactRoot, output, "$diagnosticPath.outputs", diagnostics, mustExist = false)
    }
    return WorkspaceArtifactAction(
      id = id,
      label = action.label.ifBlank { id },
      kind = action.kind,
      command = action.command,
      cwd = cwd,
      inputPath = inputPath,
      timeoutMs = action.timeoutMs.coerceIn(WORKSPACE_ARTIFACT_MIN_TIMEOUT_MS, WORKSPACE_ARTIFACT_MAX_TIMEOUT_MS),
      networkEnabled = action.networkEnabled,
      environment = action.environment.filterKeys { it.isNotBlank() },
      outputs = outputs,
    )
  }

  private fun artifactRelativePathOrThrow(artifactRoot: File, path: String): String {
    require(path.isNotBlank()) { "Path must be non-empty." }
    val file = File(artifactRoot, path).canonicalFile
    val canonicalRoot = root.canonicalFile
    require(file.path == canonicalRoot.path || file.path.startsWith(canonicalRoot.path + File.separator)) {
      "Path escapes workspace: $path"
    }
    return relativeToRoot(file)
  }

  private fun artifactRelativePathOrDiagnostic(
    artifactRoot: File,
    path: String,
    diagnosticPath: String,
    diagnostics: MutableList<WorkspaceArtifactDiagnostic>,
    mustExist: Boolean,
  ): String? {
    if (path.isBlank()) {
      diagnostics += WorkspaceArtifactDiagnostic(
        level = "error",
        path = diagnosticPath,
        message = "Path must be non-empty.",
      )
      return null
    }
    return runCatching {
      val file = File(artifactRoot, path).canonicalFile
      val canonicalRoot = root.canonicalFile
      require(file.path == canonicalRoot.path || file.path.startsWith(canonicalRoot.path + File.separator)) {
        "Path escapes workspace: $path"
      }
      if (mustExist && !file.exists()) {
        error("Path does not exist: $path")
      }
      relativeToRoot(file)
    }.getOrElse { error ->
      diagnostics += WorkspaceArtifactDiagnostic(
        level = "error",
        path = diagnosticPath,
        message = error.message ?: "Invalid path: $path",
      )
      null
    }
  }

  private fun workspaceArtifactJobFile(jobId: String): File? {
    if (!WORKSPACE_ARTIFACT_JOB_ID_REGEX.matches(jobId)) return null
    return safeFile(".flovera/jobs/$jobId.json")
  }

  private fun markStaleWorkspaceArtifactJobsInterrupted() {
    val jobsDir = safeFile(".flovera/jobs")
    jobsDir.listFiles()
      ?.filter { it.isFile && it.extension == "json" }
      ?.forEach { file ->
        val job = runCatching { compactJson.decodeFromString<WorkspaceArtifactJob>(readUtf8Text(file)) }.getOrNull()
        if (job?.status == WORKSPACE_ARTIFACT_JOB_QUEUED || job?.status == WORKSPACE_ARTIFACT_JOB_RUNNING) {
          writeWorkspaceArtifactJob(
            job.copy(
              status = WORKSPACE_ARTIFACT_JOB_INTERRUPTED,
              error = "Flovera restarted before this artifact job finished.",
            ),
          )
        }
      }
  }

  private fun displayName(uri: Uri): String? {
    return runCatching {
      appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } else {
          null
        }
      }
    }.getOrNull()
  }

  private fun sanitizeRootFileName(name: String): String {
    val leaf = name.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = leaf.map { char ->
      when {
        char.isISOControl() -> '_'
        char == '/' || char == '\\' || char == ':' || char == '*' || char == '?' || char == '"' || char == '<' || char == '>' || char == '|' -> '_'
        else -> char
      }
    }.joinToString("").trim().trim('.')
    return cleaned.ifBlank { "shared-file" }
  }

  private fun uniqueRootFileName(name: String): String {
    val base = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    fun candidate(index: Int): String {
      val suffix = if (index == 0) "" else " ($index)"
      return if (extension.isBlank() || base == name) "$base$suffix" else "$base$suffix.$extension"
    }
    var index = 0
    while (safeFile(candidate(index)).exists()) index += 1
    return candidate(index)
  }

  private fun relativeToRoot(file: File): String {
    return file.canonicalFile.toRelativeString(root.canonicalFile).ifBlank { "." }
  }

  private companion object {
    const val WORKSPACE_ARTIFACT_MANIFEST_NAME = "flovera.app.json"
    const val WORKSPACE_ARTIFACT_PREVIEW_WEBVIEW = "webview"
    const val WORKSPACE_ARTIFACT_PREVIEW_LOCAL_HTTP = "local_http"
    val WORKSPACE_ARTIFACT_PREVIEW_KINDS = setOf(WORKSPACE_ARTIFACT_PREVIEW_WEBVIEW, WORKSPACE_ARTIFACT_PREVIEW_LOCAL_HTTP)
    const val WORKSPACE_ARTIFACT_SERVER_PYTHON_HTTP = "python_http"
    val WORKSPACE_ARTIFACT_SERVER_KINDS = setOf(WORKSPACE_ARTIFACT_SERVER_PYTHON_HTTP)
    const val WORKSPACE_ARTIFACT_ACTION_PYTHON_JOB = "python_job"
    const val MAX_WORKSPACE_ARTIFACT_MANIFESTS = 200
    const val WORKSPACE_ARTIFACT_MIN_TIMEOUT_MS = 1_000
    const val WORKSPACE_ARTIFACT_MAX_TIMEOUT_MS = 600_000
    const val WORKSPACE_ARTIFACT_JOB_QUEUED = "queued"
    const val WORKSPACE_ARTIFACT_JOB_RUNNING = "running"
    const val WORKSPACE_ARTIFACT_JOB_INTERRUPTED = "interrupted"
    val WORKSPACE_ARTIFACT_JOB_ID_REGEX = Regex("[0-9a-fA-F-]{36}")
    const val WORKSPACE_SEARCH_SCOPE_PUBLIC = "workspace_public"
    const val WORKSPACE_SEARCH_SCOPE_APP_METADATA = "workspace_app_metadata"
    const val WORKSPACE_SEARCH_SCOPE_INTERNAL = "workspace_internal"
    const val WORKSPACE_SEARCH_MODE_LITERAL = "literal"
    const val WORKSPACE_SEARCH_MODE_REGEX = "regex"
    const val WORKSPACE_SEARCH_OUTPUT_MATCHES = "matches"
    const val WORKSPACE_SEARCH_OUTPUT_FILES = "files"
    const val WORKSPACE_SEARCH_OUTPUT_COUNT = "count"
    const val MAX_WORKSPACE_SEARCH_RESULTS = 25
    const val MAX_WORKSPACE_SEARCH_CONTEXT_LINES = 5
    const val MAX_WORKSPACE_SEARCH_FILE_BYTES = 512 * 1024L
    const val DEFAULT_WORKSPACE_SEARCH_MAX_FILES = 2000
    const val MAX_WORKSPACE_SEARCH_FILES = 10000
    const val DEFAULT_WORKSPACE_SEARCH_SNIPPET_CHARS = 200
    const val MIN_WORKSPACE_SEARCH_SNIPPET_CHARS = 80
    const val MAX_WORKSPACE_SEARCH_SNIPPET_CHARS = 500
  }
}

private fun normalizeWorkspaceSearchScope(scope: String): String {
  return when (scope.trim().lowercase()) {
    "", "workspace", "public", "workspace_public" -> "workspace_public"
    "metadata", "app_metadata", "workspace_app_metadata", "flovera_metadata" -> "workspace_app_metadata"
    "internal", "workspace_internal", "all" -> "workspace_internal"
    else -> "workspace_public"
  }
}

private fun normalizeWorkspaceSearchMode(mode: String): String {
  return when (mode.trim().lowercase()) {
    "regex", "regexp" -> "regex"
    else -> "literal"
  }
}

private fun normalizeWorkspaceSearchOutput(output: String): String {
  return when (output.trim().lowercase()) {
    "file", "files", "files_with_matches", "paths" -> "files"
    "count", "counts", "count_only" -> "count"
    else -> "matches"
  }
}

private fun workspaceSearchTokens(query: String, caseSensitive: Boolean): List<String> {
  val source = if (caseSensitive) query else query.lowercase()
  return Regex("[\\p{L}\\p{N}_./:-]+")
    .findAll(source)
    .map { it.value.trim('.', '/', ':', '-') }
    .filter { it.length >= 2 }
    .distinct()
    .toList()
}

private fun workspaceSearchPathScore(
  path: String,
  query: String,
  tokens: List<String>,
  caseSensitive: Boolean,
  mode: String,
  regex: Regex?,
): Int {
  val haystack = if (caseSensitive) path else path.lowercase()
  val needle = if (caseSensitive) query else query.lowercase()
  var score = 0
  if (mode == "regex" && regex?.containsMatchIn(path) == true) score += 24
  if (mode == "literal" && needle.length >= 2 && haystack.contains(needle)) score += 24
  tokens.forEach { token ->
    if (haystack.contains(token)) score += if (path.substringAfterLast('/').lowercase().contains(token)) 8 else 4
  }
  return score
}

private fun workspaceSearchLineScore(
  line: String,
  query: String,
  tokens: List<String>,
  caseSensitive: Boolean,
  mode: String,
  regex: Regex?,
): Int {
  val haystack = if (caseSensitive) line else line.lowercase()
  val needle = if (caseSensitive) query else query.lowercase()
  var score = 0
  if (mode == "regex" && regex?.containsMatchIn(line) == true) score += 40
  if (mode == "literal" && needle.length >= 2 && haystack.contains(needle)) score += 40
  tokens.forEach { token ->
    if (haystack.contains(token)) score += 12
  }
  return score
}

private fun workspaceGlobRegex(glob: String): Regex? {
  val raw = glob.trim()
  if (raw.isBlank()) return null
  val normalized = raw.replace('\\', '/').let { value ->
    if ("/" in value) value else "**/$value"
  }
  val pattern = buildString {
    append("^")
    val chars = normalized
    var index = 0
    while (index < chars.length) {
      val char = chars[index]
      when {
        char == '*' && index + 1 < chars.length && chars[index + 1] == '*' && index + 2 < chars.length && chars[index + 2] == '/' -> {
          append("(?:.*/)?")
          index += 2
        }
        char == '*' && index + 1 < chars.length && chars[index + 1] == '*' -> {
          append(".*")
          index += 1
        }
        char == '*' -> append("[^/]*")
        char == '?' -> append("[^/]")
        char == '.' -> append("\\.")
        char == '/' -> append("/")
        else -> append(Regex.escape(char.toString()))
      }
      index += 1
    }
    append("$")
  }
  return Regex(pattern, RegexOption.IGNORE_CASE)
}

private fun workspaceIgnoreRule(basePath: String, rawLine: String): WorkspaceIgnoreRule? {
  var line = rawLine.trim()
  if (line.isBlank() || line.startsWith("#")) return null
  val negated = line.startsWith("!")
  if (negated) line = line.drop(1).trim()
  if (line.isBlank()) return null
  val directoryOnly = line.endsWith("/")
  line = line.trim('/')
  if (line.isBlank()) return null
  val anchored = rawLine.trim().removePrefix("!").startsWith("/")
  val hasSlash = "/" in line
  val pattern = when {
    anchored || hasSlash -> basePath + line
    else -> basePath + "**/$line"
  }
  val regex = workspaceGlobRegex(pattern) ?: return null
  val descendantRegex = if (directoryOnly) workspaceGlobRegex("$pattern/**") else null
  return WorkspaceIgnoreRule(regex = regex, descendantRegex = descendantRegex, negated = negated)
}

private fun workspaceSearchSummary(scannedFiles: Int, skippedFiles: Int, stoppedEarly: Boolean, maxFiles: Int): String {
  val stopped = if (stoppedEarly) ", stoppedAfterMaxFiles=$maxFiles" else ""
  return "scannedFiles=$scannedFiles, skippedFiles=$skippedFiles$stopped"
}

private fun workspaceSearchHeaderSuffix(
  debug: Boolean,
  scannedFiles: Int,
  skippedFiles: Int,
  stoppedEarly: Boolean,
  maxFiles: Int,
  elapsedMillis: Long,
): String {
  if (debug) return " (${workspaceSearchSummary(scannedFiles, skippedFiles, stoppedEarly, maxFiles)}, elapsedMs=$elapsedMillis)"
  return if (stoppedEarly) " (stoppedAfterMaxFiles=$maxFiles)" else ""
}

private fun workspaceSearchSnippet(lines: List<String>, index: Int, contextLines: Int, maxChars: Int): String {
  if (contextLines <= 0) return workspaceSearchSnippet(lines[index], maxChars)
  val start = (index - contextLines).coerceAtLeast(0)
  val end = (index + contextLines).coerceAtMost(lines.lastIndex)
  return (start..end).joinToString(" | ") { lineIndex ->
    val marker = if (lineIndex == index) ">" else " "
    "$marker${lineIndex + 1}: ${workspaceSearchSnippet(lines[lineIndex], maxChars)}"
  }
}

private fun workspaceSearchContext(
  lines: List<String>,
  index: Int,
  contextLines: Int,
  maxChars: Int,
): List<WorkspaceSearchContextLine> {
  val start = (index - contextLines).coerceAtLeast(0)
  val end = (index + contextLines).coerceAtMost(lines.lastIndex)
  return (start..end).map { lineIndex ->
    WorkspaceSearchContextLine(
      lineNumber = lineIndex + 1,
      text = workspaceSearchSnippet(lines[lineIndex], maxChars),
      isMatch = lineIndex == index,
    )
  }
}

private fun workspaceSearchSnippet(line: String, maxChars: Int): String {
  val normalized = line.trim().replace(Regex("\\s+"), " ")
  if (normalized.length <= maxChars) return normalized
  return normalized.take((maxChars - 3).coerceAtLeast(1)) + "..."
}

private fun workspaceSearchMatchesOutput(
  query: String,
  requestedPath: String,
  scope: String,
  mode: String,
  hits: List<WorkspaceSearchHit>,
  totalMatches: Int,
  scannedFiles: Int,
  skippedFiles: Int,
  stoppedEarly: Boolean,
  maxFiles: Int,
  elapsedMillis: Long,
  debug: Boolean,
): String {
  return buildString {
    appendLine(
      "Found $totalMatches matches for \"$query\" " +
        "(path=$requestedPath, scope=$scope, mode=$mode)" +
        workspaceSearchHeaderSuffix(debug, scannedFiles, skippedFiles, stoppedEarly, maxFiles, elapsedMillis) +
        ":",
    )
    hits.groupBy { it.path }.forEach { (path, fileHits) ->
      val lineNumbers = fileHits.map { it.lineNumber }.distinct().sorted().joinToString(",")
      val debugSuffix = if (debug) " maxScore=${fileHits.maxOf { it.score }}" else ""
      appendLine("$path:$lineNumbers$debugSuffix")
      workspaceSearchMergedContext(fileHits).forEach { line ->
        val marker = if (line.isMatch) ">" else " "
        appendLine("$marker${line.lineNumber}: ${line.text}")
      }
    }
  }.trimEnd()
}

private fun workspaceSearchMergedContext(hits: List<WorkspaceSearchHit>): List<WorkspaceSearchContextLine> {
  return hits
    .flatMap { hit ->
      hit.context.ifEmpty {
        listOf(WorkspaceSearchContextLine(hit.lineNumber, hit.snippet, isMatch = true))
      }
    }
    .groupBy { it.lineNumber }
    .map { (lineNumber, lines) ->
      WorkspaceSearchContextLine(
        lineNumber = lineNumber,
        text = lines.first().text,
        isMatch = lines.any { it.isMatch },
      )
    }
    .sortedBy { it.lineNumber }
}

@Serializable
data class FloveraWorkspaceManifest(
  val version: Int = 1,
  val workspaceId: String,
  val settingsViewPath: String,
  val capabilitiesPath: String,
  val proposalsPath: String,
)

@Serializable
data class FloveraSettingsView(
  val provider: String = "",
  val providerApiMode: String = "",
  val providerTransport: String = "",
  val providerBaseUrl: String = "",
  val providerModelsUrl: String = "",
  val providerResponsesPath: String = "",
  val providerMessagesPath: String = "",
  val providerModelsPath: String = "",
  val providerAuthType: String = "api_key",
  val providerDefaultHeaderNames: List<String> = emptyList(),
  val providerSupportsHealthCheck: Boolean = true,
  val model: String = "",
  val activeWorkspaceId: String = "",
  val activeSessionId: String? = null,
  val selectedHtmlPath: String = "",
  val pinnedHtmlPaths: List<String> = emptyList(),
  val recentHtmlPaths: List<String> = emptyList(),
  val workspaceArtifactManifestName: String = "flovera.app.json",
  val workspaceArtifactJobsPath: String = ".flovera/jobs",
  val maxAgentIterations: Int = 0,
  val networkEnabled: Boolean = false,
  val networkUserConfigured: Boolean = false,
  val webSearchEnabled: Boolean = false,
  val webSearchUserConfigured: Boolean = false,
  val backgroundKeepAliveEnabled: Boolean = false,
  val pythonRunToolFallbackEnabled: Boolean = false,
  val language: String = "",
  val themeMode: String = "",
  val themeColor: String = "",
  val authorityMode: String = "safe",
  val deepSeekThinkingEffort: String = "high",
  val reasoningEffort: String = "",
  val customOpenAIBaseUrl: String = "",
  val customOpenAIChatCompletionsPath: String = "/v1/chat/completions",
  val customOpenAICompatibilityMode: String = "generic",
  val openRouterProviderPreferences: JsonObject = JsonObject(emptyMap()),
  val openRouterMinCodingScore: Double? = null,
  val providerInjectsOllamaNumCtx: Boolean = false,
  val providerInjectsOpenRouterRouting: Boolean = false,
  val providerRequestHookIds: List<String> = emptyList(),
  val providerRequestOmittedFields: List<String> = emptyList(),
  val providerRequestAddedFields: List<String> = emptyList(),
  val modelContextWindowTokens: Int? = null,
  val modelContextSource: String = "unknown",
  val modelSupportsReasoning: Boolean = false,
  val tokenUsageSource: String = "estimate",
  val compressionThresholdPercent: Int? = null,
  val apiKeyRef: String = "",
  val braveSearchApiKeyRef: String = "",
)

@Serializable
data class FloveraProviderProfileView(
  val id: String,
  val label: String,
  val apiMode: String,
  val transport: String,
  val aliases: List<String> = emptyList(),
  val defaultModel: String,
  val suggestedModels: List<String> = emptyList(),
  val modelContexts: Map<String, FloveraModelContextView> = emptyMap(),
  val baseUrl: String = "",
  val modelsUrl: String = "",
  val responsesPath: String = "",
  val messagesPath: String = "",
  val modelsPath: String = "",
  val authType: String = "api_key",
  val defaultHeaderNames: List<String> = emptyList(),
  val supportsHealthCheck: Boolean = true,
  val defaultMaxTokens: Int? = null,
  val defaultAuxModel: String = "",
  val requestCompatibilityModes: List<String> = listOf("generic"),
  val requestHooks: List<String> = emptyList(),
  val omittedRequestFields: List<String> = emptyList(),
  val addedRequestFields: List<String> = emptyList(),
  val customRequestBody: Boolean = false,
)

@Serializable
data class FloveraModelContextView(
  val contextWindowTokens: Int? = null,
  val source: String = "unknown",
  val usageSource: String = "estimate",
  val compressionThresholdPercent: Int? = null,
  val supportsReasoning: Boolean = false,
)

@Serializable
data class FloveraCapabilities(
  val workspaceFiles: Boolean = true,
  val workspaceSearch: Boolean = true,
  val workspaceSearchScopes: List<String> = listOf("workspace_public", "workspace_app_metadata", "workspace_internal"),
  val artifactInspect: Boolean = true,
  val artifactInspectFormats: List<String> = listOf("json", "html", "docx", "xlsx", "pdf", "png", "jpg", "jpeg", "webp", "text"),
  val workspaceArtifacts: Boolean = true,
  val workspaceArtifactManifestName: String = "flovera.app.json",
  val workspaceArtifactPreviewKinds: List<String> = listOf("webview", "local_http"),
  val workspaceArtifactPreferredPreviewKind: String = "local_http",
  val workspaceArtifactLocalHttp: Boolean = true,
  val workspaceArtifactPythonHttp: Boolean = true,
  val workspaceArtifactWorkspaceOwnedHttp: Boolean = true,
  val workspaceArtifactPythonHttpLifecycle: Boolean = true,
  val workspaceArtifactPythonHttpDiagnostics: Boolean = true,
  val workspaceArtifactViewportHelper: Boolean = true,
  val workspaceArtifactViewportCssVars: List<String> = listOf(
    "--flovera-viewport-height",
    "--flovera-viewport-width",
    "--flovera-safe-bottom",
  ),
  val workspaceArtifactVisibleContentCheck: Boolean = true,
  val workspaceArtifactLocalHttpRoutes: List<String> = listOf(
    "/__flovera__/workspace/<path>",
    "/__flovera__/api/health",
    "/__flovera__/api/deepseek/stream",
    "artifact python_http command routes",
  ),
  val workspaceArtifactActionKinds: List<String> = listOf("python_job"),
  val workspaceArtifactPythonJobNetwork: Boolean = true,
  val workspaceArtifactEnvironmentRefs: List<String> = listOf(
    "provider:deepseek.apiKey",
    "provider:deepseek.baseUrl",
    "provider:deepseek.model",
  ),
  val workspaceArtifactJobsPath: String = ".flovera/jobs",
  val workspaceArtifactBridgeCalls: List<String> = listOf("runAction", "getJob", "cancelJob"),
  val workspaceArtifactJobUi: Boolean = true,
  val seededPortableArtifactDemoPath: String = "agent-demo/flovera.app.json",
  val toolProgressNarration: Boolean = true,
  val agentRunTimeline: Boolean = true,
  val agentRunEventBus: Boolean = true,
  val finalAssistantResponseStreaming: Boolean = true,
  val modelTextDeltaStreaming: Boolean = true,
  val modelTextDeltaStreamingSource: String = "koog_stream_frame_event_handler",
  val modelTextDeltaPolicy: String = "optional_model_output_not_required",
  val finalAssistantResponseStreamingSource: String = "koog_stream_frame_event_handler_compat",
  val mainSurfaceHtmlQuickPicker: Boolean = true,
  val conversationPathLinks: Boolean = true,
  val webPreview: Boolean = true,
  val previewFormats: List<String> = listOf("html", "markdown", "json", "csv", "text", "code", "image", "pdf"),
  val snapshots: Boolean = true,
  val notifications: Boolean = true,
  val foregroundAgentRunService: Boolean = true,
  val backgroundKeepAlive: Boolean = true,
  val backgroundKeepAliveEnabled: Boolean = false,
  val networkTools: Boolean = false,
  val pythonRuntime: Boolean = true,
  val pythonRunTool: Boolean = false,
  val pythonRunToolFallbackEnabled: Boolean = false,
  val workspaceCommandRuntime: Boolean = true,
  val workspaceCommandRuntimeKind: String = "argv",
  val workspaceCommandSupportedCommands: List<String> = listOf("python", "python3", "groovy"),
  val groovyCommandRuntime: Boolean = true,
  val groovyCommandRuntimeStatus: String = "experimental_full_authority",
  val groovyWorkspaceJarClasspath: Boolean = true,
  val jvmWorkspaceLibraries: Boolean = true,
  val jvmWorkspaceLibraryPath: String = "libs",
  val jvmArtifactDexCachePath: String = ".flovera/runtime/jvm-artifacts",
  val jvmArtifactSourceModes: List<String> = listOf("workspace_jar", "maven_coordinate"),
  val jvmMavenCoordinateResolution: Boolean = true,
  val jvmMavenConfigPaths: List<String> = listOf("libs/maven.json", ".flovera/jvm/maven.json"),
  val jvmMavenDefaultRepositories: List<String> = listOf("https://repo1.maven.org/maven2"),
  val jvmMavenTransitiveDependencies: String = "basic_compile_runtime_scope",
  val workspaceCommandShellAccess: Boolean = false,
  val pythonPackageInstall: Boolean = true,
  val pythonPackageCatalogPath: String = ".flovera/python/wheel-catalog.json",
  val pythonWorkspaceSitePackagesPath: String = ".flovera/python/site-packages",
  val pythonToolManifestPath: String = ".flovera/tools/manifest.json",
  val pythonBuiltInPackages: List<String> = listOf("lxml", "python-docx", "openpyxl", "XlsxWriter", "pypdf", "Markdown", "Jinja2"),
  val webSearch: Boolean = false,
  val settingsView: Boolean = true,
  val settingsProposals: Boolean = true,
  val controlledToolProposals: Boolean = true,
  val controlledMcpProposals: Boolean = true,
  val modelContextOverrides: Boolean = true,
  val deepSeekThinkingEffort: Boolean = true,
  val reasoningEffort: Boolean = true,
  val customOpenAICompatibleProvider: Boolean = true,
  val openRouterRouting: Boolean = true,
  val customUrlRouting: Boolean = true,
  val providerProfiles: Boolean = true,
  val providerApiModes: List<String> = listOf("chat_completions", "anthropic_messages"),
  val providerProfileCatalog: List<FloveraProviderProfileView> = emptyList(),
  val providerRequestHooks: Boolean = true,
  val customRequestBody: Boolean = false,
  val directSettingsWrite: Boolean = false,
  val directToolInstall: Boolean = false,
  val directMcpInstall: Boolean = false,
  val executableToolExpansion: Boolean = false,
  val proposalTypes: List<String> = listOf("settings", "tool", "mcp"),
  val authorityMode: String = "safe",
  val supportedAuthorityModes: List<String> = listOf("safe", "assisted", "full"),
  val pendingAuthorityModes: List<String> = emptyList(),
) {
  companion object {
    fun fromSettings(
      settingsView: FloveraSettingsView,
      providerProfileCatalog: List<FloveraProviderProfileView> = emptyList(),
      providerApiModes: List<String> = listOf("chat_completions", "anthropic_messages"),
    ): FloveraCapabilities {
      val fullAuthority = settingsView.authorityMode == "full"
      return FloveraCapabilities(
        networkTools = settingsView.networkEnabled,
        webSearch = settingsView.webSearchEnabled,
        backgroundKeepAliveEnabled = settingsView.backgroundKeepAliveEnabled,
        pythonRunTool = settingsView.pythonRunToolFallbackEnabled,
        pythonRunToolFallbackEnabled = settingsView.pythonRunToolFallbackEnabled,
        providerApiModes = providerApiModes,
        providerProfileCatalog = providerProfileCatalog,
        directSettingsWrite = fullAuthority,
        authorityMode = settingsView.authorityMode,
      )
    }
  }
}

@Serializable
data class FloveraPythonToolsManifest(
  val version: Int = 1,
  val tools: List<FloveraPythonToolManifestEntry> = emptyList(),
)

@Serializable
data class FloveraPythonToolManifestEntry(
  val name: String = "",
  val path: String = "",
  val description: String = "",
  val entrypoint: String = "",
  val permissions: List<String> = listOf("workspace_public"),
)

@Serializable
data class FloveraPythonWheelCatalog(
  val version: Int = 1,
  val packages: List<FloveraPythonWheelPackage>,
) {
  companion object {
    fun default(): FloveraPythonWheelCatalog = FloveraPythonWheelCatalog(
      packages = listOf(
        FloveraPythonWheelPackage(
          name = "openpyxl",
          version = "3.1.5",
          wheelUrl = "https://files.pythonhosted.org/packages/c0/da/977ded879c29cbd04de313843e76868e6e13408a94ed6b987245dc7c8506/openpyxl-3.1.5-py2.py3-none-any.whl",
          sha256 = "5282c12b107bffeef825f4617dc029afaf41d0ea60823bbb665ef3079dc79de2",
          topLevelImports = listOf("openpyxl"),
          dependencies = listOf("et_xmlfile"),
          bundled = true,
        ),
        FloveraPythonWheelPackage(
          name = "et_xmlfile",
          version = "2.0.0",
          wheelUrl = "https://files.pythonhosted.org/packages/c1/8b/5fe2cc11fee489817272089c4203e679c63b570a5aaeb18d852ae3cbba6a/et_xmlfile-2.0.0-py3-none-any.whl",
          sha256 = "7a91720bc756843502c3b7504c77b8fe44217c85c537d85037f0f536151b2caa",
          topLevelImports = listOf("et_xmlfile"),
          bundled = true,
        ),
        FloveraPythonWheelPackage(
          name = "XlsxWriter",
          version = "3.2.9",
          wheelUrl = "https://files.pythonhosted.org/packages/3a/0c/3662f4a66880196a590b202f0db82d919dd2f89e99a27fadef91c4a33d41/xlsxwriter-3.2.9-py3-none-any.whl",
          sha256 = "9a5db42bc5dff014806c58a20b9eae7322a134abb6fce3c92c181bfb275ec5b3",
          topLevelImports = listOf("xlsxwriter"),
          bundled = true,
        ),
        FloveraPythonWheelPackage(
          name = "pypdf",
          version = "6.11.0",
          wheelUrl = "https://files.pythonhosted.org/packages/07/b1/68feb7eb3b99f0c020b414234825f4a5d70e0126c18d933770e8c93a35fc/pypdf-6.11.0-py3-none-any.whl",
          sha256 = "769394d5756d5b304c9b6bef88b54b1816b328e7e6fc9254e625529a15ed4ab8",
          topLevelImports = listOf("pypdf"),
          bundled = true,
        ),
        FloveraPythonWheelPackage(
          name = "Markdown",
          version = "3.10.2",
          wheelUrl = "https://files.pythonhosted.org/packages/de/1f/77fa3081e4f66ca3576c896ae5d31c3002ac6607f9747d2e3aa49227e464/markdown-3.10.2-py3-none-any.whl",
          sha256 = "e91464b71ae3ee7afd3017d9f358ef0baf158fd9a298db92f1d4761133824c36",
          topLevelImports = listOf("markdown"),
          bundled = true,
        ),
        FloveraPythonWheelPackage(
          name = "Jinja2",
          version = "3.1.6",
          wheelUrl = "https://files.pythonhosted.org/packages/62/a1/3d680cbfd5f4b8f15abc1d571870c5fc3e594bb582bc3b64ea099db13e56/jinja2-3.1.6-py3-none-any.whl",
          sha256 = "85ece4451f492d0c13c5dd7c13a64681a86afae63a5f347908daf103ce6d2f67",
          topLevelImports = listOf("jinja2"),
          dependencies = listOf("MarkupSafe"),
          bundled = true,
          purePython = false,
        ),
      ),
    )
  }
}

@Serializable
data class FloveraPythonWheelPackage(
  val name: String,
  val version: String,
  val wheelUrl: String,
  val sha256: String,
  val topLevelImports: List<String>,
  val dependencies: List<String> = emptyList(),
  val purePython: Boolean = true,
  val bundled: Boolean = false,
)

@Serializable
data class WorkspaceSettingsProposalFile(
  val type: String = "settings",
  val title: String = "",
  val reason: String = "",
  val changes: SettingsProposalChanges = SettingsProposalChanges(),
)

@Serializable
data class WorkspaceFullAuthorityAuditRecord(
  val id: String,
  val timestampMillis: Long,
  val action: String,
  val targetPath: String,
  val title: String,
  val reason: String,
  val changes: SettingsProposalChanges,
)

@Serializable
data class WorkspaceCommandAuditRecord(
  val id: String,
  val timestampMillis: Long,
  val command: String,
  val cwd: String,
  val authorityMode: String,
  val riskCategory: String,
  val permissions: List<String>,
  val allowed: Boolean,
  val reason: String,
  val status: String,
  val exitCode: Int,
  val elapsedMs: Int,
)

data class WorkspaceSettingsProposal(
  val path: String,
  val title: String,
  val reason: String,
  val changes: SettingsProposalChanges,
  val createdAtMillis: Long,
)

@Serializable
data class WorkspaceControlledToolProposalFile(
  val type: String = "tool",
  val title: String = "",
  val reason: String = "",
  val name: String = "",
  val description: String = "",
  val command: String = "",
  val endpoint: String = "",
  val requestedCapabilities: List<String> = emptyList(),
  val permissions: List<String> = emptyList(),
)

data class WorkspaceControlledToolProposal(
  val path: String,
  val type: String,
  val title: String,
  val reason: String,
  val name: String,
  val description: String,
  val command: String,
  val endpoint: String,
  val requestedCapabilities: List<String>,
  val permissions: List<String>,
  val createdAtMillis: Long,
)
