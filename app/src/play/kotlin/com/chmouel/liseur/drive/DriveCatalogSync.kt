package com.chmouel.liseur.drive

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.WorkIdentifiers
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Whole-library catalog entry sync for Google Drive (`docs/DRIVE_SYNC.md` §4.2).
 *
 * Invariants:
 * - Catalog entries sync for the ENTIRE library regardless of EPUB upload selection.
 * - Remote-only stubs use `url = "drive:<workKey>"`, `download_state = REMOTE`,
 *   `local_uri = null`, and **`remote_uuid = null`** (never set `remote_uuid`, so
 *   server disconnect logic never touches Drive entries).
 * - `driveFileId` in `editions[]` is published ONLY after the EPUB upload has
 *   succeeded in `drive_upload` / `drive_file`.
 * - `archived_at` auto-resolves by higher HLC; `hidden_at` stays strictly local.
 */
internal class DriveCatalogSync(
    private val database: LiseurDatabase,
    private val store: DriveSyncDatabase,
    private val workIdentity: DriveWorkIdentity,
    bookRemoval: BookRemoval? = null,
    private val onUploadDiscarded: suspend (permissionId: String, sha256: String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val books get() = database.bookDao()
    private val metadata get() = database.bookMetadataDao()
    private val identities get() = database.workIdentityDao()
    private val recordStates get() = store.recordStates()
    private val uploads get() = store.uploads()
    private val driveFiles get() = store.files()

    private val removal: BookRemoval = bookRemoval ?: BookRemoval(
        bookDao = database.bookDao(),
        sessionDao = database.readingSessionDao(),
        peerStateDao = database.syncPeerStateDao(),
        identityDao = database.workIdentityDao(),
        progressDao = database.readingProgressDao(),
        annotationDao = database.annotationDao(),
        annotationSyncDao = database.annotationSyncDao(),
        inTransaction = { work -> database.withTransaction { work() } },
        metadataDao = database.bookMetadataDao(),
        evaluationDao = database.bookEvaluationDao(),
    )

    /**
     * Collects dirty `catalog` records for every eligible library book whose
     * canonical catalog representation changed since last publish, plus any
     * unpublished library deletion tombstones (`op: "del"`).
     */
    suspend fun collectDirtyRecords(
        permissionId: String,
        bookUrlFilter: String? = null,
        nextHlc: suspend () -> String,
    ): List<DriveWireRecord> {
        val out = mutableListOf<DriveWireRecord>()

        // 1. Emit any unpublished durable `catalog` deletion tombstones first.
        for (state in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_CATALOG)) {
            if (state.versionOp != DriveRecordState.OP_DEL) continue
            if (state.publishedFp == state.versionFp) continue
            out.add(
                DriveWireCodec.delRecord(
                    domain = DriveWireCodec.DOMAIN_CATALOG,
                    key = state.recordKey,
                    hlc = state.lastHlc.ifBlank { nextHlc() },
                    ancestors = DriveWorkIdentity.decodeStringArray(state.ancestorsJson),
                    resRefs = DriveWorkIdentity.decodeStringArray(state.resRefsJson),
                    explicitFp = state.versionFp,
                ),
            )
        }

        val eligibleBooks = if (bookUrlFilter == null) {
            books.allOnce()
        } else {
            // A book-scoped run publishes only the entry for that book's work, so its other
            // records never reach a peer ahead of the catalog entry that creates the book.
            val filtered = books.getByUrl(bookUrlFilter) ?: return out
            val alias = workIdentity.resolveBook(permissionId, filtered) ?: return out
            if (!alias.usable) return out
            val workKey = workIdentity.canonicalWorkKey(permissionId, alias.workId)
            val peerId = DriveLibrarySync.accountPeerId(permissionId)
            val sameWorkUrls = identities.aliasesFor(peerId)
                .filter { it.usable && workIdentity.canonicalWorkKey(permissionId, it.workId) == workKey }
                .map { it.bookUrl }
            (sameWorkUrls + bookUrlFilter).distinct().mapNotNull { books.getByUrl(it) }
        }.filter { it.browseServerId == null }
        val uploadedBySha = uploads.forAccount(permissionId)
            .filter {
                it.state == DriveUpload.STATE_UPLOADED &&
                    !it.fileId.isNullOrBlank() &&
                    driveFiles.get(permissionId, it.fileId)?.trashed != true
            }
            .associateBy { it.sha256.lowercase(Locale.ROOT) }
        // A deselected upload's file awaiting trash is never advertised as available.
        val awaitingCleanup = uploads.forAccount(permissionId)
            .filter { it.state == DriveUpload.STATE_CANCELLED || it.state == DriveUpload.STATE_REQUEUE }
        val cancelledFileIds = awaitingCleanup.mapNotNull { it.fileId }.toSet()

        val grouped = linkedMapOf<String, MutableList<Pair<Book, WorkAlias>>>()
        for (book in eligibleBooks) {
            val alias = workIdentity.resolveBook(permissionId, book) ?: continue
            if (!alias.usable) continue
            val canonical = workIdentity.canonicalWorkKey(permissionId, alias.workId)
            grouped.getOrPut(canonical) { mutableListOf() }.add(book to alias)
        }

        for ((workKey, entries) in grouped) {
            val primaryBook = entries.firstOrNull { it.first.openableUrl != null }?.first ?: entries.first().first
            val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, workKey)
            val existingBody = existingState?.bodyJson?.let {
                runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(it)) }.getOrNull()
            }

            val identifiers = buildList {
                existingBody?.identifiers?.let(::addAll)
                for ((book, _) in entries) {
                    val rows = metadata.forBook(book.url)
                    val bySource = rows.associateBy { it.source }
                    listOf(MetadataSource.FILE.key, MetadataSource.CATALOG.key).forEach { src ->
                        bySource[src]?.toSnapshot()?.identifiers?.forEach { id ->
                            if (id.value.isNotBlank()) {
                                add(
                                    DriveCatalogIdentifier(
                                        scheme = id.scheme?.ifBlank { "other" } ?: "other",
                                        value = id.value.trim(),
                                    ),
                                )
                            }
                        }
                    }
                }
            }.distinctBy { it.scheme to it.value }
                .sortedWith(compareBy({ it.scheme }, { it.value }))

            val editionsBySha = linkedMapOf<String, DriveCatalogEdition>()
            existingBody?.editions?.forEach { editionsBySha[it.sha256.lowercase(Locale.ROOT)] = it }

            for ((book, alias) in entries) {
                val fpRow = identities.fingerprint(book.url)
                val sha = (fpRow?.sha256 ?: alias.editionSha)?.trim()?.lowercase(Locale.ROOT)
                if (sha.isNullOrEmpty()) continue
                val existingEd = editionsBySha[sha]
                val upload = uploadedBySha[sha]
                val trashedFile = existingEd?.driveFileId?.let { fid ->
                    driveFiles.get(permissionId, fid)?.trashed == true
                } == true

                val resolvedFileId = when {
                    upload?.fileId != null -> upload.fileId
                    trashedFile -> null
                    else -> existingEd?.driveFileId?.takeIf { it !in cancelledFileIds }
                }
                val resolvedUploadedAt = when {
                    resolvedFileId == null -> null
                    upload?.fileId == resolvedFileId -> upload.updatedAt
                    else -> existingEd?.uploadedAt ?: clock()
                }
                val resolvedFileName = when {
                    resolvedFileId == null -> null
                    else -> existingEd?.fileName ?: sanitizeEpubFileName(primaryBook.stableTitle ?: primaryBook.title)
                }
                editionsBySha[sha] = DriveCatalogEdition(
                    sha256 = sha,
                    sizeBytes = fpRow?.fileSize ?: book.sizeBytes ?: upload?.sizeBytes ?: existingEd?.sizeBytes,
                    driveFileId = resolvedFileId,
                    fileName = if (existingEd != null && resolvedFileId == existingEd.driveFileId) existingEd.fileName else resolvedFileName,
                    uploadedAt = if (existingEd != null && resolvedFileId == existingEd.driveFileId) existingEd.uploadedAt else resolvedUploadedAt,
                    availabilityHlc = if (resolvedFileId != existingEd?.driveFileId) nextHlc() else existingEd?.availabilityHlc,
                )
            }

            // Also ensure any trashed Drive EPUB clears driveFileId cleanly.
            for ((sha, ed) in editionsBySha.toMap()) {
                val fid = ed.driveFileId ?: continue
                if (driveFiles.get(permissionId, fid)?.trashed == true) {
                    editionsBySha[sha] = ed.copy(driveFileId = null, fileName = null, uploadedAt = null, availabilityHlc = nextHlc())
                }
            }

            val addedAt = (listOfNotNull(existingBody?.addedAt) + entries.map { it.first.addedAt })
                .filter { it > 0L }
                .minOrNull() ?: primaryBook.addedAt
            val title = primaryBook.stableTitle?.takeIf { it.isNotBlank() }
                ?: existingBody?.title?.takeIf { it.isNotBlank() }
                ?: primaryBook.title
            val author = primaryBook.stableAuthor ?: existingBody?.author
            val fileSeriesName = primaryBook.fileSeriesName ?: existingBody?.fileSeriesName
            val fileSeriesIndex = if (fileSeriesName != null) {
                primaryBook.fileSeriesIndex ?: existingBody?.fileSeriesIndex
            } else {
                null
            }
            val archivedAt = primaryBook.archivedAt
            // A changed archive choice gets its own HLC so a later record that merely carries
            // the old value (an availability update, say) cannot override it.
            val archiveChanged = existingBody != null && (archivedAt != null) != (existingBody.archivedAt != null) ||
                existingBody == null && archivedAt != null
            val pendingArchiveHlc = if (archiveChanged) nextHlc() else existingBody?.archiveHlc

            // Compare content without updatedAt drift: reuse existing updatedAt if semantic fields match.
            val candidateBody = DriveCatalogBody(
                workKey = workKey,
                title = title,
                author = author,
                identifiers = identifiers,
                editions = editionsBySha.values.sortedBy { it.sha256 },
                fileSeriesName = fileSeriesName,
                fileSeriesIndex = fileSeriesIndex,
                addedAt = addedAt,
                archivedAt = archivedAt,
                updatedAt = existingBody?.updatedAt ?: clock(),
                archiveHlc = pendingArchiveHlc,
            )
            val candidateMap = DriveWireCodec.encodeCatalogBody(candidateBody)
            val candidateFp = DriveCanonicalJson.contentFp(candidateMap)
            if (existingState != null && existingState.versionOp == DriveRecordState.OP_PUT && existingState.contentFp == candidateFp) {
                if (existingState.publishedFp != existingState.versionFp) {
                    out.add(
                        DriveWireRecord(
                            d = DriveWireCodec.DOMAIN_CATALOG, k = workKey, op = DriveRecordState.OP_PUT,
                            hlc = existingState.lastHlc, fp = existingState.versionFp,
                            anc = DriveWorkIdentity.decodeStringArray(existingState.ancestorsJson),
                            resRefs = DriveWorkIdentity.decodeStringArray(existingState.resRefsJson),
                            body = JSONObject(requireNotNull(existingState.bodyJson)),
                        ),
                    )
                }
                continue
            }

            val finalBody = candidateBody.copy(updatedAt = clock())
            val finalMap = DriveWireCodec.encodeCatalogBody(finalBody)
            val contentFp = DriveCanonicalJson.contentFp(finalMap)
            val hlc = nextHlc()
            val finalFp = DriveCanonicalJson.putFp(finalMap, hlc)
            val ancestors = buildList {
                existingState?.versionFp?.takeIf { it != finalFp }?.let(::add)
                addAll(DriveWorkIdentity.decodeStringArray(existingState?.ancestorsJson))
            }.distinct().take(DriveWireCodec.MAX_ANCESTORS)

            val record = DriveWireCodec.putRecord(
                domain = DriveWireCodec.DOMAIN_CATALOG,
                key = workKey,
                hlc = hlc,
                bodyMap = finalMap,
                ancestors = ancestors,
                resRefs = DriveWorkIdentity.decodeStringArray(existingState?.resRefsJson),
            )
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = DriveWireCodec.DOMAIN_CATALOG,
                    recordKey = workKey,
                    versionFp = finalFp,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = existingState?.publishedFp,
                    contentFp = contentFp,
                    originDeviceId = null,
                    originSeq = null,
                    ancestorsJson = DriveCanonicalJson.encode(ancestors),
                    resRefsJson = existingState?.resRefsJson ?: "[]",
                    lastHlc = hlc,
                    bodyJson = DriveCanonicalJson.encode(finalMap),
                ),
            )
            out.add(record)
        }
        return out
    }

    /** Marks published `catalog` records acknowledged in `drive_record_state`. */
    suspend fun markPublished(permissionId: String, records: List<DriveWireRecord>) {
        for (record in records) {
            if (record.d != DriveWireCodec.DOMAIN_CATALOG) continue
            val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, record.k) ?: continue
            if (existing.versionFp == record.fp) {
                recordStates.upsert(existing.copy(publishedFp = record.fp))
            }
        }
    }

    /**
     * Applies an incoming remote `catalog` record idempotently.
     *
     * Restores catalog stubs (`download_state = REMOTE`, `url = "drive:<workKey>"`,
     * `remote_uuid = null`) before any EPUB download occurs.
     */
    suspend fun applyRemoteRecord(
        permissionId: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
        nextHlc: suspend () -> String,
    ) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        if (record.op == DriveRecordState.OP_DEL) {
            val canonical = workIdentity.canonicalWorkKey(permissionId, record.k)
            val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, canonical)
            if (existing != null && record.fp in DriveWorkIdentity.decodeStringArray(existing.ancestorsJson)) return
            if (existing != null && existing.versionOp == DriveRecordState.OP_DEL && record.hlc <= existing.lastHlc) {
                return
            }
            val shasToClear = removeLocalWorkCopies(permissionId, peerId, canonical, selected = null).toMutableSet()
            existing?.bodyJson?.let { raw ->
                runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(raw)) }.getOrNull()
            }?.editions?.forEach { ed ->
                shasToClear.add(ed.sha256.lowercase(Locale.ROOT))
            }
            store.withTransaction {
                for (sha in shasToClear) {
                    dropUpload(permissionId, sha)
                }
                val current = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, canonical)
                val pendingTrash = current?.takeIf { it.versionOp == DriveRecordState.OP_DEL }
                    ?.bodyJson
                    ?.let { TombstonePayload.parse(it).copy(cleanupPending = false).encode() }
                val anc = (listOfNotNull(existing?.versionFp) +
                    DriveWorkIdentity.decodeStringArray(existing?.ancestorsJson) + record.anc)
                    .distinct()
                    .take(DriveWireCodec.MAX_ANCESTORS)
                recordStates.upsert(
                    DriveRecordState(
                        permissionId = permissionId,
                        domain = DriveWireCodec.DOMAIN_CATALOG,
                        recordKey = canonical,
                        versionFp = record.fp,
                        versionOp = DriveRecordState.OP_DEL,
                        publishedFp = record.fp,
                        contentFp = null,
                        originDeviceId = remoteDeviceId,
                        originSeq = remoteSeq,
                        ancestorsJson = DriveCanonicalJson.encode(anc),
                        resRefsJson = DriveCanonicalJson.encode(record.resRefs.distinct().sorted()),
                        lastHlc = maxOf(existing?.lastHlc ?: "", record.hlc),
                        bodyJson = pendingTrash,
                    ),
                )
            }
            for (sha in shasToClear) {
                onUploadDiscarded(permissionId, sha)
            }
            return
        }

        val bodyJson = record.body ?: return
        val incoming = DriveWireCodec.decodeCatalogBody(bodyJson) ?: return
        val canonicalKey = workIdentity.canonicalWorkKey(permissionId, incoming.workKey)
        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, canonicalKey)
        if (existingState != null && existingState.versionOp == DriveRecordState.OP_DEL) {
            val existingAnc = DriveWorkIdentity.decodeStringArray(existingState.ancestorsJson)
            val supersedesDeletion = existingState.versionFp in record.anc && record.fp !in existingAnc
            if (!supersedesDeletion) {
                return
            }
        }
        val existingBody = existingState?.bodyJson?.let {
            runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(it)) }.getOrNull()
        }
        val incomingCanonical = incoming.copy(workKey = canonicalKey)
        val now = clock()
        // Evaluate local alias conflicts against the unmerged incoming catalog before edition union,
        // so pre-existing local catalog state under a colliding ISBN cannot hide the conflict.
        val matchResult = findMatchingLocalBookUrls(permissionId, canonicalKey, incomingCanonical, now)
        val localPublishedEarlier = existingState != null &&
            !existingState.publishedFp.isNullOrBlank() &&
            existingState.lastHlc.isNotBlank() &&
            existingState.lastHlc < record.hlc
        if (matchResult.rekeyedAliases.isNotEmpty() && matchResult.matchedUrls.isEmpty() && localPublishedEarlier) {
            // Both devices independently published unrelated works under the same identifier key;
            // keep the earlier-published local work on canonicalKey while the later device re-keys to sha:<sha>.
            return
        }

        val filteredExistingBody = if (existingBody != null && matchResult.conflictingShas.isNotEmpty()) {
            val remainingEditions = existingBody.editions.filter {
                it.sha256.lowercase(Locale.ROOT) !in matchResult.conflictingShas
            }
            if (remainingEditions.isEmpty()) {
                null
            } else {
                existingBody.copy(editions = remainingEditions)
            }
        } else {
            existingBody
        }
        val replacedConflictingExisting = existingBody != null && filteredExistingBody == null
        val incomingNewerHlc = existingState == null || replacedConflictingExisting || record.hlc > existingState.lastHlc

        val merged = mergeCatalogBodies(
            existing = filteredExistingBody,
            incoming = incomingCanonical,
            incomingNewerHlc = incomingNewerHlc,
        )
        val mergedMap = DriveWireCodec.encodeCatalogBody(merged)
        val mergedJson = DriveCanonicalJson.encode(mergedMap)
        val mergedFp = DriveCanonicalJson.contentFp(mergedMap)
        val incomingFp = DriveCanonicalJson.contentFp(bodyJson)
        val useIncoming = mergedFp == incomingFp
        val useExisting = !useIncoming && existingState?.versionOp == DriveRecordState.OP_PUT && existingState.contentFp == mergedFp
        val versionHlc = when {
            useIncoming -> record.hlc
            useExisting -> requireNotNull(existingState).lastHlc
            else -> nextHlc()
        }
        val versionFp = when {
            useIncoming -> record.fp
            useExisting -> requireNotNull(existingState).versionFp
            else -> DriveCanonicalJson.putFp(mergedMap, versionHlc)
        }

        val uploadedEdition = merged.editions.firstOrNull { ed ->
            val fid = ed.driveFileId
            !fid.isNullOrBlank() && driveFiles.get(permissionId, fid)?.trashed != true
        }
        val primaryEdition = uploadedEdition ?: merged.editions.firstOrNull()
        val downloadHref = uploadedEdition?.driveFileId?.let(::downloadHrefFor)

        val matchingBookUrls = matchResult.matchedUrls

        // Transaction 1 (`liseur.db`): re-key conflicting local aliases and insert stub or update existing book metadata.
        database.withTransaction {
            for (rekeyed in matchResult.rekeyedAliases) {
                identities.upsert(rekeyed)
            }
            if (matchingBookUrls.isEmpty()) {
                val stubUrl = DriveWorkIdentity.driveBookUrl(canonicalKey)
                val existingStub = books.getByUrl(stubUrl)
                if (existingStub == null) {
                    books.upsert(
                        Book(
                            url = stubUrl,
                            title = merged.title,
                            author = merged.author,
                            coverPath = null,
                            source = null,
                            addedAt = merged.addedAt.takeIf { it > 0L } ?: now,
                            lastOpenedAt = null,
                            localUri = null,
                            remoteUuid = null, // STRICT INVARIANT: never set remote_uuid on Drive rows.
                            browseServerId = null,
                            downloadHref = downloadHref,
                            downloadState = DownloadState.REMOTE,
                            sizeBytes = primaryEdition?.sizeBytes,
                            fileTitle = merged.title,
                            fileAuthor = merged.author,
                            fileSeriesName = merged.fileSeriesName,
                            fileSeriesIndex = merged.fileSeriesIndex,
                            seriesName = merged.fileSeriesName,
                            seriesIndex = merged.fileSeriesIndex,
                            archivedAt = merged.archivedAt,
                        ),
                    )
                } else {
                    val refreshed = if (incomingNewerHlc) existingStub.withCatalogBaseline(merged) else existingStub
                    val updated = refreshed.copy(
                        remoteUuid = null,
                        downloadHref = downloadHref,
                        sizeBytes = primaryEdition?.sizeBytes ?: existingStub.sizeBytes,
                        archivedAt = if (
                            existingBody != null &&
                            (existingStub.archivedAt != null) != (existingBody.archivedAt != null)
                        ) {
                            existingStub.archivedAt
                        } else {
                            merged.archivedAt
                        },
                    )
                    books.upsert(updated)
                }
                identities.upsert(
                    WorkAlias(
                        bookUrl = stubUrl,
                        peerId = peerId,
                        workId = canonicalKey,
                        confidence = WorkAlias.HIGH,
                        confirmed = true,
                        seeded = false,
                        // File-less until a download pins the installed edition.
                        editionSha = null,
                        resolvedAt = now,
                    ),
                )
            } else {
                for (url in matchingBookUrls) {
                    val book = books.getByUrl(url) ?: continue
                    val existingAlias = identities.alias(url, peerId)
                    if (existingAlias == null) {
                        val localSha = identities.fingerprint(url)?.sha256?.lowercase(Locale.ROOT)
                            ?: primaryEdition?.sha256?.takeUnless { isFilelessUnownedRow(book) }
                        identities.upsert(
                            WorkAlias(
                                bookUrl = url,
                                peerId = peerId,
                                workId = canonicalKey,
                                confidence = WorkAlias.HIGH,
                                confirmed = true,
                                seeded = false,
                                editionSha = localSha,
                                resolvedAt = now,
                            ),
                        )
                    }
                    // A local archive choice that differs from the last agreed catalog value is an
                    // unpublished edit; keep it so collectDirtyRecords publishes it over the pull.
                    val localArchiveUnpublished = filteredExistingBody != null &&
                        (book.archivedAt != null) != (filteredExistingBody.archivedAt != null)
                    val archivedAt = if (localArchiveUnpublished) book.archivedAt else merged.archivedAt
                    if (book.archivedAt != archivedAt) {
                        books.setArchived(url, archivedAt)
                    }
                    if (isFilelessUnownedRow(book)) {
                        val refreshed = if (incomingNewerHlc && book.url.startsWith(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX)) {
                            book.withCatalogBaseline(merged)
                        } else book
                        books.upsert(
                            refreshed.copy(
                                remoteUuid = null,
                                downloadHref = downloadHref,
                                sizeBytes = primaryEdition?.sizeBytes ?: book.sizeBytes,
                                archivedAt = archivedAt,
                            ),
                        )
                    }
                }
            }
        }

        // Check for ambiguous title/author match against other local books (never silently merges).
        if (matchingBookUrls.isEmpty()) {
            workIdentity.detectAmbiguousTitleAuthorMatch(
                permissionId = permissionId,
                remoteWorkKey = canonicalKey,
                remoteTitle = merged.title,
                remoteAuthor = merged.author,
            )
        }

        // Transaction 2 (`drive_sync.db`): record merged catalog state & discovered Drive EPUB files.
        store.withTransaction {
            val anc = (listOfNotNull(existingState?.versionFp, record.fp) +
                DriveWorkIdentity.decodeStringArray(existingState?.ancestorsJson) + record.anc)
                .distinct()
                .filter { it != versionFp }
                .take(DriveWireCodec.MAX_ANCESTORS)
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = DriveWireCodec.DOMAIN_CATALOG,
                    recordKey = canonicalKey,
                    versionFp = versionFp,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = when {
                        useIncoming -> record.fp
                        useExisting -> existingState?.publishedFp
                        else -> null
                    },
                    contentFp = mergedFp,
                    originDeviceId = if (useIncoming) remoteDeviceId else if (useExisting) existingState?.originDeviceId else null,
                    originSeq = if (useIncoming) remoteSeq else if (useExisting) existingState?.originSeq else null,
                    ancestorsJson = DriveCanonicalJson.encode(anc),
                    resRefsJson = DriveCanonicalJson.encode(record.resRefs.distinct().sorted()),
                    lastHlc = versionHlc,
                    bodyJson = mergedJson,
                ),
            )
            for (ed in merged.editions) {
                val fid = ed.driveFileId ?: continue
                if (driveFiles.get(permissionId, fid) == null) {
                    driveFiles.upsert(
                        DriveFile(
                            permissionId = permissionId,
                            fileId = fid,
                            name = ed.fileName ?: "${ed.sha256}.epub",
                            kind = DriveFile.KIND_EPUB,
                            sha256 = ed.sha256,
                            size = ed.sizeBytes,
                            trashed = false,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Cleans every local copy of [canonical]: fileless `drive:` stubs go via [BookRemoval]
     * (preserving annotations); books with a local file are only unlinked from Drive.
     * Returns the edition SHAs of the cleaned copies.
     */
    private suspend fun removeLocalWorkCopies(
        permissionId: String,
        peerId: String,
        canonical: String,
        selected: Pair<Book, String?>?,
    ): Set<String> {
        val targets = linkedMapOf<String, Pair<Book, String?>>()
        val shas = linkedSetOf<String>()
        for (alias in identities.aliasesFor(peerId)) {
            if (workIdentity.canonicalWorkKey(permissionId, alias.workId) != canonical) continue
            val book = books.getByUrl(alias.bookUrl) ?: continue
            alias.editionSha?.lowercase(Locale.ROOT)?.let(shas::add)
            targets[book.url] = book to alias.workId
        }
        selected?.let { (book, workId) ->
            books.getByUrl(book.url)?.let { targets[book.url] = it to workId }
        }
        // The canonical drive:<workKey> stub may exist without an alias.
        val stubUrl = DriveWorkIdentity.driveBookUrl(canonical)
        if (stubUrl !in targets) {
            books.getByUrl(stubUrl)?.let { targets[stubUrl] = it to null }
        }
        val remoteOnlyUrls = mutableListOf<String>()
        val unlinks = mutableListOf<Pair<String, String?>>()
        val sharedStubs = mutableListOf<Pair<String, String?>>()
        for ((url, target) in targets) {
            val (book, staleWorkId) = target
            identities.fingerprint(url)?.sha256?.lowercase(Locale.ROOT)?.let(shas::add)
            val remoteOnly = url.startsWith(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX) && book.localUri == null
            if (remoteOnly && !identities.hasAliasFromOtherPeer(url, peerId)) {
                remoteOnlyUrls.add(url)
            } else if (remoteOnly) {
                // Another account still references this stub: only this account's link goes.
                sharedStubs.add(url to staleWorkId)
            } else {
                unlinks.add(url to staleWorkId)
            }
        }
        if (remoteOnlyUrls.isNotEmpty()) {
            removal.deleteByUrls(remoteOnlyUrls, preserveReadingHistory = true)
        }
        if (unlinks.isNotEmpty() || sharedStubs.isNotEmpty()) {
            database.withTransaction {
                for ((bookUrl, staleWorkId) in sharedStubs) {
                    if (staleWorkId != null) identities.deleteAliasIfStale(bookUrl, peerId, staleWorkId)
                    identities.clearAmbiguity(bookUrl, peerId)
                }
                for ((bookUrl, staleWorkId) in unlinks) {
                    val alias = identities.alias(bookUrl, peerId)
                    if (alias != null && alias.workId == staleWorkId) {
                        identities.upsert(alias.copy(confidence = WorkAlias.REJECTED, confirmed = false))
                    }
                    identities.clearAmbiguity(bookUrl, peerId)
                    val latest = books.getByUrl(bookUrl) ?: continue
                    if (fileIdFromHref(latest.downloadHref) != null) {
                        books.upsert(latest.copy(downloadHref = null))
                    }
                }
            }
        }
        return shas
    }

    /**
     * Explicit **Delete from synced library** (`docs/DRIVE_SYNC.md` §4.2 & ADR-0078):
     * 1. Writes a durable `catalog` `op: "del"` tombstone in `drive_record_state`.
     * 2. Trashes any uploaded Drive EPUB in `Liseur/` (or queues pending trash if offline).
     * 3. Removes `drive:<workKey>` rows via [BookRemoval] (preserving annotations) or
     *    unlinks local-file books from Drive without deleting the reader's local file.
     */
    suspend fun deleteFromSyncedLibrary(
        permissionId: String,
        book: Book,
        nextHlc: suspend () -> String,
        driveApi: DriveRemoteApi? = null,
        accessToken: String? = null,
    ): Boolean {
        if (book.browseServerId != null) return false
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val alias = identities.alias(book.url, peerId)?.takeIf { it.usable }
            ?: workIdentity.resolveBook(permissionId, book, computeFingerprintIfMissing = false)
        val rawWorkKey = alias?.workId
            ?: book.url.takeIf { it.startsWith(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX) }
                ?.removePrefix(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX)
            ?: return false
        val canonicalKey = workIdentity.canonicalWorkKey(permissionId, rawWorkKey)

        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, canonicalKey)
        val existingBody = existingState?.bodyJson?.let {
            runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(it)) }.getOrNull()
        }

        // Collect all Drive EPUB file IDs & edition SHAs tied to this work.
        val fileIdsToTrash = linkedSetOf<String>()
        val shasToRemove = linkedSetOf<String>()
        existingBody?.editions?.forEach { ed ->
            shasToRemove.add(ed.sha256.lowercase(Locale.ROOT))
            ed.driveFileId?.takeIf { it.isNotBlank() }?.let(fileIdsToTrash::add)
        }
        fileIdFromHref(book.downloadHref)?.let(fileIdsToTrash::add)
        // Same-account copies of the work absent from the stored catalog (e.g. an unpublished
        // second edition) still need their uploads, staged files and remote copies cleaned.
        for (workAlias in identities.aliasesFor(peerId)) {
            if (workIdentity.canonicalWorkKey(permissionId, workAlias.workId) != canonicalKey) continue
            workAlias.editionSha?.lowercase(Locale.ROOT)?.let(shasToRemove::add)
            val copy = books.getByUrl(workAlias.bookUrl) ?: continue
            identities.fingerprint(copy.url)?.sha256?.lowercase(Locale.ROOT)?.let(shasToRemove::add)
            fileIdFromHref(copy.downloadHref)?.let(fileIdsToTrash::add)
        }
        alias?.editionSha?.lowercase(Locale.ROOT)?.let(shasToRemove::add)
        identities.fingerprint(book.url)?.sha256?.lowercase(Locale.ROOT)?.let(shasToRemove::add)

        val hlc = nextHlc()
        val delFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_CATALOG, canonicalKey, hlc)
        val anc = buildList {
            existingState?.versionFp?.let(::add)
            addAll(DriveWorkIdentity.decodeStringArray(existingState?.ancestorsJson))
        }.distinct().take(DriveWireCodec.MAX_ANCESTORS)

        // Persist tombstone & pending trash IDs FIRST so a crash or cancellation while trashing
        // remote files never leaves the EPUB gone without recording the catalog deletion.
        // The tombstone and the local cleanup are one unit: a cancellation after the durable
        // write must not leave the deletion half-applied, and nothing here touches the network.
        withContext(NonCancellable) {
            store.withTransaction {
                // Read in the same transaction that drops the rows, so an upload that completed
                // since the catalog read above still has its file trashed.
                for (sha in shasToRemove) {
                    uploads.get(permissionId, sha)?.fileId?.takeIf { it.isNotBlank() }?.let(fileIdsToTrash::add)
                    driveFiles.byKindAndSha256(permissionId, DriveFile.KIND_EPUB, sha)
                        .forEach { fileIdsToTrash.add(it.fileId) }
                }
                val tombstonePayload = TombstonePayload(
                    pendingTrashFileIds = fileIdsToTrash.distinct().sorted(),
                    cleanupPending = true,
                    cleanupBookUrl = book.url,
                    cleanupWorkId = alias?.workId,
                )
                for (fid in fileIdsToTrash) {
                    if (driveFiles.get(permissionId, fid) != null) {
                        driveFiles.setTrashed(permissionId, fid, true)
                    }
                }
                for (sha in shasToRemove) {
                    dropUpload(permissionId, sha)
                }
                recordStates.upsert(
                    DriveRecordState(
                        permissionId = permissionId,
                        domain = DriveWireCodec.DOMAIN_CATALOG,
                        recordKey = canonicalKey,
                        versionFp = delFp,
                        versionOp = DriveRecordState.OP_DEL,
                        publishedFp = null, // unpublished -> emitted in next delta batch
                        contentFp = null,
                        originDeviceId = null,
                        originSeq = null,
                        ancestorsJson = DriveCanonicalJson.encode(anc),
                        resRefsJson = existingState?.resRefsJson ?: "[]",
                        lastHlc = hlc,
                        bodyJson = tombstonePayload.encode(),
                    ),
                )
            }

            // Every local copy of the work is cleaned, not only the selected edition.
            removeLocalWorkCopies(permissionId, peerId, canonicalKey, selected = book to alias?.workId)
            clearCleanupPending(permissionId, canonicalKey)
            for (sha in shasToRemove) {
                onUploadDiscarded(permissionId, sha)
            }
        }

        // Best-effort trash immediately after the tombstone is durable; any remaining IDs
        // stay queued in `pendingTrashFileIds` for the next sync.
        if (fileIdsToTrash.isNotEmpty() && driveApi != null && !accessToken.isNullOrBlank()) {
            drainPendingEpubTrashes(permissionId, driveApi, accessToken)
        }
        return true
    }

    /**
     * Removes an upload row, except that a row that may own a resumable session or a created
     * file becomes `CANCELLED` so the upload cleanup still resolves and trashes it.
     */
    private suspend fun dropUpload(permissionId: String, sha: String) {
        val row = uploads.get(permissionId, sha) ?: return
        val owesCleanup = row.state == DriveUpload.STATE_UPLOADING || row.state == DriveUpload.STATE_REQUEUE ||
            (row.state != DriveUpload.STATE_UPLOADED && (row.sessionUri != null || row.fileId != null))
        if (owesCleanup) {
            if (row.state != DriveUpload.STATE_CANCELLED) {
                uploads.transition(permissionId, sha, row.state, DriveUpload.STATE_CANCELLED, clock())
            }
        } else {
            uploads.delete(permissionId, sha)
        }
    }

    /** Drains any Drive EPUB trash operations queued while offline during `deleteFromSyncedLibrary`. */
    suspend fun drainPendingEpubTrashes(
        permissionId: String,
        driveApi: DriveRemoteApi,
        accessToken: String,
    ) {
        for (state in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_CATALOG)) {
            if (state.versionOp != DriveRecordState.OP_DEL) continue
            val payload = state.bodyJson?.let(TombstonePayload::parse) ?: continue
            if (payload.pendingTrashFileIds.isEmpty()) continue
            val remaining = mutableListOf<String>()
            for (fid in payload.pendingTrashFileIds) {
                val trashed = try {
                    driveApi.trashFile(accessToken, fid)
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: DriveNotFoundException) {
                    true
                } catch (_: Exception) {
                    false
                }
                if (!trashed) remaining.add(fid)
            }
            // Re-read so a cleanup flag cleared while the network call ran is not restored.
            val latest = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, state.recordKey) ?: continue
            if (latest.versionOp != DriveRecordState.OP_DEL) continue
            val latestPayload = latest.bodyJson?.let(TombstonePayload::parse) ?: continue
            val nextBody = latestPayload.copy(pendingTrashFileIds = remaining.distinct().sorted()).encode()
            if (nextBody != latest.bodyJson) {
                recordStates.upsert(latest.copy(bodyJson = nextBody))
            }
        }
    }

    /**
     * Finishes local cleanup for catalog tombstones written by [deleteFromSyncedLibrary] whose
     * process died before the cleanup completed. Runs before any alias resolution so a surviving
     * book is not re-linked to a deleted work. Idempotent: the durable flag clears only after cleanup.
     */
    suspend fun recoverPendingCleanups(permissionId: String) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        for (state in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_CATALOG)) {
            if (state.versionOp != DriveRecordState.OP_DEL) continue
            val payload = state.bodyJson?.let(TombstonePayload::parse) ?: continue
            if (!payload.cleanupPending) continue
            val selected = payload.cleanupBookUrl
                ?.let { books.getByUrl(it) }
                ?.let { it to payload.cleanupWorkId }
            withContext(NonCancellable) {
                removeLocalWorkCopies(permissionId, peerId, state.recordKey, selected)
                clearCleanupPending(permissionId, state.recordKey)
            }
        }
    }

    private suspend fun clearCleanupPending(permissionId: String, canonicalKey: String) {
        store.withTransaction {
            val current = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, canonicalKey) ?: return@withTransaction
            if (current.versionOp != DriveRecordState.OP_DEL) return@withTransaction
            val payload = current.bodyJson?.let(TombstonePayload::parse) ?: return@withTransaction
            if (!payload.cleanupPending) return@withTransaction
            recordStates.upsert(current.copy(bodyJson = payload.copy(cleanupPending = false).encode()))
        }
    }

    /**
     * Marks a Drive-side EPUB file unavailable when `changes.list` or a full
     * bootstrap rescan reports it removed or trashed in Google Drive, retaining
     * the catalog entry and all local reading history while suppressing that
     * edition for the account so only an explicit re-selection sends it up again.
     */
    suspend fun handleDriveEpubRemoved(permissionId: String, fileId: String, nextHlc: suspend () -> String) {
        val removedHref = downloadHrefFor(fileId)
        val statesToUpdate = mutableListOf<DriveRecordState>()
        val stubUpdates = linkedMapOf<String, String?>()

        store.withTransaction {
            val removedSha = driveFiles.get(permissionId, fileId)?.sha256?.lowercase(Locale.ROOT)
            var removedEditionSha: String? = null
            driveFiles.setTrashed(permissionId, fileId, true)
            // Keep the row as a suppression instead of deleting it: a re-added or re-read book
            // must not upload itself again on the next launch. Re-selecting it by hand clears it.
            uploads.suppressUploadedByFileId(permissionId, fileId, clock())
            val states = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_CATALOG)
            for (state in states) {
                if (state.versionOp == DriveRecordState.OP_DEL) continue
                val bodyJson = state.bodyJson ?: continue
                val body = runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(bodyJson)) }.getOrNull() ?: continue
                removedEditionSha = (body.editions.firstOrNull { it.driveFileId == fileId } ?: continue)
                    .sha256.lowercase(Locale.ROOT)
                val hlc = nextHlc()
                val updatedEditions = body.editions.map { ed ->
                    if (ed.driveFileId == fileId) ed.copy(driveFileId = null, fileName = null, uploadedAt = null, availabilityHlc = hlc) else ed
                }
                val updatedBody = body.copy(editions = updatedEditions, updatedAt = clock())
                val map = DriveWireCodec.encodeCatalogBody(updatedBody)
                val contentFp = DriveCanonicalJson.contentFp(map)
                val fp = DriveCanonicalJson.putFp(map, hlc)
                val uploaded = updatedEditions.firstOrNull { ed ->
                    val fid = ed.driveFileId
                    !fid.isNullOrBlank() && driveFiles.get(permissionId, fid)?.trashed != true
                }
                val newHref = uploaded?.driveFileId?.let(::downloadHrefFor)
                stubUpdates[DriveWorkIdentity.driveBookUrl(body.workKey)] = newHref
                statesToUpdate.add(
                    state.copy(
                        versionFp = fp,
                        publishedFp = null, // republish cleared availability
                        contentFp = contentFp,
                        ancestorsJson = DriveCanonicalJson.encode(
                            (listOf(state.versionFp) + DriveWorkIdentity.decodeStringArray(state.ancestorsJson))
                                .distinct().take(DriveWireCodec.MAX_ANCESTORS),
                        ),
                        lastHlc = hlc,
                        bodyJson = DriveCanonicalJson.encode(map),
                    ),
                )
            }
            for (updatedState in statesToUpdate) {
                recordStates.upsert(updatedState)
            }
            // A book held only by another device's upload has no queue row here, so the deleted
            // edition needs one of its own or the automatic offer would send its bytes up again.
            (removedSha ?: removedEditionSha)?.let { uploads.suppressEdition(permissionId, it, clock()) }
        }

        database.withTransaction {
            val handledUrls = mutableSetOf<String>()
            for ((stubUrl, newHref) in stubUpdates) {
                books.getByUrl(stubUrl)?.let { stub ->
                    if (stub.localUri == null) {
                        books.upsert(stub.copy(downloadHref = newHref))
                        handledUrls.add(stub.url)
                    }
                }
            }
            for (book in books.allOnce()) {
                if (book.url !in handledUrls && book.localUri == null && book.downloadHref == removedHref) {
                    val key = book.url.removePrefix(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX)
                    val body = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, key)?.bodyJson
                        ?.let { runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(it)) }.getOrNull() }
                    val replacement = body?.editions?.firstOrNull { ed ->
                        val fid = ed.driveFileId
                        !fid.isNullOrBlank() && fid != fileId && driveFiles.get(permissionId, fid)?.trashed != true
                    }?.driveFileId?.let(::downloadHrefFor)
                    books.upsert(book.copy(downloadHref = replacement))
                }
            }
        }
    }

    /** Finds the uploaded Drive file ID and expected SHA-256 for a Drive book download. */
    suspend fun resolveDownloadSource(
        permissionId: String,
        book: Book,
        requiredSha256: String? = null,
    ): Pair<String, String>? {
        val alias = workIdentity.resolveBook(permissionId, book, computeFingerprintIfMissing = false)
        val workKey = alias?.workId
            ?.let { workIdentity.canonicalWorkKey(permissionId, it) }
            ?: book.url.takeIf { it.startsWith(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX) }
                ?.removePrefix(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX)
            ?: return null

        val catalogState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, workKey)
            ?.takeIf { it.versionOp != DriveRecordState.OP_DEL }
        val body = catalogState?.bodyJson?.let {
            runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(it)) }.getOrNull()
        }
        // A removed download keeps its alias, so a later download fetches the same edition.
        val required = (requiredSha256 ?: alias?.editionSha)?.lowercase(Locale.ROOT)
        val editions = body?.editions.orEmpty().filter { ed ->
            required == null || ed.sha256.equals(required, ignoreCase = true)
        }
        for (ed in editions) {
            val fid = ed.driveFileId ?: continue
            val known = driveFiles.get(permissionId, fid)
            if (known == null || !known.trashed) {
                return fid to ed.sha256.lowercase(Locale.ROOT)
            }
        }
        return null
    }

    /**
     * File IDs that live catalog editions publish per SHA-256. An indexed EPUB nobody
     * advertises may be an unfinished or cancelled upload, so it is never reused.
     */
    suspend fun advertisedEpubFileIds(permissionId: String): Map<String, Set<String>> =
        catalogAdvertisedEpubFileIds(recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_CATALOG))

    /**
     * A row with no file that no server owns: a `drive:` stub or a stub preserved
     * from a retired main server. Drive sets its availability.
     */
    private fun isFilelessUnownedRow(book: Book): Boolean =
        book.localUri == null &&
            book.remoteUuid == null &&
            book.browseServerId == null &&
            book.downloadState != DownloadState.DOWNLOADED

    private data class LocalBookMatchResult(
        val matchedUrls: List<String>,
        val rekeyedAliases: List<WorkAlias>,
        val conflictingShas: Set<String> = emptySet(),
    )

    private suspend fun findMatchingLocalBookUrls(
        permissionId: String,
        canonicalWorkKey: String,
        catalog: DriveCatalogBody,
        now: Long,
    ): LocalBookMatchResult {
        val editions = catalog.editions
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val matched = linkedSetOf<String>()
        val rekeyedAliases = mutableListOf<WorkAlias>()
        val conflictingShas = linkedSetOf<String>()
        val shas = editions.map { it.sha256.lowercase(Locale.ROOT) }.toSet()
        val remoteTa = WorkIdentifiers.titleAuthor(catalog.title, catalog.author)
        for (alias in identities.aliasesFor(peerId)) {
            if (!alias.usable) continue
            if (workIdentity.canonicalWorkKey(permissionId, alias.workId) != canonicalWorkKey) continue
            // A local book that only shares an identifier with an unrelated file stays separate,
            // and its conflicting alias is re-keyed to its own sha:<localSha> work key.
            if (
                !alias.bookUrl.startsWith(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX) &&
                !canonicalWorkKey.startsWith("sha:") &&
                remoteTa != null &&
                shas.isNotEmpty()
            ) {
                val book = books.getByUrl(alias.bookUrl)
                val localSha = identities.fingerprint(alias.bookUrl)?.sha256?.lowercase(Locale.ROOT)
                    ?: alias.editionSha?.lowercase(Locale.ROOT)
                val localTa = book?.let {
                    WorkIdentifiers.titleAuthor(it.stableTitle ?: it.title, it.identityAuthor ?: it.stableAuthor)
                }
                if (localSha != null && localSha !in shas && localTa != null && localTa != remoteTa) {
                    val fallbackKey = workIdentity.canonicalWorkKey(permissionId, "sha:$localSha")
                    rekeyedAliases.add(
                        alias.copy(
                            workId = fallbackKey,
                            seeded = false,
                            editionSha = localSha,
                            resolvedAt = now,
                        ),
                    )
                    conflictingShas.add(localSha)
                    continue
                }
            }
            matched.add(alias.bookUrl)
        }
        if (matched.isEmpty() && editions.isNotEmpty()) {
            for (book in books.allOnce()) {
                if (book.browseServerId != null) continue
                val fp = workIdentity.editionSha(book) ?: continue
                if (fp in shas) matched.add(book.url)
            }
        }
        return LocalBookMatchResult(
            matchedUrls = matched.toList(),
            rekeyedAliases = rekeyedAliases,
            conflictingShas = conflictingShas,
        )
    }

    private fun mergeCatalogBodies(
        existing: DriveCatalogBody?,
        incoming: DriveCatalogBody,
        incomingNewerHlc: Boolean,
    ): DriveCatalogBody {
        if (existing == null) return incoming
        val editions = linkedMapOf<String, DriveCatalogEdition>()
        existing.editions.forEach { editions[it.sha256.lowercase(Locale.ROOT)] = it }
        for (ed in incoming.editions) {
            val sha = ed.sha256.lowercase(Locale.ROOT)
            val prev = editions[sha]
            editions[sha] = if (prev == null) {
                ed
            } else {
                val incomingAvailabilityWins = when {
                    ed.availabilityHlc != prev.availabilityHlc -> (ed.availabilityHlc ?: "") > (prev.availabilityHlc ?: "")
                    else -> (ed.driveFileId ?: "") > (prev.driveFileId ?: "")
                }
                val chosen = if (incomingAvailabilityWins) ed else prev
                chosen.copy(sizeBytes = listOfNotNull(ed.sizeBytes, prev.sizeBytes).maxOrNull())
            }
        }
        val identifiers = (existing.identifiers + incoming.identifiers)
            .distinctBy { it.scheme to it.value }
            .sortedWith(compareBy({ it.scheme }, { it.value }))
        val addedAt = listOf(existing.addedAt, incoming.addedAt).filter { it > 0L }.minOrNull() ?: 0L
        // The archive choice is decided by its own HLC; record HLC only breaks ties between bodies without one.
        val incomingArchiveWins = when {
            incoming.archiveHlc == null && existing.archiveHlc == null -> incomingNewerHlc
            else -> (incoming.archiveHlc ?: "") > (existing.archiveHlc ?: "")
        }
        return DriveCatalogBody(
            workKey = incoming.workKey,
            title = if (incomingNewerHlc && incoming.title.isNotBlank()) incoming.title else existing.title.ifBlank { incoming.title },
            author = if (incomingNewerHlc) incoming.author ?: existing.author else existing.author ?: incoming.author,
            identifiers = identifiers,
            editions = editions.values.sortedBy { it.sha256 },
            fileSeriesName = if (incomingNewerHlc) incoming.fileSeriesName ?: existing.fileSeriesName else existing.fileSeriesName ?: incoming.fileSeriesName,
            fileSeriesIndex = if (incomingNewerHlc) incoming.fileSeriesIndex ?: existing.fileSeriesIndex else existing.fileSeriesIndex ?: incoming.fileSeriesIndex,
            addedAt = addedAt,
            archivedAt = if (incomingArchiveWins) incoming.archivedAt else existing.archivedAt,
            updatedAt = maxOf(existing.updatedAt, incoming.updatedAt),
            archiveHlc = if (incomingArchiveWins) incoming.archiveHlc else existing.archiveHlc,
        )
    }

    companion object {
        const val DRIVE_FILE_HREF_PREFIX = "drive-file:"

        fun downloadHrefFor(fileId: String): String = "$DRIVE_FILE_HREF_PREFIX$fileId"

        fun fileIdFromHref(downloadHref: String?): String? =
            downloadHref?.takeIf { it.startsWith(DRIVE_FILE_HREF_PREFIX) }
                ?.removePrefix(DRIVE_FILE_HREF_PREFIX)
                ?.takeIf { it.isNotBlank() }

        internal fun sanitizeEpubFileName(title: String): String {
            val cleaned = title.trim()
                .replace(Regex("""[\\/:*?"<>|]+"""), "_")
                .replace(Regex("""\s+"""), " ")
                .take(80)
                .ifBlank { "book" }
            return if (cleaned.lowercase(Locale.ROOT).endsWith(".epub")) cleaned else "$cleaned.epub"
        }
    }
}

