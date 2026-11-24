package com.chmouel.liseur.drive

import com.chmouel.liseur.data.remote.RemoteHttp
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

/** Base exception for Google Drive REST API failures. */
open class DriveApiException(
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
    open val retryAfterMs: Long? = null,
) : Exception(message, cause)

/** Thrown when the OAuth2 access token is rejected (HTTP 401 or insufficient Drive permissions). */
class DriveAuthExpiredException(
    message: String,
    httpCode: Int = 401,
    cause: Throwable? = null,
) : DriveApiException(message, httpCode, cause)

/** Thrown when Google Drive rejects a request due to rate limiting (429/403) or storage quota. */
class DriveQuotaException(
    message: String,
    httpCode: Int,
    override val retryAfterMs: Long? = null,
    val reason: String? = null,
    val isStorageQuota: Boolean = false,
) : DriveApiException(message, httpCode, null, retryAfterMs)

/** Thrown when a requested Drive file or resource does not exist (HTTP 404). */
class DriveNotFoundException(
    message: String,
    val fileId: String? = null,
) : DriveApiException(message, 404, null)

/** Thrown when `changes.list` rejects an expired or invalid `pageToken` (HTTP 400/404). */
class DriveInvalidPageTokenException(
    message: String,
    httpCode: Int,
) : DriveApiException(message, httpCode, null)

/** Thrown for other non-2xx Google Drive REST responses. */
class DriveHttpException(
    message: String,
    httpCode: Int,
    val reason: String? = null,
    override val retryAfterMs: Long? = null,
) : DriveApiException(message, httpCode, null, retryAfterMs)

/** Stable identity returned by `GET /drive/v3/about?fields=user(permissionId,emailAddress)`. */
data class DriveUserAbout(
    val permissionId: String,
    val emailAddress: String,
)

/** Metadata for a file or folder returned by Google Drive v3. */
data class DriveRemoteFile(
    val id: String,
    val name: String,
    val mimeType: String? = null,
    val parents: List<String> = emptyList(),
    val trashed: Boolean = false,
    val appProperties: Map<String, String> = emptyMap(),
    val size: Long? = null,
    val md5Checksum: String? = null,
    val createdTime: String? = null,
)

/** Result of resolving app-created `Liseur` folders in `spaces=drive`. */
data class LiseurFolderResolution(
    /** Canonical write target: lexicographically lowest `(createdTime, id)`. */
    val canonical: DriveRemoteFile,
    /** All visible app-created `Liseur` folders (ordered canonically) so reads never orphan files. */
    val allFolders: List<DriveRemoteFile>,
) {
    val allFolderIds: List<String>
        get() = allFolders.map { it.id }
}

/** One entry from `GET /drive/v3/changes`. */
data class DriveChange(
    val fileId: String,
    val removed: Boolean,
    val file: DriveRemoteFile?,
)

/** One page from `GET /drive/v3/changes`. */
data class DriveChangesPage(
    val changes: List<DriveChange>,
    val nextPageToken: String?,
    val newStartPageToken: String?,
)

/** Outcome of querying or advancing a Google Drive resumable upload session. */
sealed interface ResumableUploadStatus {
    data class InProgress(val nextByteOffset: Long) : ResumableUploadStatus
    data class Completed(val file: DriveRemoteFile) : ResumableUploadStatus
    data object Expired : ResumableUploadStatus
}

/** Result of a resumable (Range-aware) media download. */
data class DriveDownloadResult(
    val bytesWritten: Long,
    val totalBytes: Long?,
    val restartedFromZero: Boolean,
)

/** Narrow Drive REST interface so account management and sync engines can be tested with fakes. */
interface DriveRemoteApi {
    suspend fun getAbout(accessToken: String): DriveUserAbout
    suspend fun listLiseurFolders(accessToken: String): List<DriveRemoteFile>
    suspend fun ensureLiseurFolder(accessToken: String): LiseurFolderResolution
    suspend fun listDriveEpubFiles(accessToken: String): List<DriveRemoteFile> = emptyList()
    suspend fun getStartPageToken(accessToken: String): String
    suspend fun listChanges(accessToken: String, pageToken: String, pageSize: Int = 1000): DriveChangesPage
    suspend fun listAppDataFiles(
        accessToken: String,
        query: String? = null,
        pageSize: Int = 1000,
    ): List<DriveRemoteFile>
    suspend fun findAppDataFileByBatchId(accessToken: String, batchId: String): DriveRemoteFile?
    suspend fun downloadMedia(accessToken: String, fileId: String): ByteArray
    suspend fun uploadAppDataJsonBatch(
        accessToken: String,
        name: String,
        jsonPayload: ByteArray,
        appProperties: Map<String, String>,
    ): DriveRemoteFile
    suspend fun createAppDataFile(
        accessToken: String,
        name: String,
        mimeType: String,
        content: ByteArray,
        appProperties: Map<String, String> = emptyMap(),
    ): DriveRemoteFile
    suspend fun updateFileMedia(
        accessToken: String,
        fileId: String,
        mimeType: String,
        content: ByteArray,
        appProperties: Map<String, String>? = null,
    ): DriveRemoteFile
    suspend fun deleteFile(accessToken: String, fileId: String)
    suspend fun trashFile(accessToken: String, fileId: String): DriveRemoteFile
    suspend fun initiateResumableUpload(
        accessToken: String,
        name: String,
        mimeType: String,
        totalBytes: Long,
        parents: List<String>,
        appProperties: Map<String, String> = emptyMap(),
    ): String
    suspend fun queryResumableUploadOffset(
        accessToken: String,
        sessionUri: String,
        totalBytes: Long,
    ): ResumableUploadStatus
    suspend fun uploadResumableChunk(
        accessToken: String,
        sessionUri: String,
        chunk: ByteArray,
        offset: Long,
        totalBytes: Long,
        mimeType: String,
    ): ResumableUploadStatus
    suspend fun uploadResumableFile(
        accessToken: String,
        name: String,
        mimeType: String,
        totalBytes: Long,
        parents: List<String>,
        appProperties: Map<String, String> = emptyMap(),
        existingSessionUri: String? = null,
        chunkSizeBytes: Int = DEFAULT_RESUMABLE_CHUNK_BYTES,
        openStream: () -> InputStream,
        onSessionUri: suspend (String) -> Unit = {},
        onProgress: suspend (bytesSent: Long, sessionUri: String) -> Unit = { _, _ -> },
    ): DriveRemoteFile
    /**
     * Downloads [fileId] into [destination], resuming at [startOffset]. When Drive ignores
     * the `Range` header and sends the whole file, a [FileOutputStream] destination is
     * truncated and rewound before writing; for any other stream [onRestartFromZero] must
     * discard the bytes already written.
     */
    suspend fun downloadResumable(
        accessToken: String,
        fileId: String,
        destination: OutputStream,
        startOffset: Long = 0L,
        onRestartFromZero: () -> Unit,
        onProgress: (bytesWrittenTotal: Long) -> Unit = {},
    ): DriveDownloadResult

