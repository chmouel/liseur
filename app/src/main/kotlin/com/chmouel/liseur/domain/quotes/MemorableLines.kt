package com.chmouel.liseur.domain.quotes

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min

/** One block of a book's text, in reading order. */
data class QuoteParagraph(
    val text: String,
    /** The paragraph opens or closes its file, which is usually a chapter. */
    val chapterEdge: Boolean = false,
)

/** A sentence worth showing on its own, and where it sits in its paragraph. */
data class MemorableLine(
    val paragraph: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val score: Double,
)

/**
 * Finds the sentences of a book most likely to read well out of context.
 *
 * No model and no network: every signal is something a reader would
 * notice too. A line stands alone (its own paragraph, the end of one, the
 * edge of a chapter), speaks generally rather than about this scene
 * (present tense, "always", "nobody", "we", few names, no dialogue, no
 * "he" or "this" pointing back at something unseen), has some shape (a
 * turn on "but", a semicolon, a word coming back), and uses words this
 * book does not use often. Signals that need a word list have one for
 * the languages the app is translated into; other languages get the
 * neutral ones only.
 */
object MemorableLines {
    /** Bump when the scoring changes, so books scanned before are scanned again. */
    const val VERSION = 4

    const val KEEP = 30

    fun find(paragraphs: List<QuoteParagraph>, language: String?, keep: Int = KEEP): List<MemorableLine> {
        val lang = language?.substringBefore('-')?.substringBefore('_')?.lowercase(Locale.ROOT)
        val words = Lexicon.of(lang)
        val locale = lang?.let(Locale::forLanguageTag) ?: Locale.ROOT
        val wordIterator = BreakIterator.getWordInstance(locale)
        val sentenceIterator = BreakIterator.getSentenceInstance(locale)
        val counts = HashMap<String, Int>()
        var total = 0
        for (paragraph in paragraphs) {
            for (token in tokens(paragraph.text, wordIterator)) {
                if (token.isContentWord() && !token.isProperLike(lang)) {
                    counts.merge(token.lowercase(locale), 1, Int::plus)
                    total++
                }
            }
        }
        val rarity = Rarity(counts, total)
        val lines = ArrayList<MemorableLine>()
        paragraphs.forEachIndexed { index, paragraph ->
            if (BOILERPLATE.containsMatchIn(paragraph.text)) return@forEachIndexed
            val sentences = sentences(paragraph.text, sentenceIterator)
            sentences.forEachIndexed { at, range ->
                val next = sentences.getOrNull(at + 1)
                val connected = next != null && next.last - range.first + 1 <= SWEET_SPOT.last &&
                    startsWithTurn(paragraph.text.substring(next), words, locale)
                val ends = if (next != null && connected) listOf(range.last, next.last) else listOf(range.last)
                for (end in ends) {
                    val text = paragraph.text.substring(range.first, end + 1)
                    if (!acceptable(text)) continue
                    val place = Place(
                        alone = range.first == sentences.first().first && end == sentences.last().last,
                        last = end == sentences.last().last,
                        first = at == 0,
                        chapterEdge = paragraph.chapterEdge,
                    )
                    val score = score(text, lang, words, place, rarity, locale, wordIterator)
                    if (score >= MIN_QUALITY) {
                        lines += MemorableLine(index, range.first, end + 1, text, score)
                    }
                }
            }
        }
        return best(lines, keep, locale)
    }

    private fun startsWithTurn(text: String, words: Lexicon, locale: Locale): Boolean {
        val lower = text.lowercase(locale)
        if (locale.language == "zh") return CHINESE_CONTINUATION.any(lower::startsWith)
        val tokens = WORD.findAll(lower).map { it.value }.toList()
        return words.turns.any { tokens.take(it.size) == it }
    }

    private data class Place(
        val alone: Boolean = false,
        val last: Boolean = false,
        val first: Boolean = false,
        val chapterEdge: Boolean = false,
    )

