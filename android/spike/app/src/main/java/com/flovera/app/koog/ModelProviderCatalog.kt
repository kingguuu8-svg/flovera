package com.flovera.app.koog

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.flovera.app.config.AppSettings
import com.flovera.app.config.ModelContextOverride
import kotlinx.serialization.json.JsonElement

data class ModelProviderSpec(
  val id: String,
  val label: String,
  val apiKeyLabel: String,
  val defaultModel: String,
  val suggestedModels: List<String>,
  val llmProvider: LLMProvider,
  val transport: ProviderTransport,
  val apiMode: ProviderApiMode = ProviderApiMode.ChatCompletions,
  val aliases: Set<String> = emptySet(),
  val baseUrl: String = "",
  val modelsUrl: String = "",
  val chatCompletionsPath: String = "/v1/chat/completions",
  val responsesPath: String = "v1/responses",
  val messagesPath: String = "/v1/messages",
  val modelsPath: String = "/v1/models",
  val authType: ProviderAuthType = ProviderAuthType.ApiKey,
  val supportsHealthCheck: Boolean = true,
  val defaultHeaders: Map<String, String> = emptyMap(),
  val defaultMaxTokens: Int? = null,
  val defaultAuxModel: String = "",
  val requestProfile: ProviderRequestProfile = ProviderRequestProfile(),
  val modelContexts: Map<String, ModelContextSpec> = emptyMap(),
  val defaultContext: ModelContextSpec = ModelContextSpec(),
) {
  fun contextFor(modelId: String): ModelContextSpec {
    return modelContexts[modelId.ifBlank { defaultModel }] ?: defaultContext
  }

  fun createModel(modelId: String, contextSpec: ModelContextSpec? = null): LLModel {
    val model = modelId.ifBlank { defaultModel }
    val context = contextSpec ?: contextFor(model)
    return LLModel(
      provider = llmProvider,
      id = model,
      capabilities = agentModelCapabilitiesFor(apiMode),
      contextLength = (context.contextWindowTokens ?: 200_000).toLong(),
      maxOutputTokens = 32_000,
    )
  }
}

data class ProviderRuntimeProfile(
  val providerId: String,
  val label: String,
  val apiMode: ProviderApiMode,
  val transport: ProviderTransport,
  val llmProvider: LLMProvider,
  val baseUrl: String,
  val modelsUrl: String,
  val chatCompletionsPath: String,
  val responsesPath: String,
  val messagesPath: String,
  val modelsPath: String,
  val authType: ProviderAuthType,
  val supportsHealthCheck: Boolean,
  val defaultHeaders: Map<String, String>,
  val defaultMaxTokens: Int?,
  val defaultAuxModel: String,
  val requestProfile: ProviderRequestProfile,
) {
  fun requireBaseUrl(): String {
    return baseUrl.takeIf { it.isNotBlank() } ?: error("Provider profile $providerId has no base URL configured.")
  }
}

data class ProviderRequestProfile(
  val compatibilityMode: String = "generic",
  val injectOllamaNumCtx: Boolean = false,
  val injectOpenRouterRouting: Boolean = false,
  val injectKimiThinking: Boolean = false,
  val injectTencentTokenHubReasoning: Boolean = false,
  val injectLmStudioReasoning: Boolean = false,
  val injectNousPortalReasoning: Boolean = false,
  val injectQwenPortalRequestShape: Boolean = false,
  val injectCopilotReasoning: Boolean = false,
  val omittedRequestFields: Set<String> = emptySet(),
  val addedRequestFields: Map<String, JsonElement> = emptyMap(),
)

enum class ProviderApiMode(val id: String) {
  ChatCompletions("chat_completions"),
  AnthropicMessages("anthropic_messages"),
  BedrockConverse("bedrock_converse"),
  CodexResponses("codex_responses"),
}

enum class ProviderAuthType(val id: String) {
  ApiKey("api_key"),
  BearerToken("bearer_token"),
  AwsSdk("aws_sdk"),
  OAuthDeviceCode("oauth_device_code"),
  OAuthExternal("oauth_external"),
  ExternalProcess("external_process"),
  Copilot("copilot"),
}

data class ModelContextSpec(
  val contextWindowTokens: Int? = null,
  val source: String = "unknown",
  val usageSource: String = "estimate",
  val compressionThresholdPercent: Int? = null,
  val supportsReasoning: Boolean = false,
)

private fun hermesContext(tokens: Int, supportsReasoning: Boolean = false): ModelContextSpec {
  return ModelContextSpec(
    contextWindowTokens = tokens,
    source = "hermes_model_metadata",
    usageSource = "estimate",
    compressionThresholdPercent = 82,
    supportsReasoning = supportsReasoning,
  )
}

private fun reasoningContext(tokens: Int): ModelContextSpec {
  return hermesContext(tokens, supportsReasoning = true)
}

private fun contextMap(vararg entries: Pair<String, Int>): Map<String, ModelContextSpec> {
  return entries.associate { (model, tokens) -> model to hermesContext(tokens) }
}

private fun copilotDefaultHeaders(): Map<String, String> {
  return mapOf(
    "Editor-Version" to "vscode/1.104.1",
    "User-Agent" to "HermesAgent/1.0",
    "Openai-Intent" to "conversation-edits",
    "x-initiator" to "agent",
  )
}

