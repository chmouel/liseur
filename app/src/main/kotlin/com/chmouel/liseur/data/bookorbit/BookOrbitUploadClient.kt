package com.chmouel.liseur.data.bookorbit

import android.util.Log
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingDao
import com.chmouel.liseur.data.db.BookOrbitBindingState
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.remote.BookUploader
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.RemoteUploadTarget
import com.chmouel.liseur.data.remote.RemoteUrl
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.ServerUploadResult
import com.chmouel.liseur.data.remote.SyncFailure
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject

/**
 * Sends a book from this device into a BookOrbit library.
 *
 * BookOrbit takes a book as a resumable upload session: create it, send
 * the bytes in chunks from wherever the server says it has got to, then
 * ask it to import them. The session is named by an idempotency key, and
 * this client derives that key from what is being sent rather than
 * inventing one. A worker that died halfway through asks for the same
 * key again, is handed the same session back, and carries on from the
 * server's offset instead of starting over or storing the book twice.
 *
 * What the key is made of matters. The library entry's own URL is in
 * it, so two entries holding identical bytes get a session each and can
 * never adopt each other's result. A session that ended for good
 * (expired, cancelled, failed in a way that may not recur) is left
 * behind by moving to the next generation of the key, a bounded number
 * of times.
 *
 * Adoption needs the server file, not only the book: every position and
 * status Liseur keeps for BookOrbit names a file. The file is only named
 * when it is proved, by size and, if that is ambiguous, by the bytes'
 * digest; otherwise the book is reported as uploaded but unlinked, and
 * nothing is guessed.
 *
 * Messages from the server are never shown or logged: they can carry
 * paths from its disk. The coded refusals map onto the app's own words.
 */
