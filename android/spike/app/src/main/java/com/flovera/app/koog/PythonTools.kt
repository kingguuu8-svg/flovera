package com.flovera.app.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.flovera.app.workspace.WorkspaceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PythonRunTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
  private val networkEnabled: Boolean = false,
) : SimpleTool<PythonRunTool.Args>(
  argsType = typeToken<Args>(),
  name = "python_run",
  description = "Run blocking Python code inside the current workspace. The run is conversation-bound: no background threads, subprocesses, or daemon/server behavior. Use it for calculation, file generation, algorithm checks, and workspace-local scripting.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Python source code to execute. Use normal Python stdlib code; write durable outputs to workspace files.")
    val code: String,
    @property:LLMDescription("Relative workspace directory to use as cwd. Use '.' for the workspace root.")
    val cwd: String = ".",
    @property:LLMDescription("Timeout in milliseconds. Values are clamped to 1000..600000.")
    val timeoutMs: Int = 30_000,
    @property:LLMDescription("Maximum stdout/stderr characters returned per stream. Values are clamped to 1000..200000.")
    val maxOutputChars: Int = 20_000,
    @property:LLMDescription("Optional conversation-local Python session id. Same id preserves globals/imports between calls.")
    val sessionId: String = "",
    @property:LLMDescription("Whether to reset the named Python session before executing.")
    val resetSession: Boolean = false,
    @property:LLMDescription("Workspace permission scope: workspace_public, workspace_app_metadata, or workspace_internal.")
    val scope: String = "workspace_public",
    @property:LLMDescription("Whether to create an automatic workspace snapshot before running code.")
    val snapshotBeforeRun: Boolean = true,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      FloveraPythonRuntime(workspace, networkEnabled).run(args)
    }.getOrElse { it.message ?: it.toString() }
    recorder.record(
      name,
      "cwd=${args.cwd}, timeoutMs=${args.timeoutMs}, sessionId=${args.sessionId.ifBlank { "(none)" }}",
      result,
    )
    return result
  }
}

private class FloveraPythonRuntime(
  private val workspace: WorkspaceManager,
  private val networkEnabled: Boolean,
) {
  fun run(args: PythonRunTool.Args): String {
    val cwd = workspace.workspaceRuntimeDirectory(args.cwd)
    if (!cwd.exists()) return "Python cwd does not exist: ${args.cwd}"
    if (!cwd.isDirectory) return "Python cwd is not a directory: ${args.cwd}"
    if (args.snapshotBeforeRun) {
      workspace.createAutomaticSnapshot("python_run")
    }

    ensureStarted(workspace)
    val timeoutMs = args.timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    val maxOutputChars = args.maxOutputChars.coerceIn(MIN_OUTPUT_CHARS, MAX_OUTPUT_CHARS)
    val module = Python.getInstance().getModule("flovera_runtime")
    val jsonText = module.callAttr(
      "run_code",
      args.code,
      workspace.root.canonicalPath,
      cwd.canonicalPath,
      timeoutMs,
      maxOutputChars,
      args.sessionId,
      args.resetSession,
      args.scope,
      networkEnabled,
    ).toString()
    val result = json.decodeFromString<PythonRunResult>(jsonText)
    return formatResult(result, workspace.workspaceRelativePath(cwd), timeoutMs)
  }

  private fun formatResult(result: PythonRunResult, cwd: String, timeoutMs: Int): String {
    return buildString {
      append("Python status=${result.status} exitCode=${result.exitCode} elapsedMs=${result.elapsedMs} cwd=$cwd")
      if (result.sessionId.isNotBlank()) append(" session=${result.sessionId}")
      if (result.status == "timeout") append(" timeoutMs=$timeoutMs")
      appendLine()
      if (result.stdout.isBlank() && result.stderr.isBlank()) {
        appendLine("(no output)")
      }
      if (result.stdout.isNotBlank()) {
        appendLine("stdout:")
        appendLine(result.stdout.trimEnd())
        if (result.stdoutTruncated) appendLine("[stdout truncated]")
      }
      if (result.stderr.isNotBlank()) {
        appendLine("stderr:")
        appendLine(result.stderr.trimEnd())
        if (result.stderrTruncated) appendLine("[stderr truncated]")
      }
    }.trimEnd()
  }

  private companion object {
    const val MIN_TIMEOUT_MS = 1_000
    const val MAX_TIMEOUT_MS = 600_000
    const val MIN_OUTPUT_CHARS = 1_000
    const val MAX_OUTPUT_CHARS = 200_000
    val json = Json { ignoreUnknownKeys = true }

    fun ensureStarted(workspace: WorkspaceManager) {
      synchronized(FloveraPythonRuntime::class.java) {
        if (!Python.isStarted()) {
          Python.start(AndroidPlatform(workspace.applicationContext))
        }
      }
    }
  }
}

@Serializable
private data class PythonRunResult(
  val status: String,
  val exitCode: Int,
  val stdout: String = "",
  val stderr: String = "",
  val stdoutTruncated: Boolean = false,
  val stderrTruncated: Boolean = false,
  val elapsedMs: Int = 0,
  val sessionId: String = "",
)
