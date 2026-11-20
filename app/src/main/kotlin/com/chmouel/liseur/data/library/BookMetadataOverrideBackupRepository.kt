package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class MetadataOverrideBackupCover(
    val path: String,
    val sha256: String,
    val file: File,
)

data class PreparedMetadataOverrideBackup(
    val jsonBytes: ByteArray,
    val covers: List<MetadataOverrideBackupCover>,
)

class BookMetadataOverrideBackupRepository(
    private val bookDao: BookDao,
    private val overrideRepository: BookMetadataOverrideRepository,
) {
    suspend fun exportOverrides(): PreparedMetadataOverrideBackup? {
        val books = bookDao.booksWithMetadataOverrides()
        if (books.isEmpty()) return null
        val coversBySha = linkedMapOf<String, MetadataOverrideBackupCover>()
        val items = JSONArray()
        for (book in books) {
            val obj = JSONObject().put("book_url", book.url)
            book.remoteUuid?.let { obj.put("remote_uuid", it) }
            if (book.titleOverridden) {
                obj.put("title_override", true)
                obj.put("user_title", book.userTitle ?: "")
            }
            if (book.authorOverridden) {
                obj.put("author_override", true)
                obj.put("user_author", book.userAuthor ?: "")
            }
            if (book.descriptionOverridden) {
                obj.put("description_override", true)
                obj.put("user_description", book.userDescription ?: "")
            }
            if (book.seriesOverridden) {
                obj.put("series_override", true)
                obj.put("user_series_name", book.userSeriesName ?: JSONObject.NULL)
            }
            if (book.indexOverridden) {
                obj.put("series_index_override", true)
                obj.put("user_series_index", book.userSeriesIndex ?: JSONObject.NULL)
            }
            if (book.coverOverridden) {
                obj.put("cover_override", true)
                val coverFile = book.userCoverPath?.let(::File)?.takeIf { it.isFile }
                val sha = book.userCoverSha256?.takeIf { SHA256.matches(it) }
                if (coverFile != null && sha != null) {
                    val archivePath = "metadata_covers/$sha.jpg"
                    obj.put("cover_sha256", sha)
                    coversBySha.putIfAbsent(sha, MetadataOverrideBackupCover(archivePath, sha, coverFile))
                } else {
                    obj.put("cover_sha256", JSONObject.NULL)
                }
            }
            items.put(obj)
        }
        val payload = JSONObject()
            .put("format", FORMAT)
            .put("application", "liseur")
            .put("overrides", items)
            .toString()
            .toByteArray(Charsets.UTF_8)
        return PreparedMetadataOverrideBackup(payload, coversBySha.values.toList())
    }

    suspend fun restore(
        jsonBytes: ByteArray,
        stagedCovers: Map<String, File>,
        aliases: Map<String, String> = emptyMap(),
    ): Int {
        val root = try {
            JSONObject(jsonBytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return 0
        }
        if (root.optString("application") != "liseur") return 0
        val array = root.optJSONArray("overrides") ?: return 0
        var restored = 0
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val rawUrl = item.optString("book_url").takeIf { it.isNotEmpty() } ?: continue
            val remoteUuid = item.optString("remote_uuid").takeIf { it.isNotEmpty() }
            val targetBook = resolveBook(rawUrl, remoteUuid, aliases) ?: continue
            val url = targetBook.url

            val titleEdit = if (item.optBoolean("title_override", false)) {
                FieldEdit.Set(item.optString("user_title", ""))
            } else {
                FieldEdit.Unchanged
            }
            val authorEdit = if (item.optBoolean("author_override", false)) {
                FieldEdit.Set(item.optString("user_author", ""))
            } else {
                FieldEdit.Unchanged
            }
            val descriptionEdit = if (item.optBoolean("description_override", false)) {
                FieldEdit.Set(item.optString("user_description", ""))
            } else {
                FieldEdit.Unchanged
            }
            overrideRepository.applyTextEdits(
                bookUrl = url,
                title = titleEdit,
                author = authorEdit,
                description = descriptionEdit,
            )

            if (item.optBoolean("series_override", false)) {
                val seriesName = if (item.isNull("user_series_name")) null else item.optString("user_series_name")
                val seriesIndex = if (item.isNull("user_series_index")) null else item.optDouble("user_series_index").takeIf { it.isFinite() }
                bookDao.setSeriesOverride(url, seriesName, seriesIndex)
            }

            if (item.optBoolean("cover_override", false)) {
                val sha = if (item.isNull("cover_sha256")) null else item.optString("cover_sha256").takeIf { SHA256.matches(it) }
                if (sha == null) {
                    overrideRepository.clearCoverToEmpty(url)
                } else {
                    val staged = stagedCovers["metadata_covers/$sha.jpg"]
                    if (staged != null && staged.isFile) {
                        val bytes = staged.readBytes()
                        val saved = overrideRepository.writeExternalCoverFile(url, sha, bytes)
                        if (saved != null) {
                            bookDao.setCoverOverride(url, saved.absolutePath, sha)
                        }
                    }
                }
            }
            restored++
        }
        return restored
    }

    private suspend fun resolveBook(
        rawUrl: String,
        remoteUuid: String?,
        aliases: Map<String, String>,
    ): Book? {
        val mapped = aliases[rawUrl] ?: rawUrl
        bookDao.getByUrl(mapped)?.let { return it }
        if (mapped != rawUrl) {
            bookDao.getByUrl(rawUrl)?.let { return it }
        }
        if (remoteUuid != null) {
            bookDao.byRemoteUuids(listOf(remoteUuid)).firstOrNull()?.let { return it }
        }
        return null
    }

    companion object {
        const val FORMAT = 1
        const val MAX_JSON_BYTES = 4L * 1024 * 1024
        const val MAX_COVER_BYTES = 4L * 1024 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
