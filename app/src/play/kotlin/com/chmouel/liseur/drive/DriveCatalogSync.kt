package com.chmouel.liseur.drive

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.domain.MetadataSource
import java.util.Locale
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val books get() = database.bookDao()
    private val metadata get() = database.bookMetadataDao()
    private val identities get() = database.workIdentityDao()
    private val recordStates get() = store.recordStates()
    private val uploads get() = store.uploads()
    private val driveFiles get() = store.files()

    /**
     * Collects dirty `catalog` records for every eligible library book whose
     * canonical catalog representation changed since last publish.
     */
    suspend fun collectDirtyRecords(
        permissionId: String,
        nextHlc: suspend () -> String,
    ): List<DriveWireRecord> {
        val eligibleBooks = books.allOnce().filter { it.browseServerId == null }
        val uploadedBySha = uploads.forAccount(permissionId)
            .filter {
                it.state == DriveUpload.STATE_UPLOADED &&
                    !it.fileId.isNullOrBlank() &&
                    driveFiles.get(permissionId, it.fileId)?.trashed != true
            }
            .associateBy { it.sha256.lowercase(Locale.ROOT) }
        val remoteEpubsBySha = driveFiles.byKind(permissionId, DriveFile.KIND_EPUB)
            .filter { !it.trashed && !it.sha256.isNullOrBlank() }
            .groupBy { it.sha256!!.lowercase(Locale.ROOT) }

        val grouped = linkedMapOf<String, MutableList<Pair<Book, WorkAlias>>>()
        for (book in eligibleBooks) {
            val alias = workIdentity.resolveBook(permissionId, book) ?: continue
            if (!alias.usable) continue
            val canonical = workIdentity.canonicalWorkKey(permissionId, alias.workId)
            grouped.getOrPut(canonical) { mutableListOf() }.add(book to alias)
        }

        val out = mutableListOf<DriveWireRecord>()
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
                val knownFile = remoteEpubsBySha[sha]?.firstOrNull()
                val trashedFile = existingEd?.driveFileId?.let { fid ->
                    driveFiles.get(permissionId, fid)?.trashed == true
                } == true

                val resolvedFileId = when {
                    upload?.fileId != null -> upload.fileId
                    knownFile != null -> knownFile.fileId
                    trashedFile -> null
                    else -> existingEd?.driveFileId
                }
                val resolvedUploadedAt = when {
                    resolvedFileId == null -> null
                    upload?.fileId == resolvedFileId -> upload.updatedAt
                    else -> existingEd?.uploadedAt ?: clock()
                }
                val resolvedFileName = when {
                    resolvedFileId == null -> existingEd?.fileName
                    knownFile != null -> knownFile.name
                    else -> existingEd?.fileName ?: sanitizeEpubFileName(primaryBook.stableTitle ?: primaryBook.title)
                }
                editionsBySha[sha] = DriveCatalogEdition(
                    sha256 = sha,
                    sizeBytes = fpRow?.fileSize ?: book.sizeBytes ?: upload?.sizeBytes ?: existingEd?.sizeBytes,
                    driveFileId = resolvedFileId,
                    fileName = resolvedFileName,
                    uploadedAt = resolvedUploadedAt,
                )
            }

            // Also ensure any trashed Drive EPUB clears driveFileId cleanly.
            for ((sha, ed) in editionsBySha.toMap()) {
                val fid = ed.driveFileId ?: continue
                if (driveFiles.get(permissionId, fid)?.trashed == true) {
                    editionsBySha[sha] = ed.copy(driveFileId = null, uploadedAt = null)
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
            if (existingState != null && existingState.publishedFp == candidateFp) {
                continue
            }

            val finalBody = if (existingBody != null && existingState?.versionFp == candidateFp) {
                candidateBody
            } else {
                candidateBody.copy(updatedAt = clock())
            }
            val finalMap = DriveWireCodec.encodeCatalogBody(finalBody)
            val finalFp = DriveCanonicalJson.contentFp(finalMap)
            val hlc = nextHlc()
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
                    contentFp = finalFp,
                    originDeviceId = existingState?.originDeviceId,
                    originSeq = existingState?.originSeq,
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
    ) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        if (record.op == DriveRecordState.OP_DEL) {
            val canonical = workIdentity.canonicalWorkKey(permissionId, record.k)
            val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, canonical)
            if (existing != null && record.hlc <= existing.lastHlc) return
            // Unlink local copies; remove remote-only Drive stub without touching local files.
            database.withTransaction {
                val matchingAliases = identities.aliasesFor(peerId).filter {
                    workIdentity.canonicalWorkKey(permissionId, it.workId) == canonical
                }
                for (alias in matchingAliases) {
                    val book = books.getByUrl(alias.bookUrl) ?: continue
                    if (book.url.startsWith(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX) && book.localUri == null) {
                        books.deleteByUrls(listOf(book.url))
                    }
                    identities.deleteAliasIfStale(book.url, peerId, alias.workId)
                }
            }
            store.withTransaction {
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
                        bodyJson = null,
                    ),
                )
            }
            return
        }

        val bodyJson = record.body ?: return
        val incoming = DriveWireCodec.decodeCatalogBody(bodyJson) ?: return
        val canonicalKey = workIdentity.canonicalWorkKey(permissionId, incoming.workKey)
        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, canonicalKey)
        if (existingState?.versionOp == DriveRecordState.OP_DEL && record.hlc <= existingState.lastHlc) return
        val existingBody = existingState?.bodyJson?.let {
            runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(it)) }.getOrNull()
        }
        val incomingNewerHlc = existingState == null || record.hlc > existingState.lastHlc

        val merged = mergeCatalogBodies(
            existing = existingBody,
            incoming = incoming.copy(workKey = canonicalKey),
            incomingNewerHlc = incomingNewerHlc,
        )
        val mergedMap = DriveWireCodec.encodeCatalogBody(merged)
        val mergedJson = DriveCanonicalJson.encode(mergedMap)
        val mergedFp = DriveCanonicalJson.contentFp(mergedMap)

        val uploadedEdition = merged.editions.firstOrNull { ed ->
            val fid = ed.driveFileId
            !fid.isNullOrBlank() && driveFiles.get(permissionId, fid)?.trashed != true
        }
        val primaryEdition = uploadedEdition ?: merged.editions.firstOrNull()
        val downloadHref = uploadedEdition?.driveFileId?.let(::downloadHrefFor)

        // Check if any local book already matches by workKey or exact edition SHA-256.
        val matchingBookUrls = findMatchingLocalBookUrls(permissionId, canonicalKey, merged.editions)
        val now = clock()

        // Transaction 1 (`liseur.db`): insert stub or update existing book metadata.
        database.withTransaction {
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
                        editionSha = primaryEdition?.sha256,
                        resolvedAt = now,
                    ),
                )
            } else {
                for (url in matchingBookUrls) {
                    val book = books.getByUrl(url) ?: continue
                    val existingAlias = identities.alias(url, peerId)
                    if (existingAlias == null) {
                        val localSha = identities.fingerprint(url)?.sha256?.lowercase(Locale.ROOT)
                            ?: primaryEdition?.sha256
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
                    val localArchiveUnpublished = existingBody != null &&
                        (book.archivedAt != null) != (existingBody.archivedAt != null)
                    val archivedAt = if (localArchiveUnpublished) book.archivedAt else merged.archivedAt
                    if (book.archivedAt != archivedAt) {
                        books.setArchived(url, archivedAt)
                    }
                    if (book.openableUrl == null) {
                        // A fileless stub has no file of its own: the catalog is its baseline metadata.
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
            val anc = (listOfNotNull(existingState?.versionFp) +
                DriveWorkIdentity.decodeStringArray(existingState?.ancestorsJson) + record.anc)
                .distinct()
                .filter { it != mergedFp }
                .take(DriveWireCodec.MAX_ANCESTORS)
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = DriveWireCodec.DOMAIN_CATALOG,
                    recordKey = canonicalKey,
                    versionFp = mergedFp,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = if (mergedFp == record.fp) record.fp else existingState?.publishedFp,
                    contentFp = mergedFp,
                    originDeviceId = remoteDeviceId,
                    originSeq = remoteSeq,
                    ancestorsJson = DriveCanonicalJson.encode(anc),
                    resRefsJson = DriveCanonicalJson.encode(record.resRefs.distinct().sorted()),
                    lastHlc = maxOf(existingState?.lastHlc ?: "", record.hlc),
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
     * Marks a Drive-side EPUB file unavailable when `changes.list` or a full
     * bootstrap rescan reports it removed or trashed in Google Drive, retaining
     * the catalog entry and all local reading history while invalidating any
     * matching `UPLOADED` queue entry so the reader may explicitly re-select it.
     */
    suspend fun handleDriveEpubRemoved(permissionId: String, fileId: String) {
        val removedHref = downloadHrefFor(fileId)
        val statesToUpdate = mutableListOf<DriveRecordState>()
        val stubUpdates = linkedMapOf<String, String?>()

        store.withTransaction {
            driveFiles.setTrashed(permissionId, fileId, true)
            // Invalidate matching UPLOADED queue entry without auto-reuploading so
            // queueSelectedBooks() lets the reader explicitly re-select and re-upload it.
            uploads.deleteUploadedByFileId(permissionId, fileId)
            val states = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_CATALOG)
            for (state in states) {
                val bodyJson = state.bodyJson ?: continue
                val body = runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(bodyJson)) }.getOrNull() ?: continue
                if (body.editions.none { it.driveFileId == fileId }) continue
                val updatedEditions = body.editions.map { ed ->
                    if (ed.driveFileId == fileId) ed.copy(driveFileId = null, uploadedAt = null) else ed
                }
                val updatedBody = body.copy(editions = updatedEditions, updatedAt = clock())
                val map = DriveWireCodec.encodeCatalogBody(updatedBody)
                val fp = DriveCanonicalJson.contentFp(map)
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
                        contentFp = fp,
                        bodyJson = DriveCanonicalJson.encode(map),
                    ),
                )
            }
            for (updatedState in statesToUpdate) {
                recordStates.upsert(updatedState)
            }
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
                    books.upsert(book.copy(downloadHref = null))
                }
            }
        }
    }

    /** Finds the uploaded Drive file ID and expected SHA-256 for a Drive book download. */
    suspend fun resolveDownloadSource(
        permissionId: String,
        book: Book,
    ): Pair<String, String>? {
        val workKey = workIdentity.resolveBook(permissionId, book, computeFingerprintIfMissing = false)
            ?.workId
            ?.let { workIdentity.canonicalWorkKey(permissionId, it) }
            ?: book.url.takeIf { it.startsWith(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX) }
                ?.removePrefix(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX)
            ?: return null

        val catalogState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, workKey)
        val body = catalogState?.bodyJson?.let {
            runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(it)) }.getOrNull()
        }
        val editions = body?.editions.orEmpty()
        for (ed in editions) {
            val fid = ed.driveFileId ?: continue
            val known = driveFiles.get(permissionId, fid)
            if (known == null || !known.trashed) {
                return fid to ed.sha256.lowercase(Locale.ROOT)
            }
        }
        // Fallback: match any non-trashed drive_file by edition SHA-256.
        for (ed in editions) {
            val file = driveFiles.byKindAndSha256(permissionId, DriveFile.KIND_EPUB, ed.sha256.lowercase(Locale.ROOT))
                .firstOrNull()
            if (file != null) return file.fileId to ed.sha256.lowercase(Locale.ROOT)
        }
        return null
    }

    private suspend fun findMatchingLocalBookUrls(
        permissionId: String,
        canonicalWorkKey: String,
        editions: List<DriveCatalogEdition>,
    ): List<String> {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val matched = linkedSetOf<String>()
        for (alias in identities.aliasesFor(peerId)) {
            if (!alias.usable) continue
            if (workIdentity.canonicalWorkKey(permissionId, alias.workId) == canonicalWorkKey) {
                matched.add(alias.bookUrl)
            }
        }
        if (matched.isEmpty() && editions.isNotEmpty()) {
            val shas = editions.map { it.sha256.lowercase(Locale.ROOT) }.toSet()
            for (book in books.allOnce()) {
                if (book.browseServerId != null) continue
                val fp = workIdentity.editionSha(book) ?: continue
                if (fp in shas) matched.add(book.url)
            }
        }
        return matched.toList()
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
            } else if (incomingNewerHlc) {
                DriveCatalogEdition(
                    sha256 = sha,
                    sizeBytes = ed.sizeBytes ?: prev.sizeBytes,
                    driveFileId = ed.driveFileId,
                    fileName = ed.fileName ?: prev.fileName,
                    uploadedAt = if (ed.driveFileId != null) ed.uploadedAt ?: prev.uploadedAt else null,
                )
            } else {
                DriveCatalogEdition(
                    sha256 = sha,
                    sizeBytes = prev.sizeBytes ?: ed.sizeBytes,
                    driveFileId = prev.driveFileId,
                    fileName = prev.fileName ?: ed.fileName,
                    uploadedAt = if (prev.driveFileId != null) prev.uploadedAt ?: ed.uploadedAt else null,
                )
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
