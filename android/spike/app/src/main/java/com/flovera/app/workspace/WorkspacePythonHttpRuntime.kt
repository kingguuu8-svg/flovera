package com.flovera.app.workspace

import com.chaquo.python.Python
import com.flovera.app.koog.FloveraPythonRuntime
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class WorkspacePythonHttpRuntimeStatus(
  val manifestPath: String,
  val rootPath: String,
  val state: String,
  val url: String = "",
  val port: Int? = null,
  val command: String = "",
  val cwd: String = "",
  val startedAtMillis: Long? = null,
  val detail: String = "",
)

class WorkspacePythonHttpRuntime(
  private val workspace: WorkspaceManager,
) {
  private val servers = ConcurrentHashMap<String, RunningServer>()
  private val lastErrors = ConcurrentHashMap<String, String>()

  fun previewUrl(artifact: WorkspaceArtifact): String? {
    val preview = artifact.preview ?: return null
    if (preview.kind != WORKSPACE_ARTIFACT_PREVIEW_LOCAL_HTTP || preview.command.isBlank()) return null
    val key = runtimeKey(artifact, preview)
    return runCatching {
      val server = ensureServer(artifact, preview)
      lastErrors.remove(key)
      server.url
    }.getOrElse { error ->
      lastErrors[key] = error.message ?: error::class.java.simpleName
      throw error
    }
  }

  fun statusFor(artifact: WorkspaceArtifact): WorkspacePythonHttpRuntimeStatus? {
    val preview = artifact.preview ?: return null
    if (preview.kind != WORKSPACE_ARTIFACT_PREVIEW_LOCAL_HTTP || preview.command.isBlank()) return null
    val key = runtimeKey(artifact, preview)
    val running = servers[key]
    if (running?.isOpen() == true) {
      return running.toStatus("running")
    }
    if (running != null) servers.remove(key, running)
    val error = lastErrors[key]
    if (!error.isNullOrBlank()) {
      return WorkspacePythonHttpRuntimeStatus(
        manifestPath = artifact.manifestPath,
        rootPath = artifact.rootPath,
        state = "error",
        command = preview.command,
        cwd = preview.cwd,
        detail = error,
      )
    }
    return WorkspacePythonHttpRuntimeStatus(
      manifestPath = artifact.manifestPath,
      rootPath = artifact.rootPath,
      state = "stopped",
      command = preview.command,
      cwd = preview.cwd,
    )
  }

  fun statusesFor(artifacts: List<WorkspaceArtifact>): List<WorkspacePythonHttpRuntimeStatus> {
    return artifacts.mapNotNull { statusFor(it) }
  }

  fun stop(artifact: WorkspaceArtifact): Boolean {
    val preview = artifact.preview ?: return false
    return stopByKey(runtimeKey(artifact, preview))
  }

  fun stopManifest(manifestPath: String): Boolean {
    val runningServers = synchronized(servers) {
      servers.values
        .filter { it.manifestPath == manifestPath }
        .also { matches ->
          matches.forEach { running ->
            servers.remove(running.key, running)
            lastErrors.remove(running.key)
          }
        }
    }
    runningServers.forEach(::stopInBackground)
    return runningServers.isNotEmpty()
  }

  fun stopAll(): Int {
    return servers.keys.count { stopByKey(it) }
  }

  private fun ensureServer(artifact: WorkspaceArtifact, preview: WorkspaceArtifactEntrypoint): RunningServer {
    val key = runtimeKey(artifact, preview)
    servers[key]?.takeIf { it.isOpen() }?.let { return it }
    synchronized(servers) {
      servers[key]?.takeIf { it.isOpen() }?.let { return it }
      val port = reservePort()
      val tokens = splitCommand(preview.command)
      require(tokens.size >= 2) { "python_http command must look like: python path/to/server.py [args...]" }
      require(tokens.first() == "python" || tokens.first() == "python3") {
        "Unsupported python_http launcher '${tokens.first()}'. Use python or python3."
      }
      val cwd = preview.cwd.ifBlank { artifact.rootPath }
      val cwdFile = workspace.workspaceRuntimeDirectory(cwd)
      require(cwdFile.isDirectory) { "python_http cwd must be a directory: $cwd" }
      val scriptFile = File(cwdFile, tokens[1]).canonicalFile
      val workspaceRoot = workspace.root.canonicalFile
      require(scriptFile.path == workspaceRoot.path || scriptFile.path.startsWith(workspaceRoot.path + File.separator)) {
        "python_http script escapes workspace: ${tokens[1]}"
      }
      require(scriptFile.isFile) { "python_http script does not exist: ${tokens[1]}" }
      val argv = normalizeServerArgv(tokens.drop(2), port)
      val environment = mapOf(
        "FLOVERA_WORKSPACE_ROOT" to workspaceRoot.canonicalPath,
        "FLOVERA_ARTIFACT_ROOT" to File(workspaceRoot, artifact.rootPath).canonicalFile.canonicalPath,
        "FLOVERA_HTTP_HOST" to HOST,
        "FLOVERA_HTTP_PORT" to port.toString(),
        "HOST" to HOST,
        "PORT" to port.toString(),
      )
      FloveraPythonRuntime.ensureStarted(workspace)
      val url = "http://127.0.0.1:$port${preview.urlPath.normalizedUrlPath()}"
      val startupError = AtomicReference<Throwable?>(null)
      val running = RunningServer(
        key = key,
        manifestPath = artifact.manifestPath,
        rootPath = artifact.rootPath,
        port = port,
        url = url,
        command = preview.command,
        cwd = cwd,
        startedAtMillis = System.currentTimeMillis(),
        thread = thread(
          start = true,
          isDaemon = true,
          name = "FloveraWorkspacePythonHttp-$port",
        ) {
          try {
            Python.getInstance()
              .getModule("flovera_http_runtime")
              .callAttr(
                "run_script_server",
                scriptFile.canonicalPath,
                workspaceRoot.canonicalPath,
                cwdFile.canonicalPath,
                HOST,
                port,
                json.encodeToString(argv),
                json.encodeToString(environment),
              )
          } catch (error: Throwable) {
            startupError.set(error)
            lastErrors[key] = error.message ?: error::class.java.simpleName
          }
        },
      )
      try {
        waitUntilOpen(port, running.thread, startupError)
      } catch (error: Throwable) {
        runCatching { stopRunningServer(running) }
        throw error
      }
      servers[key] = running
      return running
    }
  }

  private fun stopByKey(key: String): Boolean {
    val running = servers[key] ?: return false
    val finished = stopRunningServer(running)
    if (finished) {
      servers.remove(key, running)
      lastErrors.remove(key)
    }
    return finished
  }

  private fun stopRunningServer(running: RunningServer): Boolean {
    var stopped = false
    repeat(STOP_ATTEMPTS) {
      if (stopped || !running.thread.isAlive) return@repeat
      stopped = runCatching {
        Python.getInstance()
          .getModule("flovera_http_runtime")
          .callAttr("stop_server", running.port)
          .toString()
          .equals("True", ignoreCase = true)
      }.getOrDefault(false)
      if (!stopped) Thread.sleep(STOP_RETRY_DELAY_MS)
    }
    runCatching { running.thread.join(STOP_WAIT_MS) }
    return stopped || !running.thread.isAlive
  }

  private fun stopInBackground(running: RunningServer) {
    thread(
      start = true,
      isDaemon = true,
      name = "FloveraWorkspacePythonHttpStop-${running.port}",
    ) {
      runCatching { stopRunningServer(running) }
    }
  }

  private fun normalizeServerArgv(argv: List<String>, port: Int): List<String> {
    val replaced = argv.map { token ->
      token
        .replace("{HOST}", HOST)
        .replace("\${HOST}", HOST)
        .replace("{PORT}", port.toString())
        .replace("\${PORT}", port.toString())
    }.toMutableList()
    replaceOption(replaced, "--host", HOST)
    replaceOption(replaced, "--port", port.toString())
    if ("--host" !in replaced) {
      replaced += listOf("--host", HOST)
    }
    if ("--port" !in replaced) {
      replaced += listOf("--port", port.toString())
    }
    return replaced
  }

  private fun replaceOption(tokens: MutableList<String>, option: String, value: String) {
    val index = tokens.indexOf(option)
    if (index >= 0) {
      if (index + 1 < tokens.size) {
        tokens[index + 1] = value
      } else {
        tokens += value
      }
    }
  }

  private fun waitUntilOpen(port: Int, thread: Thread, startupError: AtomicReference<Throwable?>) {
    repeat(HTTP_START_ATTEMPTS) {
      if (Thread.currentThread().isInterrupted) {
        throw InterruptedException("python_http startup cancelled")
      }
      if (canConnect(port)) return
      startupError.get()?.let { error ->
        throw IllegalStateException("python_http server failed before opening $HOST:$port: ${error.message ?: error::class.java.simpleName}", error)
      }
      if (!thread.isAlive) {
        error("python_http server exited before opening $HOST:$port")
      }
      Thread.sleep(HTTP_START_DELAY_MS)
    }
    error("python_http server did not open $HOST:$port within ${HTTP_START_ATTEMPTS * HTTP_START_DELAY_MS}ms")
  }

  private fun canConnect(port: Int): Boolean {
    return runCatching {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(HOST, port), HTTP_CONNECT_TIMEOUT_MS)
      }
    }.isSuccess
  }

  private fun reservePort(): Int {
    return ServerSocket(0).use { socket ->
      socket.reuseAddress = true
      socket.localPort
    }
  }

  private fun String.normalizedUrlPath(): String {
    val trimmed = trim().ifBlank { "/" }
    return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
  }

  private fun runtimeKey(artifact: WorkspaceArtifact, preview: WorkspaceArtifactEntrypoint): String {
    return listOf(artifact.manifestPath, artifact.rootPath, preview.command, preview.cwd, preview.urlPath).joinToString("\n")
  }

  private fun splitCommand(command: String): List<String> {
    require(command.none { it == '|' || it == '<' || it == '>' || it == ';' }) {
      "python_http command does not support shell operators."
    }
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaping = false
    for (char in command.trim()) {
      when {
        escaping -> {
          current.append(char)
          escaping = false
        }
        char == '\\' -> escaping = true
        quote != null && char == quote -> quote = null
        quote != null -> current.append(char)
        char == '"' || char == '\'' -> quote = char
        char.isWhitespace() -> {
          if (current.isNotEmpty()) {
            tokens += current.toString()
            current.clear()
          }
        }
        else -> current.append(char)
      }
    }
    require(quote == null) { "python_http command has an unterminated quote." }
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens
  }

  private data class RunningServer(
    val key: String,
    val manifestPath: String,
    val rootPath: String,
    val port: Int,
    val url: String,
    val command: String,
    val cwd: String,
    val startedAtMillis: Long,
    val thread: Thread,
  ) {
    fun isOpen(): Boolean = thread.isAlive

    fun toStatus(state: String): WorkspacePythonHttpRuntimeStatus {
      return WorkspacePythonHttpRuntimeStatus(
        manifestPath = manifestPath,
        rootPath = rootPath,
        state = state,
        url = url,
        port = port,
        command = command,
        cwd = cwd,
        startedAtMillis = startedAtMillis,
      )
    }
  }

  private companion object {
    const val HOST = "127.0.0.1"
    const val WORKSPACE_ARTIFACT_PREVIEW_LOCAL_HTTP = "local_http"
    const val HTTP_START_ATTEMPTS = 120
    const val HTTP_START_DELAY_MS = 100L
    const val HTTP_CONNECT_TIMEOUT_MS = 250
    const val STOP_WAIT_MS = 1_000L
    const val STOP_ATTEMPTS = 10
    const val STOP_RETRY_DELAY_MS = 50L
    val json = Json { ignoreUnknownKeys = true }
  }
}
