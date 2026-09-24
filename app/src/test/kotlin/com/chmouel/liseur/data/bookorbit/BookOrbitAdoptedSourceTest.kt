package com.chmouel.liseur.data.bookorbit

import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A remembered match stands in for a full read only while the file's size
 * and modification time are the ones it was read under, and only for a
 * while.
 *
 * Each test swaps the bytes for others of the same length and puts the
 * old time back, so the only way to tell them apart is to read the file.
 * A `true` after that swap shows the remembered match was used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BookOrbitAdoptedSourceTest {

    private lateinit var file: File
    private lateinit var uri: Uri
    private var clock = 1_000L
    private val source = BookOrbitAdoptedSource(context = null, now = { clock })
    private val expected = BookOrbitUploadClient.sha256Of("the book".byteInputStream())

    @Before
    fun write() {
        file = File.createTempFile("adopted", ".epub").apply {
            writeText("the book")
            setLastModified(1_700_000_000_000L)
        }
        uri = Uri.fromFile(file)
    }

    @After
    fun remove() {
        file.delete()
    }

    private fun swapKeepingStamp() {
        val modified = file.lastModified()
        file.writeText("the look")
        file.setLastModified(modified)
    }

    @Test
    fun `a match is reused while the stamp is unchanged`() = runBlocking {
        assertTrue(source.holds("book", uri, expected))
        swapKeepingStamp()
        clock += 60_000L

        assertTrue(source.holds("book", uri, expected))
    }

    @Test
    fun `a match expires and the file is read again`() = runBlocking {
        assertTrue(source.holds("book", uri, expected))
        swapKeepingStamp()
        clock += 5 * 60_000L

        assertFalse(source.holds("book", uri, expected))
    }

    @Test
    fun `a changed modification time reads the file again`() = runBlocking {
        assertTrue(source.holds("book", uri, expected))
        file.writeText("the look")
        file.setLastModified(1_700_000_060_000L)

        assertFalse(source.holds("book", uri, expected))
    }

    @Test
    fun `a changed size reads the file again`() = runBlocking {
        assertTrue(source.holds("book", uri, expected))
        file.writeText("a longer replacement")
        file.setLastModified(1_700_000_000_000L)

        assertFalse(source.holds("book", uri, expected))
    }

    @Test
    fun `a failed read forgets the earlier match`() = runBlocking {
        assertTrue(source.holds("book", uri, expected))
        file.writeText("the look")
        file.setLastModified(1_700_000_060_000L)
        assertFalse(source.holds("book", uri, expected))
        file.setLastModified(1_700_000_000_000L)

        assertFalse(source.holds("book", uri, expected))
    }

    @Test
    fun `another expected digest is not answered from the remembered one`() = runBlocking {
        assertTrue(source.holds("book", uri, expected))

        assertFalse(source.holds("book", uri, "0".repeat(64)))
    }
}
