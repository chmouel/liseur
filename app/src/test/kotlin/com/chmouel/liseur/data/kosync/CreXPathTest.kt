package com.chmouel.liseur.data.kosync

import com.chmouel.liseur.reader.progress.ViewportTextAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deriving a CRe XPath from a Liseur text anchor.
 *
 * The golden strings come from liseur-sync's `internal/xpointer` tests,
 * which are themselves CrossPoint's own resolver fixtures: the two sides
 * must write the same XPath for the same place. The offset cases pin the
 * one deliberate difference — an offset inside a run whose prefix
 * KOReader's collapsing would move is written as the start of that run.
 */
class CreXPathTest {

    private fun xpath(
        xhtml: String,
        selector: String,
        before: String,
        highlight: String,
        after: String = "",
        fragment: Int = 1,
    ): String? = CreXPath.forAnchor(
        xhtml.toByteArray(),
        fragment,
        ViewportTextAnchor(selector, before, highlight, after),
    )

    private fun assertXpath(
        want: String?,
        xhtml: String,
        selector: String,
        before: String,
        highlight: String,
        after: String = "",
        fragment: Int = 1,
    ) {
        assertEquals(
            xhtml,
            want,
            xpath(xhtml, selector, before, highlight, after, fragment),
        )
    }

    private val chapter = "body > div:nth-of-type(1) > section:nth-of-type(1)"

    // -- The device's own fixtures ---------------------------------------

    @Test
    fun `every index is spelled out, only children included`() {
        assertXpath(
            "/body/DocFragment[1]/body/div[1]/section[1]/p[1]/text()[1].6",
            NESTED,
            "$chapter > p:nth-of-type(1)",
            "Alpha ",
            "bravo",
        )
    }

    @Test
    fun `a quote is placed where liseur-sync places it`() {
        assertXpath(
            "/body/DocFragment[2]/body/p[1]/text()[1].25",
            "<html><body><h1>One</h1><p>It was a bright cold day in April, and the clocks " +
                "were striking thirteen.</p></body></html>",
            "body > p:nth-of-type(1)",
            "It was a bright cold day ",
            "in",
            " April, and the clocks were stri",
            fragment = 2,
        )
    }

    @Test
    fun `a quote that spans a run boundary lands in the run its word is in`() {
        assertXpath(
            "/body/DocFragment[1]/body/div[1]/section[1]/p[2]/em[1]/text()[1].0",
            NESTED,
            "$chapter > p:nth-of-type(2)",
            "Second ",
            "nested",
            " tail",
        )
    }

    @Test
    fun `the fragment number is the spine index`() {
        assertXpath(
            "/body/DocFragment[7]/body/div[1]/section[1]/p[1]/text()[1].6",
            NESTED,
            "$chapter > p:nth-of-type(1)",
            "Alpha ",
            "bravo",
            fragment = 7,
        )
    }

    // -- Runs, and what splits them ---------------------------------------

