package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.data.remote.SyncPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the manual sync button decides to do about two positions.
 *
 * Worth its own test because it is the one place in sync that
 * deliberately does *not* take the further side, and the rule that says
 * so has to be readable on its own — without a navigator, a database or
 * a server anywhere near it.
 */
class BookSyncChoiceTest {

    @Test
    fun `no remote position is nothing to sync with`() {
        val verdict = BookSyncChoice.decide(SyncPreview(local = 0.4, remote = null, remoteAt = null))

        assertEquals(BookSyncVerdict.NoRemote, verdict)
    }

    @Test
    fun `both sides in the same place ask nothing`() {
        val verdict = BookSyncChoice.decide(SyncPreview(local = 0.40, remote = 0.401, remoteAt = 1))

        assertEquals(BookSyncVerdict.InStep, verdict)
    }

    @Test
    fun `an exact anchor agreeing is agreement whatever the percentages say`() {
        val verdict = BookSyncChoice.decide(
            SyncPreview(local = 0.4, remote = 0.6, remoteAt = 1, exactPositionAgreement = true),
        )

        assertEquals(BookSyncVerdict.InStep, verdict)
    }

    @Test
    fun `nothing on this device is taken rather than asked about`() {
        // Offering "keep this device's position" here would be offering a
        // position that does not exist.
        val verdict = BookSyncChoice.decide(SyncPreview(local = null, remote = 0.4, remoteAt = 1))

        assertEquals(BookSyncVerdict.NoLocal, verdict)
    }

    @Test
    fun `an answer nobody wrote down is not offered as a choice`() {
        // liseur-sync's newest op is this device's own last push. The
        // server is behind on what it is already owed, and there is no
        // other reader's position to adopt — a "go to the server's
        // position" button here would do nothing at all.
        val verdict = BookSyncChoice.decide(
            SyncPreview(local = 0.6, remote = 0.2, remoteAt = 1, resolvable = false),
        )

        assertEquals(BookSyncVerdict.Owed, verdict)
    }

    @Test
    fun `agreement still comes first when nothing was written down`() {
        val verdict = BookSyncChoice.decide(
            SyncPreview(local = 0.4, remote = 0.4, remoteAt = 1, resolvable = false),
        )

        assertEquals(BookSyncVerdict.InStep, verdict)
    }

    @Test
    fun `the server having read further is asked about`() {
        val verdict = BookSyncChoice.decide(SyncPreview(local = 0.2, remote = 0.6, remoteAt = 1))

        assertEquals(BookSyncVerdict.Ask(SyncRelation.AHEAD), verdict)
    }

    @Test
    fun `the server being behind is asked about too`() {
        // The case the button exists for. An ordinary sync says nothing
        // about it, and it is exactly what a reread produces.
        val verdict = BookSyncChoice.decide(SyncPreview(local = 0.6, remote = 0.2, remoteAt = 1))

        assertEquals(BookSyncVerdict.Ask(SyncRelation.BEHIND), verdict)
    }

    @Test
    fun `the same page in two different spots says so`() {
        val verdict = BookSyncChoice.decide(
            SyncPreview(
                local = 0.4,
                remote = 0.4,
                remoteAt = 1,
                exactPositionAgreement = false,
            ),
        )

        // Two identical page numbers over two buttons is a riddle; this
        // is the case that owns up to it in words.
        assertEquals(BookSyncVerdict.Ask(SyncRelation.SAME_PAGE), verdict)
    }

    @Test
    fun `a percentage-only partner still gets a relation`() {
        val verdict = BookSyncChoice.decide(
            SyncPreview(
                local = 0.2,
                remote = 0.6,
                remoteAt = 1,
                exactPositionAgreement = null,
                confidence = ResumeConfidence.APPROXIMATE,
            ),
        )

        assertEquals(BookSyncVerdict.Ask(SyncRelation.AHEAD), verdict)
    }

    @Test
    fun `nothing on either side is agreement, not a question`() {
        val verdict = BookSyncChoice.decide(SyncPreview(local = null, remote = null, remoteAt = null))

        assertEquals(BookSyncVerdict.NoRemote, verdict)
    }

    private val answer = SyncPreview(
        local = 0.2,
        remote = 0.6,
        remoteAt = 1_000,
        peerId = "catalog",
        accountKey = "https://books.example|alice",
        remoteStatus = "reading",
        remoteLocatorJson = """{"href":"/ch3.xhtml"}""",
    )

    @Test
    fun `the same answer fingerprints the same`() {
        assertTrue(answer.fingerprint().matches(answer.copy().fingerprint()))
    }

    @Test
    fun `a percentage that survived a round trip is still the same answer`() {
        val rounded = answer.copy(remote = 0.6001)

        assertTrue(answer.fingerprint().matches(rounded.fingerprint()))
    }

    @Test
    fun `a moved position is a different answer`() {
        assertFalse(answer.fingerprint().matches(answer.copy(remote = 0.7).fingerprint()))
    }

    @Test
    fun `a different anchor at the same percentage is a different answer`() {
        // The whole reason a progression will not do: another device
        // rereading the same page lands on the same percentage.
        val elsewhere = answer.copy(remoteLocatorJson = """{"href":"/ch9.xhtml"}""")

        assertFalse(answer.fingerprint().matches(elsewhere.fingerprint()))
    }

    @Test
    fun `a status changing on its own is a different answer`() {
        assertFalse(answer.fingerprint().matches(answer.copy(remoteStatus = "finished").fingerprint()))
    }

    @Test
    fun `a newer server timestamp is a different answer`() {
        assertFalse(answer.fingerprint().matches(answer.copy(remoteAt = 2_000).fingerprint()))
    }

    @Test
    fun `another peer's identical position is a different answer`() {
        assertFalse(answer.fingerprint().matches(answer.copy(peerId = "kosync").fingerprint()))
    }

    @Test
    fun `the same login on another server is somebody else`() {
        val other = answer.copy(accountKey = "https://other.example|alice")

        assertFalse(answer.fingerprint().matches(other.fingerprint()))
    }

    @Test
    fun `a percentage-only partner fingerprints on what it knows`() {
        val bare = SyncPreview(
            local = 0.2,
            remote = 0.6,
            remoteAt = 1_000,
            peerId = "kosync",
            accountKey = "kosync",
        )

        assertTrue(bare.fingerprint().matches(bare.copy(excerpt = "anything").fingerprint()))
        assertFalse(bare.fingerprint().matches(bare.copy(remoteAt = 2_000).fingerprint()))
    }

    @Test
    fun `an anchor appearing where there was none is a different answer`() {
        val bare = answer.copy(remoteLocatorJson = null)

        assertFalse(bare.fingerprint().matches(answer.fingerprint()))
    }
}
