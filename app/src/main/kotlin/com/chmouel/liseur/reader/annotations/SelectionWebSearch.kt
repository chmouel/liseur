package com.chmouel.liseur.reader.annotations

import com.chmouel.liseur.tts.BookLanguage
import com.chmouel.liseur.tts.SpeechLanguage
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** Book details a custom website address can ask for; [text] and the language are passed separately. */
internal data class SelectionBookContext(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val chapter: String? = null,
    val progress: Int? = null,
    val page: Int? = null,
    val pages: Int? = null,
    val isbn: String? = null,
)

internal val SELECTION_WEB_PLACEHOLDERS = listOf("text", "title", "author", "chapter", "progress", "page", "pages", "language", "isbn")

private val PLACEHOLDER = Regex("""\{([a-z]+)\}""")

internal fun selectionWebsiteTemplate(value: String): String? {
    val template = value.trim()
    if (PLACEHOLDER.findAll(template).none { it.groupValues[1] in SELECTION_WEB_PLACEHOLDERS }) return null
    val authority = template.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.contains('{') || authority.contains('}')) return null
    val uri = runCatching { URI(expand(template) { "liseur" }) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
        uri.port !in -1..65535) return null
    return template
}

/** Built-ins that send an AI assistant a question about the passage rather than the bare selection. */
internal val SelectionAction?.asksAi: Boolean get() = this == SelectionAction.ASK_BRAVE || this == SelectionAction.DUCK_AI

internal fun selectionWebUrl(
    entry: SelectionActionEntry,
    text: String,
    language: String?,
    book: SelectionBookContext = SelectionBookContext(),
): String? {
    val term = text.trim().takeIf { it.isNotEmpty() } ?: return null
    val template = when (val target = entry.target) {
        is SelectionActionTarget.Website -> selectionWebsiteTemplate(target.urlTemplate) ?: return null
        is SelectionActionTarget.BuiltIn -> when (target.action) {
            SelectionAction.WIKIPEDIA -> "https://${wikipediaEdition(language)}.wikipedia.org/w/index.php?search={text}"
            SelectionAction.OPEN_LIBRARY -> "https://openlibrary.org/search?q={text}"
            SelectionAction.ASK_BRAVE -> "https://search.brave.com/ask?q={text}"
            SelectionAction.DUCK_AI -> "https://duckduckgo.com/?q={text}&ia=chat&bang=true"
            else -> return null
        }
    }
    return expand(template) { name ->
        when (name) {
            "text" -> term
            "title" -> book.title
            "author" -> book.authors.filter { it.isNotBlank() }.joinToString(", ")
            "chapter" -> book.chapter
            "progress" -> book.progress?.toString()
            "page" -> book.page?.toString()
            "pages" -> book.pages?.toString()
            "language" -> language
            "isbn" -> book.isbn
            else -> null
        }.orEmpty().trim().let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}

// One pass, so a value that itself looks like a placeholder is never expanded again; braces left
// over from unknown placeholders are kept as typed, percent-encoded so the address stays valid.
private fun expand(template: String, value: (String) -> String): String = buildString {
    var at = 0
    for (match in PLACEHOLDER.findAll(template)) {
        if (match.groupValues[1] !in SELECTION_WEB_PLACEHOLDERS) continue
        appendLiteral(template.substring(at, match.range.first))
        append(value(match.groupValues[1]))
        at = match.range.last + 1
    }
    appendLiteral(template.substring(at))
}

private fun StringBuilder.appendLiteral(part: String) {
    append(part.replace("{", "%7B").replace("}", "%7D"))
}

/** The ISBN in an EPUB identifier such as `urn:isbn:978-2-07-036822-8`, or null when it is not one. */
internal fun isbnOf(identifier: String?): String? {
    val digits = identifier?.trim()?.replace(Regex("^(urn:)?isbn:", RegexOption.IGNORE_CASE), "")
        ?.filterNot { it == '-' || it == ' ' }?.uppercase(Locale.ROOT) ?: return null
    val valid = when {
        digits.matches(Regex("\\d{13}")) ->
            digits.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum() % 10 == 0
        digits.matches(Regex("\\d{9}[\\dX]")) ->
            digits.mapIndexed { i, c -> (if (c == 'X') 10 else c - '0') * (10 - i) }.sum() % 11 == 0
        else -> false
    }
    return digits.takeIf { valid }
}

internal fun selectionSearchLanguage(declared: List<String>, translated: Boolean, target: String?): String? =
    if (translated) SpeechLanguage.normalize(target) else (SpeechLanguage.ofBook(declared) as? BookLanguage.Known)?.tag

internal fun wikipediaEdition(language: String?): String {
    val normal = SpeechLanguage.normalize(language)?.lowercase(Locale.ROOT) ?: return "en"
    if (normal in WIKIPEDIA_EDITIONS) return normal
    val primary = normal.substringBefore('-')
    val edition = WIKIPEDIA_ALIASES[primary] ?: primary
    return edition.takeIf { it in WIKIPEDIA_EDITIONS } ?: "en"
}

private val WIKIPEDIA_ALIASES = mapOf(
    "nb" to "no", "gsw" to "als", "lzh" to "zh-classical", "nan" to "zh-min-nan",
    "yue" to "zh-yue", "sgs" to "bat-smg", "vro" to "fiu-vro", "rup" to "roa-rup",
)

// Edition hosts listed by https://www.wikipedia.org; no discovery request is made while reading
private val WIKIPEDIA_EDITIONS = """
    ab ace ady af als alt am ami an ang ann anp ar arc ary arz as ast atj av avk awa ay az azb
    ba ban bar bat-smg bbc bcl bdr be be-tarask bew bg bh bi bjn blk bm bn bo bol bpy br bs btm bug bxr
    ca cbk-zam cdo ce ceb ch chr chy ckb co crh cs csb cu cv cy da dag de dga din diq dsb dtp dty dv dz
    ee el eml en eo es et eu ext fa fat ff fi fiu-vro fj fo fon fr frp frr fur fy ga gag gan gcr gd gl glk
    gn gom gor got gpe gu guc gur guw gv ha hak haw he hi hif hr hsb ht hu hy hyw ia iba id ie ig igl ik
    ilo inh io is isv it iu ja jam jbo jv ka kaa kab kai kaj kbd kbp kcg kg kge ki kk km kn knc ko koi krc
    ks ksh ku kus kv kw ky la lad lb lbe lez lfn lg li lij lld lmo ln lo lt ltg lv mad mag mai map-bms mdf
    mg mhr mi min mk ml mn mni mnw mos mr mrj ms mt mwl my myv mzn nah nap nds nds-nl ne new nia nl nn no
    nov nqo nr nrm nso nup nv ny oc olo om or os pa pag pam pap pcd pcm pdc pfl pi pl pms pnb pnt ppl ps
    pt pwn qu rki rm rmy rn ro roa-rup roa-tara rsk ru rue rw sa sah sat sc scn sco sd se sg sh shi shn si
    simple sk skr sl sm smn sn so sq sr srn ss st stq su sv sw syl szl szy ta tay tcy tdd te tet tg th ti tk
    tl tly tn to tok tpi tr trv ts tt tum tw ty tyv udm ug uk ur uz ve vec vep vi vls vo wa war wo wuu xal
    xh xmf yi yo za zea zgh zh zh-classical zh-min-nan zh-yue zu
""".trim().split(Regex("\\s+")).toSet()
