package com.flovera.app.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.flovera.app.platform.AndroidPermissionCapabilities
import com.flovera.app.platform.AndroidSystemCommandApi
import com.flovera.app.workspace.WorkspaceManager
import dalvik.system.DexClassLoader
import groovy.lang.Binding
import groovy.lang.GroovyClassLoader
import groovy.lang.Script
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PrintWriter
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.tools.FileSystemCompiler
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.w3c.dom.Element
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

class WorkspaceCommandRunTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
  private val networkEnabled: Boolean = false,
  private val authorityMode: String = "safe",
  private val secretEnvironment: Map<String, String> = emptyMap(),
) : SimpleTool<WorkspaceCommandRunTool.Args>(
  argsType = typeToken<Args>(),
  name = "workspace_command_run",
  description = "Primary bounded command-style execution surface for Flovera-owned workspace runtimes. Use this for normal Python execution, generated scripts, project commands, local Git/JGit work, Android system APIs, cross-app accessibility operations, workspace automation scripts, Office OOXML inspection/editing, skill-scoped Flovera workflow commands described by activated skills, and groovy scripts when JVM access is useful. This is not Android shell access: supported command profiles are python/python3, groovy, git, android, flovera script, flovera office, and activated-skill Flovera workflows. The android profile provides permission-gated native APIs and an android ui surface for semantic inspection, screenshots, verified gestures, and persistent desktop-task recovery.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Command argv array. Supported now: python/python3, groovy, git, and android command profiles. Do not use shell syntax.")
    val argv: List<String>,
    @property:LLMDescription("Relative workspace directory to use as cwd. Use '.' for the workspace root.")
    val cwd: String = ".",
    @property:LLMDescription("Timeout in milliseconds. Values are clamped to 1000..600000.")
    val timeoutMs: Int = 30_000,
    @property:LLMDescription("Maximum stdout/stderr characters returned per stream. Values are clamped to 1000..200000.")
    val maxOutputChars: Int = 20_000,
    @property:LLMDescription("Workspace permission scope: workspace_public, workspace_app_metadata, or workspace_internal.")
    val scope: String = "workspace_public",
    @property:LLMDescription("Whether to create an automatic workspace snapshot before running the command.")
    val snapshotBeforeRun: Boolean = true,
    @property:LLMDescription("Optional environment variables for this bounded command. Values are restored after the run. Groovy supports FLOVERA_JVM_MAVEN_CONFIG=<workspace-relative-json> to use one Maven config file for this run instead of the default merged libs/maven.json and .flovera/jvm/maven.json files.")
    val environment: Map<String, String> = emptyMap(),
  )

  override suspend fun execute(args: Args): String {
    val runtime = FloveraPythonRuntime(workspace, networkEnabled, secretEnvironment)
    val runId = UUID.randomUUID().toString()
    val cancellationHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
      if (cause is CancellationException) runtime.cancel(runId)
    }
    val result = try {
      coroutineContext.ensureActive()
      runInterruptible(Dispatchers.IO) {
        try {
          WorkspaceCommandGateway(
            workspace = workspace,
            networkEnabled = networkEnabled,
            authorityMode = authorityMode,
            secretEnvironment = secretEnvironment,
          ).run(args, runId)
        } catch (error: CancellationException) {
          throw error
        } catch (error: Throwable) {
          error.message ?: error.toString()
        }
      }
    } finally {
      cancellationHandle?.dispose()
    }
    coroutineContext.ensureActive()
    recorder.record(
      name,
      "argv=${args.argv.commandSummary()}, cwd=${args.cwd}, timeoutMs=${args.timeoutMs}",
      result,
    )
    return result
  }
}

class WorkspaceCommandGateway(
  private val workspace: WorkspaceManager,
  private val networkEnabled: Boolean,
  private val authorityMode: String,
  private val secretEnvironment: Map<String, String> = emptyMap(),
) {
  private val adapters: List<WorkspaceCommandAdapter> = listOf(
    PythonCommandAdapter(workspace, networkEnabled, secretEnvironment),
    GroovyCommandAdapter(workspace, networkEnabled, secretEnvironment),
    GitCommandAdapter(workspace),
    AndroidCommandAdapter(workspace, networkEnabled),
    FloveraCommandAdapter(workspace, networkEnabled, authorityMode, secretEnvironment),
  )

  fun run(args: WorkspaceCommandRunTool.Args, runId: String = UUID.randomUUID().toString()): String {
    throwIfInterrupted()
    val argv = args.argv.map { it.trim() }.filter { it.isNotEmpty() }
    if (argv.isEmpty()) {
      val risk = WorkspaceCommandRisk.unsupported("missing")
      return denied(
        message = "Missing command argv. Supported now: python/python3, groovy, git, android, flovera script/office, and skill-scoped Flovera workflow command profiles.",
        args = args,
        argv = emptyList(),
        risk = risk,
      )
    }

    val command = argv.first().substringAfterLast('/').substringAfterLast('\\').lowercase()
    val adapter = adapters.firstOrNull { command in it.commandNames }
    if (adapter == null) {
      val risk = WorkspaceCommandRisk.unsupported(command)
      return denied(
        message = "Unsupported workspace command: ${argv.first()}. Supported now: python, python3, experimental groovy, git, android, flovera script/office, and skill-scoped Flovera workflow command profiles. Android shell, npm, daemons, and shell operators are not enabled through this tool.",
        args = args,
        argv = argv,
        risk = risk,
      )
    }

    val risk = adapter.classify(argv)
    val authorization = authorize(risk)
    if (!authorization.allowed) {
      return denied(authorization.reason, args, argv, risk)
    }

    val result = adapter.execute(argv, args, runId)
    throwIfInterrupted()
    val cwdFile = runCatching { workspace.workspaceRuntimeDirectory(args.cwd) }.getOrNull()
    val cwd = cwdFile?.let { workspace.workspaceRelativePath(it) } ?: args.cwd
    appendAudit(argv, args, risk, authorization, result)
    return formatResult(
      command = argv.commandSummary(),
      cwd = cwd,
      timeoutMs = args.timeoutMs.coerceIn(FloveraPythonRuntime.MIN_TIMEOUT_MS, FloveraPythonRuntime.MAX_TIMEOUT_MS),
      result = result,
      risk = risk,
      authorization = authorization,
    )
  }

  private fun throwIfInterrupted() {
    if (Thread.currentThread().isInterrupted) {
      throw CancellationException("Workspace command cancelled")
    }
  }

  private fun authorize(risk: WorkspaceCommandRisk): WorkspaceCommandAuthorization {
    val normalizedAuthority = when (authorityMode) {
      "assisted" -> "assisted"
      "full" -> "full"
      else -> "safe"
    }
    if (risk.category == "unsupported") {
      return WorkspaceCommandAuthorization(false, normalizedAuthority, "unsupported command")
    }
    if (risk.permissions.any { it == "android.shell" || it == "daemon" }) {
      return WorkspaceCommandAuthorization(false, normalizedAuthority, "permission denied: ${risk.permissions.joinToString(",")}")
    }
    if ("jvm.dynamic" in risk.permissions && normalizedAuthority != "full") {
      return WorkspaceCommandAuthorization(false, normalizedAuthority, "jvm.dynamic requires Full Authority")
    }
    return WorkspaceCommandAuthorization(true, normalizedAuthority, "allowed")
  }

  private fun denied(
    message: String,
    args: WorkspaceCommandRunTool.Args,
    argv: List<String>,
    risk: WorkspaceCommandRisk,
  ): String {
    val timeoutMs = args.timeoutMs.coerceIn(FloveraPythonRuntime.MIN_TIMEOUT_MS, FloveraPythonRuntime.MAX_TIMEOUT_MS)
    val authorization = authorize(risk)
    val result = PythonRunResult(status = "unsupported", exitCode = 127, stderr = "$message\n")
    appendAudit(argv, args, risk, authorization, result)
    return formatResult(
      command = argv.commandSummary().ifBlank { "(empty)" },
      cwd = args.cwd,
      timeoutMs = timeoutMs,
      result = result,
      risk = risk,
      authorization = authorization,
    )
  }

  private fun appendAudit(
    argv: List<String>,
    args: WorkspaceCommandRunTool.Args,
    risk: WorkspaceCommandRisk,
    authorization: WorkspaceCommandAuthorization,
    result: PythonRunResult,
  ) {
    runCatching {
      workspace.appendWorkspaceCommandAudit(
        command = argv.commandSummary(),
        cwd = args.cwd,
        authorityMode = authorization.authorityMode,
        riskCategory = risk.category,
        permissions = risk.permissions,
        allowed = authorization.allowed,
        reason = authorization.reason,
        status = result.status,
        exitCode = result.exitCode,
        elapsedMs = result.elapsedMs,
      )
    }
  }

  private fun formatResult(
    command: String,
    cwd: String,
    timeoutMs: Int,
    result: PythonRunResult,
    risk: WorkspaceCommandRisk,
    authorization: WorkspaceCommandAuthorization,
  ): String {
    return buildString {
      append("Workspace command status=${result.status} exitCode=${result.exitCode} elapsedMs=${result.elapsedMs} cwd=$cwd command=$command")
      if (result.status == "timeout") append(" timeoutMs=$timeoutMs")
      appendLine()
      appendLine("authorization=${if (authorization.allowed) "allowed" else "denied"} authority=${authorization.authorityMode} risk=${risk.category} permissions=${risk.permissions.joinToString("|").ifBlank { "none" }}")
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
}

private interface WorkspaceCommandAdapter {
  val commandNames: Set<String>
  fun classify(argv: List<String>): WorkspaceCommandRisk
  fun execute(argv: List<String>, args: WorkspaceCommandRunTool.Args, runId: String): PythonRunResult
}

private class PythonCommandAdapter(
  private val workspace: WorkspaceManager,
  private val networkEnabled: Boolean,
  private val secretEnvironment: Map<String, String>,
) : WorkspaceCommandAdapter {
  override val commandNames: Set<String> = setOf("python", "python3")

  override fun classify(argv: List<String>): WorkspaceCommandRisk {
    if (argv.size < 2) {
      return WorkspaceCommandRisk("python.invalid", listOf("workspace.read"))
    }
    return when {
      argv[1] == "-c" -> WorkspaceCommandRisk("python.dynamic_code", listOf("workspace.read", "workspace.write", "dynamic_code"))
      argv[1].startsWith("-") -> WorkspaceCommandRisk("python.unsupported_option", listOf("workspace.read"))
      else -> WorkspaceCommandRisk("python.workspace_script", listOf("workspace.read", "workspace.write", "workspace_script"))
    }
  }

  override fun execute(argv: List<String>, args: WorkspaceCommandRunTool.Args, runId: String): PythonRunResult {
    if (argv.size < 2) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Python command requires a script path or -c code, for example: python tools/check.py\n")
    }

    val python = FloveraPythonRuntime(workspace, networkEnabled, secretEnvironment)
    val timeoutMs = args.timeoutMs.coerceIn(FloveraPythonRuntime.MIN_TIMEOUT_MS, FloveraPythonRuntime.MAX_TIMEOUT_MS)
    val maxOutputChars = args.maxOutputChars.coerceIn(FloveraPythonRuntime.MIN_OUTPUT_CHARS, FloveraPythonRuntime.MAX_OUTPUT_CHARS)
    val cwdFile = workspace.workspaceRuntimeDirectory(args.cwd)
    if (!cwdFile.exists()) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Command cwd does not exist: ${args.cwd}\n")
    }
    if (!cwdFile.isDirectory) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Command cwd is not a directory: ${args.cwd}\n")
    }

    return if (argv[1] == "-c") {
      if (argv.size < 3) {
        PythonRunResult(status = "error", exitCode = 1, stderr = "python -c requires code.\n")
      } else {
        python.runRaw(
          PythonRunTool.Args(
            code = pythonCommandCode(argv[2], argv.drop(3)),
            cwd = args.cwd,
            timeoutMs = timeoutMs,
            maxOutputChars = maxOutputChars,
            sessionId = "",
            resetSession = true,
            scope = args.scope,
            snapshotBeforeRun = args.snapshotBeforeRun,
            environment = args.environment,
          ),
          runId,
        )
      }
    } else if (argv[1].startsWith("-")) {
      PythonRunResult(
        status = "error",
        exitCode = 1,
        stderr = "Unsupported python option: ${argv[1]}. Supported now: python script.py [args...] and python -c code [args...]\n",
      )
    } else {
      python.runScript(
        scriptPath = argv[1],
        argv = argv.drop(2),
        cwd = args.cwd,
        timeoutMs = timeoutMs,
        maxOutputChars = maxOutputChars,
        sessionId = "",
        scope = args.scope,
        snapshotBeforeRun = args.snapshotBeforeRun,
        environment = args.environment,
        runId = runId,
      )
    }
  }

  private fun pythonCommandCode(code: String, scriptArgs: List<String>): String {
    val encodedArgs = FloveraPythonRuntime.json.encodeToString(scriptArgs)
    val normalizedCode = code.trimStart('\n', '\r')
    return "import sys as _flovera_sys\n" +
      "_flovera_sys.argv = [\"-c\"] + $encodedArgs\n" +
      "del _flovera_sys\n" +
      normalizedCode
  }
}

