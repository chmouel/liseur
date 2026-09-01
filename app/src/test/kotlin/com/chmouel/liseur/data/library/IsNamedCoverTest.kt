package com.chmouel.liseur.data.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last-resort rule for a book that declares no cover at all.
 *
 * It only decides between images that are already in the publication,
 * and only once both declared routes have come back empty, so the cost
 * of being wrong is a wrong picture rather than a missing book. It is
 * still worth pinning: this is what a reader sees on the shelf.
 */
class IsNamedCoverTest {
    @Test
    fun `the conventional names count`() {
        assertTrue(isNamedCover("cover.jpg"))
        assertTrue(isNamedCover("cover.jpeg"))
        assertTrue(isNamedCover("cover.png"))
    }

    /**
     * The name rule knows nothing about formats, and does not need to:
     * a candidate is only considered when Readium already called it a
     * bitmap, and `MediaType.isBitmap` is BMP, GIF, JPEG, PNG, TIFF and
     * WEBP. So `cover.svg` passing here does not mean an SVG cover
     * works — the media type is what turns it away, one step earlier.
     */
    @Test
    fun `the rule is about the name, not the format`() {
        assertTrue(isNamedCover("cover.svg"))
        assertTrue(isNamedCover("cover.txt"))
    }

    @Test
    fun `an extension is not required`() {
        assertTrue(isNamedCover("cover"))
    }

    @Test
    fun `the convention is written every way round`() {
        assertTrue(isNamedCover("Cover.jpg"))
        assertTrue(isNamedCover("COVER.JPG"))
    }

    /**
     * Only the last dot is an extension. A book that spells its cover
     * `cover.small.jpg` is naming a variant, not the cover.
     */
    @Test
    fun `only the final extension is dropped`() {
        assertFalse(isNamedCover("cover.small.jpg"))
    }

    /**
     * The whole point of matching exactly: a book has one cover, and
     * several images whose names merely contain the word would make the
     * choice arbitrary.
     */
    @Test
    fun `a name that merely contains cover is not one`() {
        assertFalse(isNamedCover("cover-page.jpg"))
        assertFalse(isNamedCover("covers.jpg"))
        assertFalse(isNamedCover("frontcover.jpg"))
        assertFalse(isNamedCover("cover_1.jpg"))
        assertFalse(isNamedCover("9781958803684_cover.jpg"))
    }

    /** A dotfile has no stem to drop, so it is compared whole. */
    @Test
    fun `a leading dot is not an extension`() {
        assertFalse(isNamedCover(".cover"))
    }

    @Test
    fun `nothing at all is not a cover`() {
        assertFalse(isNamedCover(null))
        assertFalse(isNamedCover(""))
    }
}