/**
 * Takes a fileless Drive stub's baseline title, author and file series from the merged
 * catalog entry, leaving any reader override in place, so a stub never republishes
 * metadata the catalog has since replaced.
 */
private fun Book.withCatalogBaseline(catalog: DriveCatalogBody): Book = copy(
    fileTitle = catalog.title,
    fileAuthor = catalog.author,
    fileSeriesName = catalog.fileSeriesName,
    fileSeriesIndex = catalog.fileSeriesIndex,
    title = if (titleOverridden) title else catalog.title,
    author = if (authorOverridden) author else catalog.author,
    seriesName = if (seriesOverridden) seriesName else catalog.fileSeriesName,
    seriesIndex = if (seriesOverridden || indexOverridden) seriesIndex else catalog.fileSeriesIndex,
)

/** Durable `catalog` tombstone side-state kept in `drive_record_state.bodyJson`. */
private data class TombstonePayload(
    val pendingTrashFileIds: List<String> = emptyList(),
    val cleanupPending: Boolean = false,
    val cleanupBookUrl: String? = null,
    val cleanupWorkId: String? = null,
) {
    fun encode(): String? {
        val fields = linkedMapOf<String, Any?>()
        if (pendingTrashFileIds.isNotEmpty()) fields["pendingTrashFileIds"] = pendingTrashFileIds
        if (cleanupPending) {
            fields["cleanupPending"] = true
            cleanupBookUrl?.let { fields["cleanupBookUrl"] = it }
            cleanupWorkId?.let { fields["cleanupWorkId"] = it }
        }
        return if (fields.isEmpty()) null else DriveCanonicalJson.encode(fields)
    }

    companion object {
        fun parse(raw: String): TombstonePayload {
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return TombstonePayload()
            val arr = obj.optJSONArray("pendingTrashFileIds")
            val ids = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            return TombstonePayload(
                pendingTrashFileIds = ids,
                cleanupPending = obj.optBoolean("cleanupPending", false),
                cleanupBookUrl = obj.optString("cleanupBookUrl").takeIf { it.isNotEmpty() },
                cleanupWorkId = obj.optString("cleanupWorkId").takeIf { it.isNotEmpty() },
            )
        }
    }
}

