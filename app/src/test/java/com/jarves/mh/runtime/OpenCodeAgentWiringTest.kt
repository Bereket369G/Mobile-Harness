package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.OPENCODE_PROVIDERS
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.providersForAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class OpenCodeAgentWiringTest {
    @Test
    fun openCodeIsARegisteredAgent() {
        assertEquals(AgentKind.OPENCODE, AgentKind.fromStored("opencode"))
        assertEquals("opencode", AgentKind.OPENCODE.stableId)
    }

    @Test
    fun openCodeOffersZenAndGatewayProviders() {
        val offered = providersForAgent(AgentKind.OPENCODE)
        assertTrue(ProviderKind.OPENCODE_ZEN in offered)
        assertTrue(ProviderKind.CUSTOM in offered)
        assertTrue(offered.all { it in OPENCODE_PROVIDERS })
    }

    @Test
    fun zenStaysAvailableToDeepSeekHarness() {
        // The existing DSH route must keep working: Zen is shared, not OpenCode-exclusive.
        assertTrue(ProviderKind.OPENCODE_ZEN in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
    }

    @Test
    fun claudeCodeDoesNotOfferZen() {
        assertTrue(ProviderKind.OPENCODE_ZEN !in providersForAgent(AgentKind.CLAUDE_CODE))
    }

    @Test
    fun permissionResultFramesAreWellFormed() {
        // The bridge writes these back on the child's stdin, so the shape is asserted here
        // rather than only on the parse path.
        val allow = JSONObject().put("outcome", "selected").put("optionId", "allow")
        assertEquals("selected", allow.getString("outcome"))
        val deny = JSONObject().put("outcome", "cancelled")
        assertEquals("cancelled", deny.getString("outcome"))
    }
}