private data class BuiltInProviderProfile(
  val id: String,
  val label: String,
  val apiKeyLabel: String,
  val defaultModel: String,
  val suggestedModels: List<String>,
  val aliases: Set<String> = emptySet(),
  val baseUrl: String,
  val modelsUrl: String = "",
  val defaultMaxTokens: Int? = null,
  val defaultAuxModel: String = "",
  val defaultHeaders: Map<String, String> = emptyMap(),
  val authType: ProviderAuthType = ProviderAuthType.ApiKey,
  val supportsHealthCheck: Boolean = true,
  val requestProfile: ProviderRequestProfile = ProviderRequestProfile(),
  val modelContexts: Map<String, ModelContextSpec> = emptyMap(),
  val defaultContext: ModelContextSpec = ModelContextSpec(),
) {
  fun toSpec(): ModelProviderSpec {
    return ModelProviderSpec(
      id = id,
      label = label,
      apiKeyLabel = apiKeyLabel,
      defaultModel = defaultModel,
      suggestedModels = suggestedModels,
      llmProvider = LLMProvider.OpenAI,
      transport = ProviderTransport.FloveraOpenAICompatibleChatCompletions,
      aliases = aliases,
      baseUrl = baseUrl,
      modelsUrl = modelsUrl,
      defaultMaxTokens = defaultMaxTokens,
      defaultAuxModel = defaultAuxModel,
      defaultHeaders = defaultHeaders,
      authType = authType,
      supportsHealthCheck = supportsHealthCheck,
      requestProfile = requestProfile,
      modelContexts = modelContexts,
      defaultContext = defaultContext,
    )
  }
}

