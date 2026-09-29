package com.jarves.mh.runtime

import com.jarves.mh.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeAcpProtocolParserTest {
    private val parser = OpenCodeAcpProtocolParser()

    @Test
    fun nonJsonLineIsIgnored() {
        assertEquals(OpenCodeAcpEvent.Ignored, parser.parseLine("plain log line"))
    }

    @Test
    fun sessionNewResultCarriesAcpSessionId() {
        val parsed = parser.parseLine(
            """{"jsonrpc":"2.0","id":2,"result":{"sessionId":"acp-123"}}""",
        )
        assertEquals(OpenCodeAcpEvent.SessionCreated("acp-123"), parsed)
    }

    @Test
    fun promptResultCarriesStopReason() {
        val parsed = parser.parseLine(
            """{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""",
        )
        assertEquals(OpenCodeAcpEvent.Result(3, "end_turn"), parsed)
    }

    @Test
    fun agentMessageChunkBecomesAssistantText() {
        val parsed = parser.parseLine(
            """{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hello from OpenCode"}}}}""",
        )
        assertEquals(OpenCodeAcpEvent.AgentChunk("Hello from OpenCode", thought = false), parsed)
    }

    @Test
    fun agentThoughtChunkIsReasoning() {
        val parsed = parser.parseLine(
            """{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"checking files"}}}}""",
        )
        assertEquals(OpenCodeAcpEvent.AgentChunk("checking files", thought = true), parsed)
    }

    @Test
    fun toolCallIsReportedWithTitleAndStatus() {
        val parsed = parser.parseLine(
            """{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"tool_call","toolCallId":"call-1","title":"Read build.gradle.kts","kind":"read","status":"pending"}}}""",
        )
        val update = parsed as OpenCodeAcpEvent.ToolUpdate
        assertEquals("call-1", update.toolCallId)
        assertEquals("Read build.gradle.kts", update.title)
        assertEquals("pending", update.status)
    }

    @Test
    fun permissionRequestIsSurfacedWithRisk() {
        val parsed = parser.parseLine(
            """{"jsonrpc":"2.0","id":9,"method":"session/request_permission","params":{"toolCall":{"toolCallId":"call-7","title":"Run bash","kind":"execute","rawInput":{"command":"rm -rf build"}},"options":[{"optionId":"allow","name":"Allow"}]}}""",
        )
        val request = parsed as OpenCodeAcpEvent.PermissionRequest
        assertEquals(9, request.requestId)
        // toolName is ACP's own title; the dangerous payload lives in the preview, not the label.
        assertEquals("Run bash", request.toolName)
        assertEquals("rm -rf build", request.commandPreview)
        assertEquals(RiskLevel.HIGH, request.risk)
    }

    @Test
    fun permissionRequestForAnEditIsReviewLevel() {
        val parsed = parser.parseLine(
            """{"jsonrpc":"2.0","id":10,"method":"session/request_permission","params":{"toolCall":{"toolCallId":"call-8","title":"Edit README.md","kind":"edit","rawInput":{"path":"README.md"}}}}""",
        )
        val request = parsed as OpenCodeAcpEvent.PermissionRequest
        assertEquals(RiskLevel.REVIEW, request.risk)
        assertTrue(request.affectedPaths.contains("README.md"))
    }

    @Test
    fun permissionRequestSurfacesCommandPreviewFromObjectRawInput() {
        // Real ACP sends rawInput as an object, so the approval card must show the command.
        val parsed = parser.parseLine(
            """{"jsonrpc":"2.0","id":11,"method":"session/request_permission","params":{"toolCall":{"toolCallId":"call-9","title":"Run bash","kind":"execute","rawInput":{"command":"ls -la"}}}}""",
        )
        val request = parsed as OpenCodeAcpEvent.PermissionRequest
        assertEquals("ls -la", request.commandPreview)
        assertEquals(RiskLevel.REVIEW, request.risk)
    }

    @Test
    fun errorResponseBecomesFailure() {
        val parsed = parser.parseLine(
            """{"jsonrpc":"2.0","id":4,"error":{"message":"provider rejected the request"}}""",
        )
        assertEquals(OpenCodeAcpEvent.Failed("provider rejected the request"), parsed)
    }

    @Test
    fun renamedToolKindStillProducesAToolUpdate() {
        // Safety net: if a client renames a tool-call kind but still tags it with a toolCallId,
        // it must still surface as a ToolUpdate so an approval card is never missed.
        val parsed = parser.parseLine(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"tool_call_v2","toolCallId":"tc9","title":"Delete build dir","kind":"execute","status":"pending"}}}""",
        )
        assertTrue(parsed is OpenCodeAcpEvent.ToolUpdate)
        val tool = parsed as OpenCodeAcpEvent.ToolUpdate
        assertEquals("tc9", tool.toolCallId)
        assertEquals("Delete build dir", tool.title)
    }

    @Test
    fun unrecognisedNonToolUpdateIsSurfacedNotDropped() {
        val parsed = parser.parseLine(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"plan","entries":["step one"]}}}""",
        )
        assertTrue(parsed is OpenCodeAcpEvent.UnknownUpdate)
        assertEquals("plan", (parsed as OpenCodeAcpEvent.UnknownUpdate).kind)
    }
}
