package com.chmouel.liseur.reader.dictionary

internal data class DefinitionQuery(val word: String, val language: String?) {
    fun fallbackWords(): List<String> {
        val cleaned = cleanDictionarySelection(word.lineSequence().first())
        val withoutAffix = when (language?.let(OfflineDictionary::primaryLanguage)) {
            "en" -> cleaned.removeSuffix("'s")
            "fr" -> cleaned.replace(frenchElision, "")
            else -> cleaned
        }.trim()
        return listOf(cleaned, withoutAffix).distinct().filter { it.isNotEmpty() && it != word }
    }
}

internal fun definitionQuery(
    originalText: String,
    displayedText: String?,
    language: String?,
    useDisplayedText: Boolean,
): DefinitionQuery? {
    val languageTag = language?.trim()?.takeIf { it.isNotEmpty() }
    val word = (if (useDisplayedText) displayedText else originalText)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return DefinitionQuery(word, languageTag)
}

private val unicodeSpaces = Regex("""\p{Zs}""")
private val pronunciationMarks = Regex("[·|↑ˈˌ]")
private val frenchElision = Regex("^(?:[lsdmntjc]|qu)'", RegexOption.IGNORE_CASE)
// Everything outside these categories is punctuation, a quote, a bracket or a space
// in some script, so it only ever decorates the edges of a selected word.
private val edgeDecoration = Regex("^[^\\p{L}\\p{N}\\p{M}]+|[^\\p{L}\\p{N}\\p{M}]+$")

private fun cleanDictionarySelection(text: String): String =
    text.replace(unicodeSpaces, " ")
        .trim()
        .replace('’', '\'')
        .replace(pronunciationMarks, "")
        .replace(edgeDecoration, "")
        .trim()
