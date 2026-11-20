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
            when (title) {
                is FieldEdit.Set -> {
                    bookDao.setTitleOverride(bookUrl, title.value)
                    bumpRevision(bookUrl, MetadataOverrideFields.TITLE, at)
                    changed = true
                }
                FieldEdit.Reset -> {
                    bookDao.clearTitleOverride(bookUrl)
                    bumpRevision(bookUrl, MetadataOverrideFields.TITLE, at)
                    changed = true
                }
                FieldEdit.Unchanged -> Unit
            }
            when (author) {
                is FieldEdit.Set -> {
                    bookDao.setAuthorOverride(bookUrl, author.value)
                    bumpRevision(bookUrl, MetadataOverrideFields.AUTHOR, at)
                    changed = true
                }
                FieldEdit.Reset -> {
                    bookDao.clearAuthorOverride(bookUrl)
                    bumpRevision(bookUrl, MetadataOverrideFields.AUTHOR, at)
                    changed = true
                }
                FieldEdit.Unchanged -> Unit
            }
            when (description) {
                is FieldEdit.Set -> {
                    bookDao.setDescriptionOverride(bookUrl, description.value)
                    bumpRevision(bookUrl, MetadataOverrideFields.DESCRIPTION, at)
                    changed = true
                }
                FieldEdit.Reset -> {
                    bookDao.clearDescriptionOverride(bookUrl)
                    bumpRevision(bookUrl, MetadataOverrideFields.DESCRIPTION, at)
                    changed = true
                }
                FieldEdit.Unchanged -> Unit
            }
        }
        if (changed) requestSync(bookUrl)
    }

    suspend fun setCoverFromBytes(bookUrl: String, rawBytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            if (rawBytes.isEmpty() || rawBytes.size > MAX_COVER_SOURCE_BYTES) return@withContext false
            val jpeg = encodeCoverJpeg(rawBytes) ?: return@withContext false
            val sha256 = sha256Hex(jpeg)
            val target = writeCoverFile(bookUrl, sha256, jpeg) ?: return@withContext false
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
                return@withContext false
            }
            if (oldPath != null && oldPath != target.absolutePath) {
                File(oldPath!!).delete()
            }
            requestSync(bookUrl)
            true
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
                revision = (prior?.revision ?: 0L) + 1L,
                retiredRevision = prior?.retiredRevision ?: 0L,
                updatedAt = at,
            ),
        )
    }

    companion object {
        const val MAX_COVER_SOURCE_BYTES = 16 * 1024 * 1024
        private const val MAX_COVER_DIMENSION = 1600
        private const val JPEG_QUALITY = 85

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
