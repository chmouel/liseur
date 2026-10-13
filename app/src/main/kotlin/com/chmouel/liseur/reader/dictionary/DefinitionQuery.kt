package com.chmouel.liseur.reader.dictionary

internal data class DefinitionQuery(val word: String, val language: String?)

internal fun definitionQuery(
    originalText: String,
    displayedText: String?,
    language: String?,
    useDisplayedText: Boolean,
): DefinitionQuery? {
    val word = (if (useDisplayedText) displayedText else originalText)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return DefinitionQuery(word, language?.trim()?.takeIf { it.isNotEmpty() })
}
