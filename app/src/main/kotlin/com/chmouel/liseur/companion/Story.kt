package com.chmouel.liseur.companion

import org.readium.r2.shared.publication.Locator

/** What a chapter's stored recap gives the story so far. */
sealed interface StoryRecap {
    data class Summary(val sentences: List<String>) : StoryRecap

    /** A recap holding characters only, or written for a chapter without text. */
    data object NoSummary : StoryRecap

    data object Unreadable : StoryRecap

    data object Missing : StoryRecap
}

/** One earlier chapter in the story so far; [start] is null when it cannot be opened reliably. */
data class StoryChapter<P>(val unit: Int, val title: String?, val recap: StoryRecap, val start: P?)

/** The story so far over the book: earlier chapters and their stored summaries. */
data class StoryPanel(
    val chapters: List<StoryChapter<Locator>> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val visible: Boolean = true,
)

object Story {
    /**
     * The rows for [earlier], the chapters known to lie before the place
     * asked from, with what [saved] holds for each. A recap saved when the
     * chapters were cut differently counts as none.
     */
    fun <P> chapters(map: ChapterMap, earlier: List<EarlierChapter<P>>, saved: List<SavedRecap>): List<StoryChapter<P>> {
        val byUnit = saved
            .filter { it.unit.index < map.chapterCount && map.unitKey(it.unit.index) == it.unit.key }
            .associateBy { it.unit.index }
        return earlier.sortedBy { it.unit }.map { chapter ->
            val recap = when (val row = byUnit[chapter.unit]) {
                null -> StoryRecap.Missing
                else -> ChapterRecap.decode(row.json)?.let { decoded ->
                    decoded.summary.map(String::trim).filter(String::isNotEmpty)
                        .takeIf { it.isNotEmpty() }?.let(StoryRecap::Summary) ?: StoryRecap.NoSummary
                } ?: StoryRecap.Unreadable
            }
            StoryChapter(chapter.unit, map.titleOf(chapter.unit), recap, chapter.start)
        }
    }

    /** The unit keys of the recaps [chapters] show, to be touched as used. */
    fun shown(map: ChapterMap, chapters: List<StoryChapter<*>>): List<String> =
        chapters.filter { it.recap is StoryRecap.Summary || it.recap is StoryRecap.NoSummary }.map { map.unitKey(it.unit) }
}
