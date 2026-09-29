package com.jarves.mh.runtime

import com.jarves.mh.model.RiskLevel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Protocol events surfaced by [OpenCodeAcpProtocolParser] from `opencode acp` nd-JSON lines.
 *
 * The Agent Client Protocol (ACP) is the same shape the DeepSeek Harness SDK profile uses:
 * newline-delimited JSON-RPC 2.0 on stdin/stdout. `opencode acp` is configured by every ACP
 * editor as `{"command":"opencode","args":["acp"]}`.
 */
internal sealed interface OpenCodeAcpEvent {
    data object Ignored : OpenCodeAcpEvent

    /** `initialize` result received; the client may now create a session. */
    data object Initialized : OpenCodeAcpEvent

    /** `session/new` result carrying the ACP session id. */
    data class SessionCreated(val acpSessionId: String) : OpenCodeAcpEvent

    /** Result for a request we sent (e.g. prompt accepted, turn finished). */
    data class Result(val id: Any, val stopReason: String) : OpenCodeAcpEvent

    /** Error result for a request we sent. */
    data class Failed(val message: String) : OpenCodeAcpEvent

    /** `session/update` notification payload. */
    data class SessionUpdate(val update: JSONObject) : OpenCodeAcpEvent

    /** A tool call started (`session/update` with `tool_call`/`tool_call_update`). */
    data class ToolUpdate(
        val toolCallId: String,
        val title: String,
        val kind: String,
        val status: String,
        val detail: String,
    ) : OpenCodeAcpEvent

    /** Assistant text delta (`agent_message_chunk` / `agent_thought_chunk`). */
    data class AgentChunk(val text: String, val thought: Boolean) : OpenCodeAcpEvent

    /**
     * A `session/update` whose `sessionUpdate` kind we do not recognise, passed through verbatim.
     *
     * The ACP spec fixes the kind strings, but a client can add its own. Rather than drop such
     * updates on the floor (which would silently hide, say, a tool call whose kind name shifted),
     * they are surfaced so the bridge can log them once and be extended without a blind spot.
     */
    data class UnknownUpdate(val kind: String, val update: JSONObject) : OpenCodeAcpEvent

    /** An agent → client permission request; the bridge must reply with allow/deny. */
    data class PermissionRequest(
        val requestId: Any,
        val toolCallId: String,
        val toolName: String,
        val detail: String,
        val affectedPaths: List<String>,
        val commandPreview: String?,
        val risk: RiskLevel,
    ) : OpenCodeAcpEvent
}

/**
 * Incremental nd-JSON reader for the Agent Client Protocol.
 *
 * Each line is a JSON-RPC 2.0 object. Requests that carry an `id` and a `method` are inbound
 * permission prompts; notifications carry a `method` with no `id`; responses to our own requests
 * carry an `id` plus `result` or `error`.
 */
internal class OpenCodeAcpProtocolParser {
    private companion object {
        /** Risk keywords that make an ACP permission request warrant a foreground approval card. */
        val HIGH_RISK_TOKENS = listOf("rm -rf", "sudo", "curl", "wget", "chmod", "kill", "dd ", "mkfs", "> /", "git push")

        /** Tools that touch the workspace without being outright destructive. */
        val EDIT_OR_EXEC_TOKENS = listOf(
            "edit",
            "write",
            "create",
            "delete",
            "move",
            "bash",
            "execute",
            "run",
            "patch",
            "apply",
        )
    }

    fun parseLine(line: String): OpenCodeAcpEvent {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return OpenCodeAcpEvent.Ignored
        val frame = runCatching { JSONObject(trimmed) }.getOrNull() ?: return OpenCodeAcpEvent.Ignored

        // Error response to one of our requests.
        frame.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { "OpenCode reported an error" }
            return OpenCodeAcpEvent.Failed(message)
        }

        // Successful response to one of our requests.
        if (frame.has("id") && !frame.has("method")) {
            val id = frame.opt("id") ?: JSONObject.NULL
            val result = frame.optJSONObject("result")
            return when {
                result == null -> OpenCodeAcpEvent.Result(id, "")
                result.has("sessionId") -> OpenCodeAcpEvent.SessionCreated(result.optString("sessionId"))
                // session/prompt resolves with { "stopReason": "end_turn" | "max_tokens" | ... }.
                else -> OpenCodeAcpEvent.Result(id, result.optString("stopReason"))
            }
        }

        val method = frame.optString("method")
        if (method.isEmpty()) return OpenCodeAcpEvent.Ignored

        // Inbound permission request (has an id and expects a response).
        if (method == "session/request_permission" && frame.has("id")) {
            return parsePermission(frame)
        }

        if (method == "session/update") {
            return parseSessionUpdate(frame.optJSONObject("params")?.optJSONObject("update"))
        }

