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
            assertEquals(language, listOf(standalone, scene), best(language, scene, standalone))
        }
    }

    @Test
    fun `missing language metadata permits sentences in an uncased script`() {
        val line = "تحتفظ الذاكرة بما تعجز الأيام الطويلة عن محوه."
        assertEquals(listOf(line), best(null, line))
    }

    @Test
    fun `one line per paragraph and no repeats`() {
        val line = "Every family keeps its own silence, and every silence keeps its own family."
        val found = best("en", "$line $line", line, "A second sentence that is long enough to be acceptable here.")
        assertEquals(1, found.count { it == line })
    }
}
