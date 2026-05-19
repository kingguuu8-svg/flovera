package com.flovera.app.koog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ProviderRequestHook(val id: String) {
  OmitRequestFields("omit_request_fields"),
  AddRequestFields("add_request_fields"),
  InjectOllamaNumCtx("inject_ollama_num_ctx"),
  InjectOpenRouterRouting("inject_openrouter_routing"),
  InjectKimiThinking("inject_kimi_thinking"),
  InjectTencentTokenHubReasoning("inject_tencent_tokenhub_reasoning"),
  InjectLmStudioReasoning("inject_lmstudio_reasoning"),
  InjectNousPortalReasoning("inject_nous_portal_reasoning"),
  InjectQwenPortalRequestShape("inject_qwen_portal_request_shape"),
  InjectCopilotReasoning("inject_copilot_reasoning"),
}

data class ProviderRequestContext(
  val providerId: String = "",
  val modelId: String = "",
  val supportsReasoning: Boolean = false,
  val reasoningConfig: JsonObject? = null,
  val openRouterProviderPreferences: JsonObject = JsonObject(emptyMap()),
  val openRouterMinCodingScore: Double? = null,
)

fun ProviderRequestContext.withModelId(modelId: String): ProviderRequestContext {
  return copy(modelId = modelId)
}

fun ProviderRequestProfile.hookIds(): List<String> {
  return buildList {
    if (omittedRequestFields.isNotEmpty()) add(ProviderRequestHook.OmitRequestFields.id)
    if (addedRequestFields.isNotEmpty()) add(ProviderRequestHook.AddRequestFields.id)
    if (injectOllamaNumCtx) add(ProviderRequestHook.InjectOllamaNumCtx.id)
    if (injectOpenRouterRouting) add(ProviderRequestHook.InjectOpenRouterRouting.id)
    if (injectKimiThinking) add(ProviderRequestHook.InjectKimiThinking.id)
    if (injectTencentTokenHubReasoning) add(ProviderRequestHook.InjectTencentTokenHubReasoning.id)
    if (injectLmStudioReasoning) add(ProviderRequestHook.InjectLmStudioReasoning.id)
    if (injectNousPortalReasoning) add(ProviderRequestHook.InjectNousPortalReasoning.id)
    if (injectQwenPortalRequestShape) add(ProviderRequestHook.InjectQwenPortalRequestShape.id)
    if (injectCopilotReasoning) add(ProviderRequestHook.InjectCopilotReasoning.id)
  }
}

fun providerRequestString(value: String): JsonElement {
  return JsonPrimitive(value)
}

fun providerRequestBoolean(value: Boolean): JsonElement {
  return JsonPrimitive(value)
}

fun providerRequestObject(vararg fields: Pair<String, JsonElement>): JsonElement {
  return JsonObject(mapOf(*fields))
}

fun providerReasoningConfigFromEffort(effort: String): JsonObject? {
  return when (effort.trim().lowercase()) {
    "" -> null
    "none" -> providerRequestObject("enabled" to providerRequestBoolean(false)).jsonObject
    "minimal", "low", "medium", "high", "xhigh" -> providerRequestObject(
      "enabled" to providerRequestBoolean(true),
      "effort" to providerRequestString(effort.trim().lowercase()),
    ).jsonObject
    else -> null
  }
}

object ProviderRequestHooks {
  fun apply(
    requestJson: String,
    requestProfile: ProviderRequestProfile,
    modelContext: ModelContextSpec,
    requestContext: ProviderRequestContext = ProviderRequestContext(),
  ): String {
    if (requestProfile.hookIds().isEmpty()) return requestJson
    val root = requestHookJson.parseToJsonElement(requestJson).jsonObject.toMutableMap()
    applyOmitRequestFields(root, requestProfile.omittedRequestFields)
    applyAddRequestFields(root, requestProfile.addedRequestFields)
    applyOllamaNumCtx(root, requestProfile, modelContext)
    applyOpenRouterRouting(root, requestProfile, requestContext)
    applyKimiThinking(root, requestProfile, requestContext)
    applyTencentTokenHubReasoning(root, requestProfile, requestContext)
    applyLmStudioReasoning(root, requestProfile, requestContext)
    applyNousPortalReasoning(root, requestProfile, requestContext)
    applyQwenPortalRequestShape(root, requestProfile)
    applyCopilotReasoning(root, requestProfile, requestContext)
    return requestHookJson.encodeToString(JsonObject.serializer(), JsonObject(root))
  }

  private fun applyOmitRequestFields(
    root: MutableMap<String, JsonElement>,
    omittedRequestFields: Set<String>,
  ) {
    omittedRequestFields.forEach { field ->
      root.remove(field)
    }
  }

