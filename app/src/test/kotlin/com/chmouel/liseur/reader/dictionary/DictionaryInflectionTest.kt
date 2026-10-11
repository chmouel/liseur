package com.chmouel.liseur.reader.dictionary

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryInflectionTest {
    @Test
    fun `English noun and verb keep their base-word links`() {
        val senses = parseWiktionaryDefinitions(englishForms())
        assertEquals(listOf("Noun", "Verb"), senses.map { it.partOfSpeech })
        assertTrue(senses.all { it.references[0] == DictionaryReference("harness", "en") })
        assertEquals("plural of harness", senses.first().definitions.single())
    }

    @Test
    fun `plural and conjugated forms preserve their language and grammatical group`() {
        for (fixture in htmlForms) {
            val sense = parseWiktionaryEntryHtml(fixture.html, listOf(fixture.language)).single()
            assertEquals(fixture.word, DictionaryReference(fixture.base, fixture.language), sense.references[0])
            assertEquals(fixture.word, fixture.part, grammaticalPart(sense.partOfSpeech))
            assertEquals(fixture.language, sense.language)
        }
    }

    @Test
    fun `ordinary links and non-inflection notes never become references`() {
        val fragments = listOf(
            """A <a href="/wiki/harness">harness</a> for climbing.""",
            """<span class="form-of-definition"><a href="/wiki/harness">harness</a></span>""",
        )
        for (fragment in fragments) {
            assertTrue(parseWiktionaryDefinitions(englishDefinition(fragment)).single().references.isEmpty())
        }
        val spanish = page("es", "Español", "Sustantivo masculino",
            """<dl><dt>1</dt><dd><span class="definicion-impropia">Sinónimo de </span><a href="./gato">gato</a></dd></dl>""")
        assertTrue(parseWiktionaryEntryHtml(spanish).single().references.isEmpty())
    }

    @Test
    fun `only local entry links in the same language qualify`() {
        val invalid = listOf(
            "https://evil.example/wiki/harness", "//evil.example/wiki/harness",
            "/wiki/Help:word", "./Category%3Aword", "./%2e%2e%2fword", "./%23word", "./%",
            "/wiki/harness#French", "/wiki/harness?redirect=yes",
        )
        for (href in invalid) {
            val senses = parseWiktionaryDefinitions(englishDefinition(formLink("harness", href)))
            assertTrue(href, senses.single().references.isEmpty())
        }
        val encoded = parseWiktionaryDefinitions(englishDefinition(formLink("café", "./caf%C3%A9#English")))
        assertEquals("café", encoded.single().references[0]?.term)
    }

    @Test
    fun `usage example links do not compete with grammatical targets`() {
        val fixture = htmlForms.first()
        val html = fixture.html.replace("</li>", """<ul><li>A <a href="./selle">selle</a>.</li></ul></li>""")
        assertEquals("cheval", parseWiktionaryEntryHtml(html).single().references[0]?.term)
        assertNull(grammaticalPart("Étymologie"))
    }

    companion object {
        // Reduced grammatical markup from the named Wiktionary entries (CC BY-SA 4.0).
        // https://{edition}.wiktionary.org/wiki/{word}; examples and prose definitions omitted.
        data class HtmlForm(
            val language: String, val word: String, val base: String, val part: String, val html: String,
        )

        val htmlForms = listOf(
            HtmlForm("fr", "chevaux", "cheval", "noun", page("fr", "Français", "Forme de nom commun",
                """<ol><li><i>Pluriel de</i> <a href="./cheval">cheval</a>.</li></ol>""")),
            HtmlForm("fr", "mangeaient", "manger", "verb", page("fr", "Français", "Forme de verbe",
                """<ol><li><i>Troisième personne du pluriel de l’imparfait de l’indicatif de</i> <a href="./manger">manger</a>.</li></ol>""")),
            HtmlForm("es", "gatos", "gato", "noun", page("es", "Español", "Forma sustantiva",
                """<dl><dt>1</dt><dd><span class="definicion-impropia">Forma del plural de </span><a href="./gato#Español">gato</a>.</dd></dl>""")),
            HtmlForm("es", "comieron", "comer", "verb", page("es", "Español", "Forma verbal",
                """<dl><dt>1</dt><dd><span class="definicion-impropia"><a href="./tercera_persona">Tercera persona</a> del plural del pretérito de indicativo de </span><a href="./comer#Español">comer</a>.</dd></dl>""")),
            HtmlForm("nl", "paarden", "paard", "noun", page("nl", "Nederlands", "Zelfstandig naamwoord",
                """<ol><li>meervoud van het zelfstandig naamwoord <a href="./paard">paard</a></li></ol>""")),
            HtmlForm("nl", "liepen", "lopen", "verb", page("nl", "Nederlands", "Werkwoord",
                """<ol><li>meervoud verleden tijd van <a href="./lopen">lopen</a><dl><dd>Wij liepen.</dd></dl></li></ol>""")),
        )

        fun page(language: String, heading: String, part: String, definitions: String): String =
            """<html lang="$language"><body><section><h2>$heading</h2><section><h3>$part</h3>$definitions</section></section></body></html>"""

        fun formLink(term: String, href: String = "/wiki/$term#English", note: String = "plural of"): String =
            """<span class="form-of-definition">$note <span class="form-of-definition-link"><i lang="en"><a href="$href">$term</a></i></span></span>"""

        fun englishDefinition(html: String, part: String = "Noun"): String =
            """{"en":[{"partOfSpeech":"$part","definitions":[{"definition":${JSONObject.quote(html)}}]}]}"""

        fun englishForms(): String =
            """{"en":[
                {"partOfSpeech":"Noun","definitions":[{"definition":${JSONObject.quote(formLink("harness"))}}]},
                {"partOfSpeech":"Verb","definitions":[{"definition":${JSONObject.quote(formLink("harness", note = "third-person singular present of"))}}]}
            ]}"""
    }
}
