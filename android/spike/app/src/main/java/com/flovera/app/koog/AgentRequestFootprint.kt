package com.flovera.app.koog

import ai.koog.agents.core.tools.ToolDescriptor
import com.flovera.app.config.AppSettings
import com.flovera.app.session.AgentSession
import com.flovera.app.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentRequestFootprint(
  val provider: String,
  val model: String,
  val systemPrompt: String,
  val workspaceUserRules: String,
  val userPrompt: String,
  val toolSchemaJson: String,
  val payloadJson: String,
  val transportOverheadChars: Int,
) {
  val rulesChars: Int = systemPrompt.length + workspaceUserRules.length
  val toolSchemaChars: Int = toolSchemaJson.length
  val requestChars: Int = payloadJson.length + transportOverheadChars
}

object AgentRequestFootprintBuilder {
  fun build(
    input: String,
    currentVisibleInput: String = input,
    settings: AppSettings,
    session: AgentSession,
    workspace: WorkspaceManager,
  ): AgentRequestFootprint {
    val webSearchAvailable = settings.networkEnabled && settings.webSearchEnabled && settings.braveSearchApiKey.isNotBlank()
    val provider = ModelProviderCatalog.findProvider(settings.provider)
    val modelContext = ModelProviderCatalog.contextFor(settings)
    val systemPrompt = AgentPromptBuilder.systemPrompt(
      networkEnabled = settings.networkEnabled,
      webSearchAvailable = webSearchAvailable,
      authorityMode = settings.agentAuthorityMode,
      pythonRunToolFallbackEnabled = settings.pythonRunToolFallbackEnabled,
    )
    val workspaceUserRules = workspace.readAgentRules()
    val userPrompt = AgentPromptBuilder.userInput(
      input = input,
      session = session,
      workspaceUserRules = workspaceUserRules,
      currentVisibleInput = currentVisibleInput,
    )
    val toolDescriptors = workspaceToolRegistry(
      workspace = workspace,
      recorder = ToolEventRecorder {},
      networkEnabled = settings.networkEnabled,
      pythonRunToolFallbackEnabled = settings.pythonRunToolFallbackEnabled,
      authorityMode = settings.agentAuthorityMode,
      webSearchEnabled = webSearchAvailable,
      braveSearchApiKey = settings.braveSearchApiKey,
    ).tools.map { it.descriptor }
    val toolSchema = toolSchemaPayload(toolDescriptors)
    val payload = buildJsonObject {
      put("provider", provider?.id ?: settings.provider)
      put("model", settings.model)
      put("stream", true)
      put(
        "messages",
        buildJsonArray {
          add(
            buildJsonObject {
              put("role", "system")
              put("content", systemPrompt)
            },
          )
          add(
            buildJsonObject {
              put("role", "user")
              put("content", userPrompt)
            },
          )
        },
      )
      put("tools", toolSchema)
      put(
        "flovera_request_profile",
        buildJsonObject {
          put("apiMode", provider?.apiMode?.id.orEmpty())
          put("transport", provider?.transport?.id.orEmpty())
          put("authorityMode", settings.agentAuthorityMode)
          put("networkEnabled", settings.networkEnabled)
          put("webSearchAvailable", webSearchAvailable)
          modelContext.contextWindowTokens?.let { put("contextWindowTokens", it) }
        },
      )
    }
    val toolSchemaJson = toolSchema.toString()
    val providerOverheadChars = providerOverheadChars(settings, toolDescriptors.size)
    return AgentRequestFootprint(
      provider = provider?.id ?: settings.provider,
      model = settings.model,
      systemPrompt = systemPrompt,
      workspaceUserRules = workspaceUserRules,
      userPrompt = userPrompt,
      toolSchemaJson = toolSchemaJson,
      payloadJson = payload.toString(),
      transportOverheadChars = providerOverheadChars,
    )
  }

  private fun toolSchemaPayload(descriptors: List<ToolDescriptor>): JsonArray {
    return buildJsonArray {
      descriptors.forEach { descriptor ->
        add(
          buildJsonObject {
            put("type", "function")
            put(
              "function",
              buildJsonObject {
                put("name", descriptor.name)
                put("description", descriptor.description)
                put(
                  "parameters",
                  buildJsonObject {
                    put("type", "object")
                    put("properties", parameterProperties(descriptor))
                    put(
                      "required",
                      buildJsonArray {
                        descriptor.requiredParameters.forEach { parameter ->
                          add(JsonPrimitive(parameter.name))
                        }
                      },
                    )
                  },
                )
              },
            )
          },
        )
      }
    }
  }

  private fun parameterProperties(descriptor: ToolDescriptor): JsonObject {
    return buildJsonObject {
      (descriptor.requiredParameters + descriptor.optionalParameters).forEach { parameter ->
        put(
          parameter.name,
          buildJsonObject {
            put("type", parameter.type.name)
            put("description", parameter.description)
          },
        )
      }
    }
  }

  private fun providerOverheadChars(settings: AppSettings, toolCount: Int): Int {
    val providerFields = settings.provider.length + settings.model.length
    val transportEnvelope = when (settings.provider) {
      "deepseek", "custom-openai", "openrouter", "xai", "alibaba", "moonshot", "zai" -> 420
      "anthropic", "gemini", "bedrock" -> 700
      else -> 520
    }
    return transportEnvelope + providerFields + (toolCount * 12)
  }
}
