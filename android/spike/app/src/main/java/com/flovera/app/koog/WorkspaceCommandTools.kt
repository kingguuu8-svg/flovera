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
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.Serializable
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
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.tools.FileSystemCompiler
import org.w3c.dom.Element

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
    GroovyCommandAdapter(workspace, networkEnabled),
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
    val normalizedCode = code.trimStart('\n', '\r')
    return "import sys as _flovera_sys\n" +
      "_flovera_sys.argv = [\"-c\"] + $encodedArgs\n" +
      "del _flovera_sys\n" +
      normalizedCode
  }
}

private class GroovyCommandAdapter(
  private val workspace: WorkspaceManager,
  private val networkEnabled: Boolean,
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
    val workspaceJars = artifacts.workspaceJars() + artifacts.mavenJars(networkEnabled)
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
    return FloveraGroovyFile.withWorkspaceRoot(workspace.root) { script.run() }
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

  fun mavenJars(networkEnabled: Boolean): List<JvmJarArtifact> {
    val request = readMavenRequest() ?: return emptyList()
    val resolver = MavenArtifactResolver(
      repositoryRoot = File(cacheRoot, "maven/repository"),
      repositories = request.repositories,
      networkEnabled = networkEnabled,
    )
    return resolver.resolve(request.dependencies).map { file ->
      val canonical = file.canonicalFile
      JvmJarArtifact(
        file = canonical,
        relativePath = ".flovera/runtime/jvm-artifacts/maven/repository/${canonical.relativeTo(File(cacheRoot, "maven/repository").canonicalFile).invariantSeparatorsPath}",
        sha256 = sha256(canonical.readBytes()),
        sizeBytes = canonical.length(),
      )
    }
  }

  private fun readMavenRequest(): MavenRequest? {
    val configFiles = listOf(
      File(root, "libs/maven.json"),
      File(root, ".flovera/jvm/maven.json"),
    ).map { it.canonicalFile }.filter { it.isFile && isInsideWorkspace(it) }
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
      compileGroovyToClasses(compileSource, classDir, libraryDex)
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
      ensureArtifactFile(coordinate, "pom")
      resolved[coordinate.key] = coordinate
      parsePom(coordinate).dependencies.forEach { dependency ->
        if (dependency.key !in resolved) queue.add(dependency)
      }
    }
    return resolved.values.map { coordinate -> ensureArtifactFile(coordinate, "jar") }
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

private fun sha256(bytes: ByteArray): String {
  return MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
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
