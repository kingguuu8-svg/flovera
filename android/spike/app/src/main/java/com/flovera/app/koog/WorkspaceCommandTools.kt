package com.flovera.app.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import android.os.Build
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.flovera.app.workspace.WorkspaceManager
import dalvik.system.DexClassLoader
import groovy.lang.Binding
import groovy.lang.GroovyClassLoader
import groovy.lang.Script
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.tools.FileSystemCompiler

class WorkspaceCommandRunTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
  private val networkEnabled: Boolean = false,
  private val authorityMode: String = "safe",
) : SimpleTool<WorkspaceCommandRunTool.Args>(
  argsType = typeToken<Args>(),
  name = "workspace_command_run",
  description = "Primary bounded command-style execution surface for Flovera-owned workspace runtimes. Use this for normal Python execution, including user requests like \"use Python\", generated scripts, project commands, and python -c code. This is not Android shell access: supported command runtimes are python/python3 and an experimental Groovy spike with workspace libs/*.jar classpath support, cwd, timeout, output limits, snapshots, and workspace boundaries.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Command argv array. Supported now: [\"python\", \"script.py\", ...], [\"python3\", \"script.py\", ...], or [\"python\", \"-c\", \"code\", ...]. Do not use shell syntax.")
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
    @property:LLMDescription("Optional environment variables for this bounded command. Values are restored after the run.")
    val environment: Map<String, String> = emptyMap(),
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      WorkspaceCommandGateway(
        workspace = workspace,
        networkEnabled = networkEnabled,
        authorityMode = authorityMode,
      ).run(args)
    }.getOrElse { it.message ?: it.toString() }
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
) {
  private val adapters: List<WorkspaceCommandAdapter> = listOf(
    PythonCommandAdapter(workspace, networkEnabled),
    GroovyCommandAdapter(workspace),
  )

  fun run(args: WorkspaceCommandRunTool.Args): String {
    val argv = args.argv.map { it.trim() }.filter { it.isNotEmpty() }
    if (argv.isEmpty()) {
      val risk = WorkspaceCommandRisk.unsupported("missing")
      return denied(
        message = "Missing command argv. Supported now: python/python3 workspace scripts or python -c code.",
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
        message = "Unsupported workspace command: ${argv.first()}. Supported now: python, python3, and experimental groovy. Android shell, npm, and git are not enabled through this tool yet.",
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

    val result = adapter.execute(argv, args)
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
  fun execute(argv: List<String>, args: WorkspaceCommandRunTool.Args): PythonRunResult
}

private class PythonCommandAdapter(
  private val workspace: WorkspaceManager,
  private val networkEnabled: Boolean,
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

  override fun execute(argv: List<String>, args: WorkspaceCommandRunTool.Args): PythonRunResult {
    if (argv.size < 2) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Python command requires a script path or -c code, for example: python tools/check.py\n")
    }

    val python = FloveraPythonRuntime(workspace, networkEnabled)
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
      )
    }
  }

  private fun pythonCommandCode(code: String, scriptArgs: List<String>): String {
    val encodedArgs = FloveraPythonRuntime.json.encodeToString(scriptArgs)
    return """
      import sys as _flovera_sys
      _flovera_sys.argv = ["-c"] + $encodedArgs
      del _flovera_sys
      $code
    """.trimIndent()
  }
}

