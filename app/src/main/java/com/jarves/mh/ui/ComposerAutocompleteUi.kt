package com.jarves.mh.ui

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.runtime.AgentCapability
import com.jarves.mh.runtime.AgentRegistry
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which popup a candidate belongs to, so one list can serve both `/` and `@`. */
enum class ComposerSuggestionKind {
    SLASH,
    MENTION,
}

/** One selectable row in the `/` or `@` autocomplete popup. */
data class ComposerSuggestion(
    val label: String,
    val detail: String? = null,
    /** What gets written into the composer when the row is committed. */
    val insertion: String,
    val kind: ComposerSuggestionKind,
)

/**
 * Slash commands offered for agents that declare [AgentCapability.SLASH_COMMANDS].
 *
 * These are the commands OpenCode itself documents, minus `/undo` and `/redo` which its ACP
 * surface does not support. An agent with no declared slash commands gets an empty list, which is
 * what keeps the popup hidden rather than showing commands it would not interpret.
 */
private val DEFAULT_SLASH_COMMANDS = listOf(
    ComposerSuggestion("/help", "Show available commands", "/help ", ComposerSuggestionKind.SLASH),
    ComposerSuggestion("/init", "Create an AGENTS.md for this workspace", "/init ", ComposerSuggestionKind.SLASH),
    ComposerSuggestion("/compact", "Summarize the conversation to free context", "/compact ", ComposerSuggestionKind.SLASH),
    ComposerSuggestion("/clear", "Start a fresh session", "/clear ", ComposerSuggestionKind.SLASH),
    ComposerSuggestion("/model", "Switch the active model", "/model ", ComposerSuggestionKind.SLASH),
    ComposerSuggestion("/share", "Share this session", "/share ", ComposerSuggestionKind.SLASH),
)

/**
 * Builds the candidate list for [agentKind].
 *
 * Mentions are driven entirely by the workspace listing, so an agent without
 * [AgentCapability.FILE_MENTIONS] gets none. Workspace entries are capped because a large project
 * would otherwise push tens of thousands of paths through the composer on every keystroke.
 */
fun buildComposerSuggestions(
    agentKind: AgentKind,
    workspaceFiles: List<WorkspaceEntry>,
    mentionLimit: Int = 400,
): List<ComposerSuggestion> {
    val capabilities = AgentRegistry.capabilitiesOf(agentKind)
    val result = mutableListOf<ComposerSuggestion>()
    if (AgentCapability.SLASH_COMMANDS in capabilities) {
        result += DEFAULT_SLASH_COMMANDS
    }
    if (AgentCapability.FILE_MENTIONS in capabilities) {
        result += workspaceFiles.take(mentionLimit).map { entry ->
            ComposerSuggestion(
                label = "@${entry.path}",
                detail = if (entry.isDirectory) "dir" else null,
                insertion = "@${entry.path} ",
                kind = ComposerSuggestionKind.MENTION,
            )
        }
    }
    return result
}

/**
 * The `/` and `@` autocomplete popup, rendered directly above the composer surface.
 *
 * Intentionally agent-agnostic: the caller supplies the candidate list, so an agent that
 * advertises no slash commands simply passes an empty list and the popup never appears. That is
 * what lets this be shared by every harness instead of hard-coding OpenCode's commands.
 */
@Composable
fun ComposerAutocompletePopup(
    suggestions: List<ComposerSuggestion>,
    onPick: (ComposerSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(vertical = 4.dp),
    ) {
        suggestions.forEach { suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(suggestion) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = suggestion.label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (suggestion.detail != null) {
                    Text(
                        text = suggestion.detail,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * Filters [candidates] down to those matching [query], preferring prefix matches and falling back
 * to substring matches so a mistyped fragment still finds the command.
 *
 * Capped to a short list: the popup is a keyboard-driven affordance on a phone, and rendering
 * hundreds of rows for a broad prefix would cost more than it helps.
 */
fun filterSuggestions(
    candidates: List<ComposerSuggestion>,
    query: String,
    limit: Int = 8,
): List<ComposerSuggestion> {
    if (query.isBlank()) return candidates.take(limit)
    val needle = query.lowercase()
    val prefix = candidates.filter { it.label.lowercase().startsWith(needle) }
    if (prefix.size >= limit) return prefix.take(limit)
    val contains = candidates.filter {
        !it.label.lowercase().startsWith(needle) && it.label.lowercase().contains(needle)
    }
    return (prefix + contains).take(limit)
}
