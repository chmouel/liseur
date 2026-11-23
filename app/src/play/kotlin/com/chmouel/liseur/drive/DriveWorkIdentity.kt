package com.chmouel.liseur.drive

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.db.WorkAmbiguity
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.USELESS_IDENTIFIERS
import com.chmouel.liseur.domain.WorkIdentifiers
import com.chmouel.liseur.hardcover.Isbn
import java.util.Locale
import org.json.JSONObject

/** A pending ambiguous title/author match awaiting explicit reader confirmation. */
data class DrivePendingWorkMatch(
    val bookUrl: String,
    val bookTitle: String,
    val bookAuthor: String?,
    val localWorkKey: String,
    val candidateWorkKey: String,
    val candidateTitle: String?,
    val candidateAuthor: String?,
)

/**
 * Deterministic client-side `workKey` derivation and `workAlias` merge/reject
 * management for Google Drive sync (ADR-0078 & `docs/DRIVE_SYNC.md` §1.1, §4.1).
 */
internal class DriveWorkIdentity(
    private val database: LiseurDatabase,
    private val store: DriveSyncDatabase,
    private val fingerprintStore: BookFingerprintStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val books get() = database.bookDao()
    private val metadata get() = database.bookMetadataDao()
    private val identities get() = database.workIdentityDao()
    private val recordStates get() = store.recordStates()

    /**
     * Resolves or returns the frozen Drive [WorkAlias] for [book] under
     * `peer_id = "drive:<permissionId>"`.
     *
     * For local/downloaded books, requires `book_fingerprint` and
     * `books.file_metadata_checked = 1` per ADR-0078 so a `sha:` key is never
     * minted prematurely before OPF `dc:identifier` / ISBN metadata is read.
     */
    suspend fun resolveBook(
        permissionId: String,
        book: Book,
        computeFingerprintIfMissing: Boolean = true,
    ): WorkAlias? {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val existing = identities.alias(book.url, peerId)
        if (existing != null && existing.usable) {
            val canonical = canonicalWorkKey(permissionId, existing.workId)
            val currentSha = identities.fingerprint(book.url)?.sha256?.lowercase(Locale.ROOT)
                ?: existing.editionSha
            if (canonical != existing.workId || (currentSha != null && existing.editionSha != currentSha)) {
                val updated = existing.copy(
                    workId = canonical,
                    editionSha = currentSha,
                    seeded = if (canonical != existing.workId) false else existing.seeded,
                )
                identities.upsert(updated)
                return updated
            }
            return existing
        }

        val now = clock()
        // Remote Drive catalog stub: key is encoded in its `drive:<workKey>` URL.
        if (book.url.startsWith(DRIVE_BOOK_URL_PREFIX)) {
            val rawKey = book.url.removePrefix(DRIVE_BOOK_URL_PREFIX).trim()
            if (rawKey.isEmpty()) return null
            val canonical = canonicalWorkKey(permissionId, rawKey)
            val fp = identities.fingerprint(book.url)
            val alias = WorkAlias(
                bookUrl = book.url,
                peerId = peerId,
                workId = canonical,
                confidence = WorkAlias.HIGH,
                confirmed = true,
                seeded = existing?.seeded ?: false,
                editionSha = fp?.sha256?.lowercase(Locale.ROOT) ?: existing?.editionSha,
                resolvedAt = now,
            )
            identities.upsert(alias)
            return alias
        }

        // Local/downloaded book path.
        if (book.openableUrl != null) {
            if (!book.fileMetadataChecked) return null
            val fpRow = identities.fingerprint(book.url)
                ?: if (computeFingerprintIfMissing) {
                    fingerprintStore?.of(book)?.let {
                        identities.fingerprint(book.url)
                    }
                } else {
                    null
                }
                ?: return null
            val metadataRows = metadata.forBook(book.url)
            val derived = deriveLocalWorkKey(book, metadataRows, fpRow)
            val canonical = canonicalWorkKey(permissionId, derived)
            val alias = WorkAlias(
                bookUrl = book.url,
                peerId = peerId,
                workId = canonical,
                confidence = WorkAlias.HIGH,
                confirmed = true,
                seeded = false,
                editionSha = fpRow.sha256.lowercase(Locale.ROOT),
                resolvedAt = now,
            )
            val persistedBook = if (book.id > 0L) book else books.getByUrl(book.url) ?: book
            identities.saveResolution(persistedBook, alias)
            return identities.alias(book.url, peerId) ?: alias
        }

        // Fileless non-Drive book (e.g., preserved stub without existing alias).
        val metadataRows = metadata.forBook(book.url)
        val derived = DriveLibrarySync.preservedStubWorkKey(book, metadataRows, existing?.editionSha)
        val canonical = canonicalWorkKey(permissionId, derived)
        val alias = WorkAlias(
            bookUrl = book.url,
            peerId = peerId,
            workId = canonical,
            confidence = WorkAlias.HIGH,
            confirmed = true,
            seeded = false,
            editionSha = existing?.editionSha,
            resolvedAt = now,
        )
        identities.upsert(alias)
        return alias
    }

    /**
     * Resolves deterministic Drive `work_alias` rows for all eligible local
     * books before pulling remote records, so different editions sharing a
     * strong identifier (e.g. ISBN) link to the existing local book cleanly.
     */
    suspend fun resolveAllLocalBooks(permissionId: String) {
        for (book in books.allOnce()) {
            if (book.browseServerId != null) continue
            if (book.url.startsWith(DRIVE_BOOK_URL_PREFIX)) continue
            resolveBook(permissionId, book, computeFingerprintIfMissing = false)
        }
    }

    /**
     * Resolves [workKey] through all known `workAlias` `merge:` records to its
     * deterministic lexicographical minimum fixpoint.
     */
    suspend fun canonicalWorkKey(permissionId: String, workKey: String): String {
        val merges = loadMergeMap(permissionId)
        return resolveCanonical(workKey, merges)
    }

    /** Returns true if the reader has already rejected matching [keyA] with [keyB]. */
    suspend fun isRejectedPair(permissionId: String, keyA: String, keyB: String): Boolean {
        if (keyA == keyB) return false
        val low = minOf(keyA, keyB)
        val high = maxOf(keyA, keyB)
        val rejectKey = rejectRecordKey(low, high)
        val state = recordStates.get(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS, rejectKey)
        return state != null && state.versionOp == DriveRecordState.OP_PUT
    }

    /**
     * Checks whether a remote catalog entry ([remoteWorkKey], [remoteTitle], [remoteAuthor])
     * has an ambiguous title/author match against any local book with a different
     * `workKey`, and records a pending confirmation without silently merging.
     */
    suspend fun detectAmbiguousTitleAuthorMatch(
        permissionId: String,
        remoteWorkKey: String,
        remoteTitle: String?,
        remoteAuthor: String?,
    ): Boolean {
        val remoteTa = WorkIdentifiers.titleAuthor(remoteTitle, remoteAuthor) ?: return false
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val merges = loadMergeMap(permissionId)
        val canonicalRemote = resolveCanonical(remoteWorkKey, merges)
        val allAliases = identities.aliasesFor(peerId).associateBy { it.bookUrl }
        val localBooks = books.allOnce().filter { book ->
            book.browseServerId == null && !book.url.startsWith(DRIVE_BOOK_URL_PREFIX)
        }
        var found = false
        for (local in localBooks) {
            val localTa = WorkIdentifiers.titleAuthor(
                local.stableTitle ?: local.title,
                local.identityAuthor ?: local.stableAuthor,
            ) ?: continue
            if (localTa != remoteTa) continue
            val localAlias = allAliases[local.url]
            if (localAlias == null) {
                // Local book has no Drive workKey yet; record a low-confidence alias awaiting answer.
                identities.upsert(
                    WorkAlias(
                        bookUrl = local.url,
                        peerId = peerId,
                        workId = canonicalRemote,
                        confidence = WorkAlias.LOW,
                        confirmed = false,
                        seeded = false,
                        editionSha = identities.fingerprint(local.url)?.sha256?.lowercase(Locale.ROOT),
                        resolvedAt = clock(),
                    ),
                )
                found = true
                continue
            }
            if (localAlias.confidence == WorkAlias.REJECTED) continue
            val canonicalLocal = resolveCanonical(localAlias.workId, merges)
            if (canonicalLocal == canonicalRemote) continue
            if (isRejectedPair(permissionId, canonicalLocal, canonicalRemote)) continue

            val existingAmbiguity = identities.ambiguitiesFor(peerId).firstOrNull { it.bookUrl == local.url }
            val existingCandidates = existingAmbiguity?.candidates.orEmpty()
            val mergedCandidates = (listOf(canonicalLocal) + existingCandidates + canonicalRemote).distinct()
            identities.upsert(
                WorkAmbiguity(
                    bookUrl = local.url,
                    peerId = peerId,
                    workIds = mergedCandidates.joinToString("\n"),
                    noticedAt = existingAmbiguity?.noticedAt ?: clock(),
                ),
            )
            found = true
        }
        return found
    }

    /** Lists all pending ambiguous title/author matches for [permissionId]. */
    suspend fun pendingMatches(permissionId: String): List<DrivePendingWorkMatch> {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val merges = loadMergeMap(permissionId)
        val allBooks = books.allOnce().associateBy { it.url }
        val aliasesByWork = identities.aliasesFor(peerId).groupBy { resolveCanonical(it.workId, merges) }
        val results = mutableListOf<DrivePendingWorkMatch>()

        // 1. Ambiguities where local book already has a high-confidence workKey.
        for (ambiguity in identities.ambiguitiesFor(peerId)) {
            val localBook = allBooks[ambiguity.bookUrl] ?: continue
            val localAlias = identities.alias(ambiguity.bookUrl, peerId)
            val localKey = localAlias?.let { resolveCanonical(it.workId, merges) }
                ?: ambiguity.candidates.firstOrNull()
                ?: continue
            for (candidate in ambiguity.candidates) {
                val canonicalCandidate = resolveCanonical(candidate, merges)
                if (canonicalCandidate == localKey) continue
                if (isRejectedPair(permissionId, localKey, canonicalCandidate)) continue
                val remoteBook = allBooks[driveBookUrl(canonicalCandidate)]
                    ?: aliasesByWork[canonicalCandidate]?.firstNotNullOfOrNull { allBooks[it.bookUrl] }
                results.add(
                    DrivePendingWorkMatch(
                        bookUrl = localBook.url,
                        bookTitle = localBook.title,
                        bookAuthor = localBook.author,
                        localWorkKey = localKey,
                        candidateWorkKey = canonicalCandidate,
                        candidateTitle = remoteBook?.title,
                        candidateAuthor = remoteBook?.author,
                    ),
                )
            }
        }

        // 2. Low-confidence aliases awaiting confirmation.
        for (alias in identities.aliasesFor(peerId).filter { it.awaitingAnswer }) {
            val localBook = allBooks[alias.bookUrl] ?: continue
            val canonicalCandidate = resolveCanonical(alias.workId, merges)
            val remoteBook = allBooks[driveBookUrl(canonicalCandidate)]
            results.add(
                DrivePendingWorkMatch(
                    bookUrl = localBook.url,
                    bookTitle = localBook.title,
                    bookAuthor = localBook.author,
                    localWorkKey = "",
                    candidateWorkKey = canonicalCandidate,
                    candidateTitle = remoteBook?.title,
                    candidateAuthor = remoteBook?.author,
                ),
            )
        }
        return results
    }

    /**
     * Reader explicitly confirms that [keyA] and [keyB] are the same work (or confirms a
     * low-confidence match when [keyA] is empty).
     *
     * Persists a `merge:<from>` record (`to < from` lexicographically) in `drive_record_state`
     * ready for delta publication, updates affected `work_alias` rows in `liseur.db`, and clears
     * any `work_ambiguity` row.
     */
    suspend fun confirmMerge(
        permissionId: String,
        bookUrl: String,
        keyA: String,
        keyB: String,
        hlc: String,
    ): String {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val now = clock()
        if (keyA.isBlank() || keyA == keyB) {
            val canonical = canonicalWorkKey(permissionId, keyB)
            database.withTransaction {
                identities.confirm(bookUrl, peerId)
                identities.clearAmbiguity(bookUrl, peerId)
            }
            return canonical
        }

        val merges = loadMergeMap(permissionId)
        val canonA = resolveCanonical(keyA, merges)
        val canonB = resolveCanonical(keyB, merges)
        if (canonA == canonB) {
            identities.clearAmbiguity(bookUrl, peerId)
            return canonA
        }

        val to = minOf(canonA, canonB)
        val from = maxOf(canonA, canonB)
        val mergeKey = mergeRecordKey(from)
        val body = DriveWorkAliasBody.Merge(from = from, to = to, updatedAt = now)
        val bodyMap = DriveWireCodec.encodeWorkAliasBody(body)
        val bodyJson = DriveCanonicalJson.encode(bodyMap)
        val fp = DriveCanonicalJson.contentFp(bodyMap)

        // Transaction 1 (`liseur.db`): update aliases and clear ambiguity.
        database.withTransaction {
            val allAliases = identities.aliasesFor(peerId)
            for (alias in allAliases) {
                if (resolveCanonical(alias.workId, merges) == from) {
                    identities.upsert(
                        alias.copy(
                            workId = to,
                            confidence = WorkAlias.HIGH,
                            confirmed = true,
                            seeded = false,
                        ),
                    )
                }
            }
            identities.clearAmbiguity(bookUrl, peerId)
        }

        // Transaction 2 (`drive_sync.db`): queue `workAlias` merge record (unpublished -> `publishedFp = null`).
        store.withTransaction {
            val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS, mergeKey)
            val ancestors = buildAncestors(existing)
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
                    recordKey = mergeKey,
                    versionFp = fp,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = null,
                    contentFp = fp,
                    ancestorsJson = DriveCanonicalJson.encode(ancestors),
                    resRefsJson = existing?.resRefsJson ?: "[]",
                    lastHlc = hlc,
                    bodyJson = bodyJson,
                ),
            )
            // Merge supersedes any prior reject for this pair.
            recordStates.delete(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS, rejectRecordKey(to, from))
        }
        return to
    }

    /**
     * Reader explicitly rejects an ambiguous title/author match between [keyA] and [keyB].
     *
     * Persists a `reject:<min>|<max>` record in `drive_record_state` so the prompt is suppressed
     * on both this device and remote devices.
     */
    suspend fun rejectMerge(
        permissionId: String,
        bookUrl: String,
        keyA: String,
        keyB: String,
        hlc: String,
    ) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val now = clock()
        database.withTransaction {
            identities.clearAmbiguity(bookUrl, peerId)
            val alias = identities.alias(bookUrl, peerId)
            if (alias != null && alias.awaitingAnswer) {
                identities.reject(bookUrl, peerId)
            }
        }
        if (keyA.isBlank() || keyB.isBlank() || keyA == keyB) return

        val low = minOf(keyA, keyB)
        val high = maxOf(keyA, keyB)
        val rejectKey = rejectRecordKey(low, high)
        val body = DriveWorkAliasBody.Reject(a = low, b = high, updatedAt = now)
        val bodyMap = DriveWireCodec.encodeWorkAliasBody(body)
        val bodyJson = DriveCanonicalJson.encode(bodyMap)
        val fp = DriveCanonicalJson.contentFp(bodyMap)

        store.withTransaction {
            val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS, rejectKey)
            val ancestors = buildAncestors(existing)
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
                    recordKey = rejectKey,
                    versionFp = fp,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = null,
                    contentFp = fp,
                    ancestorsJson = DriveCanonicalJson.encode(ancestors),
                    resRefsJson = existing?.resRefsJson ?: "[]",
                    lastHlc = hlc,
                    bodyJson = bodyJson,
                ),
            )
        }
    }

    /** Collects unpublished `workAlias` records for delta batch publication. */
    suspend fun collectDirtyRecords(permissionId: String): List<DriveWireRecord> {
        val states = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS)
        return states.mapNotNull { state ->
            if (state.publishedFp == state.versionFp) return@mapNotNull null
            val bodyJson = state.bodyJson ?: return@mapNotNull null
            DriveWireRecord(
                d = DriveWireCodec.DOMAIN_WORK_ALIAS,
                k = state.recordKey,
                op = state.versionOp,
                hlc = state.lastHlc,
                fp = state.versionFp,
                anc = decodeStringArray(state.ancestorsJson),
                resRefs = decodeStringArray(state.resRefsJson),
                body = JSONObject(bodyJson),
            )
        }
    }

    /** Marks published `workAlias` records acknowledged in `drive_record_state`. */
    suspend fun markPublished(permissionId: String, records: List<DriveWireRecord>) {
        for (record in records) {
            if (record.d != DriveWireCodec.DOMAIN_WORK_ALIAS) continue
            val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS, record.k) ?: continue
            if (existing.versionFp == record.fp) {
                recordStates.upsert(existing.copy(publishedFp = record.fp))
            }
        }
    }

    /** Applies an incoming remote `workAlias` record idempotently. */
    suspend fun applyRemoteRecord(
        permissionId: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
    ) {
        if (record.op != DriveRecordState.OP_PUT) return
        val bodyJson = record.body ?: return
        val parsed = DriveWireCodec.decodeWorkAliasBody(bodyJson) ?: return
        val peerId = DriveLibrarySync.accountPeerId(permissionId)

        when (parsed) {
            is DriveWorkAliasBody.Merge -> {
                val mergeKey = mergeRecordKey(parsed.from)
                val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS, mergeKey)
                val existingTo = existing?.bodyJson?.let {
                    runCatching { DriveWireCodec.decodeWorkAliasBody(JSONObject(it)) }.getOrNull()
                } as? DriveWorkAliasBody.Merge
                val chosenTo = if (existingTo != null) minOf(existingTo.to, parsed.to) else parsed.to
                val chosenBody = if (chosenTo == parsed.to) {
                    bodyJson.toString()
                } else {
                    DriveCanonicalJson.encode(
                        DriveWireCodec.encodeWorkAliasBody(parsed.copy(to = chosenTo)),
                    )
                }
                val chosenFp = if (chosenTo == parsed.to) {
                    record.fp
                } else {
                    DriveCanonicalJson.contentFp(JSONObject(chosenBody))
                }

                // Transaction 1 (`liseur.db`): propagate merge across aliases & clear matching ambiguities.
                val merges = loadMergeMap(permissionId).toMutableMap()
                merges[parsed.from] = chosenTo
                val fixpoint = resolveCanonical(parsed.from, merges)
                database.withTransaction {
                    for (alias in identities.aliasesFor(peerId)) {
                        val resolved = resolveCanonical(alias.workId, merges)
                        if (resolved != alias.workId) {
                            identities.upsert(
                                alias.copy(
                                    workId = resolved,
                                    confidence = WorkAlias.HIGH,
                                    confirmed = true,
                                    seeded = false,
                                ),
                            )
                        }
                    }
                    for (amb in identities.ambiguitiesFor(peerId)) {
                        val reduced = amb.candidates.map { resolveCanonical(it, merges) }.distinct()
                        if (reduced.size <= 1) {
                            identities.clearAmbiguity(amb.bookUrl, peerId)
                        }
                    }
                }

                // Transaction 2 (`drive_sync.db`): persist merge record and clear superseded reject.
                store.withTransaction {
                    val anc = (listOfNotNull(existing?.versionFp) + decodeStringArray(existing?.ancestorsJson) + record.anc)
                        .distinct()
                        .filter { it != chosenFp }
                        .take(DriveWireCodec.MAX_ANCESTORS)
                    recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
                            recordKey = mergeKey,
                            versionFp = chosenFp,
                            versionOp = DriveRecordState.OP_PUT,
                            publishedFp = chosenFp,
                            contentFp = chosenFp,
                            originDeviceId = remoteDeviceId,
                            originSeq = remoteSeq,
                            ancestorsJson = DriveCanonicalJson.encode(anc),
                            resRefsJson = DriveCanonicalJson.encode(record.resRefs.distinct().sorted()),
                            lastHlc = maxOf(existing?.lastHlc ?: "", record.hlc),
                            bodyJson = chosenBody,
                        ),
                    )
                    recordStates.delete(
                        permissionId,
                        DriveWireCodec.DOMAIN_WORK_ALIAS,
                        rejectRecordKey(minOf(parsed.from, fixpoint), maxOf(parsed.from, fixpoint)),
                    )
                }
            }
            is DriveWorkAliasBody.Reject -> {
                // `merge` supersedes `reject`: if already merged to the same fixpoint, ignore reject.
                val merges = loadMergeMap(permissionId)
                if (resolveCanonical(parsed.a, merges) == resolveCanonical(parsed.b, merges)) return
                val rejectKey = rejectRecordKey(parsed.a, parsed.b)
                val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS, rejectKey)
                val canonicalBody = DriveCanonicalJson.encode(DriveWireCodec.encodeWorkAliasBody(parsed))

                database.withTransaction {
                    for (amb in identities.ambiguitiesFor(peerId)) {
                        val cands = amb.candidates.map { resolveCanonical(it, merges) }.toSet()
                        if (parsed.a in cands && parsed.b in cands && cands.size == 2) {
                            identities.clearAmbiguity(amb.bookUrl, peerId)
                        }
                    }
                }
                store.withTransaction {
                    recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
                            recordKey = rejectKey,
                            versionFp = record.fp,
                            versionOp = DriveRecordState.OP_PUT,
                            publishedFp = record.fp,
                            contentFp = record.fp,
                            originDeviceId = remoteDeviceId,
                            originSeq = remoteSeq,
                            ancestorsJson = DriveCanonicalJson.encode(record.anc.take(DriveWireCodec.MAX_ANCESTORS)),
                            resRefsJson = DriveCanonicalJson.encode(record.resRefs.distinct().sorted()),
                            lastHlc = maxOf(existing?.lastHlc ?: "", record.hlc),
                            bodyJson = canonicalBody,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun loadMergeMap(permissionId: String): Map<String, String> {
        val states = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS)
        return buildMap {
            for (state in states) {
                if (!state.recordKey.startsWith(MERGE_KEY_PREFIX)) continue
                val body = state.bodyJson ?: continue
                val parsed = runCatching { DriveWireCodec.decodeWorkAliasBody(JSONObject(body)) }.getOrNull()
                if (parsed is DriveWorkAliasBody.Merge) {
                    val existing = get(parsed.from)
                    if (existing == null || parsed.to < existing) {
                        put(parsed.from, parsed.to)
                    }
                }
            }
        }
    }

    private fun buildAncestors(existing: DriveRecordState?): List<String> {
        if (existing == null) return emptyList()
        return (listOf(existing.versionFp) + decodeStringArray(existing.ancestorsJson))
            .distinct()
            .take(DriveWireCodec.MAX_ANCESTORS)
    }

    companion object {
        const val DRIVE_BOOK_URL_PREFIX = "drive:"
        const val MERGE_KEY_PREFIX = "merge:"
        const val REJECT_KEY_PREFIX = "reject:"

        fun driveBookUrl(workKey: String): String = "$DRIVE_BOOK_URL_PREFIX$workKey"

        fun mergeRecordKey(fromWorkKey: String): String = "$MERGE_KEY_PREFIX$fromWorkKey"

        fun rejectRecordKey(a: String, b: String): String {
            val low = minOf(a, b)
            val high = maxOf(a, b)
            return "$REJECT_KEY_PREFIX$low|$high"
        }

        /**
         * Derives the deterministic `workKey` for a local/downloaded book once
         * `book_fingerprint` exists and `books.file_metadata_checked = 1` (ADR-0078):
         * 1. `isbn:<isbn13>` from `WorkIdentifiers.dcFrom` or `book_metadata(file).identifiers`
         * 2. `dc:<sha256hex(trim.lowercase(id))>` for any other usable `dc:identifier`
         * 3. `sha:<edition sha256>`
         */
        fun deriveLocalWorkKey(
            book: Book,
            metadataRows: List<BookMetadata>,
            fingerprint: BookFingerprintRow,
        ): String {
            val candidates = buildList {
                WorkIdentifiers.dcFrom(
                    storedWorkId = book.workId,
                    title = book.stableTitle,
                    author = book.identityAuthor ?: book.stableAuthor,
                )?.let(::add)
                val fileMeta = metadataRows.firstOrNull { it.source == MetadataSource.FILE.key }
                fileMeta?.toSnapshot()?.identifiers?.forEach { identifier ->
                    if (identifier.value.isNotBlank()) add(identifier.value)
                }
            }
            for (candidate in candidates) {
                Isbn.from(candidate)?.let { return "isbn:${it.isbn13}" }
            }
            for (candidate in candidates) {
                val normalized = candidate.trim().lowercase(Locale.ROOT)
                if (normalized.isNotEmpty() && normalized !in USELESS_IDENTIFIERS) {
                    return "dc:${DriveCanonicalJson.sha256Hex(normalized)}"
                }
            }
            return "sha:${fingerprint.sha256.lowercase(Locale.ROOT)}"
        }

        /** Follows acyclic `from -> to` (`to < from`) chains to a unique fixpoint. */
        fun resolveCanonical(workKey: String, merges: Map<String, String>): String {
            var current = workKey
            val visited = mutableSetOf<String>()
            while (visited.add(current)) {
                val next = merges[current] ?: break
                if (next >= current) break
                current = next
            }
            return current
        }

        internal fun decodeStringArray(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = org.json.JSONArray(json)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (s.isNotEmpty()) add(s)
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