private class GitCommandAdapter(
  private val workspace: WorkspaceManager,
) : WorkspaceCommandAdapter {
  override val commandNames: Set<String> = setOf("git")

  override fun classify(argv: List<String>): WorkspaceCommandRisk {
    val subcommand = argv.getOrNull(1)?.lowercase().orEmpty()
    return when (subcommand) {
      "status", "diff", "log", "show", "branch" -> WorkspaceCommandRisk("git.read", listOf("workspace.read", "git.read"))
      "init", "add", "commit" -> WorkspaceCommandRisk("git.write", listOf("workspace.read", "workspace.write", "git.write"))
      "" -> WorkspaceCommandRisk("git.invalid", listOf("workspace.read", "git.read"))
      else -> WorkspaceCommandRisk("git.unsupported", listOf("workspace.read", "git:$subcommand"))
    }
  }

  override fun execute(argv: List<String>, args: WorkspaceCommandRunTool.Args, runId: String): PythonRunResult {
    val startedAt = System.currentTimeMillis()
    val maxOutputChars = args.maxOutputChars.coerceIn(FloveraPythonRuntime.MIN_OUTPUT_CHARS, FloveraPythonRuntime.MAX_OUTPUT_CHARS)
    val subcommand = argv.getOrNull(1)?.lowercase().orEmpty()
    val cwd = runCatching { workspace.workspaceRuntimeDirectory(args.cwd) }.getOrElse {
      return gitError(startedAt, "Command cwd is invalid: ${args.cwd}\n")
    }
    if (!cwd.exists()) return gitError(startedAt, "Command cwd does not exist: ${args.cwd}\n")
    if (!cwd.isDirectory) return gitError(startedAt, "Command cwd is not a directory: ${args.cwd}\n")
    return runCatching {
      val output = when (subcommand) {
        "init" -> initRepository()
        "status" -> withRepository { repo -> status(repo) }
        "diff" -> withRepository { repo -> diff(repo, argv.drop(2), maxOutputChars) }
        "log" -> withRepository { repo -> log(repo, argv.drop(2), maxOutputChars) }
        "show" -> withRepository { repo -> show(repo, argv.drop(2), maxOutputChars) }
        "branch" -> withRepository { repo -> branch(repo) }
        "add" -> withRepository { repo -> add(repo, argv.drop(2)) }
        "commit" -> withRepository { repo -> commit(repo, argv.drop(2)) }
        "" -> "git command requires a subcommand, for example: git status\n"
        else -> "Unsupported git subcommand: $subcommand. Supported: init, status, diff, log, show, branch, add, commit.\n"
      }
      val bounded = boundedText(output, maxOutputChars)
      PythonRunResult(
        status = if (subcommand.isBlank() || subcommand !in SUPPORTED_GIT_SUBCOMMANDS) "error" else "ok",
        exitCode = if (subcommand.isBlank() || subcommand !in SUPPORTED_GIT_SUBCOMMANDS) 1 else 0,
        stdout = bounded.text,
        stdoutTruncated = bounded.truncated,
        elapsedMs = elapsedSince(startedAt),
      )
    }.getOrElse { throwable ->
      gitError(startedAt, "Git command failed: ${throwable.message ?: throwable::class.java.name}\n")
    }
  }

  private fun initRepository(): String {
    Git.init().setDirectory(workspace.root).call().use { git ->
      ensureGitExcludes(git.repository)
      return "initialized=true\nworkTree=${workspace.workspaceRelativePath(git.repository.workTree)}\ngitDir=${git.repository.directory.name}\n"
    }
  }

  private fun <T> withRepository(block: (Repository) -> T): T {
    val builder = FileRepositoryBuilder()
      .readEnvironment()
      .findGitDir(workspace.root)
    val gitDir = builder.gitDir ?: error("Workspace is not a Git repository. Run `git init` first.")
    val root = workspace.root.canonicalFile
    val canonicalGitDir = gitDir.canonicalFile
    if (canonicalGitDir.path != root.path && !canonicalGitDir.path.startsWith(root.path + File.separator)) {
      error("Git directory escapes workspace.")
    }
    return builder.build().use { repo ->
      ensureGitExcludes(repo)
      block(repo)
    }
  }

  private fun ensureGitExcludes(repo: Repository) {
    val infoDir = File(repo.directory, "info")
    val excludeFile = File(infoDir, "exclude")
    infoDir.mkdirs()
    val existing = if (excludeFile.isFile) excludeFile.readText() else ""
    val additions = FLOVERA_GIT_EXCLUDES.filterNot { pattern ->
      existing.lineSequence().any { it.trim() == pattern }
    }
    if (additions.isEmpty()) return
    excludeFile.appendText(
      buildString {
        if (existing.isNotBlank() && !existing.endsWith("\n")) appendLine()
        additions.forEach { appendLine(it) }
      },
    )
  }

  private fun status(repo: Repository): String {
    Git(repo).use { git ->
      val status = git.status().call()
      return buildString {
        appendLine("branch=${repo.branch.orEmpty()}")
        appendLine("clean=${status.isClean}")
        appendGitSet("added", status.added)
        appendGitSet("changed", status.changed)
        appendGitSet("modified", status.modified)
        appendGitSet("missing", status.missing)
        appendGitSet("removed", status.removed)
        appendGitSet("untracked", status.untracked)
        appendGitSet("conflicting", status.conflicting)
      }
    }
  }

  private fun diff(repo: Repository, flags: List<String>, maxOutputChars: Int): String {
    return runCatching {
      Git(repo).use { git ->
        val stdout = ByteArrayOutputStream()
        DiffFormatter(stdout).use { formatter ->
          formatter.setRepository(repo)
          formatter.setDetectRenames(true)
          val cached = "--cached" in flags || "--staged" in flags
          val diffs = if (cached) git.diff().setCached(true).call() else git.diff().call()
          diffs.forEach { formatter.format(it) }
        }
        val text = stdout.toString(StandardCharsets.UTF_8.name())
        text.ifBlank { "(no diff)\n" }.take(maxOutputChars + 1)
      }
    }.getOrElse { throwable ->
      fallbackWorkingTreeDiff(repo, throwable, maxOutputChars)
    }
  }

  private fun fallbackWorkingTreeDiff(repo: Repository, throwable: Throwable, maxOutputChars: Int): String {
    return buildString {
      appendLine("diffFallback=working_tree_full_file")
      appendLine("fallbackReason=${throwable.message ?: throwable::class.java.name}")
      workspace.root.walkTopDown()
        .onEnter { directory ->
          val relative = workspace.workspaceRelativePath(directory).replace('\\', '/')
          relative.isBlank() || relative !in setOf(".git") && !isFloveraRuntimeGitPath(relative)
        }
        .filter { it.isFile }
        .map { workspace.workspaceRelativePath(it).replace('\\', '/') }
        .filter { it.isNotBlank() && !isFloveraRuntimeGitPath(it) && !it.startsWith(".git/") }
        .sorted()
        .forEach { path ->
          if (length > maxOutputChars) return@forEach
          appendLine("diff --flovera-fallback a/$path b/$path")
          appendLine("--- a/$path")
          appendLine("+++ b/$path")
          val file = File(repo.workTree, path)
          file.readLines().forEach { line ->
            if (length <= maxOutputChars) appendLine("+$line")
          }
        }
    }.take(maxOutputChars + 1)
  }

  private fun log(repo: Repository, args: List<String>, maxOutputChars: Int): String {
    val limit = args.firstOrNull { it.startsWith("-n") }
      ?.removePrefix("-n")
      ?.toIntOrNull()
      ?.coerceIn(1, 50)
      ?: 10
    Git(repo).use { git ->
      val commits = runCatching { git.log().setMaxCount(limit).call().toList() }
        .getOrElse { emptyList() }
      if (commits.isEmpty()) return "(no commits)\n"
      return commits.joinToString("\n") { commit ->
        "${commit.name.take(12)} ${commit.authorIdent.`when`.time} ${commit.shortMessage}"
      }.plus("\n").take(maxOutputChars + 1)
    }
  }

  private fun show(repo: Repository, args: List<String>, maxOutputChars: Int): String {
    val rev = args.firstOrNull { !it.startsWith("-") } ?: Constants.HEAD
    val objectId = repo.resolve(rev) ?: error("Cannot resolve revision: $rev")
    RevWalk(repo).use { walk ->
      val commit = walk.parseCommit(objectId)
      val parentTree = commit.parents.firstOrNull()?.let { parent -> walk.parseCommit(parent.id).tree }
      val output = ByteArrayOutputStream()
      output.writer(StandardCharsets.UTF_8).use { writer ->
        writer.appendLine("commit ${commit.name}")
        writer.appendLine("author ${commit.authorIdent.name} <${commit.authorIdent.emailAddress}>")
        writer.appendLine("date ${commit.authorIdent.`when`}")
        writer.appendLine()
        writer.appendLine(commit.fullMessage.trimEnd())
        writer.appendLine()
      }
      DiffFormatter(output).use { formatter ->
        formatter.setRepository(repo)
        formatter.setDetectRenames(true)
        val newTree = CanonicalTreeParser().also { parser ->
          repo.newObjectReader().use { reader -> parser.reset(reader, commit.tree.id) }
        }
        val oldTree = parentTree?.let { tree ->
          CanonicalTreeParser().also { parser ->
            repo.newObjectReader().use { reader -> parser.reset(reader, tree.id) }
          }
        } ?: EmptyTreeIterator()
        formatter.scan(oldTree, newTree).forEach { formatter.format(it) }
      }
      return output.toString(StandardCharsets.UTF_8.name()).take(maxOutputChars + 1)
    }
  }

  private fun branch(repo: Repository): String {
    Git(repo).use { git ->
      val branches = git.branchList().call()
      return buildString {
        appendLine("current=${repo.branch.orEmpty()}")
        branches.forEach { ref ->
          appendLine(ref.name.removePrefix("refs/heads/"))
        }
      }
    }
  }

  private fun add(repo: Repository, patterns: List<String>): String {
    val normalized = expandGitPatterns(normalizeGitPatterns(patterns.ifEmpty { listOf(".") }))
    writeIndexEntries(repo, normalized)
    return "added=${normalized.joinToString(",")}\n"
  }

  private fun writeIndexEntries(repo: Repository, paths: List<String>) {
    val selected = paths
      .filter { path -> File(repo.workTree, path).isFile }
      .distinct()
      .sorted()
    if (selected.isEmpty()) return
    val selectedSet = selected.toSet()
    val cache = DirCache.lock(repo, null)
    try {
      val entries = mutableListOf<DirCacheEntry>()
      repeat(cache.entryCount) { index ->
        val existing = cache.getEntry(index)
        if (existing.pathString !in selectedSet) {
          entries += DirCacheEntry(existing)
        }
      }
      repo.newObjectInserter().use { inserter ->
        selected.forEach { path ->
          val file = File(repo.workTree, path)
          val bytes = file.readBytes()
          val objectId = ByteArrayInputStream(bytes).use { input ->
            inserter.insert(Constants.OBJ_BLOB, bytes.size.toLong(), input)
          }
          val entry = DirCacheEntry(path).apply {
            fileMode = FileMode.REGULAR_FILE
            setObjectId(objectId)
            setLength(bytes.size.toLong())
            setLastModified(Instant.ofEpochMilli(file.lastModified()))
          }
          entries += entry
        }
        inserter.flush()
        selected.forEach { path ->
          val entry = entries.firstOrNull { it.pathString == path } ?: return@forEach
          repo.open(entry.objectId).openStream().close()
        }
      }
      val builder = cache.builder()
      entries.sortedBy { it.pathString }.forEach { builder.add(it) }
      builder.commit()
    } finally {
      cache.unlock()
    }
  }

  private fun commit(repo: Repository, args: List<String>): String {
    val message = gitCommitMessage(args)
    if (message.isBlank()) return "git commit requires -m <message>.\n"
    Git(repo).use { git ->
      val commit = git.commit()
        .setMessage(message)
        .setAuthor("Flovera", "flovera@local")
        .setCommitter("Flovera", "flovera@local")
        .call()
      return "commit=${commit.name}\nshort=${commit.name.take(12)}\nmessage=${commit.shortMessage}\n"
    }
  }

  private fun normalizeGitPatterns(patterns: List<String>): List<String> {
    return patterns
      .map { it.replace('\\', '/').trim().trimStart('/') }
      .filter { it.isNotBlank() }
      .map {
        require(!it.split('/').any { segment -> segment == ".." }) { "Git path escapes workspace: $it" }
        it
      }
      .distinct()
  }

  private fun expandGitPatterns(patterns: List<String>): List<String> {
    if (patterns.none { it == "." }) return patterns.filterNot(::isFloveraRuntimeGitPath)
    val explicit = patterns.filterNot { it == "." }
    val expanded = workspace.root.walkTopDown()
      .onEnter { directory ->
        val relative = workspace.workspaceRelativePath(directory).replace('\\', '/')
        relative.isBlank() || relative !in setOf(".git") && !isFloveraRuntimeGitPath(relative)
      }
      .filter { it.isFile }
      .map { workspace.workspaceRelativePath(it).replace('\\', '/') }
      .filter { it.isNotBlank() && !isFloveraRuntimeGitPath(it) && !it.startsWith(".git/") }
      .toList()
    return (explicit + expanded).distinct()
  }

  private fun isFloveraRuntimeGitPath(path: String): Boolean {
    val normalized = path.replace('\\', '/').trimStart('/')
    return normalized == ".flovera/logs" ||
      normalized == ".flovera/runtime" ||
      normalized == ".flovera/jobs" ||
      normalized.startsWith(".flovera/logs/") ||
      normalized.startsWith(".flovera/runtime/") ||
      normalized.startsWith(".flovera/jobs/")
  }

  private fun gitCommitMessage(args: List<String>): String {
    val index = args.indexOf("-m").takeIf { it >= 0 } ?: args.indexOf("--message").takeIf { it >= 0 } ?: -1
    if (index < 0) return ""
    return args.getOrNull(index + 1)?.trim().orEmpty()
  }

  private fun StringBuilder.appendGitSet(name: String, values: Set<String>) {
    if (values.isNotEmpty()) appendLine("$name=${values.sorted().joinToString(",")}")
  }

  private fun gitError(startedAt: Long, message: String): PythonRunResult {
    return PythonRunResult(status = "error", exitCode = 1, stderr = message, elapsedMs = elapsedSince(startedAt))
  }

  private fun elapsedSince(startedAt: Long): Int {
    return (System.currentTimeMillis() - startedAt).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  }

  private companion object {
    val SUPPORTED_GIT_SUBCOMMANDS = setOf("init", "status", "diff", "log", "show", "branch", "add", "commit")
    val FLOVERA_GIT_EXCLUDES = listOf(
      ".flovera/logs/",
      ".flovera/runtime/",
      ".flovera/jobs/",
    )
  }
}

