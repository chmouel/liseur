package com.chmouel.liseur.reader.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Parsoid entry-page parser, against the three shapes the editions
 * actually use: an `<ol>` of senses (French and most others), a `<dl>`
 * of `[1]`-numbered `<dd>`s (German), and a `<dl>` of numbered `<dt>`s
 * (Spanish). The fixtures are trimmed from real pages.
 */
class WiktionaryHtmlTest {

    private val french = """
        <html><body>
        <section data-mw-section-id="1"><h2 id="Français">Français</h2>
          <section><h3 id="Étymologie">Étymologie</h3><p>Du latin liber.</p></section>
          <section><h3 id="Nom_commun_1">Nom commun 1</h3>
            <p>livre masculin</p>
            <ol>
              <li>Assemblage de <a href="./feuille">feuilles</a> imprimées.
                <ul><li><span class="example">C’est le livre des Destinées.</span></li></ul>
              </li>
              <li>Registre de comptes.</li>
            </ol>
          </section>
          <section><h3 id="Références">Références</h3>
            <ol class="references" typeof="mw:Extension/references">
              <li id="cite_note-1">Un renvoi.</li>
            </ol>
          </section>
        </section>
        <section data-mw-section-id="2"><h2 id="Portugais">Portugais</h2>
          <section><h3 id="Adjectif">Adjectif</h3><ol><li>Libre.</li></ol></section>
        </section>
        </body></html>
    """.trimIndent()

    private val german = """
        <html><body>
        <section><h2 id="Buch_(Deutsch)">Buch (Deutsch)</h2>
          <section><h3 id="Substantiv,_n">Substantiv, n</h3>
            <p>Worttrennung:</p>
            <dl><dd>Buch, Plural: Bü·cher</dd></dl>
            <p>Bedeutungen:</p>
            <dl>
              <dd>[1] fest gebundenes <a href="./Schriftwerk">Schriftwerk</a></dd>
              <dd>[2] literarische Publikation in Buchform</dd>
            </dl>
          </section>
        </section>
        </body></html>
    """.trimIndent()

    private val spanish = """
        <html><body>
        <section><h2 id="Alemán">Alemán</h2>
          <section><h3 id="Sustantivo_neutro">Sustantivo neutro</h3>
            <dl><dt>1 Cultura</dt><dd><a href="./libro">libro</a>.</dd></dl>
          </section>
        </section>
        </body></html>
    """.trimIndent()

    @Test
    fun `reads senses out of a French-style entry`() {
        val senses = parseWiktionaryEntryHtml(french, listOf("fr"))

        assertEquals(listOf("Nom commun"), senses.map { it.partOfSpeech })
        assertEquals(
            listOf("Assemblage de feuilles imprimées.", "Registre de comptes."),
            senses.single().definitions,
        )
    }

    @Test
    fun `prefers the section of the book's language`() {
        val senses = parseWiktionaryEntryHtml(french, listOf("pt"))

        assertEquals(listOf("Adjectif"), senses.map { it.partOfSpeech })
        assertEquals(listOf("Libre."), senses.single().definitions)
    }

    @Test
    fun `falls back to the first section with senses`() {
        val senses = parseWiktionaryEntryHtml(french, listOf("ja"))

        assertEquals("Nom commun", senses.first().partOfSpeech)
    }

    @Test
    fun `usage examples stay in the full entry, not on the card`() {
        val senses = parseWiktionaryEntryHtml(french, listOf("fr"))

        assertTrue(senses.single().definitions.none { it.contains("Destinées") })
    }

    @Test
    fun `footnotes are not definitions`() {
        val senses = parseWiktionaryEntryHtml(french, listOf("fr"))

        assertTrue(senses.none { it.partOfSpeech == "Références" })
    }

    @Test
    fun `reads senses out of a German-style entry`() {
        val senses = parseWiktionaryEntryHtml(german, listOf("de"))

        assertEquals(listOf("Substantiv, n"), senses.map { it.partOfSpeech })
        assertEquals(
            listOf(
                "fest gebundenes Schriftwerk",
                "literarische Publikation in Buchform",
            ),
            senses.single().definitions,
        )
    }

    @Test
    fun `reads senses out of a Spanish-style entry`() {
        val senses = parseWiktionaryEntryHtml(spanish, listOf("de"))

        assertEquals(listOf("Sustantivo neutro"), senses.map { it.partOfSpeech })
        assertEquals(listOf("libro."), senses.single().definitions)
    }

    @Test
    fun `an empty or unparseable page has no senses`() {
        assertEquals(emptyList<DictionarySense>(), parseWiktionaryEntryHtml(""))
        assertEquals(
            emptyList<DictionarySense>(),
            parseWiktionaryEntryHtml("<html><body><p>Rien.</p></body></html>"),
        )
    }
}
