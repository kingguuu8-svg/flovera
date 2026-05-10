package com.example.ailinuxvmspike.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.example.ailinuxvmspike.workspace.WorkspaceManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private const val FETCH_MAX_BYTES = 512 * 1024
private const val DOWNLOAD_MAX_BYTES = 10 * 1024 * 1024
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000
private const val MAX_REDIRECTS = 5

data class NetworkResponse(
  val statusCode: Int,
  val contentType: String?,
  val finalUrl: String,
  val body: ByteArray,
  val truncated: Boolean,
)

interface NetworkHttpClient {
  suspend fun get(url: URL, maxBytes: Int): NetworkResponse
}

class JavaNetNetworkHttpClient : NetworkHttpClient {
  override suspend fun get(url: URL, maxBytes: Int): NetworkResponse = withContext(Dispatchers.IO) {
    var current = url
    repeat(MAX_REDIRECTS + 1) { redirectCount ->
      val connection = (current.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        requestMethod = "GET"
        setRequestProperty("User-Agent", "AiLinuxAndroidAgent/1.0")
      }
      try {
        val statusCode = connection.responseCode
        if (statusCode in 300..399) {
          if (redirectCount == MAX_REDIRECTS) {
            return@withContext NetworkResponse(
              statusCode = statusCode,
              contentType = connection.contentType,
              finalUrl = current.toString(),
              body = "Too many redirects".encodeToByteArray(),
              truncated = false,
            )
          }
          val location = connection.getHeaderField("Location")
          require(!location.isNullOrBlank()) { "Redirect response did not include Location." }
          current = NetworkUrlPolicy.validate(current.toURI().resolve(location).toString())
          return@repeat
        }

        val stream = if (statusCode >= 400) connection.errorStream ?: connection.inputStream else connection.inputStream
        val (body, truncated) = readLimited(stream, maxBytes)
        return@withContext NetworkResponse(
          statusCode = statusCode,
          contentType = connection.contentType,
          finalUrl = current.toString(),
          body = body,
          truncated = truncated,
        )
      } finally {
        connection.disconnect()
      }
    }
    error("Too many redirects")
  }

  private fun readLimited(input: java.io.InputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
    input.use { stream ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      var remaining = maxBytes + 1
      while (remaining > 0) {
        val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
        if (read == -1) break
        output.write(buffer, 0, read)
        remaining -= read
      }
      val bytes = output.toByteArray()
      return if (bytes.size > maxBytes) {
        bytes.copyOf(maxBytes) to true
      } else {
        bytes to false
      }
    }
  }
}

object NetworkUrlPolicy {
  fun validate(rawUrl: String): URL {
    val uri = URI(rawUrl.trim()).normalize()
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "Only http and https URLs are allowed." }
    require(uri.userInfo == null) { "URLs with user info are not allowed." }
    val host = uri.host?.trim()?.lowercase()
    require(!host.isNullOrBlank()) { "URL host is required." }
    require(host != "localhost") { "Localhost URLs are not allowed." }
    InetAddress.getAllByName(host).forEach { address ->
      require(!address.isRestrictedForAgent()) { "Private, local, or reserved network addresses are not allowed: $host" }
    }
    return uri.toURL()
  }
}

class FetchUrlTool(
  private val recorder: ToolEventRecorder,
  private val client: NetworkHttpClient = JavaNetNetworkHttpClient(),
) : SimpleTool<FetchUrlTool.Args>(
  argsType = typeToken<Args>(),
  name = "fetch_url",
  description = "Fetch a public http or https URL and return a bounded text preview. Local, private, and reserved network targets are blocked.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Public http or https URL to fetch.")
    val url: String,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      val url = NetworkUrlPolicy.validate(args.url)
      val response = client.get(url, FETCH_MAX_BYTES)
      response.formatForFetch()
    }.getOrElse { it.message ?: it.toString() }
    recorder.record(name, "url=${args.url}", result)
    return result
  }
}

class DownloadFileTool(
  private val workspace: WorkspaceManager,
  private val recorder: ToolEventRecorder,
  private val client: NetworkHttpClient = JavaNetNetworkHttpClient(),
) : SimpleTool<DownloadFileTool.Args>(
  argsType = typeToken<Args>(),
  name = "download_file",
  description = "Download a public http or https URL into the Android workspace. The path is relative to the workspace root.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Public http or https URL to download.")
    val url: String,
    @property:LLMDescription("Workspace-relative destination path.")
    val path: String,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      val url = NetworkUrlPolicy.validate(args.url)
      val response = client.get(url, DOWNLOAD_MAX_BYTES)
      require(!response.truncated) { "Download exceeds ${DOWNLOAD_MAX_BYTES} bytes and was not saved." }
      val writeResult = workspace.writeBytes(args.path, response.body)
      "Downloaded ${response.body.size} bytes from ${response.finalUrl} to ${args.path} (status=${response.statusCode}, contentType=${response.contentType ?: "unknown"}). $writeResult"
    }.getOrElse { it.message ?: it.toString() }
    recorder.record(name, "url=${args.url}, path=${args.path}", result)
    return result
  }
}

private fun NetworkResponse.formatForFetch(): String {
  val bodyText = if (contentType?.startsWith("text/", ignoreCase = true) == true ||
    contentType?.contains("json", ignoreCase = true) == true ||
    contentType?.contains("xml", ignoreCase = true) == true ||
    contentType?.contains("javascript", ignoreCase = true) == true ||
    contentType.isNullOrBlank()
  ) {
    body.decodeToString()
  } else {
    "[binary response omitted: ${body.size} bytes]"
  }
  val suffix = if (truncated) "\n[truncated at $FETCH_MAX_BYTES bytes]" else ""
  return """
    status: $statusCode
    final_url: $finalUrl
    content_type: ${contentType ?: "unknown"}

    $bodyText$suffix
  """.trimIndent()
}

private fun InetAddress.isRestrictedForAgent(): Boolean {
  if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) return true
  return when (this) {
    is Inet4Address -> address.isRestrictedIpv4()
    is Inet6Address -> address.isRestrictedIpv6()
    else -> true
  }
}

private fun ByteArray.isRestrictedIpv4(): Boolean {
  val first = this[0].toInt() and 0xff
  val second = this[1].toInt() and 0xff
  return first == 0 ||
    first == 10 ||
    first == 127 ||
    first >= 224 ||
    first == 169 && second == 254 ||
    first == 172 && second in 16..31 ||
    first == 192 && second == 168 ||
    first == 100 && second in 64..127 ||
    first == 198 && second in 18..19
}

private fun ByteArray.isRestrictedIpv6(): Boolean {
  val first = this[0].toInt() and 0xff
  return first == 0 || first == 0xff || (first and 0xfe) == 0xfc
}
