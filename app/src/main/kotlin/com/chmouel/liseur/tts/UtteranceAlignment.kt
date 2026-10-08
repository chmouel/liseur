package com.chmouel.liseur.tts

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.tokenizer.TextTokenizer
import org.readium.r2.shared.util.tokenizer.Tokenizer

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
 *
 * A sentence that opens its element has nothing before it at all, so
 * [matches] compares what follows instead: [onward], the selection and the
 * text after it, must agree with the sentence and the rest of its element,
 * past the sentence when the element goes on.
 */
class SelectionTarget(
    private val href: Url,
    private val prefix: String,
    private val before: String,
    private val onward: String = "",
) {

    @OptIn(ExperimentalReadiumApi::class)
    fun matches(location: TtsNavigator.Location): Boolean =
        location.href == href && matches(location.utterance, location.textBefore, following(location))

    @OptIn(ExperimentalReadiumApi::class)
    fun matchesClosely(location: TtsNavigator.Location): Boolean =
        location.href == href &&
            matches(location.utterance, location.textBefore, following(location), closely = true)

    fun matches(utterance: String, textBefore: String?, textAfter: String? = null, closely: Boolean = false): Boolean {
        val spoken = squash(utterance)
        val leading = squash(textBefore.orEmpty())
        return starts(spoken).any { at ->
            val context = leading + spoken.substring(0, at)
            if (context.isEmpty() && before.isNotEmpty()) {
                !closely && continues(spoken.substring(at), squash(textAfter.orEmpty()))
            } else {
                agrees(context, closely)
            }
        }
    }

    /** Whether [onward] reads as [sentence] then [after], past the sentence unless nothing follows it. */
    private fun continues(sentence: String, after: String): Boolean {
        val candidate = sentence + after
        val compared = minOf(onward.length, candidate.length)
        if (compared == 0 || onward.take(compared) != candidate.take(compared)) return false
        return compared > sentence.length || (after.isEmpty() && compared == sentence.length)
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

        /** [anchor]'s sentence, found by its start and the text before it, however the sentences are now cut. */
        fun of(anchor: UtteranceAnchor): SelectionTarget? = of(
            anchor.locator.copy(text = anchor.locator.text.copy(highlight = anchor.text)),
            object : Tokenizer<String, IntRange> {
                override fun tokenize(data: String) = listOf(data.indices)
            },
        )

        /** Null when the selection has no text to look for. */
        fun of(locator: Locator, sentences: TextTokenizer): SelectionTarget? {
            val selected = locator.text.highlight?.takeIf { it.isNotBlank() } ?: return null
            val first = sentences.tokenize(selected).firstOrNull()?.let(selected::substring) ?: selected
            val prefix = squash(first).take(PREFIX).takeIf { it.isNotEmpty() } ?: return null
            return SelectionTarget(
                locator.href,
                prefix,
                squash(locator.text.before.orEmpty()),
                onward = squash(selected + locator.text.after.orEmpty()),
            )
        }

        // Whitespace differs between the page's selection and the
        // tokenized content (line breaks, collapsed runs), so it is ignored.
        private fun squash(text: String) = text.filterNot(Char::isWhitespace)

        /**
         * The text after [utterance] in its element. Readium starts it at
         * the utterance's last character, so that character comes first
         * again and a sentence ending its element is followed by its own
         * full stop.
         */
        fun following(utterance: String, textAfter: String?): String? {
            val last = utterance.lastOrNull() ?: return textAfter
            return if (textAfter?.firstOrNull() == last) textAfter.drop(1) else textAfter
        }

        @OptIn(ExperimentalReadiumApi::class)
        private fun following(location: TtsNavigator.Location) = following(location.utterance, location.textAfter)
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