class BookOrbitUploadClient(
    private val http: BookOrbitHttp,
    private val serverDao: RemoteServerDao,
    private val bindings: BookOrbitBindingDao,
) : BookUploader {

    /**
     * The libraries this account may add an EPUB to.
     *
     * Throws when the server could not be asked, as the contract
     * requires: an empty list turns uploading off until the reader signs
     * in again, which only a real "none" should be able to do.
     */
    override suspend fun targets(
        baseUrl: String,
        credentials: RemoteCredentials,
    ): List<RemoteUploadTarget> {
        val (context, _) = connection(baseUrl) ?: throw RemoteHttpFailure(SyncFailure.Unauthorised)
        val caps = capabilities(context)
        if (!caps.canUpload) return emptyList()
        // The worker sends to the first one and the library is part of the
        // upload's key, so a retry must see the same first library.
        return caps.libraries
            .filter { it.takesEpub }
            .sortedBy { it.id }
            .map { RemoteUploadTarget(folderId = "$TARGET_PREFIX${it.id}", name = it.name) }
    }

    override suspend fun upload(
        baseUrl: String,
        credentials: RemoteCredentials,
        folderId: String,
        file: File,
        filename: String,
        bookUrl: String,
        sha256: String,
        accountKey: String,
    ): ServerUploadResult {
        val libraryId = folderId.removePrefix(TARGET_PREFIX).toLongOrNull()
            ?.takeIf { folderId.startsWith(TARGET_PREFIX) && it > 0 }
            ?: return ServerUploadResult.Failed("not a BookOrbit library: $folderId")
        val (context, server) = connection(baseUrl)
            ?: return ServerUploadResult.Failed("no BookOrbit connection for this address")
        // Every request below signs as the account in this context, so
        // this is the one check that keeps the book out of another
        // reader's library on the same server.
        if (server.accountKey != accountKey) return ServerUploadResult.Failed("the account changed")
        val accountId = server.accountId ?: return ServerUploadResult.Failed("no account id")
        val size = file.length()
        if (size <= 0L) return ServerUploadResult.Rejected(null)
        return try {
            val caps = capabilities(context)
            if (caps.maxFileSizeBytes != null && size > caps.maxFileSizeBytes) {
                return ServerUploadResult.TooLarge
            }
            val scope = BookOrbitScope.fingerprint(context.baseUrl, accountId)
            val sent = Sent(file, filename, size, sha256.lowercase(), libraryId, caps.chunkSize)
            for (generation in 0 until MAX_GENERATIONS) {
                val key = idempotencyKey(scope, bookUrl, libraryId, filename, size, sent.sha256, generation)
                when (val step = session(context, key, sent)) {
                    Step.NextGeneration -> continue
                    is Step.Done -> return when (val result = step.result) {
                        is Stored -> adoptable(context, accountId, result.bookId, sent)
                        is Settled -> result.result
                    }
                }
            }
            // Every generation ended without a book. Whatever keeps
            // happening is not going to stop happening on its own.
            ServerUploadResult.Rejected(null)
        } catch (e: RemoteHttpFailure) {
            Log.i(TAG, "BookOrbit upload failed: ${e.reason}")
            ServerUploadResult.Failed(e.reason.toString())
        } catch (e: IOException) {
            Log.i(TAG, "BookOrbit upload interrupted", e)
            ServerUploadResult.Failed(e.message)
        }
    }

    /**
     * Writes the binding for the adopted book, under its local URL.
     *
     * The worker has already checked the account inside this transaction;
     * this checks that it is still a BookOrbit account. The digest is
     * written here and nowhere else: it is what lets the local file stand
     * for the server file when a position is computed.
     */
    override suspend fun adopted(
        bookUrl: String,
        accountKey: String,
        result: ServerUploadResult.Uploaded,
        sha256: String,
    ) {
        val server = serverDao.get()
        if (server?.kind != ServerKind.BOOKORBIT || server.accountKey != accountKey) return
        val bookId = result.remoteBookId.toLongOrNull() ?: return
        val fileId = result.fileId ?: return
        val existing = bindings.get(accountKey, bookUrl)
        bindings.write(
            BookOrbitBinding(
                accountKey = accountKey,
                bookUrl = bookUrl,
                bookId = bookId,
                fileId = fileId,
                fileFormat = EPUB_FORMAT,
                fileSize = result.fileSize,
                fileName = null,
                revision = (existing?.revision ?: -1L) + 1,
                state = BookOrbitBindingState.SELECTED.name,
                updatedAt = System.currentTimeMillis(),
                localSha256 = sha256.lowercase(),
            ),
        )
    }

    /** What is being sent, in one place. */
    private class Sent(
        val file: File,
        val filename: String,
        val size: Long,
        val sha256: String,
        val libraryId: Long,
        val chunkSize: Long,
    )

    private sealed interface Outcome
    private data class Stored(val bookId: Long) : Outcome
    private data class Settled(val result: ServerUploadResult) : Outcome

    private sealed interface Step {
        data object NextGeneration : Step
        data class Done(val result: Outcome) : Step
    }

    /**
     * Drives one session, named by [key], as far as it will go now.
     *
     * Every turn acts on the session as the server last described it,
     * never on what this client thinks it did: an answer lost on the way
     * back is read back rather than assumed.
     */
    private suspend fun session(
        context: BookOrbitRequestContext,
        key: String,
        sent: Sent,
    ): Step {
        val created = http.exchange(
            context,
            BookOrbitUrl.api(context.baseUrl, "uploads"),
            "POST",
            body = { jsonBody(createBody(key, sent)) },
        )
        if (!created.isSuccessful) {
            // The key is taken by an upload that differs in name, size
            // or target: a book renamed since, most likely.
            if (created.code(ERROR_SESSION_STATE)) return Step.NextGeneration
            return done(refusal(created))
        }
        var session = created.body ?: return done(ServerUploadResult.Failed("empty session"))
        val id = session.stringOrNull("id") ?: return done(ServerUploadResult.Failed("no session id"))
        var turns = (sent.size / sent.chunkSize) * 2 + EXTRA_TURNS
        while (turns-- > 0) {
            when (session.stringOrNull("status")) {
                STATUS_RECEIVING -> {
                    val received = session.longOrNull("receivedBytes") ?: 0L
                    val answer = if (received < sent.size) {
                        sendChunk(context, id, sent, received)
                    } else {
                        complete(context, id)
                    }
                    session = when {
                        answer.isSuccessful -> answer.body ?: read(context, id) ?: return Step.NextGeneration
                        // Where the server has got to is its to say; ask.
                        answer.code(ERROR_OFFSET) || answer.code(ERROR_SESSION_STATE) ->
                            read(context, id) ?: return Step.NextGeneration
                        answer.code(ERROR_EXPIRED) || answer.code == 404 -> return Step.NextGeneration
                        // The server's copy of the whole file is not what
                        // was meant to be sent, and it cannot be resent
                        // into the same session. Start a clean one.
                        answer.code(ERROR_CHECKSUM) && received >= sent.size -> {
                            cancel(context, id)
                            return Step.NextGeneration
                        }
                        // A chunk damaged on the way. The session is
                        // where it was; the next attempt resends it.
                        answer.code(ERROR_CHECKSUM) ->
                            return done(ServerUploadResult.Failed("chunk checksum"))
                        else -> return done(refusal(answer))
                    }
                }
                // Stored but not yet imported, and an import can still
                // fail. Adopting now would link a book that may never be.
                STATUS_PROCESSING -> return done(ServerUploadResult.Pending)
                STATUS_COMPLETED -> {
                    val bookId = session.longOrNull("bookId")
                        ?: return done(ServerUploadResult.Failed("completed without a book"))
                    return Step.Done(Stored(bookId))
                }
                STATUS_FAILED -> {
                    val code = session.stringOrNull("errorCode")
                    // The library went, as in [refusal]; another key
                    // would aim at the same one.
                    if (code == ERROR_TARGET) return done(ServerUploadResult.Failed("upload target"))
                    // A code that says what is wrong with the book is an
                    // answer. An import that failed for no stated reason
                    // may not fail again, and the next generation is how
                    // to find out without looping on this session.
                    val mapped = code?.let { mapCode(it, 0) }
                    return if (mapped == null || mapped is ServerUploadResult.Failed) {
                        Step.NextGeneration
                    } else {
                        done(mapped)
                    }
                }
                STATUS_EXPIRED, STATUS_CANCELLED -> return Step.NextGeneration
                else -> return done(ServerUploadResult.Failed("unknown session state"))
            }
        }
        return done(ServerUploadResult.Failed("upload did not settle"))
    }

    private fun done(result: ServerUploadResult) = Step.Done(Settled(result))

    private fun createBody(key: String, sent: Sent) = JSONObject()
        .put("filename", sent.filename)
        .put("sizeBytes", sent.size)
        .put("idempotencyKey", key)
        .put("target", JSONObject().put("kind", "library").put("libraryId", sent.libraryId))
        .put("contentType", EPUB_TYPE)
        .put("sha256", sent.sha256)

    private suspend fun sendChunk(
        context: BookOrbitRequestContext,
        id: String,
        sent: Sent,
        offset: Long,
    ): BookOrbitHttp.Answer {
        val length = minOf(sent.chunkSize, sent.size - offset)
        val checksum = sliceSha256(sent.file, offset, length)
        return http.exchange(
            context,
            BookOrbitUrl.api(context.baseUrl, "uploads/$id/chunks"),
            "POST",
            body = {
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", sent.filename, FileSlice(sent.file, offset, length))
                    .build()
            },
            headers = mapOf(
                "Upload-Offset" to offset.toString(),
                "Upload-Checksum" to "sha256=$checksum",
            ),
            transfer = true,
        )
    }

    private suspend fun complete(context: BookOrbitRequestContext, id: String) =
        http.exchange(
            context,
            BookOrbitUrl.api(context.baseUrl, "uploads/$id/complete"),
            "POST",
            transfer = true,
        )

    private suspend fun read(context: BookOrbitRequestContext, id: String): JSONObject? {
        val answer = http.exchange(context, BookOrbitUrl.api(context.baseUrl, "uploads/$id"), "GET")
        return answer.body.takeIf { answer.isSuccessful }
    }

    private suspend fun cancel(context: BookOrbitRequestContext, id: String) {
        http.exchange(context, BookOrbitUrl.api(context.baseUrl, "uploads/$id"), "DELETE")
    }

    /**
     * Turns a stored book into a link, if the file can be proved.
     *
     * The server says which book the bytes went into, not which of its
     * files they became. In a library that keeps one book per folder an
     * upload can join a book that already had an EPUB, so the file is
     * picked by size and, where more than one fits, by reading each
     * candidate back and comparing digests.
     */
    private suspend fun adoptable(
        context: BookOrbitRequestContext,
        accountId: String,
        bookId: Long,
        sent: Sent,
    ): ServerUploadResult {
        val book = http.getObject(context, BookOrbitUrl.api(context.baseUrl, "books/$bookId"))
        val candidates = book.objects("files")
            .mapNotNull(BookOrbitBooks::parseFile)
            .filter { it.isEpub && it.sizeBytes == sent.size }
        val file = when {
            candidates.size == 1 -> candidates.single()
            candidates.size in 2..MAX_HASHED_CANDIDATES -> candidates
                .filter { digestOf(context, it.id) == sent.sha256 }
                .singleOrNull()
            else -> null
        } ?: return ServerUploadResult.UploadedUnlinked(null)
        val remoteUuid = BookOrbitScope.remoteId(context.baseUrl, accountId, bookId)
        return ServerUploadResult.Uploaded(
            remoteBookId = bookId.toString(),
            alreadyThere = false,
            remoteUuid = remoteUuid,
            downloadHref = BookOrbitUrl.downloadHref(file.id),
            fileId = file.id,
            fileSize = sent.size,
        )
    }

    /**
     * A failed read is thrown rather than taken as a mismatch: a file
     * that could not be read might be the match, and a choice made
     * without it would not be proved.
     */
    private suspend fun digestOf(context: BookOrbitRequestContext, fileId: Long): String =
        http.stream(context, BookOrbitUrl.api(context.baseUrl, "books/files/$fileId/download")) {
            sha256Of(it)
        }

    private suspend fun capabilities(context: BookOrbitRequestContext): Capabilities {
        val json = http.getObject(context, BookOrbitUrl.api(context.baseUrl, "uploads/capabilities"))
        val canUpload = json.booleanOrNull("canUploadToLibrary")
            ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        val libraries = json.optJSONArray("libraries")
            ?: throw RemoteHttpFailure(SyncFailure.Malformed)
        return Capabilities(
            canUpload = canUpload,
            maxFileSizeBytes = json.longOrNull("maxFileSizeBytes")?.takeIf { it > 0 },
            chunkSize = json.longOrNull("chunkSizeBytes")?.takeIf { it > 0 } ?: DEFAULT_CHUNK,
            libraries = (0 until libraries.length()).mapNotNull { index ->
                val library = libraries.optJSONObject(index) ?: return@mapNotNull null
                val id = library.longOrNull("id")?.takeIf { it > 0 } ?: return@mapNotNull null
                val formats = library.optJSONArray("allowedFormats")
                    ?.let { array -> (0 until array.length()).map { array.optString(it).lowercase() } }
                    .orEmpty()
                Library(
                    id = id,
                    name = library.stringOrNull("name") ?: "#$id",
                    takesEpub = formats.isEmpty() || EPUB_FORMAT in formats,
                )
            },
        )
    }

    private data class Capabilities(
        val canUpload: Boolean,
        val maxFileSizeBytes: Long?,
        val chunkSize: Long,
        val libraries: List<Library>,
    )

    private data class Library(val id: Long, val name: String, val takesEpub: Boolean)

    private suspend fun connection(baseUrl: String): Pair<BookOrbitRequestContext, RemoteServer>? {
        val server = serverDao.get()?.takeIf {
            it.kind == ServerKind.BOOKORBIT && RemoteUrl.sameAddress(it.baseUrl, baseUrl)
        } ?: return null
        val context = BookOrbitRequestContext.from(server) ?: return null
        return context to server
    }

    /** A byte range of a file, readable as many times as the call is retried. */
    private class FileSlice(
        private val file: File,
        private val offset: Long,
        private val length: Long,
    ) : RequestBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(BUFFER)
                var left = length
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) throw IOException("file shorter than it was")
                    sink.write(buffer, 0, read)
                    left -= read
                }
            }
        }
    }

    internal companion object {
        private const val TAG = "BookOrbitUpload"
        const val TARGET_PREFIX = "library:"
        private const val EPUB_FORMAT = "epub"
        private const val EPUB_TYPE = "application/epub+zip"
        private const val MAX_GENERATIONS = 5
        private const val MAX_HASHED_CANDIDATES = 3
        private const val EXTRA_TURNS = 8L
        private const val DEFAULT_CHUNK = 16L * 1024 * 1024
        private const val BUFFER = 64 * 1024
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val STATUS_RECEIVING = "receiving"
        private const val STATUS_PROCESSING = "processing"
        private const val STATUS_COMPLETED = "completed"
        private const val STATUS_FAILED = "failed"
        private const val STATUS_EXPIRED = "expired"
        private const val STATUS_CANCELLED = "cancelled"

        private const val ERROR_TOO_LARGE = "UPLOAD_TOO_LARGE"
        private const val ERROR_EMPTY = "UPLOAD_EMPTY"
        private const val ERROR_UNSUPPORTED = "UPLOAD_FORMAT_UNSUPPORTED"
        private const val ERROR_NOT_ALLOWED = "UPLOAD_FORMAT_NOT_ALLOWED"
        private const val ERROR_INVALID = "UPLOAD_CONTENT_INVALID"
        private const val ERROR_DUPLICATE = "UPLOAD_DUPLICATE"
        private const val ERROR_CONFLICT = "UPLOAD_DESTINATION_CONFLICT"
        private const val ERROR_OFFSET = "UPLOAD_OFFSET_MISMATCH"
        private const val ERROR_CHECKSUM = "UPLOAD_CHECKSUM_MISMATCH"
        private const val ERROR_EXPIRED = "UPLOAD_SESSION_EXPIRED"
        private const val ERROR_SESSION_STATE = "UPLOAD_SESSION_STATE_INVALID"
        private const val ERROR_TARGET = "UPLOAD_TARGET_INVALID"

        /**
         * The session name for one attempt at sending these bytes for
         * this entry to this library. Length-delimited so no two inputs
         * spell the same material, and short enough for the server's
         * 100-character limit.
         */
        fun idempotencyKey(
            scope: String,
            bookUrl: String,
            libraryId: Long,
            filename: String,
            size: Long,
            sha256: String,
            generation: Int,
        ): String {
            val material = listOf(scope, bookUrl, libraryId.toString(), filename, size.toString(), sha256)
                .joinToString("|") { "${it.length}:$it" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(material.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(KEY_DIGEST_HEX)
            return "liseur-$digest-g$generation"
        }

        private const val KEY_DIGEST_HEX = 40

        /**
         * What a coded refusal means for this book.
         *
         * Null for a code this client has no answer for, which callers
         * treat as worth another try.
         */
        fun mapCode(code: String, status: Int): ServerUploadResult? = when (code) {
            ERROR_TOO_LARGE -> ServerUploadResult.TooLarge
            ERROR_EMPTY, ERROR_UNSUPPORTED, ERROR_NOT_ALLOWED, ERROR_INVALID,
            ERROR_DUPLICATE, ERROR_CONFLICT,
            -> ServerUploadResult.Rejected(null)
            else -> if (status >= 500) ServerUploadResult.Failed("HTTP $status") else null
        }

        private fun BookOrbitHttp.Answer.code(expected: String): Boolean =
            body?.stringOrNull("errorCode") == expected

        /** An unsuccessful answer, as the worker should take it. */
        private fun refusal(answer: BookOrbitHttp.Answer): ServerUploadResult {
            // The library went, or stopped being this account's. The
            // next attempt lists the libraries again.
            if (answer.code(ERROR_TARGET)) return ServerUploadResult.Failed("upload target")
            answer.body?.stringOrNull("errorCode")?.let { mapCode(it, answer.code) }?.let { return it }
            return when (answer.code) {
                403 -> ServerUploadResult.NotAllowed
                413 -> ServerUploadResult.TooLarge
                // Validation of a request this client built. Sending it
                // again will not make it valid.
                400, 422 -> ServerUploadResult.Rejected(null)
                else -> ServerUploadResult.Failed("HTTP ${answer.code}")
            }
        }

        private fun jsonBody(json: JSONObject): RequestBody =
            object : RequestBody() {
                private val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
                override fun contentType() = JSON_TYPE
                override fun contentLength() = bytes.size.toLong()
                override fun writeTo(sink: BufferedSink) {
                    sink.write(bytes)
                }
            }

        private fun sliceSha256(file: File, offset: Long, length: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(BUFFER)
                var left = length
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) throw IOException("file shorter than it was")
                    digest.update(buffer, 0, read)
                    left -= read
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256Of(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
