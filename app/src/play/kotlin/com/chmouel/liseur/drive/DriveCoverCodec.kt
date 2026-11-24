package com.chmouel.liseur.drive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of encoding a custom cover into the Drive content-addressed WebP budget. */
internal sealed interface DriveCoverEncodeResult {
    data class Encoded(
        val webpBytes: ByteArray,
        val sha256: String,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
    ) : DriveCoverEncodeResult

    data class TooLarge(val lastAttemptBytes: Int) : DriveCoverEncodeResult
    data object InvalidImage : DriveCoverEncodeResult
}

/** Injectable image-to-WebP encoder for deterministic JVM unit tests and Android runtime. */
internal fun interface DriveWebpEncoder {
    fun encode(sourceBytes: ByteArray, maxLongEdge: Int, quality: Int): EncodedImageFrame?
}

internal data class EncodedImageFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * Content-addressed, size-capped WebP custom cover manager for Google Drive sync
 * (`docs/DRIVE_SYNC.md` §2.5 & §4.3).
 *
 * Enforces:
 * - Long edge `<= 1200 px`, preserving aspect ratio.
 * - Lossy WebP quality ladder `85 -> 75 -> 65 -> 55 -> 45` until `<= 512 KiB`.
 * - Non-fatal refusal when even quality 45 exceeds 512 KiB.
 * - Content-addressed deduplication in `appDataFolder` as `cover-<sha256>.webp`.
 */
