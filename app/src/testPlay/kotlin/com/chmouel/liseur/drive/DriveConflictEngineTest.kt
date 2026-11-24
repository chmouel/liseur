package com.chmouel.liseur.drive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive unit tests for [DriveConflictEngine] covering ADR-0078 & `docs/DRIVE_SYNC.md` §3.1:
 * - Step 0 mandatory resolution & `resRefs`-bridge settlement pre-step (Agreement & Stale paths)
 * - Delivery ACK (`published_fp`) vs causal baseline separation (simultaneous offline upload `EDIT_EDIT`)
 * - Stale delete vs `DELETE_EDIT` and `op: "resolve"` (`outcome: "put"` / `"delete"`)
 * - Strict competing-resolution `(hlc, fp)` arbitration ONLY when BOTH current `L` and `R` are `op: "resolve"`
 * - Subsequent edit after resolution (`resA -> fNew` vs higher-HLC `resB`) MUST NOT silently arbitrate
 * - >64-edit `resRefs` compaction bridge vs independent-branch skewed-clock non-bridge (`fB -> fD`)
 */
class DriveConflictEngineTest {

    private val permId = "perm-1"
    private val domain = DriveWireCodec.DOMAIN_ANNOTATION
    private val key = "ann-1"
    private val bookUrl = "file:///book.epub"

    private fun putBody(text: String): JSONObject =
        JSONObject(DriveCanonicalJson.encode(mapOf("id" to key, "note" to text)))

    private fun putFp(text: String): String =
        DriveCanonicalJson.contentFp(putBody(text))

    @Test
    fun `simultaneous offline uploads create EDIT_EDIT despite both having published_fp == L`() {
        val f0 = putFp("v0")
        val fA = putFp("edit-A")
        val fB = putFp("edit-B")

        // Device A edited f0 -> fA and ALREADY uploaded its batch (published_fp == fA).
        val localA = LocalRecordView(
            versionFp = fA,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = fA,
            contentFp = fA,
            ancestors = listOf(f0),
            lastHlc = "0017450000100-0001-dev-a",
            bodyJson = putBody("edit-A").toString(),
        )
        // Device B independently edited f0 -> fB and uploaded its batch.
        val incomingB = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_PUT,
            hlc = "0017450000200-0001-dev-b",
            fp = fB,
            anc = listOf(f0),
            resRefs = emptyList(),
            body = putBody("edit-B"),
        )

