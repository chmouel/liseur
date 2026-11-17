package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookEvaluation
import com.chmouel.liseur.data.db.BookEvaluationDao
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.EvaluationSync
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.RetainedEvaluation
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.domain.WorkIdentifier
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class LiseurSyncEvaluations(
    private val servers: RemoteServerDao,
    private val identities: WorkIdentityDao,
    private val books: BookDao,
    private val evaluations: BookEvaluationDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    private val works: WorkResolver,
    private val writerId: suspend () -> String,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
) {
    private val unsupported = mutableSetOf<String>()
    private val fields = listOf(PersonalEvaluation.RATING, PersonalEvaluation.REVIEW)

    private data class Target(val alias: WorkAlias, val retained: RetainedEvaluation? = null)

    suspend fun sync(server: RemoteServer, book: String?) {
        if (server.baseUrl in unsupported) return
        val peer = server.accountKey
        val targets = mutableListOf<Target>()
        val states = evaluations.states(peer).groupBy { it.bookUrl }
        for (retained in evaluations.retained()) {
            val saved = states[retained.bookUrl].orEmpty()
            val values = fields.mapNotNull { evaluations.get(retained.bookUrl, it) }
            if (saved.isEmpty() && values.none { it.owed(null) }) continue
            val workId = saved.firstOrNull()?.workId.orEmpty()
            if (book != null && book != retained.originalUrl && workId.isEmpty()) continue
            val alias = if (workId.isNotEmpty()) {
                WorkAlias(retained.bookUrl, peer, workId, WorkAlias.HIGH, resolvedAt = 0)
            } else {
                if (!stillOurs(server)) return
                val metadata = values.firstOrNull() ?: continue
                val identifiers = JSONArray(retained.identifiers).let { array ->
                    (0 until array.length()).map {
                        val item = array.getJSONObject(it)
                        WorkIdentifier(item.getString("kind"), item.getString("value"))
                    }
                }
                works.resolveRetained(
                    Book(url = retained.bookUrl, title = metadata.title, author = metadata.author, coverPath = null,
                        source = null, addedAt = 0, lastOpenedAt = null),
                    identifiers, peer, server.baseUrl, server.credentials ?: return,
                ) ?: continue
            }
            targets += Target(alias, retained)
        }
        targets += identities.aliasesFor(peer).filter { it.usable }.map { Target(it) }
        for (copies in targets.groupBy { it.alias.workId }.values) {
            if (book != null && copies.none { it.alias.bookUrl == book || it.retained?.originalUrl == book }) continue
            if (!stillOurs(server)) return
            try {
                syncWork(server, copies)
            } catch (e: LiseurSyncRejection) {
                if (e.code == 404 && e.rawBody.trim().let { it.isEmpty() || it == "404 page not found" }) {
                    unsupported += server.baseUrl
                    return
                }
                if (e.code == 404) {
                    inTransaction {
                        for (target in copies) {
                            if (stillOurs(server) && stillBound(target)) {
                                identities.deleteAliasIfStale(target.alias.bookUrl, peer, target.alias.workId)
                                evaluations.invalidateWork(peer, target.alias.bookUrl, target.alias.workId)
                            }
                        }
                    }
                    continue
                }
                throw e
            }
        }
    }

    private suspend fun syncWork(server: RemoteServer, targets: List<Target>) {
        val peer = server.accountKey
        val workId = targets.first().alias.workId
        val url = LiseurSyncApi.evaluation(server.baseUrl, workId)
        val bound = mutableListOf<Target>()
        inTransaction {
            for (target in targets) {
                if (!stillOurs(server) || !stillBound(target)) continue
                for (field in fields) {
                    val prior = evaluations.state(peer, target.alias.bookUrl, field)
                    // Receipts move with a merged work; acknowledgement and retry bytes must follow
                    evaluations.upsertState(prior?.copy(workId = workId) ?: EvaluationSync(peer, target.alias.bookUrl, field, workId))
                }
                bound += target
            }
        }
        if (bound.isEmpty()) return
        val writer = writerId()
        for (field in fields) {
            val pending = bound.mapNotNull { target ->
                evaluations.state(peer, target.alias.bookUrl, field)?.takeIf { it.sentJson != null }?.let { target to it }
            }.sortedBy { it.second.sentRevision }
            for ((target, state) in pending) {
                if (!stillOurs(server)) return
                if (stillBound(target)) send(server, url, state)
            }
            val edits = mutableListOf<Pair<Target, BookEvaluation>>()
            inTransaction {
                if (!stillOurs(server)) return@inTransaction
                val valid = bound.filter { stillBound(it) }
                val agreements = valid.associateWith { evaluations.state(peer, it.alias.bookUrl, field) }
                val acknowledged = agreements.values.maxOfOrNull { it?.takeIf { it.workId == workId }?.ackedRevision ?: 0 } ?: 0
                for (target in valid) {
                    val current = evaluations.get(target.alias.bookUrl, field) ?: continue
                    val agreement = agreements[target]
                    if (agreement?.workId == workId && current.owed(agreement)) {
                        // A copy may be confirmed after another copy delivered a later edit
                        if (current.revision < acknowledged) {
                            evaluations.upsert(current.copy(retiredRevision = current.revision))
                        } else {
                            edits += target to current
                        }
                    }
                }
            }
            for ((target, planned) in edits.sortedBy { it.second.revision }) {
                if (!stillOurs(server)) return
                var prepared: EvaluationSync? = null
                inTransaction {
                    if (!stillOurs(server) || !stillBound(target)) return@inTransaction
                    val current = evaluations.get(target.alias.bookUrl, field)
                    val agreement = evaluations.state(peer, target.alias.bookUrl, field)
                    if (current != null && current == planned && agreement?.workId == workId && agreement.sentJson == null && current.owed(agreement)) {
                        val id = UUID.nameUUIDFromBytes("evaluation|$writer|$workId|${current.bookUrl}|$field|${current.revision}".toByteArray(Charsets.UTF_8)).toString()
                        val value = if (field == PersonalEvaluation.RATING) current.value?.toInt() else current.value
                        val json = JSONObject().put("write_id", id).put("writer_id", writer).put("revision", current.revision)
                            .put("field", field).put("value", value ?: JSONObject.NULL).toString()
                        prepared = agreement.copy(sentJson = json, sentRevision = current.revision)
                        evaluations.upsertState(checkNotNull(prepared))
                    }
                }
                prepared?.let { send(server, url, it) }
            }
        }
        if (!stillOurs(server)) return
        val response = http.get(url, server.credentials, expected = setOf(404))
        inTransaction {
            if (!stillOurs(server)) return@inTransaction
            val remoteFields = response.getJSONArray("fields")
            for (target in bound) {
                if (!stillBound(target)) continue
                val bookUrl = target.alias.bookUrl
                val metadata = books.getByUrl(bookUrl)
                for (index in 0 until remoteFields.length()) {
                    val remote = remoteFields.getJSONObject(index)
                    val field = remote.getString("field")
                    val value = if (remote.isNull("value")) null else remote.get("value").toString()
                    require(PersonalEvaluation.valid(field, value))
                    val current = evaluations.get(bookUrl, field)
                    val agreement = evaluations.state(peer, bookUrl, field)
                    if (agreement?.workId == workId && (current == null || !current.owed(agreement))) {
                        evaluations.upsert(BookEvaluation(
                            bookUrl, field, value,
                            metadata?.title ?: current?.title.orEmpty(),
                            metadata?.author ?: current?.author.orEmpty(),
                            current?.revision ?: 0, current?.retiredRevision ?: 0,
                            java.time.Instant.parse(remote.getString("updated_at")).toEpochMilli(),
                        ))
                    }
                }
            }
        }
    }

    private suspend fun stillBound(target: Target): Boolean = if (target.retained != null) {
        evaluations.retained(target.alias.bookUrl) == target.retained
    } else {
        identities.alias(target.alias.bookUrl, target.alias.peerId)?.let {
            it.usable && it.workId == target.alias.workId && it.resolvedAt == target.alias.resolvedAt && it.editionSha == target.alias.editionSha
        } == true
    }

    private suspend fun send(server: RemoteServer, url: String, state: EvaluationSync) {
        if (!stillOurs(server)) return
        http.postRaw(url, server.credentials, checkNotNull(state.sentJson), expected = setOf(404, 409))
        inTransaction {
            if (stillOurs(server)) {
                val current = evaluations.state(state.peerId, state.bookUrl, state.field)
                if (current?.sentJson == state.sentJson && current.workId == state.workId) {
                    evaluations.upsertState(current.copy(
                        ackedRevision = maxOf(current.ackedRevision, checkNotNull(state.sentRevision)),
                        sentJson = null, sentRevision = null,
                    ))
                }
            }
        }
    }

    private suspend fun stillOurs(server: RemoteServer): Boolean = servers.get()?.let {
        it.accountKey == server.accountKey && it.addedAt == server.addedAt && it.credentials == server.credentials
    } == true
}
