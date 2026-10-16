package com.chmouel.liseur.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexExtractorTest {

    private fun names(text: String): Set<String> = CodexExtractor.extract(text).keys

    @Test
    fun `a name mid-sentence is strong evidence`() {
        assertEquals(mapOf("Mira" to CodexStats(1, 0)), CodexExtractor.extract("At dawn, Mira waited by the door."))
    }

    @Test
    fun `a sentence-start name counts only with strong evidence in the same unit`() {
        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("Suddenly it rained all day."))
        assertEquals(mapOf("Mira" to CodexStats(2, 1)), CodexExtractor.extract("Mira waited. At dawn, Mira smiled."))
    }

    @Test
    fun `consecutive capitalized tokens merge into one entry`() {
        assertEquals(mapOf("Elder Chen" to CodexStats(1, 0)), CodexExtractor.extract("At the gate, Elder Chen greeted them."))
    }

    @Test
    fun `punctuation separates adjacent names`() {
        for (text in listOf(
            "He greeted Mira (Tobin was away).",
            "He greeted Mira: Tobin was away.",
            "He greeted Mira, Tobin was away.",
            "He greeted Mira [Tobin was away].",
        )) {
            assertEquals(mapOf("Mira" to CodexStats(1, 0), "Tobin" to CodexStats(1, 0)), CodexExtractor.extract(text))
        }
    }

    @Test
    fun `paragraph boundaries break name runs and speech adjacency`() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            assertEquals(
                mapOf("Mira" to CodexStats(1, 0), "Tobin" to CodexStats(2, 0)),
                CodexExtractor.extract("At dawn, Mira${newline}Tobin waited. At noon, Tobin${newline}said nothing."),
            )
        }
    }

    @Test
    fun `a long run keeps three tokens and sheds no tail`() {
        assertEquals(setOf("President Ricardo Alonso"), names("President Ricardo Alonso De La Cruz spoke at noon."))
    }

    @Test
    fun `a generic title alone is a form of address, not a name`() {
        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("The Young Master spoke."))
    }

    @Test
    fun `a generic title in a phrase makes a name`() {
        assertEquals(mapOf("Young Master Wei" to CodexStats(1, 0)), CodexExtractor.extract("Young Master Wei bowed at the gate."))
    }

    @Test
    fun `possessives merge into the bare name`() {
        assertEquals(mapOf("Xiulan" to CodexStats(2, 0)), CodexExtractor.extract("At dawn, Xiulan's ledger lay open. Xiulan waited."))
    }

    @Test
    fun `a selected name opens the form the scan stores`() {
        assertEquals("Xiulan", CodexExtractor.storedName("Xiulan’s"))
        assertEquals("Artagnan", CodexExtractor.storedName("d’Artagnan"))
        assertEquals("King's Landing", CodexExtractor.storedName("King’s Landing’s"))
        assertEquals("O'Brien", CodexExtractor.storedName("O’Brien"))
        assertEquals("Gabriele D'Annunzio", CodexExtractor.storedName("Gabriele D'Annunzio"))
    }

    @Test
    fun `compound names preserve internal possessives and strip trailing ones`() {
        for (apostrophe in listOf("'", "’")) {
            val name = "King${apostrophe}s Landing"
            assertEquals(
                mapOf("King's Landing" to CodexStats(2, 0)),
                CodexExtractor.extract("They reached $name. They left $name${apostrophe}s walls."),
            )
        }
    }

    @Test
    fun `contractions never count`() {
        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("Don't shout, I'm told. You'll see."))
    }

    @Test
    fun `all-caps shouting never counts`() {
        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("At dawn, MIRA shouted to LOOK OUT."))
    }

    @Test
    fun `french elisions stay one entry, whatever their case`() {
        assertEquals(
            mapOf("Artagnan" to CodexStats(3, 1)),
            CodexExtractor.extract("Il parla à d'Artagnan, puis d'Artagnan sourit. D'Artagnan partit."),
        )
    }

    @Test
    fun `compound names preserve internal elisions`() {
        for (apostrophe in listOf("'", "’")) {
            val name = "Gabriele D${apostrophe}Annunzio"
            assertEquals(
                mapOf("Gabriele D'Annunzio" to CodexStats(2, 0)),
                CodexExtractor.extract("He greeted $name. He found $name${apostrophe}s books."),
            )
        }
    }

    @Test
    fun `equivalent unicode and apostrophe spellings merge into one entry`() {
        assertEquals(
            mapOf("Élisabeth" to CodexStats(2, 0)),
            CodexExtractor.extract("Il vit Élisabeth partir, puis E\u0301lisabeth revint."),
        )
        assertEquals(
            mapOf("O'Brien" to CodexStats(2, 0)),
            CodexExtractor.extract("At the pub, O'Brien sang. Later, O’Brien left."),
        )
    }

    @Test
    fun `an accented capital carries a name`() {
        assertEquals(mapOf("Élisabeth" to CodexStats(2, 0)), CodexExtractor.extract("Il vit Élisabeth partir, puis Élisabeth revint."))
    }

    @Test
    fun `an apostrophe that is no elision stays in the name`() {
        assertEquals(mapOf("O'Brien" to CodexStats(2, 0)), CodexExtractor.extract("At the pub, O'Brien sang. O'Brien left early."))
    }

    @Test
    fun `cyrillic names carry the same evidence`() {
        assertEquals(
            mapOf("Мира" to CodexStats(1, 0), "Тобин" to CodexStats(1, 0)),
            CodexExtractor.extract("На рассвете Мира пошла к мельнице, и Тобин ждал у ворот."),
        )
        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("Внезапно пошёл дождь."))
    }

    @Test
    fun `greek names carry the same evidence`() {
        assertEquals(
            mapOf("Ελένη" to CodexStats(1, 1), "Νίκος" to CodexStats(1, 1)),
            CodexExtractor.extract("Το πρωί, η Ελένη χαμογέλασε και ο Νίκος απάντησε."),
        )
    }

    @Test
    fun `the greek question mark ends a sentence`() {
        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("γκρινιάρης; Ξανθιός έβρεξε."))
    }

    @Test
    fun `german nouns are candidates and the read floor is their dam`() {
        assertEquals(
            mapOf("Tür" to CodexStats(1, 0), "Tisch" to CodexStats(1, 0), "Fenster" to CodexStats(1, 0)),
            CodexExtractor.extract("Die Tür war offen, und der Tisch stand am Fenster."),
        )
    }

    @Test
    fun `a quote starts a sentence, so dialogue leads are weak evidence`() {
        val german = CodexExtractor.extract("»Halte die Klappe«, sagte Mira. Mira nickte.")
        assertTrue("Mira" in german.keys)
        assertTrue("Halte" !in german.keys)

        val russian = CodexExtractor.extract("— Привет, — сказала она. Потом Мира улыбнулась.")
        assertEquals(setOf("Мира"), russian.keys)

        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("He whispered, ‘Wait here.’ ".repeat(3)))
        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("He whispered, 'Wait here.' ".repeat(3)))
        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("– Wait here, – she said. ".repeat(3)))
    }

    @Test
    fun `speech adjacency accumulates while the words are in hand`() {
        assertEquals(
            mapOf("Xiulan" to CodexStats(2, 2)),
            CodexExtractor.extract("At dawn, Xiulan said nothing. Tobin asked, and Xiulan replied."),
        )
    }

    @Test
    fun `scanlation furniture and numbers never count`() {
        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("Chapter 12 of the translation. Translator note: the patreon link."))
        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("At noon, Twelve men gathered."))
    }

    @Test
    fun `scripts without case yield nothing`() {
        assertEquals(emptyMap<String, CodexStats>(), CodexExtractor.extract("李雷去了市场。韩梅梅在那里等着。"))
    }

    @Test
    fun `classification reads the name and its speech evidence`() {
        assertEquals(CodexKind.FACTION, CodexExtractor.classify("Azure Cloud Sect", 0))
        assertEquals(CodexKind.PLACE, CodexExtractor.classify("Blackwood Manor", 0))
        assertEquals(CodexKind.PERSON, CodexExtractor.classify("Elder Chen", 0))
        assertEquals(CodexKind.PERSON, CodexExtractor.classify("Xiulan", 2))
        assertEquals(CodexKind.UNKNOWN, CodexExtractor.classify("Zhora", 1))
    }
}
