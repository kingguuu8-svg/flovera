package com.example.ailinuxvmspike

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
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
import java.util.concurrent.atomic.AtomicReference

data class VmUiState(
  val assetsPrepared: Boolean = false,
  val vmRunning: Boolean = false,
  val vmExitCode: Int? = null,
  val logText: String = "AI Linux VM Spike ready.\n",
)

data class VmInputs(
  val qemuBinary: File,
  val firmwareImage: File,
  val kernelImage: File,
  val initramfs: File,
  val sshPrivateKey: File,
  val sshPort: Int = 2222,
  val qemuMemoryMb: Int = 768,
)

private const val ASSET_TEMPLATE_DIR = "spike"
private const val RUNTIME_ROOT = "ai-linux-spike"
private const val BUNDLED_QEMU_NAME = "libqemu-system-aarch64.so"

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
      appendLog("VM is already running.")
      return@launch
    }

    val missing = validateInputs(inputs)
    if (missing.isNotEmpty()) {
      appendLog("VM start blocked.")
      missing.forEach { appendLog("Missing: $it") }
      appendLog("This is expected on emulator until the external firmware/kernel/initramfs/key inputs are copied.")
      updateState { it.copy(vmRunning = false, vmExitCode = null) }
      return@launch
    }

    withContext(Dispatchers.IO) {
      if (!inputs.qemuBinary.canExecute()) {
        appendLog("VM start blocked: QEMU binary is still not executable at ${inputs.qemuBinary.absolutePath}")
        updateState { it.copy(vmRunning = false, vmExitCode = null) }
        return@withContext
      }

      inputsDir.mkdirs()
      val builder = ProcessBuilder(launchCommand)
      builder.directory(inputsDir)
      builder.redirectErrorStream(false)
      appendLog("Launching VM:")
      appendLog("Executable: ${inputs.qemuBinary.absolutePath}")
      appendLog("Working dir: ${inputsDir.absolutePath}")
      appendLog(launchCommand.joinToString(" "))

      try {
        val process = builder.start()
        processRef.set(process)
        updateState { it.copy(vmRunning = true, vmExitCode = null) }
        pumpStream("stdout", process.inputStream)
        pumpStream("stderr", process.errorStream)
        monitorProcess(process)
        appendLog("VM process started.")
      } catch (exception: IOException) {
        appendLog("Failed to start VM: ${exception.message}")
        updateState { it.copy(vmRunning = false, vmExitCode = null) }
      }
    }
  }

  fun stopVm() = scope.launch {
    val process = processRef.getAndSet(null)
    if (process == null) {
      appendLog("Stop requested, but no VM process is running.")
      updateState { it.copy(vmRunning = false) }
      return@launch
    }

    withContext(Dispatchers.IO) {
      appendLog("Stopping VM process.")
      process.destroy()
      val exitedGracefully = withTimeoutOrNull(5_000) {
        while (process.isAlive) {
          delay(100)
        }
        true
      } == true
      if (!exitedGracefully && process.isAlive) {
        appendLog("VM did not stop cleanly; forcing termination.")
        process.destroyForcibly()
      }
      val exitCode = runCatching { process.exitValue() }.getOrNull()
      updateState { it.copy(vmRunning = false, vmExitCode = exitCode) }
      appendLog("VM stopped. exitCode=${exitCode ?: "unknown"}")
    }
  }

  fun runEchoReady() = scope.launch {
    val inputs = buildInputs()
    val processAlive = processRef.get()?.isAlive == true
    if (!processAlive) {
      appendLog("Run echo ready blocked: the VM is not running.")
      return@launch
    }
    if (!inputs.sshPrivateKey.isFile) {
      appendLog("Run echo ready blocked: missing SSH key at ${inputs.sshPrivateKey.absolutePath}")
      return@launch
    }

    withContext(Dispatchers.IO) {
      val output = ByteArrayOutputStream()
      val errors = ByteArrayOutputStream()
      val session = try {
        val jsch = JSch().apply { installJschLogger() }
        loadSshIdentity(jsch, inputs.sshPrivateKey)
        jsch.getSession("root", "127.0.0.1", inputs.sshPort).apply {
          setConfig("StrictHostKeyChecking", "no")
          setConfig("PreferredAuthentications", "publickey")
          timeout = 10_000
          connect(10_000)
        }
      } catch (exception: Exception) {
        appendLog("SSH setup failed: ${exception.message}")
        return@withContext
      }

      try {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand("echo ready")
        channel.setOutputStream(output)
        channel.setErrStream(errors)
        channel.connect(10_000)
        while (!channel.isClosed) {
          delay(100)
        }
        val stdout = output.toString(Charsets.UTF_8.name()).trim()
        val stderr = errors.toString(Charsets.UTF_8.name()).trim()
        appendLog("Run echo ready exit=${channel.exitStatus}")
        if (stdout.isNotEmpty()) {
          appendLog("stdout: $stdout")
        }
        if (stderr.isNotEmpty()) {
          appendLog("stderr: $stderr")
        }
        if (stdout == "ready") {
          appendLog("Ready probe succeeded.")
        } else {
          appendLog("Ready probe did not return the expected token.")
        }
      } catch (exception: Exception) {
        appendLog("Run echo ready failed: ${exception.message}")
      } finally {
        session.disconnect()
      }
    }
  }

  private suspend fun monitorProcess(process: Process) {
    scope.launch(Dispatchers.IO) {
      val exitCode = process.waitFor()
      if (processRef.compareAndSet(process, null)) {
        updateState { it.copy(vmRunning = false, vmExitCode = exitCode) }
        appendLog("VM process exited. exitCode=$exitCode")
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
      This directory is created by Prepare Assets.

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
