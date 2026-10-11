package com.chmouel.liseur.reader.dictionary

import com.chmouel.liseur.translate.TranslationLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiktionary translation tables, read by language tag. The fixtures are
 * trimmed from real en.wiktionary ("water", "bank", "house" and its
 * subpage), fr.wiktionary ("maison") and de.wiktionary ("Haus") pages.
 */
class WiktionaryTranslationsTest {

    @Test
    fun `english tables give the target words under their gloss and part of speech`() {
        val translations = parseWiktionaryTranslations(WATER, "en", "fr")

        assertEquals(
            listOf(
                TranslationSection("Noun", listOf(TranslationGroup("clear liquid H₂O", listOf("eau")))),
                TranslationSection(
                    "Verb",
                    listOf(TranslationGroup("to pour water into the soil surrounding (plants)", listOf("arroser"))),
                ),
            ),
            translations.sections.filter { it.groups.isNotEmpty() },
        )
        assertNull(translations.subpage)
    }

    @Test
    fun `transliterations, sub-varieties and other languages are left out`() {
        assertEquals(listOf("eau"), parseWiktionaryTranslations(WATER, "en", "fr").terms("Noun"))
        assertEquals(listOf("水"), parseWiktionaryTranslations(WATER, "en", "zh-Hans").terms("Noun"))
    }

    @Test
    fun `chinese keeps the script asked for`() {
        assertEquals(listOf("书"), parseWiktionaryTranslations(BOOK, "en", "zh-Hans").terms("Noun"))
        assertEquals(listOf("書"), parseWiktionaryTranslations(BOOK, "en", "zh-Hant").terms("Noun"))
    }

    @Test
    fun `serbo-croatian keeps the script asked for`() {
        assertEquals(listOf("kuća"), parseWiktionaryTranslations(HOUSE_SH, "en", "sr-Latn").terms("Noun"))
        assertEquals(listOf("кућа"), parseWiktionaryTranslations(HOUSE_SH, "en", "sr-Cyrl").terms("Noun"))
        assertEquals(listOf("кућа", "kuća"), parseWiktionaryTranslations(HOUSE_SH, "en", "sr").terms("Noun"))
        assertEquals(listOf("kuća"), parseWiktionaryTranslations(HOUSE_SH, "en", "hr").terms("Noun"))
    }

    @Test
    fun `a moved table points at its subpage, whose tables take its place`() {
        val page = parseWiktionaryTranslations(HOUSE, "en", "fr")
        assertEquals(listOf(TranslationSection("Noun", emptyList())), page.sections)
        assertEquals("house/translations", page.subpage)

        val merged = page.plus(parseWiktionaryTranslations(HOUSE_TRANSLATIONS, "en", "fr"))
        assertEquals(
            listOf(TranslationSection("Noun", listOf(TranslationGroup("human abode", listOf("maison"))))),
            merged.sections,
        )
    }

    @Test
    fun `moved tables of repeated parts of speech keep page order`() {
        fun section(part: String, word: String = "") =
            TranslationSection(part, if (word.isEmpty()) emptyList() else listOf(TranslationGroup("", listOf(word))))
        val entry = EntryTranslations(listOf(section("Noun"), section("Verb"), section("Noun")), "bank/translations")
        val moved = EntryTranslations(
            listOf(section("Noun", "banque"), section("Verb", "déposer"), section("Noun", "rive"), section("Noun", "berge")),
        )
        assertEquals(
            listOf(section("Noun", "banque"), section("Verb", "déposer"), section("Noun", "rive"), section("Noun", "berge")),
            entry.plus(moved).sections,
        )
    }

    @Test
    fun `each etymology's tables stay apart, in page order`() {
        assertEquals(
            listOf(
                TranslationSection("Noun", listOf(TranslationGroup("institution", listOf("banque")))),
                TranslationSection("Verb", listOf(TranslationGroup("to put into a bank", listOf("déposer")))),
                TranslationSection("Noun", listOf(TranslationGroup("edge of river or lake", listOf("rive", "berge")))),
            ),
            parseWiktionaryTranslations(BANK, "en", "fr").sections,
        )
    }

    @Test
    fun `french boxes give the target words under their heading`() {
        assertEquals(
            listOf(
                TranslationSection(
                    "Nom commun",
                    listOf(
                        TranslationGroup("Bâtiment d’habitation", listOf("house", "home")),
                        TranslationGroup("Foyer", listOf("home")),
                    ),
                ),
            ),
            parseWiktionaryTranslations(MAISON, "fr", "en").sections,
        )
    }