internal class DriveCoverCodec(
    private val coversDir: File,
    private val store: DriveSyncDatabase,
    private val driveApi: DriveRemoteApi,
    private val encoder: DriveWebpEncoder = AndroidBitmapWebpEncoder,
) {
    private val driveFiles get() = store.files()

    /** Encodes [sourceBytes] to WebP within `MAX_LONG_EDGE` and `MAX_WEBP_BYTES`. */
    fun encodeToBudget(sourceBytes: ByteArray): DriveCoverEncodeResult {
        if (sourceBytes.isEmpty()) return DriveCoverEncodeResult.InvalidImage
        var lastSize = 0
        for (quality in QUALITY_LADDER) {
            val frame = encoder.encode(sourceBytes, MAX_LONG_EDGE, quality)
                ?: return DriveCoverEncodeResult.InvalidImage
            if (frame.width <= 0 || frame.height <= 0 || frame.bytes.isEmpty()) {
                return DriveCoverEncodeResult.InvalidImage
            }
            lastSize = frame.bytes.size
            if (frame.bytes.size <= MAX_WEBP_BYTES) {
                val sha = DriveCanonicalJson.sha256Hex(frame.bytes)
                return DriveCoverEncodeResult.Encoded(
                    webpBytes = frame.bytes,
                    sha256 = sha,
                    width = frame.width,
                    height = frame.height,
                    sizeBytes = frame.bytes.size.toLong(),
                )
            }
        }
        return DriveCoverEncodeResult.TooLarge(lastSize)
    }

    /**
     * Ensures `cover-<sha256>.webp` exists in `appDataFolder` before publishing the
     * `metadata` delta referencing it, deduplicating via local `drive_file` index
     * and remote `files.list` lookup.
     */
    suspend fun ensureUploaded(
        permissionId: String,
        accessToken: String,
        encoded: DriveCoverEncodeResult.Encoded,
    ): String {
        val sha = encoded.sha256.lowercase(Locale.ROOT)
        // Persist a local content-addressed copy so crash recovery and repeat diffs never drift.
        saveLocalCoverFile(sha, encoded.webpBytes)

        // 1. Check local drive_file index first.
        driveFiles.byKindAndSha256(permissionId, DriveFile.KIND_COVER, sha)
            .firstOrNull { !it.trashed }
            ?.let { return it.fileId }

        // 2. Deduplicate remotely in appDataFolder before uploading.
        val fileName = DriveWireCodec.coverFileName(sha)
        val escapedName = DriveRestClient.escapeDriveQueryLiteral(fileName)
        val existingRemote = driveApi.listAppDataFiles(
            accessToken = accessToken,
            query = "name = '$escapedName' and trashed = false",
            pageSize = 5,
        ).firstOrNull { !it.trashed && it.name == fileName }
        if (existingRemote != null) {
            driveFiles.upsert(
                DriveFile(
                    permissionId = permissionId,
                    fileId = existingRemote.id,
                    name = fileName,
                    kind = DriveFile.KIND_COVER,
                    sha256 = sha,
                    size = existingRemote.size ?: encoded.sizeBytes,
                    md5 = existingRemote.md5Checksum,
                    trashed = false,
                    createdTime = existingRemote.createdTime,
                ),
            )
            return existingRemote.fileIdOrId()
        }

        // 3. Upload immutable content-addressed WebP blob.
        val uploaded = driveApi.createAppDataFile(
            accessToken = accessToken,
            name = fileName,
            mimeType = WEBP_MIME_TYPE,
            content = encoded.webpBytes,
            appProperties = DriveWireCodec.coverAppProperties(sha),
        )
        driveFiles.upsert(
            DriveFile(
                permissionId = permissionId,
                fileId = uploaded.id,
                name = fileName,
                kind = DriveFile.KIND_COVER,
                sha256 = sha,
                size = uploaded.size ?: encoded.sizeBytes,
                md5 = uploaded.md5Checksum,
                trashed = false,
                createdTime = uploaded.createdTime,
            ),
        )
        return uploaded.id
    }

    /**
     * Downloads `cover-<coverSha256>.webp` (or reuses an already verified local file),
     * verifies SHA-256 strictly, and returns the local [File] or `null` if the cover
     * blob is not yet visible or fails SHA-256 verification. Retryable network/API
     * failures propagate so the caller does not advance the domain cursor.
     */
    suspend fun downloadVerified(
        permissionId: String,
        accessToken: String,
        coverSha256: String,
    ): File? = withContext(Dispatchers.IO) {
        val sha = coverSha256.trim().lowercase(Locale.ROOT)
        val target = localCoverFile(sha)
        if (target.isFile) {
            val existingSha = runCatching { DriveCanonicalJson.sha256Hex(target.readBytes()) }.getOrNull()
            if (existingSha == sha) return@withContext target
            target.delete()
        }

        val indexedFile = driveFiles.byKindAndSha256(permissionId, DriveFile.KIND_COVER, sha)
            .firstOrNull { !it.trashed }
        val fileId = indexedFile?.fileId ?: run {
            val fileName = DriveWireCodec.coverFileName(sha)
            val escapedName = DriveRestClient.escapeDriveQueryLiteral(fileName)
            val remote = driveApi.listAppDataFiles(
                accessToken = accessToken,
                query = "name = '$escapedName' and trashed = false",
                pageSize = 5,
            ).firstOrNull { !it.trashed && it.name == fileName } ?: return@withContext null
            driveFiles.upsert(
                DriveFile(
                    permissionId = permissionId,
                    fileId = remote.id,
                    name = fileName,
                    kind = DriveFile.KIND_COVER,
                    sha256 = sha,
                    size = remote.size,
                    md5 = remote.md5Checksum,
                    trashed = false,
                    createdTime = remote.createdTime,
                ),
            )
            remote.id
        }

        val bytes = driveApi.downloadMedia(accessToken, fileId)
        if (DriveCanonicalJson.sha256Hex(bytes) != sha) {
            return@withContext null
        }
        saveLocalCoverFile(sha, bytes)
    }

    /** Keeps [encoded] as this device's content-addressed copy, so a later publish can upload it. */
    fun storeLocal(encoded: DriveCoverEncodeResult.Encoded): File? =
        saveLocalCoverFile(encoded.sha256.lowercase(Locale.ROOT), encoded.webpBytes)

    /**
     * Copies the shared cache file for [sha256] to a file owned by [bookUrl], so a cover reset
     * or replacement on one book never deletes the image another book still displays.
     */
    fun installForBook(bookUrl: String, sha256: String): File? {
        val source = localCoverFile(sha256).takeIf { it.isFile } ?: return null
        val bookHash = MessageDigest.getInstance("SHA-1").digest(bookUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val target = File(coversDir, "drive-book-$bookHash-${sha256.trim().lowercase(Locale.ROOT).take(12)}.webp")
        val tmp = File(coversDir, "${target.name}.tmp")
        return try {
            source.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        } catch (_: Exception) {
            tmp.delete()
            null
        }
    }

    fun localCoverFile(sha256: String): File =
        File(coversDir, "drive-cover-${sha256.trim().lowercase(Locale.ROOT)}.webp")

    private fun saveLocalCoverFile(sha256: String, bytes: ByteArray): File? {
        if (!coversDir.exists() && !coversDir.mkdirs()) return null
        val target = localCoverFile(sha256)
        if (target.isFile && runCatching { DriveCanonicalJson.sha256Hex(target.readBytes()) == sha256 }.getOrDefault(false)) {
            return target
        }
        val tmp = File(coversDir, "${target.name}.tmp")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        } catch (_: Exception) {
            tmp.delete()
            null
        }
    }

    private fun DriveRemoteFile.fileIdOrId(): String = id

    companion object {
        const val WEBP_MIME_TYPE = "image/webp"
        const val MAX_LONG_EDGE = 1200
        const val MAX_WEBP_BYTES = 512 * 1024 // 512 KiB
        val QUALITY_LADDER = intArrayOf(85, 75, 65, 55, 45)
    }
}

internal object AndroidBitmapWebpEncoder : DriveWebpEncoder {
    override fun encode(sourceBytes: ByteArray, maxLongEdge: Int, quality: Int): EncodedImageFrame? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sampleSize = 1
        while (maxOf(rawW / sampleSize, rawH / sampleSize) > maxLongEdge * 2) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            sourceBytes,
            0,
            sourceBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null

        val longEdge = maxOf(decoded.width, decoded.height)
        val scaled = if (longEdge > maxLongEdge) {
            val scale = maxLongEdge.toFloat() / longEdge.toFloat()
            val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
            val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val width = scaled.width
        val height = scaled.height
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        val ok = scaled.compress(format, quality, out)
        scaled.recycle()
        if (!ok) return null
        return EncodedImageFrame(bytes = out.toByteArray(), width = width, height = height)
    }
}
