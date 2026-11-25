package com.chmouel.liseur.domain.quotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorableLinesTest {
    private fun best(language: String?, vararg paragraphs: String): List<String> =
        MemorableLines.find(paragraphs.map { QuoteParagraph(it) }, language).map { it.text }

    @Test
    fun `a general line standing alone beats plot, dialogue and lines that lean on the one before`() {
        val found = best(
            "en",
            "Marcus walked to the station with Helen and Jacob after dinner on Tuesday.",
            "\u201CWe should go now,\u201D she said, looking back at the house behind them.",
            "He did not answer her, and they kept on walking down the narrow lane.",
            "We never know how much we love a place until we have to leave it behind.",
        )
        assertEquals("We never know how much we love a place until we have to leave it behind.", found.first())
    }

    @Test
    fun `lines that cannot stand on their own are refused outright`() {
        assertFalse(MemorableLines.acceptable("Too short to say much."))
        assertFalse(MemorableLines.acceptable("this one starts in the middle of a thought and goes on for a while."))
        assertFalse(MemorableLines.acceptable("This sentence never comes to an end and just trails away into nothing"))
        assertFalse(MemorableLines.acceptable("CHAPTER ONE IN WHICH EVERYTHING HAPPENS AT ONCE AND LOUDLY."))
        assertFalse(MemorableLines.acceptable("Copyright © 2021 by the author, all rights reserved worldwide."))
        assertFalse(MemorableLines.acceptable("She left (without a word and never came back to see us again."))
        assertTrue(MemorableLines.acceptable("Nothing is so painful to the human mind as a great and sudden change."))
    }

    @Test
    fun `the publisher's small print is never quoted, even its plain sentences`() {
        val found = best(
            "en",
            "This eBook is for the use of anyone anywhere in the United States and most other parts of the world. " +
                "If you are not located in the United States, you will have to check the laws of the country " +
                "where you are located before using this eBook.",
            "We never know how much we love a place until we have to leave it behind.",
        )
        assertEquals(listOf("We never know how much we love a place until we have to leave it behind."), found)
    }

    @Test
    fun `ordinary prose about notebooks and Facebook survives the small print check`() {
        val notebook = "We never know how much we love a place until we write about it in a notebook."
        val facebook = "We never know how much we love a place until we write about it on Facebook."
        assertEquals(listOf(notebook, facebook), best("en", notebook, facebook))
    }

    @Test
    fun `french and italian notices with a typographic apostrophe are small print too`() {
        val found = best(
            "en",
            "Tous droits réservés. Le droit d’auteur de cette traduction appartient à son auteur. " +
                "Nous avons mis beaucoup de soin dans ce travail et nous espérons qu'il vous plaira.",
            "Tutti i diritti riservati. Il diritto d’autore di questa edizione appartiene al curatore. " +
                "Abbiamo messo molta cura in questo lavoro e speriamo che vi piaccia leggere il resto.",
        )
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `chinese lines are measured in characters and need no capital`() {
        assertTrue(MemorableLines.acceptable("人生最大的幸福，莫过于在平凡的日子里找到属于自己的光。"))
        assertFalse(MemorableLines.acceptable("他走了。"))
    }

    @Test
    fun `french dialogue and anaphora lose to a general line`() {
        val found = best(
            "fr",
            "\u2014 Viens ici tout de suite, dit-elle à son frère qui jouait dehors.",
            "Il regarda longtemps la mer sans rien dire à personne ce soir-là.",
            "On ne voit bien qu\u2019avec le cœur, et l\u2019essentiel est invisible pour les yeux.",
        )
        assertEquals("On ne voit bien qu\u2019avec le cœur, et l\u2019essentiel est invisible pour les yeux.", found.first())
    }

    @Test
    fun `self-contained lines outrank references to the scene across languages`() {
        val examples = listOf(
            Triple("en", "We were only three, and nobody knew how to take care of it.",
                "We learn to live with silence long before we learn to listen to it."),
            Triple("fr-FR", "Nous n’étions que trois, nous n’étions pas équipés pour nous en occuper.",
                "La mémoire garde les blessures que le temps apprend à refermer."),
            Triple("es", "Después de la cena se lo explicó con toda la paciencia del mundo.",
                "La verdad cambia de voz cada vez que alguien se atreve a contarla."),
            Triple("it", "Dopo la cena glielo disse con la calma di chi aveva aspettato a lungo.",
                "La memoria custodisce ciò che il tempo non riesce a portare via."),
            Triple("de", "Nach dem Abendessen sagte er zu ihr, dass die Reise am Morgen beginnen würde.",
                "Die Erinnerung bewahrt, was die Zeit uns nicht zurückgeben kann."),
            Triple("pt-BR", "Depois do jantar, ninguém sabia como cuidar disso antes de partir.",
                "O tempo leva embora as palavras, mas deixa o silêncio que elas fizeram."),
            Triple("cs", "Po večeři se podíval na ni a pomalu zavřel dveře do zahrady.",
                "Čas mění podobu vzpomínek, ale pravda v nich zůstává stejná."),
            Triple("ru", "После ужина он посмотрел на неё и медленно закрыл дверь в сад.",
                "Время меняет наши воспоминания, но не отнимает у них правду."),
            Triple("zh-Hans", "晚饭过后，他转过身对她说，明天一早就得出门。",
                "人生的勇气不是从来不害怕，而是害怕的时候仍然愿意向前走。"),
        )
        for ((language, scene, standalone) in examples) {
            assertEquals(language, standalone, best(language, scene, standalone).firstOrNull())
        }
    }

    @Test
    fun `missing language metadata permits sentences in an uncased script`() {
        val line = "تحتفظ الذاكرة بما تعجز الأيام الطويلة عن محوه."
        assertEquals(listOf(line), best(null, line))
    }

    @Test
    fun `a weak automatic candidate can leave the book without a quote`() {
        val scene = "Nous n’étions que trois, nous n’étions pas équipés pour nous en occuper."
        assertTrue(MemorableLines.acceptable(scene))
        assertTrue(best("fr", scene).isEmpty())
    }

    @Test
    fun `a short setup and its turn can be quoted together within a paragraph`() {
        val first = "Silence lies."
        val second = "But silence also tells us what we are afraid to hear."
        val paragraph = "$first $second"
        val line = MemorableLines.find(listOf(QuoteParagraph(paragraph)), "en").single()
        assertEquals(paragraph, line.text)
        assertEquals(0, line.start)
        assertEquals(paragraph.length, line.end)
        assertTrue(best("en", first, second).isEmpty())
    }

    @Test
    fun `one line per paragraph and no repeats`() {
        val line = "Every family keeps its own silence, and every silence keeps its own family."
        val found = best("en", "$line $line", line, "A second sentence that is long enough to be acceptable here.")
        assertEquals(1, found.count { it == line })
    }

    @Test
    fun `large books keep exact offsets while reusing sentence iteration`() {
        val filler = QuoteParagraph("Marcus walked down the road with Helen before dinner on Tuesday.")
        val target = "We never know how much we love a place until we have to leave it behind."
        val prefix = "A quiet note. "
        val paragraphs = MutableList(5_000) { filler }
        paragraphs[2_500] = QuoteParagraph("$prefix$target")

        val found = MemorableLines.find(paragraphs, "en")

        val hit = found.first { it.text == target }
        assertEquals(2_500, hit.paragraph)
        assertEquals(prefix.length, hit.start)
        assertEquals(prefix.length + target.length, hit.end)
    }
}