    @Test
    fun `german tables give the target words under their gloss`() {
        assertEquals(
            listOf(
                TranslationSection(
                    "Substantiv, n",
                    listOf(
                        TranslationGroup("zum Wohnen dienendes und genutztes Gebäude", listOf("maison")),
                        TranslationGroup("umgangssprachlich, scherzhaft: Mensch, Person", listOf("type", "gars")),
                    ),
                ),
            ),
            parseWiktionaryTranslations(HAUS, "de", "fr").sections,
        )
    }

    @Test
    fun `only the edition's own language section has tables`() {
        assertTrue(parseWiktionaryTranslations(WATER, "fr", "en").sections.isEmpty())
        assertTrue(parseWiktionaryTranslations(WATER, "en", "not a language").sections.isEmpty())
    }

    @Test
    fun `an edition's own language is found in its headings`() {
        assertEquals("de", headingLanguage("Haus (Deutsch)", "de"))
        assertEquals("pl", headingLanguage("dom (język polski)", "pl"))
        assertEquals("it", headingLanguage("Italiano", "it"))
        assertEquals("en", headingLanguage("English", null))
        assertNull(headingLanguage("Haus (Mittelhochdeutsch)", "de"))
        assertNull(headingLanguage("Middle English", "en"))
        assertNull(headingLanguage("Haus (Deutsch)", null))
        assertEquals("de", parseWiktionaryEntryHtml(HAUS, listOf("de"), "de").first().language)
    }

    @Test
    fun `translations are offered for words in the edition's language into another one`() {
        val english = listOf(DictionarySense("Noun", listOf("A clear liquid."), "en"))
        val french = listOf(DictionarySense("Nom", listOf("Eau."), "fr"))

        assertEquals("fr", translationTarget(english, "en", "fr"))
        assertEquals("zh-Hant", translationTarget(english, "en", "zh_TW"))
        assertNull(translationTarget(english, "en", null))
        assertNull(translationTarget(english, "en", "en-GB"))
        assertNull(translationTarget(french, "en", "de"))
        assertNull(translationTarget(english, "en", "not a language"))
    }

    @Test
    fun `a chosen alternative language always applies, automatic only for a book in another language`() {
        assertNull(altLanguageFor(ALT_LANGUAGE_OFF, "en", "fr"))

        assertEquals("de", altLanguageFor("de", "en", "fr"))
        assertEquals("de", altLanguageFor("de", null, "fr"))
        assertEquals("fr", altLanguageFor("fr", "fr", "fr"))

        assertEquals("fr", altLanguageFor(null, "en", "fr-FR"))
        assertEquals("zh-Hans", altLanguageFor(null, "en", "zh-CN"))
        assertNull(altLanguageFor(null, "en-GB", "en-US"))
        assertNull(altLanguageFor(null, null, "fr"))
        assertNull(altLanguageFor(null, "und", "fr"))
        assertNull(altLanguageFor(null, "en", "not a language"))
    }

    @Test
    fun `the off setting can never be read as a language`() {
        assertNull(TranslationLanguages.of(ALT_LANGUAGE_OFF))
    }

    @Test
    fun `a translation is defined on the Wiktionary of its own language, never through a mirror`() {
        assertEquals("https://fr.wiktionary.org", translationSite("https://en.wiktionary.org", "fr"))
        assertEquals("https://zh.wiktionary.org", translationSite("https://en.wiktionary.org", "zh-Hant"))
        assertNull(translationSite("https://wiktionary.example.org", "fr"))
        assertNull(translationSite("https://en.wiktionary.org", "eo"))
        assertEquals("https://no.wiktionary.org", translationSite("https://en.wiktionary.org", "nb"))
    }

    @Test
    fun `the button names the first translation of the first sense that has one`() {
        val state = TranslationsState.Found(
            listOf(
                TranslationSection("Noun", listOf(TranslationGroup("clear liquid", listOf("eau", "aigue")))),
                TranslationSection("Verb", listOf(TranslationGroup("to pour water", listOf("arroser")))),
            ),
        )

        assertEquals("eau", state.first)
    }

    private fun EntryTranslations.terms(part: String): List<String> =
        sections.single { it.partOfSpeech == part }.groups.single().terms