object ModelProviderCatalog {
  private val openAICompatibleProviderProfiles = listOf(
    BuiltInProviderProfile(
      id = "nous",
      label = "Nous Research",
      apiKeyLabel = "Nous OAuth access token",
      defaultModel = "hermes-3-405b",
      suggestedModels = listOf("hermes-3-405b", "hermes-3-70b"),
      aliases = setOf("nous-portal", "nousresearch"),
      baseUrl = "https://inference-api.nousresearch.com/v1",
      authType = ProviderAuthType.OAuthDeviceCode,
      requestProfile = ProviderRequestProfile(injectNousPortalReasoning = true),
      defaultContext = reasoningContext(128_000),
    ),
    BuiltInProviderProfile(
      id = "qwen-oauth",
      label = "Qwen OAuth",
      apiKeyLabel = "Qwen OAuth access token",
      defaultModel = "qwen3-coder-plus",
      suggestedModels = listOf("qwen3-coder-plus", "qwen-plus", "qwen-max", "qwen-turbo"),
      aliases = setOf("qwen", "qwen-portal", "qwen-cli"),
      baseUrl = "https://portal.qwen.ai/v1",
      authType = ProviderAuthType.OAuthExternal,
      defaultMaxTokens = 65_536,
      requestProfile = ProviderRequestProfile(injectQwenPortalRequestShape = true),
      modelContexts = contextMap(
        "qwen3-coder-plus" to 1_000_000,
        "qwen-plus" to 131_072,
        "qwen-max" to 131_072,
        "qwen-turbo" to 131_072,
      ),
    ),
    BuiltInProviderProfile(
      id = "openai",
      label = "OpenAI",
      apiKeyLabel = "OpenAI API key",
      defaultModel = "gpt-4.1",
      suggestedModels = listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4o-mini"),
      aliases = setOf("gpt", "openai-compatible"),
      baseUrl = "https://api.openai.com/v1",
      modelsUrl = "https://api.openai.com/v1/models",
      modelContexts = contextMap(
        "gpt-4.1" to 1_047_576,
        "gpt-4.1-mini" to 1_047_576,
        "gpt-4o-mini" to 128_000,
      ),
    ),
    BuiltInProviderProfile(
      id = "azure-foundry",
      label = "Azure Foundry",
      apiKeyLabel = "Azure Foundry API key",
      defaultModel = "azure-model",
      suggestedModels = listOf("azure-model"),
      aliases = setOf("azure", "azure-ai-foundry", "azure-ai"),
      baseUrl = "",
      supportsHealthCheck = false,
    ),
    BuiltInProviderProfile(
      id = "ai-gateway",
      label = "Vercel AI Gateway",
      apiKeyLabel = "AI Gateway API key",
      defaultModel = "moonshotai/kimi-k2.6",
      suggestedModels = listOf(
        "moonshotai/kimi-k2.6",
        "alibaba/qwen3.6-plus",
        "zai/glm-5.1",
        "minimax/minimax-m2.7",
        "anthropic/claude-sonnet-4.6",
        "openai/gpt-5.4",
        "google/gemini-3-flash",
        "xai/grok-4.20-reasoning",
      ),
      aliases = setOf("vercel", "vercel-ai-gateway", "ai_gateway", "aigateway"),
      baseUrl = "https://ai-gateway.vercel.sh/v1",
      defaultHeaders = mapOf(
        "HTTP-Referer" to "https://hermes-agent.nousresearch.com",
        "X-Title" to "Hermes Agent",
      ),
      defaultAuxModel = "google/gemini-3-flash",
      requestProfile = ProviderRequestProfile(
        addedRequestFields = mapOf(
          "reasoning" to providerRequestObject(
            "enabled" to providerRequestBoolean(true),
            "effort" to providerRequestString("medium"),
          ),
        ),
      ),
    ),
    BuiltInProviderProfile(
      id = "alibaba",
      label = "Alibaba DashScope",
      apiKeyLabel = "DashScope API key",
      defaultModel = "qwen3-coder-plus",
      suggestedModels = listOf("qwen3-coder-plus", "qwen-plus", "qwen-max", "qwen-turbo"),
      aliases = setOf("dashscope", "alibaba-cloud", "qwen-dashscope"),
      baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
      modelContexts = contextMap(
        "qwen3-coder-plus" to 1_000_000,
        "qwen-plus" to 131_072,
        "qwen-max" to 131_072,
        "qwen-turbo" to 131_072,
      ),
    ),
    BuiltInProviderProfile(
      id = "alibaba-coding-plan",
      label = "Alibaba Coding Plan",
      apiKeyLabel = "Alibaba Coding Plan API key",
      defaultModel = "qwen3.6-plus",
      suggestedModels = listOf(
        "qwen3.6-plus",
        "qwen3.5-plus",
        "qwen3-coder-plus",
        "qwen3-coder-next",
        "kimi-k2.5",
        "glm-5",
        "glm-4.7",
        "MiniMax-M2.5",
      ),
      aliases = setOf("alibaba_coding", "alibaba-coding", "alibaba_coding_plan", "dashscope-coding"),
      baseUrl = "https://coding-intl.dashscope.aliyuncs.com/v1",
    ),
    BuiltInProviderProfile(
      id = "moonshot",
      label = "Moonshot (Kimi OpenAI-compatible)",
      apiKeyLabel = "Moonshot API key",
      defaultModel = "kimi-k2-turbo-preview",
      suggestedModels = listOf("kimi-k2-turbo-preview", "kimi-k2.6", "kimi-k2.5", "kimi-k2-thinking"),
      aliases = setOf("kimi", "moonshot", "moonshot-ai", "kimi-openai", "kimi-coding", "kimi-for-coding"),
      baseUrl = "https://api.moonshot.ai/v1",
      defaultMaxTokens = 32_000,
      defaultHeaders = mapOf("User-Agent" to "hermes-agent/1.0"),
      defaultAuxModel = "kimi-k2-turbo-preview",
      requestProfile = ProviderRequestProfile(
        injectKimiThinking = true,
        omittedRequestFields = setOf("temperature"),
      ),
      modelContexts = contextMap(
        "kimi-k2-turbo-preview" to 262_144,
        "kimi-k2.6" to 262_144,
        "kimi-k2.5" to 262_144,
        "kimi-k2-thinking" to 262_144,
      ),
    ),
    BuiltInProviderProfile(
      id = "kimi-coding-cn",
      label = "Moonshot China (Kimi OpenAI-compatible)",
      apiKeyLabel = "Moonshot CN API key",
      defaultModel = "kimi-k2-turbo-preview",
      suggestedModels = listOf("kimi-k2-turbo-preview", "kimi-k2.6", "kimi-k2.5", "kimi-k2-thinking"),
      aliases = setOf("kimi-cn", "moonshot-cn"),
      baseUrl = "https://api.moonshot.cn/v1",
      defaultMaxTokens = 32_000,
      defaultHeaders = mapOf("User-Agent" to "hermes-agent/1.0"),
      defaultAuxModel = "kimi-k2-turbo-preview",
      requestProfile = ProviderRequestProfile(
        injectKimiThinking = true,
        omittedRequestFields = setOf("temperature"),
      ),
      modelContexts = contextMap(
        "kimi-k2-turbo-preview" to 262_144,
        "kimi-k2.6" to 262_144,
        "kimi-k2.5" to 262_144,
        "kimi-k2-thinking" to 262_144,
      ),
    ),
    BuiltInProviderProfile(
      id = "arcee",
      label = "Arcee AI",
      apiKeyLabel = "Arcee AI API key",
      defaultModel = "trinity-large-thinking",
      suggestedModels = listOf("trinity-large-thinking", "trinity-large-preview", "trinity-mini"),
      aliases = setOf("arcee-ai", "arceeai"),
      baseUrl = "https://api.arcee.ai/api/v1",
      defaultContext = hermesContext(262_144),
    ),
    BuiltInProviderProfile(
      id = "gmi",
      label = "GMI Cloud",
      apiKeyLabel = "GMI API key",
      defaultModel = "deepseek-ai/DeepSeek-V3.2",
      suggestedModels = listOf(
        "deepseek-ai/DeepSeek-V3.2",
        "zai-org/GLM-5.1-FP8",
        "moonshotai/Kimi-K2.5",
        "google/gemini-3.1-flash-lite-preview",
        "anthropic/claude-sonnet-4.6",
        "openai/gpt-5.4",
      ),
      aliases = setOf("gmi-cloud", "gmicloud"),
      baseUrl = "https://api.gmi-serving.com/v1",
      defaultHeaders = mapOf("User-Agent" to "HermesAgent/1.0"),
      defaultAuxModel = "google/gemini-3.1-flash-lite-preview",
      modelContexts = contextMap(
        "deepseek-ai/DeepSeek-V3.2" to 65_536,
        "zai-org/GLM-5.1-FP8" to 202_752,
        "moonshotai/Kimi-K2.5" to 262_144,
        "google/gemini-3.1-flash-lite-preview" to 1_048_576,
        "anthropic/claude-sonnet-4.6" to 1_000_000,
        "openai/gpt-5.4" to 1_050_000,
      ),
    ),
    BuiltInProviderProfile(
      id = "nvidia",
      label = "NVIDIA NIM",
      apiKeyLabel = "NVIDIA API key",
      defaultModel = "nvidia/llama-3.3-70b-instruct",
      suggestedModels = listOf(
        "nvidia/llama-3.3-70b-instruct",
        "nvidia/llama-3.1-nemotron-70b-instruct",
      ),
      aliases = setOf("nvidia-nim"),
      baseUrl = "https://integrate.api.nvidia.com/v1",
      defaultMaxTokens = 16_384,
      modelContexts = contextMap(
        "nvidia/llama-3.3-70b-instruct" to 131_072,
        "nvidia/llama-3.1-nemotron-70b-instruct" to 131_072,
      ),
    ),
    BuiltInProviderProfile(
      id = "novita",
      label = "NovitaAI",
      apiKeyLabel = "Novita API key",
      defaultModel = "deepseek/deepseek-v3-0324",
      suggestedModels = listOf(
        "deepseek/deepseek-v3-0324",
        "moonshotai/kimi-k2.5",
        "minimax/minimax-m2.7",
        "zai-org/glm-5",
        "deepseek/deepseek-r1-0528",
        "qwen/qwen3-235b-a22b-fp8",
      ),
      aliases = setOf("novita-ai", "novitaai"),
      baseUrl = "https://api.novita.ai/openai/v1",
      defaultAuxModel = "deepseek/deepseek-v3-0324",
      modelContexts = contextMap(
        "deepseek/deepseek-v3-0324" to 128_000,
        "moonshotai/kimi-k2.5" to 262_144,
        "minimax/minimax-m2.7" to 204_800,
        "zai-org/glm-5" to 202_752,
        "deepseek/deepseek-r1-0528" to 128_000,
        "qwen/qwen3-235b-a22b-fp8" to 262_144,
      ),
    ),
    BuiltInProviderProfile(
      id = "kilocode",
      label = "Kilo Code",
      apiKeyLabel = "Kilo Code API key",
      defaultModel = "google/gemini-3-flash-preview",
      suggestedModels = listOf(
        "anthropic/claude-opus-4.6",
        "anthropic/claude-sonnet-4.6",
        "openai/gpt-5.4",
        "google/gemini-3-pro-preview",
        "google/gemini-3-flash-preview",
      ),
      aliases = setOf("kilo-code", "kilo", "kilo-gateway"),
      baseUrl = "https://api.kilo.ai/api/gateway",
      defaultAuxModel = "google/gemini-3-flash-preview",
    ),
    BuiltInProviderProfile(
      id = "opencode-zen",
      label = "OpenCode Zen",
      apiKeyLabel = "OpenCode Zen API key",
      defaultModel = "kimi-k2.5",
      suggestedModels = listOf(
        "kimi-k2.5",
        "gpt-5.4-pro",
        "gpt-5.4",
        "gpt-5.3-codex",
        "claude-sonnet-4-6",
        "gemini-3-flash",
        "minimax-m2.7",
        "glm-5",
        "qwen3-coder",
      ),
      aliases = setOf("opencode", "opencode_zen", "zen"),
      baseUrl = "https://opencode.ai/zen/v1",
      defaultAuxModel = "gemini-3-flash",
    ),
    BuiltInProviderProfile(
      id = "opencode-go",
      label = "OpenCode Go",
      apiKeyLabel = "OpenCode Go API key",
      defaultModel = "kimi-k2.6",
      suggestedModels = listOf(
        "kimi-k2.6",
        "kimi-k2.5",
        "glm-5.1",
        "glm-5",
        "mimo-v2.5-pro",
        "mimo-v2.5",
        "minimax-m2.7",
        "qwen3.6-plus",
      ),
      aliases = setOf("opencode_go", "go", "opencode-go-sub"),
      baseUrl = "https://opencode.ai/zen/go/v1",
      defaultAuxModel = "glm-5",
      modelContexts = contextMap(
        "mimo-v2.5-pro" to 1_048_576,
        "mimo-v2.5" to 1_048_576,
        "mimo-v2-pro" to 1_048_576,
        "mimo-v2-omni" to 262_144,
      ),
    ),
    BuiltInProviderProfile(
      id = "zai",
      label = "Z.AI (GLM)",
      apiKeyLabel = "Z.AI API key",
      defaultModel = "glm-5",
      suggestedModels = listOf("glm-5", "glm-4-9b", "glm-4.5-flash"),
      aliases = setOf("glm", "z-ai", "z.ai", "zhipu"),
      baseUrl = "https://api.z.ai/api/paas/v4",
      defaultAuxModel = "glm-4.5-flash",
      defaultContext = hermesContext(202_752),
    ),
    BuiltInProviderProfile(
      id = "stepfun",
      label = "StepFun",
      apiKeyLabel = "StepFun API key",
      defaultModel = "step-3.5-flash",
      suggestedModels = listOf("step-3.5-flash"),
      aliases = setOf("step", "stepfun-coding-plan"),
      baseUrl = "https://api.stepfun.ai/step_plan/v1",
      defaultAuxModel = "step-3.5-flash",
    ),
    BuiltInProviderProfile(
      id = "huggingface",
      label = "HuggingFace",
      apiKeyLabel = "HuggingFace token",
      defaultModel = "moonshotai/Kimi-K2.5",
      suggestedModels = listOf(
        "moonshotai/Kimi-K2.5",
        "Qwen/Qwen3.5-397B-A17B",
        "Qwen/Qwen3.5-35B-A3B",
        "deepseek-ai/DeepSeek-V3.2",
        "MiniMaxAI/MiniMax-M2.5",
        "zai-org/GLM-5",
        "XiaomiMiMo/MiMo-V2-Flash",
        "moonshotai/Kimi-K2-Thinking",
        "moonshotai/Kimi-K2.6",
      ),
      aliases = setOf("hf", "hugging-face", "huggingface-hub"),
      baseUrl = "https://router.huggingface.co/v1",
      modelContexts = contextMap(
        "moonshotai/Kimi-K2.5" to 262_144,
        "Qwen/Qwen3.5-397B-A17B" to 131_072,
        "Qwen/Qwen3.5-35B-A3B" to 131_072,
        "deepseek-ai/DeepSeek-V3.2" to 65_536,
        "MiniMaxAI/MiniMax-M2.5" to 204_800,
        "zai-org/GLM-5" to 202_752,
        "XiaomiMiMo/MiMo-V2-Flash" to 262_144,
        "moonshotai/Kimi-K2-Thinking" to 262_144,
        "moonshotai/Kimi-K2.6" to 262_144,
      ),
    ),
    BuiltInProviderProfile(
      id = "ollama-cloud",
      label = "Ollama Cloud",
      apiKeyLabel = "Ollama API key",
      defaultModel = "nemotron-3-nano:30b",
      suggestedModels = listOf("nemotron-3-nano:30b"),
      aliases = setOf("ollama_cloud"),
      baseUrl = "https://ollama.com/v1",
      defaultAuxModel = "nemotron-3-nano:30b",
      defaultContext = hermesContext(131_072),
    ),
    BuiltInProviderProfile(
      id = "lmstudio",
      label = "LM Studio",
      apiKeyLabel = "LM Studio API key (optional)",
      defaultModel = "local-model",
      suggestedModels = listOf("local-model"),
      aliases = setOf("lm-studio", "lm_studio"),
      baseUrl = "http://127.0.0.1:1234/v1",
      requestProfile = ProviderRequestProfile(injectLmStudioReasoning = true),
    ),
    BuiltInProviderProfile(
      id = "xiaomi",
      label = "Xiaomi MiMo",
      apiKeyLabel = "Xiaomi API key",
      defaultModel = "mimo-v2.5-pro",
      suggestedModels = listOf("mimo-v2.5-pro", "mimo-v2.5", "mimo-v2-pro", "mimo-v2-omni", "mimo-v2-flash"),
      aliases = setOf("mimo", "xiaomi-mimo"),
      baseUrl = "https://api.xiaomimimo.com/v1",
      supportsHealthCheck = false,
      modelContexts = contextMap(
        "mimo-v2.5-pro" to 1_048_576,
        "mimo-v2.5" to 1_048_576,
        "mimo-v2-pro" to 1_048_576,
        "mimo-v2-omni" to 262_144,
        "mimo-v2-flash" to 262_144,
      ),
    ),
    BuiltInProviderProfile(
      id = "tencent-tokenhub",
      label = "Tencent TokenHub",
      apiKeyLabel = "Tencent TokenHub API key",
      defaultModel = "hy3-preview",
      suggestedModels = listOf("hy3-preview"),
      aliases = setOf("tencent", "tokenhub", "tencent-cloud", "tencentmaas"),
      baseUrl = "https://tokenhub.tencentmaas.com/v1",
      defaultAuxModel = "hy3-preview",
      requestProfile = ProviderRequestProfile(injectTencentTokenHubReasoning = true),
      modelContexts = mapOf("hy3-preview" to reasoningContext(262_144)),
    ),
  )

