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
private val frenchElision = Regex("^(?:[lsdmnt]|qu)'", RegexOption.IGNORE_CASE)

private fun cleanDictionarySelection(text: String): String =
    text.replace(unicodeSpaces, " ")
        .trim()
        .replace('’', '\'')
        .trim { it in '!'..'/' || it in ':'..'@' || it in '['..'`' || it in '{'..'~' || it in '\u2000'..'\u206F' }
        .replace(pronunciationMarks, "")
        .trim()