        // Any other notification or request is not something this bridge acts on yet.
        return OpenCodeAcpEvent.Ignored
    }

    private fun parsePermission(frame: JSONObject): OpenCodeAcpEvent {
        val params = frame.optJSONObject("params") ?: JSONObject()
        val toolCall = params.optJSONObject("toolCall") ?: JSONObject()
        val requestId = frame.opt("id") ?: JSONObject.NULL

        val toolName = toolCall.optString("title").ifBlank { toolCall.optString("kind").ifBlank { "tool" } }
        // ACP sends `rawInput` as a tool-specific object; some agents send it as a JSON string.
        // Normalise both to a compact readable string so the approval card shows the real input.
        val rawInput = toolCall.opt("rawInput")
        val rawInputText = when (rawInput) {
            null, JSONObject.NULL -> ""
            is JSONObject -> rawInput.toString()
            is JSONArray -> rawInput.toString()
            else -> rawInput.toString()
        }.trim()

        val detail = rawInputText
            .takeIf { it.isNotBlank() && it != "{}" && it != "[]" }
            ?.take(600)
            ?: toolCall.optString("description").ifBlank { "OpenCode is requesting permission to continue." }

        val paths = collectPaths(toolCall)
        // Prefer the most dangerous field for the one-line preview shown on the approval card.
        val commandPreview = rawInput
            .let { it as? JSONObject }
            ?.optString("command")
            ?.takeIf { it.isNotBlank() }
            ?.take(400)
            ?: rawInputText.takeIf { it.isNotBlank() && it != "{}" }?.take(400)
        val risk = classifyRisk(toolName, "$detail $toolName")

        return OpenCodeAcpEvent.PermissionRequest(
            requestId = requestId,
            toolCallId = toolCall.optString("toolCallId"),
            toolName = toolName,
            detail = detail,
            affectedPaths = paths,
            commandPreview = commandPreview,
            risk = risk,
        )
    }

    private fun parseSessionUpdate(update: JSONObject?): OpenCodeAcpEvent {
        if (update == null) return OpenCodeAcpEvent.Ignored
        return when (update.optString("sessionUpdate")) {
            "agent_message_chunk" -> {
                val text = contentText(update.optJSONObject("content"))
                if (text.isEmpty()) OpenCodeAcpEvent.Ignored
                else OpenCodeAcpEvent.AgentChunk(text, thought = false)
            }
            "agent_thought_chunk" -> {
                val text = contentText(update.optJSONObject("content"))
                if (text.isEmpty()) OpenCodeAcpEvent.Ignored
                else OpenCodeAcpEvent.AgentChunk(text, thought = true)
            }
            "tool_call" -> toolUpdate(
                update,
                status = update.optString("status").ifBlank { "pending" },
            )
            "tool_call_update" -> toolUpdate(
                update,
                status = update.optString("status").ifBlank { "in_progress" },
            )
            else -> {
                val kind = update.optString("sessionUpdate")
                // Safety net: if the client renamed a tool-call kind but still identifies a tool
                // call with a toolCallId, treat it as a tool update so approvals are never missed.
                if (update.optString("toolCallId").isNotEmpty()) {
                    toolUpdate(update, status = update.optString("status").ifBlank { "in_progress" })
                } else {
                    OpenCodeAcpEvent.UnknownUpdate(kind, update)
                }
            }
        }
    }

    private fun toolUpdate(update: JSONObject, status: String): OpenCodeAcpEvent {
        val toolCallId = update.optString("toolCallId")
        if (toolCallId.isEmpty()) return OpenCodeAcpEvent.Ignored
        val title = update.optString("title").ifBlank { update.optString("kind").ifBlank { "tool" } }
        val kind = update.optString("kind").ifBlank { "other" }
        val locations = update.optJSONArray("locations")
        val detail = buildString {
            update.optJSONArray("content")?.let { content ->
                for (i in 0 until content.length()) {
                    val entry = content.optJSONObject(i) ?: continue
                    entry.optString("text").takeIf { it.isNotBlank() }?.let { append(it.trim()) }
                }
            }
            if (isEmpty() && locations != null) {
                for (i in 0 until locations.length()) {
                    locations.optJSONObject(i)?.optString("path")?.let { append(it) }
                }
            }
        }.trim().take(400)
        return OpenCodeAcpEvent.ToolUpdate(
            toolCallId = toolCallId,
            title = title,
            kind = kind,
            status = status,
            detail = detail,
        )
    }

    private fun contentText(content: JSONObject?): String = when (content?.optString("type")) {
        "text" -> content.optString("text")
        else -> ""
    }

    private fun collectPaths(toolCall: JSONObject): List<String> {
        val rawInput = toolCall.optJSONObject("rawInput") ?: return emptyList()
        val out = LinkedHashSet<String>()
        rawInput.optJSONObject("path")?.let { out += it.optString("filePath", it.optString("path")) }
        rawInput.optString("path").takeIf { it.isNotBlank() }?.let { out += it }
        rawInput.optString("filePath").takeIf { it.isNotBlank() }?.let { out += it }
        rawInput.optJSONArray("paths")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out.filter { it.isNotBlank() }.take(20)
    }

    private fun classifyRisk(toolName: String, detail: String): RiskLevel {
        val haystack = "$toolName $detail".lowercase()
        return when {
            HIGH_RISK_TOKENS.any { haystack.contains(it) } -> RiskLevel.HIGH
            // Editing or executing anything is worth a glance; pure reads do not need a card.
            EDIT_OR_EXEC_TOKENS.any { haystack.contains(it) } -> RiskLevel.REVIEW
            else -> RiskLevel.SAFE
        }
    }
}
