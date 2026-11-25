package com.chmouel.liseur.drive

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Result of checking an incoming manifest, delta, or snapshot against supported schema versions. */
internal sealed interface DriveSchemaCheck {
    data object Supported : DriveSchemaCheck

    /** Entire remote device stream has a higher envelope `schemaVersion` than this build supports. */
    data class UnsupportedStream(val schemaVersion: Int, val deviceLabel: String?) : DriveSchemaCheck

    /** One or more domains in the envelope have a higher domain version than this build supports. */
    data class UnsupportedDomains(val unsupported: Map<String, Int>) : DriveSchemaCheck
}

internal data class DriveSnapshotPartRef(
    val name: String,
    val fileId: String,
    val sha256: String,
    val records: Int,
    val resolutions: Int,
)

internal data class DriveSnapshotRef(
    val throughSeq: Long,
    val parts: List<DriveSnapshotPartRef>,
)

internal data class DriveEpubFolderRef(
    val fileId: String,
    val createdTime: String?,
)

internal data class DriveManifest(
    val schemaVersion: Int = DriveWireCodec.SCHEMA_VERSION,
    val deviceId: String,
    val deviceLabel: String,
    val appVersionCode: Int,
    val updatedHlc: String,
    val snapshot: DriveSnapshotRef? = null,
    val previousSnapshot: DriveSnapshotRef? = null,
    val domains: Map<String, Int> = DriveWireCodec.PHASE3_DOMAINS,
    val epubFolder: DriveEpubFolderRef? = null,
    val appliedVector: Map<String, Long> = emptyMap(),
)

internal data class DriveWireRecord(
    val d: String,
    val k: String,
    val op: String,
    val hlc: String,
    val fp: String,
    val anc: List<String> = emptyList(),
    val resRefs: List<String> = emptyList(),
    val body: JSONObject? = null,
    val outcome: String? = null,
    val resolvedFps: List<String> = emptyList(),
)

internal data class DriveDeltaBatch(
    val schemaVersion: Int = DriveWireCodec.SCHEMA_VERSION,
    val batchId: String,
    val deviceId: String,
    val seq: Long,
    val createdHlc: String,
    val domains: Map<String, Int>,
    val records: List<DriveWireRecord>,
)

internal data class DriveSnapshotPart(
    val schemaVersion: Int = DriveWireCodec.SCHEMA_VERSION,
    val deviceId: String,
    val throughSeq: Long,
    val partIndex: Int,
    val partCount: Int,
    val createdHlc: String,
    val domains: Map<String, Int>,
    val records: List<DriveWireRecord>,
)

// -- Phase 3 Domain Wire Bodies -----------------------------------------------

internal sealed interface DriveWorkAliasBody {
    val kind: String
    val updatedAt: Long

    data class Merge(
        val from: String,
        val to: String,
        override val updatedAt: Long,
    ) : DriveWorkAliasBody {
        override val kind: String = KIND_MERGE

        init {
            require(to < from) { "workAlias merge requires to < from lexicographically ($to vs $from)" }
        }
    }

    data class Reject(
        val a: String,
        val b: String,
        override val updatedAt: Long,
    ) : DriveWorkAliasBody {
        override val kind: String = KIND_REJECT

        init {
            require(a < b) { "workAlias reject requires a < b lexicographically ($a vs $b)" }
        }
    }

    companion object {
        const val KIND_MERGE = "merge"
        const val KIND_REJECT = "reject"
    }
}

internal data class DriveCatalogIdentifier(
    val scheme: String,
    val value: String,
)

internal data class DriveCatalogEdition(
    val sha256: String,
    val sizeBytes: Long?,
    val driveFileId: String?,
    val fileName: String?,
    val uploadedAt: Long?,
    val availabilityHlc: String? = null,
)

internal data class DriveCatalogBody(
    val workKey: String,
    val title: String,
    val author: String?,
    val identifiers: List<DriveCatalogIdentifier>,
    val editions: List<DriveCatalogEdition>,
    val fileSeriesName: String?,
    val fileSeriesIndex: Double?,
    val addedAt: Long,
    val archivedAt: Long?,
    val updatedAt: Long,
    /** HLC of the archive/unarchive choice that set [archivedAt]; `null` before any choice. */
    val archiveHlc: String? = null,
)