    companion object {
        val WATER = """
            <html><body>
            <section><h2 id="English">English</h2>
            <section><h3 id="Etymology_1">Etymology 1</h3>
            <section><h4 id="Noun">Noun</h4>
            <ol><li>A clear liquid.</li></ol>
            <section><h5 id="Translations">Translations</h5>
            <div class="translations" role="presentation" data-gloss="clear liquid H₂O"><ul>
            <li>Chinese:<dl><dd>Mandarin: <span class="Hant" lang="cmn"><a href="./水#Chinese">水</a></span>, <span class="Hans" lang="cmn">水</span> <span class="tr Latn" lang="cmn-Latn">shuǐ</span></dd></dl></li>
            <li>French: <span class="Latn" lang="fr"><a href="./eau#French" rel="mw:WikiLink" title="eau">eau</a></span><span class="tpos"> <a class="extiw" href="https://fr.wiktionary.org/wiki/eau">(fr)</a></span>
            <dl><dd>Old French: <span class="Latn" lang="fro"><a href="./eve#Old_French">eve</a></span></dd></dl></li>
            <li>Russian: <span class="Cyrl" lang="ru">вода́</span> <span class="tr Latn" lang="ru-Latn">vodá</span></li>
            </ul></div>
            </section></section>
            <section><h4 id="Verb">Verb</h4>
            <ol><li>To pour water.</li></ol>
            <section><h5 id="Translations_2">Translations</h5>
            <div class="translations" data-gloss="to pour water into the soil surrounding (plants)"><ul>
            <li>French: <span class="Latn" lang="fr"><a href="./arroser#French">arroser</a></span></li></ul></div>
            <div class="translations" data-gloss="to provide drink for animals"><ul>
            <li>German: <span class="Latn" lang="de">tränken</span></li></ul></div>
            </section></section>
            </section></section>
            <section><h2 id="French">French</h2>
            <section><h3>Noun</h3><ol><li>water</li></ol></section></section>
            </body></html>
        """.trimIndent()

        val BOOK = """
            <section><h2 id="English">English</h2>
            <section><h3 id="Noun">Noun</h3><ol><li>A collection of pages.</li></ol>
            <section><h4 id="Translations">Translations</h4>
            <div class="translations" data-gloss="collection of sheets of paper"><ul>
            <li>Chinese:<dl><dd>Mandarin: <span class="Hant" lang="cmn">書</span>／<span class="Hans" lang="cmn">书</span> <span class="tr Latn" lang="cmn-Latn">shū</span></dd></dl></li>
            </ul></div></section></section></section>
        """.trimIndent()

        val HOUSE = """
            <section><h2 id="English">English</h2>
            <section><h3 id="Noun">Noun</h3><ol><li>A structure serving as an abode.</li></ol>
            <section><h5 id="Translations">Translations</h5>
            <div class="pseudo NavFrame"><div class="NavHead" style="text-align: left;">See <a href="./house/translations#Noun" rel="mw:WikiLink" title="house/translations">house/translations § Noun</a>.</div></div>
            </section></section></section>
        """.trimIndent()

        val HOUSE_TRANSLATIONS = """
            <section><h2 id="English">English</h2>
            <section><h3 id="Noun">Noun</h3>
            <section><h4 id="Translations">Translations</h4>
            <table class="translations" data-gloss="human abode" role="presentation"><tbody><tr><td><ul>
            <li>French: <span class="Latn" lang="fr"><a href="./maison#French">maison</a></span></li>
            </ul></td></tr></tbody></table>
            </section></section></section>
        """.trimIndent()

        val MAISON = """
            <section><h2 id="Français"><span class="sectionlangue" id="fr">Français</span></h2>
            <section><h3 id="Nom_commun_1"><span class="titredef">Nom commun 1</span></h3>
            <ol><li>Bâtiment servant de logis.</li></ol>
            <section><h4 id="Traductions"><span class="titretrad">Traductions</span></h4>
            <div class="boite"><div class="NavFrame"><div class="NavHead"><span class="nav-head-title"><b>Bâtiment d’habitation</b></span> <span class="nav-head-number">(1)</span></div>
            <div class="NavContent"><ul>
            <li><span class="trad-de" data-translation-lang="de">Allemand</span> : <span class="translation"><bdi class="lang-de" lang="de"><a href="./Haus#de">Haus</a></bdi></span></li>
            <li><span class="trad-en" data-translation-lang="en">Anglais</span> : <span class="translation"><bdi class="lang-en" lang="en"><a href="./house#en">house</a></bdi> <span class="trad-exposant"><a class="extiw" href="https://en.wiktionary.org/wiki/house"><span class="trad-existe">(en)</span></a></span></span>, <span class="translation"><bdi class="lang-en" lang="en"><a href="./home#en">home</a></bdi></span></li>
            </ul></div></div></div>
            <div class="boite"><div class="NavFrame"><div class="NavHead"><span class="nav-head-title"><b>Foyer</b></span> <span class="nav-head-number">(2)</span></div>
            <div class="NavContent"><ul>
            <li><span class="trad-en" data-translation-lang="en">Anglais</span> : <span class="translation"><bdi class="lang-en" lang="en"><a href="./home#en">home</a></bdi></span></li>
            </ul></div></div></div>
            </section></section></section>
        """.trimIndent()
        val BANK = """
            <section><h2 id="English">English</h2>
            <section><h3 id="Etymology_1">Etymology 1</h3>
            <section><h4 id="Noun">Noun</h4><ol><li>An institution.</li></ol>
            <section><h5 id="Translations">Translations</h5>
            <div class="translations" data-gloss="institution"><ul>
            <li>French: <span class="Latn" lang="fr">banque</span></li></ul></div>
            </section></section>
            <section><h4 id="Verb">Verb</h4><ol><li>To deposit.</li></ol>
            <section><h5 id="Translations_2">Translations</h5>
            <div class="translations" data-gloss="to put into a bank"><ul>
            <li>French: <span class="Latn" lang="fr">déposer</span></li></ul></div>
            </section></section></section>
            <section><h3 id="Etymology_2">Etymology 2</h3>
            <section><h4 id="Noun_2">Noun</h4><ol><li>An edge of a river.</li></ol>
            <section><h5 id="Translations_3">Translations</h5>
            <div class="translations" data-gloss="edge of river or lake"><ul>
            <li>French: <span class="Latn" lang="fr">rive</span>, <span class="Latn" lang="fr">berge</span></li></ul></div>
            </section></section></section></section>
        """.trimIndent()

        val HOUSE_SH = """
            <section><h2 id="English">English</h2>
            <section><h3 id="Noun">Noun</h3><ol><li>An abode.</li></ol>
            <section><h4 id="Translations">Translations</h4>
            <div class="translations" data-gloss="human abode"><ul>
            <li>Serbo-Croatian:<dl>
            <dd>Cyrillic: <span class="Cyrl" lang="sh"><a href="./кућа#Serbo-Croatian">кућа</a></span></dd>
            <dd>Latin: <span class="Latn" lang="sh"><a href="./kuća#Serbo-Croatian">kuća</a></span></dd>
            </dl></li></ul></div>
            </section></section></section>
        """.trimIndent()

        val HAUS = """
            <html lang="de"><body>
            <section><h2 id="Haus_(Deutsch)">Haus (<a href="./Deutsch">Deutsch</a>)</h2>
            <section><h3 id="Substantiv,_n">Substantiv, <i>n</i></h3>
            <p><b>Bedeutungen:</b></p>
            <dl><dd>[1] zum Wohnen dienendes Gebäude</dd><dd>[2] umgangssprachlich: Mensch</dd></dl>
            <section><h4 id="Übersetzungen">Übersetzungen</h4>
            <div class="printNav"><div class="NavFrame uetabelle"><div class="NavHead uetabelle__head"> [<span class="bedeutung">1</span>] <span class="glosse">zum Wohnen dienendes und genutztes Gebäude</span></div>
            <div class="NavContent uetabelle__content"><table class="uetabelle__table"><tbody><tr><td class="uetabelle__cell"><ul>
            <li><a href="./Englisch">Englisch</a>: <span lang="en"><a href="./house">house</a></span></li>
            <li><a href="./Französisch">Französisch</a>: <span lang="fr"><a href="./maison#maison_(Französisch)">maison</a></span> <em>f</em></li>
            </ul></td></tr></tbody></table></div></div></div>
            <div class="printNav"><div class="NavFrame uetabelle"><div class="NavHead uetabelle__head"> [<span class="bedeutung">10</span>] <span class="glosse">umgangssprachlich, scherzhaft: Mensch, Person</span></div>
            <div class="NavContent uetabelle__content"><table class="uetabelle__table"><tbody><tr><td class="uetabelle__cell"><ul>
            <li><a href="./Französisch">Französisch</a>: <span lang="fr"><a href="./type">type</a></span>, <span lang="fr"><a href="./gars">gars</a></span></li>
            </ul></td></tr></tbody></table></div></div></div>
            </section></section></section>
            </body></html>
        """.trimIndent()
    }
}
