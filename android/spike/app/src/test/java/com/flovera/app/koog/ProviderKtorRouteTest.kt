package com.flovera.app.koog

import ai.koog.prompt.llm.LLMProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderKtorRouteTest {
  @Test
  fun openAIStyleBaseUrlKeepsSingleV1Prefix() {
    val route = providerKtorRoute(
      profile(
        baseUrl = "https://api.openai.com/v1",
        chatCompletionsPath = "/v1/chat/completions",
        modelsPath = "/v1/models",
      ),
    )

    assertEquals("https://api.openai.com", route.baseUrl)
    assertEquals("v1/chat/completions", route.chatCompletionsPath)
    assertEquals("v1/models", route.modelsPath)
  }

  @Test
  fun vendorBasePathIsPreservedForChatCompletions() {
    val alibaba = providerKtorRoute(
      profile(
        baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        chatCompletionsPath = "/v1/chat/completions",
      ),
    )
    val zai = providerKtorRoute(
      profile(
        baseUrl = "https://api.z.ai/api/paas/v4",
        chatCompletionsPath = "chat/completions",
      ),
    )

    assertEquals("https://dashscope-intl.aliyuncs.com", alibaba.baseUrl)
    assertEquals("compatible-mode/v1/chat/completions", alibaba.chatCompletionsPath)
    assertEquals("https://api.z.ai", zai.baseUrl)
    assertEquals("api/paas/v4/chat/completions", zai.chatCompletionsPath)
  }

  @Test
  fun legacyHermesV1ChatPathGets404FallbackWithoutDroppingVendorPrefix() {
    val routes = providerKtorRouteCandidates(
      profile(
        baseUrl = "https://api.z.ai/api/paas/v4",
        chatCompletionsPath = "/v1/chat/completions",
      ),
    )

    assertEquals("api/paas/v4/v1/chat/completions", routes.first().chatCompletionsPath)
    assertTrue(routes.any { it.chatCompletionsPath == "api/paas/v4/chat/completions" })
  }

  @Test
  fun missingVersionSegmentGetsFallbackCandidate() {
    val routes = providerKtorRouteCandidates(
      profile(
        baseUrl = "https://api.openai.com",
        chatCompletionsPath = "chat/completions",
      ),
    )

    assertEquals("chat/completions", routes.first().chatCompletionsPath)
    assertTrue(routes.any { it.chatCompletionsPath == "v1/chat/completions" })
  }

  @Test
  fun accidentalBasePathPrefixGetsBareRequestFallbackCandidate() {
    val routes = providerKtorRouteCandidates(
      profile(
        baseUrl = "https://api.example.com/bad-prefix",
        chatCompletionsPath = "/v1/chat/completions",
      ),
    )

    assertEquals("bad-prefix/v1/chat/completions", routes.first().chatCompletionsPath)
    assertTrue(routes.any { it.chatCompletionsPath == "v1/chat/completions" })
  }

  @Test
  fun responsesAndAnthropicMessagesPreserveBasePathPrefixes() {
    val responses = providerKtorRoute(
      profile(
        baseUrl = "https://api.x.ai/v1",
        responsesPath = "responses",
      ),
    )
    val messages = providerKtorRoute(
      profile(
        baseUrl = "https://api.minimax.io/anthropic",
        messagesPath = "/v1/messages",
        modelsPath = "/v1/models",
      ),
    )

    assertEquals("https://api.x.ai", responses.baseUrl)
    assertEquals("v1/responses", responses.responsesPath)
    assertEquals("https://api.minimax.io", messages.baseUrl)
    assertEquals("anthropic/v1/messages", messages.messagesPath)
    assertEquals("anthropic/v1/models", messages.modelsPath)
  }

  private fun profile(
    baseUrl: String,
    chatCompletionsPath: String = "/v1/chat/completions",
    responsesPath: String = "v1/responses",
    messagesPath: String = "/v1/messages",
    modelsPath: String = "/v1/models",
  ): ProviderRuntimeProfile {
    return ProviderRuntimeProfile(
      providerId = "test-provider",
      label = "Test Provider",
      apiMode = ProviderApiMode.ChatCompletions,
      transport = ProviderTransport.FloveraOpenAICompatibleChatCompletions,
      llmProvider = LLMProvider.OpenAI,
      baseUrl = baseUrl,
      modelsUrl = "",
      chatCompletionsPath = chatCompletionsPath,
      responsesPath = responsesPath,
      messagesPath = messagesPath,
      modelsPath = modelsPath,
      authType = ProviderAuthType.ApiKey,
      supportsHealthCheck = true,
      defaultHeaders = emptyMap(),
      defaultMaxTokens = null,
      defaultAuxModel = "",
      requestProfile = ProviderRequestProfile(),
    )
  }
}