internal data class DrivePositionBody(
    val workKey: String,
    val deviceId: String,
    val editionSha: String?,
    val locatorJson: String,
    val totalProgression: Double?,
    val status: String,
    val finishedOverride: String,
    val coverage: String?,
    val unreadResetAt: Long?,
    val readAt: Long?,
    val updatedAt: Long,
)

internal sealed interface DriveFurthestBody {
    val kind: String
    val workKey: String

    data class Peak(
        override val workKey: String,
        val editionSha: String,
        val deviceId: String,
        val seq: Long,
        val progression: Double,
        val locatorJson: String,
        val coverage: String?,
        val peakAt: Long,
    ) : DriveFurthestBody {
        override val kind: String = KIND_PEAK
    }

    data class Reset(
        override val workKey: String,
        val resetId: String,
        val floors: Map<String, Long>,
        val resetAt: Long,
    ) : DriveFurthestBody {
        override val kind: String = KIND_RESET
    }

    companion object {
        const val KIND_PEAK = "peak"
        const val KIND_RESET = "reset"
    }
}

/**
 * Wire-format encoder, decoder, and schema-version guard for `drive.appdata` v1
 * (`docs/DRIVE_SYNC.md` §2 & §4.1–4.6).
 */
internal object DriveWireCodec {

    const val SCHEMA_VERSION = 1
    const val STREAM_PAUSE_DOMAIN = "*"

    const val MAX_BATCH_RECORDS = 2_000
    const val MAX_BATCH_BYTES = 1_048_576
    private const val BATCH_ENVELOPE_MARGIN = 512
    const val MAX_ANCESTORS = 64

    const val DOMAIN_WORK_ALIAS = "workAlias"
    const val DOMAIN_CATALOG = "catalog"
    const val DOMAIN_METADATA = "metadata"
    const val DOMAIN_SERIES = "series"
    const val DOMAIN_POSITION = "position"
    const val DOMAIN_FURTHEST = "furthest"
    const val DOMAIN_ANNOTATION = "annotation"
    const val DOMAIN_EVALUATION = "evaluation"
    const val DOMAIN_VOCAB_WORD = "vocabWord"
    const val DOMAIN_VOCAB_OCCURRENCE = "vocabOccurrence"
    const val DOMAIN_VOCAB_REVIEW = "vocabReview"
    const val DOMAIN_SESSION = "session"

    /** Domains actively published and applied in Phase 3. */
    val PHASE3_DOMAINS: Map<String, Int> = mapOf(
        DOMAIN_WORK_ALIAS to 1,
        DOMAIN_CATALOG to 1,
        DOMAIN_POSITION to 1,
        DOMAIN_FURTHEST to 1,
    )

    /** Highest domain versions actively implemented and supported by this build. */
    val SUPPORTED_DOMAIN_VERSIONS: Map<String, Int> = PHASE3_DOMAINS

    /** Canonical apply order within a sync run (`docs/DRIVE_SYNC.md` §4). */
    val APPLY_ORDER: List<String> = listOf(
        DOMAIN_WORK_ALIAS,
        DOMAIN_CATALOG,
        DOMAIN_METADATA,
        DOMAIN_SERIES,
        DOMAIN_EVALUATION,
        DOMAIN_ANNOTATION,
        DOMAIN_POSITION,
        DOMAIN_FURTHEST,
        DOMAIN_VOCAB_WORD,
        DOMAIN_VOCAB_OCCURRENCE,
        DOMAIN_VOCAB_REVIEW,
        DOMAIN_SESSION,
    )

    fun domainRank(domain: String): Int {
        val idx = APPLY_ORDER.indexOf(domain)
        return if (idx >= 0) idx else APPLY_ORDER.size
    }

    fun manifestFileName(deviceId: String): String = "device-$deviceId-manifest.json"

    fun deltaFileName(deviceId: String, seq: Long): String =
        String.format(Locale.ROOT, "device-%s-seq-%010d.json", deviceId, seq)

    fun manifestAppProperties(deviceId: String): Map<String, String> = mapOf(
        "liseur" to "1",
        "kind" to DriveFile.KIND_MANIFEST,
        "schemaVersion" to SCHEMA_VERSION.toString(),
        "deviceId" to deviceId,
    )

