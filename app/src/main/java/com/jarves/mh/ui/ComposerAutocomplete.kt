package com.jarves.mh.ui

/** What kind of autocomplete popup (if any) the composer caret currently opens. */
enum class ComposerTriggerKind {
    SLASH,
    MENTION,
}

/**
 * An active autocomplete invocation anchored at [replaceStart]..[caret].
 *
 * [query] is the fragment after the trigger character, used to filter candidates. The popup
 * replaces exactly this range when the user commits a choice.
 */
data class ComposerTrigger(
    val kind: ComposerTriggerKind,
    val query: String,
    val replaceStart: Int,
    val caret: Int,
) {
    val range: IntRange get() = replaceStart until caret
}

/**
 * Pure caret-anchored trigger detection for the chat composer's `/` and `@` shortcuts.
 *
 * Kept free of Compose so it is exhaustively unit-testable, matching how the other agent
 * protocol parsers are structured. A trigger is only active when the caret sits inside a
 * whitespace-delimited token that *starts* with the trigger character, which is what stops
 * `user@example.com` or `3/4` from popping the wrong menu.
 */
object ComposerAutocomplete {

    /**
     * Longest run of characters we scan back from the caret looking for a trigger start.
     * Bounded so a pasted wall of text can never drive an unbounded backwards scan.
     */
    private const val MAX_SCAN = 256

    /**
     * Characters that can appear *inside* an autocomplete token body. Whitespace ends a token;
     * so does any character that legitimately shows up in a path or command (`/` for paths, `:`
     * for `file:line`, `.` and `-` for filenames). The `@` trigger character is deliberately not
     * a body char, which is what stops `user@example.com` opening the mention menu.
     */
    private fun isBodyChar(c: Char): Boolean = c.isLetterOrDigit() ||
        c == '_' || c == '-' || c == '.' || c == '/' || c == ':' || c == '\\' || c == '+' || c == '~'

    /**
     * A trigger may only begin the message or follow whitespace or opening punctuation, so
     * `and/or` and `emails@x` never masquerade as a command or a mention.
     */
    private fun isBoundaryBefore(text: String, markerIndex: Int): Boolean {
        if (markerIndex <= 0) return true
        val before = text[markerIndex - 1]
        return before.isWhitespace() || before == '(' || before == '[' || before == '{' || before == ','
    }

    /**
     * Detects an active trigger ending at [caret], or null when none is open.
     *
     * [query] is the text already typed after the trigger, e.g. for `"fix @src/Mai"` with the
     * caret at the end, this returns a MENTION trigger with query `"src/Mai"`.
     */
    fun detect(text: String, caret: Int): ComposerTrigger? {
        if (caret < 0 || caret > text.length) return null

        // Scan back from the caret over the token body. The scan stops on whitespace, on `@`, or at
        // the bounded floor.
        var i = caret
        val floor = maxOf(0, caret - MAX_SCAN)
        while (i > floor && isBodyChar(text[i - 1])) i--

        // `/` is a body char, so a leading slash gets consumed by that scan (that is what keeps
        // `@src/main.kt` intact). A boundary-legal `/` sitting exactly at the scan stop is the
        // slash-command marker; otherwise the char just before the body is the `@` marker.
        val markerIndex = when {
            i < text.length && text[i] == '/' && isBoundaryBefore(text, i) -> i
            i > 0 && text[i - 1] == '@' && isBoundaryBefore(text, i - 1) -> i - 1
            else -> return null
        }

        val marker = text[markerIndex]
        val query = text.substring(markerIndex + 1, caret)
        return ComposerTrigger(
            kind = if (marker == '/') ComposerTriggerKind.SLASH else ComposerTriggerKind.MENTION,
            query = query,
            replaceStart = markerIndex,
            caret = caret,
        )
    }

    /**
     * Replaces the trigger range in [text] with [insertion], leaving the caret after it.
     *
     * Committing a mention usually wants to keep typing a space, which is why callers pass
     * `"@src/Main.kt "` rather than adding it here — the choice belongs to the caller.
     */
    fun commit(text: String, trigger: ComposerTrigger, insertion: String): TextFieldValueLite {
        val before = text.substring(0, trigger.replaceStart)
        val after = text.substring(trigger.caret)
        val updated = before + insertion + after
        val caret = before.length + insertion.length
        return TextFieldValueLite(text = updated, caret = caret)
    }
}

/**
 * Minimal caret-carrying value so [ComposerAutocomplete] stays Compose-free while still being
 * able to express "the text after an edit". Callers convert to Compose's `TextFieldValue`.
 */
data class TextFieldValueLite(val text: String, val caret: Int)
