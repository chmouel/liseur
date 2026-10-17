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

/** Where a chapter the reader can ask for stands in the work under way. */
sealed interface StoryProgress {
    data object Idle : StoryProgress

    data object Queued : StoryProgress

    data class Working(val batch: Int, val batches: Int) : StoryProgress

    data class Failed(val failed: CompanionView.Failed) : StoryProgress
}

/** One earlier chapter in the story so far; [start] is null when it cannot be opened reliably. */
data class StoryChapter<P>(
    val unit: Int,
    val title: String?,
    val recap: StoryRecap,
    val start: P?,
    val progress: StoryProgress = StoryProgress.Idle,
    /** The characters the chapter's recap names, each once. */
    val characters: List<String> = emptyList(),
) {
    /** Whether the reader can ask for this chapter's recap now. */
    val askable: Boolean
        get() = (recap == StoryRecap.Missing || recap == StoryRecap.Unreadable) &&
            (progress == StoryProgress.Idle || progress is StoryProgress.Failed)
}

/** The running summary over the earlier chapters, as the Story tab shows it. */
data class StorySoFar(
    /** The best saved summary that may be shown here; null when none is. */
    val text: String? = null,
    /** The last chapter [text] reads, and the chapters it read without a summary. */
    val through: Int? = null,
    val unsummarized: List<Int> = emptyList(),
    /** Whether [text] reads every summary there is now. */
    val current: Boolean = false,
    /** Whether a summary can be written: some earlier chapter has one. */
    val writable: Boolean = false,
    val writing: Boolean = false,
    val failed: CompanionView.Failed? = null,
)

/** What Story is sending, for the strip with Stop: [waiting] counts chapters asked for besides the one [working]. */
data class StoryWork(val waiting: Int, val working: Int?, val batch: Int, val batches: Int, val summarizing: Boolean)

/** The Story tab for one frozen scope. */
data class StoryView(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val chapters: List<StoryChapter<Locator>> = emptyList(),
    /** Whether to ask once before sending anything on the reader's behalf, naming [service]. */
    val asking: Boolean = false,
    val service: String? = null,
    val soFar: StorySoFar = StorySoFar(),
    val work: StoryWork? = null,
) {
    val missing: Int get() = Story.missing(chapters).size
}

object Story {
    /**
     * The rows for [earlier], the chapters known to lie before the place
     * asked from, with what [saved] holds for each. A recap saved when the
     * chapters were cut differently counts as none.
     */
    fun <P> chapters(
        map: ChapterMap,
        earlier: List<EarlierChapter<P>>,
        saved: List<SavedRecap>,
        decode: (SavedRecap) -> StoryRecap = ::decode,
        names: (SavedRecap) -> List<String> = ::characters,
    ): List<StoryChapter<P>> {
        val byUnit = saved
            .filter { it.unit.index < map.chapterCount && map.unitKey(it.unit.index) == it.unit.key }
            .associateBy { it.unit.index }
        return earlier.sortedBy { it.unit }.map { chapter ->
            val row = byUnit[chapter.unit]
            val recap = row?.let(decode) ?: StoryRecap.Missing
            StoryChapter(chapter.unit, map.titleOf(chapter.unit), recap, chapter.start, characters = row?.let(names).orEmpty())
        }
    }

    fun decode(row: SavedRecap): StoryRecap = ChapterRecap.decode(row.json)?.let { decoded ->
        decoded.summary.map(String::trim).filter(String::isNotEmpty)
            .takeIf { it.isNotEmpty() }?.let(StoryRecap::Summary) ?: StoryRecap.NoSummary
    } ?: StoryRecap.Unreadable

    fun characters(row: SavedRecap): List<String> = ChapterRecap.decode(row.json)?.characters.orEmpty()
        .map { it.name.trim() }.filter { characterKey(it).isNotEmpty() }.distinctBy(::characterKey)