    fun deltaAppProperties(deviceId: String, seq: Long, batchId: String): Map<String, String> = mapOf(
        "liseur" to "1",
        "kind" to DriveFile.KIND_DELTA,
        "schemaVersion" to SCHEMA_VERSION.toString(),
        "deviceId" to deviceId,
        "seq" to seq.toString(),
        "batchId" to batchId,
    )

    fun epubAppProperties(sha256: String, workKey: String): Map<String, String> = mapOf(
        "liseur" to "1",
        "liseurKind" to DriveFile.KIND_EPUB,
        "sha256" to sha256,
        "workKey" to workKey.take(DriveRestClient.MAX_APP_PROPERTY_BYTES),
    )

    /** Checks envelope and domain versions against what this build supports. */
    fun checkSchema(
        schemaVersion: Int,
        domains: Map<String, Int>,
        deviceLabel: String? = null,
    ): DriveSchemaCheck {
        if (schemaVersion > SCHEMA_VERSION) {
            return DriveSchemaCheck.UnsupportedStream(schemaVersion, deviceLabel)
        }
        val unsupported = buildMap {
            for ((domain, version) in domains) {
                val maxSupported = SUPPORTED_DOMAIN_VERSIONS[domain] ?: 0
                if (version > maxSupported) {
                    put(domain, version)
                }
            }
        }
        return if (unsupported.isEmpty()) {
            DriveSchemaCheck.Supported
        } else {
            DriveSchemaCheck.UnsupportedDomains(unsupported)
        }
    }

    // -- Manifest codec -------------------------------------------------------

    fun encodeManifest(manifest: DriveManifest): String {
        val map = linkedMapOf<String, Any?>(
            "schemaVersion" to manifest.schemaVersion,
            "kind" to DriveFile.KIND_MANIFEST,
            "deviceId" to manifest.deviceId,
            "deviceLabel" to manifest.deviceLabel,
            "appVersionCode" to manifest.appVersionCode,
            "updatedHlc" to manifest.updatedHlc,
            "snapshot" to manifest.snapshot?.let(::snapshotRefToMap),
            "previousSnapshot" to manifest.previousSnapshot?.let(::snapshotRefToMap),
            "domains" to manifest.domains,
            "epubFolder" to manifest.epubFolder?.let {
                mapOf("fileId" to it.fileId, "createdTime" to it.createdTime)
            },
            "appliedVector" to manifest.appliedVector,
        )
        return DriveCanonicalJson.encode(map)
    }

    fun decodeManifest(rawJson: String): DriveManifest {
        val json = JSONObject(rawJson)
        val schemaVersion = json.optInt("schemaVersion", 0)
        require(schemaVersion > 0) { "Manifest missing valid schemaVersion" }
        val deviceId = json.optString("deviceId").trim()
        require(deviceId.isNotEmpty()) { "Manifest missing deviceId" }
        val deviceLabel = json.optString("deviceLabel").ifBlank { deviceId }
        val appVersionCode = json.optInt("appVersionCode", 0)
        val updatedHlc = json.optString("updatedHlc")
        val snapshot = json.optJSONObject("snapshot")?.let(::parseSnapshotRef)
        val previousSnapshot = json.optJSONObject("previousSnapshot")?.let(::parseSnapshotRef)
        val domains = parseIntMap(json.optJSONObject("domains"))
        val epubFolder = json.optJSONObject("epubFolder")?.let { f ->
            val fileId = f.optString("fileId").trim()
            if (fileId.isNotEmpty()) {
                DriveEpubFolderRef(
                    fileId = fileId,
                    createdTime = f.optString("createdTime").takeIf { it.isNotEmpty() && !f.isNull("createdTime") },
                )
            } else {
                null
            }
        }
        val appliedVector = parseLongMap(json.optJSONObject("appliedVector"))
        return DriveManifest(
            schemaVersion = schemaVersion,
            deviceId = deviceId,
            deviceLabel = deviceLabel,
            appVersionCode = appVersionCode,
            updatedHlc = updatedHlc,
            snapshot = snapshot,
            previousSnapshot = previousSnapshot,
            domains = domains,
            epubFolder = epubFolder,
            appliedVector = appliedVector,
        )
    }

    // -- Delta batch codec ----------------------------------------------------

