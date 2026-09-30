package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the "configure a provider before you can use OpenCode"
 * trap: onboarding and the model picker used to demand an API key for every
 * provider, which forced users to buy and enter an OpenRouter key just to reach
 * a free, keyless agent. These lock in that a keyless provider is genuinely
 * keyless and that key-requiring providers are unchanged.
 */
class KeylessProviderTest {
    @Test
    fun openCodeZenIsUsableWithoutAnApiKey() {
        assertTrue(ProviderKind.OPENCODE_ZEN.worksWithoutApiKey)
    }

    @Test
    fun keylessProvidersHaveAFixedEndpoint() {
        // A keyless provider must point at a known public host; otherwise
        // "works without a key" would send the user to an unreachable URL.
        val kind = ProviderKind.OPENCODE_ZEN
        assertTrue(kind.fixedBaseUrl)
        assertTrue(kind.defaultBaseUrl.startsWith("https://"))
    }

    @Test
    fun keyRequiringProvidersAreNotFlaggedKeyless() {
        // Guards against over-broadening: only genuinely anonymous providers
        // may bypass the key requirement.
        listOf(
            ProviderKind.CLAUDE,
            ProviderKind.ANTHROPIC,
            ProviderKind.LLM_ROUTER,
            ProviderKind.DEEPSEEK,
            ProviderKind.KIMI,
            ProviderKind.NVIDIA_NIM,
            ProviderKind.CUSTOM,
        ).forEach { kind ->
            assertFalse("${kind.name} must still require a key", kind.worksWithoutApiKey)
        }
    }

    @Test
    fun openCodeDefaultsToTheKeylessZenProvider() {
        // providersForAgent filters the enum in declaration order, so assert
        // membership rather than position.
        assertTrue(ProviderKind.OPENCODE_ZEN in providersForAgent(AgentKind.OPENCODE))
    }

    @Test
    fun zenUsesChatCompletationsNotTheResponsesApi() {
        // The user-visible symptom: every free model failed setup with
        // `401 ModelError: ... not supported for format openai`, because the app
        // POSTed to /v1/responses. Zen serves these models on /chat/completions,
        // and the opencode binary itself declares @ai-sdk/openai-compatible.
        // See docs/ZEN_FREE_MODELS.md.
        val zen = ProviderKind.OPENCODE_ZEN
        assertEquals(ProviderProtocol.OPENAI_CHAT, zen.protocol)
        assertTrue(
            "protocol must be pinned so a stored openai-responses value cannot win",
            zen.fixedProtocol,
        )
    }

    @Test
    fun zenDefaultModelIsTheStableFreeModel() {
        // Not `space-bunny-free`: it is a novelty model that OpenCode can remove at
        // any time, so it must never be the default a new user lands on. It also
        // must not be a paid model (the old default `deepseek-v4-flash` returns
        // `401 AuthError: Missing API key` for a provider advertised as keyless).
        val default = ProviderKind.OPENCODE_ZEN.defaultModel
        assertEquals("big-pickle", default)
        assertTrue("default must be reachable keylessly", ProviderKind.OPENCODE_ZEN.worksWithoutApiKey)
    }
}