    /** [chapters] with where each stands in [activity]; [failed] shows a failure. */
    fun <P> progress(
        chapters: List<StoryChapter<P>>,
        activity: RecapActivity,
        failed: (RecapFailure) -> CompanionView.Failed,
    ): List<StoryChapter<P>> {
        val waiting = activity.waiting.toSet()
        return chapters.map { chapter ->
            val progress = when {
                activity.working == chapter.unit -> StoryProgress.Working(activity.batch, activity.batches)
                chapter.unit in waiting -> StoryProgress.Queued
                else -> activity.failed[chapter.unit]?.let { StoryProgress.Failed(failed(it)) } ?: StoryProgress.Idle
            }
            if (progress == chapter.progress) chapter else chapter.copy(progress = progress)
        }
    }

    /**
     * The chapter Story recaps on its own: the one right before the place,
     * when it has no recap and was not asked for. Others are the reader's to ask for.
     */
    fun previous(chapters: List<StoryChapter<*>>): List<Int> =
        listOfNotNull(chapters.maxByOrNull { it.unit }?.takeIf { it.recap == StoryRecap.Missing && it.progress == StoryProgress.Idle }?.unit)

    /** The missing chapters "Recap missing" asks for, nearest first: not already asked for, failed ones again. */
    fun missing(chapters: List<StoryChapter<*>>): List<Int> =
        chapters.filter { it.askable && it.recap == StoryRecap.Missing }.map { it.unit }.sortedDescending()

    /** The unit keys of the recaps [chapters] show, to be touched as used. */
    fun shown(map: ChapterMap, chapters: List<StoryChapter<*>>): List<String> =
        chapters.filter { it.recap is StoryRecap.Summary || it.recap is StoryRecap.NoSummary }.map { map.unitKey(it.unit) }
}

/** Decodes each stored recap once while its text stays the same. */
class StoryDecoder {
    private val memo = HashMap<String, Pair<String, StoryRecap>>()
    private val named = HashMap<String, Pair<String, List<String>>>()

    fun of(row: SavedRecap): StoryRecap {
        memo[row.unit.key]?.let { (json, recap) -> if (json == row.json) return recap }
        return Story.decode(row).also { memo[row.unit.key] = row.json to it }
    }

    fun names(row: SavedRecap): List<String> {
        named[row.unit.key]?.let { (json, names) -> if (json == row.json) return names }
        return Story.characters(row).also { named[row.unit.key] = row.json to it }
    }
}

/**
 * Where Story's own work stands for each frozen scope in this reading
 * session. Nothing is sent on the reader's behalf until they allow it, and
 * then the chapter before the place is picked once per scope: showing the tab
 * again, a new recap arriving, or a peek never picks again.
 */
class StoryEvents {
    enum class Stage { ASKING, SELECTED, DECLINED, STOPPED }

    private val stages = HashMap<AskingScope, Stage>()

    fun stage(scope: AskingScope): Stage? = stages[scope]

    /**
     * Story shows [scope] with [chapters]. With the reader's leave stored
     * it picks, once, the chapters to recap; otherwise it waits to ask.
     */
    fun shown(scope: AskingScope, allowed: Boolean, chapters: List<StoryChapter<*>>): List<Int> = when (stages[scope]) {
        null, Stage.ASKING -> if (allowed) select(scope, chapters) else emptyList<Int>().also { stages[scope] = Stage.ASKING }
        else -> emptyList()
    }

    /** The reader allowed it from [scope]'s card: picks its chapters, once. */
    fun allow(scope: AskingScope, chapters: List<StoryChapter<*>>): List<Int> =
        if (stages[scope] == null || stages[scope] == Stage.ASKING) select(scope, chapters) else emptyList()

    fun decline(scope: AskingScope) {
        if (stages[scope] == null || stages[scope] == Stage.ASKING) stages[scope] = Stage.DECLINED
    }

    fun stop(scope: AskingScope) {
        stages[scope] = Stage.STOPPED
    }

    /** Whether Story may write the running summary for [scope] on its own. */
    fun automatic(scope: AskingScope, allowed: Boolean): Boolean = allowed && stages[scope] == Stage.SELECTED

    private fun select(scope: AskingScope, chapters: List<StoryChapter<*>>): List<Int> {
        stages[scope] = Stage.SELECTED
        return Story.previous(chapters)
    }
}
