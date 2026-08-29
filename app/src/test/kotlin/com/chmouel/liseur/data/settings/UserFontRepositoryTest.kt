package com.chmouel.liseur.data.settings

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.settings.fonts.SfntFixtures
import com.chmouel.liseur.data.settings.fonts.UserFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * The store behind the reader's imported fonts.
 *
 * The interesting assertions here are the ones about failure. A font
 * arrives from a file picker, so every step of the way something can be
 * absent, truncated, enormous, or not a font at all, and none of it may
 * end with the published list disagreeing with what is actually on disk
 * — a list that names a file that is not there is a dropdown row that
 * blanks the page when it is tapped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class UserFontRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.filesDir, "fonts")
    private val index = File(dir, "index.json")

    private var next = 0

    @Before
    fun clean() {
        dir.deleteRecursively()
    }

    @After
    fun reset() {
        ShadowContentResolver.reset()
    }

    // -- the happy path -----------------------------------------------------

    @Test
    fun `an imported font is stored under its own digest`() = test { fonts ->
        val bytes = SfntFixtures.sfnt(names = listOf(SfntFixtures.name(1, "Fixture Text")))
        val result = fonts.import(uriFor(bytes), "whatever-they-called-it.ttf")

        val digest = sha256(bytes)
        assertEquals(ImportResult.Imported("user:$digest"), result)
        assertTrue(File(dir, "$digest.ttf").exists())
        assertEquals(listOf("Fixture Text"), fonts.fonts.value.map { it.displayName })
        assertEquals(setOf("user:$digest"), fonts.registry())
    }

    @Test
    fun `the extension comes from the magic and not from what was picked`() = test { fonts ->
        val bytes = SfntFixtures.sfnt(magic = SfntFixtures.OTTO)
        fonts.import(uriFor(bytes), "definitely-a.ttf")

        assertEquals("otf", fonts.fonts.value.single().extension)
        assertTrue(File(dir, "${sha256(bytes)}.otf").exists())
    }

    @Test
    fun `weight and italic are carried through for the declaration`() = test { fonts ->
        val bytes = SfntFixtures.sfnt(
            weightClass = 300,
            fsSelection = SfntFixtures.OS2_ITALIC,
            weightAxis = 200 to 900,
        )
        fonts.import(uriFor(bytes), null)

        val font = fonts.fonts.value.single()
        assertEquals(300, font.staticWeight)
        assertTrue(font.italic)
        assertEquals(200..900, font.weightRange)
    }

    @Test
    fun `fonts are listed in an order the reader can scan`() = test { fonts ->
        fonts.import(uriFor(named("Zeta")), null)
        fonts.import(uriFor(named("alpha")), null)

        assertEquals(listOf("alpha", "Zeta"), fonts.fonts.value.map { it.displayName })
    }

    // -- naming -------------------------------------------------------------

    @Test
    fun `a font with no name of its own takes the one it was picked under`() = test { fonts ->
        fonts.import(uriFor(SfntFixtures.sfnt(names = emptyList())), "OpenDyslexic-Regular.ttf")

        assertEquals("OpenDyslexic-Regular", fonts.fonts.value.single().displayName)
    }

    @Test
    fun `a nameless font picked from nowhere still has something to show`() = test { fonts ->
        val bytes = SfntFixtures.sfnt(names = emptyList())
        fonts.import(uriFor(bytes), null)

        val shown = fonts.fonts.value.single().displayName
        assertTrue(shown, shown.isNotBlank())
        assertTrue(shown, shown.contains(sha256(bytes).take(8)))
    }

    @Test
    fun `a hostile picked name is sanitised before it is ever stored`() = test { fonts ->
        fonts.import(uriFor(SfntFixtures.sfnt(names = emptyList())), "Bo\u202Eslim.ttf\u202C")

        val shown = fonts.fonts.value.single().displayName
        assertFalse(shown, shown.any { it == '\u202E' || it == '\u202C' })
    }

    @Test
    fun `the picked name of a nameless font survives a restart`() = test { fonts ->
        // The one thing a file cannot tell us twice, which is the whole
        // reason there is an index at all.
        fonts.import(uriFor(SfntFixtures.sfnt(names = emptyList())), "OpenDyslexic.ttf")

        assertEquals("OpenDyslexic", reopened().fonts.value.single().displayName)
    }

    // -- refusals -----------------------------------------------------------

    @Test
    fun `something that is not a font is refused`() = test { fonts ->
        val result = fonts.import(uriFor(ByteArray(4096) { (it * 31 % 251).toByte() }), "x.ttf")

        assertEquals(ImportResult.NotAFont, result)
        assertEquals(emptyList<UserFont>(), fonts.fonts.value)
    }

    @Test
    fun `an empty file is refused`() = test { fonts ->
        assertEquals(ImportResult.NotAFont, fonts.import(uriFor(ByteArray(0)), "empty.ttf"))
    }

    @Test
    fun `a font the platform cannot load is refused before it reaches the preview`() {
        // Plausible sfnt tables are not the same thing as a face Android
        // can render, and the dropdown's preview has nowhere to put a
        // throw.
        test(loadCheck = { false }) { fonts ->
            assertEquals(ImportResult.NotAFont, fonts.import(uriFor(SfntFixtures.sfnt()), null))
            assertEquals(emptyList<UserFont>(), fonts.fonts.value)
        }
    }

    @Test
    fun `a provider that dies mid-read is reported rather than swallowed`() = test { fonts ->
        // The grant can be withdrawn, or the file removed, between the
        // tap and the read. There is nowhere for that to surface except
        // a result the caller can show.
        val uri = supplying {
            object : InputStream() {
                override fun read() = throw IOException("provider went away")
            }
        }
        assertEquals(ImportResult.Unreadable, fonts.import(uri, "gone.ttf"))
        assertEquals(emptyList<String>(), tempFiles())
    }

    @Test
    fun `an enormous file is abandoned rather than copied in whole`() = test { fonts ->
        val huge = ByteArray(17 * 1024 * 1024)
        SfntFixtures.sfnt().copyInto(huge)

        assertEquals(ImportResult.TooLarge, fonts.import(uriFor(huge), "video.ttf"))
        assertEquals(emptyList<UserFont>(), fonts.fonts.value)
    }

    @Test
    fun `nothing refused leaves a temp file behind`() = test { fonts ->
        fonts.import(uriFor(ByteArray(4096)), "junk.ttf")
        fonts.import(uriFor(ByteArray(17 * 1024 * 1024)), "huge.ttf")

        assertEquals(emptyList<String>(), tempFiles())
    }

    // -- deduping -----------------------------------------------------------

    @Test
    fun `the same bytes picked twice are one font`() = test { fonts ->
        val bytes = SfntFixtures.sfnt()
        val first = fonts.import(uriFor(bytes), "Serif.ttf")
        val again = fonts.import(uriFor(bytes), "A copy of Serif.TTF")

        assertEquals(ImportResult.Imported("user:${sha256(bytes)}"), first)
        assertEquals(ImportResult.AlreadyPresent("user:${sha256(bytes)}"), again)
        assertEquals(1, fonts.fonts.value.size)
    }

    @Test
    fun `re-picking a font is never refused for a limit it does not push against`() =
        test { fonts ->
            // Dedupe is checked before the cap, deliberately. Otherwise a
            // full shelf turns "tap the font you already have" into
            // "you have too many fonts", which is nonsense.
            repeat(32) { fonts.import(uriFor(named("Filler $it")), null) }
            assertEquals(32, fonts.fonts.value.size)

            val existing = named("Filler 0")
            assertEquals(
                ImportResult.AlreadyPresent("user:${sha256(existing)}"),
                fonts.import(uriFor(existing), null),
            )
            assertEquals(ImportResult.TooMany, fonts.import(uriFor(named("One more")), null))
        }

    // -- removal ------------------------------------------------------------

    @Test
    fun `removing a font takes the file and the name with it`() = test { fonts ->
        val bytes = named("Doomed")
        fonts.import(uriFor(bytes), null)
        val id = "user:${sha256(bytes)}"

        assertEquals(RemovalResult.Removed, fonts.remove(id))
        assertEquals(emptyList<UserFont>(), fonts.fonts.value)
        assertFalse(File(dir, "${sha256(bytes)}.ttf").exists())
        assertFalse(index.readText().contains(id))
    }

    @Test
    fun `an id that was never a font is refused without touching anything`() = test { fonts ->
        fonts.import(uriFor(named("Kept")), null)

        assertEquals(RemovalResult.InvalidId, fonts.remove("literata"))
        assertEquals(RemovalResult.InvalidId, fonts.remove("user:../../databases/liseur.db"))
        assertEquals(RemovalResult.InvalidId, fonts.remove("user:nothexatall"))
        assertEquals(RemovalResult.NotFound, fonts.remove("user:" + "f".repeat(64)))
        assertEquals(1, fonts.fonts.value.size)
    }

    // -- what survives a restart --------------------------------------------

    @Test
    fun `the shelf comes back`() = test { fonts ->
        fonts.import(uriFor(named("One")), null)
        fonts.import(uriFor(named("Two")), null)

        assertEquals(listOf("One", "Two"), reopened().fonts.value.map { it.displayName })
    }

    @Test
    fun `a corrupt index costs a name and never a font`() = test { fonts ->
        fonts.import(uriFor(SfntFixtures.sfnt(names = emptyList())), "Picked Name.ttf")
        index.writeText("{ this is not json")

        val recovered = reopened().fonts.value.single()
        assertTrue(recovered.file.exists())
        assertTrue(recovered.displayName, recovered.displayName.isNotBlank())
    }

    @Test
    fun `a missing index costs a name and never a font`() = test { fonts ->
        fonts.import(uriFor(named("Named In Its Tables")), null)
        assertTrue(index.delete())

        // The name is in the font's own tables, so this one loses nothing.
        assertEquals("Named In Its Tables", reopened().fonts.value.single().displayName)
    }

    @Test
    fun `an index naming a font that is gone does not conjure it back`() = test { fonts ->
        fonts.import(uriFor(named("Deleted Behind Our Back")), null)
        dir.listFiles()!!.single { it.name.endsWith(".ttf") }.delete()

        assertEquals(emptyList<UserFont>(), reopened().fonts.value)
    }

    @Test
    fun `a staged import that died before its rename is swept`() = test { _ ->
        dir.mkdirs()
        File(dir, "orphan.tmp").writeBytes(SfntFixtures.sfnt())

        assertEquals(emptyList<UserFont>(), reopened().fonts.value)
        assertEquals(emptyList<String>(), tempFiles())
    }

    @Test
    fun `a file Liseur did not write is ignored`() = test { _ ->
        dir.mkdirs()
        // Not a digest.
        File(dir, "MyFavourite.ttf").writeBytes(SfntFixtures.sfnt())
        // Not lowercase hex of the right length.
        File(dir, "${"A".repeat(64)}.ttf").writeBytes(SfntFixtures.sfnt())
        File(dir, "${"a".repeat(63)}.ttf").writeBytes(SfntFixtures.sfnt())
        // A name Liseur could have written, over bytes that disagree with it.
        File(dir, "${"a".repeat(64)}.ttf").writeBytes(SfntFixtures.sfnt(magic = SfntFixtures.OTTO))
        File(dir, "${"b".repeat(64)}.ttf").writeBytes(ByteArray(64))

        assertEquals(emptyList<UserFont>(), reopened().fonts.value)
    }

    // -- the list never lies about the directory ----------------------------

    @Test
    fun `a failed index write still publishes the font that landed`() = test { fonts ->
        // The file is there. Whatever happened to the index, the list has
        // to say so, because the alternative is a font the reader can see
        // in their storage and not in the app.
        val bytes = named("Landed")
        blockIndexWrites()

        val result = fonts.import(uriFor(bytes), null)

        assertEquals(ImportResult.Imported("user:${sha256(bytes)}"), result)
        assertEquals(listOf("Landed"), fonts.fonts.value.map { it.displayName })
    }

    @Test
    fun `a failed index write on removal still publishes the font that went`() = test { fonts ->
        val bytes = named("Going")
        fonts.import(uriFor(bytes), null)
        blockIndexWrites()

        assertEquals(RemovalResult.IndexFailed, fonts.remove("user:${sha256(bytes)}"))
        assertEquals(emptyList<UserFont>(), fonts.fonts.value)
        assertFalse(File(dir, "${sha256(bytes)}.ttf").exists())
    }

    @Test
    fun `a font that has become unreadable costs itself and not the shelf`() = test { fonts ->
        fonts.import(uriFor(named("Readable")), null)
        val gone = named("Unreadable")
        dir.mkdirs()
        // A directory where a file should be: what a half-restored backup
        // or a detached volume can leave behind. `init` is where this is
        // read, so a throw would cost the whole shelf, permanently.
        File(dir, "${sha256(gone)}.ttf").mkdirs()

        assertEquals(listOf("Readable"), reopened().fonts.value.map { it.displayName })
    }

    // -- ordering -----------------------------------------------------------

    @Test
    fun `the opening scan cannot land on top of a later import`() = runTest {
        // Without the lock covering the scan, a reader quick enough to
        // import before it finished would watch the font disappear.
        val bytes = named("Raced")
        val fonts = UserFontRepository(context, this, loadCheck = { true })

        val result = fonts.import(uriFor(bytes), null)
        advanceUntilIdle()

        assertEquals(ImportResult.Imported("user:${sha256(bytes)}"), result)
        assertEquals(listOf("Raced"), fonts.fonts.value.map { it.displayName })
    }

    @Test
    fun `awaitReady returns once the shelf is published`() = runTest {
        dir.mkdirs()
        val bytes = named("Already There")
        File(dir, "${sha256(bytes)}.ttf").writeBytes(bytes)

        val fonts = UserFontRepository(context, this, loadCheck = { true })
        fonts.awaitReady()

        assertEquals(listOf("Already There"), fonts.fonts.value.map { it.displayName })
    }

    // -- plumbing -----------------------------------------------------------

    private fun test(
        loadCheck: (File) -> Boolean = { true },
        body: suspend TestScope.(UserFontRepository) -> Unit,
    ) = runTest(StandardTestDispatcher()) {
        val fonts = UserFontRepository(context, this, loadCheck = loadCheck)
        fonts.awaitReady()
        body(fonts)
    }

    /** A second repository over the same directory, standing in for a restart. */
    private suspend fun TestScope.reopened(): UserFontRepository =
        UserFontRepository(context, this, loadCheck = { true }).also { it.awaitReady() }

    /**
     * Makes the next index write fail, without touching the font files.
     *
     * `AtomicFile` stages into `<base>.new`; a non-empty directory there
     * is something it can neither open nor clear, and its own retry
     * (`parent.mkdirs()`) cannot help because the parent already exists.
     */
    private fun blockIndexWrites() {
        dir.mkdirs()
        File(dir, "index.json.new").mkdirs()
        File(dir, "index.json.new/occupied").writeText("x")
    }

    /** A font whose own `name` table says [family]. */
    private fun named(family: String) =
        SfntFixtures.sfnt(names = listOf(SfntFixtures.name(1, family)))

    private fun uriFor(bytes: ByteArray): Uri = supplying { ByteArrayInputStream(bytes) }

    private fun supplying(stream: () -> InputStream): Uri {
        val uri = Uri.parse("content://test/${next++}")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri, stream)
        return uri
    }

    private fun tempFiles() =
        dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
