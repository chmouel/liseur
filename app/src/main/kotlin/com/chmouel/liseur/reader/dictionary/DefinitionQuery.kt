package com.chmouel.liseur.reader.dictionary

internal data class DefinitionQuery(val word: String, val language: String?)

internal fun definitionQuery(
    originalText: String,
    displayedText: String?,
    language: String?,
    useDisplayedText: Boolean,
): DefinitionQuery? {
    val languageTag = language?.trim()?.takeIf { it.isNotEmpty() }
    val word = (if (useDisplayedText) displayedText else originalText)
        ?.let { cleanDictionarySelection(it, languageTag) }
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return DefinitionQuery(word, languageTag)
}

private val unicodeSpaces = Regex("""\p{Zs}""")
private val generalPunctuation = Regex("[\u2000-\u206F]")
private val pronunciationMarks = Regex("[·|↑ˈˌ]")
private val frenchElision = Regex("^(?:[lsdmnt]|qu)'", RegexOption.IGNORE_CASE)

private fun cleanDictionarySelection(text: String, language: String?): String {
    val cleaned = text.replace(unicodeSpaces, " ")
        .trim()
        .replace('’', '\'')
        .replace(generalPunctuation, "")
        .trim { it in '!'..'/' || it in ':'..'@' || it in '['..'`' || it in '{'..'~' }
        .replace(pronunciationMarks, "")
    return when (language?.let(OfflineDictionary::primaryLanguage)) {
        "en" -> cleaned.removeSuffix("'s")
        "fr" -> cleaned.replace(frenchElision, "")
        else -> cleaned
    }.trim()
}
