package com.example.ailinuxvmspike

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class LinuxStatus {
  Stopped,
  Starting,
  Running,
  Paused,
  Stopping,
  Error,
}

data class VmUiState(
  val assetsPrepared: Boolean = false,
  val linuxStatus: LinuxStatus = LinuxStatus.Stopped,
  val vmExitCode: Int? = null,
  val terminalCommand: String = "echo ready",
  val logText: String = "AI Linux VM Spike ready.\n",
)

data class VmInputs(
  val qemuBinary: File,
  val firmwareImage: File,
  val kernelImage: File,
  val initramfs: File,
  val sshPrivateKey: File,
  val sshPort: Int = 2222,
  val qmpPort: Int = 4444,
  val qemuMemoryMb: Int = 768,
)

private const val ASSET_TEMPLATE_DIR = "spike"
private const val RUNTIME_ROOT = "ai-linux-spike"
private const val BUNDLED_QEMU_NAME = "libqemu-system-aarch64.so"
private const val SSH_CONNECT_TIMEOUT_MS = 10_000
private const val SSH_READY_TIMEOUT_MS = 90_000L
private const val SSH_RETRY_DELAY_MS = 2_000L

class VmController(
  private val context: Context,
  private val scope: CoroutineScope = CoroutineScope(Job() + Dispatchers.Main.immediate),
) : Closeable {
  private val stateFlow = MutableStateFlow(VmUiState())
  val state: StateFlow<VmUiState> = stateFlow.asStateFlow()
  private val nativeLibraryDir = File(
    requireNotNull(context.applicationInfo.nativeLibraryDir) {
      "Application nativeLibraryDir is not available."
    },
  )
  private val baseDir = File(context.filesDir, RUNTIME_ROOT)
  private val assetsDir = File(baseDir, "released-assets")
  private val inputsDir = File(baseDir, "inputs")
  private val logsDir = File(baseDir, "logs")
  private val processRef = AtomicReference<Process?>(null)

  fun prepareAssets() = scope.launch {
    withContext(Dispatchers.IO) {
      baseDir.mkdirs()
      assetsDir.mkdirs()
      inputsDir.mkdirs()
      logsDir.mkdirs()

      copyBundledAsset("inputs-template.txt", File(assetsDir, "inputs-template.txt"))
      val launchTemplate = File(assetsDir, "qemu-launch-template.sh")
      copyBundledAsset("qemu-launch-template.sh", launchTemplate)
      launchTemplate.setExecutable(true, true)
      writeReleaseNotes(File(assetsDir, "README.txt"))
    }
    appendLog(
      "Prepared assets under ${assetsDir.absolutePath}",
      "Bundled QEMU executable is expected at ${File(nativeLibraryDir, BUNDLED_QEMU_NAME).absolutePath}",
      "Runtime inputs live in ${inputsDir.absolutePath}: QEMU_EFI.fd, vmlinuz-virt, ai-linux-aarch64.cpio.gz, id_ed25519",
      "Runtime libraries must be co-located with QEMU in ${nativeLibraryDir.absolutePath} with RUNPATH=\$ORIGIN.",
    )
    updateState { it.copy(assetsPrepared = true) }
  }

  fun startVm() = scope.launch {
    val inputs = buildInputs()
    val launchCommand = buildQemuCommand(inputs)

    if (processRef.get()?.isAlive == true) {
      appendLog("Linux is already running.")
      return@launch
    }

    val missing = validateInputs(inputs)
    if (missing.isNotEmpty()) {
      appendLog("Start Linux blocked.")
      missing.forEach { appendLog("Missing: $it") }
      appendLog("This is expected on emulator until the external firmware/kernel/initramfs/key inputs are copied.")
      updateState { it.copy(linuxStatus = LinuxStatus.Stopped, vmExitCode = null) }
      return@launch
    }

    withContext(Dispatchers.IO) {
      if (!inputs.qemuBinary.canExecute()) {
        appendLog("Start Linux blocked: QEMU binary is still not executable at ${inputs.qemuBinary.absolutePath}")
        updateState { it.copy(linuxStatus = LinuxStatus.Stopped, vmExitCode = null) }
        return@withContext
      }

      inputsDir.mkdirs()
      val builder = ProcessBuilder(launchCommand)
      builder.directory(inputsDir)
      builder.redirectErrorStream(false)
      appendLog("Starting Linux:")
      appendLog("Executable: ${inputs.qemuBinary.absolutePath}")
      appendLog("Working dir: ${inputsDir.absolutePath}")
      appendLog("SSH terminal port: ${inputs.sshPort}")
      appendLog("QMP control port: ${inputs.qmpPort}")
      appendLog(launchCommand.joinToString(" "))

      try {
        updateState { it.copy(linuxStatus = LinuxStatus.Starting, vmExitCode = null) }
        val process = builder.start()
        processRef.set(process)
        updateState { it.copy(linuxStatus = LinuxStatus.Running, vmExitCode = null) }
        pumpStream("stdout", process.inputStream)
        pumpStream("stderr", process.errorStream)
        monitorProcess(process)
        appendLog("Linux process started.")
      } catch (exception: IOException) {
        appendLog("Failed to start Linux: ${exception.message}")
        updateState { it.copy(linuxStatus = LinuxStatus.Error, vmExitCode = null) }
      }
    }
  }

  fun stopVm() = scope.launch {
    val process = processRef.get()
    if (process == null) {
      appendLog("Shutdown requested, but Linux is not running.")
      updateState { it.copy(linuxStatus = LinuxStatus.Stopped) }
      return@launch
    }

    withContext(Dispatchers.IO) {
      try {
        appendLog("Shutting down Linux process.")
        updateState { it.copy(linuxStatus = LinuxStatus.Stopping) }

        val qmpQuitRequested = runCatching {
          qmpExecute(buildInputs().qmpPort, "quit")
        }.onSuccess { response ->
          appendLog("Shutdown requested through QMP quit.")
          appendLog("QMP response: $response")
        }.onFailure { exception ->
          appendLog("QMP quit failed: ${exception.message}")
          appendLog("Falling back to process termination.")
        }.isSuccess

        var exited = waitForProcessExit(process, 5_000)
        if (!exited && process.isAlive) {
          val action = if (qmpQuitRequested) {
            "Linux did not exit after QMP quit; terminating process."
          } else {
            "Linux did not stop cleanly; terminating process."
          }
          appendLog(action)
          process.destroy()
          exited = waitForProcessExit(process, 3_000)
        }

        if (!exited && process.isAlive) {
          appendLog("Linux did not stop after terminate; forcing termination.")
          process.destroyForcibly()
          waitForProcessExit(process, 5_000)
        }
      } catch (exception: Exception) {
        appendLog("Shutdown cleanup failed: ${exception.message}")
        if (process.isAlive) {
          process.destroyForcibly()
          waitForProcessExit(process, 5_000)
        }
      } finally {
        processRef.compareAndSet(process, null)
        val exitCode = runCatching { process.exitValue() }.getOrNull()
        updateState { it.copy(linuxStatus = LinuxStatus.Stopped, vmExitCode = exitCode) }
        appendLog("Linux stopped. exitCode=${exitCode ?: "unknown"}")
      }
    }
  }

  fun pauseLinux() = scope.launch {
    if (processRef.get()?.isAlive != true) {
      appendLog("Pause blocked: Linux is not running.")
      return@launch
    }
    withContext(Dispatchers.IO) {
      runCatching {
        qmpExecute(buildInputs().qmpPort, "stop")
      }.onSuccess { response ->
        appendLog("Pause requested through QMP.")
        appendLog("QMP response: $response")
        updateState { it.copy(linuxStatus = LinuxStatus.Paused) }
      }.onFailure { exception ->
        appendLog("Pause failed: ${exception.message}")
      }
    }
  }

  fun resumeLinux() = scope.launch {
    if (processRef.get()?.isAlive != true) {
      appendLog("Resume blocked: Linux is not running.")
      return@launch
    }
    withContext(Dispatchers.IO) {
      runCatching {
        qmpExecute(buildInputs().qmpPort, "cont")
      }.onSuccess { response ->
        appendLog("Resume requested through QMP.")
        appendLog("QMP response: $response")
        updateState { it.copy(linuxStatus = LinuxStatus.Running) }
      }.onFailure { exception ->
        appendLog("Resume failed: ${exception.message}")
      }
    }
  }

  fun updateTerminalCommand(command: String) {
    stateFlow.update { it.copy(terminalCommand = command) }
  }

  fun runEchoReady() = scope.launch {
    runTerminalCommand("echo ready").join()
  }

  fun runTerminalCommand(command: String = stateFlow.value.terminalCommand) = scope.launch {
    val normalizedCommand = command.trim()
    if (normalizedCommand.isEmpty()) {
      appendLog("Terminal command blocked: command is empty.")
      return@launch
    }
    val inputs = buildInputs()
    val processAlive = processRef.get()?.isAlive == true
    if (!processAlive) {
      appendLog("Terminal command blocked: Linux is not running.")
      return@launch
    }
    if (!inputs.sshPrivateKey.isFile) {
      appendLog("Terminal command blocked: missing SSH key at ${inputs.sshPrivateKey.absolutePath}")
      return@launch
    }

    withContext(Dispatchers.IO) {
      val output = ByteArrayOutputStream()
      val errors = ByteArrayOutputStream()
      val session = openSshSessionWhenReady(inputs) ?: return@withContext

      try {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(normalizedCommand)
        channel.setOutputStream(output)
        channel.setErrStream(errors)
        channel.connect(SSH_CONNECT_TIMEOUT_MS)
        while (!channel.isClosed) {
          delay(100)
        }
        val stdout = output.toString(Charsets.UTF_8.name()).trim()
        val stderr = errors.toString(Charsets.UTF_8.name()).trim()
        appendLog("$ $normalizedCommand")
        appendLog("terminal exit=${channel.exitStatus}")
        if (stdout.isNotEmpty()) {
          appendLog("stdout: $stdout")
        }
        if (stderr.isNotEmpty()) {
          appendLog("stderr: $stderr")
        }
        if (stdout == "ready") {
          appendLog("Ready probe succeeded.")
        }
      } catch (exception: Exception) {
        appendLog("Terminal command failed: ${exception.message}")
      } finally {
        session.disconnect()
      }
    }
  }

  private suspend fun openSshSessionWhenReady(inputs: VmInputs): Session? {
    val jsch = JSch().apply { installJschLogger() }
    loadSshIdentity(jsch, inputs.sshPrivateKey)
    appendLog("Waiting for SSH terminal on 127.0.0.1:${inputs.sshPort}.")

    val deadline = System.currentTimeMillis() + SSH_READY_TIMEOUT_MS
    var attempt = 1
    var lastError = "unknown"
    while (System.currentTimeMillis() < deadline && processRef.get()?.isAlive == true) {
      val session = jsch.getSession("root", "127.0.0.1", inputs.sshPort).apply {
        setConfig("StrictHostKeyChecking", "no")
        setConfig("PreferredAuthentications", "publickey")
        timeout = SSH_CONNECT_TIMEOUT_MS
      }
      try {
        session.connect(SSH_CONNECT_TIMEOUT_MS)
        if (attempt > 1) {
          appendLog("SSH terminal connected after $attempt attempts.")
        }
        return session
      } catch (exception: Exception) {
        session.disconnect()
        lastError = exception.message ?: exception::class.java.simpleName
        if (attempt == 1) {
          appendLog("SSH terminal is not ready yet: $lastError")
        }
        attempt += 1
        delay(SSH_RETRY_DELAY_MS)
      }
    }

    appendLog("SSH setup failed after waiting: $lastError")
    return null
  }

  private suspend fun monitorProcess(process: Process) {
    scope.launch(Dispatchers.IO) {
      val exitCode = process.waitFor()
      if (processRef.compareAndSet(process, null)) {
        updateState { it.copy(linuxStatus = LinuxStatus.Stopped, vmExitCode = exitCode) }
        appendLog("Linux process exited. exitCode=$exitCode")
      }
    }
  }

  private suspend fun pumpStream(label: String, inputStream: java.io.InputStream) {
    scope.launch(Dispatchers.IO) {
      inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
          appendLog("[$label] $line")
        }
      }
    }
  }

  private suspend fun buildInputs(): VmInputs {
    val qemuBinary = File(nativeLibraryDir, BUNDLED_QEMU_NAME)
    val firmwareImage = File(inputsDir, "QEMU_EFI.fd")
    val kernelImage = File(inputsDir, "vmlinuz-virt")
    val initramfs = File(inputsDir, "ai-linux-aarch64.cpio.gz")
    val sshPrivateKey = File(inputsDir, "id_ed25519")
    return VmInputs(
      qemuBinary = qemuBinary,
      firmwareImage = firmwareImage,
      kernelImage = kernelImage,
      initramfs = initramfs,
      sshPrivateKey = sshPrivateKey,
    )
  }

  private fun validateInputs(inputs: VmInputs): List<String> = buildList {
    if (!inputs.qemuBinary.isFile) add("Bundled QEMU executable: ${inputs.qemuBinary.absolutePath}")
    if (inputs.qemuBinary.isFile && !inputs.qemuBinary.canExecute()) {
      add("QEMU executable is not executable: ${inputs.qemuBinary.absolutePath}")
    }
    if (!inputs.firmwareImage.isFile) add("UEFI firmware: ${inputs.firmwareImage.absolutePath}")
    if (!inputs.kernelImage.isFile) add("Kernel image: ${inputs.kernelImage.absolutePath}")
    if (!inputs.initramfs.isFile) add("Initramfs: ${inputs.initramfs.absolutePath}")
  }

  private fun buildQemuCommand(inputs: VmInputs): List<String> = listOf(
    inputs.qemuBinary.absolutePath,
    "-machine",
    "virt,accel=tcg",
    "-cpu",
    "cortex-a57",
    "-m",
    "${inputs.qemuMemoryMb}M",
    "-smp",
    "1",
    "-no-reboot",
    "-bios",
    inputs.firmwareImage.absolutePath,
    "-display",
    "none",
    "-serial",
    "stdio",
    "-qmp",
    "tcp:127.0.0.1:${inputs.qmpPort},server=on,wait=off",
    "-kernel",
    inputs.kernelImage.absolutePath,
    "-initrd",
    inputs.initramfs.absolutePath,
    "-append",
    "console=ttyAMA0 earlycon panic=1 rdinit=/usr/local/sbin/ai-vm-init",
    "-netdev",
    "user,id=net0,hostfwd=tcp:127.0.0.1:${inputs.sshPort}-:22",
    "-device",
    "virtio-net-pci,netdev=net0,romfile=",
  )

  private fun qmpExecute(port: Int, command: String): String {
    Socket("127.0.0.1", port).use { socket ->
      socket.soTimeout = 10_000
      val reader = socket.getInputStream().bufferedReader()
      val writer = socket.getOutputStream().bufferedWriter()
      reader.readLine() ?: error("QMP greeting was empty")
      writer.write("""{"execute":"qmp_capabilities"}""")
      writer.write("\r\n")
      writer.flush()
      reader.readLine() ?: error("QMP capabilities response was empty")
      writer.write("""{"execute":"$command"}""")
      writer.write("\r\n")
      writer.flush()
      return reader.readLine() ?: error("QMP $command response was empty")
    }
  }

  private fun waitForProcessExit(process: Process, timeoutMs: Long): Boolean =
    runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(!process.isAlive)

  private suspend fun appendLog(vararg lines: String) {
    withContext(Dispatchers.Main.immediate) {
      stateFlow.update { current ->
        val builder = StringBuilder(current.logText)
        lines.forEach { line ->
          builder.append(line).append('\n')
        }
        current.copy(logText = builder.toString())
      }
    }
  }

  private suspend fun updateState(update: (VmUiState) -> VmUiState) {
    withContext(Dispatchers.Main.immediate) {
      stateFlow.update(update)
    }
  }

  private fun installJschLogger() {
    JSch.setLogger(object : Logger {
      override fun isEnabled(level: Int): Boolean = true

      override fun log(level: Int, message: String) {
        scope.launch(Dispatchers.IO) {
          appendLog("[jsch/${jschLogLevelName(level)}] $message")
        }
      }
    })
  }

  private fun jschLogLevelName(level: Int): String = when (level) {
    Logger.DEBUG -> "DEBUG"
    Logger.INFO -> "INFO"
    Logger.WARN -> "WARN"
    Logger.ERROR -> "ERROR"
    Logger.FATAL -> "FATAL"
    else -> level.toString()
  }

  private suspend fun loadSshIdentity(jsch: JSch, keyFile: File) {
    val keyBytes = keyFile.readBytes()
    val keyHeader = keyFile.bufferedReader().use { reader ->
      reader.lineSequence().firstOrNull()?.trim().orEmpty()
    }
    appendLog("Loading SSH identity from ${keyFile.absolutePath}")
    appendLog("SSH key size=${keyBytes.size} bytes")
    if (keyHeader.isNotEmpty()) {
      appendLog("SSH key header: $keyHeader")
    }

    runCatching {
      jsch.addIdentity(keyFile.name, keyBytes, null, null)
    }.onSuccess {
      appendLog("SSH identity loaded from bytes.")
      return
    }.onFailure { exception ->
      appendLog("SSH identity load from bytes failed: ${exception.message}")
    }

    jsch.addIdentity(keyFile.absolutePath)
    appendLog("SSH identity loaded from path fallback.")
  }

  private suspend fun copyBundledAsset(assetName: String, destination: File) {
    context.assets.open("$ASSET_TEMPLATE_DIR/$assetName").use { input ->
      destination.outputStream().use { output ->
        input.copyTo(output)
      }
    }
  }

  private fun writeReleaseNotes(destination: File) {
    destination.writeText(
      """
      This directory is created by Prepare Linux.

      QEMU executable is expected here:
      ${File(nativeLibraryDir, BUNDLED_QEMU_NAME).absolutePath}

      Runtime libraries must live alongside the QEMU executable in nativeLibraryDir.
      Expected RUNPATH: ${'$'}ORIGIN

      Put the remaining Android spike inputs into:
      ${inputsDir.absolutePath}

      Required names:
      - QEMU_EFI.fd
      - vmlinuz-virt
      - ai-linux-aarch64.cpio.gz
      - id_ed25519

      QMP control port:
      - 127.0.0.1:4444

      SSH terminal port:
      - 127.0.0.1:2222

      The emulator stage is allowed to stop here with a clear error if these files are absent.
      """.trimIndent(),
    )
  }

  override fun close() {
    processRef.getAndSet(null)?.let { process ->
      if (process.isAlive) {
        process.destroy()
        process.destroyForcibly()
      }
    }
    scope.cancel()
  }
}
