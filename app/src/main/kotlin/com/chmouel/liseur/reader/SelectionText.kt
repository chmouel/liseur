package com.chmouel.liseur.reader

internal suspend fun selectionActionText(
    original: String,
    translated: Boolean,
    inSheet: Boolean,
    retainedText: String?,
    readDisplayedText: suspend () -> String?,
): String? {
    val text = when {
        !translated -> original
        inSheet -> retainedText
        else -> readDisplayedText()
    }
    return text?.takeIf { it.isNotBlank() }
}
