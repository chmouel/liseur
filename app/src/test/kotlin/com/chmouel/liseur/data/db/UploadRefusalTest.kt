package com.chmouel.liseur.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a server would not take, against the real SQL.
 *
 * This table is the answer to a reader being asked to send the same two
 * books on every launch for weeks, so the questions worth asking of it
 * are all about when it stops applying: when the bytes change, when the
 * book goes, when the account does, and when the reader asks by hand.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class UploadRefusalTest {

    private lateinit var db: LiseurDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private val dao get() = db.uploadRefusalDao()

    private suspend fun add(url: String) = db.bookDao().upsert(
        Book(
            url = url,
            title = url,
            author = null,
            coverPath = null,
            source = null,
            addedAt = 0,
            lastOpenedAt = null,
        ),
    )

    private suspend fun fingerprint(url: String, sha: String) =
        db.workIdentityDao().upsert(
            BookFingerprintRow(
                bookUrl = url,
                sha256 = sha,
                partialMd5 = "md5",
                fileSize = 10,
                fileModifiedAt = null,
                computedAt = 0,
            ),
        )

    private suspend fun refuse(
        url: String,
        account: String,
        sha: String? = "aa",
        kind: String = UploadRefusal.SERVER_REFUSED,
        at: Long = 1,
    ) = dao.upsert(
        UploadRefusal(
            bookUrl = url,
            accountKey = account,
            refusedAt = at,
            kind = kind,
            reason = "not an epub",
            contentSha256 = sha,
        ),
    )

    private suspend fun suppressed(account: String) =
        dao.observeFor(account).first().filter { it.stillApplies }.map { it.bookUrl }

    @Test
    fun `a refusal applies while the file still hashes to what was refused`() = runTest {
        add("file:///a")
        fingerprint("file:///a", "aa")
        refuse("file:///a", "sync|me")

        assertEquals(listOf("file:///a"), suppressed("sync|me"))
    }

    /**
     * The invalidation, and the reason it needs no code anywhere else: a
     * reader who replaces a book with a copy the server does like is
     * offered it again because the digest stopped matching, not because
     * something remembered to clear a row.
     */
    @Test
    fun `a refusal stops applying once the bytes change`() = runTest {
        add("file:///a")
        fingerprint("file:///a", "aa")
        refuse("file:///a", "sync|me")

        fingerprint("file:///a", "bb")

        assertTrue(suppressed("sync|me").isEmpty())
    }

    /**
     * A book whose bytes nobody has hashed is offered. Re-offering is a
     * cheap mistake; withholding a book the reader could have sent, for
     * a reason never shown, is the expensive one.
     */
    @Test
    fun `a book with no fingerprint yet is still offered`() = runTest {
        add("file:///a")
        refuse("file:///a", "sync|me")

        assertTrue(suppressed("sync|me").isEmpty())
    }

    /** A refusal that never got as far as bytes suppresses nothing. */
    @Test
    fun `an unreadable file does not suppress the offer`() = runTest {
        add("file:///a")
        fingerprint("file:///a", "aa")
        refuse("file:///a", "sync|me", sha = null, kind = UploadRefusal.FILE_UNREADABLE)

        assertTrue(suppressed("sync|me").isEmpty())
    }

    /** One server's opinion. The next may want the book. */
    @Test
    fun `another account is still offered the same book`() = runTest {
        add("file:///a")
        fingerprint("file:///a", "aa")
        refuse("file:///a", "sync|me")

        assertTrue(suppressed("sync|somebody-else").isEmpty())
        assertEquals(listOf("file:///a"), suppressed("sync|me"))
    }

    @Test
    fun `asking by hand clears only this account's refusal`() = runTest {
        add("file:///a")
        fingerprint("file:///a", "aa")
        refuse("file:///a", "sync|me")
        refuse("file:///a", "sync|them")

        dao.clear("file:///a", "sync|me")

        assertNull(dao.get("file:///a", "sync|me"))
        assertNotNull(dao.get("file:///a", "sync|them"))
    }

    @Test
    fun `disconnecting forgets what that account refused`() = runTest {
        add("file:///a")
        add("file:///b")
        refuse("file:///a", "sync|me")
        refuse("file:///b", "sync|me")
        refuse("file:///a", "sync|them")

        dao.clearAccount("sync|me")

        assertNull(dao.get("file:///a", "sync|me"))
        assertNull(dao.get("file:///b", "sync|me"))
        assertNotNull(dao.get("file:///a", "sync|them"))
    }

    /**
     * Deleting a book and importing the same file again must be a clean
     * slate, or a reader who removed a book to fix it would find it
     * still un-sendable and be told nothing.
     */
    @Test
    fun `deleting a book and importing it again forgets the refusal`() = runTest {
        add("file:///a")
        fingerprint("file:///a", "aa")
        refuse("file:///a", "sync|me")

        db.bookDao().deleteByUrls(listOf("file:///a"))
        add("file:///a")
        fingerprint("file:///a", "aa")

        assertNull(dao.get("file:///a", "sync|me"))
        assertTrue(suppressed("sync|me").isEmpty())
    }

    /**
     * A second refusal landing while the first snackbar is up must not
     * be marked read by it, or the reader is never told why the second
     * book stopped being offered.
     */
    @Test
    fun `marking one refusal seen leaves a newer one unseen`() = runTest {
        add("file:///a")
        refuse("file:///a", "sync|me", at = 1)

        refuse("file:///a", "sync|me", at = 2, kind = UploadRefusal.TOO_LARGE)
        dao.markSeen("file:///a", "sync|me", refusedAt = 1, kind = UploadRefusal.SERVER_REFUSED, seenAt = 5)

        assertEquals(1, dao.observeUnseen("sync|me").first().size)

        dao.markSeen("file:///a", "sync|me", refusedAt = 2, kind = UploadRefusal.TOO_LARGE, seenAt = 6)

        assertTrue(dao.observeUnseen("sync|me").first().isEmpty())
    }
}
