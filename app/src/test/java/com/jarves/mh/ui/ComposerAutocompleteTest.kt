package com.jarves.mh.ui

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Caret-anchored trigger detection is the kind of code that is cheap to get subtly wrong, so the
 * interesting cases (mid-token carets, false-positive `@` in emails, path characters, bounded
 * scanning) are pinned here rather than left to manual QA.
 */
class ComposerAutocompleteTest {

    private fun caretEnd(s: String) = s.length

    @Test
    fun `slash command at start of message is detected`() {
        val trigger = ComposerAutocomplete.detect("/he", 3)
        assertEquals(ComposerTriggerKind.SLASH, trigger?.kind)
        assertEquals("he", trigger?.query)
        assertEquals(0, trigger?.replaceStart)
    }

    @Test
    fun `bare trigger with empty query is detected`() {
        val trigger = ComposerAutocomplete.detect("/", 1)
        assertEquals(ComposerTriggerKind.SLASH, trigger?.kind)
        assertEquals("", trigger?.query)
    }

    @Test
    fun `slash command after whitespace is detected`() {
        val text = "fix the bug /cle"
        val trigger = ComposerAutocomplete.detect(text, caretEnd(text))
        assertEquals(ComposerTriggerKind.SLASH, trigger?.kind)
        assertEquals("cle", trigger?.query)
        assertEquals(12, trigger?.replaceStart)
    }

    @Test
    fun `mention with path characters keeps the whole path as query`() {
        val text = "look at @src/main/kotlin"
        val trigger = ComposerAutocomplete.detect(text, caretEnd(text))
        assertEquals(ComposerTriggerKind.MENTION, trigger?.kind)
        assertEquals("src/main/kotlin", trigger?.query)
        assertEquals(8, trigger?.replaceStart)
    }

    @Test
    fun `plain text with no trigger returns null`() {
        assertNull(ComposerAutocomplete.detect("hello world", 11))
    }

    @Test
    fun `email address is not treated as a mention`() {
        // `@` is mid-token here, so it must not open the mention popup.
        assertNull(ComposerAutocomplete.detect("mail me@example.com", 19))
    }

    @Test
    fun `slash mid-token like a path is not a command`() {
        assertNull(ComposerAutocomplete.detect("and/or", 6))
    }

    @Test
    fun `caret in the middle of a token uses text up to the caret`() {
        // Caret at 6 sits right after "/clear" in "/clear now" -> query is what precedes the caret.
        val trigger = ComposerAutocomplete.detect("/clear now", 6)
        assertEquals(ComposerTriggerKind.SLASH, trigger?.kind)
        assertEquals("clear", trigger?.query)
        assertEquals(0, trigger?.replaceStart)
        assertEquals(6, trigger?.caret)
    }

    @Test
    fun `caret after whitespace following a token does not keep the trigger open`() {
        // "/clear " with caret at the end (after the space) should be closed.
        assertNull(ComposerAutocomplete.detect("/clear ", 7))
    }

    @Test
    fun `out of range caret returns null instead of throwing`() {
        assertNull(ComposerAutocomplete.detect("abc", 99))
        assertNull(ComposerAutocomplete.detect("abc", -1))
    }

    @Test
    fun `commit replaces the trigger range and positions caret after insertion`() {
        val text = "look at @src/ now"
        val trigger = ComposerAutocomplete.detect(text, 13)!! // @src/
        val result = ComposerAutocomplete.commit(text, trigger, "@src/main/kotlin ")
        assertEquals("look at @src/main/kotlin  now", result.text)
        assertEquals("look at @src/main/kotlin ".length, result.caret)
    }

    @Test
    fun `commit preserves trailing text after the caret`() {
        val text = "@sr and then rest"
        val trigger = ComposerAutocomplete.detect(text, 3)!!
        val result = ComposerAutocomplete.commit(text, trigger, "@src/")
        assertEquals("@src/ and then rest", result.text)
        assertEquals(5, result.caret)
    }

    @Test
    fun `scan is bounded for very long tokens`() {
        // A huge token should not be scanned forever; the trigger start is clamped by MAX_SCAN.
        val huge = "/" + "a".repeat(5000) + "x"
        val trigger = ComposerAutocomplete.detect(huge, caretEnd(huge))
        // Either it finds a bounded start or returns null, but it must not throw and must not
        // report a start inside the clamped window that precedes the visible token.
        if (trigger != null) {
            assertEquals(ComposerTriggerKind.SLASH, trigger.kind)
            assert(trigger.replaceStart >= caretEnd(huge) - 256)
        }
    }

    private fun entry(path: String, dir: Boolean = false) = WorkspaceEntry(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = dir,
        depth = path.count { it == '/' },
    )

    @Test
    fun `opencode gets both slash commands and file mentions`() {
        val files = listOf(entry("src/Main.kt"), entry("app", dir = true))
        val suggestions = buildComposerSuggestions(AgentKind.OPENCODE, files)

        assert(suggestions.any { it.kind == ComposerSuggestionKind.SLASH })
        assert(suggestions.any { it.kind == ComposerSuggestionKind.MENTION })
        // A mention inserts the full path plus a trailing space so typing can continue.
        val mention = suggestions.first { it.label == "@src/Main.kt" }
        assertEquals("@src/Main.kt ", mention.insertion)
        assertEquals("dir", suggestions.first { it.label == "@app" }.detail)
    }

    @Test
    fun `agents without slash or mention capabilities get an empty candidate list`() {
        val files = listOf(entry("src/Main.kt"))
        // Claude/DSH/Antigravity do not parse `/` or `@`; offering either would insert text the
        // agent would forward to the model verbatim.
        listOf(
            AgentKind.CLAUDE_CODE,
            AgentKind.DEEPSEEK_HARNESS,
            AgentKind.ANTIGRAVITY,
        ).forEach { kind ->
            assertEquals(kind.stableId, emptyList<ComposerSuggestion>(), buildComposerSuggestions(kind, files))
        }
    }

    @Test
    fun `mention candidates are capped so a huge workspace cannot flood the composer`() {
        val many = (0 until 900).map { entry("src/File$it.kt") }
        val mentions = buildComposerSuggestions(AgentKind.OPENCODE, many, mentionLimit = 50)
            .filter { it.kind == ComposerSuggestionKind.MENTION }
        assertEquals(50, mentions.size)
    }

    @Test
    fun `filter prefers prefix matches and falls back to substring matches`() {
        val candidates = listOf(
            ComposerSuggestion("/compact", null, "/compact ", ComposerSuggestionKind.SLASH),
            ComposerSuggestion("/clear", null, "/clear ", ComposerSuggestionKind.SLASH),
            ComposerSuggestion("/clear-cache", null, "/clear-cache ", ComposerSuggestionKind.SLASH),
            ComposerSuggestion("/help", null, "/help ", ComposerSuggestionKind.SLASH),
        )
        // Prefix matches come first, so "clear" lists the two /clear* commands before /help.
        assertEquals(
            listOf("/clear", "/clear-cache"),
            filterSuggestions(candidates, "clear").map { it.label },
        )
        // A substring-only match still resolves, but after the prefix hits.
        assertEquals("/help", filterSuggestions(candidates, "hel").single().label)
        // A blank query shows the head of the list, capped by the limit.
        assertEquals(4, filterSuggestions(candidates, "", limit = 8).size)
        assertEquals(2, filterSuggestions(candidates, "", limit = 2).size)
    }
}