  private fun applyAddRequestFields(
    root: MutableMap<String, JsonElement>,
    addedRequestFields: Map<String, JsonElement>,
  ) {
    addedRequestFields.forEach { (field, value) ->
      root[field] = value
    }
  }

  private fun applyOllamaNumCtx(
    root: MutableMap<String, JsonElement>,
    requestProfile: ProviderRequestProfile,
    modelContext: ModelContextSpec,
  ) {
    val numCtx = modelContext.contextWindowTokens?.takeIf { it > 0 } ?: return
    if (!requestProfile.injectOllamaNumCtx) return
    val options = (root["options"] as? JsonObject)?.toMutableMap() ?: mutableMapOf<String, JsonElement>()
    options["num_ctx"] = JsonPrimitive(numCtx)
    root["options"] = JsonObject(options)
  }

  private fun applyOpenRouterRouting(
    root: MutableMap<String, JsonElement>,
    requestProfile: ProviderRequestProfile,
    requestContext: ProviderRequestContext,
  ) {
    if (!requestProfile.injectOpenRouterRouting || requestContext.providerId != "openrouter") return
    if (requestContext.supportsReasoning) {
      root["reasoning"] = requestContext.reasoningConfig ?: providerRequestObject(
        "enabled" to providerRequestBoolean(true),
        "effort" to providerRequestString("medium"),
      )
    }
    if (requestContext.openRouterProviderPreferences.isNotEmpty()) {
      root["provider"] = requestContext.openRouterProviderPreferences
    }
    val modelId = requestContext.modelId.ifBlank {
      root["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
    val score = requestContext.openRouterMinCodingScore?.takeIf { it in 0.0..1.0 }
    if (modelId == "openrouter/pareto-code" && score != null) {
      root["plugins"] = JsonArray(
        listOf(
          providerRequestObject(
            "id" to providerRequestString("pareto-router"),
            "min_coding_score" to JsonPrimitive(score),
          ),
        ),
      )
    }
  }

  private fun applyKimiThinking(
    root: MutableMap<String, JsonElement>,
    requestProfile: ProviderRequestProfile,
    requestContext: ProviderRequestContext,
  ) {
    if (!requestProfile.injectKimiThinking) return
    val reasoningConfig = requestContext.reasoningConfig
    val disabled = reasoningConfig?.get("enabled")?.jsonPrimitive?.contentOrNull == "false"
    root["thinking"] = providerRequestObject(
      "type" to providerRequestString(if (disabled) "disabled" else "enabled"),
    )
    if (disabled) {
      root.remove("reasoning_effort")
      return
    }
    val effort = reasoningConfig?.get("effort")?.jsonPrimitive?.contentOrNull
      ?.trim()
      ?.lowercase()
      ?.takeIf { it in setOf("low", "medium", "high") }
      ?: "medium"
    root["reasoning_effort"] = providerRequestString(effort)
  }

  private fun applyTencentTokenHubReasoning(
    root: MutableMap<String, JsonElement>,
    requestProfile: ProviderRequestProfile,
    requestContext: ProviderRequestContext,
  ) {
    if (!requestProfile.injectTencentTokenHubReasoning) return
    val reasoningConfig = requestContext.reasoningConfig
    val disabled = reasoningConfig?.get("enabled")?.jsonPrimitive?.contentOrNull == "false"
    if (disabled) {
      root.remove("reasoning_effort")
      return
    }
    val effort = reasoningConfig?.get("effort")?.jsonPrimitive?.contentOrNull
      ?.trim()
      ?.lowercase()
      ?.takeIf { it in setOf("low", "medium", "high") }
      ?: "high"
    root["reasoning_effort"] = providerRequestString(effort)
  }

  private fun applyLmStudioReasoning(
    root: MutableMap<String, JsonElement>,
    requestProfile: ProviderRequestProfile,
    requestContext: ProviderRequestContext,
  ) {
    if (!requestProfile.injectLmStudioReasoning || !requestContext.supportsReasoning) return
    val reasoningConfig = requestContext.reasoningConfig
    val disabled = reasoningConfig?.get("enabled")?.jsonPrimitive?.contentOrNull == "false"
    val effort = if (disabled) {
      "none"
    } else {
      reasoningConfig?.get("effort")?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.lowercase()
        ?.takeIf { it in setOf("minimal", "low", "medium", "high", "xhigh") }
        ?: "medium"
    }
    root["reasoning_effort"] = providerRequestString(effort)
  }

  private fun applyNousPortalReasoning(
    root: MutableMap<String, JsonElement>,
    requestProfile: ProviderRequestProfile,
    requestContext: ProviderRequestContext,
  ) {
    if (!requestProfile.injectNousPortalReasoning) return
    root["tags"] = JsonArray(
      listOf(
        providerRequestString("product=hermes-agent"),
        providerRequestString("client=hermes-client-vunknown"),
      ),
    )
    if (!requestContext.supportsReasoning) return
    val reasoningConfig = requestContext.reasoningConfig
    val disabled = reasoningConfig?.get("enabled")?.jsonPrimitive?.booleanOrNull == false ||
      reasoningConfig?.get("enabled")?.jsonPrimitive?.contentOrNull == "false"
    if (disabled) {
      root.remove("reasoning")
      return
    }
    root["reasoning"] = reasoningConfig ?: providerRequestObject(
      "enabled" to providerRequestBoolean(true),
      "effort" to providerRequestString("medium"),
    )
  }

  private fun applyQwenPortalRequestShape(
    root: MutableMap<String, JsonElement>,
    requestProfile: ProviderRequestProfile,
  ) {
    if (!requestProfile.injectQwenPortalRequestShape) return
    root["vl_high_resolution_images"] = providerRequestBoolean(true)
    val messages = root["messages"] as? JsonArray ?: return
    root["messages"] = JsonArray(messages.map { normalizeQwenMessage(it) })
  }

  private fun normalizeQwenMessage(message: JsonElement): JsonElement {
    val obj = message as? JsonObject ?: return message
    val mutable = obj.toMutableMap()
    val content = mutable["content"]
    val normalizedContent = when (content) {
      is JsonPrimitive -> JsonArray(listOf(providerRequestObject("type" to providerRequestString("text"), "text" to content)))
      is JsonArray -> JsonArray(
        content.mapNotNull { part ->
          when (part) {
            is JsonPrimitive -> providerRequestObject("type" to providerRequestString("text"), "text" to part)
            is JsonObject -> part
            else -> null
          }
        },
      )
      else -> null
    }
    if (normalizedContent != null) {
      mutable["content"] = if (obj["role"]?.jsonPrimitive?.contentOrNull == "system") {
        normalizedContent.withQwenSystemCacheControl()
      } else {
        normalizedContent
      }
    }
    return JsonObject(mutable)
  }

  private fun JsonArray.withQwenSystemCacheControl(): JsonArray {
    if (isEmpty()) return this
    return JsonArray(mapIndexed { index, part ->
      if (index != lastIndex) return@mapIndexed part
      val obj = part as? JsonObject ?: return@mapIndexed part
      val mutable = obj.toMutableMap()
      mutable["cache_control"] = providerRequestObject("type" to providerRequestString("ephemeral"))
      JsonObject(mutable)
    })
  }

  private fun applyCopilotReasoning(
    root: MutableMap<String, JsonElement>,
    requestProfile: ProviderRequestProfile,
    requestContext: ProviderRequestContext,
  ) {
    if (!requestProfile.injectCopilotReasoning || requestContext.providerId != "copilot") return
    if (!requestContext.supportsReasoning) return
    val supportedEfforts = copilotReasoningEfforts(requestContext.modelId)
    if (supportedEfforts.isEmpty()) return
    val reasoningConfig = requestContext.reasoningConfig
    val disabled = reasoningConfig?.get("enabled")?.jsonPrimitive?.booleanOrNull == false ||
      reasoningConfig?.get("enabled")?.jsonPrimitive?.contentOrNull == "false"
    if (disabled) {
      root.remove("reasoning")
      return
    }
    val requested = reasoningConfig?.get("effort")?.jsonPrimitive?.contentOrNull
      ?.trim()
      ?.lowercase()
      ?.let { if (it == "xhigh") "high" else it }
      ?: "medium"
    val effort = requested.takeIf { it in supportedEfforts } ?: "medium".takeIf { it in supportedEfforts } ?: supportedEfforts.first()
    root["reasoning"] = providerRequestObject("effort" to providerRequestString(effort))
  }

  private fun copilotReasoningEfforts(modelId: String): List<String> {
    val normalized = ModelProviderCatalog.normalizeCopilotModelId(modelId).lowercase()
    if (normalized.startsWith("o1") || normalized.startsWith("o3") || normalized.startsWith("o4")) {
      return listOf("low", "medium", "high")
    }
    if (normalized.startsWith("gpt-5")) {
      return listOf("minimal", "low", "medium", "high")
    }
    return emptyList()
  }
}

private val requestHookJson = Json {
  encodeDefaults = false
}