    fun encodeDeltaBatch(batch: DriveDeltaBatch): String {
        val map = linkedMapOf<String, Any?>(
            "schemaVersion" to batch.schemaVersion,
            "kind" to DriveFile.KIND_DELTA,
            "batchId" to batch.batchId,
            "deviceId" to batch.deviceId,
            "seq" to batch.seq,
            "createdHlc" to batch.createdHlc,
            "domains" to batch.domains,
            "records" to batch.records.map(::wireRecordToMap),
        )
        return DriveCanonicalJson.encode(map)
    }

    fun decodeDeltaBatch(rawJson: String): DriveDeltaBatch {
        val json = JSONObject(rawJson)
        val schemaVersion = json.optInt("schemaVersion", 0)
        require(schemaVersion > 0) { "Delta batch missing valid schemaVersion" }
        val deviceId = json.optString("deviceId").trim()
        require(deviceId.isNotEmpty()) { "Delta batch missing deviceId" }
        val seq = json.optLong("seq", -1L)
        require(seq > 0L) { "Delta batch missing positive seq" }
        val batchId = json.optString("batchId").ifBlank { DriveCanonicalJson.batchId(deviceId, seq) }
        val createdHlc = json.optString("createdHlc")
        val domains = parseIntMap(json.optJSONObject("domains"))
        val records = if (schemaVersion <= SCHEMA_VERSION) {
            parseWireRecords(json.optJSONArray("records"))
        } else {
            emptyList()
        }
        return DriveDeltaBatch(
            schemaVersion = schemaVersion,
            batchId = batchId,
            deviceId = deviceId,
            seq = seq,
            createdHlc = createdHlc,
            domains = domains,
            records = records,
        )
    }

    // -- Snapshot part decoder (for bootstrap / fallback) ---------------------

    fun decodeSnapshotPart(rawJson: String): DriveSnapshotPart {
        val json = JSONObject(rawJson)
        val schemaVersion = json.optInt("schemaVersion", 0)
        require(schemaVersion > 0) { "Snapshot part missing valid schemaVersion" }
        val deviceId = json.optString("deviceId").trim()
        require(deviceId.isNotEmpty()) { "Snapshot part missing deviceId" }
        val throughSeq = json.optLong("throughSeq", 0L)
        val partIndex = json.optInt("partIndex", 0)
        val partCount = json.optInt("partCount", 1)
        val createdHlc = json.optString("createdHlc")
        val domains = parseIntMap(json.optJSONObject("domains"))
        val records = if (schemaVersion <= SCHEMA_VERSION) {
            parseWireRecords(json.optJSONArray("records"))
        } else {
            emptyList()
        }
        return DriveSnapshotPart(
            schemaVersion = schemaVersion,
            deviceId = deviceId,
            throughSeq = throughSeq,
            partIndex = partIndex,
            partCount = partCount,
            createdHlc = createdHlc,
            domains = domains,
            records = records,
        )
    }

    // -- Domain body codecs ---------------------------------------------------

    fun encodeWorkAliasBody(body: DriveWorkAliasBody): Map<String, Any?> = when (body) {
        is DriveWorkAliasBody.Merge -> mapOf(
            "kind" to DriveWorkAliasBody.KIND_MERGE,
            "from" to body.from,
            "to" to body.to,
            "updatedAt" to body.updatedAt,
        )
        is DriveWorkAliasBody.Reject -> mapOf(
            "kind" to DriveWorkAliasBody.KIND_REJECT,
            "a" to body.a,
            "b" to body.b,
            "updatedAt" to body.updatedAt,
        )
    }

    fun decodeWorkAliasBody(json: JSONObject): DriveWorkAliasBody? = when (json.optString("kind")) {
        DriveWorkAliasBody.KIND_MERGE -> {
            val from = json.optString("from").trim()
            val to = json.optString("to").trim()
            if (from.isEmpty() || to.isEmpty() || to >= from) return null
            DriveWorkAliasBody.Merge(
                from = from,
                to = to,
                updatedAt = json.optLong("updatedAt", 0L),
            )
        }
        DriveWorkAliasBody.KIND_REJECT -> {
            val a = json.optString("a").trim()
            val b = json.optString("b").trim()
            if (a.isEmpty() || b.isEmpty() || a >= b) return null
            DriveWorkAliasBody.Reject(
                a = a,
                b = b,
                updatedAt = json.optLong("updatedAt", 0L),
            )
        }
        else -> null
    }

