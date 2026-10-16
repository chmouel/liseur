package com.chmouel.liseur.companion

import java.text.Normalizer

/** What one unit's scan learned about one name: how often it was counted, and how often it sat beside a speech or gesture verb. */
data class CodexStats(val occurrences: Int, val speechHits: Int)

/** What kind of thing a codex entry is. UNKNOWN is a real answer: a bare name with no title, no speech line and no head noun genuinely does not say. */
enum class CodexKind { PERSON, PLACE, FACTION, UNKNOWN }

/**
 * Heuristic proper-noun extraction for the codex, over scripts that mark
 * case: Latin, Cyrillic and Greek. No model, no dictionary: capitalization
 * carries almost all the signal. The rule set follows the codex NovelForge
 * publishes (GPL-3.0); this is a reimplementation of its published rules,
 * not a port of its code.
 *
 *  - A capitalized token mid-sentence is strong evidence of a name. One at
 *    a sentence start is weak, and counts only when the same name also has
 *    strong evidence in the same unit: "Suddenly" never survives, while
 *    "Xiulan looked…" does because she appears mid-sentence elsewhere.
 *  - Consecutive capitalized tokens merge into one entry, three at most.
 *    Scanning resumes at the run's end, not at the name's, so a longer run
 *    cannot shed its tail as a bogus standalone entry.
 *  - Generic titles ("Elder", "Master") live inside a phrase but never
 *    stand alone: "Young Master" is a form of address, "Young Master Wei"
 *    a name.
 *  - A quotation mark or a dash starts a sentence, so the first word of a
 *    line of dialogue is weak evidence in every language: German and
 *    Italian capitalize there, and Russian dialogue opens with a dash.
 *  - Trailing possessives merge ("Xiulan's" is "Xiulan"), while internal
 *    ones stay ("King's Landing"); contractions, ALL-CAPS
 *    shouting, stopwords, spelled-out numbers and scanlation furniture
 *    never count.
 *  - French and Italian elisions are stripped from the head, so
 *    "d'Artagnan" and sentence-initial "D'Artagnan" stay one entry as
 *    "Artagnan". Internal elisions stay in "Gabriele D'Annunzio".
 *    "O'Brien" keeps its apostrophe: "O" is no elision.
 *
 * Places and factions ("Azure Cloud Sect", "Blackwood Manor") pass on
 * purpose: a codex covers who and what. Speech-verb adjacency is gathered
 * here, while the surrounding words are in hand, because it is the one
 * signal that separates people from places; classification itself stays a
 * pure read-time function ([classify]), so sharpening the word lists costs
 * no rescan.
 *
 * German noun capitalization makes every noun a strong candidate; the
 * codex's read-time occurrence floor is what keeps that flood down, and
 * frequent German nouns still surface. Scripts without case (Chinese,
 * Japanese, Thai…) carry no signal here and yield nothing: a documented
 * limit of the heuristic, not a failure to parse.
 */
object CodexExtractor {

    /** The longest run of tokens that merges into one entry. */
    const val MAX_NAME_TOKENS = 3