  val providers = listOf(
    ModelProviderSpec(
      id = "deepseek",
      label = "DeepSeek",
      apiKeyLabel = "DeepSeek API key",
      defaultModel = "deepseek-v4-pro",
      suggestedModels = listOf("deepseek-v4-pro", "deepseek-v4-flash", "deepseek-chat", "deepseek-reasoner"),
      llmProvider = LLMProvider.DeepSeek,
      transport = ProviderTransport.FloveraDeepSeekChatCompletions,
      baseUrl = "https://api.deepseek.com",
      defaultMaxTokens = 32_000,
      modelContexts = listOf(
        "deepseek-v4-pro",
        "deepseek-v4-flash",
        "deepseek-chat",
        "deepseek-reasoner",
      ).associateWith {
        ModelContextSpec(
          contextWindowTokens = 1_000_000,
          source = "deepseek_catalog",
          usageSource = "estimate",
          compressionThresholdPercent = 82,
        )
      },
    ),
  ) + openAICompatibleProviderProfiles.map { it.toSpec() } + listOf(
    ModelProviderSpec(
      id = "custom-openai",
      label = "Custom OpenAI-compatible",
      apiKeyLabel = "Custom provider API key",
      defaultModel = "custom-model",
      suggestedModels = listOf("custom-model", "gpt-oss-120b", "qwen3-coder", "deepseek-chat"),
      llmProvider = LLMProvider.OpenAI,
      transport = ProviderTransport.FloveraOpenAICompatibleChatCompletions,
      aliases = setOf("custom", "ollama", "local", "vllm", "llamacpp", "llama.cpp", "llama-cpp", "openai-compatible-custom"),
      supportsHealthCheck = false,
    ),
    ModelProviderSpec(
      id = "openai-codex",
      label = "OpenAI Codex",
      apiKeyLabel = "OpenAI Codex OAuth access token",
      defaultModel = "gpt-5.3-codex",
      suggestedModels = listOf("gpt-5.3-codex", "gpt-5.2", "gpt-5.4"),
      llmProvider = LLMProvider.OpenAI,
      transport = ProviderTransport.FloveraCodexResponses,
      apiMode = ProviderApiMode.CodexResponses,
      aliases = setOf("codex", "openai_codex"),
      baseUrl = "https://chatgpt.com/backend-api/codex",
      responsesPath = "responses",
      modelsPath = "models",
      authType = ProviderAuthType.OAuthExternal,
      supportsHealthCheck = false,
      defaultAuxModel = "gpt-5.3-codex",
      defaultContext = reasoningContext(1_050_000),
    ),
    ModelProviderSpec(
      id = "copilot",
      label = "GitHub Copilot",
      apiKeyLabel = "GitHub Copilot token",
      defaultModel = "gpt-5",
      suggestedModels = listOf("gpt-5", "gpt-5-mini", "gpt-5.3-codex", "claude-sonnet-4.6", "o3-mini"),
      llmProvider = LLMProvider.OpenAI,
      transport = ProviderTransport.FloveraOpenAICompatibleChatCompletions,
      aliases = setOf("github-copilot", "github-models", "github-model", "github"),
      baseUrl = "https://api.githubcopilot.com",
      modelsUrl = "https://api.githubcopilot.com/models",
      chatCompletionsPath = "/chat/completions",
      responsesPath = "responses",
      messagesPath = "/v1/messages",
      modelsPath = "models",
      authType = ProviderAuthType.Copilot,
      defaultHeaders = copilotDefaultHeaders(),
      requestProfile = ProviderRequestProfile(injectCopilotReasoning = true),
      modelContexts = mapOf(
        "gpt-5" to reasoningContext(1_050_000),
        "gpt-5-mini" to reasoningContext(1_050_000),
        "gpt-5.3-codex" to reasoningContext(1_050_000),
        "o3-mini" to reasoningContext(200_000),
        "claude-sonnet-4.6" to hermesContext(200_000),
      ),
      defaultContext = hermesContext(200_000),
    ),
    ModelProviderSpec(
      id = "bedrock",
      label = "AWS Bedrock",
      apiKeyLabel = "AWS SDK credentials",
      defaultModel = "us.anthropic.claude-sonnet-4-6",
      suggestedModels = listOf(
        "us.anthropic.claude-sonnet-4-6",
        "us.anthropic.claude-opus-4-6-v1",
        "us.anthropic.claude-haiku-4-5-20251001-v1:0",
        "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
        "us.amazon.nova-pro-v1:0",
        "us.amazon.nova-lite-v1:0",
        "us.amazon.nova-micro-v1:0",
        "deepseek.v3.2",
        "us.meta.llama4-maverick-17b-instruct-v1:0",
        "us.meta.llama4-scout-17b-instruct-v1:0",
      ),
      llmProvider = LLMProvider.Bedrock,
      transport = ProviderTransport.KoogBedrockConverse,
      apiMode = ProviderApiMode.BedrockConverse,
      aliases = setOf("aws", "aws-bedrock", "amazon-bedrock", "amazon"),
      baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
      authType = ProviderAuthType.AwsSdk,
      supportsHealthCheck = false,
      defaultAuxModel = "us.anthropic.claude-sonnet-4-6",
      modelContexts = mapOf(
        "us.anthropic.claude-sonnet-4-6" to hermesContext(200_000),
        "us.anthropic.claude-opus-4-6-v1" to hermesContext(200_000),
        "us.anthropic.claude-haiku-4-5-20251001-v1:0" to hermesContext(200_000),
        "us.anthropic.claude-sonnet-4-5-20250929-v1:0" to hermesContext(200_000),
        "us.amazon.nova-pro-v1:0" to hermesContext(300_000),
        "us.amazon.nova-lite-v1:0" to hermesContext(300_000),
        "us.amazon.nova-micro-v1:0" to hermesContext(128_000),
        "deepseek.v3.2" to hermesContext(128_000),
        "us.meta.llama4-maverick-17b-instruct-v1:0" to hermesContext(128_000),
        "us.meta.llama4-scout-17b-instruct-v1:0" to hermesContext(128_000),
      ),
      defaultContext = hermesContext(128_000),
    ),
    ModelProviderSpec(
      id = "gemini",
      label = "Google AI Studio",
      apiKeyLabel = "Google API key",
      defaultModel = "gemini-3-flash-preview",
      suggestedModels = listOf(
        "gemini-3-flash-preview",
        "gemini-3-pro-preview",
        "gemini-3.1-pro-preview",
        "gemini-3.1-flash-lite-preview",
        "gemini-2.5-pro",
      ),
      llmProvider = LLMProvider.Google,
      transport = ProviderTransport.KoogGoogleGeminiNative,
      aliases = setOf("google", "google-gemini", "google-ai-studio"),
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
      defaultAuxModel = "gemini-3-flash-preview",
      modelContexts = mapOf(
        "gemini-3-flash-preview" to hermesContext(1_048_576),
        "gemini-3-pro-preview" to hermesContext(1_048_576),
        "gemini-3.1-pro-preview" to hermesContext(1_048_576),
        "gemini-3.1-flash-lite-preview" to hermesContext(1_048_576),
        "gemini-2.5-pro" to hermesContext(1_048_576),
      ),
    ),
    ModelProviderSpec(
      id = "xai",
      label = "xAI",
      apiKeyLabel = "xAI API key",
      defaultModel = "grok-4.3",
      suggestedModels = listOf(
        "grok-4.3",
        "grok-4.20-0309-reasoning",
        "grok-4.20-0309-non-reasoning",
        "grok-4.20-multi-agent-0309",
        "grok-3-mini",
      ),
      llmProvider = LLMProvider.OpenAI,
      transport = ProviderTransport.FloveraCodexResponses,
      apiMode = ProviderApiMode.CodexResponses,
      aliases = setOf("grok", "x-ai", "x.ai"),
      baseUrl = "https://api.x.ai/v1",
      modelsUrl = "https://api.x.ai/v1/models",
      responsesPath = "responses",
      modelsPath = "models",
      defaultAuxModel = "grok-4.3",
      modelContexts = mapOf(
        "grok-4.3" to reasoningContext(1_000_000),
        "grok-4.20-0309-reasoning" to reasoningContext(2_000_000),
        "grok-4.20-0309-non-reasoning" to reasoningContext(2_000_000),
        "grok-4.20-multi-agent-0309" to reasoningContext(2_000_000),
        "grok-3-mini" to reasoningContext(131_072),
      ),
      defaultContext = reasoningContext(128_000),
    ),
    ModelProviderSpec(
      id = "openrouter",
      label = "OpenRouter",
      apiKeyLabel = "OpenRouter API key",
      defaultModel = "anthropic/claude-sonnet-4.6",
      suggestedModels = listOf(
        "anthropic/claude-sonnet-4.6",
        "openai/gpt-5.4",
        "deepseek/deepseek-chat",
        "google/gemini-3-flash-preview",
        "qwen/qwen3-plus",
      ),
      llmProvider = LLMProvider.OpenRouter,
      transport = ProviderTransport.FloveraOpenAICompatibleChatCompletions,
      aliases = setOf("router", "or"),
      baseUrl = "https://openrouter.ai/api/v1",
      modelsUrl = "https://openrouter.ai/api/v1/models",
      requestProfile = ProviderRequestProfile(injectOpenRouterRouting = true),
      modelContexts = mapOf(
        "anthropic/claude-sonnet-4.6" to reasoningContext(1_000_000),
        "openai/gpt-5.4" to reasoningContext(1_050_000),
        "deepseek/deepseek-chat" to hermesContext(163_840),
        "google/gemini-3-flash-preview" to reasoningContext(1_048_576),
        "openrouter/pareto-code" to hermesContext(2_000_000),
      ),
    ),
    ModelProviderSpec(
      id = "minimax",
      label = "MiniMax",
      apiKeyLabel = "MiniMax API key",
      defaultModel = "MiniMax-M2.7",
      suggestedModels = listOf("MiniMax-M2.7", "MiniMax-M2.5", "MiniMax-M2.1", "MiniMax-M2"),
      llmProvider = LLMProvider.Anthropic,
      transport = ProviderTransport.FloveraAnthropicMessages,
      apiMode = ProviderApiMode.AnthropicMessages,
      aliases = setOf("mini-max", "minimax-global"),
      baseUrl = "https://api.minimax.io/anthropic",
      modelsUrl = "https://api.minimax.io/anthropic/v1/models",
      authType = ProviderAuthType.BearerToken,
      defaultHeaders = mapOf("anthropic-beta" to "interleaved-thinking-2025-05-14"),
      defaultAuxModel = "MiniMax-M2.7",
      defaultContext = hermesContext(131_072),
    ),
    ModelProviderSpec(
      id = "minimax-cn",
      label = "MiniMax China",
      apiKeyLabel = "MiniMax CN API key",
      defaultModel = "MiniMax-M2.7",
      suggestedModels = listOf("MiniMax-M2.7", "MiniMax-M2.5", "MiniMax-M2.1", "MiniMax-M2"),
      llmProvider = LLMProvider.Anthropic,
      transport = ProviderTransport.FloveraAnthropicMessages,
      apiMode = ProviderApiMode.AnthropicMessages,
      aliases = setOf("minimax-china", "minimax_cn"),
      baseUrl = "https://api.minimaxi.com/anthropic",
      modelsUrl = "https://api.minimaxi.com/anthropic/v1/models",
      authType = ProviderAuthType.BearerToken,
      defaultHeaders = mapOf("anthropic-beta" to "interleaved-thinking-2025-05-14"),
      defaultAuxModel = "MiniMax-M2.7",
      defaultContext = hermesContext(131_072),
    ),
    ModelProviderSpec(
      id = "minimax-oauth",
      label = "MiniMax OAuth",
      apiKeyLabel = "MiniMax OAuth access token",
      defaultModel = "MiniMax-M2.7-highspeed",
      suggestedModels = listOf("MiniMax-M2.7-highspeed", "MiniMax-M2.7", "MiniMax-M2.5", "MiniMax-M2.1", "MiniMax-M2"),
      llmProvider = LLMProvider.Anthropic,
      transport = ProviderTransport.FloveraAnthropicMessages,
      apiMode = ProviderApiMode.AnthropicMessages,
      aliases = setOf("minimax_oauth", "minimax-oauth-io"),
      baseUrl = "https://api.minimax.io/anthropic",
      modelsUrl = "https://api.minimax.io/anthropic/v1/models",
      authType = ProviderAuthType.OAuthExternal,
      defaultHeaders = mapOf("anthropic-beta" to "interleaved-thinking-2025-05-14"),
      defaultAuxModel = "MiniMax-M2.7-highspeed",
      defaultContext = hermesContext(131_072),
    ),
    ModelProviderSpec(
      id = "anthropic",
      label = "Anthropic",
      apiKeyLabel = "Anthropic API key",
      defaultModel = "claude-sonnet-4-6",
      suggestedModels = listOf(
        "claude-opus-4-7",
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-sonnet-4-5-20250929",
        "claude-haiku-4-5-20251001",
      ),
      llmProvider = LLMProvider.Anthropic,
      transport = ProviderTransport.FloveraAnthropicMessages,
      apiMode = ProviderApiMode.AnthropicMessages,
      aliases = setOf("claude", "claude-code"),
      baseUrl = "https://api.anthropic.com",
      modelsUrl = "https://api.anthropic.com/v1/models",
      defaultHeaders = mapOf(
        "anthropic-beta" to "interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14",
      ),
    ),
  )

