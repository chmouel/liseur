package com.chmouel.liseur.hardcover

import org.junit.Assert.assertEquals
import org.junit.Test

class HardcoverRefTest {

    @Test
    fun `a book link gives its slug whatever surrounds it`() {
        val slug = HardcoverRef.Slug("qumran-1996")
        assertEquals(slug, HardcoverRef.parse("https://hardcover.app/books/qumran-1996"))
        assertEquals(slug, HardcoverRef.parse("  http://www.hardcover.app/books/qumran-1996/editions/123?ref=x#top "))
        assertEquals(slug, HardcoverRef.parse("hardcover.app/books/qumran-1996?x=1"))
        assertEquals(slug, HardcoverRef.parse("HTTPS://HARDCOVER.APP/books/qumran-1996#a"))
    }

    @Test
    fun `a link anywhere else is not hardcover and is never sent as text`() {
        assertEquals(HardcoverRef.NotHardcover, HardcoverRef.parse("https://example.com/books/x?q=secret"))
        assertEquals(HardcoverRef.NotHardcover, HardcoverRef.parse("https://hardcover.app.evil.com/books/x"))
        assertEquals(HardcoverRef.NotHardcover, HardcoverRef.parse("https://hardcover.app@evil.com/books/x"))
        assertEquals(HardcoverRef.NotHardcover, HardcoverRef.parse("example.com/path"))
        assertEquals(HardcoverRef.NotHardcover, HardcoverRef.parse("example.com?token=secret"))
        assertEquals(HardcoverRef.NotHardcover, HardcoverRef.parse("www.example.com#private"))
        assertEquals(HardcoverRef.Text("Node.js"), HardcoverRef.parse("Node.js"))
        assertEquals(HardcoverRef.NotHardcover, HardcoverRef.parse("https://hardcover.app/authors/x"))
        assertEquals(HardcoverRef.NotHardcover, HardcoverRef.parse("ftp://x"))
        assertEquals(HardcoverRef.NotHardcover, HardcoverRef.parse("//example.com/books/x?token=secret"))
        assertEquals(HardcoverRef.Slug("qumran-1996"), HardcoverRef.parse("//hardcover.app/books/qumran-1996"))
        assertEquals(HardcoverRef.NotHardcover, HardcoverRef.parse("https://hardcover.app/books/a%20b"))
    }

    @Test
    fun `isbns in any usual spelling are recognised before book ids`() {
        val isbn = Isbn.from("9780547928227")!!
        assertEquals(HardcoverRef.Isbn(isbn), HardcoverRef.parse("978-0-547-92822-7"))
        assertEquals(HardcoverRef.Isbn(isbn), HardcoverRef.parse("ISBN 978 0547928227"))
        assertEquals(HardcoverRef.Isbn(Isbn.from("080442957X")!!), HardcoverRef.parse("0-8044-2957-x"))
    }

    @Test
    fun `digits are a book id when they fit, even a single one`() {
        assertEquals(HardcoverRef.BookId(7), HardcoverRef.parse("7"))
        assertEquals(HardcoverRef.BookId(123456), HardcoverRef.parse(" 123456 "))
        assertEquals(HardcoverRef.BookId(Int.MAX_VALUE), HardcoverRef.parse(Int.MAX_VALUE.toString()))
    }

    @Test
    fun `digits that cannot be an id are refused`() {
        assertEquals(HardcoverRef.BadId, HardcoverRef.parse("0"))
        assertEquals(HardcoverRef.BadId, HardcoverRef.parse("3000000000"))
        assertEquals(HardcoverRef.BadId, HardcoverRef.parse("99999999999999999999"))
    }

    @Test
    fun `anything else is text, and blank is blank`() {
        assertEquals(HardcoverRef.Text("Le Maitre du Talmud"), HardcoverRef.parse(" Le Maitre du Talmud "))
        assertEquals(HardcoverRef.Text("a"), HardcoverRef.parse("a"))
        assertEquals(HardcoverRef.Text("Mr. Smith / Jones"), HardcoverRef.parse("Mr. Smith / Jones"))
        assertEquals(HardcoverRef.Blank, HardcoverRef.parse(" \n"))
    }

    @Test
    fun `only a multi character jump to a recognised link or id submits`() {
        val link = "https://hardcover.app/books/qumran-1996"
        assertEquals(true, HardcoverRef.submitsOnPaste("", link))
        assertEquals(true, HardcoverRef.submitsOnPaste("old", "12345"))
        assertEquals(true, HardcoverRef.submitsOnPaste("", "https://example.com/x"))
        assertEquals(true, HardcoverRef.submitsOnPaste("", "978-0-547-92822-7"))
        assertEquals(false, HardcoverRef.submitsOnPaste("", "Le Maitre du Talmud"))
        assertEquals(false, HardcoverRef.submitsOnPaste("1234", "12345"))
        assertEquals(false, HardcoverRef.submitsOnPaste("hardcover.app/books/qumran-199", "hardcover.app/books/qumran-1996"))
        assertEquals(false, HardcoverRef.submitsOnPaste(link, ""))
    }

    @Test
    fun `a shorter paste over a longer prefilled query still submits`() {
        val prefilled = "Le Maitre du Talmud Rabbi Aryeh"
        assertEquals(true, HardcoverRef.submitsOnPaste(prefilled, "hardcover.app/books/qumran"))
        assertEquals(true, HardcoverRef.submitsOnPaste(prefilled, "12345"))
        assertEquals(false, HardcoverRef.submitsOnPaste(prefilled, "Le Maitre du Talmud Rabbi Aryeh X"))
        assertEquals(false, HardcoverRef.submitsOnPaste("Qumran", "Qumran 1996"))
    }
}
