package com.chmouel.liseur.domain.quotes

import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteBenchmarkTest {
    @Test
    fun `rank the first five automatic quotes from each public domain excerpt`() {
        val editions = listOf("cs", "de", "en", "en-essay", "es", "fr", "it", "pt", "ru", "zh")
        for (edition in editions) {
            val source = checkNotNull(javaClass.getResource("/quotes/$edition.txt")).readText()
            val paragraphs = source.lineSequence().drop(1).filter(String::isNotBlank).map(::QuoteParagraph).toList()
            val lines = MemorableLines.find(paragraphs, edition.substringBefore('-'), keep = 5)

            println("$edition · ${source.lineSequence().first()}")
            for (line in lines) println("${"%.2f".format(Locale.ROOT, line.score)}\t${line.text}")
            if (lines.isEmpty()) println("(no quote)")

            when (edition) {
                "en" -> assertTrue(lines.first().text.startsWith("It is a truth universally acknowledged"))
                "en-essay" -> assertTrue(lines.any { it.text.contains("fear death") })
                "es", "ru" -> assertTrue("$edition should skip the scene excerpts", lines.isEmpty())
                "zh" -> assertTrue(lines.any { it.text.contains("敬事而信") })
            }
        }
    }
}