  val defaultProvider: ModelProviderSpec = providers.first()

  val supportedApiModes: List<String> = providers
    .map { it.apiMode.id }
    .distinct()

  fun findProvider(providerId: String): ModelProviderSpec? {
    val normalized = providerId.trim().lowercase()
    if (normalized.isBlank()) return null
    return providers.firstOrNull { provider ->
      provider.id == normalized || normalized in provider.aliases
    }
  }

  fun requireProvider(providerId: String): ModelProviderSpec {
    return findProvider(providerId) ?: error("Unsupported model provider: $providerId")
  }

  fun runtimeProfileFor(settings: AppSettings): ProviderRuntimeProfile {
    val provider = findProvider(settings.provider) ?: defaultProvider
    return runtimeProfileFor(provider, settings)
  }

  fun runtimeProfileFor(provider: ModelProviderSpec, settings: AppSettings): ProviderRuntimeProfile {
    val customProfile = settings.customOpenAIProvider.takeIf {
      provider.id == "custom-openai" || provider.id == "azure-foundry"
    }
    val apiMode = runtimeApiModeFor(provider, settings)
    val transport = runtimeTransportFor(provider, apiMode)
    val baseUrl = customProfile?.baseUrl?.takeIf { it.isNotBlank() } ?: provider.baseUrl
    val chatCompletionsPath = customProfile?.chatCompletionsPath?.takeIf { it.isNotBlank() }
      ?: provider.chatCompletionsPath
    val responsesPath = if (provider.id == "azure-foundry" && apiMode == ProviderApiMode.CodexResponses) {
      azureFoundryResponsesPath(chatCompletionsPath, provider.responsesPath)
    } else {
      provider.responsesPath
    }
    val modelsUrl = provider.modelsUrl.ifBlank { defaultModelsUrl(baseUrl) }
    val compatibilityMode = customProfile?.compatibilityMode ?: provider.requestProfile.compatibilityMode
    val requestProfile = provider.requestProfile.copy(
      compatibilityMode = compatibilityMode,
      injectOllamaNumCtx = compatibilityMode == "ollama",
    )
    return ProviderRuntimeProfile(
      providerId = provider.id,
      label = provider.label,
      apiMode = apiMode,
      transport = transport,
      llmProvider = provider.llmProvider,
      baseUrl = baseUrl,
      modelsUrl = modelsUrl,
      chatCompletionsPath = chatCompletionsPath,
      responsesPath = responsesPath,
      messagesPath = provider.messagesPath,
      modelsPath = provider.modelsPath,
      authType = provider.authType,
      supportsHealthCheck = provider.supportsHealthCheck,
      defaultHeaders = provider.defaultHeaders,
      defaultMaxTokens = provider.defaultMaxTokens,
      defaultAuxModel = provider.defaultAuxModel,
      requestProfile = requestProfile,
    )
  }

