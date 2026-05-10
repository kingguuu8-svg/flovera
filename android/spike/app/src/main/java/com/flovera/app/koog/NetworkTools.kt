package com.flovera.app.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.flovera.app.workspace.WorkspaceManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

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
  suspend fun get(url: URL): NetworkResponse
}

class JavaNetNetworkHttpClient : NetworkHttpClient {
  override suspend fun get(url: URL): NetworkResponse = withContext(Dispatchers.IO) {
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
        val body = readFully(stream)
        return@withContext NetworkResponse(
          statusCode = statusCode,
          contentType = connection.contentType,
          finalUrl = current.toString(),
          body = body,
          truncated = false,
        )
      } finally {
        connection.disconnect()
      }
    }
    error("Too many redirects")
  }

  private fun readFully(input: java.io.InputStream): ByteArray {
    input.use { stream ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read == -1) break
        output.write(buffer, 0, read)
      }
      return output.toByteArray()
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
    return uri.toURL()
  }
}

class FetchUrlTool(
  private val recorder: ToolEventRecorder,
  private val client: NetworkHttpClient = JavaNetNetworkHttpClient(),
) : SimpleTool<FetchUrlTool.Args>(
  argsType = typeToken<Args>(),
  name = "fetch_url",
  description = "Fetch an http or https URL and return the response content. This tool is available only when the user enables Network in the conversation.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("HTTP or HTTPS URL to fetch.")
    val url: String,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      val url = NetworkUrlPolicy.validate(args.url)
      val response = client.get(url)
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
  description = "Download an http or https URL into the Android workspace. This tool is available only when the user enables Network in the conversation.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("HTTP or HTTPS URL to download.")
    val url: String,
    @property:LLMDescription("Workspace-relative destination path.")
    val path: String,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      val url = NetworkUrlPolicy.validate(args.url)
      val response = client.get(url)
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
  return """
    status: $statusCode
    final_url: $finalUrl
    content_type: ${contentType ?: "unknown"}

    $bodyText
  """.trimIndent()
}
