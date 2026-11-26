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
        aliasesByKey: Map<String, List<WorkAlias>>? = null,
    ): WorkAlias? {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val existing = identities.alias(book.url, peerId)
        if (existing != null && existing.usable) {
            val canonical = canonicalWorkKey(permissionId, existing.workId)
            val currentSha = if (book.openableUrl != null) {
                validFingerprint(book, computeFingerprintIfMissing)?.sha256?.lowercase(Locale.ROOT)
                    ?: return null
            } else {
                identities.fingerprint(book.url)?.sha256?.lowercase(Locale.ROOT) ?: existing.editionSha
            }
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
        if (existing?.confidence == WorkAlias.REJECTED) return null

        val now = clock()
        // Remote Drive catalog stub: key is encoded in its `drive:<workKey>` URL.
        if (book.url.startsWith(DRIVE_BOOK_URL_PREFIX)) {
            val rawKey = book.url.removePrefix(DRIVE_BOOK_URL_PREFIX).trim()
            if (rawKey.isEmpty()) return null
            val canonical = canonicalWorkKey(permissionId, rawKey)
            // Never silently re-link or resurrect a downloaded Drive book unlinked by a catalog tombstone.
            if (recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, canonical)?.versionOp == DriveRecordState.OP_DEL) {
                return null
            }
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
            val fpRow = validFingerprint(book, computeFingerprintIfMissing) ?: return null
            val metadataRows = metadata.forBook(book.url)
            val localSha = fpRow.sha256.lowercase(Locale.ROOT)
            val identifierKey = canonicalWorkKey(permissionId, deriveLocalWorkKey(book, metadataRows, fpRow))
            // A shared ISBN/dc identifier on an unrelated file is not proof of the same work.
            val canonical = if (contradictsSharedKey(permissionId, book, localSha, identifierKey, aliasesByKey)) {
                canonicalWorkKey(permissionId, "sha:$localSha")
            } else {
                identifierKey
            }
            // Never silently re-link or resurrect a local book unlinked by a catalog tombstone.
            if (recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, canonical)?.versionOp == DriveRecordState.OP_DEL) {
                return null
            }
            val alias = WorkAlias(
                bookUrl = book.url,
                peerId = peerId,
                workId = canonical,
                confidence = WorkAlias.HIGH,
                confirmed = true,
                seeded = false,
                editionSha = localSha,
                resolvedAt = now,
            )
            val persistedBook = if (book.id > 0L) book else book.copy(id = books.getByUrl(book.url)?.id ?: book.id)
            if (!identities.saveResolution(persistedBook, alias)) return null
            return identities.alias(book.url, peerId) ?: alias
        }

        // Fileless non-Drive book (e.g., preserved stub without existing alias).
        val metadataRows = metadata.forBook(book.url)
        val derived = DriveLibrarySync.preservedStubWorkKey(book, metadataRows, existing?.editionSha)
        val canonical = canonicalWorkKey(permissionId, derived)
        if (recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, canonical)?.versionOp == DriveRecordState.OP_DEL) {
            return null
        }
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

    private suspend fun validFingerprint(book: Book, compute: Boolean): BookFingerprintRow? {
        val cached = identities.fingerprint(book.url)
        if (cached != null && cached.fileModifiedAt == book.fileModifiedAt) return cached
        if (!compute || fingerprintStore?.of(book) == null) return null
        return identities.fingerprint(book.url)?.takeIf { it.fileModifiedAt == book.fileModifiedAt }
    }

    /**
     * True when [key] (derived from a shared identifier) already names a different
     * file whose title and author also differ from [book]: two unrelated EPUBs
     * carrying the same erroneous ISBN stay separate until the reader merges them.
     */
    internal suspend fun contradictsSharedKey(
        permissionId: String,
        book: Book,
        localSha: String,
        key: String,
        aliasesByKey: Map<String, List<WorkAlias>>? = null,
    ): Boolean {
        if (key.startsWith("sha:")) return false
        val localTa = bookTitleAuthor(book) ?: return false
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val merges = loadMergeMap(permissionId)
        for (alias in aliasesByKey?.get(key) ?: if (aliasesByKey == null) identities.aliasesFor(peerId) else emptyList()) {
            if (alias.bookUrl == book.url || !alias.usable) continue
            if (resolveCanonical(alias.workId, merges) != key) continue
            val other = books.getByUrl(alias.bookUrl) ?: continue
            val otherSha = identities.fingerprint(other.url)?.sha256?.lowercase(Locale.ROOT)
                ?: alias.editionSha?.lowercase(Locale.ROOT)
                ?: continue
            if (otherSha == localSha) continue
            val otherTa = bookTitleAuthor(other) ?: continue
            if (otherTa != localTa) return true
        }
        val state = recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, key)
        if (state == null || state.versionOp != DriveRecordState.OP_PUT) return false
        val body = state.bodyJson
            ?.let { runCatching { DriveWireCodec.decodeCatalogBody(JSONObject(it)) }.getOrNull() }
            ?: return false
        if (body.editions.isEmpty() || body.editions.any { it.sha256.lowercase(Locale.ROOT) == localSha }) return false
        val remoteTa = WorkIdentifiers.titleAuthor(body.title, body.author) ?: return false
        return remoteTa != localTa
    }

    private fun bookTitleAuthor(book: Book): String? = WorkIdentifiers.titleAuthor(
        book.stableTitle ?: book.title,
        book.identityAuthor ?: book.stableAuthor,
    )

    /**
     * Resolves deterministic Drive `work_alias` rows for all eligible local
     * books before pulling remote records, so different editions sharing a
     * strong identifier (e.g. ISBN) link to the existing local book cleanly.
     */
    suspend fun resolveAllLocalBooks(permissionId: String) {
        replayPendingAliasEffects(permissionId)
        val merges = loadMergeMap(permissionId)
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val aliasesByKey = identities.aliasesFor(peerId).groupBy { resolveCanonical(it.workId, merges) }
            .mapValues { it.value.toMutableList() }.toMutableMap()
        for (book in books.allOnce()) {
            if (book.browseServerId != null) continue
            if (book.url.startsWith(DRIVE_BOOK_URL_PREFIX)) continue
            val alias = resolveBook(permissionId, book, computeFingerprintIfMissing = false, aliasesByKey = aliasesByKey)
            if (alias != null) {
                val group = aliasesByKey.getOrPut(resolveCanonical(alias.workId, merges)) { mutableListOf() }
                group.removeAll { it.bookUrl == alias.bookUrl }
                group.add(alias)
            }
        }
    }

    /**
     * SHA-256 of [book]'s file, hashing it now when its metadata is checked but
     * its lazy fingerprint is still missing. Null for fileless or unchecked books.
     */
    suspend fun editionSha(book: Book): String? {
        identities.fingerprint(book.url)?.let { return it.sha256.lowercase(Locale.ROOT) }
        if (book.openableUrl == null || !book.fileMetadataChecked) return null
        fingerprintStore?.of(book)
        return identities.fingerprint(book.url)?.sha256?.lowercase(Locale.ROOT)
    }

    /**
     * Resolves [workKey] through all known `workAlias` `merge:` records to its
     * deterministic lexicographical minimum fixpoint.
     */
    suspend fun canonicalWorkKey(permissionId: String, workKey: String): String {
        val merges = loadMergeMap(permissionId)
        return resolveCanonical(workKey, merges)
    }

    /**
     * True when a book-scoped record for [rawWorkKey] names a work that has no local
     * book yet and whose catalog entry has not arrived. The caller keeps the domain
     * cursor so the record replays once the catalog creates the book.
     */
    suspend fun awaitsCatalogEntry(permissionId: String, rawWorkKey: String): Boolean {
        val merges = loadMergeMap(permissionId)
        val canonical = resolveCanonical(rawWorkKey, merges)
        return !hasLocalBook(permissionId, canonical, merges) &&
            catalogHead(permissionId, canonical, merges) == null
    }

    /**
     * True when [rawWorkKey] already resolves to a local `books` row, directly or through
     * an equivalent catalog key. Retained records for such a work can be materialized.
     */
    suspend fun hasLocalBookFor(permissionId: String, rawWorkKey: String): Boolean {
        val merges = loadMergeMap(permissionId)
        return hasLocalBook(permissionId, resolveCanonical(rawWorkKey, merges), merges)
    }

    private suspend fun hasLocalBook(
        permissionId: String,
        canonical: String,
        merges: Map<String, String>,
    ): Boolean {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        return identities.aliasesFor(peerId).any {
            it.usable && resolveCanonical(it.workId, merges) == canonical && books.getByUrl(it.bookUrl) != null
        } || books.getByUrl(driveBookUrl(canonical)) != null
    }

    /**
     * Re-points history retained under the synthetic `drive:<workKey>` key onto the book that
     * now represents the same work.
     *
     * A work whose catalog tombstone arrived before its records keeps its marks, agreements and
     * sessions under `drive:<workKey>`. When the book comes back bound to another URL — matched
     * to a local file or to a server copy by the usual edition policy rather than landing on the
     * synthetic one — that history would otherwise stay attached to a key no book answers to.
     * The rows keep their identifiers, so deduplication and later edits are unaffected.
     */
    suspend fun reattachHiddenHistory(permissionId: String) {
        val prefix = DRIVE_BOOK_URL_PREFIX
        val annotations = database.annotationDao()
        val agreements = database.annotationSyncDao()
        val sessions = database.readingSessionDao()
        val conflictBookKeys = (store.conflicts().openForAccount(permissionId) +
            store.conflicts().resolvingForAccount(permissionId))
            .mapNotNull { it.bookUrl }
            .filter { it.startsWith(prefix) }
        val hiddenKeys = (annotations.bookKeysWithPrefix(prefix) +
            agreements.bookKeysWithPrefix(prefix) +
            sessions.bookKeysWithPrefix(prefix) +
            conflictBookKeys).toSet()
        if (hiddenKeys.isEmpty()) return
        val moves = mutableListOf<Pair<String, String>>()
        database.withTransaction {
            for (hidden in hiddenKeys) {
                // A book still answering to this key keeps its history where it is.
                if (books.getByUrl(hidden) != null) continue
                val target = liveBookUrlFor(permissionId, hidden.removePrefix(prefix)) ?: continue
                if (target == hidden) continue
                annotations.moveBook(hidden, target)
                agreements.moveBook(hidden, target)
                sessions.moveBook(hidden, target)
                moves.add(hidden to target)
            }
        }
        if (moves.isNotEmpty()) {
            store.withTransaction {
                for ((hidden, target) in moves) {
                    store.conflicts().moveBookUrl(permissionId, hidden, target)
                }
            }
        }
    }

    internal suspend fun liveBookUrlFor(permissionId: String, rawWorkKey: String): String? {
        val merges = loadMergeMap(permissionId)
        val canonical = resolveCanonical(rawWorkKey, merges)
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val matched = identities.aliasesFor(peerId)
            .filter { it.usable && resolveCanonical(it.workId, merges) == canonical }
            .mapNotNull { books.getByUrl(it.bookUrl)?.url }
        return matched.firstOrNull { !it.startsWith(DRIVE_BOOK_URL_PREFIX) }
            ?: matched.firstOrNull()
            ?: books.getByUrl(driveBookUrl(canonical))?.url
    }

    suspend fun hasDeletedCatalogEntry(permissionId: String, rawWorkKey: String): Boolean {
        val merges = loadMergeMap(permissionId)
        val canonical = resolveCanonical(rawWorkKey, merges)
        val state = catalogHead(permissionId, canonical, merges) ?: return false
        return state.versionOp == DriveRecordState.OP_DEL ||
            state.versionOp == DriveRecordState.OP_RESOLVE &&
            store.resolutions().get(permissionId, DriveWireCodec.DOMAIN_CATALOG, state.recordKey, state.versionFp)
                ?.outcome == DriveResolution.OUTCOME_DELETE
    }

    internal suspend fun equivalentWorkKeys(permissionId: String, rawWorkKey: String): List<String> {
        val merges = loadMergeMap(permissionId)
        val canonical = resolveCanonical(rawWorkKey, merges)
        return equivalentWorkKeys(canonical, merges)
    }

    private fun equivalentWorkKeys(canonical: String, merges: Map<String, String>): List<String> =
        listOf(canonical) + merges.keys.filter { it != canonical && resolveCanonical(it, merges) == canonical }

    private suspend fun catalogHead(
        permissionId: String,
        canonical: String,
        merges: Map<String, String>,
    ): DriveRecordState? {
        val heads = equivalentWorkKeys(canonical, merges)
            .mapNotNull { recordStates.get(permissionId, DriveWireCodec.DOMAIN_CATALOG, it) }
        val ancestors = heads.flatMap { decodeStringArray(it.ancestorsJson) }.toSet()
        return heads.filterNot { it.versionFp in ancestors }
            .maxWithOrNull(compareBy({ it.lastHlc }, { it.versionFp }))
    }

    /** Returns true if the reader has already rejected matching [keyA] with [keyB]. */
    suspend fun isRejectedPair(permissionId: String, keyA: String, keyB: String): Boolean {
        if (keyA == keyB) return false
        val merges = loadMergeMap(permissionId)
        val canonA = resolveCanonical(keyA, merges)
        val canonB = resolveCanonical(keyB, merges)
        if (canonA == canonB) return false
        return recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS).any { state ->
            if (!state.recordKey.startsWith(REJECT_KEY_PREFIX) || state.versionOp != DriveRecordState.OP_PUT) return@any false
            val reject = state.bodyJson?.let {
                runCatching { DriveWireCodec.decodeWorkAliasBody(JSONObject(it)) }.getOrNull()
            } as? DriveWorkAliasBody.Reject ?: return@any false
            setOf(resolveCanonical(reject.a, merges), resolveCanonical(reject.b, merges)) == setOf(canonA, canonB)
        }
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
        replayPendingAliasEffects(permissionId)
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
            if (isCandidateRejected(permissionId, local.url, canonicalRemote)) continue
            val localAlias = allAliases[local.url]
            if (localAlias?.confidence == WorkAlias.REJECTED &&
                !isCandidateRejected(permissionId, local.url, resolveCanonical(localAlias.workId, merges))
            ) continue
            if (localAlias == null || localAlias.confidence == WorkAlias.REJECTED) {
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
            val canonicalLocal = resolveCanonical(localAlias.workId, merges)
            if (canonicalLocal == canonicalRemote) continue
            if (localAlias.confirmed && isRejectedPair(permissionId, canonicalLocal, canonicalRemote)) continue

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
        replayPendingAliasEffects(permissionId)
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val merges = loadMergeMap(permissionId)
        val allBooks = books.allOnce().associateBy { it.url }
        val aliasesByWork = identities.aliasesFor(peerId).groupBy { resolveCanonical(it.workId, merges) }
        val results = mutableListOf<DrivePendingWorkMatch>()

        // 1. Ambiguities where local book already has a high-confidence workKey.
        for (ambiguity in identities.ambiguitiesFor(peerId)) {
            val localBook = allBooks[ambiguity.bookUrl] ?: continue
            val localAlias = identities.alias(ambiguity.bookUrl, peerId)
            if (localAlias?.confidence == WorkAlias.REJECTED) continue
            // An unconfirmed candidate is one of the options, not what the book is: leaving
            // `localWorkKey` empty makes confirming a choice pin the alias instead of merging
            // two remote works on a guess.
            val localKey = localAlias?.confirmedWorkKey()?.let { resolveCanonical(it, merges) }.orEmpty()
            for (candidate in ambiguity.candidates) {
                val canonicalCandidate = resolveCanonical(candidate, merges)
                if (isCandidateRejected(permissionId, localBook.url, canonicalCandidate)) continue
                if (canonicalCandidate == localKey) continue
                if (localKey.isNotEmpty() && isRejectedPair(permissionId, localKey, canonicalCandidate)) continue
                if (results.any { it.bookUrl == localBook.url && it.candidateWorkKey == canonicalCandidate }) continue
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
            if (isCandidateRejected(permissionId, localBook.url, canonicalCandidate)) continue
            if (results.any { it.bookUrl == localBook.url && it.candidateWorkKey == canonicalCandidate }) continue
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
                val alias = identities.alias(bookUrl, peerId)
                if (alias != null && alias.workId != canonical) {
                    // Keep `confidence`: the reader chose between candidates for this book, which
                    // says nothing about two remote works being one, so no `merge:` is emitted.
                    identities.upsert(alias.copy(workId = canonical, confirmed = true, seeded = false))
                } else {
                    identities.confirm(bookUrl, peerId)
                }
                identities.clearAmbiguity(bookUrl, peerId)
            }
            return canonical
        }

        val merges = loadMergeMap(permissionId)
        val canonA = resolveCanonical(keyA, merges)
        val canonB = resolveCanonical(keyB, merges)
        if (canonA == canonB) {
            identities.confirm(bookUrl, peerId)
            identities.clearAmbiguity(bookUrl, peerId)
            return canonA
        }

        val to = minOf(canonA, canonB)
        val from = maxOf(canonA, canonB)
        val mergeKey = mergeRecordKey(from)
        val body = DriveWorkAliasBody.Merge(from = from, to = to, updatedAt = now)
        val bodyMap = DriveWireCodec.encodeWorkAliasBody(body)
        val bodyJson = DriveCanonicalJson.encode(bodyMap)
        val fp = DriveCanonicalJson.putFp(bodyMap, hlc)

        // The wire record and the recovery marker commit together, before any `liseur.db` change.
        val intent = PendingAliasEffect.Merge(bookUrl = bookUrl, from = from, to = to)
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
                    contentFp = DriveCanonicalJson.contentFp(bodyMap),
                    ancestorsJson = DriveCanonicalJson.encode(ancestors),
                    resRefsJson = existing?.resRefsJson ?: "[]",
                    lastHlc = hlc,
                    bodyJson = bodyJson,
                ),
            )
            // Merge supersedes any prior reject for this pair.
            recordStates.delete(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS, rejectRecordKey(to, from))
            queuePendingEffect(permissionId, intent, hlc)
        }
        replayPendingAliasEffects(permissionId)
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
        if (keyA.isBlank() || keyB.isBlank() || keyA == keyB) {
            if (keyB.isBlank()) return
            val candidate = canonicalWorkKey(permissionId, keyB)
            val rejectionKey = candidateRejectionKey(bookUrl, candidate)
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = DOMAIN_REJECTED_CANDIDATE,
                    recordKey = rejectionKey,
                    versionFp = rejectionKey,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = rejectionKey,
                    contentFp = rejectionKey,
                    lastHlc = hlc,
                ),
            )
            database.withTransaction {
                val ambiguity = identities.ambiguitiesFor(peerId).firstOrNull { it.bookUrl == bookUrl }
                val remaining = ambiguity?.candidates.orEmpty().filter { it != candidate }
                val alias = identities.alias(bookUrl, peerId)
                if (alias?.awaitingAnswer == true && alias.workId == candidate) {
                    if (remaining.isEmpty()) identities.reject(bookUrl, peerId)
                    else identities.upsert(alias.copy(workId = remaining.first()))
                }
                if (remaining.size > 1 && ambiguity != null) {
                    identities.upsert(ambiguity.copy(workIds = remaining.joinToString("\n")))
                } else identities.clearAmbiguity(bookUrl, peerId)
            }
            return
        }

        val low = minOf(keyA, keyB)
        val high = maxOf(keyA, keyB)
        val rejectKey = rejectRecordKey(low, high)
        val body = DriveWorkAliasBody.Reject(a = low, b = high, updatedAt = now)
        val bodyMap = DriveWireCodec.encodeWorkAliasBody(body)
        val bodyJson = DriveCanonicalJson.encode(bodyMap)
        val fp = DriveCanonicalJson.putFp(bodyMap, hlc)

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
                    contentFp = DriveCanonicalJson.contentFp(bodyMap),
                    ancestorsJson = DriveCanonicalJson.encode(ancestors),
                    resRefsJson = existing?.resRefsJson ?: "[]",
                    lastHlc = hlc,
                    bodyJson = bodyJson,
                ),
            )
            queuePendingEffect(permissionId, PendingAliasEffect.Reject(bookUrl, low, high), hlc)
        }
        replayPendingAliasEffects(permissionId)
    }

    private fun candidateRejectionKey(bookUrl: String, candidate: String): String =
        DriveCanonicalJson.sha256Hex(DriveCanonicalJson.encode(listOf(bookUrl, candidate)))

    private suspend fun isCandidateRejected(permissionId: String, bookUrl: String, candidate: String): Boolean {
        val merges = loadMergeMap(permissionId)
        val canonical = resolveCanonical(candidate, merges)
        val equivalent = merges.keys.filter { resolveCanonical(it, merges) == canonical } + canonical
        return equivalent.any {
            recordStates.get(permissionId, DOMAIN_REJECTED_CANDIDATE, candidateRejectionKey(bookUrl, it)) != null
        }
    }

    /**
     * Completes `liseur.db` effects of a confirm/reject whose wire record is already durable.
     * Each effect is idempotent, so a crash at any point is recovered by the next call.
     */
    suspend fun replayPendingAliasEffects(permissionId: String) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val pending = recordStates.forDomain(permissionId, DOMAIN_PENDING_ALIAS_EFFECT)
            .sortedWith(compareBy({ it.lastHlc }, { it.recordKey }))
        for (row in pending) {
            val effect = row.bodyJson?.let { PendingAliasEffect.decode(it) }
            if (effect != null) applyPendingEffect(peerId, effect, loadMergeMap(permissionId))
            recordStates.delete(permissionId, DOMAIN_PENDING_ALIAS_EFFECT, row.recordKey)
        }
    }

    private suspend fun applyPendingEffect(
        peerId: String,
        effect: PendingAliasEffect,
        merges: Map<String, String>,
    ) {
        database.withTransaction {
            when (effect) {
                is PendingAliasEffect.Merge -> {
                    val target = resolveCanonical(effect.to, merges)
                    for (alias in identities.aliasesFor(peerId)) {
                        val resolved = resolveCanonical(alias.workId, merges)
                        if (resolved == alias.workId && !(alias.bookUrl == effect.bookUrl && resolved == target)) continue
                        // Only the book the reader confirmed is promoted; other books keep their
                        // confidence, so a guess or rejection there is not turned into consent.
                        identities.upsert(
                            if (alias.bookUrl == effect.bookUrl && resolved == target) {
                                alias.copy(workId = resolved, confidence = WorkAlias.HIGH, confirmed = true, seeded = false)
                            } else {
                                alias.copy(workId = resolved)
                            },
                        )
                    }
                    identities.clearAmbiguity(effect.bookUrl, peerId)
                }
                is PendingAliasEffect.Reject -> {
                    val alias = identities.alias(effect.bookUrl, peerId)
                    if (alias != null && alias.awaitingAnswer) identities.reject(effect.bookUrl, peerId)
                    val localKey = alias?.confirmedWorkKey()?.let { resolveCanonical(it, merges) }
                    val rejected = if (localKey == resolveCanonical(effect.a, merges)) effect.b else effect.a
                    val ambiguity = identities.ambiguitiesFor(peerId).firstOrNull { it.bookUrl == effect.bookUrl }
                    val remaining = ambiguity?.candidates.orEmpty().filter {
                        resolveCanonical(it, merges) != resolveCanonical(rejected, merges)
                    }
                    if (alias?.awaitingAnswer != true && ambiguity != null && remaining.any { resolveCanonical(it, merges) != localKey }) {
                        identities.upsert(ambiguity.copy(workIds = remaining.joinToString("\n")))
                    } else identities.clearAmbiguity(effect.bookUrl, peerId)
                }
            }
        }
    }

    /** Runs inside the caller's `drive_sync.db` transaction. Never published: `publishedFp == versionFp`. */
    private suspend fun queuePendingEffect(permissionId: String, effect: PendingAliasEffect, hlc: String) {
        val bodyJson = DriveCanonicalJson.encode(effect.toMap())
        val fp = DriveCanonicalJson.sha256Hex(bodyJson)
        recordStates.upsert(
            DriveRecordState(
                permissionId = permissionId,
                domain = DOMAIN_PENDING_ALIAS_EFFECT,
                recordKey = fp,
                versionFp = fp,
                versionOp = DriveRecordState.OP_PUT,
                publishedFp = fp,
                contentFp = fp,
                lastHlc = hlc,
                bodyJson = bodyJson,
            ),
        )
    }

    private sealed interface PendingAliasEffect {
        val bookUrl: String

        data class Merge(override val bookUrl: String, val from: String, val to: String) : PendingAliasEffect
        data class Reject(override val bookUrl: String, val a: String, val b: String) : PendingAliasEffect

        fun toMap(): Map<String, Any?> = when (this) {
            is Merge -> mapOf("kind" to "merge", "bookUrl" to bookUrl, "from" to from, "to" to to)
            is Reject -> mapOf("kind" to "reject", "bookUrl" to bookUrl, "a" to a, "b" to b)
        }

        companion object {
            fun decode(json: String): PendingAliasEffect? = runCatching {
                val o = JSONObject(json)
                when (o.getString("kind")) {
                    "merge" -> Merge(o.getString("bookUrl"), o.getString("from"), o.getString("to"))
                    "reject" -> Reject(o.getString("bookUrl"), o.getString("a"), o.getString("b"))
                    else -> null
                }
            }.getOrNull()
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
        store.withTransaction {
            for (record in records) {
                if (record.d != DriveWireCodec.DOMAIN_WORK_ALIAS) continue
                val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS, record.k) ?: continue
                if (existing.versionFp == record.fp) {
                    recordStates.upsert(existing.copy(publishedFp = record.fp))
                }
            }
        }
    }

    /** Applies an incoming remote `workAlias` record idempotently. */
    suspend fun applyRemoteRecord(
        permissionId: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
        nextHlc: suspend () -> String = { record.hlc },
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
                val existingWins = existing != null && existingTo != null && chosenTo != parsed.to
                val chosenBody = if (existingWins) existing!!.bodyJson!! else bodyJson.toString()
                val chosenFp = if (existingWins) existing!!.versionFp else record.fp

                // Transaction 1 (`liseur.db`): propagate merge across aliases & clear matching ambiguities.
                val mergesBefore = loadMergeMap(permissionId)
                val merges = mergesBefore.toMutableMap()
                merges[parsed.from] = chosenTo
                // Concurrent merges of one source to different targets confirm that both targets
                // are the same work too. Link their canonical destinations (larger to smaller):
                // a canonical key has no outgoing edge, so no existing edge is ever replaced.
                val derivedMerge = existingTo?.to?.takeIf { it != parsed.to }?.let { otherTo ->
                    val a = resolveCanonical(otherTo, mergesBefore)
                    val b = resolveCanonical(parsed.to, mergesBefore)
                    if (a == b) return@let null
                    DriveWorkAliasBody.Merge(from = maxOf(a, b), to = minOf(a, b), updatedAt = clock())
                }
                derivedMerge?.let { merges[it.from] = it.to }
                val derivedHlc = derivedMerge?.let { nextHlc() }
                val fixpoint = resolveCanonical(parsed.from, merges)
                database.withTransaction {
                    for (alias in identities.aliasesFor(peerId)) {
                        val resolved = resolveCanonical(alias.workId, merges)
                        if (resolved != alias.workId) {
                            // A remote merge renames the work this alias points at; it is no
                            // evidence that an unconfirmed local candidate was right.
                            identities.upsert(alias.copy(workId = resolved))
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
                    if (existingWins) {
                        // The incoming version lost: keep the stored winner's body, fingerprint,
                        // ancestry and publication status coherent.
                        recordStates.upsert(existing!!)
                    } else recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
                            recordKey = mergeKey,
                            versionFp = chosenFp,
                            versionOp = DriveRecordState.OP_PUT,
                            publishedFp = chosenFp,
                            contentFp = DriveCanonicalJson.contentFp(bodyJson),
                            originDeviceId = remoteDeviceId,
                            originSeq = remoteSeq,
                            ancestorsJson = DriveCanonicalJson.encode(anc),
                            resRefsJson = DriveCanonicalJson.encode(record.resRefs.distinct().sorted()),
                            lastHlc = record.hlc,
                            bodyJson = chosenBody,
                        ),
                    )
                    recordStates.delete(
                        permissionId,
                        DriveWireCodec.DOMAIN_WORK_ALIAS,
                        rejectRecordKey(minOf(parsed.from, fixpoint), maxOf(parsed.from, fixpoint)),
                    )
                    // Same transaction as the winning record, so a crash cannot keep one without the other.
                    if (derivedMerge != null && derivedHlc != null) queueDerivedMerge(permissionId, derivedMerge, derivedHlc)
                }
            }
            is DriveWorkAliasBody.Reject -> {
                // `merge` supersedes `reject`: if already merged to the same fixpoint, ignore reject.
                val merges = loadMergeMap(permissionId)
                if (resolveCanonical(parsed.a, merges) == resolveCanonical(parsed.b, merges)) return
                val rejectKey = rejectRecordKey(parsed.a, parsed.b)
                val canonicalBody = DriveCanonicalJson.encode(DriveWireCodec.encodeWorkAliasBody(parsed))

                database.withTransaction {
                    for (amb in identities.ambiguitiesFor(peerId)) {
                        val cands = amb.candidates.map { resolveCanonical(it, merges) }.toSet()
                        val confirmed = identities.alias(amb.bookUrl, peerId)?.confirmedWorkKey()
                            ?.let { resolveCanonical(it, merges) }
                        if (confirmed != null && confirmed in cands && parsed.a in cands && parsed.b in cands && cands.size == 2) {
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
                            contentFp = DriveCanonicalJson.contentFp(bodyJson),
                            originDeviceId = remoteDeviceId,
                            originSeq = remoteSeq,
                            ancestorsJson = DriveCanonicalJson.encode(record.anc.take(DriveWireCodec.MAX_ANCESTORS)),
                            resRefsJson = DriveCanonicalJson.encode(record.resRefs.distinct().sorted()),
                            lastHlc = record.hlc,
                            bodyJson = canonicalBody,
                        ),
                    )
                }
            }
        }
    }

    /** Persists a locally derived `merge:` record, unpublished, so the next delta carries it. Runs inside the caller's transaction. */
    private suspend fun queueDerivedMerge(permissionId: String, merge: DriveWorkAliasBody.Merge, hlc: String) {
        val bodyMap = DriveWireCodec.encodeWorkAliasBody(merge)
        val fp = DriveCanonicalJson.putFp(bodyMap, hlc)
        val key = mergeRecordKey(merge.from)
        store.withTransaction {
            val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS, key)
            val existingTo = existing?.bodyJson?.let {
                runCatching { DriveWireCodec.decodeWorkAliasBody(JSONObject(it)) }.getOrNull()
            } as? DriveWorkAliasBody.Merge
            if (existingTo != null && existingTo.to <= merge.to) return@withTransaction
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
                    recordKey = key,
                    versionFp = fp,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = null,
                    contentFp = DriveCanonicalJson.contentFp(bodyMap),
                    ancestorsJson = DriveCanonicalJson.encode(buildAncestors(existing)),
                    resRefsJson = existing?.resRefsJson ?: "[]",
                    lastHlc = hlc,
                    bodyJson = DriveCanonicalJson.encode(bodyMap),
                ),
            )
            recordStates.delete(permissionId, DriveWireCodec.DOMAIN_WORK_ALIAS, rejectRecordKey(merge.to, merge.from))
        }
    }

    /** The work key this alias is pinned to, unlike a candidate still awaiting the reader's answer. */
    private fun WorkAlias.confirmedWorkKey(): String? =
        workId.takeIf { confirmed && confidence != WorkAlias.REJECTED }

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

        /** Local-only recovery markers in `drive_record_state`; not a wire domain. */
        const val DOMAIN_PENDING_ALIAS_EFFECT = "_work_alias_pending"
        const val DOMAIN_REJECTED_CANDIDATE = "_work_alias_rejected_candidate"

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
                    title = book.fileTitle?.takeIf { it.isNotBlank() } ?: book.title,
                    author = book.identityAuthor ?: book.stableAuthor,
                )?.let(::add)
                val fileMeta = metadataRows.firstOrNull { it.source == MetadataSource.FILE.key }
                fileMeta?.toSnapshot()?.identifiers?.forEach { identifier ->
                    if (identifier.value.isNotBlank() &&
                        identifier.value.trim().lowercase(Locale.ROOT) !in USELESS_IDENTIFIERS
                    ) {
                        add(DriveLibrarySync.schemeQualified(identifier.scheme, identifier.value))
                    }
                }
            }.map { DriveLibrarySync.schemeQualified(null, it).lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() && it !in USELESS_IDENTIFIERS }
            for (candidate in candidates) {
                Isbn.from(candidate)?.let { return "isbn:${it.isbn13}" }
            }
            candidates.firstOrNull()?.let { return "dc:${DriveCanonicalJson.sha256Hex(it)}" }
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
