package com.chmouel.liseur.drive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveWireCodecAndHlcTest {

    @Test
    fun `canonical JSON sorts keys, normalizes numbers, escapes control chars, and preserves UTF-8`() {
        val encoded = DriveCanonicalJson.encode(
            mapOf(
                "z" to 1.0,
                "a" to -0.0,
                "m" to 0.50,
                "n" to null,
                "text" to "café\n\t\"\\\u0007",
                "nested" to mapOf("b" to 2, "a" to 7.50),
            ),
        )
        assertEquals(
            """{"a":0,"m":0.5,"n":null,"nested":{"a":7.5,"b":2},"text":"café\n\t\"\\\u0007","z":1}""",
            encoded,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `canonical JSON rejects NaN`() {
        DriveCanonicalJson.formatDouble(Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `canonical JSON rejects Infinity`() {
        DriveCanonicalJson.formatDouble(Double.POSITIVE_INFINITY)
    }

    @Test
    fun `canonicalizeJsonString sorts embedded JSON keys recursively`() {
        val raw = """{"type":"application/xhtml+xml","locations":{"totalProgression":0.50,"progression":1.0},"href":"ch1.xhtml"}"""
        assertEquals(
            """{"href":"ch1.xhtml","locations":{"progression":1,"totalProgression":0.5},"type":"application/xhtml+xml"}""",
            DriveCanonicalJson.canonicalizeJsonString(raw),
        )
    }

    @Test
    fun `fingerprints are deterministic and distinct across put, del, and resolve`() {
        val body = mapOf("title" to "The Hobbit", "addedAt" to 1000L)
        val fp1 = DriveCanonicalJson.contentFp(body)
        val fp2 = DriveCanonicalJson.contentFp(JSONObject("""{"addedAt":1000,"title":"The Hobbit"}"""))
        assertEquals(fp1, fp2)
        assertEquals(64, fp1.length)
        val put1 = DriveCanonicalJson.putFp(body, "0017450000000-0001-dev-a")
        assertEquals("76c82fff73717556b2108fd24b700e633935f7fef43d97da64fcabd0b97036a2", put1)
        assertNotEquals(put1, DriveCanonicalJson.putFp(body, "0017450000000-0002-dev-a"))

        val del1 = DriveCanonicalJson.delFp("annotation", "id-1", "0017450000000-0001-dev-a")
        val del2 = DriveCanonicalJson.delFp("annotation", "id-1", "0017450000000-0002-dev-a")
        assertNotEquals(del1, del2)

        val resKeep = DriveCanonicalJson.resolveFp("put", listOf(fp1, del1), "0017450000000-0003-dev-a", body)
        assertNotEquals(fp1, resKeep)
        assertNotEquals(del1, resKeep)

        // batchId is deterministic for (deviceId, seq)
        assertEquals(
            DriveCanonicalJson.batchId("dev-a", 7L),
            DriveCanonicalJson.batchId("dev-a", 7L),
        )
        assertNotEquals(
            DriveCanonicalJson.batchId("dev-a", 7L),
            DriveCanonicalJson.batchId("dev-a", 8L),
        )
    }

    @Test
    fun `catalog rejects uploaded edition without availability clock`() {
        val body = JSONObject("""{"workKey":"isbn:1","editions":[{"sha256":"abc","driveFileId":"file-1"}]}""")
        assertNull(DriveWireCodec.decodeCatalogBody(body))
        body.getJSONArray("editions").getJSONObject(0).put("driveFileId", JSONObject.NULL)
            .put("availabilityHlc", "0017450000000-0001-dev-a")
        assertEquals("0017450000000-0001-dev-a", DriveWireCodec.decodeCatalogBody(body)!!.editions.single().availabilityHlc)
    }

    @Test
    fun `chunking reports an oversized record and still batches the rest`() {
        fun record(key: String, title: String) = DriveWireCodec.putRecord(
            domain = "catalog",
            key = key,
            hlc = "0017450000000-0001-dev-a",
            bodyMap = mapOf("title" to title),
        )
        val small = record("a", "small")
        val huge = record("b", "x".repeat(DriveWireCodec.MAX_BATCH_BYTES))
        val result = DriveWireCodec.chunkRecordsForBatches(listOf(small, huge, record("c", "tiny")))

        assertEquals(listOf(huge), result.oversized)
        assertEquals(listOf(listOf("a", "c")), result.chunks.map { chunk -> chunk.map { it.k } })
    }

    @Test
    fun `DriveHlc keeps a fixed width wall field and fails at the representable limit`() {
        val max = DriveHlc(DriveHlc.MAX_WALL_MS, DriveHlc.MAX_COUNTER, "dev-a")
        assertEquals("9999999999999-ffff-dev-a", max.format())
        assertTrue(DriveHlc(DriveHlc.MAX_WALL_MS - 1, DriveHlc.MAX_COUNTER, "dev-a") < max)
        assertEquals(max, DriveHlc.parse(max.format()))
        assertNull(DriveHlc.parse("10000000000000-0000-dev-a"))
        assertTrue(runCatching { DriveHlc(DriveHlc.MAX_WALL_MS + 1, 0, "dev-a") }.exceptionOrNull() is IllegalArgumentException)

        val last = DriveHlc.next(DriveHlc.MAX_WALL_MS, DriveHlc.MAX_COUNTER - 1, nowMs = 0L, deviceId = "dev-a")
        assertEquals(DriveHlc.MAX_COUNTER, last.counter)
        assertTrue(
            runCatching { DriveHlc.next(DriveHlc.MAX_WALL_MS, DriveHlc.MAX_COUNTER, nowMs = 0L, deviceId = "dev-a") }
                .exceptionOrNull() is IllegalStateException,
        )
        assertTrue(
            runCatching { DriveHlc.observe(0L, 0, max, nowMs = 0L, localDeviceId = "dev-b") }
                .exceptionOrNull() is IllegalStateException,
        )
        val rolled = DriveHlc.next(DriveHlc.MAX_WALL_MS - 1, DriveHlc.MAX_COUNTER, nowMs = 0L, deviceId = "dev-a")
        assertEquals(DriveHlc.MAX_WALL_MS, rolled.wallMs)
        assertTrue(DriveHlc(DriveHlc.MAX_WALL_MS - 1, DriveHlc.MAX_COUNTER, "dev-a").format() < rolled.format())
    }

    @Test
    fun `DriveHlc maintains strict monotonic order under clock regression and merges remote clocks`() {
        val h1 = DriveHlc.next(currentWallMs = 1_700_000_000_000L, currentCounter = 0, nowMs = 1_700_000_000_000L, deviceId = "dev-a")
        val h2 = DriveHlc.next(h1.wallMs, h1.counter, nowMs = 1_600_000_000_000L, deviceId = "dev-a")
        assertTrue(h2 > h1)
        assertEquals(h1.wallMs, h2.wallMs)
        assertEquals(h1.counter + 1, h2.counter)

        // Counter wrap bumps wallMs by 1
        val wrapped = DriveHlc.next(h2.wallMs, DriveHlc.MAX_COUNTER, nowMs = h2.wallMs, deviceId = "dev-a")
        assertEquals(h2.wallMs + 1L, wrapped.wallMs)
        assertEquals(0, wrapped.counter)

        // Remote clock ahead pulls local forward
        val remote = DriveHlc(wallMs = 1_800_000_000_000L, counter = 5, deviceId = "dev-b")
        val observed = DriveHlc.observe(h2.wallMs, h2.counter, remote, nowMs = 1_700_000_000_000L, localDeviceId = "dev-a")
        assertTrue(observed > remote)
        assertEquals(1_800_000_000_000L, observed.wallMs)
        assertEquals(6, observed.counter)

        // Roundtrip parse/format
        val parsed = DriveHlc.parse(observed.format())
        assertEquals(observed, parsed)
        assertNull(DriveHlc.parse("not-an-hlc"))
    }

    @Test
    fun `manifest, delta batch, and Phase 3 domain bodies roundtrip cleanly`() {
        val manifest = DriveManifest(
            deviceId = "dev-a",
            deviceLabel = "Pixel 8",
            appVersionCode = 42,
            updatedHlc = "0017450000000-0001-dev-a",
            epubFolder = DriveEpubFolderRef(fileId = "folder-1", createdTime = "2026-04-01T12:00:00Z"),
            appliedVector = mapOf("dev-b" to 5L),
        )
        val decodedManifest = DriveWireCodec.decodeManifest(DriveWireCodec.encodeManifest(manifest))
        assertEquals(manifest, decodedManifest)

        val catalogBody = DriveCatalogBody(
            workKey = "isbn:9780547928227",
            title = "The Hobbit",
            author = "J.R.R. Tolkien",
            identifiers = listOf(DriveCatalogIdentifier("", "bare-id"), DriveCatalogIdentifier("isbn", "9780547928227")),
            editions = listOf(
                DriveCatalogEdition(
                    sha256 = "a".repeat(64),
                    sizeBytes = 12345L,
                    driveFileId = "file-1",
                    fileName = "The Hobbit.epub",
                    uploadedAt = 1000L,
                    availabilityHlc = "0017450000000-0001-dev-a",
                ),
            ),
            fileSeriesName = "Middle-earth",
            fileSeriesIndex = 1.0,
            addedAt = 500L,
            archivedAt = null,
            updatedAt = 1000L,
        )
        val catRecord = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_CATALOG,
            key = catalogBody.workKey,
            hlc = "0017450000000-0001-dev-a",
            bodyMap = DriveWireCodec.encodeCatalogBody(catalogBody),
        )
        val posBody = DrivePositionBody(
            workKey = catalogBody.workKey,
            deviceId = "dev-a",
            editionSha = "a".repeat(64),
            locatorJson = """{"href":"ch1.xhtml","locations":{"totalProgression":0.42}}""",
            totalProgression = 0.42,
            status = "Reading",
            finishedOverride = "automatic",
            coverage = null,
            unreadResetAt = null,
            readAt = 2000L,
            updatedAt = 2000L,
        )
        val posRecord = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_POSITION,
            key = DrivePositionSync.positionRecordKey(catalogBody.workKey, "dev-a"),
            hlc = "0017450000000-0002-dev-a",
            bodyMap = DriveWireCodec.encodePositionBody(posBody),
        )
        val resetBody = DriveFurthestBody.Reset(
            workKey = catalogBody.workKey,
            resetId = "reset-1",
            floors = mapOf("dev-a" to 10L, "dev-b" to 4L),
            resetAt = 3000L,
        )
        val resetRecord = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_FURTHEST,
            key = DrivePositionSync.resetRecordKey(catalogBody.workKey, "reset-1"),
            hlc = "0017450000000-0003-dev-a",
            bodyMap = DriveWireCodec.encodeFurthestBody(resetBody),
        )

        val batch = DriveDeltaBatch(
            batchId = DriveCanonicalJson.batchId("dev-a", 1L),
            deviceId = "dev-a",
            seq = 1L,
            createdHlc = "0017450000000-0003-dev-a",
            domains = mapOf("catalog" to 1, "position" to 1, "furthest" to 1),
            records = listOf(catRecord, posRecord, resetRecord),
        )
        val decodedBatch = DriveWireCodec.decodeDeltaBatch(DriveWireCodec.encodeDeltaBatch(batch))
        assertEquals(3, decodedBatch.records.size)
        assertEquals(catalogBody, DriveWireCodec.decodeCatalogBody(decodedBatch.records[0].body!!))
        assertEquals(posBody, DriveWireCodec.decodePositionBody(decodedBatch.records[1].body!!))
        assertEquals(resetBody, DriveWireCodec.decodeFurthestBody(decodedBatch.records[2].body!!))
    }

    @Test
    fun `schemaVersion guard flags unsupported stream version, per-domain versions, and unimplemented domains`() {
        assertEquals(
            setOf("workAlias", "catalog", "position", "furthest"),
            DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS.keys,
        )
        assertEquals(
            DriveSchemaCheck.Supported,
            DriveWireCodec.checkSchema(1, mapOf("catalog" to 1, "position" to 1)),
        )
        val streamCheck = DriveWireCodec.checkSchema(2, mapOf("catalog" to 1), deviceLabel = "Future Tablet")
        assertTrue(streamCheck is DriveSchemaCheck.UnsupportedStream)
        assertEquals(2, (streamCheck as DriveSchemaCheck.UnsupportedStream).schemaVersion)
        assertEquals("Future Tablet", streamCheck.deviceLabel)

        val domainCheck = DriveWireCodec.checkSchema(1, mapOf("catalog" to 2, "position" to 1))
        assertTrue(domainCheck is DriveSchemaCheck.UnsupportedDomains)
        assertEquals(mapOf("catalog" to 2), (domainCheck as DriveSchemaCheck.UnsupportedDomains).unsupported)

        val unimplementedCheck = DriveWireCodec.checkSchema(1, mapOf("position" to 1, "annotation" to 1, "session" to 1))
        assertTrue(unimplementedCheck is DriveSchemaCheck.UnsupportedDomains)
        assertEquals(
            mapOf("annotation" to 1, "session" to 1),
            (unimplementedCheck as DriveSchemaCheck.UnsupportedDomains).unsupported,
        )
        assertNotNull(DriveWireCodec.deltaAppProperties("dev-a", 1L, "batch-1"))

        val ackMeta = DriveOutboxAckMetadata(
            positionAcks = listOf(
                DrivePositionAckMeta(
                    bookUrl = "file:///book.epub",
                    localRevision = 4L,
                    statusRevision = 2L,
                    progression = 0.42,
                    status = "Reading",
                    recordKey = "isbn:9780547928227/dev-a",
                    recordFp = "a".repeat(64),
                ),
            ),
            peakAcks = listOf(
                DrivePeakAckMeta(
                    bookUrl = "file:///book.epub",
                    workKey = "isbn:9780547928227",
                    peakRevision = 3L,
                    recordKey = "peak/isbn:9780547928227/sha/dev-a",
                    recordFp = "b".repeat(64),
                ),
            ),
        )
        assertEquals(ackMeta, DriveWireCodec.decodeOutboxAckMetadata(DriveWireCodec.encodeOutboxAckMetadata(ackMeta)))
    }
}
