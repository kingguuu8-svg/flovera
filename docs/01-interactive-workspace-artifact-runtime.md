# Interactive Workspace Artifact Runtime

This document fixes the product direction for generated interactive artifacts in
Flovera. The goal is not to make every project an HTML plus Python app. The goal
is to let the agent create a normal, portable project that can also be opened,
run, edited, and iterated inside Flovera.

## Product Goal

Flovera should support a product-validation loop like this:

```text
User asks for an interactive agent demo
  -> Flovera generates a workspace project
  -> user opens and uses the generated artifact
  -> Flovera can run the declared actions
  -> outputs, errors, and changed files are visible
  -> user asks for changes
  -> Flovera edits the same project and verifies it again
```

The generated result must be useful outside Flovera. Flovera-specific metadata is
an enhancement layer, not the project identity.

## Core Principle

Generated artifacts must be ordinary projects first:

```text
portable project
  + optional Flovera adapter
```

For example:

```text
agent-demo/
|-- README.md
|-- requirements.txt
|-- flovera.app.json
|-- src/
|   |-- agent.py
|   |-- server.py
|   `-- web/
|       |-- index.html
|       |-- app.js
|       `-- styles.css
|-- data/
`-- outputs/
```

Outside Flovera, the user should still be able to understand and run the project
from `README.md`, `requirements.txt`, `pyproject.toml`, `package.json`, a CLI
entrypoint, a static HTML file, or a standard local server.

Inside Flovera, `flovera.app.json` tells the app how to preview and run the
project without the user wiring commands by hand.

## What `flovera.app.json` Is

`flovera.app.json` is a project adapter. It is not the backend, not a private
application format, and not a replacement for README documentation.

It answers four questions:

1. What is this artifact?
2. What can Flovera open as the primary preview?
3. What actions can Flovera run?
4. Where should action output, artifacts, and diagnostics appear?

Example:

```json
{
  "schema": "https://flovera.dev/schemas/app.v1.json",
  "name": "agent-demo",
  "kind": "interactive",
  "entrypoints": {
    "preview": {
      "kind": "webview",
      "path": "src/web/index.html",
      "fallback": "open src/web/index.html"
    },
    "cli": {
      "kind": "python",
      "command": "python src/agent.py"
    }
  },
  "actions": [
    {
      "id": "run-agent",
      "label": "Run Agent",
      "kind": "python_job",
      "command": "python src/agent.py --input data/input.json --output outputs/result.json",
      "timeoutMs": 120000,
      "outputs": ["outputs/result.json"]
    }
  ]
}
```

The manifest lets the frontend or Flovera UI refer to `run-agent` instead of
hardcoding how Python is launched.

## Connection Model

Do not make each artifact invent its own JSON handoff protocol. Flovera owns the
connection layer, and the primary connection surface for interactive web apps is
standard local HTTP.

```text
WebView page
  -> http://127.0.0.1:<port>
  -> Flovera local HTTP runtime
  -> app-owned HTTP/SSE endpoints or bounded action runner
  -> streamed events, JSON, stdout/stderr/result/artifacts
  -> preview surface and workspace files
```

For a WebView preview, prefer `entrypoints.preview.kind = "local_http"`:

```json
{
  "entrypoints": {
    "preview": {
      "kind": "local_http",
      "path": "src/web/index.html",
      "fallback": "python src/server.py --host 127.0.0.1 --port 8765"
    }
  }
}
```

The page is ordinary browser code. It can call:

```js
const response = await fetch("/__flovera__/api/deepseek/stream", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ messages })
})
```

The response uses `text/event-stream`, so the page can stream model text without
a private JavaScript bridge. The old `window.Flovera.runAction/getJob/cancelJob`
bridge remains a legacy bounded-job adapter, not the default path for new chat
or web app demos.

## Runtime Boundaries

Flovera is not a general operating system and should not promise transparent
process freeze/resume for arbitrary Python, native libraries, sockets, or
threads.

The supported unit is a bounded job:

```text
jobId
status: queued | running | completed | failed | timeout | canceled | interrupted
input
stdout tail
stderr tail
result
declared outputs
resume hint
```

If the app process survives, the UI can reconnect to the job. If Android kills
the process, Flovera marks previously running jobs as `interrupted` on restart
and preserves the latest recorded output and artifacts.

## Portability Rules

Every generated interactive artifact should follow these rules:

- Keep core logic in standard files and standard commands.
- Make `flovera.app.json` optional. Removing it should not make the project
  incomprehensible.
- Avoid making business logic depend directly on `window.Flovera`.
- Provide fallback instructions for non-Flovera environments.
- Store inputs and outputs in standard formats such as JSON, Markdown, CSV,
  SQLite, images, PDF, or office files.
- Keep secrets outside the project. Use Flovera settings inside Flovera and
  environment variables outside Flovera.

## Implementation Flow

### L0: Manifest Draft And Discovery

- Define `flovera.app.json` schema v1.
- Add workspace discovery for files named `flovera.app.json`.
- Parse and validate manifest fields with explicit diagnostics.
- Show discovered interactive artifacts in the file/workspace UI.

Acceptance:

- Invalid manifests show actionable errors.
- Valid manifests expose name, preview entrypoint, actions, and outputs.
- A project without a manifest still opens through normal file behavior.

### L1: Preview Entrypoint

- Let manifest `entrypoints.preview` open the declared surface.
- First supported preview kind: `webview` with a workspace-relative path.
- Preserve current plain HTML opening behavior as fallback.

Acceptance:

- Opening an artifact preview does not expose URL chrome.
- Missing preview files produce a recoverable error.
- The same HTML still opens as a normal file without the manifest.

### L2: Action Runner

- Add an app-owned action dispatcher.
- First action kind: `python_job`, backed by the existing controlled Python
  runtime.
- Bind action input to command arguments or stdin according to schema.
- Record action events in the session and in workspace job metadata.

Acceptance:

- A button or command can start a declared action by id.
- Timeout, cancellation, stdout, stderr, exit code, and produced files are
  visible.
- Action execution uses current Flovera permission and network settings.

### L3: Job State And Reconnect

- Persist job state under `.flovera/jobs/`.
- Keep bounded stdout/stderr tails and declared output paths.
- Mark stale `running` jobs as `interrupted` on app restart.
- Let UI query job status by `jobId`.

Acceptance:

- Activity recreation does not hide an active job.
- App restart explains interrupted jobs instead of pretending they completed.
- The user or agent can rerun the same action with the same input.

### L4: Local HTTP Preview Runtime

- Serve `local_http` preview entrypoints from `http://127.0.0.1:<port>`.
- Serve workspace static files under `/__flovera__/workspace/<path>`.
- Expose app-owned HTTP endpoints under `/__flovera__/api/`.
- First streaming endpoint: `POST /__flovera__/api/deepseek/stream`.
- Keep the legacy WebView bridge only for bounded-job compatibility.