private class GroovyCommandAdapter(
  private val workspace: WorkspaceManager,
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

  override fun execute(argv: List<String>, args: WorkspaceCommandRunTool.Args): PythonRunResult {
    if (argv.size < 2) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Groovy command requires a workspace script path, for example: groovy tools/hello.groovy\n")
    }
    if (argv[1].startsWith("-")) {
      return PythonRunResult(status = "error", exitCode = 1, stderr = "Unsupported groovy option: ${argv[1]}. Supported now: groovy script.groovy [args...]\n")
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
    val executor = Executors.newSingleThreadExecutor()
    val future = executor.submit(Callable {
      runGroovyScript(
        scriptFile = scriptFile,
        scriptArgs = argv.drop(2),
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
    return buildString {
      if (existingStderr.isNotBlank()) appendLine(existingStderr.trimEnd())
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

  private fun runGroovyScript(
    scriptFile: File,
    scriptArgs: List<String>,
    stdout: ByteArrayOutputStream,
    stderr: ByteArrayOutputStream,
  ): Any? {
    normalizeGroovyAndroidJavaVersion()
    val artifacts = JvmArtifactManager(workspace)
    val workspaceJars = artifacts.workspaceJars()
    val libraryDex = artifacts.ensureLibraryDex(workspaceJars)
    val scriptDex = artifacts.ensureGroovyScriptDex(scriptFile, workspaceJars, libraryDex)
    val outWriter = PrintWriter(stdout.writer(StandardCharsets.UTF_8), true)
    val errWriter = PrintWriter(stderr.writer(StandardCharsets.UTF_8), true)
    val binding = Binding().apply {
      setVariable("args", scriptArgs.toTypedArray())
      setVariable("argv", scriptArgs)
      setVariable("ctx", FloveraGroovyContext(workspace, outWriter))
      setVariable("out", outWriter)
      setVariable("err", errWriter)
    }
    val dexPath = listOf(scriptDex.dexFile.absolutePath, libraryDex?.dexFile?.absolutePath)
      .filterNotNull()
      .joinToString(File.pathSeparator)
    val loader = DexClassLoader(dexPath, scriptDex.optimizedDir.absolutePath, null, javaClass.classLoader)
    val scriptClass = loader.loadClass(scriptDex.mainClassName).asSubclass(Script::class.java)
    val script = scriptClass.getDeclaredConstructor().newInstance()
    script.binding = binding
    return script.run()
  }

  private fun normalizeGroovyAndroidJavaVersion() {
    System.setProperty("java.specification.version", "1.8")
  }
}

private class JvmArtifactManager(
  private val workspace: WorkspaceManager,
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

  fun ensureLibraryDex(jars: List<JvmJarArtifact>): JvmLibraryDex? {
    if (jars.isEmpty()) return null
    val key = sha256(
      buildString {
        appendLine("flovera-jvm-library-dex-v1")
        appendLine("api=${Build.VERSION.SDK_INT}")
        jars.forEach { jar ->
          appendLine("${jar.relativePath}:${jar.sha256}:${jar.sizeBytes}")
        }
      }.toByteArray(StandardCharsets.UTF_8),
    )
    val dexDir = File(cacheRoot, "libs/$key/dex")
    val optimizedDir = File(cacheRoot, "libs/$key/optimized")
    val dexFile = File(dexDir, "classes.dex")
    if (!dexFile.isFile) {
      dexDir.mkdirs()
      optimizedDir.mkdirs()
      val command = D8Command.builder()
        .setMode(CompilationMode.DEBUG)
        .setMinApiLevel(Build.VERSION.SDK_INT)
        .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
        .addProgramFiles(jars.map { it.file.toPath() })
        .build()
      D8.run(command)
      markDexReadOnly(dexDir)
      File(dexDir.parentFile, "artifacts.txt").writeText(
        jars.joinToString("\n") { "${it.relativePath} ${it.sha256} ${it.sizeBytes}" },
        StandardCharsets.UTF_8,
      )
    }
    return JvmLibraryDex(dexFile = dexFile, optimizedDir = optimizedDir, key = key)
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
    val optimizedDir = File(cacheDir, "optimized")
    val dexFile = File(dexDir, "classes.dex")
    val mainClassFile = File(cacheDir, "main-class.txt")
    if (!dexFile.isFile || !mainClassFile.isFile) {
      classDir.deleteRecursively()
      dexDir.deleteRecursively()
      optimizedDir.mkdirs()
      classDir.mkdirs()
      dexDir.mkdirs()
      compileGroovyToClasses(scriptFile, classDir, libraryDex)
      val mainClassName = findGroovyScriptClassName(scriptFile, classDir)
      compileClassesToDex(classDir, dexDir, jars)
      markDexReadOnly(dexDir)
      mainClassFile.writeText(mainClassName, StandardCharsets.UTF_8)
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
        libraryDex.dexFile.absolutePath,
        libraryDex.optimizedDir.absolutePath,
        null,
        javaClass.classLoader,
      )
    }
    val groovyClassLoader = GroovyClassLoader(parentLoader, configuration, false)
    val compilationUnit = CompilationUnit(configuration, null, groovyClassLoader)
    FileSystemCompiler(configuration, compilationUnit).compile(arrayOf(scriptFile.absolutePath))
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

  private fun isInsideWorkspace(file: File): Boolean {
    return file.path == root.path || file.path.startsWith(root.path + File.separator)
  }
}

private data class JvmJarArtifact(
  val file: File,
  val relativePath: String,
  val sha256: String,
  val sizeBytes: Long,
)

private data class JvmLibraryDex(
  val dexFile: File,
  val optimizedDir: File,
  val key: String,
)

private data class GroovyScriptDex(
  val dexFile: File,
  val optimizedDir: File,
  val mainClassName: String,
  val key: String,
)

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

private data class BoundedText(
  val text: String,
  val truncated: Boolean,
)

private fun boundedText(stream: ByteArrayOutputStream, maxChars: Int): BoundedText {
  val text = stream.toString(StandardCharsets.UTF_8.name())
  if (text.length <= maxChars) return BoundedText(text, false)
  return BoundedText(text.take(maxChars), true)
}

private fun sha256(bytes: ByteArray): String {
  return MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
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
