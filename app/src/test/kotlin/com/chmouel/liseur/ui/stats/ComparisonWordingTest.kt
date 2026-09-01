package com.chmouel.liseur.ui.stats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The comparison names the device it was counted on.
 *
 * The total above it is every device's reading and its caption says so.
 * The sentence beneath is not: both of its halves are this device's own
 * sittings, because a comparison only holds between two figures gathered
 * the same way and a server cannot be asked about a day that stops where
 * the clock has got to. See ADR 18.
 *
 * A reader who does all their reading on one machine loses nothing by
 * being told so. A reader with two would otherwise read a claim about
 * their month that the arithmetic never made — and the drift is silent,
 * which is why this is a test and not a comment.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ComparisonWordingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every comparison sentence says which device it counted`() {
        val sentences = listOf(
            context.getString(R.string.reading_stats_compare_more, 25, "last week"),
            context.getString(R.string.reading_stats_compare_less, 25, "last month"),
            context.getString(R.string.reading_stats_compare_more_than, "last year"),
            context.getString(R.string.reading_stats_compare_same, "last week"),
        )

        for (sentence in sentences) {
            assertTrue(sentence, sentence.contains("this device"))
        }
    }

    /**
     * And the total above it still says it counted all of them.
     *
     * The two lines sit in one card, so the contrast between them is the
     * whole of what tells the reader which figure is which.
     */
    @Test
    fun `the total above it still names every device`() {
        val captions = listOf(
            R.string.reading_stats_this_week,
            R.string.reading_stats_this_month,
            R.string.reading_stats_this_year,
        )

        for (caption in captions) {
            val text = context.getString(caption)
            assertTrue(text, text.contains("every device"))
        }
    }
}