    private fun score(
        text: String,
        lang: String?,
        words: Lexicon,
        place: Place,
        rarity: Rarity,
        locale: Locale,
        wordIterator: BreakIterator,
    ): Double {
        val tokens = tokens(text, wordIterator)
        val lower = tokens.map { it.lowercase(locale).replace('’', '\'') }
        var score = 0.0

        score += when {
            place.alone -> 1.0
            place.last -> 0.4
            place.first -> 0.15
            else -> 0.0
        }
        if (place.chapterEdge) score += 0.5

        val sweetSpot = if (isCjk(text)) CJK_SWEET_SPOT else SWEET_SPOT
        if (text.length in sweetSpot) score += 0.5 else if (text.length > LONG) score -= 0.3

        // Generality: words that speak about everyone, in the present.
        score += min(words.general.count { it in lower } * 0.5, 1.5)
        score += min(lower.count { it in words.present } * 0.3, 0.6)
        score -= min(lower.count { it in words.past } * 0.35, 1.0)
        if (lower.firstOrNull() in words.pointing) score -= 1.5
        if (words.references.any { lower.containsPhrase(it) }) score -= 1.8
        if (lang == "zh") {
            if (CHINESE_REFERENCE.containsMatchIn(text)) score -= 1.8
            if (CHINESE_TURN.containsMatchIn(text)) score += 0.8
        }
        score -= min(lower.count { it in words.thirdPerson } * 0.25, 0.75)

        // Shape: a turn, a balance, a word that comes back.
        if (MIDDLE_TURN.containsMatchIn(text)) score += 0.4
        if (words.turns.any { lower.drop(1).containsPhrase(it) }) score += 0.4
        val content = lower.filter { it.isContentWord() && it !in words.general }
        if (content.size != content.toSet().size) score += 0.6

        // Context the line cannot carry by itself.
        if (DIALOGUE.containsMatchIn(text)) score -= 1.5
        if (lower.any { it in words.speech }) score -= 1.0
        if (!words.capitalisesNouns && !isCjk(text)) {
            val names = tokens.drop(1).count { it.isProperLike(lang) }
            score -= min(names * 0.5, 2.0)
        }
        if (text.any(Char::isDigit)) score -= 0.5
        val ending = text.trimEnd(*CLOSERS)
        if (ending.endsWith('!') || ending.endsWith('！')) score -= 0.3
        if (ending.endsWith('?') || ending.endsWith('？')) score -= 0.2

        // Distinctive within this book: its rarer words, names left out.
        val distinctive = tokens.filter { it.isContentWord() && !it.isProperLike(lang) }
        score += 1.2 * rarity.of(distinctive.map { it.lowercase(locale) })
        return score
    }

    /** Whether [text] could be shown at all, before asking whether it is any good. */
    internal fun acceptable(text: String): Boolean {
        val cjk = isCjk(text)
        if (text.length !in (if (cjk) CJK_LENGTH else LENGTH)) return false
        val last = text.trimEnd(*CLOSERS).lastOrNull() ?: return false
        if (last !in ENDINGS) return false
        if (!cjk) {
            val first = text.trimStart(*OPENERS).firstOrNull() ?: return false
            val letters = text.filter(Char::isLetter)
            if (!first.isLetter() || letters.isEmpty()) return false
            if (letters.any { it.isUpperCase() || it.isLowerCase() } && !first.isUpperCase()) return false
            if (WORD.findAll(text).count() < MIN_WORDS) return false
            if (letters.count(Char::isUpperCase) * 2 > letters.length) return false
        }
        return !JUNK.containsMatchIn(text) && balanced(text)
    }

    private fun balanced(text: String): Boolean =
        text.count { it == '(' } == text.count { it == ')' } &&
            text.count { it == '[' } == text.count { it == ']' } &&
            text.count { it == '“' } == text.count { it == '”' || it == '„' } &&
            text.count { it == '«' } == text.count { it == '»' } &&
            text.count { it == '"' } % 2 == 0