private class FloveraCommandAdapter(
  private val workspace: WorkspaceManager,
  private val networkEnabled: Boolean,
  private val authorityMode: String,
  private val secretEnvironment: Map<String, String>,
) : WorkspaceCommandAdapter {
  override val commandNames: Set<String> = setOf("flovera")

  override fun classify(argv: List<String>): WorkspaceCommandRisk {
    return when (argv.getOrNull(1)?.lowercase()) {
      "script" -> WorkspaceCommandRisk("flovera.script", listOf("workspace.read", "workspace.write", "automation.script"))
      "webview" -> WorkspaceCommandRisk("flovera.webview.audit", listOf("workspace.read", "webview.audit"))
      "app" -> WorkspaceCommandRisk("flovera.app.verify", listOf("workspace.read", "artifact.registration"))
      "skill" -> WorkspaceCommandRisk("flovera.skill.check", listOf("workspace.read", "skill.registry"))
      "office" -> {
        val action = argv.getOrNull(2)?.lowercase().orEmpty()
        val permissions = if (action == "replace") listOf("workspace.read", "workspace.write", "office.ooxml") else listOf("workspace.read", "office.ooxml")
        WorkspaceCommandRisk("flovera.office", permissions)
      }
      else -> WorkspaceCommandRisk.unsupported("flovera")
    }
  }

  override fun execute(argv: List<String>, args: WorkspaceCommandRunTool.Args, runId: String): PythonRunResult {
    val startedAt = System.currentTimeMillis()
    val profile = argv.getOrNull(1)?.lowercase().orEmpty()
    return runCatching {
      when (profile) {
        "script" -> scriptCommand(argv, args, startedAt, runId)
        "webview" -> webviewCommand(argv, startedAt)
        "app" -> appCommand(argv, startedAt)
        "skill" -> skillCommand(argv, startedAt)
        "office" -> officeCommand(argv, startedAt)
        else -> PythonRunResult(
          status = "error",
          exitCode = 1,
          stderr = "Supported: flovera script list|run, flovera office inspect|validate|text|replace, plus skill-scoped Flovera workflow commands documented by activated skills.\n",
          elapsedMs = elapsed(startedAt),
        )
      }
    }.getOrElse { error ->
      if (error is CancellationException) throw error
      PythonRunResult(
        status = "error",
        exitCode = 1,
        stderr = workspaceCommandThrowableSummary(error),
        elapsedMs = elapsed(startedAt),
      )
    }
  }

  private fun workspaceCommandThrowableSummary(error: Throwable): String {
    val lines = mutableListOf<String>()
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 6) {
      lines += "${current::class.java.name}: ${current.message.orEmpty()}"
      current = current.cause
      depth += 1
    }
    return lines.joinToString("\n") + "\n"
  }

  private fun scriptCommand(argv: List<String>, args: WorkspaceCommandRunTool.Args, startedAt: Long, runId: String): PythonRunResult {
    return when (argv.getOrNull(2)?.lowercase().orEmpty().ifBlank { "list" }) {
      "list" -> scriptList(startedAt)
      "run" -> scriptRun(argv, args, startedAt, runId)
      else -> PythonRunResult(
        status = "error",
        exitCode = 1,
        stderr = "Supported: flovera script list | flovera script run <name> [--param key=value]\n",
        elapsedMs = elapsed(startedAt),
      )
    }
  }

  private fun scriptList(startedAt: Long): PythonRunResult {
    val directory = File(workspace.root, SCRIPT_DIRECTORY)
    val scripts = JSONArray()
    if (directory.isDirectory) {
      directory.walkTopDown()
        .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
        .forEach { file ->
          val json = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
          scripts.put(
            JSONObject()
              .put("name", json?.optString("name").orEmpty().ifBlank { file.nameWithoutExtension })
              .put("description", json?.optString("description").orEmpty())
              .put("path", workspace.workspaceRelativePath(file))
              .put("stepCount", json?.optJSONArray("steps")?.length() ?: 0),
          )
        }
    }
    return PythonRunResult(
      status = "ok",
      exitCode = 0,
      stdout = JSONObject()
        .put("directory", SCRIPT_DIRECTORY)
        .put("count", scripts.length())
        .put("scripts", scripts)
        .toString(2) + "\n",
      elapsedMs = elapsed(startedAt),
    )
  }

  private fun scriptRun(argv: List<String>, args: WorkspaceCommandRunTool.Args, startedAt: Long, runId: String): PythonRunResult {
    val name = argv.getOrNull(3).orEmpty().ifBlank { optionValue(argv, "name") }
    val scriptFile = resolveScriptFile(name)
    val script = JSONObject(scriptFile.readText(Charsets.UTF_8))
    val scriptName = script.optString("name").ifBlank { scriptFile.nameWithoutExtension }
    val params = optionValues(argv, "param").associate { value ->
      val key = value.substringBefore('=', "").trim()
      require(key.isNotBlank() && value.contains('=')) { "--param must be key=value: $value" }
      key to value.substringAfter('=')
    }
    val steps = script.optJSONArray("steps") ?: error("script steps must be an array")
    val outputs = JSONArray()
    for (index in 0 until steps.length()) {
      val step = steps.optJSONObject(index) ?: error("script step $index must be an object")
      val stepArgv = stepArgv(step, params, scriptName, index)
      require(stepArgv.firstOrNull()?.lowercase() != "flovera") { "nested flovera script steps are not supported yet" }
      val stepArgs = args.copy(
        argv = stepArgv,
        cwd = substituteParams(step.optString("cwd", args.cwd), params),
        timeoutMs = step.optInt("timeoutMs", args.timeoutMs).coerceIn(FloveraPythonRuntime.MIN_TIMEOUT_MS, FloveraPythonRuntime.MAX_TIMEOUT_MS),
        snapshotBeforeRun = step.optBoolean("snapshotBeforeRun", false),
      )
      val output = WorkspaceCommandGateway(
        workspace = workspace,
        networkEnabled = networkEnabled,
        authorityMode = authorityMode,
        secretEnvironment = secretEnvironment,
      ).run(stepArgs, runId)
      val ok = output.contains("Workspace command status=ok")
      outputs.put(
        JSONObject()
          .put("index", index)
          .put("name", step.optString("name").ifBlank { "step-$index" })
          .put("argv", JSONArray(stepArgv))
          .put("ok", ok)
          .put("output", output.take(20_000)),
      )
      require(ok) { "script step $index failed" }
    }
    return PythonRunResult(
      status = "ok",
      exitCode = 0,
      stdout = JSONObject()
        .put("script", scriptName)
        .put("path", workspace.workspaceRelativePath(scriptFile))
        .put("status", "completed")
        .put("stepCount", steps.length())
        .put("outputs", outputs)
        .toString(2) + "\n",
      elapsedMs = elapsed(startedAt),
    )
  }

  private fun resolveScriptFile(name: String): File {
    val normalized = name.trim().trim('/')
    require(normalized.isNotBlank()) { "script name is required" }
    val relative = when {
      normalized.endsWith(".json") && normalized.contains("/") -> normalized
      normalized.endsWith(".json") -> "$SCRIPT_DIRECTORY/$normalized"
      normalized.contains("/") -> "$normalized.json"
      else -> "$SCRIPT_DIRECTORY/$normalized.json"
    }
    val file = File(workspace.root, relative).canonicalFile
    val root = workspace.root.canonicalFile
    require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "script path escapes workspace: $name" }
    require(file.isFile) { "script was not found: $relative" }
    return file
  }

  private fun stepArgv(step: JSONObject, params: Map<String, String>, scriptName: String, index: Int): List<String> {
    val raw = step.optJSONArray("argv") ?: step.optJSONArray("cmd") ?: error("script step $index requires argv or cmd")
    val values = mutableListOf<String>()
    for (argIndex in 0 until raw.length()) {
      values += substituteParams(raw.optString(argIndex), params)
    }
    val action = values.getOrNull(2)?.lowercase().orEmpty()
    val needsActionId = values.firstOrNull()?.lowercase() == "android" &&
      values.getOrNull(1)?.lowercase() == "ui" &&
      action in setOf("click", "set-text", "input", "tap", "swipe", "global") &&
      "--action-id" !in values
    return if (needsActionId) values + listOf("--action-id", "script-${scriptName.filter { it.isLetterOrDigit() }.take(24)}-$index-$action") else values
  }

  private fun substituteParams(value: String, params: Map<String, String>): String {
    var result = value
    params.forEach { (key, replacement) ->
      result = result.replace("{{$key}}", replacement).replace("{{ $key }}", replacement)
    }
    require(!Regex("\\{\\{\\s*[^}]+\\s*\\}\\}").containsMatchIn(result)) { "unresolved script parameter in: $value" }
    return result
  }

  private fun optionValue(argv: List<String>, name: String): String = optionValues(argv, name).firstOrNull().orEmpty()

  private fun optionValues(argv: List<String>, name: String): List<String> {
    val result = mutableListOf<String>()
    argv.forEachIndexed { index, value ->
      if (value == "--$name") argv.getOrNull(index + 1)?.let(result::add)
    }
    return result
  }

  private fun webviewCommand(argv: List<String>, startedAt: Long): PythonRunResult {
    val action = argv.getOrNull(2)?.lowercase().orEmpty().ifBlank { "audit-mobile-layout" }
    if (action != "audit-mobile-layout") {
      return PythonRunResult(
        status = "error",
        exitCode = 1,
        stderr = "Supported: flovera webview audit-mobile-layout <html-or-directory-path>\n",
        elapsedMs = elapsed(startedAt),
      )
    }
    val rawPath = argv.getOrNull(3).orEmpty().ifBlank { optionValue(argv, "path").ifBlank { "." } }
    val target = resolveWorkspacePath(rawPath)
    val findings = JSONArray()
    val checkedFiles = JSONArray()
    val files = webviewAuditFiles(target)
    files.forEach { file ->
      checkedFiles.put(workspace.workspaceRelativePath(file))
      auditWebviewFile(file, findings)
    }
    if (files.isEmpty()) {
      findings.put(
        workflowFinding(
          level = "error",
          code = "no_html_or_css_files",
          path = workspace.workspaceRelativePath(target),
          message = "No HTML or CSS files were found for mobile WebView layout audit.",
          suggestion = "Pass the HTML preview file or the app web directory.",
        ),
      )
    }
    val errors = findings.countLevel("error")
    val warnings = findings.countLevel("warning")
    val output = JSONObject()
      .put("ok", errors == 0)
      .put("workflow", "flovera webview audit-mobile-layout")
      .put("path", workspace.workspaceRelativePath(target))
      .put("checkedFiles", checkedFiles)
      .put("summary", JSONObject().put("errors", errors).put("warnings", warnings))
      .put("findings", findings)
    return PythonRunResult(
      status = if (errors == 0) "ok" else "error",
      exitCode = if (errors == 0) 0 else 2,
      stdout = output.toString(2) + "\n",
      elapsedMs = elapsed(startedAt),
    )
  }

  private fun appCommand(argv: List<String>, startedAt: Long): PythonRunResult {
    val action = argv.getOrNull(2)?.lowercase().orEmpty().ifBlank { "verify-registration" }
    if (action != "verify-registration") {
      return PythonRunResult(
        status = "error",
        exitCode = 1,
        stderr = "Supported: flovera app verify-registration <flovera.app.json>\n",
        elapsedMs = elapsed(startedAt),
      )
    }
    val manifestPath = argv.getOrNull(3).orEmpty().ifBlank { optionValue(argv, "manifest") }.trim().replace('\\', '/')
    val includeReference = "--include-reference" in argv
    val artifacts = workspace.listWorkspaceArtifacts()
    val matches = artifacts.filter { manifestPath.isBlank() || it.manifestPath == manifestPath }
    val diagnostics = JSONArray()
    matches.forEach { artifact ->
      artifact.diagnostics.forEach { diagnostic ->
        diagnostics.put(
          JSONObject()
            .put("artifact", artifact.manifestPath)
            .put("level", diagnostic.level)
            .put("path", diagnostic.path)
            .put("message", diagnostic.message),
        )
      }
    }
    if (matches.isEmpty()) {
      diagnostics.put(
        JSONObject()
          .put("level", "error")
          .put("path", manifestPath.ifBlank { "flovera.app.json" })
          .put("message", "No discovered Flovera app manifest matched the request."),
      )
    }
    val artifactsJson = JSONArray()
    matches.forEach { artifact ->
      artifactsJson.put(
        JSONObject()
          .put("manifestPath", artifact.manifestPath)
          .put("rootPath", artifact.rootPath)
          .put("name", artifact.name)
          .put("kind", artifact.kind)
          .put("valid", artifact.valid)
          .put("preview", artifact.preview?.path ?: JSONObject.NULL)
          .put("previewKind", artifact.preview?.kind ?: JSONObject.NULL)
          .put("serverCommand", artifact.preview?.command?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
          .put("serverCwd", artifact.preview?.cwd ?: JSONObject.NULL)
          .put("urlPath", artifact.preview?.urlPath ?: JSONObject.NULL)
          .put("actions", JSONArray(artifact.actions.map { "${it.id}:${it.kind}" }))
          .put("outputs", JSONArray(artifact.outputs)),
      )
    }
    val errors = diagnostics.countLevel("error")
    val warnings = diagnostics.countLevel("warning")
    val ok = matches.isNotEmpty() && matches.all { it.valid && it.preview != null } && errors == 0
    val output = JSONObject()
      .put("ok", ok)
      .put("workflow", "flovera app verify-registration")
      .put("requestedManifest", if (manifestPath.isBlank()) JSONObject.NULL else manifestPath)
      .put("discoveredManifests", artifacts.size)
      .put("matchedManifests", matches.size)
      .put("summary", JSONObject().put("errors", errors).put("warnings", warnings))
      .put("artifacts", artifactsJson)
      .put("diagnostics", diagnostics)
      .put(
        "diagnoseText",
        workspace.diagnoseWorkspaceArtifact(
          manifestPath = manifestPath,
          includeReference = includeReference,
        ),
      )
    if (includeReference) output.put("reference", workspace.referenceWorkspaceArtifactDemo())
    return PythonRunResult(
      status = if (ok) "ok" else "error",
      exitCode = if (ok) 0 else 2,
      stdout = output.toString(2) + "\n",
      elapsedMs = elapsed(startedAt),
    )
  }

  private fun skillCommand(argv: List<String>, startedAt: Long): PythonRunResult {
    val action = argv.getOrNull(2)?.lowercase().orEmpty().ifBlank { "check" }
    if (action != "check") {
      return PythonRunResult(
        status = "error",
        exitCode = 1,
        stderr = "Supported: flovera skill check [skill-id|skill-path|--all]\n",
        elapsedMs = elapsed(startedAt),
      )
    }
    val target = argv.getOrNull(3).orEmpty().takeUnless { it == "--all" }.orEmpty().ifBlank { optionValue(argv, "id") }
    val manifestFile = resolveWorkspacePath(".flovera/skills/manifest.json")
    val findings = JSONArray()
    val checked = JSONArray()
    val manifest = runCatching { JSONObject(manifestFile.readText(Charsets.UTF_8)) }.getOrElse { error ->
      findings.put(
        workflowFinding(
          level = "error",
          code = "manifest_json_invalid",
          path = ".flovera/skills/manifest.json",
          message = "Skill manifest is not valid JSON: ${error.message.orEmpty()}",
          suggestion = "Rewrite .flovera/skills/manifest.json as a JSON object with version and skills array.",
        ),
      )
      null
    }
    val skills = manifest?.optJSONArray("skills") ?: JSONArray()
    val registrations = mutableListOf<JSONObject>()
    for (index in 0 until skills.length()) {
      skills.optJSONObject(index)?.let(registrations::add) ?: findings.put(
        workflowFinding(
          level = "error",
          code = "manifest_skill_entry_invalid",
          path = ".flovera/skills/manifest.json.skills[$index]",
          message = "Skill manifest entry must be an object.",
          suggestion = "Use one object per skill registration.",
        ),
      )
    }
    val selected = registrations.filter { registration ->
      target.isBlank() ||
        registration.optString("id") == target ||
        registration.optString("path").replace('\\', '/') == target.replace('\\', '/')
    }
    val pathTarget = target.takeIf { it.isNotBlank() && (it.endsWith(".md") || it.contains('/')) }
    val selectedOrPath = if (selected.isNotEmpty() || pathTarget == null) {
      selected
    } else {
      listOf(
        JSONObject()
          .put("id", File(pathTarget).parentFile?.name.orEmpty().ifBlank { File(pathTarget).nameWithoutExtension })
          .put("path", pathTarget),
      )
    }
    if (selectedOrPath.isEmpty()) {
      findings.put(
        workflowFinding(
          level = "error",
          code = "skill_not_registered",
          path = target.ifBlank { ".flovera/skills/manifest.json" },
          message = "No skill registration matched the requested id or path.",
          suggestion = "Register the skill in .flovera/skills/manifest.json, or pass --all to check registered skills.",
        ),
      )
    }
    selectedOrPath.forEach { registration ->
      checkSkillRegistration(registration, findings, checked)
    }
    val errors = findings.countLevel("error")
    val warnings = findings.countLevel("warning")
    val output = JSONObject()
      .put("ok", errors == 0)
      .put("workflow", "flovera skill check")
      .put("target", target.ifBlank { "--all" })
      .put("checked", checked)
      .put("summary", JSONObject().put("errors", errors).put("warnings", warnings))
      .put("findings", findings)
    return PythonRunResult(
      status = if (errors == 0) "ok" else "error",
      exitCode = if (errors == 0) 0 else 2,
      stdout = output.toString(2) + "\n",
      elapsedMs = elapsed(startedAt),
    )
  }

  private fun webviewAuditFiles(target: File): List<File> {
    val root = workspace.root.canonicalFile
    val candidates = if (target.isFile) {
      listOf(target) + linkedCssFiles(target)
    } else {
      target.walkTopDown()
        .onEnter { dir ->
          val relative = workspace.workspaceRelativePath(dir).replace('\\', '/')
          relative == "." ||
            relative != ".flovera" &&
            !relative.startsWith(".flovera/") &&
            relative != "node_modules" &&
            !relative.endsWith("/node_modules") &&
            !relative.contains("/node_modules/")
        }
        .filter { it.isFile && it.extension.lowercase() in setOf("html", "css") }
        .take(80)
        .toList()
    }
    return candidates
      .map { it.canonicalFile }
      .filter { it.path == root.path || it.path.startsWith(root.path + File.separator) }
      .distinctBy { it.path }
      .sortedBy { workspace.workspaceRelativePath(it) }
  }

  private fun linkedCssFiles(htmlFile: File): List<File> {
    if (!htmlFile.isFile || !htmlFile.extension.equals("html", ignoreCase = true)) return emptyList()
    val text = runCatching { htmlFile.readText(Charsets.UTF_8) }.getOrDefault("")
    val hrefRegex = Regex("""<link\b[^>]*\bhref\s*=\s*["']([^"']+\.css(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
    return hrefRegex.findAll(text)
      .mapNotNull { match ->
        val href = match.groupValues[1].substringBefore('?').trim()
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("//")) null else File(htmlFile.parentFile, href).canonicalFile
      }
      .filter { it.isFile }
      .toList()
  }

  private fun auditWebviewFile(file: File, findings: JSONArray) {
    val path = workspace.workspaceRelativePath(file)
    val text = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
    val compact = text.replace(Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL)), "")
    if (file.extension.equals("html", ignoreCase = true) && !Regex("""<meta\s+name\s*=\s*["']viewport["']""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
      findings.put(
        workflowFinding(
          level = "warning",
          code = "missing_viewport_meta",
          path = path,
          message = "HTML file does not declare a viewport meta tag.",
          suggestion = "Add <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">.",
        ),
      )
    }
    val hasFloveraViewport = compact.contains("--flovera-viewport-height") ||
      compact.contains("FloveraViewport") ||
      Regex("""min-height\s*:\s*var\(\s*--flovera-viewport-height""", RegexOption.IGNORE_CASE).containsMatchIn(compact)
    val rootHeightChain = Regex("""(?is)(html\s*,\s*body(?:\s*,\s*#[\w-]+)?|body\s*,\s*html|#(?:app|root))\s*\{[^}]*\bheight\s*:\s*100%""")
      .find(compact)
    if (rootHeightChain != null && !hasFloveraViewport) {
      findings.put(
        workflowFinding(
          level = "error",
          code = "percentage_height_root_chain",
          path = path,
          message = "Root layout uses a percentage-height chain without the Flovera viewport-height contract.",
          evidence = rootHeightChain.value.take(220),
          suggestion = "Use min-height: var(--flovera-viewport-height, 100vh) at the root and flex: 1; min-height: 0 for nested scroll/editor/canvas panes.",
        ),
      )
    }
    val overflowHiddenRoot = Regex("""(?is)(html\s*,\s*body|body|html)\s*\{[^}]*\boverflow\s*:\s*hidden""").find(compact)
    if (overflowHiddenRoot != null && !hasFloveraViewport) {
      findings.put(
        workflowFinding(
          level = "warning",
          code = "root_overflow_hidden",
          path = path,
          message = "Root overflow is hidden without an explicit Flovera viewport contract.",
          evidence = overflowHiddenRoot.value.take(220),
          suggestion = "Only hide root overflow when the visible root uses Flovera viewport sizing and internal panes own scrolling.",
        ),
      )
    }
    val viewportVarWithoutMinHeight = hasFloveraViewport &&
      !Regex("""min-height\s*:\s*var\(\s*--flovera-viewport-height""", RegexOption.IGNORE_CASE).containsMatchIn(compact)
    if (viewportVarWithoutMinHeight) {
      findings.put(
        workflowFinding(
          level = "warning",
          code = "viewport_var_without_root_min_height",
          path = path,
          message = "Flovera viewport data is referenced but no root min-height contract was found.",
          suggestion = "Drive the app root with min-height: var(--flovera-viewport-height, 100vh).",
        ),
      )
    }
  }

  private fun checkSkillRegistration(registration: JSONObject, findings: JSONArray, checked: JSONArray) {
    val id = registration.optString("id").trim()
    val path = registration.optString("path").trim().replace('\\', '/').ifBlank { ".flovera/skills/$id/SKILL.md" }
    checked.put(JSONObject().put("id", id).put("path", path).put("enabled", registration.optBoolean("enabled", true)))
    if (id.isBlank()) {
      findings.put(workflowFinding("error", "skill_id_missing", ".flovera/skills/manifest.json", "Skill registration id is blank.", "Set id to the folder name under .flovera/skills."))
    }
    if (!Regex("""^\.flovera/skills/[^/]+/SKILL\.md$""").matches(path)) {
      findings.put(workflowFinding("error", "skill_path_not_standard", path, "Skill path must use .flovera/skills/<skill-id>/SKILL.md.", "Move the skill body to the standard folder shape and update manifest path."))
    }
    listOf("descriptionEn", "descriptionZh").forEach { key ->
      if (registration.optString(key).isBlank()) {
        findings.put(workflowFinding("warning", "manifest_${key}_missing", path, "Manifest $key is blank.", "Keep bilingual user-facing descriptions explicit in the manifest."))
      }
    }
    val file = runCatching { resolveWorkspacePath(path) }.getOrNull()
    if (file == null || !file.isFile) {
      findings.put(workflowFinding("error", "skill_file_missing", path, "Registered SKILL.md file does not exist.", "Create the file or fix the manifest path."))
      return
    }
    val body = file.readText(Charsets.UTF_8)
    val frontmatter = parseSkillFrontmatter(body)
    if (frontmatter == null) {
      findings.put(workflowFinding("error", "skill_frontmatter_missing", path, "SKILL.md must start with YAML frontmatter.", "Start the file with --- and include name and description fields."))
      return
    }
    val name = frontmatter["name"].orEmpty()
    val description = frontmatter["description"].orEmpty()
    if (name.isBlank()) {
      findings.put(workflowFinding("error", "skill_name_missing", path, "SKILL.md frontmatter name is blank.", "Set name to the skill id."))
    } else if (id.isNotBlank() && name != id) {
      findings.put(workflowFinding("warning", "skill_name_mismatch", path, "SKILL.md frontmatter name does not match manifest id.", "Keep name and manifest id synchronized unless intentionally portable."))
    }
    if (description.isBlank()) {
      findings.put(workflowFinding("error", "skill_description_missing", path, "SKILL.md frontmatter description is blank.", "Add a concise English trigger description."))
    }
    if (!frontmatter.containsKey("description_zh")) {
      findings.put(workflowFinding("warning", "skill_description_zh_missing", path, "SKILL.md frontmatter description_zh is missing.", "Add description_zh for user-facing Chinese readability."))
    }
    if (!Regex("""(?m)^#\s+\S+""").containsMatchIn(body.substringAfter("---", "").substringAfter("---", ""))) {
      findings.put(workflowFinding("warning", "skill_heading_missing", path, "SKILL.md has no top-level Markdown heading after frontmatter.", "Add a short # heading before workflow instructions."))
    }
  }

  private fun parseSkillFrontmatter(body: String): Map<String, String>? {
    if (!body.startsWith("---")) return null
    val end = body.indexOf("\n---", startIndex = 3)
    if (end <= 0) return null
    return body.substring(3, end)
      .lineSequence()
      .mapNotNull { line ->
        val key = line.substringBefore(':', "").trim()
        if (key.isBlank() || !line.contains(':')) null else key to line.substringAfter(':').trim().trim('"', '\'')
      }
      .toMap()
  }

  private fun resolveWorkspacePath(rawPath: String): File {
    val file = File(workspace.root, rawPath.trim().trim('/')).canonicalFile
    val root = workspace.root.canonicalFile
    require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "Path escapes workspace: $rawPath" }
    return file
  }

  private fun workflowFinding(
    level: String,
    code: String,
    path: String,
    message: String,
    suggestion: String,
    evidence: String = "",
  ): JSONObject {
    val json = JSONObject()
      .put("level", level)
      .put("code", code)
      .put("path", path)
      .put("message", message)
      .put("suggestion", suggestion)
    if (evidence.isNotBlank()) json.put("evidence", evidence)
    return json
  }

  private fun JSONArray.countLevel(level: String): Int {
    var count = 0
    for (index in 0 until length()) {
      if (optJSONObject(index)?.optString("level") == level) count += 1
    }
    return count
  }

  private fun officeCommand(argv: List<String>, startedAt: Long): PythonRunResult {
    val action = argv.getOrNull(2)?.lowercase().orEmpty().ifBlank { "inspect" }
    val output = when (action) {
      "inspect" -> officeInspect(resolveOfficePath(argv))
      "validate" -> officeValidate(resolveOfficePath(argv))
      "text" -> officeText(
        resolveOfficePath(argv),
        optionValue(argv, "max").toIntOrNull() ?: 120,
        optionValue(argv, "backend"),
      )
      "replace" -> officeReplace(
        inputFile = resolveOfficePath(argv),
        outputPath = optionValue(argv, "output"),
        find = optionValue(argv, "find"),
        replacement = optionValue(argv, "replace"),
        backend = optionValue(argv, "backend"),
      )
      else -> error("Supported: flovera office inspect|validate|text|replace <path>")
    }
    return PythonRunResult(
      status = "ok",
      exitCode = 0,
      stdout = output.toString(2) + "\n",
      elapsedMs = elapsed(startedAt),
    )
  }

  private fun resolveOfficePath(argv: List<String>): File {
    val raw = argv.getOrNull(3).orEmpty().ifBlank { optionValue(argv, "path") }
    require(raw.isNotBlank()) { "office file path is required" }
    val file = File(workspace.root, raw.trim().trim('/')).canonicalFile
    val root = workspace.root.canonicalFile
    require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "office path escapes workspace: $raw" }
    require(file.isFile) { "office file was not found: $raw" }
    return file
  }

  private fun officeInspect(file: File): JSONObject {
    val entries = zipEntryNames(file)
    val type = officeType(file, entries)
    val validation = officeValidation(type, entries)
    val text = extractOfficeText(file, type, maxItems = 80)
    val heavy = runCatching { officeJvmBackends().inspect(file, type) }
      .getOrElse { throwable ->
        JSONObject()
          .put("available", officeJvmBackends().availability(type))
          .put("error", "${throwable::class.java.name}: ${throwable.message.orEmpty()}")
      }
    return JSONObject()
      .put("path", workspace.workspaceRelativePath(file))
      .put("type", type)
      .put("valid", validation.optBoolean("valid"))
      .put("backend", "light")
      .put("heavyBackends", heavy)
      .put("missingEntries", validation.optJSONArray("missingEntries") ?: JSONArray())
      .put("entryCount", entries.size)
      .put("textItemCount", text.optInt("count"))
      .put("textPreview", text.optJSONArray("items") ?: JSONArray())
      .put("features", officeFeatures(type, entries))
  }

  private fun officeValidate(file: File): JSONObject {
    val entries = zipEntryNames(file)
    val type = officeType(file, entries)
    return officeValidation(type, entries)
      .put("path", workspace.workspaceRelativePath(file))
      .put("type", type)
      .put("entryCount", entries.size)
      .put("heavyBackends", officeJvmBackends().availability(type))
  }

  private fun officeText(file: File, maxItems: Int, backend: String = ""): JSONObject {
    val entries = zipEntryNames(file)
    val type = officeType(file, entries)
    val normalizedBackend = backend.lowercase().ifBlank { "auto" }
    if (normalizedBackend != "light") {
      val heavy = runCatching {
        officeJvmBackends().text(file, type, maxItems.coerceIn(1, 1_000), normalizedBackend)
      }
      if (heavy.isSuccess) {
        return heavy.getOrThrow()
          .put("path", workspace.workspaceRelativePath(file))
          .put("type", type)
      }
      if (normalizedBackend != "auto" && normalizedBackend != "heavy") {
        throw heavy.exceptionOrNull() ?: IllegalStateException("Office JVM backend failed")
      }
      return extractOfficeText(file, type, maxItems = maxItems.coerceIn(1, 1_000))
        .put("path", workspace.workspaceRelativePath(file))
        .put("type", type)
        .put("backend", "light")
        .put("fallbackFrom", officeJvmBackends().availability(type).optString("defaultBackend"))
        .put("fallbackError", "${heavy.exceptionOrNull()!!::class.java.name}: ${heavy.exceptionOrNull()!!.message.orEmpty()}")
    }
    return extractOfficeText(file, type, maxItems = maxItems.coerceIn(1, 1_000))
      .put("path", workspace.workspaceRelativePath(file))
      .put("type", type)
      .put("backend", "light")
  }

  private fun officeReplace(inputFile: File, outputPath: String, find: String, replacement: String, backend: String = ""): JSONObject {
    require(find.isNotBlank()) { "--find is required" }
    val entries = zipEntryNames(inputFile)
    val type = officeType(inputFile, entries)
    require(officeValidation(type, entries).optBoolean("valid")) { "unsupported or invalid Office OOXML package: $type" }
    val outputFile = if (outputPath.isBlank()) inputFile else workspaceOutputFile(outputPath)
    val normalizedBackend = backend.lowercase().ifBlank { "auto" }
    if (normalizedBackend != "light") {
      val heavy = runCatching {
        officeJvmBackends().replace(
          inputFile,
          type,
          outputFile,
          find,
          replacement,
          normalizedBackend,
        )
      }
      if (heavy.isSuccess) {
        return heavy.getOrThrow()
          .put("path", workspace.workspaceRelativePath(inputFile))
          .put("type", type)
          .put("inPlace", outputFile.canonicalFile == inputFile.canonicalFile)
      }
      if (normalizedBackend != "auto" && normalizedBackend != "heavy") {
        throw heavy.exceptionOrNull() ?: IllegalStateException("Office JVM backend failed")
      }
      val light = officeLightReplace(inputFile, outputFile, type, entries, find, replacement)
      return light
        .put("backend", "light")
        .put("fallbackFrom", officeJvmBackends().availability(type).optString("defaultBackend"))
        .put("fallbackError", "${heavy.exceptionOrNull()!!::class.java.name}: ${heavy.exceptionOrNull()!!.message.orEmpty()}")
    }
    return officeLightReplace(inputFile, outputFile, type, entries, find, replacement)
      .put("backend", "light")
  }

  private fun officeLightReplace(
    inputFile: File,
    outputFile: File,
    type: String,
    entries: List<String>,
    find: String,
    replacement: String,
  ): JSONObject {
    val sameFile = outputFile.canonicalFile == inputFile.canonicalFile
    val target = if (sameFile) File(inputFile.parentFile, ".${inputFile.name}.${System.currentTimeMillis()}.tmp") else outputFile
    target.parentFile?.mkdirs()
    val replaceEntries = officeTextEntries(type, entries).toSet()
    val escapedFind = escapeXml(find)
    val escapedReplacement = escapeXml(replacement)
    var replacementCount = 0
    try {
      ZipInputStream(inputFile.inputStream()).use { input ->
        ZipOutputStream(target.outputStream()).use { output ->
          var entry = input.nextEntry
          while (entry != null) {
            val bytes = input.readBytes()
            val next = ZipEntry(entry.name)
            output.putNextEntry(next)
            val updated = if (!entry.isDirectory && entry.name in replaceEntries) {
              val text = bytes.toString(Charsets.UTF_8)
              val count = countOccurrences(text, escapedFind)
              if (count > 0) {
                replacementCount += count
                text.replace(escapedFind, escapedReplacement).toByteArray(Charsets.UTF_8)
              } else {
                bytes
              }
            } else {
              bytes
            }
            output.write(updated)
            output.closeEntry()
            input.closeEntry()
            entry = input.nextEntry
          }
        }
      }
      if (sameFile) {
        target.copyTo(inputFile, overwrite = true)
        target.delete()
      }
    } catch (error: Throwable) {
      if (sameFile) target.delete()
      throw error
    }
    return JSONObject()
      .put("path", workspace.workspaceRelativePath(inputFile))
      .put("output", workspace.workspaceRelativePath(outputFile))
      .put("type", type)
      .put("replacements", replacementCount)
      .put("inPlace", sameFile)
  }

  private fun officeJvmBackends(): FloveraOfficeJvmBackends {
    return FloveraOfficeJvmBackends { file -> workspace.workspaceRelativePath(file) }
  }

  private fun workspaceOutputFile(path: String): File {
    val file = File(workspace.root, path.trim().trim('/')).canonicalFile
    val root = workspace.root.canonicalFile
    require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "office output path escapes workspace: $path" }
    return file
  }

  private fun zipEntryNames(file: File): List<String> {
    return ZipFile(file).use { zip ->
      zip.entries().asSequence().map { it.name }.toList()
    }
  }

  private fun officeType(file: File, entries: List<String>): String {
    val extension = file.extension.lowercase()
    return when {
      extension == "docx" || "word/document.xml" in entries -> "docx"
      extension == "xlsx" || "xl/workbook.xml" in entries -> "xlsx"
      extension == "pptx" || "ppt/presentation.xml" in entries -> "pptx"
      else -> "unknown"
    }
  }

  private fun officeValidation(type: String, entries: List<String>): JSONObject {
    val required = when (type) {
      "docx" -> listOf("[Content_Types].xml", "word/document.xml")
      "xlsx" -> listOf("[Content_Types].xml", "xl/workbook.xml")
      "pptx" -> listOf("[Content_Types].xml", "ppt/presentation.xml")
      else -> listOf("[Content_Types].xml")
    }
    val missing = required.filterNot { it in entries }
    return JSONObject()
      .put("valid", type != "unknown" && missing.isEmpty())
      .put("missingEntries", JSONArray(missing))
      .put("requiredEntries", JSONArray(required))
  }

  private fun officeFeatures(type: String, entries: List<String>): JSONObject {
    return JSONObject()
      .put("textEntryCount", officeTextEntries(type, entries).size)
      .put("hasCoreProperties", "docProps/core.xml" in entries)
      .put("hasAppProperties", "docProps/app.xml" in entries)
      .put("sheetCount", entries.count { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") })
      .put("slideCount", entries.count { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") })
  }

  private fun officeTextEntries(type: String, entries: List<String>): List<String> {
    return when (type) {
      "docx" -> entries.filter {
        it == "word/document.xml" ||
          Regex("""word/(header|footer|footnotes|endnotes)\d*\.xml""").matches(it)
      }
      "xlsx" -> entries.filter {
        it == "xl/sharedStrings.xml" || it.startsWith("xl/worksheets/") && it.endsWith(".xml")
      }
      "pptx" -> entries.filter {
        it.startsWith("ppt/slides/") && it.endsWith(".xml") ||
          it.startsWith("ppt/notesSlides/") && it.endsWith(".xml")
      }
      else -> emptyList()
    }
  }

  private fun extractOfficeText(file: File, type: String, maxItems: Int): JSONObject {
    val items = JSONArray()
    var count = 0
    val errors = JSONArray()
    ZipFile(file).use { zip ->
      val entries = officeTextEntries(type, zip.entries().asSequence().map { it.name }.toList())
      entries.forEach { name ->
        val entry = zip.getEntry(name) ?: return@forEach
        val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        val values = runCatching { xmlTextValues(xml) }
          .onFailure { errors.put(JSONObject().put("entry", name).put("error", it.message.orEmpty())) }
          .getOrDefault(emptyList())
        values.forEach { value ->
          count += 1
          if (items.length() < maxItems && value.isNotBlank()) {
            items.put(JSONObject().put("entry", name).put("text", value))
          }
        }
      }
    }
    return JSONObject()
      .put("count", count)
      .put("items", items)
      .put("errors", errors)
  }

  private fun xmlTextValues(xml: String): List<String> {
    return OOXML_TEXT_NODE_PATTERN.findAll(xml)
      .map { match -> unescapeXml(match.groupValues[2]) }
      .toList()
  }

  private fun escapeXml(value: String): String {
    return value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
  }

  private fun unescapeXml(value: String): String {
    return value
      .replace("&apos;", "'")
      .replace("&quot;", "\"")
      .replace("&gt;", ">")
      .replace("&lt;", "<")
      .replace("&amp;", "&")
  }

  private fun countOccurrences(text: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = text.indexOf(needle)
    while (index >= 0) {
      count += 1
      index = text.indexOf(needle, index + needle.length)
    }
    return count
  }

  private fun elapsed(startedAt: Long): Int = (System.currentTimeMillis() - startedAt).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

  companion object {
    private const val SCRIPT_DIRECTORY = ".flovera/scripts"
    private val OOXML_TEXT_NODE_PATTERN = Regex(
      """<([A-Za-z0-9_]+:)?t(?:\s[^>]*)?>(.*?)</([A-Za-z0-9_]+:)?t>""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
  }
}

private class AndroidCommandAdapter(
  private val workspace: WorkspaceManager,
  private val networkEnabled: Boolean,
) : WorkspaceCommandAdapter {
  override val commandNames: Set<String> = setOf("android")

  override fun classify(argv: List<String>): WorkspaceCommandRisk {
    val profile = argv.getOrNull(1)?.lowercase().orEmpty()
    return when (profile) {
      "app", "permission", "intent", "notification", "location", "contacts", "calendar", "media",
      "bluetooth", "overlay", "storage", "package", "alarm", "network", "foreground", "camera",
      "microphone", "ui", "help", "capabilities" -> WorkspaceCommandRisk(
        "android.$profile",
        listOf("android.app", "android.$profile"),
      )
      "" -> WorkspaceCommandRisk("android.invalid", listOf("android.app"))
      else -> WorkspaceCommandRisk("android.unsupported", listOf("android:$profile"))
    }
  }

  override fun execute(argv: List<String>, args: WorkspaceCommandRunTool.Args, runId: String): PythonRunResult {
    val startedAt = System.currentTimeMillis()
    val maxOutputChars = args.maxOutputChars.coerceIn(FloveraPythonRuntime.MIN_OUTPUT_CHARS, FloveraPythonRuntime.MAX_OUTPUT_CHARS)
    val result = AndroidSystemCommandApi(
      context = workspace.applicationContext,
      workspace = workspace,
      networkEnabled = networkEnabled,
    ).execute(argv.drop(1))
    val bounded = boundedText(result.output, maxOutputChars)
    return PythonRunResult(
      status = result.status,
      exitCode = result.exitCode,
      stdout = if (result.status == "ok") bounded.text else "",
      stderr = if (result.status == "ok") "" else bounded.text,
      stdoutTruncated = result.status == "ok" && bounded.truncated,
      stderrTruncated = result.status != "ok" && bounded.truncated,
      elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    )
  }
}

private class GroovyCommandAdapter(
  private val workspace: WorkspaceManager,
  private val networkEnabled: Boolean,
  private val secretEnvironment: Map<String, String>,
  private val useIsolatedWorker: Boolean = true,
) : WorkspaceCommandAdapter {
  override val commandNames: Set<String> = setOf("groovy")

  override fun classify(argv: List<String>): WorkspaceCommandRisk {
    if (argv.size < 2) {
      return WorkspaceCommandRisk("groovy.invalid", listOf("workspace.read", "jvm.dynamic"))
    }
    return when {
      argv[1].startsWith("-") -> WorkspaceCommandRisk("groovy.unsupported_option", listOf("workspace.read", "jvm.dynamic"))
      else -> WorkspaceCommandRisk("groovy.workspace_script", listOf("workspace.read", "workspace.write", "jvm.dynamic", "workspace_script"))
    }
  }

  override fun execute(argv: List<String>, args: WorkspaceCommandRunTool.Args, runId: String): PythonRunResult {
    if (argv.size < 2) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Groovy command requires a workspace script path, for example: groovy tools/hello.groovy\n")
    }
    if (argv[1].startsWith("-")) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Unsupported groovy option: ${argv[1]}. Supported now: groovy script.groovy [args...]\n")
    }

    if (useIsolatedWorker) {
      return JvmWorkerClient(
        appContext = workspace.applicationContext,
        workspaceId = workspace.root.name,
        networkEnabled = networkEnabled,
      ).runGroovy(argv, args.copy(environment = secretEnvironment + args.environment))
    }

    val timeoutMs = args.timeoutMs.coerceIn(FloveraPythonRuntime.MIN_TIMEOUT_MS, FloveraPythonRuntime.MAX_TIMEOUT_MS)
    val maxOutputChars = args.maxOutputChars.coerceIn(FloveraPythonRuntime.MIN_OUTPUT_CHARS, FloveraPythonRuntime.MAX_OUTPUT_CHARS)
    val startedAt = System.currentTimeMillis()
    val cwdFile = workspace.workspaceRuntimeDirectory(args.cwd)
    if (!cwdFile.exists()) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Command cwd does not exist: ${args.cwd}\n")
    }
    if (!cwdFile.isDirectory) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Command cwd is not a directory: ${args.cwd}\n")
    }
    val scriptFile = File(cwdFile, argv[1]).canonicalFile
    val root = workspace.root.canonicalFile
    if (scriptFile.path != root.path && !scriptFile.path.startsWith(root.path + File.separator)) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Groovy script escapes workspace: ${argv[1]}\n")
    }
    if (!scriptFile.isFile) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Groovy script does not exist: ${argv[1]}\n")
    }
    if (args.snapshotBeforeRun) {
      workspace.createAutomaticSnapshot("workspace_command_run_groovy")
    }

    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val executor = Executors.newSingleThreadExecutor { runnable ->
      Thread {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        runnable.run()
      }.apply { name = "flovera-groovy-worker" }
    }
    val future = executor.submit(Callable {
      runGroovyScript(
        scriptFile = scriptFile,
        scriptArgs = argv.drop(2),
        environment = secretEnvironment + args.environment,
        stdout = stdout,
        stderr = stderr,
      )
    })
    return try {
      val returned = future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
      val stdoutText = boundedText(stdout, maxOutputChars)
      val stderrText = boundedText(stderr, maxOutputChars)
      val returnText = returned?.toString().orEmpty()
      PythonRunResult(
        status = "ok",
        exitCode = 0,
        stdout = listOf(stdoutText.text, returnText).filter { it.isNotBlank() }.joinToString("\n"),
        stderr = stderrText.text,
        stdoutTruncated = stdoutText.truncated,
        stderrTruncated = stderrText.truncated,
        elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
      )
    } catch (_: TimeoutException) {
      future.cancel(true)
      PythonRunResult(
        status = "timeout",
        exitCode = 124,
        stdout = boundedText(stdout, maxOutputChars).text,
        stderr = "Groovy command timed out after ${timeoutMs}ms.\n" + boundedText(stderr, maxOutputChars).text,
        elapsedMs = timeoutMs,
      )
    } catch (throwable: Throwable) {
      PythonRunResult(
        status = "error",
        exitCode = 1,
        stdout = boundedText(stdout, maxOutputChars).text,
        stderr = groovyErrorText(throwable, boundedText(stderr, maxOutputChars).text),
        elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
      )
    } finally {
      executor.shutdownNow()
    }
  }

  private fun groovyErrorText(throwable: Throwable, existingStderr: String): String {
    val classified = classifyGroovyFailure(throwable, existingStderr)
    return buildString {
      if (existingStderr.isNotBlank()) appendLine(existingStderr.trimEnd())
      appendLine("failureCategory=${classified.category}")
      appendLine("failureHint=${classified.hint}")
      appendLine("Groovy spike failed: ${throwable::class.java.name}: ${throwable.message.orEmpty()}")
      var cause = throwable.cause
      var depth = 0
      while (cause != null && depth < 5) {
        appendLine("caused by: ${cause::class.java.name}: ${cause.message.orEmpty()}")
        cause = cause.cause
        depth += 1
      }
      throwable.stackTrace.take(12).forEach { frame ->
        appendLine("  at $frame")
      }
    }
  }

  private fun classifyGroovyFailure(throwable: Throwable, existingStderr: String): GroovyFailureClassification {
    val chain = mutableListOf<String>()
    var current: Throwable? = throwable
    var depth = 0
    while (current != null && depth < 8) {
      chain += "${current::class.java.name}: ${current.message.orEmpty()}"
      current = current.cause
      depth += 1
    }
    val text = chain.joinToString("\n") + "\n" + existingStderr
    return when {
      "cancel.flag" in text || "cancelled" in text.lowercase() -> GroovyFailureClassification(
        "jvm_build_cancelled",
        "JVM 构建被取消。删除取消标记后可从已完成的缓存继续重试。",
      )
      "Unable to resolve Maven artifact" in text || "UnknownHostException" in text || "FileNotFoundException" in text && ".pom" in text -> GroovyFailureClassification(
        "maven_resolution_failed",
        "Maven 坐标、网络或仓库不可用。检查 libs/maven.json、network 设置和依赖版本。",
      )
      "D8" in text || "com.android.tools.r8" in text || "Dex" in text && "failed" in text.lowercase() -> GroovyFailureClassification(
        "d8_conversion_failed",
        "JVM jar 或脚本 class 转 dex 失败。依赖可能使用了 Android 不兼容字节码、API 或过大的库树。",
      )
      "Multiple dex files define" in text || "NoClassDefFoundError" in text || "ClassNotFoundException" in text -> GroovyFailureClassification(
        "android_class_loading_failed",
        "dex 类加载失败。可能是依赖冲突、缺少传递依赖，或 Android 运行时不支持该 JVM 库。",
      )
      "MultipleCompilationErrorsException" in text || "CompilationFailedException" in text -> GroovyFailureClassification(
        "groovy_compile_failed",
        "Groovy 脚本编译失败。检查 import、语法和 Maven 依赖是否已经可用。",
      )
      else -> GroovyFailureClassification(
        "groovy_runtime_failed",
        "脚本运行或 JVM 准备阶段失败。查看 .flovera/logs/jvm-build.jsonl 的最后阶段定位是编译还是运行。",
      )
    }
  }

  private fun runGroovyScript(
    scriptFile: File,
    scriptArgs: List<String>,
    environment: Map<String, String>,
    stdout: ByteArrayOutputStream,
    stderr: ByteArrayOutputStream,
  ): Any? {
    val outWriter = PrintWriter(stdout.writer(StandardCharsets.UTF_8), true)
    val errWriter = PrintWriter(stderr.writer(StandardCharsets.UTF_8), true)
    normalizeGroovyAndroidJavaVersion()
    val build = JvmBuildScheduler(workspace.root, errWriter)
    val buildOutput = build.exclusive("groovy:${workspace.workspaceRelativePath(scriptFile)}") {
      val artifacts = JvmArtifactManager(workspace, build)
      val workspaceJars = artifacts.workspaceJars() + artifacts.mavenJars(networkEnabled, environment)
      build.stage(
        name = "jvm.artifacts.selected",
        detail = "${workspaceJars.size} jar(s), ${workspaceJars.sumOf { it.sizeBytes }} bytes",
        cooldownMs = adaptiveCooldownMs(workspaceJars.size, workspaceJars.sumOf { it.sizeBytes }, baseMs = 80),
      ) {
        workspaceJars
      }
      val libraryDex = artifacts.ensureLibraryDex(workspaceJars)
      val scriptDex = artifacts.ensureGroovyScriptDex(scriptFile, workspaceJars, libraryDex)
      JvmGroovyBuildOutput(libraryDex = libraryDex, scriptDex = scriptDex)
    }
    val binding = Binding().apply {
      setVariable("args", scriptArgs.toTypedArray())
      setVariable("argv", scriptArgs)
      setVariable("ctx", FloveraGroovyContext(workspace, outWriter))
      setVariable("out", outWriter)
      setVariable("err", errWriter)
    }
    val dexPath = (listOf(buildOutput.scriptDex.dexFile.absolutePath) + buildOutput.libraryDex.dexPathsOrEmpty())
      .joinToString(File.pathSeparator)
    val loader = DexClassLoader(dexPath, buildOutput.scriptDex.optimizedDir.absolutePath, null, javaClass.classLoader)
    val scriptClass = loader.loadClass(buildOutput.scriptDex.mainClassName).asSubclass(Script::class.java)
    val script = scriptClass.getDeclaredConstructor().newInstance()
    script.binding = binding
    return FloveraGroovyFile.withWorkspaceRoot(workspace.root) { script.run() }
  }

  private fun normalizeGroovyAndroidJavaVersion() {
    System.setProperty("java.specification.version", "1.8")
  }

}

class JvmWorkerService : Service() {
  private lateinit var workerThread: HandlerThread
  private lateinit var messenger: Messenger

  override fun onCreate() {
    super.onCreate()
    workerThread = HandlerThread("flovera-jvm-worker-service", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
    messenger = Messenger(Handler(workerThread.looper) { message ->
      when (message.what) {
        MSG_RUN_GROOVY -> {
          handleRunGroovy(message)
          true
        }
        else -> false
      }
    })
  }

  override fun onBind(intent: Intent?): IBinder = messenger.binder

  override fun onDestroy() {
    workerThread.quitSafely()
    super.onDestroy()
  }

  private fun handleRunGroovy(message: Message) {
    val replyTo = message.replyTo ?: return
    val result = runCatching {
      val data = message.data
      val workspaceId = data.getString(KEY_WORKSPACE_ID).orEmpty()
      val argsJson = data.getString(KEY_ARGS_JSON).orEmpty()
      val argv = data.getStringArrayList(KEY_ARGV).orEmpty()
      val networkEnabled = data.getBoolean(KEY_NETWORK_ENABLED)
      val args = workspaceCommandJson.decodeFromString<WorkspaceCommandRunTool.Args>(argsJson)
      val workspace = WorkspaceManager(applicationContext, workspaceId)
      val directResult = GroovyCommandAdapter(
        workspace = workspace,
        networkEnabled = networkEnabled,
        secretEnvironment = emptyMap(),
        useIsolatedWorker = false,
      ).execute(argv, args, UUID.randomUUID().toString())
      directResult.copy(stderr = workerHeader() + directResult.stderr)
    }.getOrElse { throwable ->
      PythonRunResult(
        status = "error",
        exitCode = 1,
        stderr = workerHeader() +
          "failureCategory=jvm_worker_failed\n" +
          "failureHint=JVM worker 进程无法执行 Groovy 任务。查看 .flovera/logs/app-crash.jsonl 和 .flovera/logs/jvm-build.jsonl。\n" +
          "JVM worker failed: ${throwable::class.java.name}: ${throwable.message.orEmpty()}\n",
      )
    }
    val reply = Message.obtain(null, MSG_RESULT).apply {
      data = Bundle().apply {
        putString(KEY_RESULT_JSON, workspaceCommandJson.encodeToString(result))
      }
    }
    runCatching { replyTo.send(reply) }
  }

  private fun workerHeader(): String {
    return "[jvm-worker] process=${Application.getProcessName()} pid=${Process.myPid()}\n"
  }
}

private class JvmWorkerClient(
  private val appContext: Context,
  private val workspaceId: String,
  private val networkEnabled: Boolean,
) {
  fun runGroovy(argv: List<String>, args: WorkspaceCommandRunTool.Args): PythonRunResult {
    val timeoutMs = args.timeoutMs.coerceIn(FloveraPythonRuntime.MIN_TIMEOUT_MS, FloveraPythonRuntime.MAX_TIMEOUT_MS)
    val replyThread = HandlerThread("flovera-jvm-worker-reply", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
    val latch = CountDownLatch(1)
    val resultRef = AtomicReference<PythonRunResult?>()
    val errorRef = AtomicReference<String?>()
    val replyMessenger = Messenger(Handler(replyThread.looper) { message ->
      if (message.what == MSG_RESULT) {
        runCatching {
          val json = message.data.getString(KEY_RESULT_JSON).orEmpty()
          resultRef.set(workspaceCommandJson.decodeFromString<PythonRunResult>(json))
        }.onFailure { throwable ->
          errorRef.set("${throwable::class.java.name}: ${throwable.message.orEmpty()}")
        }
        latch.countDown()
        true
      } else {
        false
      }
    })
    var bound = false
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, service: IBinder) {
        runCatching {
          val message = Message.obtain(null, MSG_RUN_GROOVY).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
              putString(KEY_WORKSPACE_ID, workspaceId)
              putStringArrayList(KEY_ARGV, ArrayList(argv))
              putString(KEY_ARGS_JSON, workspaceCommandJson.encodeToString(args))
              putBoolean(KEY_NETWORK_ENABLED, networkEnabled)
            }
          }
          Messenger(service).send(message)
        }.onFailure { throwable ->
          errorRef.set("${throwable::class.java.name}: ${throwable.message.orEmpty()}")
          latch.countDown()
        }
      }

      override fun onServiceDisconnected(name: ComponentName) {
        errorRef.set("JVM worker service disconnected.")
        latch.countDown()
      }
    }
    return try {
      bound = appContext.bindService(Intent(appContext, JvmWorkerService::class.java), connection, Context.BIND_AUTO_CREATE)
      if (!bound) {
        return workerClientError("jvm_worker_bind_failed", "JVM worker service 绑定失败。")
      }
      val waited = latch.await((timeoutMs + 15_000).toLong(), TimeUnit.MILLISECONDS)
      if (!waited) {
        workerClientError("jvm_worker_timeout", "JVM worker 超过 IPC 等待时间，任务可能被 Android 暂停或杀死。")
      } else {
        resultRef.get() ?: workerClientError("jvm_worker_reply_failed", errorRef.get() ?: "JVM worker 未返回有效结果。")
      }
    } finally {
      if (bound) runCatching { appContext.unbindService(connection) }
      replyThread.quitSafely()
    }
  }

  private fun workerClientError(category: String, hint: String): PythonRunResult {
    return PythonRunResult(
      status = "error",
      exitCode = 1,
      stderr = "failureCategory=$category\nfailureHint=$hint 查看 .flovera/logs/app-crash.jsonl 和 .flovera/logs/jvm-build.jsonl。\n",
    )
  }
}

private class JvmArtifactManager(
  private val workspace: WorkspaceManager,
  private val scheduler: JvmBuildScheduler,
) {
  private val root: File = workspace.root.canonicalFile
  private val cacheRoot: File = File(root, ".flovera/runtime/jvm-artifacts").canonicalFile

  fun workspaceJars(): List<JvmJarArtifact> {
    val libsRoot = File(root, "libs").canonicalFile
    if (!libsRoot.isDirectory || !isInsideWorkspace(libsRoot)) return emptyList()
    return libsRoot.walkTopDown()
      .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
      .map { file ->
        val canonical = file.canonicalFile
        JvmJarArtifact(
          file = canonical,
          relativePath = workspace.workspaceRelativePath(canonical),
          sha256 = sha256(canonical.readBytes()),
          sizeBytes = canonical.length(),
        )
      }
      .filter { isInsideWorkspace(it.file) }
      .sortedBy { it.relativePath }
      .toList()
  }

  fun mavenJars(networkEnabled: Boolean, environment: Map<String, String>): List<JvmJarArtifact> {
    val request = readMavenRequest(environment) ?: return emptyList()
    val resolver = MavenArtifactResolver(
      repositoryRoot = File(cacheRoot, "maven/repository"),
      repositories = request.repositories,
      networkEnabled = networkEnabled,
      scheduler = scheduler,
    )
    return scheduler.stage(
      name = "maven.resolve",
      detail = "${request.dependencies.size} root coordinate(s)",
      cooldownMs = 150,
    ) {
      resolver.resolve(request.dependencies)
    }.map { file ->
      val canonical = file.canonicalFile
      JvmJarArtifact(
        file = canonical,
        relativePath = ".flovera/runtime/jvm-artifacts/maven/repository/${canonical.relativeTo(File(cacheRoot, "maven/repository").canonicalFile).invariantSeparatorsPath}",
        sha256 = sha256(canonical.readBytes()),
        sizeBytes = canonical.length(),
      )
    }
  }

  private fun readMavenRequest(environment: Map<String, String>): MavenRequest? {
    val configFiles = mavenConfigFiles(environment)
    if (configFiles.isEmpty()) return null
    val repositories = linkedSetOf("https://repo1.maven.org/maven2")
    val dependencies = linkedSetOf<MavenCoordinate>()
    configFiles.forEach { file ->
      val obj = mavenJson.parseToJsonElement(file.readText(StandardCharsets.UTF_8)).jsonObject
      obj["repositories"]?.jsonArrayOrNull()?.forEach { element ->
        element.jsonPrimitive.contentOrNull?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { repositories += it }
      }
      obj["dependencies"]?.jsonArrayOrNull()?.forEach { element ->
        parseMavenDependency(element)?.let { dependencies += it }
      }
    }
    if (dependencies.isEmpty()) return null
    return MavenRequest(repositories = repositories.toList(), dependencies = dependencies.toList())
  }

  private fun mavenConfigFiles(environment: Map<String, String>): List<File> {
    val overridePath = environment["FLOVERA_JVM_MAVEN_CONFIG"]?.trim().orEmpty()
    if (overridePath.isNotBlank()) {
      val file = File(root, overridePath).canonicalFile
      if (!isInsideWorkspace(file)) {
        error("FLOVERA_JVM_MAVEN_CONFIG escapes workspace: $overridePath")
      }
      if (!file.isFile) {
        error("FLOVERA_JVM_MAVEN_CONFIG file does not exist: $overridePath")
      }
      return listOf(file)
    }
    return listOf(
      File(root, "libs/maven.json"),
      File(root, ".flovera/jvm/maven.json"),
    ).map { it.canonicalFile }.filter { it.isFile && isInsideWorkspace(it) }
  }

  private fun parseMavenDependency(element: kotlinx.serialization.json.JsonElement): MavenCoordinate? {
    if (element is JsonPrimitive) {
      return MavenCoordinate.parse(element.contentOrNull.orEmpty())
    }
    val obj = (element as? JsonObject) ?: return null
    val coordinate = obj["coordinate"]?.jsonPrimitive?.contentOrNull
      ?: obj["coords"]?.jsonPrimitive?.contentOrNull
      ?: obj["gav"]?.jsonPrimitive?.contentOrNull
    if (!coordinate.isNullOrBlank()) return MavenCoordinate.parse(coordinate)
    val groupId = obj["groupId"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val artifactId = obj["artifactId"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val version = obj["version"]?.jsonPrimitive?.contentOrNull.orEmpty()
    return MavenCoordinate.of(groupId, artifactId, version)
  }

  fun ensureLibraryDex(jars: List<JvmJarArtifact>): JvmLibraryDex? {
    if (jars.isEmpty()) return null
    val key = sha256(
      buildString {
        appendLine("flovera-jvm-library-dex-v3-resource-jar")
        appendLine("api=${Build.VERSION.SDK_INT}")
        jars.forEach { jar ->
          appendLine("${jar.relativePath}:${jar.sha256}:${jar.sizeBytes}")
        }
      }.toByteArray(StandardCharsets.UTF_8),
    )
    val dexDir = File(cacheRoot, "libs/$key/dex")
    val optimizedDir = File(cacheRoot, "libs/$key/optimized")
    val manifestFile = File(dexDir.parentFile, "artifacts.txt")
    dexDir.mkdirs()
    optimizedDir.mkdirs()
    val dexArtifacts = jars.mapIndexed { index, jar ->
      val jarKey = sha256(
        buildString {
          appendLine("flovera-jvm-library-jar-dex-v2-resource-jar")
          appendLine("api=${Build.VERSION.SDK_INT}")
          appendLine("${jar.relativePath}:${jar.sha256}:${jar.sizeBytes}")
        }.toByteArray(StandardCharsets.UTF_8),
      )
      val jarDexDir = File(dexDir, "${index.toString().padStart(3, '0')}-$jarKey")
      val dexJarFile = File(jarDexDir, "artifact.dex.jar")
      if (!dexJarFile.isFile) {
        scheduler.stage(
          name = "d8.library.jar",
          detail = "${jar.relativePath} ${jar.sizeBytes} bytes",
          cooldownMs = adaptiveCooldownMs(1, jar.sizeBytes, baseMs = 350),
        ) {
          jarDexDir.deleteRecursively()
          jarDexDir.mkdirs()
          val rawDexDir = File(jarDexDir, "raw-dex")
          rawDexDir.mkdirs()
          val command = D8Command.builder()
            .setMode(CompilationMode.DEBUG)
            .setMinApiLevel(Build.VERSION.SDK_INT)
            .setOutput(rawDexDir.toPath(), OutputMode.DexIndexed)
            .addProgramFiles(jar.file.toPath())
            .build()
          D8.run(command)
          packageDexJarWithResources(jar.file, rawDexDir, dexJarFile)
          rawDexDir.deleteRecursively()
          dexJarFile.setReadable(true, true)
          dexJarFile.setWritable(false, false)
        }
      } else {
        scheduler.markCacheHit("d8.library.jar", jarKey)
      }
      JvmLibraryDexArtifact(dexFile = dexJarFile, source = jar.relativePath, key = jarKey)
    }
    if (!manifestFile.isFile) {
      scheduler.stage(
        name = "d8.library.manifest",
        detail = "${dexArtifacts.size} jar dex artifact(s)",
        cooldownMs = adaptiveCooldownMs(jars.size, jars.sumOf { it.sizeBytes }, baseMs = 150),
      ) {
        manifestFile.writeText(
          dexArtifacts.joinToString("\n") { "${it.source} ${it.key} ${it.dexFile.length()}" },
          StandardCharsets.UTF_8,
        )
      }
    } else {
      scheduler.markCacheHit("d8.library", key)
    }
    return JvmLibraryDex(dexFiles = dexArtifacts.map { it.dexFile }, optimizedDir = optimizedDir, key = key)
  }

  fun ensureGroovyScriptDex(
    scriptFile: File,
    jars: List<JvmJarArtifact>,
    libraryDex: JvmLibraryDex?,
  ): GroovyScriptDex {
    val scriptBytes = scriptFile.readBytes()
    val key = sha256(
      buildString {
        appendLine("flovera-groovy-script-dex-v1")
        appendLine("api=${Build.VERSION.SDK_INT}")
        appendLine("script=${workspace.workspaceRelativePath(scriptFile)}")
        appendLine("scriptSha256=${sha256(scriptBytes)}")
        jars.forEach { jar ->
          appendLine("jar=${jar.relativePath}:${jar.sha256}:${jar.sizeBytes}")
        }
      }.toByteArray(StandardCharsets.UTF_8),
    )
    val cacheDir = File(cacheRoot, "groovy/$key")
    val dexDir = File(cacheDir, "dex")
    val classDir = File(cacheDir, "classes")
    val sourceDir = File(cacheDir, "source")
    val optimizedDir = File(cacheDir, "optimized")
    val dexFile = File(dexDir, "classes.dex")
    val mainClassFile = File(cacheDir, "main-class.txt")
    if (!dexFile.isFile || !mainClassFile.isFile) {
      classDir.deleteRecursively()
      dexDir.deleteRecursively()
      sourceDir.deleteRecursively()
      optimizedDir.mkdirs()
      classDir.mkdirs()
      dexDir.mkdirs()
      sourceDir.mkdirs()
      val compileSource = transformedGroovySource(scriptFile, sourceDir)
      scheduler.stage(name = "groovy.compile", detail = workspace.workspaceRelativePath(scriptFile), cooldownMs = 250) {
        compileGroovyToClasses(compileSource, classDir, libraryDex)
      }
      val mainClassName = findGroovyScriptClassName(scriptFile, classDir)
      scheduler.stage(name = "d8.script", detail = mainClassName, cooldownMs = 250) {
        compileClassesToDex(classDir, dexDir, jars)
        markDexReadOnly(dexDir)
        mainClassFile.writeText(mainClassName, StandardCharsets.UTF_8)
      }
    } else {
      scheduler.markCacheHit("groovy.script", key)
    }
    return GroovyScriptDex(
      dexFile = dexFile,
      optimizedDir = optimizedDir,
      mainClassName = mainClassFile.readText(StandardCharsets.UTF_8).trim(),
      key = key,
    )
  }

  private fun compileGroovyToClasses(scriptFile: File, classDir: File, libraryDex: JvmLibraryDex?) {
    val configuration = CompilerConfiguration().apply {
      targetDirectory = classDir
      targetBytecode = "1.8"
    }
    val parentLoader = if (libraryDex == null) {
      javaClass.classLoader
    } else {
      DexClassLoader(
        libraryDex.dexPath,
        libraryDex.optimizedDir.absolutePath,
        null,
        javaClass.classLoader,
      )
    }
    val groovyClassLoader = GroovyClassLoader(parentLoader, configuration, false)
    val compilationUnit = CompilationUnit(configuration, null, groovyClassLoader)
    FileSystemCompiler(configuration, compilationUnit).compile(arrayOf(scriptFile.absolutePath))
  }

  private fun transformedGroovySource(scriptFile: File, sourceDir: File): File {
    val transformed = NEW_FILE_PATTERN.replace(scriptFile.readText(StandardCharsets.UTF_8)) {
      "new com.flovera.app.koog.FloveraGroovyFile("
    }
    val output = File(sourceDir, scriptFile.name)
    output.writeText(transformed, StandardCharsets.UTF_8)
    return output
  }

  private fun findGroovyScriptClassName(scriptFile: File, classDir: File): String {
    val classFiles = classDir.walkTopDown()
      .filter { it.isFile && it.extension == "class" && !it.name.contains("$") }
      .toList()
    val preferredName = scriptFile.nameWithoutExtension
    val preferred = classFiles.firstOrNull { it.nameWithoutExtension == preferredName }
    val selected = preferred ?: classFiles.firstOrNull()
      ?: error("Groovy compiler produced no class files for ${scriptFile.name}")
    return selected.relativeTo(classDir)
      .invariantSeparatorsPath
      .removeSuffix(".class")
      .replace("/", ".")
  }

  private fun compileClassesToDex(classDir: File, dexDir: File, jars: List<JvmJarArtifact>) {
    val classFiles = classDir.walkTopDown()
      .filter { it.isFile && it.extension == "class" }
      .map { it.toPath() }
      .toList()
    if (classFiles.isEmpty()) {
      error("Groovy compiler produced no class files.")
    }
    val command = D8Command.builder()
      .setMode(CompilationMode.DEBUG)
      .setMinApiLevel(Build.VERSION.SDK_INT)
      .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
      .addProgramFiles(classFiles)
      .addClasspathFiles(jars.map { it.file.toPath() })
      .build()
    D8.run(command)
  }

  private fun markDexReadOnly(dexDir: File) {
    dexDir.walkTopDown()
      .filter { it.isFile && it.extension == "dex" }
      .forEach { dexFile ->
        dexFile.setReadable(true, true)
        dexFile.setWritable(false, false)
      }
  }

  private fun packageDexJarWithResources(sourceJar: File, dexDir: File, outputJar: File) {
    val dexFiles = dexDir.walkTopDown()
      .filter { it.isFile && it.extension.equals("dex", ignoreCase = true) }
      .sortedBy { it.name }
      .toList()
    if (dexFiles.isEmpty()) {
      error("D8 produced no dex files for ${sourceJar.name}.")
    }
    val tempJar = File(outputJar.parentFile, "${outputJar.name}.tmp")
    val written = linkedSetOf<String>()
    JarOutputStream(tempJar.outputStream().buffered()).use { output ->
      dexFiles.forEach { dexFile ->
        val entryName = dexFile.relativeTo(dexDir).invariantSeparatorsPath
        writeJarEntry(output, written, entryName, dexFile.readBytes())
      }
      JarFile(sourceJar).use { inputJar ->
        val entries = inputJar.entries()
        while (entries.hasMoreElements()) {
          val entry = entries.nextElement()
          val name = entry.name
          if (entry.isDirectory || shouldDropOriginalJarEntry(name)) continue
          inputJar.getInputStream(entry).use { input ->
            val copy = JarEntry(name).apply { time = entry.time }
            if (!written.add(name)) return@use
            output.putNextEntry(copy)
            input.copyTo(output)
            output.closeEntry()
          }
        }
      }
    }
    if (outputJar.isFile) outputJar.delete()
    if (!tempJar.renameTo(outputJar)) {
      tempJar.copyTo(outputJar, overwrite = true)
      tempJar.delete()
    }
  }

  private fun writeJarEntry(output: JarOutputStream, written: MutableSet<String>, name: String, bytes: ByteArray) {
    if (!written.add(name)) return
    output.putNextEntry(JarEntry(name))
    output.write(bytes)
    output.closeEntry()
  }

  private fun shouldDropOriginalJarEntry(name: String): Boolean {
    if (name.endsWith(".class", ignoreCase = true)) return true
    if (name.matches(Regex("classes\\d*\\.dex"))) return true
    val upperName = name.uppercase()
    return upperName.startsWith("META-INF/") &&
      (upperName.endsWith(".SF") || upperName.endsWith(".RSA") || upperName.endsWith(".DSA"))
  }

  private fun isInsideWorkspace(file: File): Boolean {
    return file.path == root.path || file.path.startsWith(root.path + File.separator)
  }
}

private class JvmBuildScheduler(
  private val workspaceRoot: File,
  private val progressWriter: PrintWriter,
) {
  private val logFile: File = File(workspaceRoot, ".flovera/logs/jvm-build.jsonl")
  private val stateFile: File = File(workspaceRoot, ".flovera/runtime/jvm-artifacts/build-state.json")
  private val cancelFile: File = File(workspaceRoot, ".flovera/runtime/jvm-artifacts/cancel.flag")

  fun <T> exclusive(label: String, block: () -> T): T {
    val waitStartedAt = System.currentTimeMillis()
    checkCancelled()
    log("jvm.queue.wait", label)
    LOCK.acquire()
    val waitedMs = System.currentTimeMillis() - waitStartedAt
    return try {
      writeState(phase = "jvm.queue.acquired", detail = label, status = "running")
      log("jvm.queue.acquired", "$label waitedMs=$waitedMs")
      coolDown(120)
      block()
    } finally {
      try {
        coolDown(120)
        log("jvm.queue.released", label)
      } finally {
        LOCK.release()
      }
    }
  }

  fun <T> stage(name: String, detail: String, cooldownMs: Long = 0, block: () -> T): T {
    val startedAt = System.currentTimeMillis()
    checkCancelled()
    writeState(phase = name, detail = detail, status = "running")
    log("$name.start", detail)
    return try {
      val result = block()
      val elapsedMs = System.currentTimeMillis() - startedAt
      writeState(phase = name, detail = detail, status = "done", elapsedMs = elapsedMs)
      log("$name.done", "$detail elapsedMs=$elapsedMs")
      coolDown(cooldownMs)
      checkCancelled()
      result
    } catch (throwable: Throwable) {
      val elapsedMs = System.currentTimeMillis() - startedAt
      writeState(
        phase = name,
        detail = detail,
        status = "failed",
        elapsedMs = elapsedMs,
        error = "${throwable::class.java.simpleName}: ${throwable.message.orEmpty()}",
      )
      log("$name.fail", "$detail elapsedMs=$elapsedMs error=${throwable::class.java.simpleName}: ${throwable.message.orEmpty()}")
      throw throwable
    }
  }

  fun markCacheHit(name: String, key: String) {
    checkCancelled()
    writeState(phase = name, detail = key.take(16), status = "cache_hit")
    log("$name.cache_hit", key.take(16))
    coolDown(40)
  }

  private fun checkCancelled() {
    if (cancelFile.isFile) {
      val consumed = cancelFile.delete()
      val suffix = if (consumed) " The cancel flag was consumed." else " The cancel flag could not be deleted."
      error("JVM build cancelled by .flovera/runtime/jvm-artifacts/cancel.flag.$suffix")
    }
  }

  private fun coolDown(ms: Long) {
    if (ms <= 0) return
    runCatching {
      val runtime = Runtime.getRuntime()
      val usedBytes = runtime.totalMemory() - runtime.freeMemory()
      if (runtime.maxMemory() > 0 && usedBytes > runtime.maxMemory() * 3 / 4) {
        System.gc()
        Thread.sleep(150)
      }
      Thread.sleep(ms.coerceAtMost(2_000))
    }.onFailure { throwable ->
      if (throwable is InterruptedException) Thread.currentThread().interrupt()
    }
  }

  private fun log(phase: String, detail: String) {
    val runtime = Runtime.getRuntime()
    val usedBytes = runtime.totalMemory() - runtime.freeMemory()
    val line = JsonObject(
      mapOf(
        "ts" to JsonPrimitive(System.currentTimeMillis()),
        "phase" to JsonPrimitive(phase),
        "detail" to JsonPrimitive(detail),
        "thread" to JsonPrimitive(Thread.currentThread().name),
        "usedMemoryBytes" to JsonPrimitive(usedBytes),
        "maxMemoryBytes" to JsonPrimitive(runtime.maxMemory()),
      ),
    ).toString()
    runCatching {
      logFile.parentFile?.mkdirs()
      logFile.appendText(line + "\n", StandardCharsets.UTF_8)
    }
    progressWriter.println("[jvm-build] $phase $detail")
  }

  private fun writeState(
    phase: String,
    detail: String,
    status: String,
    elapsedMs: Long? = null,
    error: String? = null,
  ) {
    val runtime = Runtime.getRuntime()
    val usedBytes = runtime.totalMemory() - runtime.freeMemory()
    val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
      "ts" to JsonPrimitive(System.currentTimeMillis()),
      "phase" to JsonPrimitive(phase),
      "detail" to JsonPrimitive(detail),
      "status" to JsonPrimitive(status),
      "thread" to JsonPrimitive(Thread.currentThread().name),
      "usedMemoryBytes" to JsonPrimitive(usedBytes),
      "maxMemoryBytes" to JsonPrimitive(runtime.maxMemory()),
      "cancelPath" to JsonPrimitive(".flovera/runtime/jvm-artifacts/cancel.flag"),
    )
    elapsedMs?.let { fields["elapsedMs"] = JsonPrimitive(it) }
    error?.let { fields["error"] = JsonPrimitive(it) }
    runCatching {
      stateFile.parentFile?.mkdirs()
      stateFile.writeText(JsonObject(fields).toString(), StandardCharsets.UTF_8)
    }
  }

  companion object {
    private val LOCK = Semaphore(1, true)
  }
}

private data class JvmJarArtifact(
  val file: File,
  val relativePath: String,
  val sha256: String,
  val sizeBytes: Long,
)

private data class JvmLibraryDex(
  val dexFiles: List<File>,
  val optimizedDir: File,
  val key: String,
) {
  val dexPath: String get() = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
}

private data class JvmLibraryDexArtifact(
  val dexFile: File,
  val source: String,
  val key: String,
)

private data class GroovyScriptDex(
  val dexFile: File,
  val optimizedDir: File,
  val mainClassName: String,
  val key: String,
)

private data class JvmGroovyBuildOutput(
  val libraryDex: JvmLibraryDex?,
  val scriptDex: GroovyScriptDex,
)

private data class GroovyFailureClassification(
  val category: String,
  val hint: String,
)

private fun JvmLibraryDex?.dexPathsOrEmpty(): List<String> {
  return this?.dexFiles?.map { it.absolutePath }.orEmpty()
}

private data class MavenRequest(
  val repositories: List<String>,
  val dependencies: List<MavenCoordinate>,
)

private data class MavenCoordinate(
  val groupId: String,
  val artifactId: String,
  val version: String,
) {
  val key: String get() = "$groupId:$artifactId"
  val gav: String get() = "$groupId:$artifactId:$version"
  val artifactPath: String get() = "${groupId.replace('.', '/')}/$artifactId/$version"
  val jarFileName: String get() = "$artifactId-$version.jar"
  val pomFileName: String get() = "$artifactId-$version.pom"

  companion object {
    fun parse(value: String): MavenCoordinate? {
      val parts = value.trim().split(":")
      if (parts.size != 3) return null
      return of(parts[0], parts[1], parts[2])
    }

    fun of(groupId: String, artifactId: String, version: String): MavenCoordinate? {
      val normalizedGroup = groupId.trim()
      val normalizedArtifact = artifactId.trim()
      val normalizedVersion = version.trim()
      if (normalizedGroup.isBlank() || normalizedArtifact.isBlank() || normalizedVersion.isBlank()) return null
      if (listOf(normalizedGroup, normalizedArtifact, normalizedVersion).any { it.contains("/") || it.contains("\\") }) return null
      return MavenCoordinate(normalizedGroup, normalizedArtifact, normalizedVersion)
    }
  }
}

private data class MavenPomInfo(
  val dependencies: List<MavenCoordinate>,
)

private class MavenArtifactResolver(
  private val repositoryRoot: File,
  private val repositories: List<String>,
  private val networkEnabled: Boolean,
  private val scheduler: JvmBuildScheduler,
) {
  private val xmlFactory = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = false
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
  }

  fun resolve(roots: List<MavenCoordinate>): List<File> {
    repositoryRoot.mkdirs()
    val resolved = linkedMapOf<String, MavenCoordinate>()
    val queue = ArrayDeque<MavenCoordinate>()
    roots.forEach { queue.add(it) }
    while (queue.isNotEmpty() && resolved.size < 96) {
      val coordinate = queue.removeFirst()
      val existing = resolved[coordinate.key]
      if (existing != null) continue
      scheduler.stage(name = "maven.pom", detail = coordinate.gav, cooldownMs = 80) {
        ensureArtifactFile(coordinate, "pom")
      }
      resolved[coordinate.key] = coordinate
      scheduler.stage(name = "maven.parsePom", detail = coordinate.gav, cooldownMs = 40) {
        parsePom(coordinate)
      }.dependencies.forEach { dependency ->
        if (dependency.key !in resolved) queue.add(dependency)
      }
    }
    return resolved.values.map { coordinate ->
      scheduler.stage(name = "maven.jar", detail = coordinate.gav, cooldownMs = 120) {
        ensureArtifactFile(coordinate, "jar")
      }
    }
  }

  private fun parsePom(coordinate: MavenCoordinate): MavenPomInfo {
    val pomFile = ensureArtifactFile(coordinate, "pom")
    val document = xmlFactory.newDocumentBuilder().parse(pomFile)
    val project = document.documentElement
    val parent = project.directChild("parent")
    val properties = mutableMapOf<String, String>()
    val projectGroupId = project.childText("groupId") ?: parent?.childText("groupId") ?: coordinate.groupId
    val projectVersion = project.childText("version") ?: parent?.childText("version") ?: coordinate.version
    val projectArtifactId = project.childText("artifactId") ?: coordinate.artifactId
    properties["project.groupId"] = projectGroupId
    properties["pom.groupId"] = projectGroupId
    properties["project.version"] = projectVersion
    properties["pom.version"] = projectVersion
    properties["project.artifactId"] = projectArtifactId
    properties["pom.artifactId"] = projectArtifactId
    project.directChild("properties")?.directChildren()?.forEach { property ->
      properties[property.tagName] = property.textContent.trim()
    }

    val dependencies = project.directChild("dependencies")
      ?.directChildren("dependency")
      ?.mapNotNull { dependency ->
        val scope = substituteProperties(dependency.childText("scope") ?: "compile", properties)
        val optional = substituteProperties(dependency.childText("optional") ?: "false", properties)
        if (scope in setOf("test", "provided", "system", "import") || optional.equals("true", ignoreCase = true)) {
          null
        } else {
          val groupId = substituteProperties(dependency.childText("groupId").orEmpty(), properties)
          val artifactId = substituteProperties(dependency.childText("artifactId").orEmpty(), properties)
          val version = substituteProperties(dependency.childText("version").orEmpty(), properties)
          MavenCoordinate.of(groupId, artifactId, version)
        }
      }
      .orEmpty()
    return MavenPomInfo(dependencies)
  }

  private fun ensureArtifactFile(coordinate: MavenCoordinate, extension: String): File {
    val fileName = if (extension == "pom") coordinate.pomFileName else coordinate.jarFileName
    val localFile = File(repositoryRoot, "${coordinate.artifactPath}/$fileName")
    if (localFile.isFile) return localFile
    localFile.parentFile?.mkdirs()
    val relativePath = "${coordinate.artifactPath}/$fileName"
    val errors = mutableListOf<String>()
    repositories.forEach { repository ->
      val source = "${repository.trimEnd('/')}/$relativePath"
      val protocol = runCatching { URL(source).protocol }.getOrElse { "" }
      if (!networkEnabled && protocol in setOf("http", "https")) {
        errors += "network disabled for $source"
        return@forEach
      }
      runCatching {
        URL(source).openStream().use { input ->
          localFile.outputStream().use { output -> input.copyTo(output) }
        }
      }.onSuccess {
        return localFile
      }.onFailure { error ->
        errors += "${error::class.java.simpleName}: ${error.message.orEmpty()}"
      }
    }
    error("Unable to resolve Maven artifact ${coordinate.gav} ($extension). Tried ${repositories.size} repositories. ${errors.take(3).joinToString("; ")}")
  }

  private fun substituteProperties(value: String, properties: Map<String, String>): String {
    var current = value.trim()
    repeat(8) {
      val next = MAVEN_PROPERTY_PATTERN.replace(current) { match ->
        properties[match.groupValues[1]] ?: match.value
      }
      if (next == current) return current
      current = next
    }
    return current
  }
}

class FloveraGroovyContext internal constructor(
  private val workspace: WorkspaceManager,
  private val out: PrintWriter,
) {
  fun readText(path: String): String = workspace.readFile(path)

  fun writeText(path: String, content: String): String {
    return workspace.writeFile(path, content)
  }

  fun log(message: String) {
    out.println(message)
  }
}

class FloveraGroovyFile : File {
  constructor(pathname: String) : super(resolveWorkspacePath(pathname))
  constructor(parent: String?, child: String) : super(parent?.let { resolveWorkspacePath(it) }, child)
  constructor(parent: File?, child: String) : super(parent, child)

  companion object {
    private val workspaceRoot = ThreadLocal<File>()

    internal fun <T> withWorkspaceRoot(root: File, block: () -> T): T {
      val previous = workspaceRoot.get()
      workspaceRoot.set(root.canonicalFile)
      return try {
        block()
      } finally {
        if (previous == null) {
          workspaceRoot.remove()
        } else {
          workspaceRoot.set(previous)
        }
      }
    }

    @JvmStatic
    fun resolveWorkspacePath(path: String): String {
      val file = File(path)
      if (file.isAbsolute) return file.path
      val root = workspaceRoot.get() ?: return file.path
      return File(root, path).path
    }
  }
}

private data class BoundedText(
  val text: String,
  val truncated: Boolean,
)

private fun boundedText(stream: ByteArrayOutputStream, maxChars: Int): BoundedText {
  val text = stream.toString(StandardCharsets.UTF_8.name())
  if (text.length <= maxChars) return BoundedText(text, false)
  return BoundedText(text.take(maxChars), true)
}

private fun boundedText(text: String, maxChars: Int): BoundedText {
  if (text.length <= maxChars) return BoundedText(text, false)
  return BoundedText(text.take(maxChars), true)
}

private fun sha256(bytes: ByteArray): String {
  return MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
}

private fun adaptiveCooldownMs(itemCount: Int, totalBytes: Long, baseMs: Long): Long {
  val sizeMb = totalBytes / (1024L * 1024L)
  return (baseMs + itemCount * 90L + sizeMb * 18L).coerceIn(baseMs, 2_000L)
}

private const val MSG_RUN_GROOVY = 1
private const val MSG_RESULT = 2
private const val KEY_WORKSPACE_ID = "workspace_id"
private const val KEY_ARGV = "argv"
private const val KEY_ARGS_JSON = "args_json"
private const val KEY_RESULT_JSON = "result_json"
private const val KEY_NETWORK_ENABLED = "network_enabled"

private val workspaceCommandJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}
private val mavenJson = Json { ignoreUnknownKeys = true }
private val MAVEN_PROPERTY_PATTERN = Regex("""\$\{([^}]+)\}""")
private val NEW_FILE_PATTERN = Regex("""\bnew\s+File\s*\(""")

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

private fun Element.childText(name: String): String? {
  return directChild(name)?.textContent?.trim()?.takeIf { it.isNotBlank() }
}

private fun Element.directChild(name: String): Element? {
  return directChildren(name).firstOrNull()
}

private fun Element.directChildren(name: String? = null): List<Element> {
  val result = mutableListOf<Element>()
  val nodes = childNodes
  for (index in 0 until nodes.length) {
    val node = nodes.item(index)
    if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
      val element = node as Element
      if (name == null || element.tagName == name) {
        result += element
      }
    }
  }
  return result
}

private data class WorkspaceCommandRisk(
  val category: String,
  val permissions: List<String>,
) {
  companion object {
    fun unsupported(command: String): WorkspaceCommandRisk {
      return WorkspaceCommandRisk("unsupported", listOf("command:$command"))
    }
  }
}

private data class WorkspaceCommandAuthorization(
  val allowed: Boolean,
  val authorityMode: String,
  val reason: String,
)

private fun List<String>.commandSummary(): String {
  return joinToString(" ") { arg ->
    if (arg.length <= 80) arg else arg.take(77) + "..."
  }
}
