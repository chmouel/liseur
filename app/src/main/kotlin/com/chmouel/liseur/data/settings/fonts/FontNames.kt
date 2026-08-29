package com.chmouel.liseur.data.settings.fonts

/**
 * Cleans up a name that came from outside the app.
 *
 * Both names Liseur can end up showing for an imported font are supplied by
 * something it does not control: the `name` table of a file the reader
 * picked, and the display name a DocumentsProvider reports for it. Neither
 * is trustworthy, and a font called
 * `"Literata\u202E gnitteS"` would render in a settings list as a line that
 * reads backwards. One sanitiser, applied to both, so there is no second
 * path where somebody forgot.
 */
object FontNames {

    private const val MAX_LENGTH = 64

    /**
     * Bidirectional overrides and isolates. These have legitimate uses in
     * running text and none at all in a font's name, where their only
     * effect is to reorder the row they are drawn in — and, worse, the rows
     * around it, since an unterminated override runs on.
     */
    private val BIDI = setOf(
        '\u200E', '\u200F',                               // LRM, RLM
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E', // embeddings, override
        '\u2066', '\u2067', '\u2068', '\u2069',           // isolates
    )

    /**
     * Returns a name safe to show, or null when nothing usable is left.
     *
     * Null rather than a placeholder: only the caller knows what to fall
     * back to, and a font with an unreadable `name` table should get the
     * name of the file it arrived in before it gets a digest.
     */
    fun sanitize(raw: String): String? {
        val cleaned = buildString(raw.length) {
            for (c in raw) {
                when {
                    c in BIDI -> Unit
                    // Includes NUL, which a fixed-length name record is
                    // padded with, and the line separators that would let
                    // one name occupy two rows.
                    c.isISOControl() -> append(' ')
                    c.isWhitespace() -> append(' ')
                    else -> append(c)
                }
            }
        }
        val collapsed = cleaned.trim().replace(WHITESPACE, " ")
        if (collapsed.isEmpty()) return null
        return if (collapsed.length <= MAX_LENGTH) {
            collapsed
        } else {
            // Trimmed again: the cut may have landed just after a space.
            collapsed.take(MAX_LENGTH).trimEnd()
        }
    }

    /**
     * The name a font gets when it has told us nothing and its file was
     * called something unusable either.
     */
    fun fallbackName(digest: String): String = "Imported font ${digest.take(8)}"

    private val WHITESPACE = Regex(" {2,}")
}
