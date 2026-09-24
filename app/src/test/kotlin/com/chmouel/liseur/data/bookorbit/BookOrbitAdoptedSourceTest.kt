package com.chmouel.liseur.data.bookorbit

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
    fun `a mismatch is remembered too while the stamp is unchanged`() = runBlocking {
        file.writeText("the look")
        file.setLastModified(1_700_000_000_000L)
        assertFalse(source.holds("book", uri, expected))
        file.writeText("the book")
        file.setLastModified(1_700_000_000_000L)

        assertFalse(source.holds("book", uri, expected))
        clock += 5 * 60_000L
        assertTrue(source.holds("book", uri, expected))
    }

    @Test
    fun `a mismatch under another stamp replaces the earlier match`() = runBlocking {
        assertTrue(source.holds("book", uri, expected))
        file.writeText("the look")
        file.setLastModified(1_700_000_060_000L)
        assertFalse(source.holds("book", uri, expected))
        file.setLastModified(1_700_000_000_000L)

        assertFalse(source.holds("book", uri, expected))
    }

    @Test
    fun `a document dated zero has no known modification time`() {
        Robolectric.buildContentProvider(Undated::class.java).create(UNDATED)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val stamp = BookOrbitAdoptedSource(context).stamp(Uri.parse("content://$UNDATED/document/one"))

        assertEquals(BookOrbitAdoptedSource.Stamp(size = 10L, modifiedAt = null), stamp)
    }

    /** A provider that knows the size and answers zero, "unknown", for the time. */
    class Undated : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            args: Array<out String>?, sort: String?,
        ): android.database.Cursor = android.database.MatrixCursor(
            arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
        ).apply { addRow(arrayOf<Any>(10L, 0L)) }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test
    fun `another expected digest is not answered from the remembered one`() = runBlocking {
        assertTrue(source.holds("book", uri, expected))

        assertFalse(source.holds("book", uri, "0".repeat(64)))
    }

    private companion object {
        const val UNDATED = "com.chmouel.liseur.test.undated"
    }
}
