package com.chmouel.liseur.reader.annotations

/**
 * Which colours a passage may be marked in, and which one a plain highlight
 * gets when no colour was picked.
 *
 * [HighlightTint] has six entries because that is what liseur-sync's
 * palette is, and a colour arriving from another device must have a
 * name here or it would be rewritten on the way in. That is a reason
 * about *storage*. It says nothing about how many chips belong in a bar
 * drawn over the passage the reader is trying to look at, and six of
 * them, plus Note, Look up, Search, Share and sometimes Delete, is more
 * bar than passage on a phone.
 *
 * So the bar is the reader's to set, and this is the whole of the rule
 * it follows. Everything else stays where it was: a mark whose colour
 * this palette does not offer is still drawn over the page, still
 * listed with its own dot, and still sent and received under its own
 * name. Hiding a chip hides a *choice*, never a mark.
 */
data class HighlightPalette(
    val offered: Set<HighlightTint> = DEFAULT_OFFERED,
    val default: HighlightTint = HighlightTint.DEFAULT,
) {

    /**
     * The colours the bar offers, in the order [HighlightTint] declares
     * them.
     *
     * Declaration order rather than the order they were ticked, or the
     * default first: the reader picks a set, and a set that rearranges
     * itself costs the muscle memory of anyone who marks by position.
     * Ticking a colour slots it in beside its neighbours and leaves the
     * others where they were.
     */
    val shown: List<HighlightTint> = HighlightTint.entries.filter { it in offered }

    /** Whether the bar has no colours to offer and needs a plain action instead. */
    val isEmpty: Boolean = shown.isEmpty()

    /**
     * The tint for a new note attached to a passage.
     *
     * Notes follow the first colour the reader currently offers. An empty
     * palette has no first colour, so it falls back to the separately chosen
     * default to keep note creation possible.
     */
    val passageNoteTint: HighlightTint
        get() = shown.firstOrNull() ?: default

    /**
     * The chips to draw for the mark in hand.
     *
     * A mark can already be in a colour the palette no longer offers —
     * made here before a colour was unticked, or made on a device
     * showing all six. Leaving it out would show a highlight whose own
     * colour is nowhere in the bar that recolours it: the reader could
     * not see which one it was, and one tap anywhere would lose it with
     * no way back. So it joins the end, for as long as that mark is
     * selected.
     */
    fun chipsFor(active: HighlightTint?): List<HighlightTint> =
        if (active == null || active in shown) shown else shown + active

    /** The palette with [tint] offered, or no longer offered. */
    fun toggled(tint: HighlightTint): HighlightPalette =
        copy(offered = if (tint in offered) offered - tint else offered + tint)

    companion object {
        val MAX_COUNT = HighlightTint.entries.size

        /**
         * Three, which is what the bar offers until it is asked for
         * more.
         *
         * Enough to separate a quotation from a doubt from a word to
         * look up later, and few enough that the bar still fits beside
         * the actions next to it.
         */
        val DEFAULT_OFFERED: Set<HighlightTint> =
            setOf(HighlightTint.YELLOW, HighlightTint.GREEN, HighlightTint.BLUE)

        /**
         * A palette read back from storage, made safe to use.
         *
         * The store is a file on a device and this is the only door its
         * contents come through, as `AutoScrollPreference.sanitize` is
         * for the scroll pace. A name this build does not know is
         * dropped rather than refused, since the set is what the reader
         * ticks and a name with no swatch has nothing to be. An absent
         * set means the reader has never chosen, which is not the same
         * as choosing none: an empty set that was *stored* is honoured.
         */
        fun of(offeredNames: Set<String>?, defaultName: String?): HighlightPalette =
            HighlightPalette(
                offered = offeredNames
                    ?.mapNotNull { name -> HighlightTint.entries.firstOrNull { it.name == name } }
                    ?.toSet()
                    ?: DEFAULT_OFFERED,
                default = HighlightTint.fromName(defaultName),
            )
    }
}
