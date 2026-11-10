package com.chmouel.liseur.ui.settings.search

import com.chmouel.liseur.ui.navigation.settingsParent
import java.text.Normalizer
import java.util.Locale

/** A row with its words read out in the app's language, ready to be matched. */
data class SettingsResult(
    val entry: SettingsEntry,
    val title: String,
    val summary: String?,
    /** Where the row lives: its pages, then its section. */
    val path: String,
) {
    internal val matchTitle = SettingsSearch.normalise(title)
    internal val matchRest = SettingsSearch.normalise(listOfNotNull(summary, path).joinToString(" "))
}

object SettingsSearch {
    private const val PATH_SEPARATOR = " › "
    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")

    /** Lower case, without accents, so "theme" finds "Thème". */
    fun normalise(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT)

    /** The rows [context] would show, read out through [string]. */
    fun resolve(
        entries: List<SettingsEntry>,
        context: SearchContext,
        string: (Int) -> String,
    ): List<SettingsResult> = entries.filter { it.shown(context) }.map { entry ->
        val pages = generateSequence(entry.page) { it.settingsParent }.toList().asReversed()
        val path = (pages.map { string(it.settingsTitle) } + listOfNotNull(entry.section?.let(string)))
            .joinToString(PATH_SEPARATOR)
        SettingsResult(entry, string(entry.title), entry.summary?.let(string), path)
    }

    /**
     * The rows where every word of [query] appears, in the title, the
     * description or the path.
     *
     * A substring match rather than a word match, so a language written
     * without spaces is searched the same way. Rows whose title starts
     * with the query come first, then those where a title word does, then
     * any title match, then the rest; each in page order.
     */
    fun match(query: String, results: List<SettingsResult>): List<SettingsResult> {
        val wanted = normalise(query).trim()
        if (wanted.isEmpty()) return emptyList()
        val words = wanted.split(spaces)
        return results
            .filter { result -> words.all { it in result.matchTitle || it in result.matchRest } }
            .sortedBy { rank(it, wanted, words) }
    }

    private fun rank(result: SettingsResult, wanted: String, words: List<String>): Int {
        val title = result.matchTitle
        val titleWords = title.split(spaces)
        return when {
            title.startsWith(wanted) -> 0
            words.all { word -> titleWords.any { it.startsWith(word) } } -> 1
            words.all { it in title } -> 2
            else -> 3
        }
    }
}