Acceptance:

- A generated preview can call a standard HTTP/SSE endpoint without
  `window.Flovera`.
- Missing provider keys fail through a visible HTTP/SSE error.
- Pages still work outside Flovera through a documented local server command.

### L5: Product Validation Demo

- Rebuild the existing `agent-app` as a portable artifact:
  - standard CLI path outside Flovera;
  - `local_http` preview inside Flovera;
  - WebView or other preview as the editable interaction surface;
  - outputs in standard files.
- Verify the loop:
  1. user opens preview;
  2. user sends a message or starts an interaction;
  3. the preview calls the local HTTP/SSE runtime;
  4. streamed output appears in the preview or session;
  5. workspace file changes are visible;
  6. user asks Flovera to modify the artifact;
  7. Flovera edits and reopens or reruns the artifact.

Acceptance:

- The demo is useful inside Flovera.
- The demo is still understandable and runnable after export.
- No project-specific JSON handoff is required for the main flow.

## Phase 2 Hardening Scope

Hardening after L4/L5 is about the workspace app runtime base, not about adding
more features to the seeded demo. The seeded demo is only a probe for the common
path:

```text
Workspace HTML/WebView -> localhost HTTP API -> SSE stream -> optional Python/export path
```

The runtime base should be verified along these axes:

| Area | What must be proven |
| --- | --- |
| Local HTTP server | WebView can reach `127.0.0.1:<port>`; static files, API routes, status codes, CORS, cache policy, and `.flovera` denial are stable. |
| SSE streaming | Chunk boundaries, empty deltas, error events, timeouts, connection aborts, and `[DONE]` are handled without corrupting the UI state. |
| WebView fetch | Workspace pages can use standard `fetch` against localhost routes without depending on `window.Flovera`. |
| Python HTTP app shape | A generated app can expose a normal Python local server outside Flovera while using app-owned localhost routes inside Flovera. |
| Lifecycle | Activity recreation, app background/foreground, workspace switching, and HTML switching do not leave stale ports or dead selected URLs. |
| Optional live provider smoke | Normal verification must not need API keys; when a key is supplied, a live DeepSeek SSE smoke test proves the real provider route. |

UI should reflect the same boundary:

- `Workspace Apps` means entries declared by `flovera.app.json`, including
  `local_http` previews.
- `HTML Files` means ordinary HTML file previews.
- The seeded `agent-demo` must not look hardcoded; it is just the default
  workspace app manifest shipped with a new workspace.

## Non-Goals

- Do not implement transparent restoration of arbitrary Python execution after
  process death.
- Do not require every interactive artifact to be HTML.
- Do not require every artifact to use Python.
- Do not introduce a general unconstrained daemon runtime.
- Do not make Flovera-only project formats the default output.

## First Implementation Target

The first product-ready slice should be:

```text
manifest discovery
  + local_http preview entrypoint
  + localhost static file server
  + app-owned HTTP/SSE DeepSeek endpoint
  + one portable workspace chat demo
  + legacy python_job and WebView bridge compatibility
```

This is enough to validate whether Flovera can generate and operate a real
interactive workspace artifact without locking the user into a private format.
