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

    private companion object {
        val HREF = Url("one.xhtml")!!
    }
}