    /** Extracts candidate names from one unit's text. */
    fun extract(text: String): Map<String, CodexStats> {
        val strong = HashMap<String, Int>()
        val weak = HashMap<String, Int>()
        val speech = HashMap<String, Int>()

        for (sentence in SENTENCES.split(text)) {
            val matches = WORDS.findAll(sentence).toList()
            val tokens = matches.map { it.value }
            val candidates = tokens.map(::candidate)
            var i = 0
            var first = true
            while (i < tokens.size) {
                if (candidates[i] == null) {
                    i++
                    first = false
                    continue
                }
                // Measure the whole capitalized run before taking a name
                // from it, so a run longer than three tokens leaves no
                // standalone tail behind. Only whitespace joins separate
                // tokens into a name; punctuation ends the run.
                var runEnd = i
                while (runEnd < tokens.size && candidates[runEnd] != null) {
                    if (runEnd > i && nameBoundaryBetween(matches[runEnd - 1], matches[runEnd], sentence)) break
                    runEnd++
                }
                val take = minOf(MAX_NAME_TOKENS, runEnd - i)
                val parts = (i until i + take).map { at ->
                    val part = candidates[at]!!.let { if (at == i) it.replace(ELISION, "") else it }
                    if (at == i + take - 1) part.replace(POSSESSIVE, "") else part
                }
                val name = parts.joinToString(" ")

                if (!genericPhrase(parts)) {
                    if (first && take == 1) {
                        weak[name] = (weak[name] ?: 0) + 1
                    } else {
                        strong[name] = (strong[name] ?: 0) + 1
                    }
                    // Either side of the run: both orders occur constantly,
                    // "Xiulan said" and "said Xiulan".
                    val before = tokens.getOrNull(i - 1)?.lowercase()
                    val after = tokens.getOrNull(runEnd)?.lowercase()
                    if (before in SPEECH_VERBS || after in SPEECH_VERBS) {
                        speech[name] = (speech[name] ?: 0) + 1
                    }
                }
                i = runEnd
                first = false
            }
        }

        // Weak hits only count for names that earned strong evidence in
        // this same unit.
        val counts = HashMap(strong)
        for ((name, count) in weak) {
            if (name in strong) counts[name] = counts.getValue(name) + count
        }
        return counts.mapValues { (name, count) -> CodexStats(count, speech[name] ?: 0) }
    }

    /**
     * What kind of thing [name] is, from the name plus accumulated speech
     * evidence. Pure, so it runs at read time and improved word lists take
     * effect without a rescan. The head noun wins over a title word:
     * "Zhao Family" is an organisation even though "family" is also a form
     * of address. The head lists are English so far; names in the other
     * languages classify as PERSON only through speech or titles, and
     * otherwise UNKNOWN, honestly.
     */
    fun classify(name: String, speechHits: Int): CodexKind {
        val parts = name.split(' ')
        val head = parts.last().lowercase()
        if (head in FACTION_HEADS) return CodexKind.FACTION
        if (head in PLACE_HEADS) return CodexKind.PLACE
        if (parts.any { it.lowercase() in TITLE_WORDS }) return CodexKind.PERSON
        if (speechHits >= 2) return CodexKind.PERSON
        return CodexKind.UNKNOWN
    }

    /**
     * A selected [name] in the form a scan stores it, so a long press on
     * "Xiulan’s" or "d’Artagnan" opens "Xiulan" or "Artagnan" while
     * "King's Landing" and "O'Brien" keep their apostrophes.
     */
    fun storedName(name: String): String {
        val parts = Normalizer.normalize(name, Normalizer.Form.NFC).replace('’', '\'').split(' ').toMutableList()
        parts[0] = parts[0].replace(ELISION, "")
        parts[parts.lastIndex] = parts.last().replace(POSSESSIVE, "")
        return parts.joinToString(" ").trim().takeIf { PassageText.fold(it).isNotEmpty() } ?: name
    }

    /**
     * The form of [token] stored in the codex, or null when it cannot be
     * part of a name: a contraction, too short, not capitalized, ALL-CAPS
     * shouting, or a stopword.
     */
    private fun candidate(token: String): String? {
        val normalized = Normalizer.normalize(token, Normalizer.Form.NFC).replace('’', '\'')
        // Contractions are verb forms wearing a capital letter: "I'm",
        // "Don't", "You'll" survive every other test below.
        if (CONTRACTION.containsMatchIn(normalized)) return null
        val t = normalized.trimEnd('-')
        val bare = t.replace(ELISION, "").replace(POSSESSIVE, "")
        if (bare.length <= 1) return null
        if (!bare[0].isUpperCase()) return null
        if (bare == bare.uppercase()) return null
        if (bare.lowercase() in STOPWORDS) return null
        return t
    }

