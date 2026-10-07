package com.chmouel.liseur.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class UtteranceAlignmentTest {

    @Test
    fun stepsUntilTheTargetSentence() = runTest {
        val location = MutableStateFlow(0)
        val found = alignUtterance(
            location = location,
            hasNext = { true },
            skipToNext = { location.value++ },
            matches = { it == 3 },
        )
        assertTrue(found)
        assertEquals(3, location.value)
    }

    @Test
    fun aStepThatNeverArrivesGivesUp() = runTest {
        val location = MutableStateFlow(0)
        var skips = 0
        val found = alignUtterance(
            location = location,
            hasNext = { true },
            skipToNext = { skips++ },
            matches = { it == 3 },
            stepTimeoutMillis = 2_000,
        )
        assertFalse(found)
        assertEquals(1, skips)
        assertEquals(2_000, testScheduler.currentTime)
    }

    @Test
    fun givesUpAtTheEndOfTheScopeOrTheStepLimit() = runTest {
        val location = MutableStateFlow(0)
        val skip: () -> Unit = { location.value++ }
        assertFalse(alignUtterance(location, { true }, skip, matches = { it == 9 }, inScope = { it < 4 }))
        assertEquals(4, location.value)

        location.value = 0
        assertFalse(alignUtterance(location, { true }, skip, matches = { it == 99 }, maxSteps = 5))
        assertEquals(5, location.value)

        location.value = 0
        assertFalse(alignUtterance(location, { location.value < 2 }, skip, matches = { it == 9 }))
        assertEquals(2, location.value)
    }

    @Test
    fun aSelectionMatchesTheSentenceItStartsIn() {
        val target = SelectionTarget(HREF, prefix = "isthe", before = "Onetwo.Ittwothree.Thisone")
        assertTrue(target.matches("This one is the one.", textBefore = "One two. It two three. "))
        // The same words after a different lead-in are another occurrence.
        assertFalse(target.matches("This one is the one.", textBefore = "Something else entirely. "))
        // Without context on the page side there is nothing to disagree with.
        assertTrue(SelectionTarget(HREF, "isthe", before = "").matches("This one is the one.", "Whatever. "))
    }

    @Test
    fun aRepeatedPhraseInOneSentenceIsCheckedAtEachOccurrence() {
        // "aa" first appears after "x", then after "xaab".
        val target = SelectionTarget(HREF, prefix = "aa", before = "xaab")
        assertTrue(target.matches("x aa b aa", textBefore = null))
    }

    @Test
    fun aRepeatedSentenceAtTheStartIsNotTakenForTheOneSelectedLater() {
        val sentence = "The rain fell all night over the quiet harbour town."
        val target = SelectionTarget(HREF, prefix = squash(sentence), before = squash(sentence))
        // The first occurrence opens the chapter: nothing before it, while
        // the selection had the first occurrence before it.
        assertFalse(target.matches(sentence, textBefore = null))
        assertTrue(target.matches(sentence, textBefore = "$sentence "))
    }

    @Test
    fun aSelectionInsideARepeatedOpeningSentenceMatchesCloselyOnlyWhereItWasSelected() {
        val sentence = "The rain fell all night over the quiet harbour town."
        // Both in one paragraph; the selection starts at "rain" in the second.
        val target = SelectionTarget(
            HREF,
            prefix = squash("rain fell all night over the quiet harbour town."),
            before = squash("$sentence The"),
        )
        // Readium gives the first nothing before it: "The" is all there is to compare.
        assertTrue(target.matches(sentence, textBefore = null))
        assertFalse(target.matches(sentence, textBefore = null, closely = true))
        assertTrue(target.matches(sentence, textBefore = "$sentence ".takeLast(50), closely = true))
    }

    @Test
    fun aSelectionRunningOverASplitIsFoundInThePieceItStartsIn() {
        val opening = "Earlier text. "
        val first = "It was a long and winding sentence that went on and on, "
        val second = "until at last the reader chose to stop it somewhere around here."
        val selected = "on, until at last the reader chose to stop it somewhere around here."
        val target = SelectionTarget(
            HREF,
            prefix = squash(selected).take(SelectionTarget.PREFIX),
            before = squash(opening + first.removeSuffix("on, ")),
        )
        assertTrue(target.matches(first, textBefore = opening))
        assertFalse(target.matches(second, textBefore = opening + first))
    }

    private fun squash(text: String) = text.filterNot(Char::isWhitespace)

    private companion object {
        val HREF = Url("one.xhtml")!!
    }
}
