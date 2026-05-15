package com.flovera.app.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.flovera.app.config.normalizeBraveSearchApiKey
import com.flovera.app.workspace.WorkspaceManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000
private const val MAX_REDIRECTS = 5
private const val MAX_FETCH_RESPONSE_BYTES = 64 * 1024
private const val MAX_SEARCH_RESPONSE_BYTES = 96 * 1024

data class NetworkResponse(
  val statusCode: Int,
  val contentType: String?,
  val finalUrl: String,
  val body: ByteArray,
  val truncated: Boolean,
)

interface NetworkHttpClient {
  suspend fun get(url: URL, maxBytes: Int? = null, headers: Map<String, String> = emptyMap()): NetworkResponse
}

class JavaNetNetworkHttpClient : NetworkHttpClient {
  override suspend fun get(url: URL, maxBytes: Int?, headers: Map<String, String>): NetworkResponse = withContext(Dispatchers.IO) {
    var current = url
    repeat(MAX_REDIRECTS + 1) { redirectCount ->
      val connection = (current.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        requestMethod = "GET"
        setRequestProperty("User-Agent", "AiLinuxAndroidAgent/1.0")
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
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
        val body = readFully(stream, maxBytes)
        return@withContext NetworkResponse(
          statusCode = statusCode,
          contentType = connection.contentType,
          finalUrl = current.toString(),
          body = body.bytes,
          truncated = body.truncated,
        )
      } finally {
        connection.disconnect()
      }
    }
    error("Too many redirects")
  }

  private fun readFully(input: java.io.InputStream, maxBytes: Int?): ReadBody {
    input.use { stream ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read == -1) break
        if (maxBytes != null && output.size() + read > maxBytes) {
          val remaining = maxBytes - output.size()
          if (remaining > 0) output.write(buffer, 0, remaining)
          return ReadBody(output.toByteArray(), truncated = true)
        }
        output.write(buffer, 0, read)
      }
      return ReadBody(output.toByteArray(), truncated = false)
    }
  }

  private data class ReadBody(
    val bytes: ByteArray,
    val truncated: Boolean,
  )
}

class WebSearchTool(
  private val braveSearchApiKey: String,
  private val recorder: ToolEventRecorder,
  private val client: NetworkHttpClient = JavaNetNetworkHttpClient(),
) : SimpleTool<WebSearchTool.Args>(
  argsType = typeToken<Args>(),
  name = "web_search",
  description = "Search the web through Brave Search API and return compact result titles, URLs, and descriptions. This tool is available only when Network and Web Search are both enabled.",
) {
  @Serializable
  data class Args(
    @property:LLMDescription("Search query.")
    val query: String,
    @property:LLMDescription("Number of results to return, from 1 to 10.")
    val count: Int = 5,
  )

  override suspend fun execute(args: Args): String {
    val result = runCatching {
      val normalizedApiKey = normalizeBraveSearchApiKey(braveSearchApiKey)
      require(normalizedApiKey.isNotBlank()) { "Brave Search API key is not configured." }
      val query = args.query.trim()
      require(query.isNotBlank()) { "Search query is required." }
      val count = args.count.coerceIn(1, 10)
      val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
      val url = NetworkUrlPolicy.validate("https://api.search.brave.com/res/v1/web/search?q=$encodedQuery&count=$count")
      val response = client.get(
        url = url,
        maxBytes = MAX_SEARCH_RESPONSE_BYTES,
        headers = mapOf(
          "Accept" to "application/json",
          "X-Subscription-Token" to normalizedApiKey,
        ),
      )
      response.formatForSearch(query)
    }.getOrElse { sanitizeBraveSearchError(it, braveSearchApiKey) }
    recorder.record(name, "query=${args.query}, count=${args.count}", result)
    return result
  }
}

private fun sanitizeBraveSearchError(error: Throwable, apiKey: String): String {
  val raw = error.message ?: error.toString()
  val normalized = normalizeBraveSearchApiKey(apiKey)
  val redacted = listOf(apiKey, normalized)
    .filter { it.isNotBlank() }
    .fold(raw) { current, secret -> current.replace(secret, "[redacted]") }
    .replace(Regex("header value:.*", RegexOption.DOT_MATCHES_ALL), "header value: [redacted]")
  return "Brave Search request failed: $redacted"
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
      val response = client.get(url, maxBytes = MAX_FETCH_RESPONSE_BYTES)
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
    truncated: $truncated

    $bodyText
  """.trimIndent()
}

private fun NetworkResponse.formatForSearch(query: String): String {
  if (statusCode !in 200..299) {
    return "Search failed: status=$statusCode, final_url=$finalUrl\n${body.decodeToString()}"
  }
  val root = Json.parseToJsonElement(body.decodeToString()).jsonObject
  val results = ((root["web"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()
  if (results.isEmpty()) {
    return "No web results for: $query"
  }
  return buildString {
    appendLine("status: $statusCode")
    appendLine("query: $query")
    appendLine("final_url: $finalUrl")
    appendLine("results:")
    results.forEachIndexed { index, item ->
      val obj = item as? JsonObject ?: return@forEachIndexed
      val title = obj.stringValue("title")
      val url = obj.stringValue("url")
      val description = obj.stringValue("description")
      appendLine("${index + 1}. ${title.ifBlank { "(untitled)" }}")
      if (url.isNotBlank()) appendLine("   url: $url")
      if (description.isNotBlank()) appendLine("   description: $description")
    }
  }.trimEnd()
}

private fun JsonObject.stringValue(key: String): String {
  return runCatching { get(key)?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")
}