    fun encodeCatalogBody(body: DriveCatalogBody): Map<String, Any?> = mapOf(
        "workKey" to body.workKey,
        "title" to body.title,
        "author" to body.author,
        "identifiers" to body.identifiers
            .distinctBy { it.scheme to it.value }
            .sortedWith(compareBy({ it.scheme }, { it.value }))
            .map { mapOf("scheme" to it.scheme, "value" to it.value) },
        "editions" to body.editions
            .distinctBy { it.sha256 }
            .sortedBy { it.sha256 }
            .map {
                mapOf(
                    "sha256" to it.sha256,
                    "sizeBytes" to it.sizeBytes,
                    "driveFileId" to it.driveFileId,
                    "fileName" to it.fileName,
                    "uploadedAt" to it.uploadedAt,
                    "availabilityHlc" to it.availabilityHlc,
                )
            },
        "fileSeriesName" to body.fileSeriesName,
        "fileSeriesIndex" to body.fileSeriesIndex,
        "addedAt" to body.addedAt,
        "archivedAt" to body.archivedAt,
        "archiveHlc" to body.archiveHlc,
        "updatedAt" to body.updatedAt,
    )

    fun decodeCatalogBody(json: JSONObject): DriveCatalogBody? {
        val workKey = json.optString("workKey").trim()
        if (workKey.isEmpty()) return null
        val title = json.optString("title")
        val author = json.optNullableString("author")
        val identifiersArr = json.optJSONArray("identifiers") ?: JSONArray()
        val identifiers = buildList(identifiersArr.length()) {
            for (i in 0 until identifiersArr.length()) {
                val obj = identifiersArr.optJSONObject(i) ?: continue
                val scheme = obj.optString("scheme").trim()
                val value = obj.optString("value").trim()
                if (value.isNotEmpty()) {
                    add(DriveCatalogIdentifier(scheme = scheme, value = value))
                }
            }
        }
        val editionsArr = json.optJSONArray("editions") ?: JSONArray()
        val editions = buildList(editionsArr.length()) {
            for (i in 0 until editionsArr.length()) {
                val obj = editionsArr.optJSONObject(i) ?: continue
                val sha = obj.optString("sha256").trim().lowercase(Locale.ROOT)
                if (sha.isEmpty()) continue
                val fileId = obj.optNullableString("driveFileId")
                val availabilityHlc = obj.optNullableString("availabilityHlc")
                if (fileId != null && availabilityHlc == null) return null
                add(
                    DriveCatalogEdition(
                        sha256 = sha,
                        sizeBytes = obj.optNullableLong("sizeBytes"),
                        driveFileId = fileId,
                        fileName = obj.optNullableString("fileName"),
                        uploadedAt = obj.optNullableLong("uploadedAt"),
                        availabilityHlc = availabilityHlc,
                    ),
                )
            }
        }
        return DriveCatalogBody(
            workKey = workKey,
            title = title,
            author = author,
            identifiers = identifiers,
            editions = editions,
            fileSeriesName = json.optNullableString("fileSeriesName"),
            fileSeriesIndex = json.optNullableDouble("fileSeriesIndex"),
            addedAt = json.optLong("addedAt", 0L),
            archivedAt = json.optNullableLong("archivedAt"),
            updatedAt = json.optLong("updatedAt", 0L),
            archiveHlc = json.optNullableString("archiveHlc"),
        )
    }

    fun encodePositionBody(body: DrivePositionBody): Map<String, Any?> {
        body.totalProgression?.let { prog ->
            require(prog.isFinite() && prog in 0.0..1.0) { "Invalid totalProgression: $prog" }
        }
        return mapOf(
            "workKey" to body.workKey,
            "deviceId" to body.deviceId,
            "editionSha" to body.editionSha,
            "locatorJson" to DriveCanonicalJson.canonicalizeJsonString(body.locatorJson.ifBlank { "{}" }),
            "totalProgression" to body.totalProgression,
            "status" to body.status,
            "finishedOverride" to body.finishedOverride,
            "coverage" to body.coverage,
            "unreadResetAt" to body.unreadResetAt,
            "readAt" to body.readAt,
            "updatedAt" to body.updatedAt,
        )
    }

