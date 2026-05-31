package com.flovera.app.workspace

import com.flovera.app.config.AppSettings
import com.flovera.app.koog.ModelProviderCatalog
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

class WorkspaceLocalAppServer(
  private val workspace: WorkspaceManager,
  private val settingsProvider: () -> AppSettings,
) : AutoCloseable {
  private val lock = Any()
  private val running = AtomicBoolean(false)

  @Volatile
  private var serverSocket: ServerSocket? = null

  @Volatile
  private var port: Int = 0

  fun workspaceFileUrl(path: String): String {
    ensureStarted()
    return "$origin/$WORKSPACE_ROUTE/${encodePath(path)}"
  }

  fun rootUrl(): String {
    ensureStarted()
    return "$origin/"
  }

  private val origin: String
    get() = "http://127.0.0.1:$port"

  private fun ensureStarted() {
    if (serverSocket != null) return
    synchronized(lock) {
      if (serverSocket != null) return
      val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", 0))
      }
      port = socket.localPort
      serverSocket = socket
      running.set(true)
      thread(
        start = true,
        isDaemon = true,
        name = "FloveraWorkspaceLocalAppServer",
      ) {
        acceptLoop(socket)
      }
    }
  }

  private fun acceptLoop(socket: ServerSocket) {
    while (running.get()) {
      val client = runCatching { socket.accept() }.getOrNull() ?: break
      thread(
        start = true,
        isDaemon = true,
        name = "FloveraWorkspaceLocalAppClient",
      ) {
        client.use { handleClient(it) }
      }
    }
  }

  private fun handleClient(client: Socket) {
    client.soTimeout = CLIENT_READ_TIMEOUT_MS
    val input = BufferedInputStream(client.getInputStream())
    val output = client.getOutputStream()
    val requestLine = readAsciiLine(input)?.takeIf { it.isNotBlank() } ?: return
    val parts = requestLine.split(' ')
    if (parts.size < 3) {
      writeText(output, 400, "Bad Request", "Malformed HTTP request.")
      return
    }
    val method = parts[0].uppercase(Locale.US)
    val target = parts[1].substringBefore('?')
    val headers = readHeaders(input)
    val body = readRequestBody(input, headers["content-length"]?.toIntOrNull() ?: 0)

    runCatching {
      when {
        method == "OPTIONS" -> writePreflight(output)
        method == "GET" && target == "/$API_ROUTE/health" -> writeHealth(output)
        method == "POST" && target == "/$API_ROUTE/deepseek/stream" -> streamDeepSeek(output, body)
        method == "GET" && target.startsWith("/$WORKSPACE_ROUTE/") -> serveWorkspaceFile(output, target.removePrefix("/$WORKSPACE_ROUTE/"))
        else -> writeText(output, 404, "Not Found", "No local workspace route: $target")
      }
    }.getOrElse { error ->
      if (!client.isClosed) {
        writeText(output, 500, "Internal Server Error", error.message ?: error::class.java.simpleName)
      }
    }
  }

  private fun serveWorkspaceFile(output: OutputStream, encodedPath: String) {
    val relativePath = decodePath(encodedPath).trimStart('/').replace('\\', '/')
    if (relativePath.isBlank() || relativePath == ".flovera" || relativePath.startsWith(".flovera/")) {
      writeText(output, 403, "Forbidden", "This workspace path is not exposed through the local app server.")
      return
    }
    val file = runCatching { workspace.exportableFile(relativePath) }.getOrNull()
    if (file == null || !file.isFile) {
      writeText(output, 404, "Not Found", "Workspace file not found: $relativePath")
      return
    }
    val mimeType = workspace.mimeType(relativePath)
    writeHeaders(output, 200, "OK", contentType = mimeType, contentLength = file.length())
    file.inputStream().use { it.copyTo(output) }
  }

  private fun writeHealth(output: OutputStream) {
    val settings = settingsProvider()
    val provider = ModelProviderCatalog.findProvider(DEEPSEEK_PROVIDER_ID) ?: ModelProviderCatalog.defaultProvider
    val profile = ModelProviderCatalog.runtimeProfileFor(provider, settings)
    writeJson(
      output,
      200,
      JSONObject()
        .put("ok", true)
        .put("runtime", "flovera-local-http")
        .put("provider", DEEPSEEK_PROVIDER_ID)
        .put("baseUrl", profile.baseUrl)
        .put("model", deepSeekModel(settings))
        .put("hasApiKey", settings.apiKeyFor(DEEPSEEK_PROVIDER_ID).isNotBlank()),
    )
  }

  private fun streamDeepSeek(output: OutputStream, body: ByteArray) {
    writeSseHeaders(output)
    val settings = settingsProvider()
    val apiKey = settings.apiKeyFor(DEEPSEEK_PROVIDER_ID).trim()
    if (apiKey.isBlank()) {
      writeSseEvent(output, "error", JSONObject().put("message", "DeepSeek API key is not configured in Flovera settings."))
      writeSseDone(output)
      return
    }

    val incoming = runCatching {
      JSONObject(String(body, StandardCharsets.UTF_8).ifBlank { "{}" })
    }.getOrDefault(JSONObject())
    val messages = incoming.optJSONArray("messages") ?: JSONArray()
    if (messages.length() == 0) {
      writeSseEvent(output, "error", JSONObject().put("message", "Request must include a non-empty messages array."))
      writeSseDone(output)
      return
    }

    val requestBody = JSONObject()
      .put("model", incoming.optString("model").ifBlank { deepSeekModel(settings) })
      .put("messages", messages)
      .put("stream", true)
    if (incoming.has("temperature")) requestBody.put("temperature", incoming.optDouble("temperature"))
    if (incoming.has("max_tokens")) requestBody.put("max_tokens", incoming.optInt("max_tokens"))

    val provider = ModelProviderCatalog.findProvider(DEEPSEEK_PROVIDER_ID) ?: ModelProviderCatalog.defaultProvider
    val profile = ModelProviderCatalog.runtimeProfileFor(provider, settings)
    val endpoint = chatCompletionsEndpoint(profile.baseUrl)
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = DEEPSEEK_CONNECT_TIMEOUT_MS
      readTimeout = DEEPSEEK_READ_TIMEOUT_MS
      doOutput = true
      setRequestProperty("Authorization", "Bearer $apiKey")
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "text/event-stream")
    }

    runCatching {
      connection.outputStream.use { stream ->
        stream.write(requestBody.toString().toByteArray(StandardCharsets.UTF_8))
      }
      val responseCode = connection.responseCode
      val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
      if (responseCode !in 200..299) {
        val errorText = responseStream?.let { readText(it) }.orEmpty()
        writeSseEvent(
          output,
          "error",
          JSONObject()
            .put("message", "DeepSeek request failed with HTTP $responseCode.")
            .put("detail", errorText.take(ERROR_DETAIL_CHARS)),
        )
        return@runCatching
      }
      BufferedReader(InputStreamReader(responseStream, StandardCharsets.UTF_8)).use { reader ->
        while (true) {
          val line = reader.readLine() ?: break
          output.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
          if (line.isBlank()) output.flush()
        }
      }
    }.getOrElse { error ->
      writeSseEvent(output, "error", JSONObject().put("message", error.message ?: error::class.java.simpleName))
    }
    writeSseDone(output)
  }

  private fun deepSeekModel(settings: AppSettings): String {
    val provider = ModelProviderCatalog.findProvider(DEEPSEEK_PROVIDER_ID) ?: return settings.model.ifBlank { "deepseek-chat" }
    return if (settings.provider == DEEPSEEK_PROVIDER_ID) {
      settings.model.ifBlank { provider.defaultModel }
    } else {
      provider.defaultModel
    }
  }

  private fun chatCompletionsEndpoint(baseUrl: String): String {
    val normalized = baseUrl.trimEnd('/')
    return if (normalized.endsWith("/v1")) {
      "$normalized/chat/completions"
    } else {
      "$normalized/v1/chat/completions"
    }
  }

  private fun readHeaders(input: BufferedInputStream): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = readAsciiLine(input) ?: break
      if (line.isBlank()) break
      val separator = line.indexOf(':')
      if (separator > 0) {
        headers[line.substring(0, separator).trim().lowercase(Locale.US)] = line.substring(separator + 1).trim()
      }
    }
    return headers
  }

  private fun readRequestBody(input: BufferedInputStream, contentLength: Int): ByteArray {
    if (contentLength <= 0) return ByteArray(0)
    val buffer = ByteArray(contentLength)
    var offset = 0
    while (offset < contentLength) {
      val count = input.read(buffer, offset, contentLength - offset)
      if (count < 0) break
      offset += count
    }
    return if (offset == contentLength) buffer else buffer.copyOf(offset)
  }

  private fun readAsciiLine(input: BufferedInputStream): String? {
    val bytes = mutableListOf<Byte>()
    while (true) {
      val next = input.read()
      if (next < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
      if (next == '\n'.code) break
      if (next != '\r'.code) bytes += next.toByte()
    }
    return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
  }

  private fun writeJson(output: OutputStream, status: Int, body: JSONObject) {
    val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
    writeHeaders(output, status, reason(status), "application/json; charset=utf-8", bytes.size.toLong())
    output.write(bytes)
  }

  private fun writeText(output: OutputStream, status: Int, reason: String, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    writeHeaders(output, status, reason, "text/plain; charset=utf-8", bytes.size.toLong())
    output.write(bytes)
  }

  private fun writePreflight(output: OutputStream) {
    val headers = "HTTP/1.1 204 No Content\r\n" +
      "Access-Control-Allow-Origin: *\r\n" +
      "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
      "Access-Control-Allow-Headers: content-type\r\n" +
      "Access-Control-Max-Age: 86400\r\n" +
      "Connection: close\r\n" +
      "\r\n"
    output.write(headers.toByteArray(StandardCharsets.UTF_8))
  }

  private fun writeHeaders(
    output: OutputStream,
    status: Int,
    reason: String,
    contentType: String,
    contentLength: Long? = null,
  ) {
    val headers = buildString {
      append("HTTP/1.1 $status $reason\r\n")
      append("Content-Type: $contentType\r\n")
      if (contentLength != null) append("Content-Length: $contentLength\r\n")
      append("Access-Control-Allow-Origin: *\r\n")
      append("Cache-Control: no-store\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }
    output.write(headers.toByteArray(StandardCharsets.UTF_8))
  }

  private fun writeSseHeaders(output: OutputStream) {
    val headers = "HTTP/1.1 200 OK\r\n" +
      "Content-Type: text/event-stream; charset=utf-8\r\n" +
      "Access-Control-Allow-Origin: *\r\n" +
      "Cache-Control: no-cache\r\n" +
      "Connection: close\r\n" +
      "\r\n"
    output.write(headers.toByteArray(StandardCharsets.UTF_8))
    output.flush()
  }

  private fun writeSseEvent(output: OutputStream, event: String, data: JSONObject) {
    output.write("event: $event\n".toByteArray(StandardCharsets.UTF_8))
    output.write("data: ${data}\n\n".toByteArray(StandardCharsets.UTF_8))
    output.flush()
  }

  private fun writeSseDone(output: OutputStream) {
    output.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
    output.flush()
  }

  private fun readText(input: InputStream): String {
    return InputStreamReader(input, StandardCharsets.UTF_8).use { it.readText() }
  }

  private fun encodePath(path: String): String {
    return path.split('/').joinToString("/") { segment ->
      URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
  }

  private fun decodePath(path: String): String {
    return URLDecoder.decode(path, StandardCharsets.UTF_8.name())
  }

  private fun reason(status: Int): String {
    return when (status) {
      200 -> "OK"
      204 -> "No Content"
      400 -> "Bad Request"
      403 -> "Forbidden"
      404 -> "Not Found"
      else -> "Error"
    }
  }

  override fun close() {
    running.set(false)
    runCatching { serverSocket?.close() }
    serverSocket = null
    port = 0
  }

  private companion object {
    const val WORKSPACE_ROUTE = "__flovera__/workspace"
    const val API_ROUTE = "__flovera__/api"
    const val DEEPSEEK_PROVIDER_ID = "deepseek"
    const val CLIENT_READ_TIMEOUT_MS = 15_000
    const val DEEPSEEK_CONNECT_TIMEOUT_MS = 15_000
    const val DEEPSEEK_READ_TIMEOUT_MS = 180_000
    const val ERROR_DETAIL_CHARS = 2_000
  }
}