    /**
     * Whether every part of a name is a generic word, and the phrase holds
     * no proper noun: a form of address ("Sect Master", "Old Man"), not a
     * name. A single generic token ("Master") is one too. All-generic
     * compounds without a title ("Spirit Realm") are left through.
     */
    private fun genericPhrase(parts: List<String>): Boolean {
        val lower = parts.map { it.lowercase() }
        return lower.all { it in GENERIC_STANDALONE } &&
            (parts.size == 1 || lower.any { it in TITLE_WORDS })
    }

    /** Whether a non-whitespace separator ends a name run. */
    private fun nameBoundaryBetween(before: MatchResult, after: MatchResult, sentence: String): Boolean =
        sentence.substring(before.range.last + 1, after.range.first).any { !it.isWhitespace() }

    /**
     * Sentence ends, and the starts a quotation mark or a dash makes: the
     * first word of a line of dialogue is weak evidence in every language,
     * as German and Italian capitalize there and Russian dialogue opens
     * with a dash. `;` is the Greek question mark; splitting on it costs
     * nothing elsewhere, since a lone sentence-start token is only weak
     * evidence anyway. The split may match no whitespace at all, right
     * after a quote. Newlines also separate paragraphs, so names and
     * speech verbs cannot merge across their boundary.
     */
    private val SENTENCES = Regex("(?<=[.!?…;»«„\\u0022“”\\u2014\\u2013‘‚]|(?<![\\p{L}\\p{M}])'|(?<=[.!?…;])’)\\s*|[\\r\\n]+")

    /** Unicode letters, with combining marks for decomposed accents, apostrophes and hyphens. */
    private val WORDS = Regex("\\p{L}[\\p{L}\\p{M}'’\\-]*")

    /** A trailing possessive: `Xiulan's`, `the Zhaos'`. */
    private val POSSESSIVE = Regex("['’]s$|['’]$", RegexOption.IGNORE_CASE)

    /** Contraction tails, deliberately without `'s`, which is the possessive above. */
    private val CONTRACTION = Regex("n['’]t$|['’](m|ll|d|ve|re)$", RegexOption.IGNORE_CASE)

    /**
     * A leading elision to strip, French and Italian, so the elided and
     * the sentence-initial form of a name stay one entry: d', l', qu',
     * dell'…. "O" is not among them, so "O'Brien" keeps its apostrophe.
     */
    private val ELISION = Regex(
        "^(?:dell|nell|sull|quest|quell|lorsqu|puisqu|jusqu|all|qu|un|d|l|s|n|c|j|m|t)['’]",
        RegexOption.IGNORE_CASE,
    )

    private val ENGLISH_WORDS = words(
        """
        the a an and or but if then else when while for nor so yet as of in on at by to from with without
        into onto over under above below before after during between among through across behind beyond near
        he she it they we you i him her them us me his hers its their our your my mine yours theirs ours
        this that these those there here who whom whose which what why how where
        is are was were be been being am do does did done have has had having will would shall should can could may might must
        not no yes all any both each few more most other some such only own same than too very just even still also again once
        now later soon finally eventually
        oh ah hey hmm huh wow damn hello okay ok everyone everything something nothing anyone someone nobody somebody
        suddenly however meanwhile perhaps although though because since until unless
        """,
    )

    /** French, without the articles and particles (de, la, du…): they sit inside proper names ("De La Cruz", "La Rochelle") more often than they carry a capital alone, and a sentence-start article is only weak evidence anyway. */
    private val FRENCH_WORDS = words(
        """
        un une et ou mais donc car ni que qui quoi dont où je tu il elle on nous vous
        ils elles me te se lui leur mon ton son ma ta sa mes tes ses notre votre nos vos ce cet cette ces y en        ai as est suis sommes êtes sont était étais étaient être avoir avons avez ont avait avaient
        fait faire plus très bien tout toute tous toutes même aussi comme dans par pour sur sous avec sans
        chez vers dès depuis pendant après avant quand si ne pas aucun rien jamais toujours autre alors
        ainsi déjà encore enfin pourtant selon puis
        """,
    )

