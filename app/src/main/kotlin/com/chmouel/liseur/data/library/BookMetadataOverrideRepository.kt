package com.chmouel.liseur.data.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.BookMetadataOverrideDao
import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.db.MetadataOverrideRevision
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface FieldEdit {
    data object Unchanged : FieldEdit
    data class Set(val value: String) : FieldEdit
    data object Reset : FieldEdit
}

/** The cover part of an edit sheet, committed once the reader saves. */
sealed interface CoverEdit {
    data object Unchanged : CoverEdit
    data class Set(val bytes: ByteArray) : CoverEdit

    /** A deliberate "no cover", kept on this device only. */
    data object Empty : CoverEdit
    data object Reset : CoverEdit
}

class BookMetadataOverrideRepository(
    private val bookDao: BookDao,
    private val overrideDao: BookMetadataOverrideDao,
    private val coversDir: File,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    private val requestSync: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val encodeCoverJpeg: (ByteArray) -> ByteArray? = ::defaultEncodeCoverJpeg,
) {
    suspend fun applyTextEdits(
        bookUrl: String,
        title: FieldEdit = FieldEdit.Unchanged,
        author: FieldEdit = FieldEdit.Unchanged,
        description: FieldEdit = FieldEdit.Unchanged,
    ) {
        var changed = false
        val at = now()
        inTransaction {
            if (bookDao.getByUrl(bookUrl) == null) return@inTransaction
            for (field in MetadataOverrideFields.TEXT_FIELDS) {
                val edit = when (field) {
                    MetadataOverrideFields.TITLE -> title
                    MetadataOverrideFields.AUTHOR -> author
                    else -> description
                }
                val applied = applyTextEdit(bookUrl, field, edit, at)
                if (applied) changed = true
            }
        }
        if (changed) requestSync(bookUrl)
    }

    private suspend fun applyTextEdit(bookUrl: String, field: String, edit: FieldEdit, at: Long): Boolean = when (edit) {
        is FieldEdit.Set -> {
            if (!withinWireLimit(field, edit.value)) {
                false
            } else {
                writeTextValue(bookUrl, field, edit.value)
                bumpRevision(bookUrl, field, at)
                true
            }
        }
        FieldEdit.Reset -> {
            writeTextValue(bookUrl, field, null)
            bumpRevision(bookUrl, field, at)
            true
        }
        FieldEdit.Unchanged -> false
    }

    private suspend fun writeTextValue(bookUrl: String, field: String, value: String?) =
        when (field) {
            MetadataOverrideFields.TITLE ->
                if (value == null) bookDao.clearTitleOverride(bookUrl) else bookDao.setTitleOverride(bookUrl, value)
            MetadataOverrideFields.AUTHOR ->
                if (value == null) bookDao.clearAuthorOverride(bookUrl) else bookDao.setAuthorOverride(bookUrl, value)
            else ->
                if (value == null) bookDao.clearDescriptionOverride(bookUrl) else bookDao.setDescriptionOverride(bookUrl, value)
        }

    suspend fun setCoverFromBytes(bookUrl: String, rawBytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            if (rawBytes.isEmpty() || rawBytes.size > MAX_COVER_SOURCE_BYTES) return@withContext false
            val jpeg = encodeCoverJpeg(rawBytes) ?: return@withContext false
            storeCover(bookUrl, sha256Hex(jpeg), jpeg)
        }

    /**
     * Puts a cover back from a backup as an edit of its own.
     *
     * The bytes are already the JPEG the override held, so they are
     * stored as they are, and the edit owes a sync like any other: a
     * restored cover that nobody owes is one the next pull can undo.
     */
    internal suspend fun restoreCover(bookUrl: String, sha256: String, jpegBytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            if (!SHA256_HEX.matches(sha256) || jpegBytes.isEmpty()) return@withContext false
            storeCover(bookUrl, sha256, jpegBytes)
        }

    private suspend fun storeCover(bookUrl: String, sha256: String, jpeg: ByteArray): Boolean {
        val target = writeCoverFile(bookUrl, sha256, jpeg) ?: return false
        var oldPath: String? = null
        var updated = false
        val at = now()
        inTransaction {
            val existing = bookDao.getByUrl(bookUrl) ?: return@inTransaction
            oldPath = existing.userCoverPath
            bookDao.setCoverOverride(bookUrl, target.absolutePath, sha256)
            bumpRevision(bookUrl, MetadataOverrideFields.COVER, at)
            updated = true
        }
        if (!updated) {
            target.delete()
            return false
        }
        if (oldPath != null && oldPath != target.absolutePath) {
            File(oldPath!!).delete()
        }
        requestSync(bookUrl)
        return true
    }

    suspend fun clearCoverToEmpty(bookUrl: String) {
        var oldPath: String? = null
        var updated = false
        val at = now()
        inTransaction {
            val existing = bookDao.getByUrl(bookUrl) ?: return@inTransaction
            oldPath = existing.userCoverPath
            bookDao.setCoverOverride(bookUrl, null, null)
            bumpRevision(bookUrl, MetadataOverrideFields.COVER, at)
            updated = true
        }
        if (updated) {
            oldPath?.let { File(it).delete() }
            requestSync(bookUrl)
        }
    }

    suspend fun resetCover(bookUrl: String) {
        var oldPath: String? = null
        var updated = false
        val at = now()
        inTransaction {
            val existing = bookDao.getByUrl(bookUrl) ?: return@inTransaction
            oldPath = existing.userCoverPath
            bookDao.clearCoverOverride(bookUrl)
            bumpRevision(bookUrl, MetadataOverrideFields.COVER, at)
            updated = true
        }
        if (updated) {
            oldPath?.let { File(it).delete() }
            requestSync(bookUrl)
        }
    }

    suspend fun resetAllTextAndCover(bookUrl: String) {
        var oldPath: String? = null
        var updated = false
        val at = now()
        inTransaction {
            val existing = bookDao.getByUrl(bookUrl) ?: return@inTransaction
            oldPath = existing.userCoverPath
            if (existing.titleOverridden) {
                bookDao.clearTitleOverride(bookUrl)
                bumpRevision(bookUrl, MetadataOverrideFields.TITLE, at)
                updated = true
            }
            if (existing.authorOverridden) {
                bookDao.clearAuthorOverride(bookUrl)
                bumpRevision(bookUrl, MetadataOverrideFields.AUTHOR, at)
                updated = true
            }
            if (existing.descriptionOverridden) {
                bookDao.clearDescriptionOverride(bookUrl)
                bumpRevision(bookUrl, MetadataOverrideFields.DESCRIPTION, at)
                updated = true
            }
            if (existing.coverOverridden) {
                bookDao.clearCoverOverride(bookUrl)
                bumpRevision(bookUrl, MetadataOverrideFields.COVER, at)
                updated = true
            }
        }
        if (updated) {
            oldPath?.let { File(it).delete() }
            requestSync(bookUrl)
        }
    }

    /** Writes a cover received from liseur-sync or backup without bumping local sync revision. */
    internal suspend fun writeExternalCoverFile(bookUrl: String, sha256: String, jpegBytes: ByteArray): File? =
        withContext(Dispatchers.IO) {
            writeCoverFile(bookUrl, sha256, jpegBytes)
        }

    private fun writeCoverFile(bookUrl: String, sha256: String, jpegBytes: ByteArray): File? {
        if (!coversDir.exists() && !coversDir.mkdirs()) return null
        val bookHash = sha1Hex(bookUrl).take(16)
        val file = File(coversDir, "user-$bookHash-${sha256.take(12)}.jpg")
        val tmp = File(coversDir, "${file.name}.tmp")
        return try {
            tmp.writeBytes(jpegBytes)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            file
        } catch (_: Exception) {
            tmp.delete()
            null
        }
    }

    private suspend fun bumpRevision(bookUrl: String, field: String, at: Long) {
        val prior = overrideDao.revision(bookUrl, field)
        overrideDao.upsertRevision(
            MetadataOverrideRevision(
                bookUrl = bookUrl,
                field = field,
                revision = overrideDao.nextRevision(),
                retiredRevision = prior?.retiredRevision ?: 0L,
                updatedAt = at,
            ),
        )
    }

    companion object {
        const val MAX_COVER_SOURCE_BYTES = 16 * 1024 * 1024

        /** The server's own limits for a metadata value, in UTF-8 bytes. */
        const val MAX_TITLE_BYTES = 512
        const val MAX_AUTHOR_BYTES = 512
        const val MAX_DESCRIPTION_BYTES = 16384

        private const val MAX_COVER_DIMENSION = 1600
        private const val JPEG_QUALITY = 85
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        /** The largest value the server takes for [field]. */
        fun wireLimit(field: String): Int =
            if (field == MetadataOverrideFields.DESCRIPTION) MAX_DESCRIPTION_BYTES else MAX_TITLE_BYTES

        /** Whether [value] is short enough to be sent for [field]. */
        fun withinWireLimit(field: String, value: String?): Boolean =
            value == null || value.toByteArray(Charsets.UTF_8).size <= wireLimit(field)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun sha1Hex(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun defaultEncodeCoverJpeg(rawBytes: ByteArray): ByteArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null
            var sampleSize = 1
            while (maxOf(width / sampleSize, height / sampleSize) > MAX_COVER_DIMENSION * 2) {
                sampleSize *= 2
            }
            val decoded = BitmapFactory.decodeByteArray(
                rawBytes,
                0,
                rawBytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: return null
            val scaled = if (maxOf(decoded.width, decoded.height) > MAX_COVER_DIMENSION) {
                val scale = MAX_COVER_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height)
                val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }
            return try {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
                out.toByteArray()
            } finally {
                scaled.recycle()
            }
        }
    }
}