        val result = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localA,
            existingResolutions = emptyList(),
            unsettledConflicts = emptyList(),
            incoming = incomingB,
            incomingContentFp = fB,
            remoteDeviceId = "dev-b",
            remoteSeq = 1L,
            now = 1_000L,
        )

        assertEquals(ReconciliationCase.CONFLICT_EDIT_EDIT, result.reconciliationCase)
        assertEquals(ReconciliationDataAction.NoDataChange, result.dataAction)
        val conflict = result.conflictToOpen
        assertNotNull(conflict)
        assertEquals(DriveConflict.KIND_EDIT_EDIT, conflict!!.kind)
        assertEquals(fA, conflict.localFp)
        assertEquals(fB, conflict.remoteFp)
        assertEquals(DriveCanonicalJson.encode(putBody("edit-B")), conflict.remoteJson)
        assertEquals(fA, result.updatedRecordState!!.versionFp)
    }

    @Test
    fun `stale delete on untouched copy applies silently while concurrent edit + delete opens DELETE_EDIT`() {
        val f0 = putFp("v0")
        val fB = putFp("edit-B")
        val delFp = DriveCanonicalJson.delFp(domain, key, "0017450000100-0001-dev-a")

        val incomingDel = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_DEL,
            hlc = "0017450000100-0001-dev-a",
            fp = delFp,
            anc = listOf(f0),
            resRefs = emptyList(),
            body = null,
        )

        // Untouched device holding f0 -> silent fast-forward delete.
        val untouched = LocalRecordView(
            versionFp = f0,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = f0,
            contentFp = f0,
            ancestors = emptyList(),
            lastHlc = "0017450000050-0001-dev-a",
            bodyJson = putBody("v0").toString(),
        )
        val silentResult = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = untouched,
            existingResolutions = emptyList(),
            unsettledConflicts = emptyList(),
            incoming = incomingDel,
            incomingContentFp = null,
            remoteDeviceId = "dev-a",
            remoteSeq = 2L,
            now = 2_000L,
        )
        assertEquals(ReconciliationCase.CAUSAL_SUPERSESSION, silentResult.reconciliationCase)
        assertEquals(ReconciliationDataAction.ApplyDelete, silentResult.dataAction)
        assertNull(silentResult.conflictToOpen)
        assertEquals(DriveRecordState.OP_DEL, silentResult.updatedRecordState!!.versionOp)

        // Edited device holding fB (anc=[f0]) -> DELETE_EDIT conflict preserving fB.
        val edited = LocalRecordView(
            versionFp = fB,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = fB,
            contentFp = fB,
            ancestors = listOf(f0),
            lastHlc = "0017450000150-0001-dev-b",
            bodyJson = putBody("edit-B").toString(),
        )
        val conflictResult = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = edited,
            existingResolutions = emptyList(),
            unsettledConflicts = emptyList(),
            incoming = incomingDel,
            incomingContentFp = null,
            remoteDeviceId = "dev-a",
            remoteSeq = 2L,
            now = 2_000L,
        )
        assertEquals(ReconciliationCase.CONFLICT_DELETE_EDIT, conflictResult.reconciliationCase)
        assertEquals(ReconciliationDataAction.NoDataChange, conflictResult.dataAction)
        assertNotNull(conflictResult.conflictToOpen)
        assertEquals(DriveConflict.KIND_DELETE_EDIT, conflictResult.conflictToOpen!!.kind)
        assertNull(conflictResult.conflictToOpen!!.remoteJson)
    }

    @Test
    fun `competing offline resolutions arbitrate silently by (hlc, fp) ONLY when both are op=resolve`() {
        val fA = putFp("edit-A")
        val fB = putFp("edit-B")
        val resolvedFps = listOf(fA, fB).sorted()

        val hlcA = "0017450000300-0001-dev-a"
        val resAFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_PUT, resolvedFps, hlcA, putBody("edit-A"))
        val resA = DriveResolution(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            resolutionFp = resAFp,
            resolvedFpsJson = DriveCanonicalJson.encode(resolvedFps),
            outcome = DriveResolution.OUTCOME_PUT,
            hlc = hlcA,
            authorDeviceId = "dev-a",
            createdAt = 3_000L,
        )

        val hlcB = "0017450000400-0001-dev-b"
        val resBFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_DELETE, resolvedFps, hlcB, null)
        val incomingResB = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_RESOLVE,
            hlc = hlcB,
            fp = resBFp,
            anc = resolvedFps,
            resRefs = listOf(resBFp),
            body = null,
            outcome = DriveResolution.OUTCOME_DELETE,
            resolvedFps = resolvedFps,
        )

        // Device A is currently AT resA (versionOp == "resolve"): higher-HLC resB wins silently.
        val localAtResA = LocalRecordView(
            versionFp = resAFp,
            versionOp = DriveRecordState.OP_RESOLVE,
            isLivePut = true,
            publishedFp = resAFp,
            contentFp = fA,
            ancestors = resolvedFps,
            resRefs = listOf(resAFp),
            lastHlc = hlcA,
            bodyJson = putBody("edit-A").toString(),
        )
        val resultOnA = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localAtResA,
            existingResolutions = listOf(resA),
            unsettledConflicts = emptyList(),
            incoming = incomingResB,
            incomingContentFp = null,
            remoteDeviceId = "dev-b",
            remoteSeq = 3L,
            now = 4_000L,
        )
        assertEquals(ReconciliationCase.RESOLVE_APPLIED, resultOnA.reconciliationCase)
        assertEquals(ReconciliationDataAction.ApplyDelete, resultOnA.dataAction)
        assertNull(resultOnA.conflictToOpen)
        assertEquals(resBFp, resultOnA.updatedRecordState!!.versionFp)
        assertEquals(DriveRecordState.OP_RESOLVE, resultOnA.updatedRecordState!!.versionOp)

        // Conversely, Device B at resB receiving lower-HLC resA keeps resB silently.
        val resB = DriveResolution(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            resolutionFp = resBFp,
            resolvedFpsJson = DriveCanonicalJson.encode(resolvedFps),
            outcome = DriveResolution.OUTCOME_DELETE,
            hlc = hlcB,
            authorDeviceId = "dev-b",
            createdAt = 4_000L,
        )
        val incomingResA = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_RESOLVE,
            hlc = hlcA,
            fp = resAFp,
            anc = resolvedFps,
            resRefs = listOf(resAFp),
            body = putBody("edit-A"),
            outcome = DriveResolution.OUTCOME_PUT,
            resolvedFps = resolvedFps,
        )
        val localAtResB = LocalRecordView(
            versionFp = resBFp,
            versionOp = DriveRecordState.OP_RESOLVE,
            isLivePut = false,
            publishedFp = resBFp,
            contentFp = null,
            ancestors = resolvedFps,
            resRefs = listOf(resBFp),
            lastHlc = hlcB,
            bodyJson = null,
        )
        val resultOnB = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localAtResB,
            existingResolutions = listOf(resB),
            unsettledConflicts = emptyList(),
            incoming = incomingResA,
            incomingContentFp = fA,
            remoteDeviceId = "dev-a",
            remoteSeq = 3L,
            now = 4_500L,
        )
        assertEquals(ReconciliationCase.RESOLVE_LOCAL_WON, resultOnB.reconciliationCase)
        assertEquals(ReconciliationDataAction.NoDataChange, resultOnB.dataAction)
        assertNull(resultOnB.conflictToOpen)
        assertEquals(resBFp, resultOnB.updatedRecordState!!.versionFp)
        assertTrue(resAFp in DriveWorkIdentity.decodeStringArray(resultOnB.updatedRecordState!!.ancestorsJson))
    }

    @Test
    fun `subsequent local edit after resolution (resA to fNew) MUST NOT silently arbitrate against higher-HLC resB`() {
        val fA = putFp("edit-A")
        val fB = putFp("edit-B")
        val fNew = putFp("subsequent-edit-after-resA")
        val resolvedFps = listOf(fA, fB).sorted()

        val hlcA = "0017450000300-0001-dev-a"
        val resAFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_PUT, resolvedFps, hlcA, putBody("edit-A"))
        val resA = DriveResolution(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            resolutionFp = resAFp,
            resolvedFpsJson = DriveCanonicalJson.encode(resolvedFps),
            outcome = DriveResolution.OUTCOME_PUT,
            hlc = hlcA,
            authorDeviceId = "dev-a",
            createdAt = 3_000L,
        )

        val hlcB = "0017450000400-0001-dev-b"
        val resBFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_DELETE, resolvedFps, hlcB, null)
        val incomingResB = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_RESOLVE,
            hlc = hlcB,
            fp = resBFp,
            anc = resolvedFps,
            resRefs = listOf(resBFp),
            body = null,
            outcome = DriveResolution.OUTCOME_DELETE,
            resolvedFps = resolvedFps,
        )

        // Device A moved on from resA to fNew (`versionOp == "put"`).
        val localAtFNew = LocalRecordView(
            versionFp = fNew,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = fNew,
            contentFp = fNew,
            ancestors = listOf(resAFp) + resolvedFps,
            resRefs = listOf(resAFp),
            lastHlc = "0017450000350-0001-dev-a",
            bodyJson = putBody("subsequent-edit-after-resA").toString(),
        )

        val result = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localAtFNew,
            existingResolutions = listOf(resA),
            unsettledConflicts = emptyList(),
            incoming = incomingResB,
            incomingContentFp = null,
            remoteDeviceId = "dev-b",
            remoteSeq = 3L,
            now = 5_000L,
        )

        // Must preserve fNew and open DELETE_EDIT conflict rather than silently deleting fNew!
        assertEquals(ReconciliationCase.CONFLICT_DELETE_EDIT, result.reconciliationCase)
        assertEquals(ReconciliationDataAction.NoDataChange, result.dataAction)
        assertNotNull(result.conflictToOpen)
        assertEquals(fNew, result.conflictToOpen!!.localFp)
        assertEquals(resBFp, result.conflictToOpen!!.remoteFp)
        // Step 0 STILL persisted resB in drive_resolution.
        assertEquals(1, result.resolutionsToPersist.size)
        assertEquals(resBFp, result.resolutionsToPersist.first().resolutionFp)
        // fNew never adopted resB, so it must not advertise descent from it: a later edit carrying
        // resB in resRefs would let a peer still at fA/fB bridge over resB silently.
        val keptResRefs = DriveWorkIdentity.decodeStringArray(result.updatedRecordState!!.resRefsJson)
        assertEquals(listOf(resAFp), keptResRefs)
    }

    @Test
    fun `resRefs compaction bridge fast-forwards after 70 edits while independent branch with higher HLC MUST NOT bridge`() {
        val fA = putFp("edit-A")
        val fB = putFp("edit-B")
        val resolvedFps = listOf(fA, fB).sorted()
        val res1Hlc = "0017450000300-0001-dev-a"
        val res1Fp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_PUT, resolvedFps, res1Hlc, putBody("edit-A"))
        val res1 = DriveResolution(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            resolutionFp = res1Fp,
            resolvedFpsJson = DriveCanonicalJson.encode(resolvedFps),
            outcome = DriveResolution.OUTCOME_PUT,
            hlc = res1Hlc,
            authorDeviceId = "dev-a",
            createdAt = 3_000L,
        )

        val openConflict = DriveConflict(
            permissionId = permId,
            conflictId = DriveCanonicalJson.conflictId(domain, key, fB, fA),
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            kind = DriveConflict.KIND_EDIT_EDIT,
            localFp = fB,
            remoteFp = fA,
            remoteJson = putBody("edit-A").toString(),
            remoteDeviceId = "dev-a",
            remoteHlc = "0017450000100-0001-dev-a",
            state = DriveConflict.STATE_OPEN,
            openedAt = 1_500L,
        )
        val localC = LocalRecordView(
            versionFp = fB,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = fB,
            contentFp = fB,
            ancestors = emptyList(),
            resRefs = emptyList(),
            lastHlc = "0017450000200-0001-dev-b",
            bodyJson = putBody("edit-B").toString(),
        )

        // 1. Independent branch fB -> fD with skewed HIGHER clock (`fD.hlc > res1.hlc`) and NO `res1Fp` in `resRefs`:
        // MUST NOT bridge or fast-forward via `fB in fD.anc` because fB is retired/in open conflict!
        val fD = putFp("independent-D")
        val incomingD = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_PUT,
            hlc = "0017459999999-0001-dev-d", // skewed higher clock!
            fp = fD,
            anc = listOf(fB),
            resRefs = emptyList(), // NO proof of descending from res1!
            body = putBody("independent-D"),
        )
        val nonBridgeResult = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localC,
            existingResolutions = listOf(res1),
            unsettledConflicts = listOf(openConflict),
            incoming = incomingD,
            incomingContentFp = fD,
            remoteDeviceId = "dev-d",
            remoteSeq = 10L,
            now = 9_000L,
        )
        assertEquals(ReconciliationCase.CONFLICT_EDIT_EDIT, nonBridgeResult.reconciliationCase)
        assertEquals(ReconciliationDataAction.NoDataChange, nonBridgeResult.dataAction)
        assertTrue(nonBridgeResult.conflictsToSettle.isEmpty())

        // 2. Legitimate compacted successor f70 after >64 edits (neither fA, fB, nor res1 in anc,
        //    ONLY res1Fp carried in uncapped `resRefs`): MUST settle open conflict & fast-forward!
        val f70 = putFp("edit-70")
        val fake64Anc = (6..69).map { putFp("edit-$it") }.reversed()
        val incomingF70 = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_PUT,
            hlc = "0017450000900-0001-dev-a",
            fp = f70,
            anc = fake64Anc,
            resRefs = listOf(res1Fp),
            body = putBody("edit-70"),
        )
        val bridgeResult = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localC,
            existingResolutions = listOf(res1),
            unsettledConflicts = listOf(openConflict),
            incoming = incomingF70,
            incomingContentFp = f70,
            remoteDeviceId = "dev-a",
            remoteSeq = 75L,
            now = 10_000L,
        )
        assertEquals(ReconciliationCase.RESOLUTION_BRIDGED_SUPERSESSION, bridgeResult.reconciliationCase)
        assertTrue(bridgeResult.dataAction is ReconciliationDataAction.ApplyPut)
        assertEquals(1, bridgeResult.conflictsToSettle.size)
        assertEquals(openConflict.conflictId, bridgeResult.conflictsToSettle.first().conflictId)
        assertEquals(res1Fp, bridgeResult.conflictsToSettle.first().resolutionFp)
        assertEquals(f70, bridgeResult.updatedRecordState!!.versionFp)
        assertTrue(res1Fp in DriveWorkIdentity.decodeStringArray(bridgeResult.updatedRecordState!!.resRefsJson))
    }

    @Test
    fun `Step 0 settles OPEN conflict BEFORE Agreement and Stale replay after crash between Tx 1 and Tx 2`() {
        val fA = putFp("edit-A")
        val fB = putFp("edit-B")
        val f70 = putFp("edit-70")
        val fLocalAfterCrash = putFp("local-after-crash")
        val resolvedFps = listOf(fA, fB).sorted()
        val res1Hlc = "0017450000300-0001-dev-a"
        val res1Fp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_PUT, resolvedFps, res1Hlc, putBody("edit-A"))
        val res1 = DriveResolution(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            resolutionFp = res1Fp,
            resolvedFpsJson = DriveCanonicalJson.encode(resolvedFps),
            outcome = DriveResolution.OUTCOME_PUT,
            hlc = res1Hlc,
            authorDeviceId = "dev-a",
            createdAt = 3_000L,
        )
        val openConflict = DriveConflict(
            permissionId = permId,
            conflictId = DriveCanonicalJson.conflictId(domain, key, fB, fA),
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            kind = DriveConflict.KIND_EDIT_EDIT,
            localFp = fB,
            remoteFp = fA,
            remoteJson = putBody("edit-A").toString(),
            remoteDeviceId = "dev-a",
            remoteHlc = "0017450000100-0001-dev-a",
            state = DriveConflict.STATE_OPEN,
            openedAt = 1_500L,
        )
        val incomingF70 = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_PUT,
            hlc = "0017450000900-0001-dev-a",
            fp = f70,
            anc = emptyList(),
            resRefs = listOf(res1Fp),
            body = putBody("edit-70"),
        )

        // Variant A: Unedited replay hits Agreement -> Step 0 STILL closes OPEN conflict!
        val repairedUnedited = LocalRecordView(
            versionFp = f70,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = f70,
            contentFp = f70,
            postCrashAckMatchesIncoming = true,
            hasPostCrashLocalEdit = false,
        )
        val uneditedReplay = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = repairedUnedited,
            existingResolutions = listOf(res1),
            unsettledConflicts = listOf(openConflict),
            incoming = incomingF70,
            incomingContentFp = f70,
            remoteDeviceId = "dev-a",
            remoteSeq = 75L,
            now = 11_000L,
        )
        assertEquals(ReconciliationCase.AGREEMENT, uneditedReplay.reconciliationCase)
        assertEquals(1, uneditedReplay.conflictsToSettle.size)
        assertEquals(res1Fp, uneditedReplay.conflictsToSettle.first().resolutionFp)

        // Variant B: Post-crash local edit routes to Stale/preserved -> Step 0 STILL closes OPEN conflict!
        val editedAfterCrash = LocalRecordView(
            versionFp = fLocalAfterCrash,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = f70,
            contentFp = fLocalAfterCrash,
            ancestors = listOf(f70),
            postCrashAckMatchesIncoming = true,
            hasPostCrashLocalEdit = true,
        )
        val staleReplay = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = editedAfterCrash,
            existingResolutions = listOf(res1),
            unsettledConflicts = listOf(openConflict),
            incoming = incomingF70,
            incomingContentFp = f70,
            remoteDeviceId = "dev-a",
            remoteSeq = 75L,
            now = 12_000L,
        )
        assertEquals(ReconciliationCase.POST_CRASH_LOCAL_EDIT_PRESERVED, staleReplay.reconciliationCase)
        assertEquals(ReconciliationDataAction.NoDataChange, staleReplay.dataAction)
        assertEquals(1, staleReplay.conflictsToSettle.size)
        assertEquals(res1Fp, staleReplay.conflictsToSettle.first().resolutionFp)
        assertEquals(fLocalAfterCrash, staleReplay.updatedRecordState!!.versionFp)
        assertTrue(res1Fp in DriveWorkIdentity.decodeStringArray(staleReplay.updatedRecordState!!.resRefsJson))
    }

    @Test
    fun `crash between liseur_db and drive_sync_db on incoming op=resolve settles OPEN conflict on both Agreement and Stale replay`() {
        val fA = putFp("edit-A")
        val fB = putFp("edit-B")
        val fAfter = putFp("post-crash-edit")
        val resolvedFps = listOf(fA, fB).sorted()

        val openConflict = DriveConflict(
            permissionId = permId,
            conflictId = DriveCanonicalJson.conflictId(domain, key, fB, fA),
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            kind = DriveConflict.KIND_EDIT_EDIT,
            localFp = fB,
            remoteFp = fA,
            remoteJson = putBody("edit-A").toString(),
            remoteDeviceId = "dev-a",
            remoteHlc = "0017450000100-0001-dev-a",
            state = DriveConflict.STATE_OPEN,
            openedAt = 1_500L,
        )

        // 1. Incoming `op: "resolve"` with `outcome: "put"` — Agreement replay after crash between Tx 1 and Tx 2.
        val resPutHlc = "0017450000300-0001-dev-a"
        val resPutFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_PUT, resolvedFps, resPutHlc, putBody("edit-A"))
        val incomingResPut = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_RESOLVE,
            hlc = resPutHlc,
            fp = resPutFp,
            anc = resolvedFps,
            resRefs = listOf(resPutFp),
            body = putBody("edit-A"),
            outcome = DriveResolution.OUTCOME_PUT,
            resolvedFps = resolvedFps,
        )
        val localUneditedPutReplay = LocalRecordView(
            versionFp = fA,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = fB,
            contentFp = fA,
            ancestors = listOf(fB),
            bodyJson = putBody("edit-A").toString(),
            postCrashAckMatchesIncoming = true,
            hasPostCrashLocalEdit = false,
        )
        val putAgreement = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localUneditedPutReplay,
            existingResolutions = emptyList(),
            unsettledConflicts = listOf(openConflict),
            incoming = incomingResPut,
            incomingContentFp = fA,
            remoteDeviceId = "dev-a",
            remoteSeq = 4L,
            now = 5_000L,
        )
        assertEquals(ReconciliationCase.AGREEMENT, putAgreement.reconciliationCase)
        assertEquals(ReconciliationDataAction.NoDataChange, putAgreement.dataAction)
        assertEquals(1, putAgreement.resolutionsToPersist.size)
        assertEquals(resPutFp, putAgreement.resolutionsToPersist.single().resolutionFp)
        assertEquals(1, putAgreement.conflictsToSettle.size)
        assertEquals(openConflict.conflictId, putAgreement.conflictsToSettle.single().conflictId)
        assertEquals(resPutFp, putAgreement.updatedRecordState!!.versionFp)
        assertEquals(DriveRecordState.OP_RESOLVE, putAgreement.updatedRecordState!!.versionOp)
        assertEquals(fA, putAgreement.updatedRecordState!!.contentFp)
        assertTrue(resPutFp in DriveWorkIdentity.decodeStringArray(putAgreement.updatedRecordState!!.resRefsJson))

        // 2. Incoming `op: "resolve"` with `outcome: "delete"` — Agreement replay after crash between Tx 1 and Tx 2
        //    must clear stale contentFp/bodyJson on updatedRecordState while persisting resolution & closing conflict!
        val resDelHlc = "0017450000350-0001-dev-a"
        val resDelFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_DELETE, resolvedFps, resDelHlc, null)
        val incomingResDel = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_RESOLVE,
            hlc = resDelHlc,
            fp = resDelFp,
            anc = resolvedFps,
            resRefs = listOf(resDelFp),
            body = null,
            outcome = DriveResolution.OUTCOME_DELETE,
            resolvedFps = resolvedFps,
        )
        val localStaleStateBeforeTx2 = LocalRecordView(
            versionFp = fB,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = fB,
            contentFp = fB,
            bodyJson = putBody("edit-B").toString(),
            postCrashAckMatchesIncoming = true,
            hasPostCrashLocalEdit = false,
        )
        val delAgreement = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localStaleStateBeforeTx2,
            existingResolutions = emptyList(),
            unsettledConflicts = listOf(openConflict),
            incoming = incomingResDel,
            incomingContentFp = null,
            remoteDeviceId = "dev-a",
            remoteSeq = 5L,
            now = 5_500L,
        )
        assertEquals(ReconciliationCase.AGREEMENT, delAgreement.reconciliationCase)
        assertEquals(ReconciliationDataAction.NoDataChange, delAgreement.dataAction)
        assertEquals(1, delAgreement.resolutionsToPersist.size)
        assertEquals(1, delAgreement.conflictsToSettle.size)
        assertEquals(resDelFp, delAgreement.updatedRecordState!!.versionFp)
        assertEquals(DriveRecordState.OP_RESOLVE, delAgreement.updatedRecordState!!.versionOp)
        assertNull(delAgreement.updatedRecordState!!.contentFp)
        assertNull(delAgreement.updatedRecordState!!.bodyJson)

        // 3. Incoming `op: "resolve"` — Stale / post-crash local edit replay must STILL run Step 0,
        //    keep post-crash edit dirty (`publishedFp != versionFp`), and include `resPutFp` in `anc` & `resRefs`.
        val localEditedAfterResolveCrash = LocalRecordView(
            versionFp = fAfter,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = fB,
            contentFp = fAfter,
            ancestors = listOf(fB),
            bodyJson = putBody("post-crash-edit").toString(),
            postCrashAckMatchesIncoming = true,
            hasPostCrashLocalEdit = true,
        )
        val resolveStaleReplay = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localEditedAfterResolveCrash,
            existingResolutions = emptyList(),
            unsettledConflicts = listOf(openConflict),
            incoming = incomingResPut,
            incomingContentFp = fA,
            remoteDeviceId = "dev-a",
            remoteSeq = 4L,
            now = 6_000L,
        )
        assertEquals(ReconciliationCase.POST_CRASH_LOCAL_EDIT_PRESERVED, resolveStaleReplay.reconciliationCase)
        assertEquals(ReconciliationDataAction.NoDataChange, resolveStaleReplay.dataAction)
        assertEquals(1, resolveStaleReplay.resolutionsToPersist.size)
        assertEquals(1, resolveStaleReplay.conflictsToSettle.size)
        assertEquals(fAfter, resolveStaleReplay.updatedRecordState!!.versionFp)
        assertEquals(resPutFp, resolveStaleReplay.updatedRecordState!!.publishedFp)
        assertTrue(resPutFp in DriveWorkIdentity.decodeStringArray(resolveStaleReplay.updatedRecordState!!.ancestorsJson))
        assertTrue(resPutFp in DriveWorkIdentity.decodeStringArray(resolveStaleReplay.updatedRecordState!!.resRefsJson))
    }

    @Test
    fun `KEEP_REMOTE resolution stays OP_RESOLVE on stale replay of retained put and arbitrates competing resolution cleanly`() {
        val fA = putFp("edit-A")
        val fB = putFp("edit-B")
        val resolvedFps = listOf(fA, fB).sorted()

        // Device A chose KEEP_REMOTE (retaining fB's content), creating resA (`OP_RESOLVE`).
        val hlcA = "0017450000300-0001-dev-a"
        val resAFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_PUT, resolvedFps, hlcA, putBody("edit-B"))
        val resA = DriveResolution(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            resolutionFp = resAFp,
            resolvedFpsJson = DriveCanonicalJson.encode(resolvedFps),
            outcome = DriveResolution.OUTCOME_PUT,
            hlc = hlcA,
            authorDeviceId = "dev-a",
            createdAt = 3_000L,
        )

        // Pre-resolution remote put `fB` is replayed even though content/ack matches fB!
        val replayedOldPutB = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_PUT,
            hlc = "0017450000100-0001-dev-b",
            fp = fB,
            anc = emptyList(),
            resRefs = emptyList(),
            body = putBody("edit-B"),
        )
        val localAtResA = LocalRecordView(
            versionFp = resAFp,
            versionOp = DriveRecordState.OP_RESOLVE,
            isLivePut = true,
            publishedFp = resAFp,
            contentFp = fB,
            ancestors = resolvedFps,
            resRefs = listOf(resAFp),
            lastHlc = hlcA,
            bodyJson = putBody("edit-B").toString(),
            localRowMatchesContentFp = true,
            // Even if caller naively passed postCrashAckMatchesIncoming = true, known ancestor/retired MUST hit STALE.
            postCrashAckMatchesIncoming = true,
            hasPostCrashLocalEdit = false,
        )

        val replayResult = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localAtResA,
            existingResolutions = listOf(resA),
            unsettledConflicts = emptyList(),
            incoming = replayedOldPutB,
            incomingContentFp = fB,
            remoteDeviceId = "dev-b",
            remoteSeq = 1L,
            now = 4_000L,
        )
        assertEquals(ReconciliationCase.STALE, replayResult.reconciliationCase)
        assertEquals(ReconciliationDataAction.NoDataChange, replayResult.dataAction)
        assertEquals(resAFp, replayResult.updatedRecordState!!.versionFp)
        assertEquals(DriveRecordState.OP_RESOLVE, replayResult.updatedRecordState!!.versionOp)

        // Subsequent competing resolution from Device B with lower HLC -> RESOLVE_LOCAL_WON without conflict!
        val hlcBLower = "0017450000250-0001-dev-b"
        val resBLowerFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_PUT, resolvedFps, hlcBLower, putBody("edit-A"))
        val competingLower = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_RESOLVE,
            hlc = hlcBLower,
            fp = resBLowerFp,
            anc = resolvedFps,
            resRefs = listOf(resBLowerFp),
            body = putBody("edit-A"),
            outcome = DriveResolution.OUTCOME_PUT,
            resolvedFps = resolvedFps,
        )
        val stateAfterReplay = replayResult.updatedRecordState!!
        val localAfterReplay = LocalRecordView(
            versionFp = stateAfterReplay.versionFp,
            versionOp = stateAfterReplay.versionOp,
            isLivePut = true,
            publishedFp = stateAfterReplay.publishedFp,
            contentFp = stateAfterReplay.contentFp,
            ancestors = DriveWorkIdentity.decodeStringArray(stateAfterReplay.ancestorsJson),
            resRefs = DriveWorkIdentity.decodeStringArray(stateAfterReplay.resRefsJson),
            lastHlc = stateAfterReplay.lastHlc,
            bodyJson = stateAfterReplay.bodyJson,
            localRowMatchesContentFp = true,
        )
        val arbitrationLower = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localAfterReplay,
            existingResolutions = listOf(resA),
            unsettledConflicts = emptyList(),
            incoming = competingLower,
            incomingContentFp = fA,
            remoteDeviceId = "dev-b",
            remoteSeq = 2L,
            now = 4_500L,
        )
        assertEquals(ReconciliationCase.RESOLVE_LOCAL_WON, arbitrationLower.reconciliationCase)
        assertNull(arbitrationLower.conflictToOpen)
        assertEquals(resAFp, arbitrationLower.updatedRecordState!!.versionFp)
        assertEquals(DriveRecordState.OP_RESOLVE, arbitrationLower.updatedRecordState!!.versionOp)

        // Competing resolution with higher HLC -> RESOLVE_APPLIED without reopening conflict!
        val hlcBHigher = "0017450000500-0001-dev-b"
        val resBHigherFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_DELETE, resolvedFps, hlcBHigher, null)
        val competingHigher = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_RESOLVE,
            hlc = hlcBHigher,
            fp = resBHigherFp,
            anc = resolvedFps,
            resRefs = listOf(resBHigherFp),
            body = null,
            outcome = DriveResolution.OUTCOME_DELETE,
            resolvedFps = resolvedFps,
        )
        val arbitrationHigher = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = localAfterReplay,
            existingResolutions = listOf(resA),
            unsettledConflicts = emptyList(),
            incoming = competingHigher,
            incomingContentFp = null,
            remoteDeviceId = "dev-b",
            remoteSeq = 3L,
            now = 5_000L,
        )
        assertEquals(ReconciliationCase.RESOLVE_APPLIED, arbitrationHigher.reconciliationCase)
        assertEquals(ReconciliationDataAction.ApplyDelete, arbitrationHigher.dataAction)
        assertNull(arbitrationHigher.conflictToOpen)
        assertEquals(resBHigherFp, arbitrationHigher.updatedRecordState!!.versionFp)
        assertEquals(DriveRecordState.OP_RESOLVE, arbitrationHigher.updatedRecordState!!.versionOp)
    }

    @Test
    fun `crash Agreement adopting a different incoming version also adopts its origin`() {
        val fOld = putFp("old-local")
        val fNew = putFp("incoming-new")
        val incoming = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_PUT,
            hlc = "0017450000900-0001-dev-a",
            fp = fNew,
            anc = emptyList(),
            resRefs = emptyList(),
            body = putBody("incoming-new"),
        )
        val local = LocalRecordView(
            versionFp = fOld,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = fNew,
            contentFp = fNew,
            originDeviceId = "dev-old",
            originSeq = 5L,
            postCrashAckMatchesIncoming = true,
            hasPostCrashLocalEdit = false,
        )
        val result = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = local,
            existingResolutions = emptyList(),
            unsettledConflicts = emptyList(),
            incoming = incoming,
            incomingContentFp = fNew,
            remoteDeviceId = "dev-a",
            remoteSeq = 75L,
            now = 11_000L,
        )
        assertEquals(ReconciliationCase.AGREEMENT, result.reconciliationCase)
        assertEquals(fNew, result.updatedRecordState!!.versionFp)
        assertEquals("dev-a", result.updatedRecordState!!.originDeviceId)
        assertEquals(75L, result.updatedRecordState!!.originSeq)
    }

    @Test
    fun `delete-resolution arbitration compares the local resolution hlc not the mutable clock`() {
        val fA = putFp("edit-A")
        val fB = putFp("edit-B")
        val resolvedFps = listOf(fA, fB).sorted()
        val hlcA = "0017450000300-0001-dev-a"
        val resAFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_DELETE, resolvedFps, hlcA, null)
        val resA = DriveResolution(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            resolutionFp = resAFp,
            resolvedFpsJson = DriveCanonicalJson.encode(resolvedFps),
            outcome = DriveResolution.OUTCOME_DELETE,
            hlc = hlcA,
            authorDeviceId = "dev-a",
            createdAt = 3_000L,
        )
        val hlcB = "0017450000400-0001-dev-b"
        val resBFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_DELETE, resolvedFps, hlcB, null)
        val incomingResB = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_RESOLVE,
            hlc = hlcB,
            fp = resBFp,
            anc = resolvedFps,
            resRefs = listOf(resBFp),
            body = null,
            outcome = DriveResolution.OUTCOME_DELETE,
            resolvedFps = resolvedFps,
        )
        // lastHlc advanced by unrelated clock merges beyond resB's hlc.
        val local = LocalRecordView(
            versionFp = resAFp,
            versionOp = DriveRecordState.OP_RESOLVE,
            isLivePut = false,
            publishedFp = resAFp,
            contentFp = null,
            ancestors = resolvedFps,
            resRefs = listOf(resAFp),
            lastHlc = "0017450000900-0001-dev-z",
            bodyJson = null,
        )
        val result = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = local,
            existingResolutions = listOf(resA),
            unsettledConflicts = emptyList(),
            incoming = incomingResB,
            incomingContentFp = null,
            remoteDeviceId = "dev-b",
            remoteSeq = 3L,
            now = 4_000L,
        )
        assertEquals(ReconciliationCase.AGREEMENT, result.reconciliationCase)
        assertEquals(resBFp, result.updatedRecordState!!.versionFp)
    }

    @Test
    fun `causal supersession ignores conflicts already settled by Step 0`() {
        val fOld = putFp("old")
        val fA = putFp("edit-A")
        val fCur = putFp("current-local")
        val fIncoming = putFp("incoming")
        val resolvedFps = listOf(fA, fOld).sorted()
        val resHlc = "0017450000300-0001-dev-a"
        val resFp = DriveCanonicalJson.resolveFp(DriveResolution.OUTCOME_PUT, resolvedFps, resHlc, putBody("edit-A"))
        val res = DriveResolution(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            resolutionFp = resFp,
            resolvedFpsJson = DriveCanonicalJson.encode(resolvedFps),
            outcome = DriveResolution.OUTCOME_PUT,
            hlc = resHlc,
            authorDeviceId = "dev-a",
            createdAt = 3_000L,
        )
        val conflict = DriveConflict(
            permissionId = permId,
            conflictId = DriveCanonicalJson.conflictId(domain, key, fOld, fA),
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            kind = DriveConflict.KIND_EDIT_EDIT,
            localFp = fOld,
            remoteFp = fA,
            remoteJson = putBody("edit-A").toString(),
            remoteDeviceId = "dev-a",
            remoteHlc = "0017450000100-0001-dev-a",
            state = DriveConflict.STATE_OPEN,
            openedAt = 1_500L,
        )
        val incoming = DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_PUT,
            hlc = "0017450000900-0001-dev-a",
            fp = fIncoming,
            anc = listOf(fCur),
            resRefs = listOf(resFp),
            body = putBody("incoming"),
        )
        val local = LocalRecordView(
            versionFp = fCur,
            versionOp = DriveRecordState.OP_PUT,
            isLivePut = true,
            publishedFp = fCur,
            contentFp = fCur,
            ancestors = listOf(fOld),
        )
        val result = DriveConflictEngine.reconcileIncoming(
            permissionId = permId,
            domain = domain,
            recordKey = key,
            bookUrl = bookUrl,
            local = local,
            existingResolutions = listOf(res),
            unsettledConflicts = listOf(conflict),
            incoming = incoming,
            incomingContentFp = fIncoming,
            remoteDeviceId = "dev-a",
            remoteSeq = 75L,
            now = 11_000L,
        )
        assertEquals(ReconciliationCase.CAUSAL_SUPERSESSION, result.reconciliationCase)
        assertEquals(1, result.conflictsToSettle.size)
        assertEquals(fIncoming, result.updatedRecordState!!.versionFp)
    }
}