    private val GERMAN_WORDS = words(
        """
        der die das ein eine einen einem einer eines den dem des und oder aber wenn dann denn doch ja nein
        nicht kein keine ist sind war waren sei sein haben hat habe hatte hatten wird werden wurde wurden
        im in an auf mit von zu zum zur bei nach vor über unter durch für ohne gegen um aus seit während
        bis obwohl weil damit sodass als wie wo wer was warum wem wen wessen
        ich du er sie es wir ihr mich dich ihn ihm uns euch mir dir mein dein sein unser eure
        dieser dieses diese jener nun jetzt dann schon noch nur auch sehr so hier dort da plötzlich jedoch
        vielleicht trotzdem außerdem
        """,
    )

    /** Spanish, without its articles, for the same reason as French ("El Paso", "La Mancha"). */
    private val SPANISH_WORDS = words(
        """
        un una unos unas y o pero si entonces cuando mientras para por sin con sobre entre
        hacia desde hasta durante después antes porque aunque como que quien quienes cuyo donde cómo qué
        yo tú él ella nosotros ustedes ellos ellas me te lo le les mi tu su nuestro vuestro mis tus sus        estos estas ese esa aquel aquella esto eso aquello hay es son era eran ser estar está están fue
        muy más menos también sí no nunca siempre todo toda todos todas otro otra mismo tan así aquí allí
        ahora luego pues según
        """,
    )

    /** Italian, without its articles and the particles di and da ("La Scala", "Da Vinci"). */
    private val ITALIAN_WORDS = words(
        """
        un uno una a in con su per tra fra e o ma se allora quando mentre perché
        che chi cui dove come cosa quanto io tu lui lei noi voi loro mi ti ci vi mio tuo suo nostro vostro
        miei tuoi suoi nostri vostri questo questa questi queste quello quella quelli quelle è sono era        erano essere avere ha hanno aveva avevano molto più meno anche però sì no mai sempre tutto tutta
        tutti tutte altro altra stesso così qui là ora poi dunque secondo improvvisamente
        """,
    )

    private val RUSSIAN_WORDS = words(
        """
        и в во не что он на я с со как а то все она так его но да ты к у же вы за бы по только её мне
        было вот от меня ещё нет о из ему теперь когда даже ну вдруг ли если уже или ни быть был была
        были было есть этот это эта эти тот та те мы они их кто какой какая где куда зачем почему
        потом тогда тоже также там сейчас
        """,
    )

    private val GREEK_WORDS = words(
        """
        και ή αλλά αν τότε όταν ενώ για να με χωρίς από τον στην στο στους στα των του της τα ο οι η
        μια είναι ήταν θα έχει έχουν υπάρχει επίσης πολύ περισσότερο λιγότερο μόνο ναι όχι ποτέ πάντα
        όλα ακόμα ίδιος έτσι εδώ εκεί τώρα μετά αφού επειδή ώστε μήπως όμως ξαφνικά δηλαδή
        """,
    )

    /** Scanlation and site furniture, in the codex's languages: capitalized, recurring, uninteresting. */
    private val BOILERPLATE = words(
        """
        author translator translation translated editor proofreader proofread raw raws
        note notes chapter chapters volume part prologue epilogue foreword afterword arc
        patreon discord kofi paypal sponsor sponsored support donate advance advanced
        index contents next previous prev continue release released schedule
        comment comments review reviews rating ratings bonus extra teaser
        chapitre traduction auteur kapitel übersetzung übersetzer capítulo traducción
        capitolo traduzione autore глава автор перевод примечание
        κεφάλαιο μετάφραση συγγραφέας
        """,
    )

    /** Spelled-out numbers, English; other languages' numbers are rarely capitalized mid-sentence. */
    private val NUMBER_WORDS = words(
        """
        one two three four five six seven eight nine ten eleven twelve thirteen fourteen
        fifteen sixteen seventeen eighteen nineteen twenty thirty forty fifty sixty
        seventy eighty ninety hundred thousand million
        first second third fourth fifth sixth seventh eighth ninth tenth last final
        """,
    )