    /** The best lines, one per paragraph and none repeated. */
    private fun best(lines: List<MemorableLine>, keep: Int, locale: Locale): List<MemorableLine> {
        val seen = HashSet<String>()
        val paragraphs = HashSet<Int>()
        return lines
            .sortedWith(compareByDescending<MemorableLine> { it.score }.thenBy { it.paragraph }.thenBy { it.start })
            .filter { line ->
                val key = line.text.lowercase(locale).filter(Char::isLetterOrDigit).take(DUPLICATE_PREFIX)
                line.paragraph !in paragraphs && seen.add(key) && paragraphs.add(line.paragraph)
            }.take(keep)
    }

    /** Sentence ranges in [text], inclusive, trimmed of surrounding space. */
    internal fun sentences(text: String, iterator: BreakIterator): List<IntRange> {
        iterator.setText(text)
        val ranges = ArrayList<IntRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            var from = start
            var to = end
            while (from < to && text[from].isWhitespace()) from++
            while (to > from && text[to - 1].isWhitespace()) to--
            if (to > from) ranges += from until to
            start = end
            end = iterator.next()
        }
        return ranges
    }

    private fun tokens(text: String, wordIterator: BreakIterator): List<String> {
        if (!isCjk(text)) return WORD.findAll(text).map { it.value }.toList()
        wordIterator.setText(text)
        return buildList {
            var start = wordIterator.first()
            var end = wordIterator.next()
            while (end != BreakIterator.DONE) {
                val token = text.substring(start, end)
                if (token.any(Char::isLetter)) add(token)
                start = end
                end = wordIterator.next()
            }
        }
    }

    private fun String.isContentWord(): Boolean = length >= MIN_CONTENT_WORD || isCjk(this)

    private fun String.isProperLike(lang: String?): Boolean =
        lang != "de" && length > 1 && first().isUpperCase()

    private fun List<String>.containsPhrase(phrase: List<String>): Boolean =
        phrase.isNotEmpty() && size >= phrase.size && windowed(phrase.size).any { it == phrase }

    internal fun isCjk(text: String): Boolean {
        val letters = text.filter(Char::isLetter)
        if (letters.isEmpty()) return false
        return letters.count {
            val block = Character.UnicodeBlock.of(it)
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES
        } * 2 > letters.length
    }

    /** How unusual a line's words are in this book, from 0 (all common) to about 1. */
    internal class Rarity(private val counts: Map<String, Int>, private val total: Int) {
        fun of(words: List<String>): Double {
            if (words.isEmpty() || total < 2) return 0.0
            val top = ln(total.toDouble())
            return words.sumOf { ln(total.toDouble() / (counts[it] ?: 1)) / top } / words.size
        }
    }

    private val LENGTH = 40..240
    private val CJK_LENGTH = 15..90
    private val CJK_SWEET_SPOT = 20..55
    private const val MIN_QUALITY = 2.1
    private val SWEET_SPOT = 60..160
    private const val LONG = 200
    private const val MIN_WORDS = 6
    private const val MIN_CONTENT_WORD = 4
    private const val DUPLICATE_PREFIX = 60
    private val ENDINGS = setOf('.', '!', '?', '…', '。', '！', '？')
    private val CLOSERS = charArrayOf('"', '”', '’', '»', ')', '“', '」', '』', ' ', '\u00A0')
    private val OPENERS = charArrayOf('"', '“', '‘', '«', '¿', '¡', '„', '(', ' ', '\u00A0')
    private val WORD = Regex("\\p{L}[\\p{L}\\p{M}'’-]*")
    private val CHINESE_CONTINUATION = listOf("但是", "然而", "可是", "反而", "而是")
    private val CHINESE_REFERENCE = Regex("^[「『“]?(?:他|她|他们|她们|那时|这时|于是|随后)|(?:对他说|对她说|接着说道)")
    private val CHINESE_TURN = Regex("不但.+而且|不是.+而是|与其.+不如|越.+[，,].*越|[，,；;](?:却|但|反而)|；.+；")
    private val MIDDLE_TURN = Regex("\\S[;:]\\s+\\S")
    private val DIALOGUE = Regex("^\\s*[-—–\"“«„‘'「『]")
    /**
     * The publisher's small print: Project Gutenberg's header and licence,
     * copyright pages. A paragraph that says any of this is skipped whole,
     * since its other sentences ("If you are not located in the United
     * States…") read as prose but are the same notice.
     *
     * "eBook" needs its word boundaries, or "notebook" and "Facebook"
     * read as notices. The apostrophes accept the typographic form a
     * printed notice usually carries.
     */
    private val BOILERPLATE = Regex(
        "gutenberg|\\be-?books?\\b|copyright|trademark|licen[cs]e|all rights reserved|public domain" +
            "|droits? d['’]auteur|tous droits|urheberrecht|derechos de autor" +
            "|diritt[oi] d['’]autore|tutti i diritti riservati|direitos autorais" +
            "|авторск|版权",
        RegexOption.IGNORE_CASE,
    )

    private val JUNK = Regex(
        "https?:|www\\.|@|©|ISBN|\\d{4,}|[|•_*#<>{}=]|\\.{4,}" +
            "|^\\s*(chapter|chapitre|kapitel|capítulo|capitolo|глава|kapitola)\\b",
        RegexOption.IGNORE_CASE,
    )
}

