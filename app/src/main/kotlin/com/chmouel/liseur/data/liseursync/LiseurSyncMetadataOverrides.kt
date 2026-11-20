package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.BookMetadataOverrideDao
import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.db.MetadataOverridePoll
import com.chmouel.liseur.data.db.MetadataOverrideRevision
import com.chmouel.liseur.data.db.MetadataOverrideSync
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.library.BookMetadataOverrideRepository
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LiseurSyncMetadataOverrides(
    private val servers: RemoteServerDao,
    private val identities: WorkIdentityDao,
    private val books: BookDao,
    private val overrides: BookMetadataOverrideDao,
    private val repository: BookMetadataOverrideRepository,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    private val writerId: suspend () -> String,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
    private val now: () -> Long = System::currentTimeMillis,
    private val maxWorksPerPass: Int = 20,
) {
    private val unsupported = mutableSetOf<String>()

    private data class Target(val alias: WorkAlias, val book: Book)

    private data class RemoteCover(val sha256: String?, val updatedAt: Long)

    suspend fun sync(server: RemoteServer, book: String?): Boolean {
        if (server.baseUrl in unsupported) return false
        val peer = server.accountKey
        val states = overrides.states(peer).groupBy { it.bookUrl }
        val revisionsByBook = if (book == null) overrides.allRevisions().groupBy { it.bookUrl } else emptyMap()
        val aliases = identities.aliasesFor(peer).filter { it.usable }
        val requestedWorks = aliases.filter { it.bookUrl == book }.mapTo(mutableSetOf()) { it.workId }

        var passAt = 0L
        inTransaction {
            if (!stillOurs(server)) return@inTransaction
            val previous = overrides.poll(peer)
            passAt = previous?.startedAt ?: maxOf(1L, now(), overrides.latestPoll(peer) + 1L)
            if (book == null && previous == null) overrides.upsertPoll(MetadataOverridePoll(peer, passAt))
        }
        if (passAt == 0L) return false

        val targets = mutableListOf<Target>()
        for (alias in aliases.filter { book == null || it.bookUrl == book || it.workId in requestedWorks }) {
            books.getByUrl(alias.bookUrl)?.let { targets += Target(alias, it) }
        }

        val eligible = targets.groupBy { it.alias.workId }.values.filter { copies ->
            if (book != null) {
                copies.any { it.alias.bookUrl == book }
            } else {
                copies.any { target ->
                    val saved = states[target.alias.bookUrl].orEmpty()
                    val marker = saved.find { it.field == MetadataOverrideFields.TITLE }
                    marker == null || marker.workId != target.alias.workId || marker.polledAt < passAt ||
                        saved.any { it.sentJson != null } ||
                        revisionsByBook[target.alias.bookUrl].orEmpty().any { rev ->
                            rev.owed(saved.find { it.field == rev.field })
                        }
                }
            }
        }.sortedWith(
            compareBy<List<Target>> { copies ->
                !copies.any { target ->
                    val saved = states[target.alias.bookUrl].orEmpty()
                    saved.any { it.sentJson != null } ||
                        revisionsByBook[target.alias.bookUrl].orEmpty().any { rev ->
                            rev.owed(saved.find { it.field == rev.field })
                        }
                }
            }.thenBy { copies ->
                copies.minOf { target ->
                    states[target.alias.bookUrl].orEmpty()
                        .find { it.field == MetadataOverrideFields.TITLE }?.polledAt ?: 0L
                }
            },
        )

        val more = book == null && eligible.size > maxWorksPerPass
        for (copies in if (book == null) eligible.take(maxWorksPerPass) else eligible) {
            if (!stillOurs(server)) return false
            try {
                syncWork(server, copies, passAt)
            } catch (e: LiseurSyncRejection) {
                if (e.code == LiseurSyncHttp.NOT_FOUND &&
                    e.rawBody.trim().let { it.isEmpty() || it == "404 page not found" }
                ) {
                    unsupported += server.baseUrl
                    return false
                }
                if (e.code == LiseurSyncHttp.NOT_FOUND) {
                    inTransaction {
                        for (target in copies) {
                            if (stillOurs(server)) {
                                identities.deleteAliasIfStale(target.alias.bookUrl, peer, target.alias.workId)
                                overrides.invalidateWork(peer, target.alias.bookUrl, target.alias.workId)
                            }
                        }
                    }
                    continue
                }
                if (e.code == LiseurSyncHttp.CONFLICT) {
                    continue
                }
                throw e
            }
        }
        if (book == null && !more) {
            inTransaction {
                if (stillOurs(server) && overrides.poll(peer)?.startedAt == passAt) {
                    overrides.deletePoll(peer)
                }
            }
        }
        return more
    }

    private suspend fun syncWork(server: RemoteServer, copies: List<Target>, passAt: Long) {
        val peer = server.accountKey
        val workId = copies.first().alias.workId
        val credentials = server.credentials ?: return
        val metadataUrl = "${server.baseUrl.trimEnd('/')}/v1/works/$workId/metadata"
        val coverUrl = "${server.baseUrl.trimEnd('/')}/v1/works/$workId/cover"

        inTransaction {
            if (!stillOurs(server)) return@inTransaction
            for (target in copies) {
                for (field in MetadataOverrideFields.ALL_FIELDS) {
                    val prior = overrides.state(peer, target.alias.bookUrl, field)
                    if (prior?.workId != workId || prior.sentJson == null) {
                        overrides.upsertState(
                            prior?.copy(
                                workId = workId,
                                polledAt = if (prior.workId == workId) prior.polledAt else 0L,
                            ) ?: MetadataOverrideSync(peer, target.alias.bookUrl, field, workId),
                        )
                    }
                }
            }
        }

        // 1. Push owed text fields (replaying persisted sent_json first).
        val writer = writerId()
        for (field in MetadataOverrideFields.TEXT_FIELDS) {
            val pending = copies.mapNotNull { target ->
                overrides.state(peer, target.alias.bookUrl, field)
                    ?.takeIf { it.sentJson != null }
                    ?.let { target to it }
            }
            for ((target, state) in pending) {
                if (!stillOurs(server)) return
                sendText(server, metadataUrl, credentials, target, state)
            }

            var chosen: Pair<Target, MetadataOverrideSync>? = null
            inTransaction {
                if (!stillOurs(server)) return@inTransaction
                val owed = copies.mapNotNull { target ->
                    val rev = overrides.revision(target.alias.bookUrl, field) ?: return@mapNotNull null
                    val st = overrides.state(peer, target.alias.bookUrl, field)
                    if (rev.owed(st)) Triple(target, rev, st) else null
                }
                val winner = owed.maxByOrNull { it.second.updatedAt } ?: return@inTransaction
                if (!stillBound(winner.first)) return@inTransaction
                for ((target, rev, _) in owed) {
                    if (target.alias.bookUrl != winner.first.alias.bookUrl) {
                        overrides.upsertRevision(rev.copy(retiredRevision = rev.revision))
                    }
                }
                val currentBook = books.getByUrl(winner.first.alias.bookUrl) ?: return@inTransaction
                val rev = winner.second
                val base = winner.third ?: MetadataOverrideSync(peer, currentBook.url, field, workId)
                val payload = buildTextPayload(writer, workId, field, rev.revision, currentBook)
                val prepared = base.copy(
                    workId = workId,
                    sentJson = payload,
                    sentRevision = rev.revision,
                )
                overrides.upsertState(prepared)
                chosen = winner.first to prepared
            }
            chosen?.let { (target, state) ->
                if (!stillOurs(server)) return
                sendText(server, metadataUrl, credentials, target, state)
            }
        }

        // 2. Push owed cover change (PUT JPEG or DELETE).
        var coverPushTarget: Triple<Target, Book, Long>? = null
        inTransaction {
            if (!stillOurs(server)) return@inTransaction
            val owed = copies.mapNotNull { target ->
                val rev = overrides.revision(target.alias.bookUrl, MetadataOverrideFields.COVER)
                    ?: return@mapNotNull null
                val st = overrides.state(peer, target.alias.bookUrl, MetadataOverrideFields.COVER)
                if (rev.owed(st)) Triple(target, rev, st) else null
            }
            // Taking the cover away is this device's own answer, and the
            // server's DELETE means "back to the original cover", so it is
            // never the way to say it. Settle those books on their own, and
            // before the election: a removal on one copy must not retire
            // the custom cover another copy still owes.
            val pushable = owed.filter { (target, rev, st) ->
                val book = books.getByUrl(target.alias.bookUrl)
                if (book != null && book.coverOverridden && book.userCoverPath == null) {
                    settleCoverRemoval(peer, workId, target, rev, st)
                    false
                } else {
                    true
                }
            }
            val winner = pushable.maxByOrNull { it.second.updatedAt } ?: return@inTransaction
            for ((target, rev, _) in pushable) {
                if (target.alias.bookUrl != winner.first.alias.bookUrl) {
                    overrides.upsertRevision(rev.copy(retiredRevision = rev.revision))
                }
            }
            val currentBook = books.getByUrl(winner.first.alias.bookUrl) ?: return@inTransaction
            if (!stillBound(winner.first)) return@inTransaction
            coverPushTarget = Triple(winner.first, currentBook, winner.second.revision)
        }
        coverPushTarget?.let { (target, book, rev) ->
            if (!stillOurs(server)) return
            pushCover(server, peer, workId, coverUrl, credentials, target, book, rev)
        }

        // 3. Pull remote metadata & cover state.
        if (!stillOurs(server)) return
        val response = http.get(
            metadataUrl,
            credentials,
            expected = setOf(LiseurSyncHttp.NOT_FOUND),
        )
        val remoteFields = parseRemoteFields(response)
        val remoteCover = parseRemoteCover(response)

        // Download cover bytes outside transaction if needed.
        var downloadedCover: Pair<String, ByteArray>? = null
        if (remoteCover?.sha256 != null) {
            val needsDownload = copies.any { target ->
                val current = books.getByUrl(target.alias.bookUrl)
                val rev = overrides.revision(target.alias.bookUrl, MetadataOverrideFields.COVER)
                val st = overrides.state(peer, target.alias.bookUrl, MetadataOverrideFields.COVER)
                val isOwed = rev?.owed(st) == true
                // A cover the reader deliberately removed here is not a
                // cover to fetch: the pull would only write it and then
                // throw it away until the next pass fetched it again.
                val removedHere = current != null && current.coverOverridden && current.userCoverPath == null
                !isOwed && !removedHere && current?.userCoverSha256 != remoteCover.sha256
            }
            if (needsDownload) {
                val bytes = try {
                    http.getBytes(
                        coverUrl,
                        credentials,
                        expected = setOf(LiseurSyncHttp.NOT_FOUND),
                    )
                } catch (missing: LiseurSyncRejection) {
                    // A work can hold no cover at all: the 404 is about the
                    // picture, not about the work, so nothing here may be
                    // taken for the work having gone.
                    if (missing.code == LiseurSyncHttp.NOT_FOUND) null else throw missing
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    downloadedCover = remoteCover.sha256 to bytes
                }
            }
        }

        for (target in copies) {
            val bookUrl = target.alias.bookUrl
            val before = books.getByUrl(bookUrl)
            // The download may have written over the cover this book already
            // shows, since the path is the same book and the same bytes. Left
            // over is only deleted when it is nobody's cover.
            val awaitedCoverPath = before?.userCoverPath
            val removedHere = before != null && before.coverOverridden && before.userCoverPath == null
            var writtenCoverFile: File? = null
            if (downloadedCover != null && !removedHere) {
                writtenCoverFile = repository.writeExternalCoverFile(
                    bookUrl,
                    downloadedCover.first,
                    downloadedCover.second,
                )
            }
            var oldCoverToDelete: String? = null
            var coverWritten = false
            inTransaction {                if (!stillOurs(server) || !stillBound(target)) return@inTransaction
                val current = books.getByUrl(bookUrl) ?: return@inTransaction
                for (field in MetadataOverrideFields.TEXT_FIELDS) {
                    val rev = overrides.revision(bookUrl, field)
                    val st = overrides.state(peer, bookUrl, field)
                    if (rev?.owed(st) == true || st?.sentJson != null) continue
                    val remoteValue = remoteFields[field]
                    if (remoteValue != null) {
                        if (overriddenValue(current, field) != remoteValue) {
                            writeTextOverride(bookUrl, field, remoteValue)
                        }
                        if (st != null && !st.adopted) overrides.upsertState(st.copy(adopted = true))
                    } else if (st?.adopted == true && overriddenValue(current, field) != null) {
                        // Adopted from here in the first place, and now gone:
                        // another device reset it, and this one follows.
                        writeTextOverride(bookUrl, field, null)
                        overrides.upsertState(st.copy(adopted = false))
                    }
                }

                // Apply remote cover choice if not locally owed.
                if (remoteCover != null) {
                    val coverRev = overrides.revision(bookUrl, MetadataOverrideFields.COVER)
                    val coverState = overrides.state(peer, bookUrl, MetadataOverrideFields.COVER)
                    if (coverRev?.owed(coverState) != true && !removedHere) {
                        if (remoteCover.sha256 != null && writtenCoverFile != null) {
                            coverWritten = true
                            if (current.userCoverSha256 != remoteCover.sha256 || !current.coverOverridden) {
                                if (current.userCoverPath != writtenCoverFile.absolutePath) {
                                    oldCoverToDelete = current.userCoverPath
                                }
                                books.setCoverOverride(
                                    bookUrl,
                                    writtenCoverFile.absolutePath,
                                    remoteCover.sha256,
                                )
                            }
                            overrides.upsertState(
                                (coverState ?: MetadataOverrideSync(peer, bookUrl, MetadataOverrideFields.COVER, workId))
                                    .copy(
                                        workId = workId,
                                        remoteCoverSha256 = remoteCover.sha256,
                                        remoteCoverUpdatedAt = remoteCover.updatedAt,
                                    ),
                            )
                        } else if (remoteCover.sha256 == null) {
                            val priorUpdated = coverState?.remoteCoverUpdatedAt ?: 0L
                            if (remoteCover.updatedAt > priorUpdated && current.coverOverridden) {
                                oldCoverToDelete = current.userCoverPath
                                books.clearCoverOverride(bookUrl)
                            }
                            overrides.upsertState(
                                (coverState ?: MetadataOverrideSync(peer, bookUrl, MetadataOverrideFields.COVER, workId))
                                    .copy(
                                        workId = workId,
                                        remoteCoverSha256 = null,
                                        remoteCoverUpdatedAt = maxOf(priorUpdated, remoteCover.updatedAt),
                                    ),
                            )
                        }
                    }
                }

                val titleState = overrides.state(peer, bookUrl, MetadataOverrideFields.TITLE)
                if (titleState != null) {
                    overrides.upsertState(titleState.copy(polledAt = passAt))
                }
            }
            oldCoverToDelete?.let { File(it).delete() }
            if (!coverWritten && writtenCoverFile != null && writtenCoverFile.absolutePath != awaitedCoverPath) {
                writtenCoverFile.delete()
            }
        }
    }

    /**
     * Marks a cover the reader removed here as settled without sending it.
     *
     * The server's DELETE means "back to the original cover", which is a
     * different answer from having no cover, so this one never leaves the
     * device.
     */
    private suspend fun settleCoverRemoval(
        peer: String,
        workId: String,
        target: Target,
        revision: MetadataOverrideRevision,
        state: MetadataOverrideSync?,
    ) {
        val base = state
            ?: MetadataOverrideSync(peer, target.alias.bookUrl, MetadataOverrideFields.COVER, workId)
        overrides.upsertState(
            base.copy(
                workId = workId,
                ackedRevision = maxOf(base.ackedRevision, revision.revision),
                adopted = false,
            ),
        )
    }

    /** The value this book holds for [field] as an override, or null when it holds none. */
    private fun overriddenValue(book: Book, field: String): String? = when (field) {
        MetadataOverrideFields.TITLE -> if (book.titleOverridden) book.userTitle ?: "" else null
        MetadataOverrideFields.AUTHOR -> if (book.authorOverridden) book.userAuthor ?: "" else null
        else -> if (book.descriptionOverridden) book.userDescription ?: "" else null
    }

    private suspend fun writeTextOverride(bookUrl: String, field: String, value: String?) {
        when (field) {
            MetadataOverrideFields.TITLE ->
                if (value == null) books.clearTitleOverride(bookUrl) else books.setTitleOverride(bookUrl, value)
            MetadataOverrideFields.AUTHOR ->
                if (value == null) books.clearAuthorOverride(bookUrl) else books.setAuthorOverride(bookUrl, value)
            else ->
                if (value == null) books.clearDescriptionOverride(bookUrl) else books.setDescriptionOverride(bookUrl, value)
        }
    }

    private suspend fun sendText(
        server: RemoteServer,
        metadataUrl: String,
        credentials: com.chmouel.liseur.data.remote.RemoteCredentials,
        target: Target,
        state: MetadataOverrideSync,
    ) {
        val body = state.sentJson ?: return
        try {
            http.postRaw(
                metadataUrl,
                credentials,
                body,
                expected = setOf(
                    LiseurSyncHttp.NOT_FOUND,
                    LiseurSyncHttp.CONFLICT,
                    LiseurSyncHttp.BAD_REQUEST,
                    LiseurSyncHttp.UNPROCESSABLE,
                ),
            )
        } catch (rejection: LiseurSyncRejection) {
            // A value the server will never take would otherwise be
            // replayed ahead of every later edit, and a corrected one could
            // never get past it. Settle it here instead.
            val permanent = !rejection.isUnknownWork &&
                (rejection.code == LiseurSyncHttp.BAD_REQUEST || rejection.code == LiseurSyncHttp.UNPROCESSABLE)
            if (!permanent) throw rejection
            settleText(state, body)
            return
        }
        inTransaction {
            if (!stillOurs(server) || !stillBound(target)) return@inTransaction
            acknowledge(state, body)
        }
    }

    private suspend fun settleText(state: MetadataOverrideSync, body: String) {
        inTransaction { acknowledge(state, body) }
    }

    private suspend fun acknowledge(state: MetadataOverrideSync, body: String) {
        val current = overrides.state(state.peerId, state.bookUrl, state.field)
        if (current != null && current.sentJson == body) {
            overrides.upsertState(
                current.copy(
                    ackedRevision = maxOf(current.ackedRevision, current.sentRevision ?: 0L),
                    sentJson = null,
                    sentRevision = null,
                ),
            )
        }
    }

    private suspend fun pushCover(
        server: RemoteServer,
        peer: String,
        workId: String,
        coverUrl: String,
        credentials: com.chmouel.liseur.data.remote.RemoteCredentials,
        target: Target,
        book: Book,
        revision: Long,
    ) {
        val coverPath = if (book.coverOverridden) book.userCoverPath else null
        val bytes = coverPath?.let { path ->
            withContext(Dispatchers.IO) {
                val file = File(path)
                if (file.isFile) file.readBytes() else null
            }
        }
        val response = if (bytes != null && bytes.isNotEmpty()) {
            http.putBytes(
                coverUrl,
                credentials,
                bytes,
                contentType = "image/jpeg",
                expected = setOf(LiseurSyncHttp.NOT_FOUND, LiseurSyncHttp.TOO_LARGE),
            )
        } else {
            http.delete(
                coverUrl,
                credentials,
                expected = setOf(LiseurSyncHttp.NOT_FOUND),
            )
        }
        val remoteSha = response.optString("sha256").takeIf { it.isNotEmpty() }
        val remoteUpdated = parseIsoMillis(response.optString("updated_at"))
        inTransaction {
            if (!stillOurs(server) || !stillBound(target)) return@inTransaction
            val current = overrides.state(peer, book.url, MetadataOverrideFields.COVER)
                ?: MetadataOverrideSync(peer, book.url, MetadataOverrideFields.COVER, workId)
            overrides.upsertState(
                current.copy(
                    workId = workId,
                    ackedRevision = maxOf(current.ackedRevision, revision),
                    remoteCoverSha256 = remoteSha,
                    remoteCoverUpdatedAt = maxOf(current.remoteCoverUpdatedAt, remoteUpdated),
                ),
            )
        }
    }

    private fun buildTextPayload(
        writer: String,
        workId: String,
        field: String,
        revision: Long,
        book: Book,
    ): String {
        val writeId = UUID.nameUUIDFromBytes("$writer|$workId|$field|$revision".toByteArray(Charsets.UTF_8)).toString()
        val value: Any = overriddenValue(book, field) ?: JSONObject.NULL
        return JSONObject()
            .put("write_id", writeId)
            .put("writer_id", writer)
            .put("revision", revision)
            .put("field", field)
            .put("value", value)
            .toString()
    }

    private fun parseRemoteFields(response: JSONObject): Map<String, String> {
        val array = response.optJSONArray("fields") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val field = item.optString("field")
            if (field !in MetadataOverrideFields.TEXT_FIELDS) continue
            if (!item.isNull("value")) {
                result[field] = item.optString("value", "")
            }
        }
        return result
    }

    private fun parseRemoteCover(response: JSONObject): RemoteCover? {
        if (!response.has("cover") || response.isNull("cover")) return null
        val obj = response.optJSONObject("cover") ?: return null
        val sha = if (obj.isNull("sha256")) null else obj.optString("sha256").takeIf { it.isNotEmpty() }
        val updatedAt = parseIsoMillis(obj.optString("updated_at"))
        return RemoteCover(sha, updatedAt)
    }

    private fun parseIsoMillis(text: String): Long = try {
        if (text.isBlank()) 0L else Instant.parse(text).toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    private suspend fun stillOurs(server: RemoteServer): Boolean =
        servers.get(server.id)?.let { it.accountKey == server.accountKey } == true

    /**
     * Whether [target] still names the same book and the same work.
     *
     * A rescan can replace the book at a URL, or resolve it to another
     * work, while a metadata request is out. Answering that request then
     * would write its title, author, description or cover onto a book it
     * was never about.
     */
    private suspend fun stillBound(target: Target): Boolean {
        val current = books.getByUrl(target.alias.bookUrl) ?: return false
        if (!current.sameGeneration(target.book)) return false
        val alias = identities.alias(target.alias.bookUrl, target.alias.peerId) ?: return false
        return alias.usable && alias.workId == target.alias.workId &&
            alias.resolvedAt == target.alias.resolvedAt && alias.editionSha == target.alias.editionSha
    }
}
