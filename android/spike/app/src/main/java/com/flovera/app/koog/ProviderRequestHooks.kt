package com.flovera.app.koog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

enum class ProviderRequestHook(val id: String) {
  OmitRequestFields("omit_request_fields"),
  AddRequestFields("add_request_fields"),
  InjectOllamaNumCtx("inject_ollama_num_ctx"),
}

fun ProviderRequestProfile.hookIds(): List<String> {
  return buildList {
    if (omittedRequestFields.isNotEmpty()) add(ProviderRequestHook.OmitRequestFields.id)
    if (addedRequestFields.isNotEmpty()) add(ProviderRequestHook.AddRequestFields.id)
    if (injectOllamaNumCtx) add(ProviderRequestHook.InjectOllamaNumCtx.id)
  }
}

fun providerRequestString(value: String): JsonElement {
  return JsonPrimitive(value)
}

fun providerRequestObject(vararg fields: Pair<String, JsonElement>): JsonElement {
  return JsonObject(mapOf(*fields))
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
    applyAddRequestFields(root, requestProfile.addedRequestFields)
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
}

private val requestHookJson = Json {
  encodeDefaults = false
}
