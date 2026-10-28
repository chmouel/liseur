package com.chmouel.liseur.reader.annotations

import com.chmouel.liseur.tts.BookLanguage
import com.chmouel.liseur.tts.SpeechLanguage
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

internal fun selectionWebsiteTemplate(value: String): String? {
    val template = value.trim()
    if (!template.contains("{text}")) return null
    val uri = runCatching { URI(template.replace("{text}", "liseur-selection")) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
        uri.port !in -1..65535) return null
    val authority = template.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.contains("{text}")) return null
    return template
}

internal fun selectionWebUrl(entry: SelectionActionEntry, text: String, language: String?): String? {
    val term = text.trim().takeIf { it.isNotEmpty() } ?: return null
    val template = when (val target = entry.target) {
        is SelectionActionTarget.Website -> selectionWebsiteTemplate(target.urlTemplate) ?: return null
        is SelectionActionTarget.BuiltIn -> when (target.action) {
            SelectionAction.WIKIPEDIA -> "https://${wikipediaEdition(language)}.wikipedia.org/w/index.php?search={text}"
            SelectionAction.OPEN_LIBRARY -> "https://openlibrary.org/search?q={text}"
            else -> return null
        }
    }
    return template.replace("{text}", URLEncoder.encode(term, "UTF-8").replace("+", "%20"))
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
