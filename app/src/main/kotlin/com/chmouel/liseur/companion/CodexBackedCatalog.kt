package com.chmouel.liseur.companion

import kotlinx.coroutines.CancellationException

/**
 * [live] with its passages read from Codex's scan when the scan already
 * holds every chapter a scope can draw on, so a lookup or recap does not
 * walk the book first. The stored reads return null when they cannot
 * vouch for that (another book version, chapters not yet scanned), and
 * the book is walked as before. Either way the same [Eligibility] gate
 * picks what is returned, from the same passages.
 */
class CodexBackedCatalog<P>(
    private val live: BookCatalog<P>,
    /** Passages of chapters below `ceiling` or in resources up to `resource`, once chapters through `through` are scanned; else null. */
    private val stored: suspend (through: Int, ceiling: Int, resource: Int) -> List<Passage<P>>?,
    /** Passages whose highest chapter is `unit`, once it is scanned; else null. */
    private val storedUnit: suspend (unit: Int) -> List<Passage<P>>?,
    private val log: (Throwable) -> Unit = {},
) : BookCatalog<P> {
    override val map: ChapterMap get() = live.map

    override suspend fun scopeOf(place: P, translated: Boolean): AskingScope? = live.scopeOf(place, translated)

    override suspend fun passagesBefore(scope: AskingScope): List<Passage<P>> {
        val resource = scope.until?.resource ?: -1
        val ceiling = minOf(scope.lo, map.chapterCount)
        // Every chapter a passage below the ceiling or in those resources can end in.
        val through = maxOf(ceiling - 1, (0..resource).maxOfOrNull { map.resourceRange(it).hi } ?: -1)
        return quietly { stored(through, ceiling, resource) }?.filter { Eligibility.allows(scope, it) }
            ?: live.passagesBefore(scope)
    }

    override suspend fun passagesOf(unit: Int): List<Passage<P>> =
        quietly { storedUnit(unit) } ?: live.passagesOf(unit)

    private suspend fun <T> quietly(read: suspend () -> T?): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(e)
        null
    }
}
