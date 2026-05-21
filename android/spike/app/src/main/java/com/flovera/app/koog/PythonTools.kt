package com.flovera.app.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.flovera.app.workspace.WorkspaceManager
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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

class ArtifactInspectTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
) : SimpleTool<ArtifactInspectTool.Args>(
  argsType = typeToken<Args>(),
  name = "artifact_inspect",
  description = "Inspect a workspace artifact as its actual file format. Use after generating DOCX, XLSX, PDF, HTML, JSON, image, or text files to verify that the artifact opens and contains the expected structure.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Relative workspace file path to inspect.")
    val path: String,
    @property:LLMDescription("Maximum text preview characters for text-bearing formats. Values are clamped to 500..20000.")
    val maxTextChars: Int = 4000,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      val file = workspace.exportableFile(args.path) ?: return@runCatching "Artifact does not exist or is not a file: ${args.path}"
      FloveraPythonRuntime.ensureStarted(workspace)
      val module = Python.getInstance().getModule("artifact_inspector")
      module.callAttr(
        "inspect_artifact",
        workspace.root.canonicalPath,
        workspace.workspaceRelativePath(file),
        args.maxTextChars.coerceIn(500, 20_000),
      ).toString()
    }.getOrElse { it.message ?: it.toString() }
    recorder.record(name, "path=${args.path}", result)
    return result
  }
}

class PythonPackageInstallTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
  private val networkEnabled: Boolean = false,
) : SimpleTool<PythonPackageInstallTool.Args>(
  argsType = typeToken<Args>(),
  name = "python_package_install",
  description = "Install or confirm a package from Flovera's pure-Python wheel catalog into the current workspace Python site-packages. Network downloads require the conversation Network toggle.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Catalog package name, for example openpyxl, XlsxWriter, pypdf, Markdown, or Jinja2.")
    val packageName: String,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      FloveraPythonRuntime.ensureStarted(workspace)
      val module = Python.getInstance().getModule("flovera_packages")
      module.callAttr(
        "install_catalog_package",
        workspace.root.canonicalPath,
        args.packageName,
        networkEnabled,
      ).toString()
    }.getOrElse { it.message ?: it.toString() }
    recorder.record(name, "package=${args.packageName}", result)
    return result
  }
}

class FloveraPythonRuntime(
  private val workspace: WorkspaceManager,
  private val networkEnabled: Boolean,
) {
  fun run(args: PythonRunTool.Args): String {
    val cwd = workspace.workspaceRuntimeDirectory(args.cwd)
    val result = runRaw(args)
    return formatResult(result, workspace.workspaceRelativePath(cwd), args.timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS))
  }

  fun runRaw(args: PythonRunTool.Args): PythonRunResult {
    val cwd = workspace.workspaceRuntimeDirectory(args.cwd)
    if (!cwd.exists()) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Python cwd does not exist: ${args.cwd}\n")
    }
    if (!cwd.isDirectory) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Python cwd is not a directory: ${args.cwd}\n")
    }
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
    return json.decodeFromString<PythonRunResult>(jsonText)
  }

  fun runScript(
    scriptPath: String,
    argv: List<String>,
    cwd: String,
    timeoutMs: Int,
    maxOutputChars: Int = 20_000,
    sessionId: String = "",
    scope: String = "workspace_public",
  ): PythonRunResult {
    val cwdFile = workspace.workspaceRuntimeDirectory(cwd)
    if (!cwdFile.exists()) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Python cwd does not exist: $cwd\n")
    }
    if (!cwdFile.isDirectory) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Python cwd is not a directory: $cwd\n")
    }
    val scriptFile = File(cwdFile, scriptPath).canonicalFile
    val root = workspace.root.canonicalFile
    if (scriptFile.path != root.path && !scriptFile.path.startsWith(root.path + File.separator)) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Python script escapes workspace: $scriptPath\n")
    }
    if (!scriptFile.isFile) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Python script does not exist: $scriptPath\n")
    }
    val code = """
      import runpy
      import sys

      _flovera_script = ${json.encodeToString(scriptFile.canonicalPath)}
      _flovera_argv = ${json.encodeToString(argv)}
      sys.argv = [_flovera_script] + _flovera_argv
      runpy.run_path(_flovera_script, run_name="__main__")
    """.trimIndent()
    return runRaw(
      PythonRunTool.Args(
        code = code,
        cwd = cwd,
        timeoutMs = timeoutMs,
        maxOutputChars = maxOutputChars,
        sessionId = sessionId,
        resetSession = true,
        scope = scope,
        snapshotBeforeRun = false,
      ),
    )
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

  companion object {
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
data class PythonRunResult(
  val status: String,
  val exitCode: Int,
  val stdout: String = "",
  val stderr: String = "",
  val stdoutTruncated: Boolean = false,
  val stderrTruncated: Boolean = false,
  val elapsedMs: Int = 0,
  val sessionId: String = "",
)
