package com.chmouel.liseur.reader.dictionary

import org.jsoup.Jsoup

internal fun vocabularyOfflineDefinitionLines(entries: List<StarDictEntry>): List<String> =
    entries.flatMap { entry ->
        (entry.sections.flatMap { it.items } + listOfNotNull(entry.fallbackHtml))
            .map { Jsoup.parseBodyFragment(it).text() }
    }


internal fun vocabularyOnlineDefinitionLines(senses: List<DictionarySense>, bookLanguage: String?): List<String> {
    val preferred = if (bookLanguage == null) emptyList() else senses.filter {
        OfflineDictionary.samePrimary(it.language, bookLanguage)
    }
    val definitions = preferred.ifEmpty {
        senses.filterNot { it.language.equals("mul", ignoreCase = true) }.ifEmpty { senses }
    }
    return definitions.flatMap { sense ->
        sense.definitions.flatMapIndexed { index, line ->
            val resolved = sense.resolved[index]?.senses.orEmpty().flatMap { it.definitions }
            listOf(line) + resolved
        }
    }
}
