package com.chmouel.liseur.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.use
import java.util.TreeMap

/** Walks independently of the interactive companion; committed chapters are released immediately. */
@OptIn(ExperimentalReadiumApi::class)
class PublicationCodexSource(private val publication: Publication, val map: ChapterMap) {
    private val readingOrder = publication.readingOrder.map { it.url().toString().path() }

    suspend fun scan(from: Int, commit: suspend (Int, List<Passage<Locator>>) -> Unit) = withContext(Dispatchers.IO) {
        if (from >= map.chapterCount) return@withContext
        // Restart at the first resource that can contribute to the unfinished chapter,
        // preserving passage ordinals even when chapters share files or anchors are missing.
        val first = readingOrder.indices.firstOrNull { map.resourceRange(it).hi >= from } ?: 0
        suspend fun html(resource: Int): String =
            publication.get(publication.readingOrder[resource])?.use { it.read().getOrNull()?.decodeToString() }
                ?: error("Unreadable resource")
        var verified = first - 1
        val builder = PassageCatalogBuilder<Locator>(map, readingOrder, firstResource = first) { resource ->
            // The content service can skip an unreadable resource. Verify skipped
            // resources before treating their chapters as legitimately empty.
            for (skipped in verified + 1 until resource) html(skipped)
            html(resource).also { verified = resource }
        }
        val start = checkNotNull(publication.locatorFromLink(publication.readingOrder[first]))
        val iterator = checkNotNull(publication.content(start)) { "No content service" }.iterator()
        val pending = TreeMap<Int, MutableList<Passage<Locator>>>()
        var next = from
        suspend fun flush(before: Int) {
            while (next < before) {
                coroutineContext.ensureActive()
                commit(next, pending.remove(next).orEmpty())
                next++
            }
        }
        while (true) {
            coroutineContext.ensureActive()
            val element = iterator.nextOrNull() ?: break
            val text = when (element) {
                is Content.TextElement -> element.segments.joinToString("") { it.text }
                is Content.TextualElement -> element.text
                else -> null
            } ?: continue
            if (text.none(Char::isLetterOrDigit)) continue
            val locator = element.locator
            val passages = builder.add(SourceElement(
                locator.href.toString().path(), locator.locations.otherLocations["cssSelector"] as? String, text, locator,
            ))
            if (builder.current >= 0) flush(map.floorFrom(builder.current))
            for (passage in passages) {
                if (passage.range.hi >= from) {
                    check(passage.range.hi >= next) { "Chapter order changed" }
                    pending.getOrPut(passage.range.hi) { mutableListOf() }.add(passage)
                }
            }
        }
        for (resource in verified + 1 until readingOrder.size) html(resource)
        flush(map.chapterCount)
    }

    private fun String.path() = substringBefore('#').substringBefore('?')
}