  private fun defaultModelsUrl(baseUrl: String): String {
    return baseUrl.takeIf { it.isNotBlank() }?.trimEnd('/')?.plus("/models").orEmpty()
  }

  private fun runtimeApiModeFor(provider: ModelProviderSpec, settings: AppSettings): ProviderApiMode {
    if (provider.id == "azure-foundry" && azureFoundryUsesResponses(settings.model)) {
      return ProviderApiMode.CodexResponses
    }
    if (provider.id == "copilot") {
      return copilotApiModeFor(settings.model)
    }
    return provider.apiMode
  }

  private fun runtimeTransportFor(provider: ModelProviderSpec, apiMode: ProviderApiMode): ProviderTransport {
    if (provider.id == "azure-foundry" && apiMode == ProviderApiMode.CodexResponses) {
      return ProviderTransport.FloveraCodexResponses
    }
    if (provider.id == "copilot") {
      return when (apiMode) {
        ProviderApiMode.CodexResponses -> ProviderTransport.FloveraCodexResponses
        ProviderApiMode.AnthropicMessages -> ProviderTransport.FloveraAnthropicMessages
        else -> ProviderTransport.FloveraOpenAICompatibleChatCompletions
      }
    }
    return provider.transport
  }

  private fun azureFoundryUsesResponses(modelId: String): Boolean {
    val normalized = modelId
      .trim()
      .lowercase()
      .substringAfterLast("/")
    return listOf("codex", "gpt-5", "o1", "o3", "o4").any { normalized.startsWith(it) }
  }

