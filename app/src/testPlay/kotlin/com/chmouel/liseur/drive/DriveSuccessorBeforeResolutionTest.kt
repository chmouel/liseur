package com.chmouel.liseur.drive

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Three devices: A and B edit concurrently, C resolves (`res1`) and edits again (`succ`).
 * Device A receives `succ` before `res1`, with the fetch split across sync runs.
 */
@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DriveSuccessorBeforeResolutionTest {

    private val perm = "perm-1"
    private val domain = DriveWireCodec.DOMAIN_ANNOTATION
    private val key = "ann-1"
    private lateinit var db: DriveSyncDatabase

    private fun body(text: String) = JSONObject(DriveCanonicalJson.encode(mapOf("id" to key, "note" to text)))
    private fun fp(text: String) = DriveCanonicalJson.contentFp(body(text))

    private val e1 = fp("edit-A")
    private val e2 = fp("edit-B")
    private val resolved = listOf(e1, e2).sorted()
    private val resHlc = "0017450000300-0001-dev-c"
    private val res1Fp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_PUT, resolved, resHlc, body("merged"))
    private val res1 = DriveWireRecord(
        d = domain, k = key, op = DriveRecordState.OP_RESOLVE, hlc = resHlc, fp = res1Fp,
        anc = resolved, resRefs = listOf(res1Fp), body = body("merged"),
        outcome = DriveResolution.OUTCOME_PUT, resolvedFps = resolved,
    )
    private val succFp = fp("after-resolution")
    private val succ = DriveWireRecord(
        d = domain, k = key, op = DriveRecordState.OP_PUT, hlc = "0017450000400-0001-dev-c", fp = succFp,
        anc = listOf(res1Fp), resRefs = listOf(res1Fp), body = body("after-resolution"),
    )

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), DriveSyncDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun localAt(fp: String, text: String, dirty: Boolean = false) = LocalRecordView(
        versionFp = fp, publishedFp = fp, contentFp = fp, ancestors = listOf(fp("v0")),
        lastHlc = "0017450000100-0001-dev-a", bodyJson = body(text).toString(),
        localRowMatchesContentFp = !dirty,
    )

    private suspend fun deliver(local: LocalRecordView, incoming: DriveWireRecord, device: String, seq: Long): ReconciliationResult {
        val result = DriveConflictEngine.reconcileIncoming(
            permissionId = perm, domain = domain, recordKey = key, bookUrl = null, local = local,
            existingResolutions = db.resolutions().forRecord(perm, domain, key),
            unsettledConflicts = db.conflicts().unsettledForRecord(perm, domain, key),
            incoming = incoming,
            incomingContentFp = incoming.body?.let(DriveCanonicalJson::contentFp),
            remoteDeviceId = device, remoteSeq = seq, now = 5_000L,
        )
        result.resolutionsToPersist.forEach { db.resolutions().upsert(it) }
        result.conflictToOpen?.let { db.conflicts().upsert(it) }
        result.conflictsToSettle.forEach { s ->
            val c = db.conflicts().get(perm, s.conflictId)!!
            db.conflicts().upsert(c.copy(state = DriveConflict.STATE_RESOLVED, choice = s.choice, resolutionFp = s.resolutionFp, resolvedAt = s.resolvedAt))
        }
        return result
    }

    @Test
    fun `a parked successor resolution applies before its retired predecessor`() = runTest {
        val resolvedAgain = listOf(res1Fp, fp("other")).sorted()
        val hlc = "0017450000500-0001-dev-c"
        val newBody = body("resolved again")
        val nextFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_PUT, resolvedAgain, hlc, newBody)
        val next = DriveWireRecord(domain, key, DriveRecordState.OP_RESOLVE, hlc, nextFp,
            anc = listOf(res1Fp), resRefs = listOf(res1Fp, nextFp), body = newBody,
            outcome = DriveResolution.OUTCOME_PUT, resolvedFps = resolvedAgain)
        val first = deliver(localAt(e1, "edit-A"), next, "dev-c", 2L)
        assertEquals(ReconciliationCase.CONFLICT_EDIT_EDIT, first.reconciliationCase)
        val second = deliver(first.updatedRecordState!!.toView(), res1, "dev-c", 1L)
        assertEquals(nextFp, second.updatedRecordState?.versionFp)
        assertTrue(db.conflicts().unsettledForRecord(perm, domain, key).isEmpty())
    }

    @Test
    fun `oversized recovered resolution does not consume a sequence`() = runTest {
        db.accounts().upsert(DriveAccount(perm, "reader@example.com", DriveAccount.STATE_ACTIVE, 1L))
        val oversized = res1.copy(body = body("x".repeat(DriveWireCodec.MAX_BATCH_BYTES)))
        assertFalse(DriveConflictEngine.freezeRecoveredResolutionOutbox(db, perm, "dev-a", oversized, resHlc, 1L))
        assertEquals(1L, db.accounts().get(perm)?.nextSeq)
        assertTrue(db.outbox().forAccount(perm).isEmpty())
    }

    @Test
    fun `later parked successor from same writer wins after ancestry compaction`() = runTest {
        val initial = localAt(e1, "edit-A")
        val early = succ.copy(fp = fp("early"), body = body("early"), anc = emptyList(), resRefs = listOf(res1Fp))
        val late = succ.copy(fp = fp("late"), body = body("late"), anc = emptyList(), resRefs = listOf(res1Fp))
        deliver(initial, early, "dev-c", 2L)
        deliver(initial, late, "dev-c", 70L)
        val result = deliver(initial, res1, "dev-c", 1L)
        assertEquals(late.fp, result.updatedRecordState?.versionFp)
        assertTrue(db.conflicts().unsettledForRecord(perm, domain, key).isEmpty())
    }

    @Test
    fun `successor first keeps lineage and later resolution applies the successor`() = runTest {
        val first = deliver(localAt(e1, "edit-A"), succ, "dev-c", 2L)
        assertEquals(ReconciliationCase.CONFLICT_EDIT_EDIT, first.reconciliationCase)

        // Next run, after restart: lineage must come back from storage.
        val stored = db.conflicts().unsettledForRecord(perm, domain, key).single()
        assertEquals(listOf(res1Fp), DriveConflictEngine.decodeFps(stored.remoteResRefsJson).toList())
        assertEquals(DriveRecordState.OP_PUT, stored.remoteOp)

        val second = deliver(first.updatedRecordState!!.toView(), res1, "dev-c", 1L)
        assertEquals(ReconciliationCase.RESOLUTION_BRIDGED_SUPERSESSION, second.reconciliationCase)
        val apply = second.dataAction as ReconciliationDataAction.ApplyPut
        assertEquals(succFp, apply.contentFp)
        assertEquals(succFp, second.updatedRecordState!!.versionFp)
        assertTrue(res1Fp in DriveConflictEngine.decodeFps(second.updatedRecordState!!.resRefsJson))
        assertNull(second.conflictToOpen)
        assertEquals(DriveConflict.STATE_RESOLVED, db.conflicts().get(perm, stored.conflictId)!!.state)
        assertTrue(db.conflicts().unsettledForRecord(perm, domain, key).isEmpty())
    }

    @Test
    fun `a resolution that equals the local body still yields to its parked successor`() = runTest {
        val sameBody = body("edit-A")
        val sameFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_PUT, resolved, resHlc, sameBody)
        val sameRes = res1.copy(fp = sameFp, resRefs = listOf(sameFp), body = sameBody)
        val successor = succ.copy(anc = listOf(sameFp), resRefs = listOf(sameFp))
        val first = deliver(localAt(e1, "edit-A"), successor, "dev-c", 2L)
        assertEquals(ReconciliationCase.CONFLICT_EDIT_EDIT, first.reconciliationCase)

        val parkedView = first.updatedRecordState!!.toView().copy(postCrashAckMatchesIncoming = true)
        val second = deliver(parkedView, sameRes, "dev-b", 1L)
        assertEquals(ReconciliationCase.RESOLUTION_BRIDGED_SUPERSESSION, second.reconciliationCase)
        assertEquals(succFp, (second.dataAction as ReconciliationDataAction.ApplyPut).contentFp)
        assertEquals(succFp, second.updatedRecordState!!.versionFp)
        assertTrue(db.conflicts().unsettledForRecord(perm, domain, key).isEmpty())
    }

    @Test
    fun `either delivery order converges on the successor`() = runTest {
        val resFirst = deliver(localAt(e1, "edit-A"), res1, "dev-c", 1L)
        val viaOrdered = deliver(resFirst.updatedRecordState!!.toView(), succ, "dev-c", 2L)
        assertEquals(succFp, viaOrdered.updatedRecordState!!.versionFp)
        assertNull(viaOrdered.conflictToOpen)
    }

    @Test
    fun `local edit made between runs is never overwritten and meets the successor in a fresh conflict`() = runTest {
        val first = deliver(localAt(e1, "edit-A"), succ, "dev-c", 2L)
        val e1b = fp("edit-A-again")
        val edited = LocalRecordView(
            versionFp = e1b, publishedFp = null, contentFp = e1b,
            ancestors = listOf(e1) + first.updatedRecordState!!.toView().ancestors,
            lastHlc = "0017450000350-0001-dev-a", bodyJson = body("edit-A-again").toString(),
        )

        val second = deliver(edited, res1, "dev-c", 1L)
        assertEquals(ReconciliationDataAction.NoDataChange, second.dataAction)
        assertEquals(e1b, second.updatedRecordState!!.versionFp)
        val fresh = second.conflictToOpen!!
        assertEquals(e1b, fresh.localFp)
        assertEquals(succFp, fresh.remoteFp)
        assertEquals(DriveCanonicalJson.encode(body("after-resolution")), fresh.remoteJson)
        assertEquals(listOf(res1Fp), DriveConflictEngine.decodeFps(fresh.remoteResRefsJson).toList())
        // The original conflict stays open rather than being silently closed.
        assertEquals(2, db.conflicts().unsettledForRecord(perm, domain, key).size)
    }

    @Test
    fun `dirty local row is left alone and the successor stays parked`() = runTest {
        deliver(localAt(e1, "edit-A"), succ, "dev-c", 2L)
        val second = deliver(localAt(e1, "edit-A", dirty = true), res1, "dev-c", 1L)
        assertEquals(ReconciliationDataAction.NoDataChange, second.dataAction)
        assertNull(second.conflictToOpen)
        assertTrue(second.conflictsToSettle.isEmpty())
        assertEquals(e1, second.updatedRecordState!!.versionFp)
        assertEquals(1, db.conflicts().unsettledForRecord(perm, domain, key).size)
    }

    @Test
    fun `successor delete applies as delete once its resolution arrives`() = runTest {
        val delFp = DriveCanonicalJson.delFp(domain, key, "0017450000400-0001-dev-c")
        val del = DriveWireRecord(
            d = domain, k = key, op = DriveRecordState.OP_DEL, hlc = "0017450000400-0001-dev-c", fp = delFp,
            anc = listOf(res1Fp), resRefs = listOf(res1Fp),
        )
        val first = deliver(localAt(e1, "edit-A"), del, "dev-c", 2L)
        assertEquals(ReconciliationCase.CONFLICT_DELETE_EDIT, first.reconciliationCase)
        val second = deliver(first.updatedRecordState!!.toView(), res1, "dev-c", 1L)
        assertEquals(ReconciliationDataAction.ApplyDelete, second.dataAction)
        assertEquals(DriveRecordState.OP_DEL, second.updatedRecordState!!.versionOp)
        assertNull(second.updatedRecordState!!.contentFp)
    }

    private fun DriveRecordState.toView() = LocalRecordView(
        versionFp = versionFp, versionOp = versionOp, publishedFp = publishedFp, contentFp = contentFp,
        originDeviceId = originDeviceId, originSeq = originSeq,
        ancestors = DriveWorkIdentity.decodeStringArray(ancestorsJson),
        resRefs = DriveWorkIdentity.decodeStringArray(resRefsJson),
        lastHlc = lastHlc, bodyJson = bodyJson,
    )
}
