package com.chmouel.liseur.translate

import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.companion.CodexBookRow
import com.chmouel.liseur.companion.CodexStore
import com.chmouel.liseur.companion.PublicationCatalog
import com.chmouel.liseur.reader.PublicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

internal suspend fun AppContainer.translationPlace(bookUrl: String, publication: Publication, place: Locator?): Locator =
    place ?: database.readingProgressDao().get(bookUrl)?.locatorJson?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        ?: publication.readingOrder.firstOrNull()?.let(publication::locatorFromLink)
        ?: throw TranslationError.Unsupported()

internal suspend fun AppContainer.translationPassage(bookUrl: String, place: Locator?): Pair<String?, String> =
    withTranslationPublication(bookUrl) { publication ->
        val source = TranslationLanguages.source(publication.metadata.languages)
        val start = translationPlace(bookUrl, publication, place)
        val sentences = PublicationContext.sentences(publication, start, source)
        // The cursor starts at the place's element: the passage starts at the sentence holding its first word.
        val read = mutableListOf<String>()
        var from = 0
        if (!start.text.highlight.isNullOrBlank()) {
            while (read.size < PREVIEW_SEEK) {
                read += sentences.next()?.text ?: break
                sentenceHolding(read, start.text.before, start.text.highlight)?.let { from = it; break }
            }
        }
        val ahead = ArrayDeque(read.drop(from))
        val texts = mutableListOf<String>()
        repeat(3) {
            val sentence = ahead.removeFirstOrNull() ?: sentences.next()?.text ?: return@repeat
            if (texts.sumOf { it.length + 1 } + sentence.length <= TranslationPrompt.MAX_CHARACTERS) texts += sentence
        }
        source to texts.joinToString(" ").ifEmpty { throw TranslationError.Empty() }
    }

private const val PREVIEW_SEEK = 64

/**
 * The index of the sentence, among [sentences] read from the start of an
 * element, holding the word [highlight] after [before]; null when they do
 * not hold it.
 */
internal fun sentenceHolding(sentences: List<String>, before: String?, highlight: String?): Int? {
    val word = squash(highlight.orEmpty()).ifEmpty { return null }
    val lead = squash(before.orEmpty())
    val joined = StringBuilder()
    val starts = sentences.map { sentence -> joined.length.also { joined.append(squash(sentence)) } }
    val at = joined.indexOf(lead + word).takeIf { it >= 0 } ?: return null
    return starts.indexOfLast { it <= at + lead.length }
}

// Readium leaves out the soft hyphens and zero-width spaces the page's own text keeps, as SpokenPassage allows for.
private fun squash(text: String) = text.filterNot { it.isWhitespace() || it == '\u00AD' || it == '\u200B' }

internal suspend fun AppContainer.cachedTranslationNames(bookUrl: String, place: Locator?): List<String> {
    if (!appSettings.settings.first().codexEnabled) return emptyList()
    // A phone that never used the companion has no names to suggest, and none is made to say so.
    if (!companionStoreExists()) return emptyList()
    return withTranslationPublication(bookUrl) { publication ->
        val catalog = PublicationCatalog(publication)
        val scope = catalog.scopeOf(translationPlace(bookUrl, publication, place), translated = false) ?: return@withTranslationPublication emptyList()
        val book = database.bookDao().getByUrl(bookUrl) ?: return@withTranslationPublication emptyList()
        val sha = bookFingerprints.of(book)?.sha256 ?: return@withTranslationPublication emptyList()
        val expected = CodexBookRow(bookUrl, CodexStore.identity(sha, catalog.map), catalog.map.chapterCount)
        companionStore.codex.cachedNames(expected, scope.lo)
    }
}