    companion object {
        const val DEFAULT_RESUMABLE_CHUNK_BYTES = 1024 * 1024 // 1 MiB (multiple of 256 KiB)
    }
}

/**
 * OkHttp-backed Google Drive v3 REST client for Liseur's Play build (ADR-0078).
 *
 * Redirects are disabled so Bearer access tokens are never forwarded to
 * unintended hosts, and OkHttp's own connection retries are off so a create is
 * never re-sent behind the caller's back. Transient rate limits (`429`,
 * rate-limit `403`) and `5xx` errors are retried with exponential backoff
 * honoring `Retry-After`; a `5xx` is retried only for idempotent requests,
 * because for a create it may hide a write that already committed.
 */
class DriveRestClient(
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val uploadBaseUrl: String = DEFAULT_UPLOAD_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val baseBackoffMs: Long = DEFAULT_BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitterFactor: () -> Double = { Random.nextDouble(0.75, 1.25) },
) : DriveRemoteApi {

    override suspend fun getAbout(accessToken: String): DriveUserAbout {
        val url = apiUrl("about")
            .addQueryParameter("fields", "user(permissionId,emailAddress)")
            .build()
        val body = executeWithRetry(idempotent = true) {
            Request.Builder()
                .url(url)
                .header("Authorization", bearer(accessToken))
                .get()
                .build()
        }
        val json = parseJsonObject(body, "about")
        val user = json.optJSONObject("user")
            ?: throw DriveApiException("Google Drive about response missing user object")
        val permissionId = user.optString("permissionId").trim()
        if (permissionId.isEmpty()) {
            throw DriveApiException("Google Drive about response missing user.permissionId")
        }
        val email = user.optString("emailAddress").trim()
        return DriveUserAbout(permissionId = permissionId, emailAddress = email)
    }

    override suspend fun listLiseurFolders(accessToken: String): List<DriveRemoteFile> {
        val query = "name = '$LISEUR_FOLDER_NAME' and mimeType = '$FOLDER_MIME_TYPE' and " +
            "appProperties has { key='liseur' and value='1' } and " +
            "appProperties has { key='kind' and value='${DriveFile.KIND_FOLDER}' } and trashed = false"
        val folders = listFiles(
            accessToken = accessToken,
            spaces = SPACE_DRIVE,
            query = query,
            pageSize = 1000,
        )
        return folders.sortedWith(CANONICAL_FOLDER_ORDER)
    }

    override suspend fun ensureLiseurFolder(accessToken: String): LiseurFolderResolution {
        val existing = listLiseurFolders(accessToken)
        if (existing.isNotEmpty()) {
            return LiseurFolderResolution(canonical = existing.first(), allFolders = existing)
        }

        val metadata = JSONObject()
            .put("name", LISEUR_FOLDER_NAME)
            .put("mimeType", FOLDER_MIME_TYPE)
            .put(
                "appProperties",
                JSONObject()
                    .put("liseur", "1")
                    .put("kind", DriveFile.KIND_FOLDER)
                    .put("schemaVersion", "1"),
            )
        val createUrl = apiUrl("files")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val createdBody = executeWithRetry(idempotent = false) {
            Request.Builder()
                .url(createUrl)
                .header("Authorization", bearer(accessToken))
                .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE).oneShot())
                .build()
        }
        val created = parseDriveFile(parseJsonObject(createdBody, "createFolder"))

        // Re-list immediately so if two devices raced to create `Liseur/`, both
        // converge deterministically on the oldest `(createdTime, id)` and keep
        // every folder ID available for reads.
        val afterCreate = listLiseurFolders(accessToken)
        val merged = LinkedHashMap<String, DriveRemoteFile>()
        afterCreate.forEach { merged[it.id] = it }
        merged.putIfAbsent(created.id, created)
        val sorted = merged.values.sortedWith(CANONICAL_FOLDER_ORDER)
        return LiseurFolderResolution(canonical = sorted.first(), allFolders = sorted)
    }

    override suspend fun listDriveEpubFiles(accessToken: String): List<DriveRemoteFile> {
        val query = "appProperties has { key='liseurKind' and value='epub' } and trashed = false"
        return listFiles(
            accessToken = accessToken,
            spaces = SPACE_DRIVE,
            query = query,
            pageSize = 1000,
        )
    }

    override suspend fun getStartPageToken(accessToken: String): String {
        // Drive v3 changes/startPageToken intentionally takes NO `spaces` parameter.
        val url = apiUrl("changes", "startPageToken").build()
        val body = executeWithRetry(idempotent = true) {
            Request.Builder()
                .url(url)
                .header("Authorization", bearer(accessToken))
                .get()
                .build()
        }
        val token = parseJsonObject(body, "startPageToken").optString("startPageToken").trim()
        if (token.isEmpty()) {
            throw DriveApiException("Google Drive startPageToken response missing startPageToken")
        }
        return token
    }

    override suspend fun listChanges(
        accessToken: String,
        pageToken: String,
        pageSize: Int,
    ): DriveChangesPage {
        require(pageToken.isNotBlank()) { "pageToken must not be blank" }
        val url = apiUrl("changes")
            .addQueryParameter("pageToken", pageToken)
            .addQueryParameter("spaces", "$SPACE_APPDATA,$SPACE_DRIVE")
            .addQueryParameter("includeRemoved", "true")
            .addQueryParameter("pageSize", pageSize.coerceIn(1, 1000).toString())
            .addQueryParameter("fields", CHANGES_FIELDS)
            .build()
        val body = executeWithRetry(
            idempotent = true,
            treat400And404AsInvalidPageToken = true,
        ) {
            Request.Builder()
                .url(url)
                .header("Authorization", bearer(accessToken))
                .get()
                .build()
        }
        val json = parseJsonObject(body, "changes.list")
        val changesArray = json.optJSONArray("changes") ?: JSONArray()
        val changes = buildList(changesArray.length()) {
            for (i in 0 until changesArray.length()) {
                val item = changesArray.optJSONObject(i) ?: continue
                val fileId = item.optString("fileId").trim()
                if (fileId.isEmpty()) continue
                val removed = item.optBoolean("removed", false)
                val fileObj = item.optJSONObject("file")?.let(::parseDriveFile)
                add(DriveChange(fileId = fileId, removed = removed, file = fileObj))
            }
        }
        val nextPageToken = json.optString("nextPageToken").trim().takeIf { it.isNotEmpty() }
        val newStartPageToken = json.optString("newStartPageToken").trim().takeIf { it.isNotEmpty() }
        return DriveChangesPage(
            changes = changes,
            nextPageToken = nextPageToken,
            newStartPageToken = newStartPageToken,
        )
    }

    override suspend fun listAppDataFiles(
        accessToken: String,
        query: String?,
        pageSize: Int,
    ): List<DriveRemoteFile> = listFiles(
        accessToken = accessToken,
        spaces = SPACE_APPDATA,
        query = query,
        pageSize = pageSize,
    )

    override suspend fun findAppDataFileByBatchId(
        accessToken: String,
        batchId: String,
    ): DriveRemoteFile? {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        val escaped = escapeDriveQueryLiteral(batchId)
        val q = "appProperties has { key='batchId' and value='$escaped' } and trashed = false"
        return listAppDataFiles(accessToken = accessToken, query = q, pageSize = 10)
            .sortedWith(CANONICAL_FOLDER_ORDER)
            .firstOrNull()
    }

    override suspend fun downloadMedia(accessToken: String, fileId: String): ByteArray {
        val url = apiUrl("files", fileId)
            .addQueryParameter("alt", "media")
            .build()
        return executeBytesWithRetry(idempotent = true, fileId = fileId) {
            Request.Builder()
                .url(url)
                .header("Authorization", bearer(accessToken))
                .get()
                .build()
        }
    }

    override suspend fun uploadAppDataJsonBatch(
        accessToken: String,
        name: String,
        jsonPayload: ByteArray,
        appProperties: Map<String, String>,
    ): DriveRemoteFile {
        validateAppProperties(appProperties)
        val batchId = appProperties["batchId"]?.takeIf { it.isNotBlank() }

        // Idempotent pre-check: if a previous attempt already landed on Drive, reuse its fileId.
        if (batchId != null) {
            findAppDataFileByBatchId(accessToken, batchId)?.let { return it }
        }

        val metadata = buildFileMetadataJson(
            name = name,
            parents = listOf(SPACE_APPDATA),
            mimeType = APPLICATION_JSON,
            appProperties = appProperties,
        )
        val uploadUrl = uploadUrl("files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()

        var attempt = 0
        while (true) {
            val multipart = buildMultipartRelatedBody(
                metadataJson = metadata.toString(),
                mediaMimeType = APPLICATION_JSON,
                mediaBytes = jsonPayload,
            )
            val request = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", bearer(accessToken))
                .post(multipart.bytes.toRequestBody("multipart/related; boundary=${multipart.boundary}".toMediaType()).oneShot())
                .build()

            try {
                return when (val outcome = executeOnce(request, fileId = null, treat400And404AsInvalidPageToken = false)) {
                    is RawOutcome.Success -> parseDriveFile(parseJsonObject(outcome.body.toString(Charsets.UTF_8), "uploadBatch"))
                    is RawOutcome.Retryable -> {
                        // Without a batchId a 5xx cannot be reconciled, and may hide a
                        // committed create; rate limiting committed nothing.
                        if ((batchId == null && !outcome.isRateLimited) ||
                            attempt >= maxRetries ||
                            shouldDeferRetry(outcome.retryAfterMs)
                        ) {
                            throw outcome.toFinalException()
                        }
                        sleep(computeBackoffMs(attempt, outcome.retryAfterMs))
                        attempt++
                        // Before retrying POST, verify Drive did not commit the batch on the failed attempt.
                        if (batchId != null) {
                            findAppDataFileByBatchId(accessToken, batchId)?.let { return it }
                        }
                        continue
                    }
                    is RawOutcome.Fatal -> throw outcome.exception
                }
            } catch (e: IOException) {
                if (attempt >= maxRetries || batchId == null) {
                    throw DriveApiException("Google Drive batch upload failed: ${e.message}", cause = e)
                }
                sleep(computeBackoffMs(attempt, retryAfterMs = null))
                attempt++
                findAppDataFileByBatchId(accessToken, batchId)?.let { return it }
            }
        }
    }

    override suspend fun createAppDataFile(
        accessToken: String,
        name: String,
        mimeType: String,
        content: ByteArray,
        appProperties: Map<String, String>,
    ): DriveRemoteFile {
        validateAppProperties(appProperties)
        val metadata = buildFileMetadataJson(
            name = name,
            parents = listOf(SPACE_APPDATA),
            mimeType = mimeType,
            appProperties = appProperties,
        )
        val uploadUrl = uploadUrl("files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val body = executeWithRetry(idempotent = false) {
            val multipart = buildMultipartRelatedBody(
                metadataJson = metadata.toString(),
                mediaMimeType = mimeType,
                mediaBytes = content,
            )
            Request.Builder()
                .url(uploadUrl)
                .header("Authorization", bearer(accessToken))
                .post(multipart.bytes.toRequestBody("multipart/related; boundary=${multipart.boundary}".toMediaType()).oneShot())
                .build()
        }
        return parseDriveFile(parseJsonObject(body, "createAppDataFile"))
    }

    override suspend fun updateFileMedia(
        accessToken: String,
        fileId: String,
        mimeType: String,
        content: ByteArray,
        appProperties: Map<String, String>?,
    ): DriveRemoteFile {
        if (appProperties != null) validateAppProperties(appProperties)
        val body = if (appProperties != null) {
            val metadata = JSONObject().put("appProperties", JSONObject(appProperties))
            val url = uploadUrl("files", fileId)
                .addQueryParameter("uploadType", "multipart")
                .addQueryParameter("fields", FILE_FIELDS)
                .build()
            executeWithRetry(idempotent = true, fileId = fileId) {
                val multipart = buildMultipartRelatedBody(
                    metadataJson = metadata.toString(),
                    mediaMimeType = mimeType,
                    mediaBytes = content,
                )
                Request.Builder()
                    .url(url)
                    .header("Authorization", bearer(accessToken))
                    .patch(multipart.bytes.toRequestBody("multipart/related; boundary=${multipart.boundary}".toMediaType()))
                    .build()
            }
        } else {
            val url = uploadUrl("files", fileId)
                .addQueryParameter("uploadType", "media")
                .addQueryParameter("fields", FILE_FIELDS)
                .build()
            executeWithRetry(idempotent = true, fileId = fileId) {
                Request.Builder()
                    .url(url)
                    .header("Authorization", bearer(accessToken))
                    .patch(content.toRequestBody(mimeType.toMediaType()))
                    .build()
            }
        }
        return parseDriveFile(parseJsonObject(body, "updateFileMedia"))
    }

    override suspend fun deleteFile(accessToken: String, fileId: String) {
        val url = apiUrl("files", fileId).build()
        executeBytesWithRetry(idempotent = true, fileId = fileId) {
            Request.Builder()
                .url(url)
                .header("Authorization", bearer(accessToken))
                .delete()
                .build()
        }
    }

    override suspend fun trashFile(accessToken: String, fileId: String): DriveRemoteFile {
        val url = apiUrl("files", fileId)
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val payload = JSONObject().put("trashed", true).toString()
        val body = executeWithRetry(idempotent = true, fileId = fileId) {
            Request.Builder()
                .url(url)
                .header("Authorization", bearer(accessToken))
                .patch(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }
        return parseDriveFile(parseJsonObject(body, "trashFile"))
    }

    override suspend fun initiateResumableUpload(
        accessToken: String,
        name: String,
        mimeType: String,
        totalBytes: Long,
        parents: List<String>,
        appProperties: Map<String, String>,
    ): String {
        require(totalBytes >= 0L) { "totalBytes must be non-negative" }
        validateAppProperties(appProperties)
        val metadata = buildFileMetadataJson(
            name = name,
            parents = parents,
            mimeType = mimeType,
            appProperties = appProperties,
        )
        val url = uploadUrl("files")
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()

        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", bearer(accessToken))
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", totalBytes.toString())
                .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = try {
                executeCall(request)
            } catch (e: IOException) {
                throw DriveApiException("Failed to initiate Drive resumable upload: ${e.message}", cause = e)
            }
            response.use { res ->
                if (res.isSuccessful) {
                    val location = res.header("Location")?.trim().orEmpty()
                    if (location.isEmpty()) {
                        throw DriveApiException("Google Drive resumable upload response missing Location header")
                    }
                    return location
                }
                when (val error = classifyErrorResponse(res, fileId = null, treat400And404AsInvalidPageToken = false)) {
                    is RawOutcome.Retryable -> {
                        if (attempt >= maxRetries || shouldDeferRetry(error.retryAfterMs)) {
                            throw error.toFinalException()
                        }
                        sleep(computeBackoffMs(attempt, error.retryAfterMs))
                        attempt++
                    }
                    is RawOutcome.Fatal -> throw error.exception
                    is RawOutcome.Success -> error("unreachable")
                }
            }
        }
    }

    override suspend fun queryResumableUploadOffset(
        accessToken: String,
        sessionUri: String,
        totalBytes: Long,
    ): ResumableUploadStatus {
        require(sessionUri.isNotBlank()) { "sessionUri must not be blank" }
        val contentRange = if (totalBytes > 0L) "bytes */$totalBytes" else "bytes */*"
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(sessionUri)
                .header("Authorization", bearer(accessToken))
                .header("Content-Range", contentRange)
                .put(ByteArray(0).toRequestBody(null))
                .build()
            val response = try {
                executeCall(request)
            } catch (e: IOException) {
                if (attempt >= maxRetries) {
                    throw DriveApiException("Failed to query resumable upload offset: ${e.message}", cause = e)
                }
                sleep(computeBackoffMs(attempt, retryAfterMs = null))
                attempt++
                continue
            }
            response.use { res ->
                when (res.code) {
                    HTTP_RESUME_INCOMPLETE -> {
                        val nextOffset = parseUploadRangeNextOffset(res.header("Range")) ?: 0L
                        return ResumableUploadStatus.InProgress(nextOffset)
                    }
                    200, 201 -> {
                        val body = res.body.string()
                        return ResumableUploadStatus.Completed(parseDriveFile(parseJsonObject(body, "resumableStatus")))
                    }
                    404, 410 -> return ResumableUploadStatus.Expired
                    else -> {
                        when (val error = classifyErrorResponse(res, fileId = null, treat400And404AsInvalidPageToken = false)) {
                            is RawOutcome.Retryable -> {
                                if (attempt >= maxRetries || shouldDeferRetry(error.retryAfterMs)) {
                                    throw error.toFinalException()
                                }
                                sleep(computeBackoffMs(attempt, error.retryAfterMs))
                                attempt++
                            }
                            is RawOutcome.Fatal -> throw error.exception
                            is RawOutcome.Success -> error("unreachable")
                        }
                    }
                }
            }
        }
    }

    override suspend fun uploadResumableChunk(
        accessToken: String,
        sessionUri: String,
        chunk: ByteArray,
        offset: Long,
        totalBytes: Long,
        mimeType: String,
    ): ResumableUploadStatus {
        require(sessionUri.isNotBlank()) { "sessionUri must not be blank" }
        require(offset >= 0L) { "offset must be non-negative" }
        val builder = Request.Builder()
            .url(sessionUri)
            .header("Authorization", bearer(accessToken))
            .put(chunk.toRequestBody(mimeType.toMediaType()))
        if (totalBytes > 0L && chunk.isNotEmpty()) {
            val end = offset + chunk.size - 1L
            builder.header("Content-Range", "bytes $offset-$end/$totalBytes")
        }
        val request = builder.build()

        val response = try {
            executeCall(request)
        } catch (e: IOException) {
            throw DriveApiException("Resumable chunk upload failed: ${e.message}", cause = e)
        }
        response.use { res ->
            return when (res.code) {
                HTTP_RESUME_INCOMPLETE -> {
                    val nextOffset = parseUploadRangeNextOffset(res.header("Range")) ?: 0L
                    ResumableUploadStatus.InProgress(nextOffset)
                }
                200, 201 -> {
                    val body = res.body.string()
                    ResumableUploadStatus.Completed(parseDriveFile(parseJsonObject(body, "resumableComplete")))
                }
                404, 410 -> ResumableUploadStatus.Expired
                else -> {
                    when (val error = classifyErrorResponse(res, fileId = null, treat400And404AsInvalidPageToken = false)) {
                        is RawOutcome.Retryable -> throw error.toFinalException()
                        is RawOutcome.Fatal -> throw error.exception
                        is RawOutcome.Success -> error("unreachable")
                    }
                }
            }
        }
    }

    override suspend fun uploadResumableFile(
        accessToken: String,
        name: String,
        mimeType: String,
        totalBytes: Long,
        parents: List<String>,
        appProperties: Map<String, String>,
        existingSessionUri: String?,
        chunkSizeBytes: Int,
        openStream: () -> InputStream,
        onSessionUri: suspend (String) -> Unit,
        onProgress: suspend (bytesSent: Long, sessionUri: String) -> Unit,
    ): DriveRemoteFile {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be positive" }
        var sessionUri = existingSessionUri?.takeIf { it.isNotBlank() }
        var offset = 0L
        var restartedExpiredSession = false

        if (sessionUri != null) {
            when (val status = queryResumableUploadOffset(accessToken, sessionUri, totalBytes)) {
                is ResumableUploadStatus.Completed -> return status.file
                is ResumableUploadStatus.InProgress -> {
                    offset = status.nextByteOffset.coerceIn(0L, totalBytes)
                }
                ResumableUploadStatus.Expired -> {
                    sessionUri = null
                    offset = 0L
                }
            }
        }

        if (sessionUri == null) {
            sessionUri = initiateResumableUpload(
                accessToken = accessToken,
                name = name,
                mimeType = mimeType,
                totalBytes = totalBytes,
                parents = parents,
                appProperties = appProperties,
            )
            onSessionUri(sessionUri)
        }

        if (totalBytes == 0L) {
            val status = uploadResumableChunk(
                accessToken = accessToken,
                sessionUri = sessionUri,
                chunk = ByteArray(0),
                offset = 0L,
                totalBytes = 0L,
                mimeType = mimeType,
            )
            return when (status) {
                is ResumableUploadStatus.Completed -> {
                    onProgress(0L, sessionUri)
                    status.file
                }
                else -> throw DriveApiException("Zero-byte resumable upload did not complete: $status")
            }
        }

        val chunks = ChunkSource(openStream)
        try {
            var attempt = 0
            var highWaterOffset = offset
            while (true) {
                val activeUri = requireNotNull(sessionUri)
                val chunk = chunks.read(offset, min(chunkSizeBytes.toLong(), totalBytes - offset).toInt())
                val status = try {
                    uploadResumableChunk(
                        accessToken = accessToken,
                        sessionUri = activeUri,
                        chunk = chunk,
                        offset = offset,
                        totalBytes = totalBytes,
                        mimeType = mimeType,
                    )
                } catch (e: DriveQuotaException) {
                    val rateLimited = e.httpCode == 429 || e.reason in RETRYABLE_403_RATE_LIMIT_REASONS
                    if (!rateLimited || attempt >= maxRetries || shouldDeferRetry(e.retryAfterMs)) throw e
                    sleep(computeBackoffMs(attempt, e.retryAfterMs))
                    attempt++
                    when (val probed = queryResumableUploadOffset(accessToken, activeUri, totalBytes)) {
                        is ResumableUploadStatus.Completed -> return probed.file
                        is ResumableUploadStatus.InProgress -> {
                            offset = probed.nextByteOffset.coerceIn(0L, totalBytes)
                            if (offset > highWaterOffset) {
                                highWaterOffset = offset
                                attempt = 0
                            }
                            continue
                        }
                        ResumableUploadStatus.Expired -> ResumableUploadStatus.Expired
                    }
                } catch (e: DriveApiException) {
                    val retryableHttp = e is DriveHttpException && (
                        (e.httpCode != null && e.httpCode in 500..599) ||
                            (e.httpCode == 403 && e.reason in RETRYABLE_403_BACKEND_REASONS)
                        )
                    val retryable = retryableHttp || (e.cause is IOException)
                    val retryAfterMs = e.retryAfterMs
                    if (!retryable || attempt >= maxRetries || shouldDeferRetry(retryAfterMs)) throw e
                    sleep(computeBackoffMs(attempt, retryAfterMs = retryAfterMs))
                    attempt++
                    when (val probed = queryResumableUploadOffset(accessToken, activeUri, totalBytes)) {
                        is ResumableUploadStatus.Completed -> return probed.file
                        is ResumableUploadStatus.InProgress -> {
                            offset = probed.nextByteOffset.coerceIn(0L, totalBytes)
                            if (offset > highWaterOffset) {
                                highWaterOffset = offset
                                attempt = 0
                            }
                            continue
                        }
                        ResumableUploadStatus.Expired -> ResumableUploadStatus.Expired
                    }
                }

                when (status) {
                    is ResumableUploadStatus.Completed -> {
                        onProgress(totalBytes, activeUri)
                        return status.file
                    }
                    is ResumableUploadStatus.InProgress -> {
                        val nextOffset = status.nextByteOffset.coerceIn(0L, totalBytes)
                        if (nextOffset <= offset) {
                            if (attempt >= maxRetries) {
                                throw DriveApiException(
                                    "Google Drive resumable upload made no progress (offset=$offset, acknowledged=$nextOffset)",
                                )
                            }
                            sleep(computeBackoffMs(attempt, retryAfterMs = null))
                            attempt++
                            offset = nextOffset
                        } else if (nextOffset > highWaterOffset) {
                            highWaterOffset = nextOffset
                            attempt = 0
                            offset = nextOffset
                            onProgress(offset, activeUri)
                        } else {
                            // Replaying bytes after a rewind: advance offset without resetting retry budget.
                            offset = nextOffset
                        }
                    }
                    ResumableUploadStatus.Expired -> {
                        if (restartedExpiredSession) {
                            throw DriveApiException("Google Drive resumable upload session expired repeatedly")
                        }
                        restartedExpiredSession = true
                        attempt = 0
                        offset = 0L
                        highWaterOffset = 0L
                        val freshUri = initiateResumableUpload(
                            accessToken = accessToken,
                            name = name,
                            mimeType = mimeType,
                            totalBytes = totalBytes,
                            parents = parents,
                            appProperties = appProperties,
                        )
                        sessionUri = freshUri
                        onSessionUri(freshUri)
                    }
                }
            }
        } catch (e: Throwable) {
            chunks.close(e)
            throw e
        } finally {
            chunks.close()
        }
    }

    override suspend fun downloadResumable(
        accessToken: String,
        fileId: String,
        destination: OutputStream,
        startOffset: Long,
        onRestartFromZero: () -> Unit,
        onProgress: (bytesWrittenTotal: Long) -> Unit,
    ): DriveDownloadResult {
        require(startOffset >= 0L) { "startOffset must be non-negative" }
        val url = apiUrl("files", fileId)
            .addQueryParameter("alt", "media")
            .build()

        var attempt = 0
        while (true) {
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", bearer(accessToken))
                .get()
            if (startOffset > 0L) {
                builder.header("Range", "bytes=$startOffset-")
            }
            val response = try {
                executeCall(builder.build(), streamSuccessBody = true)
            } catch (e: IOException) {
                if (attempt >= maxRetries) {
                    throw DriveApiException("Google Drive download failed: ${e.message}", cause = e)
                }
                sleep(computeBackoffMs(attempt, retryAfterMs = null))
                attempt++
                continue
            }
            response.use { res ->
                when (res.code) {
                    206 -> {
                        val range = parseDownloadContentRange(res.header("Content-Range"))
                        if (range != null && range.start != startOffset) {
                            throw DriveApiException(
                                "Unexpected Content-Range start ${range.start} (expected $startOffset)",
                                httpCode = 206,
                            )
                        }
                        val written = copyBody(res, destination, startOffset, onProgress)
                        return DriveDownloadResult(
                            bytesWritten = written,
                            totalBytes = range?.total,
                            restartedFromZero = false,
                        )
                    }
                    200 -> {
                        val restarted = startOffset > 0L
                        if (restarted) {
                            if (destination is FileOutputStream) {
                                withContext(Dispatchers.IO) {
                                    destination.channel.truncate(0L)
                                    destination.channel.position(0L)
                                }
                            }
                            onRestartFromZero()
                        }
                        val contentLength = res.header("Content-Length")?.toLongOrNull()
                        val written = copyBody(res, destination, baseOffset = 0L, onProgress = onProgress)
                        return DriveDownloadResult(
                            bytesWritten = written,
                            totalBytes = contentLength ?: written,
                            restartedFromZero = restarted,
                        )
                    }
                    416 -> {
                        val total = parseUnsatisfiableRangeTotal(res.header("Content-Range"))
                        if (total != null && startOffset == total) {
                            return DriveDownloadResult(
                                bytesWritten = 0L,
                                totalBytes = total,
                                restartedFromZero = false,
                            )
                        }
                        throw DriveHttpException(
                            "Range not satisfiable for Drive file $fileId (offset=$startOffset)",
                            httpCode = 416,
                        )
                    }
                    else -> {
                        when (val error = classifyErrorResponse(res, fileId = fileId, treat400And404AsInvalidPageToken = false)) {
                            is RawOutcome.Retryable -> {
                                if (attempt >= maxRetries || shouldDeferRetry(error.retryAfterMs)) {
                                    throw error.toFinalException()
                                }
                                sleep(computeBackoffMs(attempt, error.retryAfterMs))
                                attempt++
                            }
                            is RawOutcome.Fatal -> throw error.exception
                            is RawOutcome.Success -> error("unreachable")
                        }
                    }
                }
            }
        }
    }

    private suspend fun listFiles(
        accessToken: String,
        spaces: String,
        query: String?,
        pageSize: Int,
    ): List<DriveRemoteFile> {
        val results = mutableListOf<DriveRemoteFile>()
        var pageToken: String? = null
        do {
            val urlBuilder = apiUrl("files")
                .addQueryParameter("spaces", spaces)
                .addQueryParameter("pageSize", pageSize.coerceIn(1, 1000).toString())
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
            if (!query.isNullOrBlank()) {
                urlBuilder.addQueryParameter("q", query)
            }
            if (pageToken != null) {
                urlBuilder.addQueryParameter("pageToken", pageToken)
            }
            val url = urlBuilder.build()
            val body = executeWithRetry(idempotent = true) {
                Request.Builder()
                    .url(url)
                    .header("Authorization", bearer(accessToken))
                    .get()
                    .build()
            }
            val json = parseJsonObject(body, "files.list")
            val filesArray = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until filesArray.length()) {
                filesArray.optJSONObject(i)?.let { results.add(parseDriveFile(it)) }
            }
            pageToken = json.optString("nextPageToken").trim().takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return results
    }

    private suspend fun executeWithRetry(
        idempotent: Boolean,
        fileId: String? = null,
        treat400And404AsInvalidPageToken: Boolean = false,
        buildRequest: () -> Request,
    ): String = executeBytesWithRetry(
        idempotent = idempotent,
        fileId = fileId,
        treat400And404AsInvalidPageToken = treat400And404AsInvalidPageToken,
        buildRequest = buildRequest,
    ).toString(Charsets.UTF_8)

    private suspend fun executeBytesWithRetry(
        idempotent: Boolean,
        fileId: String? = null,
        treat400And404AsInvalidPageToken: Boolean = false,
        buildRequest: () -> Request,
    ): ByteArray {
        var attempt = 0
        while (true) {
            val request = buildRequest()
            try {
                when (val outcome = executeOnce(request, fileId, treat400And404AsInvalidPageToken)) {
                    is RawOutcome.Success -> return outcome.body
                    is RawOutcome.Retryable -> {
                        // A 5xx answering a non-idempotent create may hide a write that
                        // already committed, so it goes back to the caller to reconcile
                        // instead of posting a duplicate. Rate limiting committed nothing.
                        if ((!idempotent && !outcome.isRateLimited) ||
                            attempt >= maxRetries ||
                            shouldDeferRetry(outcome.retryAfterMs)
                        ) {
                            throw outcome.toFinalException()
                        }
                        sleep(computeBackoffMs(attempt, outcome.retryAfterMs))
                        attempt++
                    }
                    is RawOutcome.Fatal -> throw outcome.exception
                }
            } catch (e: IOException) {
                if (!idempotent || attempt >= maxRetries) {
                    throw DriveApiException("Google Drive request failed: ${e.message}", cause = e)
                }
                sleep(computeBackoffMs(attempt, retryAfterMs = null))
                attempt++
            }
        }
    }

    private suspend fun executeOnce(
        request: Request,
        fileId: String?,
        treat400And404AsInvalidPageToken: Boolean,
    ): RawOutcome {
        val response = executeCall(request)
        response.use { res ->
            if (res.isSuccessful) {
                return RawOutcome.Success(res.body.bytes())
            }
            return classifyErrorResponse(res, fileId, treat400And404AsInvalidPageToken)
        }
    }

    /**
     * Executes [request] on [Dispatchers.IO] and, unless [streamSuccessBody] is set for a
     * successful response, reads the whole body there too (capped at
     * [MAX_BUFFERED_BODY_BYTES]), so callers never touch the network from their own
     * dispatcher and a hostile file cannot exhaust the heap.
     */
    private suspend fun executeCall(
        request: Request,
        streamSuccessBody: Boolean = false,
    ): Response {
        // A cancelled caller can drop the value `withContext` returns, so the
        // streaming response stays reachable here until the hand-off completes.
        var undelivered: Response? = null
        try {
            val result = withContext(Dispatchers.IO) {
                executeCallBlocking(request, streamSuccessBody) { undelivered = it }
            }
            undelivered = null
            return result
        } finally {
            undelivered?.close()
        }
    }

    private fun executeCallBlocking(
        request: Request,
        streamSuccessBody: Boolean,
        onStreaming: (Response) -> Unit,
    ): Response {
        val response = client.newCall(request).execute()
        if (streamSuccessBody && response.isSuccessful) {
            onStreaming(response)
            return response
        }
        return response.use { res ->
            val declared = res.body.contentLength()
            if (declared > MAX_BUFFERED_BODY_BYTES) throw oversizedBody(request, declared)
            val buffer = ByteArrayOutputStream()
            res.body.byteStream().use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    if (buffer.size() + read > MAX_BUFFERED_BODY_BYTES) {
                        throw oversizedBody(request, buffer.size().toLong() + read)
                    }
                    buffer.write(chunk, 0, read)
                }
            }
            res.newBuilder()
                .body(buffer.toByteArray().toResponseBody(res.body.contentType()))
                .build()
        }
    }

    private fun oversizedBody(request: Request, bytes: Long) = DriveApiException(
        "Google Drive response for ${request.url.encodedPath} exceeds $MAX_BUFFERED_BODY_BYTES bytes ($bytes)",
    )

    private suspend fun copyBody(
        response: Response,
        destination: OutputStream,
        baseOffset: Long,
        onProgress: (Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val stream = response.body.byteStream()
        val buffer = ByteArray(16 * 1024)
        var written = 0L
        stream.use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    currentCoroutineContext().ensureActive()
                    destination.write(buffer, 0, read)
                    written += read
                    onProgress(baseOffset + written)
                }
            }
            destination.flush()
        }
        written
    }

    /**
     * Reads upload chunks from one open stream while they are requested in
     * order, and reopens and skips forward only when the offset moves (a
     * retry of the same chunk, a server-reported rewind or a restart).
     */
    private class ChunkSource(private val openStream: () -> InputStream) {
        private var input: InputStream? = null
        private var position = 0L

        suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
            try {
                val stream = input?.takeIf { position == offset } ?: reopenAt(offset)
                val buffer = ByteArray(length)
                var filled = 0
                while (filled < length) {
                    val read = stream.read(buffer, filled, length - filled)
                    if (read < 0) break
                    filled += read
                }
                position = offset + filled
                if (filled == length) buffer else buffer.copyOf(filled)
            } catch (e: Throwable) {
                closeStream(e)
                throw e
            }
        }

        /** Closes on IO even when cancelled; a failing close never replaces [primary] or the result. */
        suspend fun close(primary: Throwable? = null) {
            withContext(Dispatchers.IO + NonCancellable) { closeStream(primary) }
        }

        private fun closeStream(primary: Throwable?) {
            val stream = input
            input = null
            try {
                stream?.close()
            } catch (e: Exception) {
                if (primary != null && primary !== e) primary.addSuppressed(e)
            }
        }

        private fun reopenAt(offset: Long): InputStream {
            closeStream(null)
            val stream = openStream()
            input = stream
            position = 0L
            var remainingSkip = offset
            val skipBuffer = ByteArray(8192)
            while (remainingSkip > 0L) {
                val skipped = stream.skip(remainingSkip)
                if (skipped > 0L) {
                    remainingSkip -= skipped
                } else {
                    val toRead = min(skipBuffer.size.toLong(), remainingSkip).toInt()
                    val read = stream.read(skipBuffer, 0, toRead)
                    if (read < 0) {
                        throw DriveApiException("Upload stream ended before offset $offset")
                    }
                    remainingSkip -= read
                }
            }
            position = offset
            return stream
        }
    }

    private fun classifyErrorResponse(
        response: Response,
        fileId: String?,
        treat400And404AsInvalidPageToken: Boolean,
    ): RawOutcome {
        val code = response.code
        val retryAfterMs = parseRetryAfterMs(response.header("Retry-After"), clock())
        val errorBody = runCatching { response.body.string() }.getOrDefault("")
        val parsed = parseGoogleError(errorBody)
        val reason = parsed.reason
        val message = parsed.message ?: "Google Drive HTTP $code"

        if (code == 401) {
            return RawOutcome.Fatal(DriveAuthExpiredException(message, httpCode = 401))
        }
        if (treat400And404AsInvalidPageToken && (code == 400 || code == 404)) {
            return RawOutcome.Fatal(DriveInvalidPageTokenException(message, httpCode = code))
        }
        if (code == 404) {
            return RawOutcome.Fatal(DriveNotFoundException(message, fileId = fileId))
        }
        if (code == 429) {
            return RawOutcome.Retryable(
                httpCode = 429,
                message = message,
                reason = reason,
                retryAfterMs = retryAfterMs,
                isQuota = true,
            )
        }
        if (code == 403) {
            return when (reason) {
                in RETRYABLE_403_RATE_LIMIT_REASONS -> RawOutcome.Retryable(
                    httpCode = 403,
                    message = message,
                    reason = reason,
                    retryAfterMs = retryAfterMs,
                    isQuota = true,
                )
                in RETRYABLE_403_BACKEND_REASONS -> RawOutcome.Retryable(
                    httpCode = 403,
                    message = message,
                    reason = reason,
                    retryAfterMs = retryAfterMs,
                    isQuota = false,
                )
                in STORAGE_QUOTA_REASONS -> RawOutcome.Fatal(
                    DriveQuotaException(
                        message = message,
                        httpCode = 403,
                        retryAfterMs = retryAfterMs,
                        reason = reason,
                        isStorageQuota = reason == "storageQuotaExceeded",
                    ),
                )
                in AUTH_403_REASONS -> RawOutcome.Fatal(
                    DriveAuthExpiredException(message, httpCode = 403),
                )
                else -> RawOutcome.Fatal(DriveHttpException(message, httpCode = 403, reason = reason))
            }
        }
        if (code in 500..599) {
            return RawOutcome.Retryable(
                httpCode = code,
                message = message,
                reason = reason,
                retryAfterMs = retryAfterMs,
                isQuota = false,
            )
        }
        return RawOutcome.Fatal(DriveHttpException(message, httpCode = code, reason = reason))
    }

    private fun shouldDeferRetry(retryAfterMs: Long?): Boolean =
        retryAfterMs != null && retryAfterMs > MAX_RETRY_AFTER_MS

    private fun computeBackoffMs(attempt: Int, retryAfterMs: Long?): Long {
        if (retryAfterMs != null && retryAfterMs >= 0L) {
            return retryAfterMs
        }
        val shift = attempt.coerceAtMost(10)
        val exponential = min(maxBackoffMs, baseBackoffMs * (1L shl shift))
        return (exponential * jitterFactor()).toLong().coerceAtLeast(1L)
    }

    private fun apiUrl(vararg segments: String) =
        apiBaseUrl.trimEnd('/').toHttpUrl().newBuilder().apply {
            segments.forEach(::addPathSegment)
        }

    private fun uploadUrl(vararg segments: String) =
        uploadBaseUrl.trimEnd('/').toHttpUrl().newBuilder().apply {
            segments.forEach(::addPathSegment)
        }

    private sealed interface RawOutcome {
        data class Success(val body: ByteArray) : RawOutcome
        data class Retryable(
            val httpCode: Int,
            val message: String,
            val reason: String?,
            val retryAfterMs: Long?,
            val isQuota: Boolean,
        ) : RawOutcome {
            val isRateLimited: Boolean get() = httpCode == 429 || (httpCode == 403 && isQuota)

            fun toFinalException(): DriveApiException = if (isQuota) {
                DriveQuotaException(
                    message = message,
                    httpCode = httpCode,
                    retryAfterMs = retryAfterMs,
                    reason = reason,
                    isStorageQuota = false,
                )
            } else {
                DriveHttpException(
                    message = message,
                    httpCode = httpCode,
                    reason = reason,
                    retryAfterMs = retryAfterMs,
                )
            }
        }
        data class Fatal(val exception: DriveApiException) : RawOutcome
    }

    private data class GoogleErrorInfo(val reason: String?, val message: String?)
    private data class MultipartPayload(val boundary: String, val bytes: ByteArray)
    private data class ByteRange(val start: Long, val end: Long, val total: Long?)

    companion object {
        const val DEFAULT_API_BASE_URL = "https://www.googleapis.com/drive/v3"
        const val DEFAULT_UPLOAD_BASE_URL = "https://www.googleapis.com/upload/drive/v3"
        const val LISEUR_FOLDER_NAME = "Liseur"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val SPACE_DRIVE = "drive"
        const val SPACE_APPDATA = "appDataFolder"
        const val MAX_APP_PROPERTY_BYTES = 124

        /** Upper bound for any non-streamed response; snapshot parts are capped at 4 MiB. */
        const val MAX_BUFFERED_BODY_BYTES = 16L * 1024 * 1024
        const val DEFAULT_MAX_RETRIES = 4
        const val DEFAULT_BASE_BACKOFF_MS = 500L
        const val DEFAULT_MAX_BACKOFF_MS = 32_000L
        const val MAX_RETRY_AFTER_MS = 120_000L
        private const val HTTP_RESUME_INCOMPLETE = 308
        private const val APPLICATION_JSON = "application/json"
        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

        private const val FILE_FIELDS =
            "id,name,mimeType,parents,trashed,appProperties,size,md5Checksum,createdTime"
        private const val CHANGES_FIELDS =
            "nextPageToken,newStartPageToken,changes(fileId,removed,file($FILE_FIELDS))"

        private val CANONICAL_FOLDER_ORDER: Comparator<DriveRemoteFile> =
            compareBy<DriveRemoteFile>({ it.createdTime ?: "" }, { it.id })

        private val RETRYABLE_403_RATE_LIMIT_REASONS = setOf(
            "rateLimitExceeded",
            "userRateLimitExceeded",
            "sharingRateLimitExceeded",
        )
        private val RETRYABLE_403_BACKEND_REASONS = setOf(
            "backendError",
            "internalError",
        )
        private val STORAGE_QUOTA_REASONS = setOf(
            "storageQuotaExceeded",
            "quotaExceeded",
            "dailyLimitExceeded",
        )
        private val AUTH_403_REASONS = setOf(
            "insufficientPermissions",
            "authError",
            "invalidCredentials",
        )

        private val UPLOAD_RANGE_REGEX = Regex("""bytes=0-(\d+)""")
        private val DOWNLOAD_CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
        private val UNSATISFIABLE_RANGE_REGEX = Regex("""bytes\s+\*/(\d+)""")

        // OkHttp replays a 503 carrying `Retry-After: 0` itself unless the body is
        // one-shot, which would repost a create that may already have committed.
        private fun RequestBody.oneShot(): RequestBody {
            val delegate = this
            return object : RequestBody() {
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
                override fun isOneShot() = true
            }
        }

        fun defaultClient(): OkHttpClient = RemoteHttp.default().newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            // OkHttp re-sends a request it believes never reached the server, so a
            // create whose connection died after the bytes arrived would be
            // duplicated; creation retries stay in `executeBytesWithRetry`.
            .retryOnConnectionFailure(false)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        internal fun validateAppProperties(appProperties: Map<String, String>) {
            for ((key, value) in appProperties) {
                val keyBytes = key.toByteArray(Charsets.UTF_8).size
                val valBytes = value.toByteArray(Charsets.UTF_8).size
                require(keyBytes >= 1) { "Drive appProperty key must not be empty" }
                // Drive's limit applies to the key and value together.
                require(keyBytes + valBytes <= MAX_APP_PROPERTY_BYTES) {
                    "Drive appProperty '$key' exceeds $MAX_APP_PROPERTY_BYTES UTF-8 bytes for key plus value " +
                        "(${keyBytes + valBytes})"
                }
            }
        }

        internal fun escapeDriveQueryLiteral(value: String): String =
            value.replace("\\", "\\\\").replace("'", "\\'")

        internal fun parseRetryAfterMs(header: String?, nowMs: Long): Long? {
            val trimmed = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            trimmed.toLongOrNull()?.let { seconds ->
                return (seconds * 1000L).coerceAtLeast(0L)
            }
            return runCatching {
                val targetMs = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
                (targetMs - nowMs).coerceAtLeast(0L)
            }.getOrNull()
        }

        internal fun parseUploadRangeNextOffset(rangeHeader: String?): Long? {
            val match = rangeHeader?.let { UPLOAD_RANGE_REGEX.find(it) } ?: return null
            val end = match.groupValues[1].toLongOrNull() ?: return null
            return end + 1L
        }

        private fun parseDownloadContentRange(header: String?): ByteRange? {
            val match = header?.let { DOWNLOAD_CONTENT_RANGE_REGEX.find(it) } ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
            return ByteRange(start, end, total)
        }

        private fun parseUnsatisfiableRangeTotal(header: String?): Long? {
            val match = header?.let { UNSATISFIABLE_RANGE_REGEX.find(it) } ?: return null
            return match.groupValues[1].toLongOrNull()
        }

        private fun bearer(accessToken: String): String = "Bearer ${accessToken.trim()}"

        private fun parseJsonObject(body: String, label: String): JSONObject = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw DriveApiException("Malformed JSON in Google Drive $label response", cause = e)
        }

        private fun parseDriveFile(json: JSONObject): DriveRemoteFile {
            val id = json.optString("id").trim()
            val name = json.optString("name")
            val mimeType = json.optString("mimeType").takeIf { it.isNotEmpty() }
            val parentsArray = json.optJSONArray("parents")
            val parents = buildList {
                if (parentsArray != null) {
                    for (i in 0 until parentsArray.length()) {
                        val p = parentsArray.optString(i)
                        if (p.isNotEmpty()) add(p)
                    }
                }
            }
            val trashed = json.optBoolean("trashed", false)
            val propsObj = json.optJSONObject("appProperties")
            val appProperties = buildMap {
                if (propsObj != null) {
                    val keys = propsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (!propsObj.isNull(k)) put(k, propsObj.optString(k))
                    }
                }
            }
            val size = if (json.has("size") && !json.isNull("size")) {
                json.optString("size").toLongOrNull() ?: json.optLong("size", -1L).takeIf { it >= 0L }
            } else {
                null
            }
            val md5 = json.optString("md5Checksum").takeIf { it.isNotEmpty() }
            val createdTime = json.optString("createdTime").takeIf { it.isNotEmpty() }
            return DriveRemoteFile(
                id = id,
                name = name,
                mimeType = mimeType,
                parents = parents,
                trashed = trashed,
                appProperties = appProperties,
                size = size,
                md5Checksum = md5,
                createdTime = createdTime,
            )
        }

        private fun parseGoogleError(body: String): GoogleErrorInfo {
            if (body.isBlank()) return GoogleErrorInfo(null, null)
            return runCatching {
                val root = JSONObject(body)
                val err = root.optJSONObject("error") ?: return GoogleErrorInfo(null, null)
                val message = err.optString("message").takeIf { it.isNotBlank() }
                val errors = err.optJSONArray("errors")
                val reason = errors?.optJSONObject(0)?.optString("reason")?.takeIf { it.isNotBlank() }
                GoogleErrorInfo(reason = reason, message = message)
            }.getOrDefault(GoogleErrorInfo(null, null))
        }

        private fun buildFileMetadataJson(
            name: String,
            parents: List<String>,
            mimeType: String,
            appProperties: Map<String, String>,
        ): JSONObject = JSONObject().apply {
            put("name", name)
            put("mimeType", mimeType)
            if (parents.isNotEmpty()) {
                put("parents", JSONArray(parents))
            }
            if (appProperties.isNotEmpty()) {
                put("appProperties", JSONObject(appProperties))
            }
        }

        private fun buildMultipartRelatedBody(
            metadataJson: String,
            mediaMimeType: String,
            mediaBytes: ByteArray,
        ): MultipartPayload {
            val boundary = "liseur_drive_${UUID.randomUUID().toString().replace("-", "")}"
            val out = ByteArrayOutputStream()
            fun writeAscii(text: String) = out.write(text.toByteArray(Charsets.UTF_8))
            writeAscii("--$boundary\r\n")
            writeAscii("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            writeAscii(metadataJson)
            writeAscii("\r\n--$boundary\r\n")
            writeAscii("Content-Type: $mediaMimeType\r\n\r\n")
            out.write(mediaBytes)
            writeAscii("\r\n--$boundary--\r\n")
            return MultipartPayload(boundary = boundary, bytes = out.toByteArray())
        }
    }
}