    /** Function words of the languages a cased-script book is most often in; a capital elsewhere than a sentence start is rare for them. Composed after every list it unions: object properties initialize in order. */
    private val STOPWORDS: Set<String> =
        ENGLISH_WORDS + FRENCH_WORDS + GERMAN_WORDS + SPANISH_WORDS + ITALIAN_WORDS + RUSSIAN_WORDS + GREEK_WORDS +
            BOILERPLATE + NUMBER_WORDS

    /** Speech and gesture verbs: adjacency to one is the cheapest signal that a name belongs to someone who can act. */
    private val SPEECH_VERBS = words(
        """
        said says say asked asks ask replied replies reply answered answers answer
        shouted shouts yelled cried called calls muttered murmured whispered
        laughed smiled smiles grinned frowned scowled sighed nodded shook
        thought thinks wondered realized snorted chuckled snapped growled roared
        exclaimed continued added interrupted responded retorted stammered
        dit disait demanda demandait répondit répondait s'écria cria hurla murmura
        chuchota sourit souriait hocha soupira songea pensa remarqua interrompit
        ajouta poursuivit continua rétorqua
        sagte sagt fragte fragte antwortete rief schrie flüsterte murmelte brummte
        lächelte seufzte nickte dachte bemerkte unterbrach fügte erwiderte entgegnete
        dijo dice decía preguntó preguntaba respondió gritó chilló murmuró susurró
        sonrió sonreía suspiró asintió pensó notó interrumpió añadió continuó replicó
        disse diceva chiese chiedeva rispose rispondeva gridò urlò mormorò sussurrò
        sorrise sorrideva sospirò annuì pensò notò interruppe aggiunse continuò replicò
        сказал сказала говорит сказали спросил спросила спросили ответил ответила
        ответили крикнул крикнула закричал прошептал прошептала пробормотал улыбнулся
        улыбнулась вздохнул кивнул подумал заметил перебил добавил продолжил возразил
        είπε λέει έλεγε ρώτησε ρωτούσε απάντησε φώναξε φώναζε ψιθύρισε χαμογέλασε
        αναστέναξε έγνεψε σκέφτηκε διέκοψε πρόσθεσε συνέχισε
        """,
    )

    /** Forms of address and relational roles: they never name anyone, alone or stacked. */
    private val TITLE_WORDS = words(
        """
        elder senior junior brother sister uncle aunt master young old man woman
        sir madam miss mister lord lady
        boss chief captain general commander doctor teacher father mother grandpa grandma kid boy girl
        king queen prince princess duke duchess count countess baron baroness saint
        """,
    )

    /** Domain vocabulary that combines into real proper nouns ("Spirit Realm") but stands for nothing alone. */
    private val COMMON_NOUNS = words(
        """
        sect city clan family mountain heaven earth god demon sword blade art dao qi
        spirit soul realm stage peak
        """,
    )

    private val GENERIC_STANDALONE: Set<String> = TITLE_WORDS + COMMON_NOUNS

    /** Head nouns that make a phrase an organisation. */
    private val FACTION_HEADS = words(
        """
        sect clan family guild alliance association order tribe league union
        empire kingdom dynasty court house school academy institute society
        legion army corps division squad team faction pavilion department
        """,
    )

    /** Head nouns that make a phrase somewhere you can stand. */
    private val PLACE_HEADS = words(
        """
        city town village capital province region realm continent world land domain
        mountain mountains peak valley forest woods lake river sea ocean island
        desert plains plain cave cavern gate gates bridge road street market square
        palace hall tower temple shrine monastery manor estate garden courtyard
        prison tomb ruins battlefield arena plaza inn tavern
        """,
    )

    private fun words(block: String): Set<String> =
        block.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
}
