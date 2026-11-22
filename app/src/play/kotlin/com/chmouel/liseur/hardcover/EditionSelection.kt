package com.chmouel.liseur.hardcover

/**
 * The edition picked on the picker page, bound to the list on screen.
 *
 * A pick holds only while [resultsChanged] has not been called since, so a
 * tap made on one list can never confirm against another; and once
 * [submit] has answered, nothing more is accepted.
 */
data class EditionSelection(
    private val revision: Long = 0,
    private val pickedId: Long? = null,
    private val pickedRevision: Long = -1,
    val submitting: Boolean = false,
) {
    /** The picked edition id while it is still valid for [shown]. */
    fun selectedIn(shown: List<Long>): Long? =
        pickedId?.takeIf { !submitting && pickedRevision == revision && it in shown }

    fun select(id: Long, shown: List<Long>): EditionSelection =
        if (submitting || id !in shown) this else copy(pickedId = id, pickedRevision = revision)

    /** The shown list was replaced, started loading, failed or cleared. */
    fun resultsChanged(): EditionSelection =
        if (submitting) this else copy(revision = revision + 1, pickedId = null)

    fun canChoose(shown: List<Long>): Boolean = selectedIn(shown) != null

    /** The id to choose, once; a second call, or a stale pick, answers null. */
    fun submit(shown: List<Long>): Pair<EditionSelection, Long?> {
        val id = selectedIn(shown) ?: return this to null
        return copy(submitting = true) to id
    }
}
