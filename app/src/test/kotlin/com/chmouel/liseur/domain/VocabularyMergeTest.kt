package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.VocabularyWordSync
import com.chmouel.liseur.domain.VocabularyMerge.Baseline
import com.chmouel.liseur.domain.VocabularyMerge.Occurrence
import com.chmouel.liseur.domain.VocabularyMerge.RemoteWordAction
import com.chmouel.liseur.domain.VocabularyMerge.ReviewEvent
import com.chmouel.liseur.domain.VocabularyMerge.WordMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyMergeTest {

    @Test
    fun `F3 delete during first pending UPSERT records LOCAL_DELETE_PENDING and survives UPSERT ack`() {
        val inFlightCreate = VocabularyWordSync(
            peerId = "p1",
            language = "en",
            normalized = "ephemeral",
            rev = 0,
            generation = 0,
            pendingJson = """{"word":"Ephemeral"}""",
            pendingRev = 0,
            pendingFingerprint = "fp-upsert",
            pendingBaseJson = """{"base":1}""",
            pendingIntent = VocabularyWordSync.INTENT_UPSERT,
            pendingWordId = 42L,
            refusedFingerprint = "old-refused",
            refusedReason = "old-reason",
        )
        val afterDelete = VocabularyMerge.onLocalDelete(inFlightCreate)!!
        assertEquals(VocabularyWordSync.LOCAL_DELETE_PENDING, afterDelete.lifecycle)
        assertNull(afterDelete.syncedWordId)

        val afterUpsertAck = VocabularyMerge.onPushWordAck(
            sync = afterDelete,
            resRev = 1L,
            resGeneration = 1L,
            resSeq = 5L,
        )
        assertEquals(VocabularyWordSync.LOCAL_DELETE_PENDING, afterUpsertAck.lifecycle)
        assertNull(afterUpsertAck.syncedWordId)
        assertEquals(1L, afterUpsertAck.rev)
        assertEquals(1L, afterUpsertAck.generation)
        assertEquals(5L, afterUpsertAck.seq)
        assertEquals("fp-upsert", afterUpsertAck.ackedFingerprint)
        assertEquals("""{"base":1}""", afterUpsertAck.ackedBaseJson)
        assertNull(afterUpsertAck.pendingJson)
        assertNull(afterUpsertAck.pendingRev)
        assertNull(afterUpsertAck.pendingFingerprint)
        assertNull(afterUpsertAck.pendingBaseJson)
        assertNull(afterUpsertAck.pendingIntent)
        assertNull(afterUpsertAck.pendingWordId)
        assertNull(afterUpsertAck.refusedFingerprint)
        assertNull(afterUpsertAck.refusedReason)
    }

    @Test
    fun `F3 delete and recollect during pending UPSERT preserves DELETE_THEN_RESURRECT on UPSERT ack`() {
        val inFlightUpsert = VocabularyWordSync(
            peerId = "p1",
            language = "en",
            normalized = "ephemeral",
            rev = 1,
            generation = 1,
            syncedWordId = 42L,
            pendingJson = """{"word":"Ephemeral"}""",
            pendingRev = 1,
            pendingFingerprint = "fp-upsert-2",
            pendingBaseJson = """{"base":2}""",
            pendingIntent = VocabularyWordSync.INTENT_UPSERT,
            pendingWordId = 42L,
        )
        val afterDelete = VocabularyMerge.onLocalDelete(inFlightUpsert)!!
        val afterRecollect = VocabularyMerge.onLocalCollect(afterDelete)!!
        assertEquals(VocabularyWordSync.DELETE_THEN_RESURRECT, afterRecollect.lifecycle)
        assertNull(afterRecollect.syncedWordId)

        val afterUpsertAck = VocabularyMerge.onPushWordAck(
            sync = afterRecollect,
            resRev = 2L,
            resGeneration = 1L,
            resSeq = 8L,
        )
        assertEquals(VocabularyWordSync.DELETE_THEN_RESURRECT, afterUpsertAck.lifecycle)
        assertNull(afterUpsertAck.syncedWordId)
        assertEquals(2L, afterUpsertAck.rev)
        assertEquals(1L, afterUpsertAck.generation)
        assertEquals(8L, afterUpsertAck.seq)
        assertEquals("fp-upsert-2", afterUpsertAck.ackedFingerprint)
        assertEquals("""{"base":2}""", afterUpsertAck.ackedBaseJson)
        assertNull(afterUpsertAck.pendingJson)
        assertNull(afterUpsertAck.pendingIntent)
        assertNull(afterUpsertAck.pendingWordId)
    }

    @Test
    fun `F3 delete then recollect ack transitions to RESURRECT_PENDING and ignores own tombstone`() {
        val synced = VocabularyWordSync(
            peerId = "p1",
            language = "en",
            normalized = "serendipity",
            rev = 2,
            generation = 1,
            syncedWordId = 10L,
            lifecycle = VocabularyWordSync.SYNCED,
            ackedFingerprint = "fp1",
        )
        val deleted = VocabularyMerge.onLocalDelete(synced)!!
        assertEquals(VocabularyWordSync.LOCAL_DELETE_PENDING, deleted.lifecycle)

        val recollected = VocabularyMerge.onLocalCollect(deleted)!!
        assertEquals(VocabularyWordSync.DELETE_THEN_RESURRECT, recollected.lifecycle)

        val deleting = recollected.copy(pendingIntent = VocabularyWordSync.INTENT_DELETE, pendingRev = 2)
        val afterDeleteAck = VocabularyMerge.onPushWordAck(deleting, resRev = 3, resGeneration = 1, resSeq = 9)
        assertEquals(VocabularyWordSync.RESURRECT_PENDING, afterDeleteAck.lifecycle)
        assertEquals(VocabularyWordSync.FINGERPRINT_DELETED, afterDeleteAck.ackedFingerprint)
        assertNull(afterDeleteAck.syncedWordId)

        // Pulling our own tombstone (gen 1 <= sync.gen 1) must be ignored, not delete the new word.
        assertEquals(
            RemoteWordAction.IGNORE_OWN_TOMBSTONE,
            VocabularyMerge.decideRemoteWord(
                remoteDeleted = true,
                remoteGeneration = 1,
                sync = afterDeleteAck,
                localWordId = 20L,
            ),
        )

        // Once the resurrection UPSERT is acked, lifecycle returns to SYNCED bound to new local word id.
        val resurrecting = afterDeleteAck.copy(
            pendingIntent = VocabularyWordSync.INTENT_UPSERT,
            pendingFingerprint = "fp2",
            pendingWordId = 20L,
        )
        val afterResurrectAck = VocabularyMerge.onPushWordAck(resurrecting, resRev = 4, resGeneration = 2, resSeq = 10)
        assertEquals(VocabularyWordSync.SYNCED, afterResurrectAck.lifecycle)
        assertEquals(2L, afterResurrectAck.generation)
        assertEquals(20L, afterResurrectAck.syncedWordId)
    }

    @Test
    fun `F11 generation replacement requires confirmed live incarnation binding`() {
        // Cached tombstone (syncedWordId == null) with positive generation must NOT replace unconfirmed local word.
        val cachedTombstone = VocabularyWordSync(
            peerId = "p1",
            language = "en",
            normalized = "labyrinth",
            rev = 3,
            generation = 1,
            syncedWordId = null,
            ackedFingerprint = VocabularyWordSync.FINGERPRINT_DELETED,
        )
        assertEquals(
            RemoteWordAction.MERGE_WITH_REMOTE,
            VocabularyMerge.decideRemoteWord(
                remoteDeleted = false,
                remoteGeneration = 2,
                sync = cachedTombstone,
                localWordId = 99L,
            ),
        )

        // Confirmed live incarnation (syncedWordId == localWordId) DOES replace when remote generation advanced.
        val confirmedLive = cachedTombstone.copy(syncedWordId = 99L, ackedFingerprint = "fp1")
        assertEquals(
            RemoteWordAction.REPLACE_LOCAL_WITH_REMOTE,
            VocabularyMerge.decideRemoteWord(
                remoteDeleted = false,
                remoteGeneration = 2,
                sync = confirmedLive,
                localWordId = 99L,
            ),
        )
    }

    @Test
    fun `F7 wireLocationKey hashes overlong keys and mergeOccurrences deduplicates provisional and canonical`() {
        val longLoc = "chapter/".repeat(30) // > 128 bytes
        val wire = VocabularyMerge.wireLocationKey(longLoc)
        assertTrue(wire.toByteArray(Charsets.UTF_8).size <= 128)
        assertNotEquals(longLoc, wire)

        val provKey = VocabularyMerge.provisionalOccurrenceKey("file:///a.epub", "Dune", longLoc)
        val canonKey = VocabularyMerge.canonicalOccurrenceKey("work-1", longLoc)
        assertNotEquals(provKey, canonKey)

        val local = listOf(
            Occurrence(
                occurrenceKey = provKey,
                workId = "work-1",
                bookTitle = "Dune",
                locationKey = wire,
                locator = """{"href":"ch1"}""",
                sentence = "Local edited sentence",
                sentenceEdited = true,
                sentenceUpdatedAt = 200L,
                lastSeenAt = 300L,
                encounters = 3,
            ),
        )
        val remote = listOf(
            Occurrence(
                occurrenceKey = canonKey,
                workId = "work-1",
                bookTitle = "Dune",
                locationKey = wire,
                locator = null,
                sentence = "Auto sentence",
                sentenceEdited = false,
                sentenceUpdatedAt = 100L,
                lastSeenAt = 250L,
                encounters = 2,
            ),
        )
        val merged = VocabularyMerge.mergeOccurrences(emptyList(), local, remote)
        assertEquals(1, merged.size)
        assertEquals(canonKey, merged[0].occurrenceKey)
        assertEquals("Local edited sentence", merged[0].sentence)
        assertEquals(true, merged[0].sentenceEdited)
        assertEquals(3, merged[0].encounters)
        assertEquals(300L, merged[0].lastSeenAt)
        assertEquals("""{"href":"ch1"}""", merged[0].locator)
    }

    @Test
    fun `3-way word metadata merge preserves non-conflicting edits and tie-breaks deterministically`() {
        val base = WordMetadata(
            word = "Ephemeral",
            definition = "short-lived",
            translationLanguage = null,
            notes = "old note",
            definitionEdited = false,
            definitionUpdatedAt = 100L,
            notesUpdatedAt = 100L,
            addedAt = 50L,
            baseline = null,
        )
        val local = base.copy(notes = "local note", notesUpdatedAt = 200L, addedAt = 40L)
        val remote = base.copy(
            definition = "lasting a very short time",
            definitionEdited = true,
            definitionUpdatedAt = 150L,
        )
        val merged = VocabularyMerge.mergeWordMetadata(base, local, remote)
        assertEquals("lasting a very short time", merged.definition)
        assertEquals(true, merged.definitionEdited)
        assertEquals("local note", merged.notes)
        assertEquals(40L, merged.addedAt)
    }

    @Test
    fun `initial merge with null base honors newer cleared notes timestamp`() {
        val local = WordMetadata(
            word = "Ephemeral",
            definition = "short-lived",
            translationLanguage = null,
            notes = "",
            definitionEdited = false,
            definitionUpdatedAt = 100L,
            notesUpdatedAt = 300L,
            addedAt = 50L,
            baseline = null,
        )
        val remote = local.copy(notes = "old note", notesUpdatedAt = 100L)
        val merged = VocabularyMerge.mergeWordMetadata(base = null, local = local, remote = remote)
        assertEquals("", merged.notes)
        assertEquals(300L, merged.notesUpdatedAt)
    }

    @Test
    fun `equal-timestamp notes and definitions converge regardless of which side is remote`() {
        val a = WordMetadata(
            word = "Ephemeral",
            definition = "short-lived",
            translationLanguage = null,
            notes = "note from A",
            definitionEdited = true,
            definitionUpdatedAt = 100L,
            notesUpdatedAt = 200L,
            addedAt = 50L,
            baseline = null,
        )
        val b = a.copy(notes = "note from B", definition = "fleeting")
        val base = a.copy(notes = "old", definition = "old def", definitionEdited = false)
        for (b0 in listOf(null, base)) {
            val onA = VocabularyMerge.mergeWordMetadata(b0, local = a, remote = b)
            val onB = VocabularyMerge.mergeWordMetadata(b0, local = b, remote = a)
            assertEquals(onA, onB)
            assertEquals("note from B", onA.notes)
            assertEquals("short-lived", onA.definition)
        }
        val newer = b.copy(notes = "aaa", notesUpdatedAt = 300L)
        assertEquals("aaa", VocabularyMerge.mergeWordMetadata(null, a, newer).notes)
        assertEquals("aaa", VocabularyMerge.mergeWordMetadata(null, newer, a).notes)
    }

    @Test
    fun `equal-timestamp sentences and differing locators converge in both arrival orders`() {
        val a = Occurrence(
            occurrenceKey = "k1",
            workId = "w1",
            bookTitle = "Dune",
            locationKey = "ch1",
            locator = """{"href":"a"}""",
            sentence = "Sentence from A",
            sentenceEdited = true,
            sentenceUpdatedAt = 500L,
            lastSeenAt = 100L,
            encounters = 1,
        )
        val b = a.copy(locator = """{"href":"b"}""", sentence = "Sentence from B")
        val base = a.copy(sentence = "orig", sentenceEdited = false, sentenceUpdatedAt = 0L)
        for (b0 in listOf(null, base)) {
            val onA = VocabularyMerge.mergeOccurrencePair(b0, local = a, remote = b)
            val onB = VocabularyMerge.mergeOccurrencePair(b0, local = b, remote = a)
            assertEquals(onA, onB)
            assertEquals("Sentence from B", onA.sentence)
            assertEquals("""{"href":"b"}""", onA.locator)
        }
        val newer = a.copy(sentence = "aaa", sentenceUpdatedAt = 600L)
        assertEquals("aaa", VocabularyMerge.mergeOccurrencePair(null, b, newer).sentence)
        assertEquals("aaa", VocabularyMerge.mergeOccurrencePair(null, newer, b).sentence)
        assertEquals(a.locator, VocabularyMerge.mergeOccurrencePair(null, a, a.copy(locator = null)).locator)
    }

    @Test
    fun `mergeOccurrencePair adopts remote-only bookTitle change and converges on concurrent edits`() {
        val base = Occurrence(
            occurrenceKey = "k1",
            workId = "w1",
            bookTitle = "Old Title",
            locationKey = "ch1",
            locator = null,
            sentence = "S",
            sentenceEdited = false,
            sentenceUpdatedAt = 0L,
            lastSeenAt = 100L,
            encounters = 1,
        )
        val remoteRenamed = base.copy(bookTitle = "New Title")
        assertEquals("New Title", VocabularyMerge.mergeOccurrencePair(base, base, remoteRenamed).bookTitle)
        val localRenamed = base.copy(bookTitle = "Local Title")
        assertEquals("Local Title", VocabularyMerge.mergeOccurrencePair(base, localRenamed, base).bookTitle)
        // Concurrent edits deterministically pick remote so both peers converge.
        assertEquals("New Title", VocabularyMerge.mergeOccurrencePair(base, localRenamed, remoteRenamed).bookTitle)
    }

    @Test
    fun `reconcileSchedule folds offline reviews deterministically and respects baseline`() {
        val reviews = listOf(
            ReviewEvent("r2", "EASY", reviewedAt = 2_000L, intervalMinutes = 0, dueAt = 0),
            ReviewEvent("r1", "GOOD", reviewedAt = 1_000L, intervalMinutes = 0, dueAt = 0),
        )
        val noBaseline = VocabularyMerge.reconcileSchedule(addedAt = 500L, baseline = null, reviews = reviews)
        assertEquals(2, noBaseline.repetitions)
        assertEquals(3, noBaseline.streak) // GOOD -> 1, EASY -> 3
        assertEquals("r1", noBaseline.firstReviewSyncId)
        assertEquals(2_000L, noBaseline.reviewedAt)

        // With an imported baseline (repetitions = 4, reviewedAt = 1500), r1 is before cutoff and ignored,
        // r2 advances from baseline streak 2 -> EASY -> streak 4, repetitions = 5, and no review is firstReview.
        val baseline = Baseline(dueAt = 1_800L, reviewedAt = 1_500L, streak = 2, intervalMinutes = 720, repetitions = 4)
        val withBaseline = VocabularyMerge.reconcileSchedule(addedAt = 500L, baseline = baseline, reviews = reviews)
        assertEquals(5, withBaseline.repetitions)
        assertEquals(4, withBaseline.streak)
        assertNull(withBaseline.firstReviewSyncId)
    }
}
