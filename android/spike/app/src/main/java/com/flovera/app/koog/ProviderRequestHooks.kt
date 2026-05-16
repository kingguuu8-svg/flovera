package com.flovera.app.koog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

enum class ProviderRequestHook(val id: String) {
  OmitRequestFields("omit_request_fields"),
  InjectOllamaNumCtx("inject_ollama_num_ctx"),
}

fun ProviderRequestProfile.hookIds(): List<String> {
  return buildList {
    if (omittedRequestFields.isNotEmpty()) add(ProviderRequestHook.OmitRequestFields.id)
    if (injectOllamaNumCtx) add(ProviderRequestHook.InjectOllamaNumCtx.id)
  }
}

object ProviderRequestHooks {
  fun apply(
    requestJson: String,
    requestProfile: ProviderRequestProfile,
    modelContext: ModelContextSpec,
  ): String {
    if (requestProfile.hookIds().isEmpty()) return requestJson
    val root = requestHookJson.parseToJsonElement(requestJson).jsonObject.toMutableMap()
    applyOmitRequestFields(root, requestProfile.omittedRequestFields)
    applyOllamaNumCtx(root, requestProfile, modelContext)
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
}

private val requestHookJson = Json {
  encodeDefaults = false
}