  private fun azureFoundryResponsesPath(chatCompletionsPath: String, fallback: String): String {
    val normalized = chatCompletionsPath.trim().trim('/')
    if (normalized.endsWith("chat/completions")) {
      return normalized.removeSuffix("chat/completions").trimEnd('/').let { prefix ->
        if (prefix.isBlank()) "responses" else "$prefix/responses"
      }
    }
    return fallback
  }

  private fun copilotApiModeFor(modelId: String): ProviderApiMode {
    val normalized = normalizeCopilotModelId(modelId).lowercase()
    if (normalized.startsWith("claude-") || normalized.startsWith("anthropic/claude-")) {
      return ProviderApiMode.AnthropicMessages
    }
    if (copilotUsesResponses(normalized)) {
      return ProviderApiMode.CodexResponses
    }
    return ProviderApiMode.ChatCompletions
  }

  private fun copilotUsesResponses(modelId: String): Boolean {
    val match = Regex("^gpt-(\\d+)").find(modelId) ?: return false
    val major = match.groupValues[1].toIntOrNull() ?: return false
    return major >= 5 && !modelId.startsWith("gpt-5-mini")
  }

  internal fun normalizeCopilotModelId(modelId: String): String {
    val raw = modelId.trim()
    if (raw.isBlank()) return ""
    return raw.substringAfter("/")
  }

