package com.chmouel.liseur.drive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(put1, DriveWireCodec.putRecord("catalog", "key", "0017450000000-0001-dev-a", body).fp)
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
            setOf(
                "workAlias",
                "catalog",
                "metadata",
                "series",
                "position",
                "furthest",
                "annotation",
                "evaluation",
                "vocabWord",
                "vocabOccurrence",
                "vocabReview",
                "session",
            ),
            DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS.keys,
        )
        assertEquals(
            DriveSchemaCheck.Supported,
            DriveWireCodec.checkSchema(
                1,
                mapOf(
                    "catalog" to 1,
                    "position" to 1,
                    "annotation" to 1,
                    "metadata" to 1,
                    "series" to 1,
                    "evaluation" to 1,
                    "vocabWord" to 1,
                    "vocabOccurrence" to 1,
                    "vocabReview" to 1,
                    "session" to 1,
                ),
            ),
        )
        val streamCheck = DriveWireCodec.checkSchema(2, mapOf("catalog" to 1), deviceLabel = "Future Tablet")
        assertTrue(streamCheck is DriveSchemaCheck.UnsupportedStream)
        assertEquals(2, (streamCheck as DriveSchemaCheck.UnsupportedStream).schemaVersion)
        assertEquals("Future Tablet", streamCheck.deviceLabel)

        val domainCheck = DriveWireCodec.checkSchema(1, mapOf("catalog" to 2, "position" to 1))
        assertTrue(domainCheck is DriveSchemaCheck.UnsupportedDomains)
        assertEquals(mapOf("catalog" to 2), (domainCheck as DriveSchemaCheck.UnsupportedDomains).unsupported)

        val unimplementedCheck = DriveWireCodec.checkSchema(1, mapOf("position" to 1, "futureStats" to 1, "session" to 2))
        assertTrue(unimplementedCheck is DriveSchemaCheck.UnsupportedDomains)
        assertEquals(
            mapOf("futureStats" to 1, "session" to 2),
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
            vocabWordAcks = listOf(
                DriveVocabWordAckMeta(
                    language = "fr",
                    normalized = "ephemere",
                    wordId = 7L,
                    recordKey = "2:fr:ephemere",
                    recordFp = "c".repeat(64),
                    baseJson = """{"word":"éphémère"}""",
                    isDelete = false,
                ),
            ),
            vocabReviewAcks = listOf(
                DriveVocabReviewAckMeta(
                    reviewId = "rev-1",
                    localReviewId = 11L,
                    language = "fr",
                    normalized = "ephemere",
                    recordFp = "d".repeat(64),
                    isDelete = false,
                ),
            ),
            sessionAcks = listOf(
                DriveSessionAckMeta(
                    remoteSessionId = "sess-1",
                    recordFp = "e".repeat(64),
                ),
            ),
        )
        assertEquals(ackMeta, DriveWireCodec.decodeOutboxAckMetadata(DriveWireCodec.encodeOutboxAckMetadata(ackMeta)))
    }

    @Test
    fun `records whose body names a different record than their key are rejected`() {
        fun record(d: String, k: String, body: Map<String, Any?>) = DriveWireRecord(
            d = d, k = k, op = DriveRecordState.OP_PUT, hlc = "0017450000000-0001-dev-a",
            fp = DriveCanonicalJson.contentFp(body), anc = emptyList(), resRefs = emptyList(),
            body = org.json.JSONObject(DriveCanonicalJson.encode(body)),
        )
        val annotation = mapOf("id" to "ann-existing", "workKey" to "isbn:1")
        assertTrue(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_ANNOTATION, "ann-existing", annotation)))
        assertFalse(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_ANNOTATION, "ann-fresh", annotation)))
        val evaluation = mapOf("workKey" to "isbn:1", "field" to "rating")
        assertTrue(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_EVALUATION, "isbn:1/rating", evaluation)))
        assertFalse(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_EVALUATION, "isbn:2/rating", evaluation)))
        assertFalse(
            DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_SERIES, "isbn:2", mapOf("workKey" to "isbn:1"))),
        )
        assertFalse(
            DriveWireCodec.bodyMatchesKey(
                record(DriveWireCodec.DOMAIN_POSITION, "isbn:1/dev-b", mapOf("workKey" to "isbn:1", "deviceId" to "dev-a")),
            ),
        )
        val word = mapOf("language" to "fr", "normalized" to "nuit")
        assertTrue(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_VOCAB_WORD, "2:fr:nuit", word)))
        assertFalse(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_VOCAB_WORD, "2:fr:jour", word)))
        val occurrence = mapOf("wordKey" to "2:fr:nuit", "occurrenceKey" to "occ-1")
        assertTrue(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE, "2:fr:nuit/occ-1", occurrence)))
        assertFalse(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE, "2:fr:nuit/occ-2", occurrence)))
        val review = mapOf("syncId" to "rev-1", "wordKey" to "2:fr:nuit", "language" to "fr", "normalized" to "nuit")
        assertTrue(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_VOCAB_REVIEW, "rev-1", review)))
        assertFalse(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_VOCAB_REVIEW, "rev-2", review)))
        assertFalse(
            DriveWireCodec.bodyMatchesKey(
                record(DriveWireCodec.DOMAIN_VOCAB_REVIEW, "rev-1", review + ("wordKey" to "2:fr:jour")),
            ),
        )
        assertTrue(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_SESSION, "s-1", mapOf("sessionId" to "s-1"))))
        assertFalse(DriveWireCodec.bodyMatchesKey(record(DriveWireCodec.DOMAIN_SESSION, "s-2", mapOf("sessionId" to "s-1"))))
    }

    @Test
    fun `Phase 5 vocabulary and session domain bodies roundtrip cleanly`() {
        val wordBody = DriveVocabWordBody(
            language = "fr",
            normalized = "ephemere",
            word = "éphémère",
            definition = "short-lived",
            translationLanguage = "en",
            notes = "From chapter 3",
            definitionEdited = true,
            definitionUpdatedAt = 1_745_000_000_000L,
            notesUpdatedAt = 1_745_000_001_000L,
            addedAt = 1_745_000_000_000L,
            baseline = DriveVocabBaselineBody(
                dueAt = 1_745_086_400_000L,
                reviewedAt = 1_745_000_000_000L,
                streak = 2,
                intervalMinutes = 1440,
                repetitions = 2,
            ),
        )
        val encodedWord = DriveCanonicalJson.encode(DriveWireCodec.encodeVocabWordBody(wordBody))
        assertEquals(wordBody, DriveWireCodec.decodeVocabWordBody(JSONObject(encodedWord)))

        val occBody = DriveVocabOccurrenceBody(
            wordKey = "2:fr:ephemere",
            occurrenceKey = "occ-key-1",
            workKey = "isbn:9780547928227",
            bookTitle = "Le Petit Prince",
            locationKey = "ch03#p12",
            locator = """{"href":"ch03.xhtml","locations":{"progression":0.25}}""",
            sentence = "Cette fleur est éphémère.",
            sentenceEdited = false,
            sentenceUpdatedAt = 1_745_000_000_000L,
            lastSeenAt = 1_745_000_000_000L,
            encounters = 2,
        )
        val encodedOcc = DriveCanonicalJson.encode(DriveWireCodec.encodeVocabOccurrenceBody(occBody))
        assertEquals(occBody, DriveWireCodec.decodeVocabOccurrenceBody(JSONObject(encodedOcc)))

        val reviewBody = DriveVocabReviewBody(
            syncId = "rev-uuid-1",
            wordKey = "2:fr:ephemere",
            language = "fr",
            normalized = "ephemere",
            rating = "GOOD",
            reviewedAt = 1_745_000_000_000L,
            intervalMinutes = 1440,
            dueAt = 1_745_086_400_000L,
        )
        val encodedReview = DriveCanonicalJson.encode(DriveWireCodec.encodeVocabReviewBody(reviewBody))
        assertEquals(reviewBody, DriveWireCodec.decodeVocabReviewBody(JSONObject(encodedReview)))

        val sessionBody = DriveSessionBody(
            sessionId = "sess-uuid-1",
            workKey = "isbn:9780547928227",
            startedAt = 1_745_000_000_000L,
            endedAt = 1_745_001_800_000L,
            durationMs = 1_750_000L,
            idleMs = 50_000L,
            startProgression = 0.1,
            endProgression = 0.185,
            originDeviceId = "dev-a",
        )
        val encodedSession = DriveCanonicalJson.encode(DriveWireCodec.encodeSessionBody(sessionBody))
        assertEquals(sessionBody, DriveWireCodec.decodeSessionBody(JSONObject(encodedSession)))

        val delWithObserved = DriveWireCodec.delRecord(
            domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
            key = "2:fr:ephemere",
            hlc = "0017450000000-0004-dev-a",
            observed = mapOf("dev-b" to 3L),
        )
        val delWithEmptyObserved = DriveWireCodec.delRecord(
            domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
            key = "2:fr:soleil",
            hlc = "0017450000000-0005-dev-a",
            observed = emptyMap(),
        )
        val batchWithObserved = DriveDeltaBatch(
            batchId = DriveCanonicalJson.batchId("dev-a", 2L),
            deviceId = "dev-a",
            seq = 2L,
            createdHlc = "0017450000000-0005-dev-a",
            domains = mapOf(DriveWireCodec.DOMAIN_VOCAB_WORD to 1),
            records = listOf(delWithObserved, delWithEmptyObserved),
            observed = mapOf("dev-b" to 3L, "dev-c" to 1L),
        )
        val decodedWithObserved = DriveWireCodec.decodeDeltaBatch(DriveWireCodec.encodeDeltaBatch(batchWithObserved))
        assertEquals(mapOf("dev-b" to 3L, "dev-c" to 1L), decodedWithObserved.observed)
        assertEquals(mapOf("dev-b" to 3L), decodedWithObserved.records[0].observed)
        assertEquals(emptyMap<String, Long>(), decodedWithObserved.records[1].observed)
    }

    @Test
    fun `present non-object observed decodes to empty while missing stays null`() {
        val batch = DriveDeltaBatch(
            batchId = DriveCanonicalJson.batchId("dev-a", 3L),
            deviceId = "dev-a",
            seq = 3L,
            createdHlc = "0017450000000-0006-dev-a",
            domains = mapOf(DriveWireCodec.DOMAIN_VOCAB_WORD to 3),
            records = listOf(
                DriveWireCodec.delRecord(DriveWireCodec.DOMAIN_VOCAB_WORD, "2:fr:a", "0017450000000-0006-dev-a"),
                DriveWireCodec.delRecord(DriveWireCodec.DOMAIN_VOCAB_WORD, "2:fr:b", "0017450000000-0007-dev-a"),
                DriveWireCodec.delRecord(DriveWireCodec.DOMAIN_VOCAB_WORD, "2:fr:c", "0017450000000-0008-dev-a"),
            ),
            observed = mapOf("dev-b" to 3L),
        )
        val json = DriveWireCodec.encodeDeltaBatch(batch)
        val root = JSONObject(json)
        val recs = root.getJSONArray("records")
        recs.getJSONObject(0).put("observed", JSONObject.NULL)
        recs.getJSONObject(1).put("observed", "garbage")
        recs.getJSONObject(2).remove("observed")
        val decoded = DriveWireCodec.decodeDeltaBatch(root.toString())
        assertEquals(emptyMap<String, Long>(), decoded.records[0].observed)
        assertEquals(emptyMap<String, Long>(), decoded.records[1].observed)
        assertNull(decoded.records[2].observed)
    }

    @Test
    fun `word keys with impossible language lengths are rejected without throwing`() {
        assertEquals("fr" to "nuit", DriveVocabularySync.parseWordKey("2:fr:nuit"))
        for (bad in listOf("2147483647:fr:nuit", "99999999999:fr:nuit", "3:fr", "2:frnuit", "2:fr:", ":fr:nuit", "0::x", "-1:fr:x")) {
            assertNull(bad, DriveVocabularySync.parseWordKey(bad))
        }
    }
}