    fun decodePositionBody(json: JSONObject): DrivePositionBody? {
        val workKey = json.optString("workKey").trim()
        val deviceId = json.optString("deviceId").trim()
        if (workKey.isEmpty() || deviceId.isEmpty()) return null
        val progression = json.optNullableDouble("totalProgression")
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val status = json.optString("status").ifBlank { "ReadyToRead" }
        val finishedOverride = json.optString("finishedOverride").ifBlank { "automatic" }
        return DrivePositionBody(
            workKey = workKey,
            deviceId = deviceId,
            editionSha = json.optNullableString("editionSha")?.lowercase(Locale.ROOT),
            locatorJson = json.optString("locatorJson").ifBlank { "{}" },
            totalProgression = progression,
            status = status,
            finishedOverride = finishedOverride,
            coverage = json.optNullableString("coverage"),
            unreadResetAt = json.optNullableLong("unreadResetAt"),
            readAt = json.optNullableLong("readAt"),
            updatedAt = json.optLong("updatedAt", 0L),
        )
    }

    fun encodeFurthestBody(body: DriveFurthestBody): Map<String, Any?> = when (body) {
        is DriveFurthestBody.Peak -> {
            require(body.progression.isFinite() && body.progression in 0.0..1.0) {
                "Invalid furthest progression: ${body.progression}"
            }
            mapOf(
                "kind" to DriveFurthestBody.KIND_PEAK,
                "workKey" to body.workKey,
                "editionSha" to body.editionSha,
                "deviceId" to body.deviceId,
                "seq" to body.seq,
                "progression" to body.progression,
                "locatorJson" to DriveCanonicalJson.canonicalizeJsonString(body.locatorJson.ifBlank { "{}" }),
                "coverage" to body.coverage,
                "peakAt" to body.peakAt,
            )
        }
        is DriveFurthestBody.Reset -> mapOf(
            "kind" to DriveFurthestBody.KIND_RESET,
            "workKey" to body.workKey,
            "resetId" to body.resetId,
            "floors" to body.floors,
            "resetAt" to body.resetAt,
        )
    }

    fun decodeFurthestBody(json: JSONObject): DriveFurthestBody? = when (json.optString("kind")) {
        DriveFurthestBody.KIND_PEAK -> {
            val workKey = json.optString("workKey").trim()
            val editionSha = json.optString("editionSha").trim().lowercase(Locale.ROOT)
            val deviceId = json.optString("deviceId").trim()
            val progression = json.optNullableDouble("progression")
                ?.takeIf { it.isFinite() && it in 0.0..1.0 }
            val seq = json.optLong("seq", -1L)
            if (workKey.isEmpty() || deviceId.isEmpty() || progression == null || seq < 0L) return null
            DriveFurthestBody.Peak(
                workKey = workKey,
                editionSha = editionSha,
                deviceId = deviceId,
                seq = seq,
                progression = progression,
                locatorJson = json.optString("locatorJson").ifBlank { "{}" },
                coverage = json.optNullableString("coverage"),
                peakAt = json.optLong("peakAt", 0L),
            )
        }
        DriveFurthestBody.KIND_RESET -> {
            val workKey = json.optString("workKey").trim()
            val resetId = json.optString("resetId").trim()
            if (workKey.isEmpty() || resetId.isEmpty()) return null
            DriveFurthestBody.Reset(
                workKey = workKey,
                resetId = resetId,
                floors = parseLongMap(json.optJSONObject("floors")),
                resetAt = json.optLong("resetAt", 0L),
            )
        }
        else -> null
    }