/**
 * File IDs catalog editions publish per SHA-256 in [catalogStates].
 *
 * An indexed EPUB nobody advertises may be an unfinished or cancelled upload.
 */
internal fun catalogAdvertisedEpubFileIds(catalogStates: List<DriveRecordState>): Map<String, Set<String>> {
    val out = linkedMapOf<String, MutableSet<String>>()
    for (state in catalogStates) {
        if (state.versionOp == DriveRecordState.OP_DEL) continue
        val body = state.bodyJson?.let {
            runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(it)) }.getOrNull()
        } ?: continue
        for (ed in body.editions) {
            val fid = ed.driveFileId?.takeIf { it.isNotBlank() } ?: continue
            out.getOrPut(ed.sha256.lowercase(Locale.ROOT)) { linkedSetOf() }.add(fid)
        }
    }
    return out
}

/**
 * SHA-256 of the EPUBs other devices can download: live Drive [files] an advertised
 * catalog edition points at. A live file nothing advertises is left out, because
 * holding it would keep a book no other device can fetch out of the offer.
 */
internal fun heldEpubSha256s(files: List<DriveFile>, catalogStates: List<DriveRecordState>): Set<String> {
    val advertised = catalogAdvertisedEpubFileIds(catalogStates).values.flatMapTo(mutableSetOf()) { it }
    return files
        .asSequence()
        .filter { it.fileId in advertised }
        .mapNotNullTo(mutableSetOf()) { it.sha256?.lowercase(Locale.ROOT) }
}