    @Test
    fun `hidden text is not counted but keeps its element's place`() {
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[1].0",
            "<html><body><p><RP><span>hidden</span></RP>Visible text</p></body></html>",
            "body > p:nth-of-type(1)",
            "",
            "Visible",
            " text",
        )
    }

    @Test
    fun `a comment splits a text run`() {
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[2].0",
            "<html><body><p>before<!--comment-->after</p></body></html>",
            "body > p:nth-of-type(1)",
            "before",
            "after",
        )
    }

    @Test
    fun `a processing instruction splits a text run`() {
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[2].0",
            "<html><body><p>before<?marker?>after</p></body></html>",
            "body > p:nth-of-type(1)",
            "before",
            "after",
        )
    }

    @Test
    fun `a CDATA section is a run of its own`() {
        val xhtml = "<html><body><p>before<![CDATA[middle]]>after</p></body></html>"
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[2].0",
            xhtml,
            "body > p:nth-of-type(1)",
            "before",
            "middle",
        )
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[3].0",
            xhtml,
            "body > p:nth-of-type(1)",
            "beforemiddle",
            "after",
        )
    }

    @Test
    fun `an empty run is no run at all`() {
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[1].0",
            "<html><body><p><![CDATA[]]>text</p></body></html>",
            "body > p:nth-of-type(1)",
            "",
            "text",
        )
    }

    @Test
    fun `a document with no body declines`() {
        assertNull(
            xpath(
                "<html><head><title>t</title></head></html>",
                "body > p:nth-of-type(1)",
                "",
                "t",
            ),
        )
    }

    // -- Selectors ---------------------------------------------------------

    @Test
    fun `an id names its element`() {
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[1].5",
            "<html><body><p id=\"p1\">Some words here</p></body></html>",
            "#p1",
            "",
            "words",
        )
    }

    @Test
    fun `an id two elements share names nothing`() {
        assertNull(
            xpath(
                "<html><body><p id=\"d\">One</p><p id=\"d\">Two</p></body></html>",
                "#d",
                "",
                "One",
            ),
        )
    }

    @Test
    fun `an id shared with an element outside the body names nothing`() {
        assertNull(
            xpath(
                "<html><head><meta id=\"x\"/></head><body><p id=\"x\">Word</p></body></html>",
                "#x",
                "",
                "Word",
            ),
        )
    }

    @Test
    fun `an escaped id is read back`() {
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[1].0",
            "<html><body><p id=\"a:b\">Words</p></body></html>",
            "#a\\:b",
            "",
            "Words",
        )
    }

    @Test
    fun `a selector that names nothing declines`() {
        assertNull(xpath(NESTED, "body > section:nth-of-type(1)", "", "bravo"))
        assertNull(xpath(NESTED, "body > div:nth-of-type(1) > p:nth-of-type(1)", "", "bravo"))
    }

    // -- Quotes that cannot be placed --------------------------------------

    @Test
    fun `a quote that occurs twice in its block declines`() {
        assertNull(
            xpath(
                "<html><body><p>a b a b</p></body></html>",
                "body > p:nth-of-type(1)",
                "",
                "a b",
            ),
        )
    }

    @Test
    fun `a quote that occurs nowhere declines`() {
        assertNull(xpath(NESTED, "$chapter > p:nth-of-type(1)", "", "charlie"))
    }

    @Test
    fun `a whitespace-only run before the place declines`() {
        assertNull(
            xpath(
                "<html><body><div>\n  <p>x</p>\n  Tail text</div></body></html>",
                "body > div:nth-of-type(1)",
                "\n  ",
                "Tail",
                " text",
            ),
        )
    }

    @Test
    fun `an empty highlight declines`() {
        assertNull(xpath(NESTED, "$chapter > p:nth-of-type(1)", "Alpha ", ""))
    }

    // -- Offsets KOReader's collapsing would move --------------------------

    @Test
    fun `an offset after an indented wrapped line is written as the start of the run`() {
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[1].0",
            "<html><body><p>line one\n   and line two</p></body></html>",
            "body > p:nth-of-type(1)",
            "line one\n   ",
            "and",
        )
    }

    @Test
    fun `an offset after a single line break is kept`() {
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[1].9",
            "<html><body><p>line one\nand line two</p></body></html>",
            "body > p:nth-of-type(1)",
            "line one\n",
            "and",
        )
    }

    @Test
    fun `an offset after two spaces is written as the start of the run`() {
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[1].0",
            "<html><body><p>a  b c</p></body></html>",
            "body > p:nth-of-type(1)",
            "a  b ",
            "c",
        )
    }

    @Test
    fun `a no-break space is an ordinary character`() {
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[1].5",
            "<html><body><p>a\u00A0\u00A0b c</p></body></html>",
            "body > p:nth-of-type(1)",
            "a\u00A0\u00A0b ",
            "c",
        )
    }

    @Test
    fun `an offset after a single leading space is kept, as liseur-sync writes it`() {
        assertXpath(
            "/body/DocFragment[1]/body/div[1]/section[1]/p[2]/text()[2].2",
            NESTED,
            "$chapter > p:nth-of-type(2)",
            "d t",
            "ail",
        )
    }

    @Test
    fun `a run CREngine would split declines`() {
        fun p(words: Int) = "<html><body><p>" + "word ".repeat(words) + "end<br/>tail</p></body></html>"
        assertXpath("/body/DocFragment[1]/body/p[1]/text()[1].8000", p(1600), "body > p:nth-of-type(1)", " ", "end")
        assertNull(xpath(p(1640), "body > p:nth-of-type(1)", " ", "end"))
        assertNull(xpath(p(1640), "body > p:nth-of-type(1)", "end", "tail"))
    }

    @Test
    fun `a run whose entities make the source long enough to split declines`() {
        // 3,200 characters of text, 9,600 of source.
        val xhtml = "<html><body><p>" + "&#65; ".repeat(1600) + "end<br/>tail</p></body></html>"
        assertNull(xpath(xhtml, "body > p:nth-of-type(1)", "end", "tail"))
    }

    @Test
    fun `a long UTF-16 run declines in either byte order`() {
        fun p(repeats: Int) = "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-16\"?>" +
            "<html><body><p>" + "\u4e3cword ".repeat(repeats) + "needle</p></body></html>"
        val anchor = ViewportTextAnchor("body > p:nth-of-type(1)", "word ", "needle", "")
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            assertEquals(
                "/body/DocFragment[1]/body/p[1]/text()[1].60",
                CreXPath.forAnchor(p(10).toByteArray(charset), 1, anchor),
            )
            assertNull(CreXPath.forAnchor(p(1500).toByteArray(charset), 1, anchor))
        }
    }

    @Test
    fun `a CDATA section long enough to split declines even with a less-than sign inside`() {
        fun p(repeats: Int) = "<html><body><p><![CDATA[" + "word < ".repeat(repeats) + "]]>needle</p></body></html>"
        assertXpath("/body/DocFragment[1]/body/p[1]/text()[2].0", p(100), "body > p:nth-of-type(1)", "< ", "needle")
        assertNull(xpath(p(1200), "body > p:nth-of-type(1)", "< ", "needle"))
    }

    @Test
    fun `an offset is counted in code points`() {
        assertXpath(
            "/body/DocFragment[1]/body/p[1]/text()[1].3",
            "<html><body><p>A\u00E9\u4E2DB</p></body></html>",
            "body > p:nth-of-type(1)",
            "A\u00E9\u4E2D",
            "B",
        )
    }

    // -- Limits ------------------------------------------------------------

    private fun nested(depth: Int) = "<div>".repeat(depth) + "text" + "</div>".repeat(depth)

    @Test
    fun `a chapter nested past the depth limit declines`() {
        fun deep(depth: Int) = "<html><body><p>word</p>" + nested(depth) + "</body></html>"
        assertXpath("/body/DocFragment[1]/body/p[1]/text()[1].0", deep(200), "body > p:nth-of-type(1)", "", "word")
        assertNull(xpath(deep(201), "body > p:nth-of-type(1)", "", "word"))
    }

    @Test
    fun `a path past the step limit declines`() {
        fun selector(depth: Int) = "body" + " > div:nth-of-type(1)".repeat(depth)
        fun deep(depth: Int) = "<html><body>" + nested(depth) + "</body></html>"
        assertXpath(
            "/body/DocFragment[1]/body" + "/div[1]".repeat(64) + "/text()[1].0",
            deep(64),
            selector(64),
            "",
            "text",
        )
        assertNull(xpath(deep(65), selector(65), "", "text"))
    }

    @Test
    fun `a chapter past the node limit declines`() {
        // The body and each <p> with its run: 1 + 2n nodes against a 200,000 limit.
        fun wide(paragraphs: Int) = "<html><body>" + "<p>word</p>".repeat(paragraphs) + "</body></html>"
        assertXpath("/body/DocFragment[1]/body/p[1]/text()[1].0", wide(99_999), "body > p:nth-of-type(1)", "", "word")
        assertNull(xpath(wide(100_000), "body > p:nth-of-type(1)", "", "word"))
    }

    private companion object {
        const val NESTED = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body><div><section><p>Alpha bravo</p><p>Second <em>nested</em> tail</p></section></div></body></html>"""
    }
}