    /** Creates a `put` [DriveWireRecord] with canonical fingerprint and capped ancestry. */
    fun putRecord(
        domain: String,
        key: String,
        hlc: String,
        bodyMap: Map<String, Any?>,
        ancestors: List<String> = emptyList(),
        resRefs: List<String> = emptyList(),
    ): DriveWireRecord {
        val canonicalBody = DriveCanonicalJson.encode(bodyMap)
        val fp = DriveCanonicalJson.putFp(bodyMap, hlc)
        return DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_PUT,
            hlc = hlc,
            fp = fp,
            anc = ancestors.distinct().take(MAX_ANCESTORS),
            resRefs = resRefs.distinct().sorted(),
            body = JSONObject(canonicalBody),
        )
    }

    /** Records that fit in batches, plus single records too large for any batch; none is dropped silently. */
    data class BatchChunking(
        val chunks: List<List<DriveWireRecord>>,
        val oversized: List<DriveWireRecord>,
    )

    /** Splits outgoing records into size- and count-bounded chunks respecting 2,000 records / 1 MiB. */
    fun chunkRecordsForBatches(records: List<DriveWireRecord>): BatchChunking {
        val chunks = mutableListOf<List<DriveWireRecord>>()
        val oversized = mutableListOf<DriveWireRecord>()
        var current = mutableListOf<DriveWireRecord>()
        var currentBytes = BATCH_ENVELOPE_MARGIN
        for (record in records) {
            val encodedSize = DriveCanonicalJson.encode(wireRecordToMap(record)).toByteArray(Charsets.UTF_8).size + 2
            if (BATCH_ENVELOPE_MARGIN + encodedSize > MAX_BATCH_BYTES) {
                oversized.add(record)
                continue
            }
            if (current.isNotEmpty() && (current.size >= MAX_BATCH_RECORDS || currentBytes + encodedSize > MAX_BATCH_BYTES)) {
                chunks.add(current)
                current = mutableListOf()
                currentBytes = BATCH_ENVELOPE_MARGIN
            }
            current.add(record)
            currentBytes += encodedSize
        }
        if (current.isNotEmpty()) chunks.add(current)
        return BatchChunking(chunks, oversized)
    }

    private fun wireRecordToMap(record: DriveWireRecord): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        put("d", record.d)
        put("k", record.k)
        put("op", record.op)
        if (record.op == DriveRecordState.OP_RESOLVE) {
            put("outcome", record.outcome)
            put("resolvedFps", record.resolvedFps.distinct().sorted())
        }
        put("hlc", record.hlc)
        put("fp", record.fp)
        put("anc", record.anc.distinct().take(MAX_ANCESTORS))
        put("resRefs", record.resRefs.distinct().sorted())
        put("body", record.body)
    }

    private fun parseWireRecords(array: JSONArray?): List<DriveWireRecord> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val d = obj.optString("d").trim()
                val k = obj.optString("k").trim()
                val op = obj.optString("op").trim()
                val hlc = obj.optString("hlc").trim()
                val fp = obj.optString("fp").trim()
                if (d.isEmpty() || k.isEmpty() || op.isEmpty() || fp.isEmpty()) continue
                add(
                    DriveWireRecord(
                        d = d,
                        k = k,
                        op = op,
                        hlc = hlc,
                        fp = fp,
                        anc = parseStringList(obj.optJSONArray("anc")),
                        resRefs = parseStringList(obj.optJSONArray("resRefs")),
                        body = if (obj.isNull("body")) null else obj.optJSONObject("body"),
                        outcome = obj.optNullableString("outcome"),
                        resolvedFps = parseStringList(obj.optJSONArray("resolvedFps")),
                    ),
                )
            }
        }
    }

    private fun snapshotRefToMap(ref: DriveSnapshotRef): Map<String, Any?> = mapOf(
        "throughSeq" to ref.throughSeq,
        "parts" to ref.parts.map { part ->
            mapOf(
                "name" to part.name,
                "fileId" to part.fileId,
                "sha256" to part.sha256,
                "records" to part.records,
                "resolutions" to part.resolutions,
            )
        },
    )

    private fun parseSnapshotRef(json: JSONObject): DriveSnapshotRef {
        val throughSeq = json.optLong("throughSeq", 0L)
        val partsArr = json.optJSONArray("parts") ?: JSONArray()
        val parts = buildList(partsArr.length()) {
            for (i in 0 until partsArr.length()) {
                val p = partsArr.optJSONObject(i) ?: continue
                val fileId = p.optString("fileId").trim()
                val sha = p.optString("sha256").trim()
                if (fileId.isEmpty()) continue
                add(
                    DriveSnapshotPartRef(
                        name = p.optString("name"),
                        fileId = fileId,
                        sha256 = sha,
                        records = p.optInt("records", 0),
                        resolutions = p.optInt("resolutions", 0),
                    ),
                )
            }
        }
        return DriveSnapshotRef(throughSeq = throughSeq, parts = parts)
    }

    private fun parseStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    private fun parseIntMap(obj: JSONObject?): Map<String, Int> {
        if (obj == null) return emptyMap()
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!obj.isNull(k)) put(k, obj.optInt(k, 0))
            }
        }
    }

    private fun parseLongMap(obj: JSONObject?): Map<String, Long> {
        if (obj == null) return emptyMap()
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!obj.isNull(k)) put(k, obj.optLong(k, 0L))
            }
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
}
