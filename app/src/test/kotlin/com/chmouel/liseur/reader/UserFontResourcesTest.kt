package com.chmouel.liseur.reader

import com.chmouel.liseur.data.settings.fonts.UserFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Which requests from the web view are answered with an imported font.
 *
 * The navigator asks the publication for a URL, and the publication is
 * whatever Liseur wrapped it in. This is the whole of the boundary
 * between "a font the reader imported" and "any other file on the
 * device", so it is spelled out rather than assumed: an exact match
 * against an href built from a font already in the registry, and no path
 * resolution anywhere.
 *
 * Readium's `Url` parses through `android.net.Uri`, so this runs under
 * Robolectric rather than on the bare JVM.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class UserFontResourcesTest {

    private val digest = "a".repeat(64)
    private val other = "b".repeat(64)

    private val font = UserFont(
        digest = digest,
        displayName = "Fixture",
        file = File("/data/user/0/com.chmouel.liseur/files/fonts/$digest.ttf"),
        extension = "ttf",
        italic = false,
        staticWeight = 400,
        weightRange = null,
    )

    private val fonts = listOf(font)

    private fun match(href: String) = UserFontResources.match(Url(href)!!, fonts)

    @Test
    fun `answers the absolute url a declaration is written with`() {
        assertEquals(font, match("https://readium_package/__liseur_fonts__/$digest.ttf"))
        assertEquals(font, UserFontResources.match(UserFontResources.url(font), fonts))
    }

    @Test
    fun `answers the relative spelling of the same path`() {
        assertEquals(font, match("__liseur_fonts__/$digest.ttf"))
    }

    @Test
    fun `refuses another host`() {
        assertNull(match("https://readium_assets/__liseur_fonts__/$digest.ttf"))
        assertNull(match("https://example.com/__liseur_fonts__/$digest.ttf"))
        assertNull(match("https://readium_package.example.com/__liseur_fonts__/$digest.ttf"))
    }

    @Test
    fun `refuses another scheme`() {
        assertNull(match("http://readium_package/__liseur_fonts__/$digest.ttf"))
        assertNull(match("file:///__liseur_fonts__/$digest.ttf"))
    }

    @Test
    fun `refuses a port or user-info smuggled into the authority`() {
        // Requiring the origin as a literal prefix settles all four of
        // scheme, host, port and user-info in one comparison, which is
        // why there is no separate assertion for each.
        assertNull(match("https://readium_package:8080/__liseur_fonts__/$digest.ttf"))
        assertNull(match("https://evil@readium_package/__liseur_fonts__/$digest.ttf"))
    }

    @Test
    fun `refuses traversal in every spelling`() {
        assertNull(match("__liseur_fonts__/../../databases/liseur.db"))
        assertNull(match("__liseur_fonts__/%2e%2e/%2e%2e/databases/liseur.db"))
        assertNull(match("__liseur_fonts__/%2E%2E/liseur.db"))
        assertNull(match("__liseur_fonts__/..%2f$digest.ttf"))
        assertNull(match("https://readium_package/__liseur_fonts__/../$digest.ttf"))
    }

    @Test
    fun `refuses a separator that only looks like part of the name`() {
        assertNull(match("__liseur_fonts__/sub/$digest.ttf"))
        assertNull(match("__liseur_fonts__%2f$digest.ttf"))
        assertNull(match("__liseur_fonts__/dir%5c$digest.ttf"))
    }

    @Test
    fun `refuses a doubled slash`() {
        assertNull(match("https://readium_package//__liseur_fonts__/$digest.ttf"))
        assertNull(match("__liseur_fonts__//$digest.ttf"))
    }

    @Test
    fun `refuses a well-formed name that is not in the registry`() {
        // The last line, and the one that means traversal has nothing to
        // aim at: the file comes from the matched UserFont, never from
        // joining a url onto a directory.
        assertNull(match("__liseur_fonts__/$other.ttf"))
        assertNull(match("__liseur_fonts__/$digest.otf"))
    }

    @Test
    fun `refuses an ordinary publication resource`() {
        assertNull(match("OEBPS/chapter1.xhtml"))
        assertNull(match("__liseur_fonts__"))
        assertNull(match("fonts/$digest.ttf"))
    }

    @Test
    fun `tolerates a fragment and a query rather than refusing them`() {
        // Publication.get looks up `href` and then retries with the query
        // removed. A rule that turned one away would break Readium's own
        // second attempt at a request that was legitimate to start with.
        assertEquals(font, match("__liseur_fonts__/$digest.ttf#anything"))
        assertEquals(font, match("__liseur_fonts__/$digest.ttf?v=1"))
        assertEquals(font, match("https://readium_package/__liseur_fonts__/$digest.ttf?v=1#x"))
    }

    @Test
    fun `refuses an href long enough to be an attack rather than a font`() {
        assertNull(match("__liseur_fonts__/" + "a".repeat(600) + ".ttf"))
    }

    @Test
    fun `the container answers only for the fonts it holds`() {
        val container = UserFontsContainer { fonts }
        assertNull(container.get(Url("OEBPS/chapter1.xhtml")!!))
        assertNull(container.get(Url("__liseur_fonts__/$other.ttf")!!))
    }

    @Test
    fun `the container is read afresh so an import lands mid-book`() {
        var current = emptyList<UserFont>()
        val container = UserFontsContainer { current }
        assertEquals(emptySet<Url>(), container.entries)
        current = fonts
        assertEquals(setOf(Url("__liseur_fonts__/$digest.ttf")!!), container.entries)
    }

    @Test
    fun `a book carrying the reserved path cannot shadow a font`() {
        // The composite puts this container first precisely because it is
        // registry-strict: it can only ever answer for a digest it holds,
        // so it shadows nothing, while the book left first would shadow
        // the reader's font.
        val container = UserFontsContainer { fonts }
        assertNull(container.get(Url("__liseur_fonts__/decoy.xhtml")!!))
        assertNull(container.get(Url("__liseur_fonts__/index.json")!!))
    }
}