/** The few words per language some of the signals need. */
internal class Lexicon(
    val general: Set<String>,
    val present: Set<String>,
    val past: Set<String>,
    /** Words that, opening a sentence, point back at something the line does not show. */
    val pointing: Set<String>,
    val thirdPerson: Set<String>,
    val speech: Set<String>,
    val turns: List<List<String>>,
    /** German capitalises every noun, so a capital says nothing about a name. */
    val capitalisesNouns: Boolean = false,
    val references: List<List<String>> = emptyList(),
) {
    companion object {
        private val NONE = Lexicon(emptySet(), emptySet(), emptySet(), emptySet(), emptySet(), emptySet(), emptyList())

        fun of(lang: String?): Lexicon = when (lang) {
            "en" -> EN
            "fr" -> FR
            "es" -> ES
            "it" -> IT
            "de" -> DE
            "pt" -> PT
            "cs" -> CS
            "ru" -> RU
            else -> NONE
        }

        private fun words(text: String) = text.split(' ').filter(String::isNotBlank).toSet()

        private fun turns(vararg phrases: String) = phrases.map { it.split(' ') }

        private val EN = Lexicon(
            general = words(
                "always never every everyone everything nothing nobody none whoever whatever life death love " +
                    "time world truth man men people human heart soul we our us sometimes",
            ),
            present = words("is are does can must cannot"),
            past = words("was were had did said went came looked"),
            pointing = words(
                "he she it they this that these those his her their then so and but or yes no well oh him them there here",
            ),
            thirdPerson = words("he she him her his hers"),
            speech = words("said asked replied whispered shouted cried answered muttered"),
            turns = turns("but", "yet", "not only", "the more", "rather than", "instead of"),
            references = turns("as he said", "as she said", "looked at him", "looked at her", "take care of it", "we shall therefore speak"),
        )

        private val FR = Lexicon(
            general = words(
                "toujours jamais chaque tout tous toutes rien personne vie mort amour temps monde vérité homme " +
                    "hommes gens cœur âme nous on parfois",
            ),
            present = words("est sont peut doit faut"),
            past = words("était étaient avait avaient fut dit alla vint regarda"),
            pointing = words("il elle ils elles ce cela ça ceci celui celle alors donc et mais ou oui non lui leur là"),
            thirdPerson = words("il elle lui son sa ses"),
            speech = words("dit demanda répondit murmura s'écria cria ajouta"),
            turns = turns("mais", "pourtant", "non seulement", "plus on", "plutôt que", "au lieu de"),
            references = turns(
                "s'en occuper", "s'en occupe", "s'en occupait", "m'en occuper", "nous en occuper",
                "vous en occuper", "s'en charger", "le lui dit", "la regarda",
            ),
        )

        private val ES = Lexicon(
            general = words(
                "siempre nunca cada todo todos nada nadie vida muerte amor tiempo mundo verdad hombre hombres " +
                    "gente corazón alma nosotros",
            ),
            present = words("es son puede debe hay"),
            past = words("era eran había fue dijo fueron miró"),
            pointing = words("él ella ellos ellas esto eso este ese esa entonces y pero o sí no le les allí"),
            thirdPerson = words("él ella su sus le"),
            speech = words("dijo preguntó respondió susurró gritó contestó"),
            turns = turns("pero", "sin embargo", "no solo", "cuanto más", "en lugar de"),
            references = turns("se lo dijo", "se lo explicó", "se hizo cargo de ello", "la miró"),
        )

        private val IT = Lexicon(
            general = words(
                "sempre mai ogni tutto tutti niente nessuno vita morte amore tempo mondo verità uomo uomini " +
                    "gente cuore anima noi",
            ),
            present = words("è sono può deve"),
            past = words("era erano aveva fu disse andò guardò"),
            pointing = words("lui lei loro questo quello questa quella allora e ma o sì no gli là"),
            thirdPerson = words("lui lei suo sua suoi gli"),
            speech = words("disse chiese rispose sussurrò gridò"),
            turns = turns("ma", "eppure", "non solo", "più si", "invece di"),
            references = turns("glielo disse", "se ne occupò", "occuparcene", "la guardò", "quel primo", "quel secondo"),
        )

        private val DE = Lexicon(
            general = words(
                "immer nie niemals jeder jede alles nichts niemand leben tod liebe zeit welt wahrheit mensch " +
                    "menschen herz seele wir man",
            ),
            present = words("ist sind kann muss"),
            past = words("war waren hatte hatten sagte ging kam sah"),
            pointing = words("er sie es dies das diese dieser jener dann also und aber oder ja nein ihm ihn ihr dort"),
            thirdPerson = words("er ihm ihn sein seine"),
            speech = words("sagte fragte antwortete flüsterte rief"),
            turns = turns("aber", "doch", "nicht nur", "je mehr", "statt"),
            references = turns("sagte er zu ihr", "sagte sie zu ihm", "sich darum kümmern", "sah ihn an"),
            capitalisesNouns = true,
        )

        private val PT = Lexicon(
            general = words(
                "sempre nunca cada tudo todos nada ninguém vida morte amor tempo mundo verdade homem homens " +
                    "gente coração alma nós",
            ),
            present = words("é são pode deve há"),
            past = words("era eram tinha foi disse olhou"),
            pointing = words("ele ela eles elas isto isso este esse essa então e mas ou sim não lhe lá"),
            thirdPerson = words("ele ela seu sua seus lhe"),
            speech = words("disse perguntou respondeu sussurrou gritou"),
            turns = turns("mas", "porém", "não só", "quanto mais", "em vez de"),
            references = turns("disse a ele", "disse a ela", "cuidar disso", "olhou para ele", "vi-lhe fazer", "que elle interrompesse"),
        )

        private val CS = Lexicon(
            general = words("vždy nikdy každý všechno nic nikdo život smrt láska čas svět pravda člověk lidé srdce duše my"),
            present = words("je jsou může musí"),
            past = words("byl byla bylo byli měl řekl šel"),
            pointing = words("on ona oni ono to tento tato ten pak tak a ale nebo ano ne jeho její tam"),
            thirdPerson = words("on ona jeho její mu"),
            speech = words("řekl řekla zeptal odpověděl zašeptal vykřikl"),
            turns = turns("ale", "přesto", "nejen", "čím víc", "místo"),
            references = turns("řekl jí", "řekla mu", "podíval se na ni", "postarat se o to", "se jim ale"),
        )

        private val RU = Lexicon(
            general = words(
                "всегда никогда каждый всё все ничего никто жизнь смерть любовь время мир правда человек люди сердце душа мы",
            ),
            present = words("есть может должен нужно"),
            past = words("был была было были сказал пошёл посмотрел"),
            pointing = words("он она они оно это этот эта тот тогда так и но или да нет его её там"),
            thirdPerson = words("он она его её ему"),
            speech = words("сказал сказала спросил ответил прошептал крикнул"),
            turns = turns("но", "однако", "не только", "чем больше", "вместо"),
            references = turns("сказал ей", "сказала ему", "посмотрел на неё", "заняться этим", "история эта"),
        )
    }
}
