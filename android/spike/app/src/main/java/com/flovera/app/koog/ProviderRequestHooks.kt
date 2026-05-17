package com.flovera.app.koog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ProviderRequestHook(val id: String) {
  OmitRequestFields("omit_request_fields"),
  AddRequestFields("add_request_fields"),
  InjectOllamaNumCtx("inject_ollama_num_ctx"),
  InjectOpenRouterRouting("inject_openrouter_routing"),
  InjectKimiThinking("inject_kimi_thinking"),
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
}

private val requestHookJson = Json {
  encodeDefaults = false
}
