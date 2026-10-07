package com.chmouel.liseur.tts

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.tokenizer.TextTokenizer

/**
 * One sentence the voice spoke, precise enough to find it again: the
 * element it sits in, its text, and the text before it in that element,
 * which tells a repeated sentence apart.
 */
data class UtteranceAnchor(val locator: Locator, val text: String) {
    /** Where a fresh navigator starts to look for it: its element. */
    val elementLocator: Locator get() = locator.copy(text = Locator.Text())

    @OptIn(ExperimentalReadiumApi::class)
    fun isAt(location: TtsNavigator.Location): Boolean =
        location.href == locator.href &&
            location.utterance == text &&
            location.utteranceLocator.text.before == locator.text.before

    companion object {
        @OptIn(ExperimentalReadiumApi::class)
        fun of(location: TtsNavigator.Location) = UtteranceAnchor(location.utteranceLocator, location.utterance)
    }
}

/**
 * The sentence a selection starts in.
 *
 * Utterances are whole sentences while a selection can start anywhere, so
 * the target is the start of the selection's first sentence (at most
 * [PREFIX] characters, never past that sentence), and an utterance matches
 * when it contains it and the text before agrees with the selection's,
 * so a phrase that repeats is found where it was selected. A long sentence
 * is spoken in pieces, so the target may also begin at the end of one
 * utterance and run on into the next.
 *
 * Readium gives an utterance only the text before it in its own element,
 * so near an element start there is little to compare. [matchesClosely]
 * requires all of the selection's context and is tried first; [matches]
 * settles for what there is, so a repeated sentence earlier in the same
 * element is not taken for the one selected.
 */
class SelectionTarget(private val href: Url, private val prefix: String, private val before: String) {

    @OptIn(ExperimentalReadiumApi::class)
    fun matches(location: TtsNavigator.Location): Boolean =
        location.href == href && matches(location.utterance, location.textBefore)

    @OptIn(ExperimentalReadiumApi::class)
    fun matchesClosely(location: TtsNavigator.Location): Boolean =
        location.href == href && matches(location.utterance, location.textBefore, closely = true)

    fun matches(utterance: String, textBefore: String?, closely: Boolean = false): Boolean {
        val spoken = squash(utterance)
        val leading = squash(textBefore.orEmpty())
        return starts(spoken).any { at -> agrees(leading + spoken.substring(0, at), closely) }
    }

    /** Where in [spoken] the target can begin: where it is, or where a tail of [spoken] begins it. */
    private fun starts(spoken: String): Sequence<Int> = sequence {
        var from = 0
        while (true) {
            val at = spoken.indexOf(prefix, from)
            if (at < 0) break
            yield(at)
            from = at + 1
        }
        // Only told apart by the text before it: a selection with none
        // starts its resource, so it cannot begin inside a sentence.
        if (before.isEmpty()) return@sequence
        for (at in maxOf(spoken.length - prefix.length + 1, 0) until spoken.length) {
            if (prefix.startsWith(spoken.substring(at))) yield(at)
        }
    }

    /**
     * Whether [context], the text before a candidate, agrees with the
     * selection's. No context on either side agrees with nothing on the
     * other, except a selection that has none itself. [closely] requires
     * the candidate to have as much context as the selection compares.
     */
    private fun agrees(context: String, closely: Boolean): Boolean {
        val wanted = minOf(CONTEXT, before.length)
        if (closely && context.length < wanted) return false
        val compared = minOf(wanted, context.length)
        if (compared == 0) return before.isEmpty()
        return context.takeLast(compared) == before.takeLast(compared)
    }

    companion object {
        const val PREFIX = 60
        private const val CONTEXT = 40

        /** Null when the selection has no text to look for. */
        fun of(locator: Locator, sentences: TextTokenizer): SelectionTarget? {
            val selected = locator.text.highlight?.takeIf { it.isNotBlank() } ?: return null
            val first = sentences.tokenize(selected).firstOrNull()?.let(selected::substring) ?: selected
            val prefix = squash(first).take(PREFIX).takeIf { it.isNotEmpty() } ?: return null
            return SelectionTarget(locator.href, prefix, squash(locator.text.before.orEmpty()))
        }

        // Whitespace differs between the page's selection and the
        // tokenized content (line breaks, collapsed runs), so it is ignored.
        private fun squash(text: String) = text.filterNot(Char::isWhitespace)
    }
}

/**
 * Steps a paused navigator forward one utterance at a time until [matches]
 * holds, waiting for each step to show up in [location]. Readium seeks only
 * to an element and moves asynchronously, so this is how the voice lands on
 * a sentence inside one.
 *
 * Returns false, leaving the navigator wherever it got to, when a step does
 * not arrive within [stepTimeoutMillis], the content runs out, [inScope]
 * stops holding or [maxSteps] pass.
 */
suspend fun <L> alignUtterance(
    location: StateFlow<L>,
    hasNext: () -> Boolean,
    skipToNext: () -> Unit,
    matches: (L) -> Boolean,
    inScope: (L) -> Boolean = { true },
    stepTimeoutMillis: Long = 2_000,
    maxSteps: Int = 20,
): Boolean {
    var current = location.value
    var steps = 0
    while (true) {
        if (!inScope(current)) return false
        if (matches(current)) return true
        if (steps++ >= maxSteps || !hasNext()) return false
        val previous = current
        skipToNext()
        current = withTimeoutOrNull(stepTimeoutMillis) { location.first { it != previous } } ?: return false
    }
}
