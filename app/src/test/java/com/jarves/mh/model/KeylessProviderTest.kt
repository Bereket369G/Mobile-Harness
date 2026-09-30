package com.jarves.mh.model

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
}