  fun createClient(provider: ModelProviderSpec, apiKey: String, settings: AppSettings): LLMClient {
    val runtimeProfile = runtimeProfileFor(provider, settings)
    return ProviderTransportFactory.createClient(
      transport = runtimeProfile.transport,
      runtimeProfile = runtimeProfile,
      apiKey = apiKey,
      settings = settings,
      modelContext = contextFor(settings),
    )
  }

  fun contextFor(settings: AppSettings): ModelContextSpec {
    val provider = findProvider(settings.provider) ?: defaultProvider
    val model = settings.model.ifBlank { provider.defaultModel }
    val base = provider.contextFor(model)
    return base.withOverride(settings.modelContextOverrideFor(provider.id, model))
  }

  private fun ModelContextSpec.withOverride(override: ModelContextOverride?): ModelContextSpec {
    if (override == null) return this
    val window = override.contextWindowTokens?.takeIf { it > 0 }
    val threshold = override.compressionThresholdPercent?.coerceIn(1, 100)
    if (window == null && threshold == null) return this
    return copy(
      contextWindowTokens = window ?: contextWindowTokens,
      source = "user_override",
      usageSource = "estimate",
      compressionThresholdPercent = threshold ?: compressionThresholdPercent,
    )
  }
}

private val agentModelCapabilities = listOf(
  LLMCapability.Completion,
  LLMCapability.OpenAIEndpoint.Completions,
  LLMCapability.Temperature,
  LLMCapability.Tools,
  LLMCapability.ToolChoice,
  LLMCapability.Schema.JSON.Basic,
  LLMCapability.Schema.JSON.Standard,
)

private val codexResponsesModelCapabilities = listOf(
  LLMCapability.Completion,
  LLMCapability.OpenAIEndpoint.Responses,
  LLMCapability.Temperature,
  LLMCapability.Tools,
  LLMCapability.ToolChoice,
  LLMCapability.Schema.JSON.Basic,
  LLMCapability.Schema.JSON.Standard,
)

private fun agentModelCapabilitiesFor(apiMode: ProviderApiMode): List<LLMCapability> {
  return when (apiMode) {
    ProviderApiMode.CodexResponses -> codexResponsesModelCapabilities
    else -> agentModelCapabilities
  }
}
