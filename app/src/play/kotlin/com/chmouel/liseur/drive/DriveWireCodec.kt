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
    val domains: Map<String, Int> = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
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
    val observed: Map<String, Long>? = null,
    /**
     * Snapshot records only: the author's publication seq of this version, so provenance
     * checks (vocabulary delete observed vectors) do not mistake it for `throughSeq`.
     */
    val seq: Long? = null,
)

internal data class DriveDeltaBatch(
    val schemaVersion: Int = DriveWireCodec.SCHEMA_VERSION,
    val batchId: String,
    val deviceId: String,
    val seq: Long,
    val createdHlc: String,
    val domains: Map<String, Int>,
    val records: List<DriveWireRecord>,
    val observed: Map<String, Long> = emptyMap(),
)

internal data class DriveSnapshotResolution(
    val d: String,
    val k: String,
    val resolutionFp: String,
    val resolvedFps: List<String>,
    val outcome: String,
    val hlc: String,
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
    val resolutions: List<DriveSnapshotResolution> = emptyList(),
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

// -- Phase 4 Domain Wire Bodies -----------------------------------------------

internal data class DriveAnnotationBody(
    val id: String,
    val workKey: String,
    val editionSha: String?,
    val kind: String,
    val locatorJson: String,
    val text: String?,
    val note: String?,
    val tint: String?,
    val chapter: String?,
    val totalProgression: Double?,
    val createdAt: Long,
    val noteCreatedAt: Long?,
    val noteUpdatedAt: Long?,
    val updatedAt: Long,
)

internal data class DriveEvaluationBody(
    val workKey: String,
    val field: String,
    val value: String,
    val updatedAt: Long,
)

internal sealed interface DriveMetadataBody {
    val workKey: String
    val field: String
    val updatedAt: Long

    data class Text(
        override val workKey: String,
        override val field: String,
        val value: String,
        override val updatedAt: Long,
    ) : DriveMetadataBody

    data class Cover(
        override val workKey: String,
        val coverSha256: String,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        override val updatedAt: Long,
    ) : DriveMetadataBody {
        override val field: String = com.chmouel.liseur.data.db.MetadataOverrideFields.COVER
    }
}

internal data class DriveSeriesBody(
    val workKey: String,
    val seriesOverridden: Boolean,
    val userSeriesName: String?,
    val indexOverridden: Boolean,
    val userSeriesIndex: Double?,
    val seriesKey: String,
    val updatedAt: Long,
)

/** Atomic `sent_json` payload for revision-based `evaluation_sync` and `metadata_override_sync` tables. */
internal data class DriveRevisionAckPayload(
    val vfp: String,
    val cfp: String?,
    val rev: Long,
    val op: String = DriveRecordState.OP_PUT,
    val hlc: String? = null,
    val bodyJson: String? = null,
)

internal data class DriveAnnotationAckMeta(
    val id: String,
    val bookId: String,
    val workId: String,
    val ackedFingerprint: String,
    val recordFp: String,
)

internal data class DriveEvaluationAckMeta(
    val bookUrl: String,
    val field: String,
    val workKey: String,
    val targetRevision: Long,
    val recordKey: String,
    val recordFp: String,
    val contentFp: String?,
    val recordOp: String,
)

internal data class DriveMetadataAckMeta(
    val bookUrl: String,
    val field: String,
    val workKey: String,
    val targetRevision: Long,
    val recordKey: String,
    val recordFp: String,
    val contentFp: String?,
    val recordOp: String,
    val remoteCoverSha256: String? = null,
    val remoteCoverUpdatedAt: Long = 0L,
)

// -- Phase 5 Domain Wire Bodies -----------------------------------------------

internal data class DriveVocabBaselineBody(
    val dueAt: Long,
    val reviewedAt: Long?,
    val streak: Int,
    val intervalMinutes: Int,
    val repetitions: Int,
)

internal data class DriveVocabWordBody(
    val language: String,
    val normalized: String,
    val word: String,
    val definition: String?,
    val translationLanguage: String?,
    val notes: String,
    val definitionEdited: Boolean,
    val definitionUpdatedAt: Long,
    val notesUpdatedAt: Long,
    val addedAt: Long,
    val baseline: DriveVocabBaselineBody?,
)

internal data class DriveVocabOccurrenceBody(
    val wordKey: String,
    val occurrenceKey: String,
    val workKey: String?,
    val bookTitle: String,
    val locationKey: String,
    val locator: String?,
    val sentence: String?,
    val sentenceEdited: Boolean,
    val sentenceUpdatedAt: Long,
    val lastSeenAt: Long,
    val encounters: Int,
)

internal data class DriveVocabReviewBody(
    val syncId: String,
    val wordKey: String,
    val language: String,
    val normalized: String,
    val rating: String,
    val reviewedAt: Long,
    val intervalMinutes: Int,
    val dueAt: Long,
)

internal data class DriveSessionBody(
    val sessionId: String,
    val workKey: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val idleMs: Long,
    val startProgression: Double,
    val endProgression: Double,
    val originDeviceId: String,
)

internal data class DriveVocabWordAckMeta(
    val language: String,
    val normalized: String,
    val wordId: Long?,
    val recordKey: String,
    val recordFp: String,
    val baseJson: String?,
    val isDelete: Boolean,
)

internal data class DriveVocabReviewAckMeta(
    val reviewId: String,
    val localReviewId: Long?,
    val language: String,
    val normalized: String,
    val recordFp: String,
    val isDelete: Boolean,
)

internal data class DriveSessionAckMeta(
    val remoteSessionId: String,
    val recordFp: String,
)

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
    const val MAX_SNAPSHOT_PART_BYTES = 4 * 1024 * 1024
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

    /** Domains actively published and applied in Phase 4. */
    val PHASE4_DOMAINS: Map<String, Int> = PHASE3_DOMAINS + mapOf(
        DOMAIN_METADATA to 1,
        DOMAIN_SERIES to 1,
        DOMAIN_ANNOTATION to 1,
        DOMAIN_EVALUATION to 1,
    )

    /** All v1 domains actively published and applied as of Phase 5. */
    val PHASE5_DOMAINS: Map<String, Int> = PHASE4_DOMAINS + mapOf(
        DOMAIN_VOCAB_WORD to 1,
        DOMAIN_VOCAB_OCCURRENCE to 1,
        DOMAIN_VOCAB_REVIEW to 1,
        DOMAIN_SESSION to 1,
    )

    /** Highest domain versions actively implemented and supported by this build. */
    val SUPPORTED_DOMAIN_VERSIONS: Map<String, Int> = PHASE5_DOMAINS

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

    fun snapshotPartFileName(deviceId: String, throughSeq: Long, partIndex: Int): String =
        String.format(Locale.ROOT, "device-%s-snapshot-%010d-part-%03d.json", deviceId, throughSeq, partIndex)

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

    fun snapshotPartAppProperties(
        deviceId: String,
        throughSeq: Long,
        partIndex: Int,
        sha256: String? = null,
    ): Map<String, String> = buildMap {
        put("liseur", "1")
        put("kind", DriveFile.KIND_SNAPSHOT)
        put("schemaVersion", SCHEMA_VERSION.toString())
        put("deviceId", deviceId)
        put("throughSeq", throughSeq.toString())
        put("partIndex", partIndex.toString())
        sha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { put("sha256", it) }
    }

    fun epubAppProperties(sha256: String, workKey: String): Map<String, String> = mapOf(
        "liseur" to "1",
        "liseurKind" to DriveFile.KIND_EPUB,
        "sha256" to sha256,
        "workKey" to workKey.take(DriveRestClient.MAX_APP_PROPERTY_BYTES - "workKey".length),
    )

    fun coverFileName(sha256: String): String =
        "cover-${sha256.trim().lowercase(Locale.ROOT)}.webp"

    fun coverAppProperties(sha256: String): Map<String, String> = mapOf(
        "liseur" to "1",
        "kind" to DriveFile.KIND_COVER,
        "schemaVersion" to SCHEMA_VERSION.toString(),
        "sha256" to sha256.trim().lowercase(Locale.ROOT),
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
        if (batch.observed.isNotEmpty()) {
            map["observed"] = batch.observed
        }
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
        val observed = parseLongMap(json.optJSONObject("observed"))
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
            observed = observed,
        )
    }

    // -- Snapshot part encoder & decoder --------------------------------------

    fun encodeSnapshotPart(part: DriveSnapshotPart): String {
        val map = linkedMapOf<String, Any?>(
            "schemaVersion" to part.schemaVersion,
            "kind" to DriveFile.KIND_SNAPSHOT,
            "deviceId" to part.deviceId,
            "throughSeq" to part.throughSeq,
            "partIndex" to part.partIndex,
            "partCount" to part.partCount,
            "createdHlc" to part.createdHlc,
            "domains" to part.domains,
            "records" to part.records.map(::wireRecordToMap),
            "resolutions" to part.resolutions.map(::snapshotResolutionToMap),
        )
        return DriveCanonicalJson.encode(map)
    }

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
        val resolutions = if (schemaVersion <= SCHEMA_VERSION) {
            parseSnapshotResolutions(json.optJSONArray("resolutions"))
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
            resolutions = resolutions,
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

    // -- Phase 4 Domain Body Codecs -------------------------------------------

    fun encodeAnnotationBody(body: DriveAnnotationBody): Map<String, Any?> {
        body.totalProgression?.let { prog ->
            require(prog.isFinite() && prog in 0.0..1.0) { "Invalid annotation totalProgression: $prog" }
        }
        return mapOf(
            "id" to body.id,
            "workKey" to body.workKey,
            "editionSha" to body.editionSha,
            "kind" to body.kind,
            "locatorJson" to DriveCanonicalJson.canonicalizeJsonString(body.locatorJson),
            "text" to body.text,
            "note" to body.note,
            "tint" to body.tint,
            "chapter" to body.chapter,
            "totalProgression" to body.totalProgression,
            "createdAt" to body.createdAt,
            "noteCreatedAt" to body.noteCreatedAt,
            "noteUpdatedAt" to body.noteUpdatedAt,
            "updatedAt" to body.updatedAt,
        )
    }

    /** Canonical fingerprint body for annotations: omits `editionSha` (and `position` is never present). */
    fun encodeAnnotationBodyForFingerprint(body: DriveAnnotationBody): Map<String, Any?> =
        encodeAnnotationBody(body).filterKeys { it != "editionSha" }

    fun annotationContentFp(body: DriveAnnotationBody): String =
        DriveCanonicalJson.contentFp(encodeAnnotationBodyForFingerprint(body))

    /**
     * True unless [record]'s body names a different record than its envelope key `k`.
     * Reconciliation and conflict state are keyed by `k`, so a body that disagrees must
     * never be applied: it could overwrite an unrelated row without being reconciled.
     */
    fun bodyMatchesKey(record: DriveWireRecord): Boolean {
        val body = record.body ?: return true
        fun s(name: String) = body.optString(name).trim()
        val expected = when (record.d) {
            DOMAIN_ANNOTATION -> s("id")
            DOMAIN_EVALUATION, DOMAIN_METADATA -> "${s("workKey")}/${s("field")}"
            DOMAIN_POSITION -> "${s("workKey")}/${s("deviceId")}"
            DOMAIN_SERIES, DOMAIN_CATALOG -> s("workKey")
            DOMAIN_VOCAB_WORD -> vocabWordKey(s("language"), s("normalized"))
            DOMAIN_VOCAB_OCCURRENCE -> "${s("wordKey")}/${s("occurrenceKey")}"
            DOMAIN_VOCAB_REVIEW -> {
                // A review also names its word twice; both must agree.
                if (s("wordKey") != vocabWordKey(s("language"), s("normalized"))) return false
                s("syncId")
            }
            DOMAIN_SESSION -> s("sessionId")
            else -> return true
        }
        return expected == record.k
    }

    /** Same format as `DriveVocabularySync.wordKey`: `"<language.length>:<language>:<normalized>"`. */
    private fun vocabWordKey(language: String, normalized: String): String = "${language.length}:$language:$normalized"

    fun decodeAnnotationBody(json: JSONObject): DriveAnnotationBody? {
        val id = json.optString("id").trim()
        val workKey = json.optString("workKey").trim()
        val kind = json.optString("kind").trim()
        if (id.isEmpty() || workKey.isEmpty() || kind.isEmpty()) return null
        if (kind !in VALID_ANNOTATION_KINDS) return null
        val locatorJson = json.optString("locatorJson")
        if (kind != "BOOK_NOTE" && locatorJson.isBlank()) return null
        val progression = json.optNullableDouble("totalProgression")
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
        return DriveAnnotationBody(
            id = id,
            workKey = workKey,
            editionSha = json.optNullableString("editionSha")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() },
            kind = kind,
            locatorJson = locatorJson,
            text = json.optNullableString("text"),
            note = json.optNullableString("note"),
            tint = json.optNullableString("tint")?.takeIf { it.isNotBlank() },
            chapter = json.optNullableString("chapter"),
            totalProgression = progression,
            createdAt = json.optLong("createdAt", 0L),
            noteCreatedAt = json.optNullableLong("noteCreatedAt"),
            noteUpdatedAt = json.optNullableLong("noteUpdatedAt"),
            updatedAt = json.optLong("updatedAt", 0L),
        )
    }

    fun encodeEvaluationBody(body: DriveEvaluationBody): Map<String, Any?> {
        require(com.chmouel.liseur.domain.PersonalEvaluation.valid(body.field, body.value)) {
            "Invalid evaluation body for field=${body.field}"
        }
        return mapOf(
            "workKey" to body.workKey,
            "field" to body.field,
            "value" to body.value,
            "updatedAt" to body.updatedAt,
        )
    }

    fun decodeEvaluationBody(json: JSONObject): DriveEvaluationBody? {
        val workKey = json.optString("workKey").trim()
        val field = json.optString("field").trim()
        val value = json.optNullableString("value") ?: return null
        if (workKey.isEmpty() || field.isEmpty()) return null
        if (!com.chmouel.liseur.domain.PersonalEvaluation.valid(field, value)) return null
        return DriveEvaluationBody(
            workKey = workKey,
            field = field,
            value = value,
            updatedAt = json.optLong("updatedAt", 0L),
        )
    }

    fun encodeMetadataBody(body: DriveMetadataBody): Map<String, Any?> = when (body) {
        is DriveMetadataBody.Text -> mapOf(
            "workKey" to body.workKey,
            "field" to body.field,
            "value" to body.value,
            "updatedAt" to body.updatedAt,
        )
        is DriveMetadataBody.Cover -> mapOf(
            "workKey" to body.workKey,
            "field" to body.field,
            "coverSha256" to body.coverSha256.lowercase(Locale.ROOT),
            "width" to body.width,
            "height" to body.height,
            "sizeBytes" to body.sizeBytes,
            "updatedAt" to body.updatedAt,
        )
    }

    fun decodeMetadataBody(json: JSONObject): DriveMetadataBody? {
        val workKey = json.optString("workKey").trim()
        val field = json.optString("field").trim()
        if (workKey.isEmpty() || field.isEmpty()) return null
        val updatedAt = json.optLong("updatedAt", 0L)
        return when (field) {
            in com.chmouel.liseur.data.db.MetadataOverrideFields.TEXT_FIELDS -> {
                val value = json.optNullableString("value") ?: return null
                DriveMetadataBody.Text(
                    workKey = workKey,
                    field = field,
                    value = value,
                    updatedAt = updatedAt,
                )
            }
            com.chmouel.liseur.data.db.MetadataOverrideFields.COVER -> {
                val sha = json.optString("coverSha256").trim().lowercase(Locale.ROOT)
                val width = json.optInt("width", 0)
                val height = json.optInt("height", 0)
                val sizeBytes = json.optLong("sizeBytes", 0L)
                if (!SHA256_REGEX.matches(sha) || width <= 0 || height <= 0 || sizeBytes <= 0L) return null
                DriveMetadataBody.Cover(
                    workKey = workKey,
                    coverSha256 = sha,
                    width = width,
                    height = height,
                    sizeBytes = sizeBytes,
                    updatedAt = updatedAt,
                )
            }
            else -> null
        }
    }

    fun encodeSeriesBody(body: DriveSeriesBody): Map<String, Any?> {
        body.userSeriesIndex?.let { idx ->
            require(idx.isFinite()) { "Invalid userSeriesIndex: $idx" }
        }
        return mapOf(
            "workKey" to body.workKey,
            "seriesOverridden" to body.seriesOverridden,
            "userSeriesName" to body.userSeriesName,
            "indexOverridden" to body.indexOverridden,
            "userSeriesIndex" to body.userSeriesIndex,
            "seriesKey" to body.seriesKey,
            "updatedAt" to body.updatedAt,
        )
    }

    fun decodeSeriesBody(json: JSONObject): DriveSeriesBody? {
        val workKey = json.optString("workKey").trim()
        if (workKey.isEmpty()) return null
        return DriveSeriesBody(
            workKey = workKey,
            seriesOverridden = json.optBoolean("seriesOverridden", false),
            userSeriesName = json.optNullableString("userSeriesName"),
            indexOverridden = json.optBoolean("indexOverridden", false),
            userSeriesIndex = json.optNullableDouble("userSeriesIndex"),
            seriesKey = json.optString("seriesKey"),
            updatedAt = json.optLong("updatedAt", 0L),
        )
    }

    // -- Phase 5 Domain Body Codecs -------------------------------------------

    fun encodeVocabWordBody(body: DriveVocabWordBody): Map<String, Any?> = mapOf(
        "language" to body.language,
        "normalized" to body.normalized,
        "word" to body.word,
        "definition" to body.definition,
        "translationLanguage" to body.translationLanguage,
        "notes" to body.notes,
        "definitionEdited" to body.definitionEdited,
        "definitionUpdatedAt" to body.definitionUpdatedAt,
        "notesUpdatedAt" to body.notesUpdatedAt,
        "addedAt" to body.addedAt,
        "baseline" to body.baseline?.let { b ->
            mapOf(
                "dueAt" to b.dueAt,
                "reviewedAt" to b.reviewedAt,
                "streak" to b.streak,
                "intervalMinutes" to b.intervalMinutes,
                "repetitions" to b.repetitions,
            )
        },
    )

    fun decodeVocabWordBody(json: JSONObject): DriveVocabWordBody? {
        val language = json.optString("language").trim()
        val normalized = json.optString("normalized").trim()
        val word = json.optString("word")
        if (language.isEmpty() || normalized.isEmpty() || word.isBlank()) return null
        val baselineObj = if (json.isNull("baseline")) null else json.optJSONObject("baseline")
        val baseline = baselineObj?.let { b ->
            val repetitions = b.optInt("repetitions", 0)
            val dueAt = b.optNullableLong("dueAt")
            if (dueAt != null && repetitions > 0) {
                DriveVocabBaselineBody(
                    dueAt = dueAt,
                    reviewedAt = b.optNullableLong("reviewedAt"),
                    streak = b.optInt("streak", 0).coerceAtLeast(0),
                    intervalMinutes = b.optInt("intervalMinutes", 0).coerceAtLeast(0),
                    repetitions = repetitions,
                )
            } else {
                null
            }
        }
        return DriveVocabWordBody(
            language = language,
            normalized = normalized,
            word = word,
            definition = json.optNullableString("definition"),
            translationLanguage = json.optNullableString("translationLanguage"),
            notes = json.optString("notes"),
            definitionEdited = json.optBoolean("definitionEdited", false),
            definitionUpdatedAt = json.optLong("definitionUpdatedAt", 0L),
            notesUpdatedAt = json.optLong("notesUpdatedAt", 0L),
            addedAt = json.optLong("addedAt", 0L),
            baseline = baseline,
        )
    }

    fun encodeVocabOccurrenceBody(body: DriveVocabOccurrenceBody): Map<String, Any?> = mapOf(
        "wordKey" to body.wordKey,
        "occurrenceKey" to body.occurrenceKey,
        "workKey" to body.workKey,
        "bookTitle" to body.bookTitle,
        "locationKey" to com.chmouel.liseur.domain.VocabularyMerge.wireLocationKey(body.locationKey),
        "locator" to body.locator?.takeIf { it.isNotBlank() }?.let(DriveCanonicalJson::canonicalizeJsonString),
        "sentence" to body.sentence,
        "sentenceEdited" to body.sentenceEdited,
        "sentenceUpdatedAt" to body.sentenceUpdatedAt,
        "lastSeenAt" to body.lastSeenAt,
        "encounters" to body.encounters.coerceAtLeast(1),
    )

    fun decodeVocabOccurrenceBody(json: JSONObject): DriveVocabOccurrenceBody? {
        val wordKey = json.optString("wordKey").trim()
        val occurrenceKey = json.optString("occurrenceKey").trim()
        if (wordKey.isEmpty() || occurrenceKey.isEmpty()) return null
        val locationKey = com.chmouel.liseur.domain.VocabularyMerge.wireLocationKey(json.optString("locationKey"))
        return DriveVocabOccurrenceBody(
            wordKey = wordKey,
            occurrenceKey = occurrenceKey,
            workKey = json.optNullableString("workKey")?.trim()?.takeIf { it.isNotEmpty() },
            bookTitle = json.optString("bookTitle"),
            locationKey = locationKey,
            locator = json.optNullableString("locator")?.takeIf { it.isNotBlank() },
            sentence = json.optNullableString("sentence"),
            sentenceEdited = json.optBoolean("sentenceEdited", false),
            sentenceUpdatedAt = json.optLong("sentenceUpdatedAt", 0L),
            lastSeenAt = json.optLong("lastSeenAt", 0L),
            encounters = json.optInt("encounters", 1).coerceAtLeast(1),
        )
    }

    fun encodeVocabReviewBody(body: DriveVocabReviewBody): Map<String, Any?> {
        require(body.rating in VALID_VOCAB_RATINGS) { "Invalid vocabulary review rating: ${body.rating}" }
        return mapOf(
            "syncId" to body.syncId,
            "wordKey" to body.wordKey,
            "language" to body.language,
            "normalized" to body.normalized,
            "rating" to body.rating,
            "reviewedAt" to body.reviewedAt,
            "intervalMinutes" to body.intervalMinutes,
            "dueAt" to body.dueAt,
        )
    }

    fun decodeVocabReviewBody(json: JSONObject): DriveVocabReviewBody? {
        val syncId = json.optString("syncId").trim()
        val wordKey = json.optString("wordKey").trim()
        val language = json.optString("language").trim()
        val normalized = json.optString("normalized").trim()
        val rating = json.optString("rating").trim()
        if (syncId.isEmpty() || wordKey.isEmpty() || language.isEmpty() || normalized.isEmpty()) return null
        if (rating !in VALID_VOCAB_RATINGS) return null
        val reviewedAt = json.optNullableLong("reviewedAt") ?: return null
        val dueAt = json.optNullableLong("dueAt") ?: return null
        return DriveVocabReviewBody(
            syncId = syncId,
            wordKey = wordKey,
            language = language,
            normalized = normalized,
            rating = rating,
            reviewedAt = reviewedAt,
            intervalMinutes = json.optInt("intervalMinutes", 0).coerceAtLeast(0),
            dueAt = dueAt,
        )
    }

    fun encodeSessionBody(body: DriveSessionBody): Map<String, Any?> {
        require(body.startProgression.isFinite() && body.startProgression in 0.0..1.0) {
            "Invalid session startProgression: ${body.startProgression}"
        }
        require(body.endProgression.isFinite() && body.endProgression in 0.0..1.0) {
            "Invalid session endProgression: ${body.endProgression}"
        }
        return mapOf(
            "sessionId" to body.sessionId,
            "workKey" to body.workKey,
            "startedAt" to body.startedAt,
            "endedAt" to body.endedAt,
            "durationMs" to body.durationMs.coerceAtLeast(0L),
            "idleMs" to body.idleMs.coerceAtLeast(0L),
            "startProgression" to body.startProgression,
            "endProgression" to body.endProgression,
            "originDeviceId" to body.originDeviceId,
        )
    }

    fun decodeSessionBody(json: JSONObject): DriveSessionBody? {
        val sessionId = json.optString("sessionId").trim()
        val workKey = json.optString("workKey").trim()
        val originDeviceId = json.optString("originDeviceId").trim()
        if (sessionId.isEmpty() || workKey.isEmpty()) return null
        val startedAt = json.optNullableLong("startedAt") ?: return null
        val endedAt = json.optNullableLong("endedAt") ?: return null
        val durationMs = json.optNullableLong("durationMs")?.takeIf { it >= 0L } ?: return null
        val idleMs = (json.optNullableLong("idleMs") ?: 0L).coerceAtLeast(0L)
        val startProg = json.optNullableDouble("startProgression")
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: return null
        val endProg = json.optNullableDouble("endProgression")
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: return null
        return DriveSessionBody(
            sessionId = sessionId,
            workKey = workKey,
            startedAt = startedAt,
            endedAt = maxOf(endedAt, startedAt),
            durationMs = durationMs,
            idleMs = idleMs,
            startProgression = startProg,
            endProgression = endProg,
            originDeviceId = originDeviceId,
        )
    }

    fun encodeRevisionAckJson(payload: DriveRevisionAckPayload): String {
        val map = linkedMapOf<String, Any?>(
            "vfp" to payload.vfp,
            "cfp" to payload.cfp,
            "rev" to payload.rev,
            "op" to payload.op,
        )
        payload.hlc?.takeIf { it.isNotBlank() }?.let { map["hlc"] = it }
        payload.bodyJson?.takeIf { it.isNotBlank() }?.let { map["body"] = it }
        return DriveCanonicalJson.encode(map)
    }

    fun decodeRevisionAckJson(rawJson: String?): DriveRevisionAckPayload? {
        if (rawJson.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val vfp = obj.optString("vfp").trim()
        if (vfp.isEmpty()) return null
        val cfp = obj.optNullableString("cfp")?.takeIf { it.isNotBlank() }
        val rev = obj.optLong("rev", 0L)
        val op = obj.optString("op").ifBlank { DriveRecordState.OP_PUT }
        val hlc = obj.optNullableString("hlc")?.takeIf { it.isNotBlank() }
        val bodyJson = obj.optNullableString("body")?.takeIf { it.isNotBlank() }
        return DriveRevisionAckPayload(vfp = vfp, cfp = cfp, rev = rev, op = op, hlc = hlc, bodyJson = bodyJson)
    }

    /** Encodes frozen outbox delivery ACK metadata for crash-safe replay. */
    fun encodeOutboxAckMetadata(metadata: DriveOutboxAckMetadata): String =
        DriveCanonicalJson.encode(
            mapOf(
                "positionAcks" to metadata.positionAcks.map { ack ->
                    mapOf(
                        "bookUrl" to ack.bookUrl,
                        "localRevision" to ack.localRevision,
                        "statusRevision" to ack.statusRevision,
                        "progression" to ack.progression,
                        "status" to ack.status,
                        "recordKey" to ack.recordKey,
                        "recordFp" to ack.recordFp,
                    )
                },
                "peakAcks" to metadata.peakAcks.map { ack ->
                    mapOf(
                        "bookUrl" to ack.bookUrl,
                        "workKey" to ack.workKey,
                        "peakRevision" to ack.peakRevision,
                        "recordKey" to ack.recordKey,
                        "recordFp" to ack.recordFp,
                    )
                },
                "annotationAcks" to metadata.annotationAcks.map { ack ->
                    mapOf(
                        "id" to ack.id,
                        "bookId" to ack.bookId,
                        "workId" to ack.workId,
                        "ackedFingerprint" to ack.ackedFingerprint,
                        "recordFp" to ack.recordFp,
                    )
                },
                "evaluationAcks" to metadata.evaluationAcks.map { ack ->
                    mapOf(
                        "bookUrl" to ack.bookUrl,
                        "field" to ack.field,
                        "workKey" to ack.workKey,
                        "targetRevision" to ack.targetRevision,
                        "recordKey" to ack.recordKey,
                        "recordFp" to ack.recordFp,
                        "contentFp" to ack.contentFp,
                        "recordOp" to ack.recordOp,
                    )
                },
                "metadataAcks" to metadata.metadataAcks.map { ack ->
                    mapOf(
                        "bookUrl" to ack.bookUrl,
                        "field" to ack.field,
                        "workKey" to ack.workKey,
                        "targetRevision" to ack.targetRevision,
                        "recordKey" to ack.recordKey,
                        "recordFp" to ack.recordFp,
                        "contentFp" to ack.contentFp,
                        "recordOp" to ack.recordOp,
                        "remoteCoverSha256" to ack.remoteCoverSha256,
                        "remoteCoverUpdatedAt" to ack.remoteCoverUpdatedAt,
                    )
                },
                "vocabWordAcks" to metadata.vocabWordAcks.map { ack ->
                    mapOf(
                        "language" to ack.language,
                        "normalized" to ack.normalized,
                        "wordId" to ack.wordId,
                        "recordKey" to ack.recordKey,
                        "recordFp" to ack.recordFp,
                        "baseJson" to ack.baseJson,
                        "isDelete" to ack.isDelete,
                    )
                },
                "vocabReviewAcks" to metadata.vocabReviewAcks.map { ack ->
                    mapOf(
                        "reviewId" to ack.reviewId,
                        "localReviewId" to ack.localReviewId,
                        "language" to ack.language,
                        "normalized" to ack.normalized,
                        "recordFp" to ack.recordFp,
                        "isDelete" to ack.isDelete,
                    )
                },
                "sessionAcks" to metadata.sessionAcks.map { ack ->
                    mapOf(
                        "remoteSessionId" to ack.remoteSessionId,
                        "recordFp" to ack.recordFp,
                    )
                },
            ),
        )

    /** Decodes persisted outbox delivery ACK metadata for crash-safe replay. */
    fun decodeOutboxAckMetadata(rawJson: String): DriveOutboxAckMetadata {
        val json = JSONObject(rawJson)
        val posArr = json.optJSONArray("positionAcks") ?: JSONArray()
        val positionAcks = buildList(posArr.length()) {
            for (i in 0 until posArr.length()) {
                val obj = posArr.optJSONObject(i) ?: continue
                val bookUrl = obj.optString("bookUrl").trim()
                if (bookUrl.isEmpty()) continue
                add(
                    DrivePositionAckMeta(
                        bookUrl = bookUrl,
                        localRevision = obj.optLong("localRevision", 0L),
                        statusRevision = obj.optLong("statusRevision", 0L),
                        progression = obj.optNullableDouble("progression"),
                        status = obj.optString("status").ifBlank { "ReadyToRead" },
                        recordKey = obj.optString("recordKey"),
                        recordFp = obj.optString("recordFp"),
                    ),
                )
            }
        }
        val peakArr = json.optJSONArray("peakAcks") ?: JSONArray()
        val peakAcks = buildList(peakArr.length()) {
            for (i in 0 until peakArr.length()) {
                val obj = peakArr.optJSONObject(i) ?: continue
                val bookUrl = obj.optString("bookUrl").trim()
                val workKey = obj.optString("workKey").trim()
                if (bookUrl.isEmpty() || workKey.isEmpty()) continue
                add(
                    DrivePeakAckMeta(
                        bookUrl = bookUrl,
                        workKey = workKey,
                        peakRevision = obj.optLong("peakRevision", 0L),
                        recordKey = obj.optString("recordKey"),
                        recordFp = obj.optString("recordFp"),
                    ),
                )
            }
        }
        val annArr = json.optJSONArray("annotationAcks") ?: JSONArray()
        val annotationAcks = buildList(annArr.length()) {
            for (i in 0 until annArr.length()) {
                val obj = annArr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isEmpty()) continue
                add(
                    DriveAnnotationAckMeta(
                        id = id,
                        bookId = obj.optString("bookId"),
                        workId = obj.optString("workId"),
                        ackedFingerprint = obj.optString("ackedFingerprint"),
                        recordFp = obj.optString("recordFp"),
                    ),
                )
            }
        }
        val evalArr = json.optJSONArray("evaluationAcks") ?: JSONArray()
        val evaluationAcks = buildList(evalArr.length()) {
            for (i in 0 until evalArr.length()) {
                val obj = evalArr.optJSONObject(i) ?: continue
                val bookUrl = obj.optString("bookUrl").trim()
                val field = obj.optString("field").trim()
                if (bookUrl.isEmpty() || field.isEmpty()) continue
                add(
                    DriveEvaluationAckMeta(
                        bookUrl = bookUrl,
                        field = field,
                        workKey = obj.optString("workKey"),
                        targetRevision = obj.optLong("targetRevision", 0L),
                        recordKey = obj.optString("recordKey"),
                        recordFp = obj.optString("recordFp"),
                        contentFp = obj.optNullableString("contentFp"),
                        recordOp = obj.optString("recordOp").ifBlank { DriveRecordState.OP_PUT },
                    ),
                )
            }
        }
        val metaArr = json.optJSONArray("metadataAcks") ?: JSONArray()
        val metadataAcks = buildList(metaArr.length()) {
            for (i in 0 until metaArr.length()) {
                val obj = metaArr.optJSONObject(i) ?: continue
                val bookUrl = obj.optString("bookUrl").trim()
                val field = obj.optString("field").trim()
                if (bookUrl.isEmpty() || field.isEmpty()) continue
                add(
                    DriveMetadataAckMeta(
                        bookUrl = bookUrl,
                        field = field,
                        workKey = obj.optString("workKey"),
                        targetRevision = obj.optLong("targetRevision", 0L),
                        recordKey = obj.optString("recordKey"),
                        recordFp = obj.optString("recordFp"),
                        contentFp = obj.optNullableString("contentFp"),
                        recordOp = obj.optString("recordOp").ifBlank { DriveRecordState.OP_PUT },
                        remoteCoverSha256 = obj.optNullableString("remoteCoverSha256"),
                        remoteCoverUpdatedAt = obj.optLong("remoteCoverUpdatedAt", 0L),
                    ),
                )
            }
        }
        val vocabWordArr = json.optJSONArray("vocabWordAcks") ?: JSONArray()
        val vocabWordAcks = buildList(vocabWordArr.length()) {
            for (i in 0 until vocabWordArr.length()) {
                val obj = vocabWordArr.optJSONObject(i) ?: continue
                val language = obj.optString("language").trim()
                val normalized = obj.optString("normalized").trim()
                if (language.isEmpty() || normalized.isEmpty()) continue
                add(
                    DriveVocabWordAckMeta(
                        language = language,
                        normalized = normalized,
                        wordId = obj.optNullableLong("wordId"),
                        recordKey = obj.optString("recordKey"),
                        recordFp = obj.optString("recordFp"),
                        baseJson = obj.optNullableString("baseJson"),
                        isDelete = obj.optBoolean("isDelete", false),
                    ),
                )
            }
        }
        val vocabReviewArr = json.optJSONArray("vocabReviewAcks") ?: JSONArray()
        val vocabReviewAcks = buildList(vocabReviewArr.length()) {
            for (i in 0 until vocabReviewArr.length()) {
                val obj = vocabReviewArr.optJSONObject(i) ?: continue
                val reviewId = obj.optString("reviewId").trim()
                if (reviewId.isEmpty()) continue
                add(
                    DriveVocabReviewAckMeta(
                        reviewId = reviewId,
                        localReviewId = obj.optNullableLong("localReviewId"),
                        language = obj.optString("language"),
                        normalized = obj.optString("normalized"),
                        recordFp = obj.optString("recordFp"),
                        isDelete = obj.optBoolean("isDelete", false),
                    ),
                )
            }
        }
        val sessionArr = json.optJSONArray("sessionAcks") ?: JSONArray()
        val sessionAcks = buildList(sessionArr.length()) {
            for (i in 0 until sessionArr.length()) {
                val obj = sessionArr.optJSONObject(i) ?: continue
                val remoteSessionId = obj.optString("remoteSessionId").trim()
                if (remoteSessionId.isEmpty()) continue
                add(
                    DriveSessionAckMeta(
                        remoteSessionId = remoteSessionId,
                        recordFp = obj.optString("recordFp"),
                    ),
                )
            }
        }
        return DriveOutboxAckMetadata(
            positionAcks = positionAcks,
            peakAcks = peakAcks,
            annotationAcks = annotationAcks,
            evaluationAcks = evaluationAcks,
            metadataAcks = metadataAcks,
            vocabWordAcks = vocabWordAcks,
            vocabReviewAcks = vocabReviewAcks,
            sessionAcks = sessionAcks,
        )
    }

    /** Creates a `put` [DriveWireRecord] with canonical fingerprint and capped ancestry. */
    fun putRecord(
        domain: String,
        key: String,
        hlc: String,
        bodyMap: Map<String, Any?>,
        ancestors: List<String> = emptyList(),
        resRefs: List<String> = emptyList(),
    ): DriveWireRecord = putRecordWithFp(
        domain = domain,
        key = key,
        hlc = hlc,
        fp = DriveCanonicalJson.putFp(bodyMap, hlc),
        bodyMap = bodyMap,
        ancestors = ancestors,
        resRefs = resRefs,
    )

    /** Creates a `put` [DriveWireRecord] using an explicit [fp] (e.g. annotations omitting `editionSha`). */
    fun putRecordWithFp(
        domain: String,
        key: String,
        hlc: String,
        fp: String,
        bodyMap: Map<String, Any?>,
        ancestors: List<String> = emptyList(),
        resRefs: List<String> = emptyList(),
    ): DriveWireRecord {
        val canonicalBody = DriveCanonicalJson.encode(bodyMap)
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

    /** Creates a `del` [DriveWireRecord] with HLC-salted `delFp` and null `body`. */
    fun delRecord(
        domain: String,
        key: String,
        hlc: String,
        ancestors: List<String> = emptyList(),
        resRefs: List<String> = emptyList(),
        explicitFp: String? = null,
        observed: Map<String, Long>? = null,
    ): DriveWireRecord = DriveWireRecord(
        d = domain,
        k = key,
        op = DriveRecordState.OP_DEL,
        hlc = hlc,
        fp = explicitFp ?: DriveCanonicalJson.delFp(domain, key, hlc),
        anc = ancestors.distinct().take(MAX_ANCESTORS),
        resRefs = resRefs.distinct().sorted(),
        body = null,
        observed = observed,
    )

    /**
     * Creates an `op: "resolve"` [DriveWireRecord] encoding either `outcome: "put"` (non-null [bodyMap])
     * or `outcome: "delete"` (`bodyMap == null`) with sorted [resolvedFps] and uncapped [resRefs].
     */
    fun resolveRecord(
        domain: String,
        key: String,
        outcome: String,
        resolvedFps: Collection<String>,
        hlc: String,
        bodyMap: Map<String, Any?>?,
        ancestors: List<String> = emptyList(),
        resRefs: List<String> = emptyList(),
        explicitFp: String? = null,
    ): DriveWireRecord {
        require(outcome == DriveResolution.OUTCOME_PUT || outcome == DriveResolution.OUTCOME_DELETE) {
            "Invalid resolve outcome: $outcome"
        }
        if (outcome == DriveResolution.OUTCOME_PUT) {
            require(bodyMap != null) { "resolve(outcome=put) requires non-null body" }
        } else {
            require(bodyMap == null) { "resolve(outcome=delete) requires null body" }
        }
        val sortedResolved = resolvedFps.distinct().sorted()
        val bodyJson = bodyMap?.let { JSONObject(DriveCanonicalJson.encode(it)) }
        val fp = explicitFp ?: DriveCanonicalJson.resolveFp(outcome, sortedResolved, hlc, bodyMap)
        return DriveWireRecord(
            d = domain,
            k = key,
            op = DriveRecordState.OP_RESOLVE,
            hlc = hlc,
            fp = fp,
            anc = ancestors.distinct().take(MAX_ANCESTORS),
            resRefs = (resRefs + fp).distinct().sorted(),
            body = bodyJson,
            outcome = outcome,
            resolvedFps = sortedResolved,
        )
    }

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

    internal fun wireRecordToMap(record: DriveWireRecord): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
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
        if (record.observed != null) {
            put("observed", record.observed)
        }
        if (record.seq != null) {
            put("seq", record.seq)
        }
    }

    internal fun snapshotResolutionToMap(resolution: DriveSnapshotResolution): Map<String, Any?> = mapOf(
        "d" to resolution.d,
        "k" to resolution.k,
        "resolutionFp" to resolution.resolutionFp,
        "resolvedFps" to resolution.resolvedFps.distinct().sorted(),
        "outcome" to resolution.outcome,
        "hlc" to resolution.hlc,
    )

    internal fun parseWireRecords(array: JSONArray?): List<DriveWireRecord> {
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
                        observed = if (obj.has("observed")) {
                            parseLongMap(obj.optJSONObject("observed"))
                        } else {
                            null
                        },
                        seq = obj.optNullableLong("seq")?.takeIf { it > 0L },
                    ),
                )
            }
        }
    }

    internal fun snapshotRefToMap(ref: DriveSnapshotRef): Map<String, Any?> = mapOf(
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

    internal fun parseSnapshotRef(json: JSONObject): DriveSnapshotRef {
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

    private fun parseSnapshotResolutions(array: JSONArray?): List<DriveSnapshotResolution> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val d = obj.optString("d").trim()
                val k = obj.optString("k").trim()
                val resolutionFp = obj.optString("resolutionFp").trim()
                val outcome = obj.optString("outcome").trim()
                val hlc = obj.optString("hlc").trim()
                val resolvedFps = parseStringList(obj.optJSONArray("resolvedFps"))
                if (d.isEmpty() || k.isEmpty() || resolutionFp.isEmpty() || resolvedFps.isEmpty()) continue
                if (outcome != DriveResolution.OUTCOME_PUT && outcome != DriveResolution.OUTCOME_DELETE) continue
                add(
                    DriveSnapshotResolution(
                        d = d,
                        k = k,
                        resolutionFp = resolutionFp,
                        resolvedFps = resolvedFps.distinct().sorted(),
                        outcome = outcome,
                        hlc = hlc,
                    ),
                )
            }
        }
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

    private val VALID_ANNOTATION_KINDS = setOf("HIGHLIGHT", "NOTE", "BOOK_NOTE", "BOOKMARK")
    private val VALID_VOCAB_RATINGS = setOf("AGAIN", "HARD", "GOOD", "EASY")
    private val SHA256_REGEX = Regex("[0-9a-f]{64}")

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
}
