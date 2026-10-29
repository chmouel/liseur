package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.chmouel.liseur.domain.BookAccessibility
import com.chmouel.liseur.domain.Contributor
import com.chmouel.liseur.domain.Identifier
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.bounded
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one source says about a book, beyond the title, author and series
 * the library already keeps on [Book] (ADR-0016).
 *
 * One row per source: `file` from the book's own OPF, `catalog` from the
 * server it was listed by. Both are kept so the screen can merge them at
 * read time and so either can be dropped without touching the other.
 * Lists are JSON arrays; nothing queries inside them.
 */
@Entity(
    tableName = "book_metadata",
    primaryKeys = ["book_url", "source"],
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["url"],
            childColumns = ["book_url"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookMetadata(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "subtitle") val subtitle: String? = null,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "publishers") val publishers: String? = null,
    @ColumnInfo(name = "imprints") val imprints: String? = null,
    @ColumnInfo(name = "published") val published: String? = null,
    @ColumnInfo(name = "languages") val languages: String? = null,
    @ColumnInfo(name = "subjects") val subjects: String? = null,
    @ColumnInfo(name = "contributors") val contributors: String? = null,
    @ColumnInfo(name = "identifiers") val identifiers: String? = null,
    @ColumnInfo(name = "page_count") val pageCount: Int? = null,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long? = null,
    @ColumnInfo(name = "accessibility") val accessibility: String? = null,
) {
    fun toSnapshot(): MetadataSnapshot = MetadataSnapshot(
        subtitle = subtitle,
        description = description,
        publishers = strings(publishers),
        imprints = strings(imprints),
        published = published,
        languages = strings(languages),
        subjects = strings(subjects),
        contributors = objects(contributors) { Contributor(it.optString("role"), it.optString("name")) },
        identifiers = objects(identifiers) { Identifier(it.optString("scheme"), it.optString("value")) },
        pageCount = pageCount,
        fileSizeBytes = fileSizeBytes,
        accessibility = accessibility?.let(::decodeAccessibility),
    )

    companion object {
        /** The row for [snapshot], bounded before it is stored. */
        fun of(bookUrl: String, source: MetadataSource, snapshot: MetadataSnapshot): BookMetadata {
            val s = snapshot.bounded()
            return BookMetadata(
                bookUrl = bookUrl,
                source = source.key,
                subtitle = s.subtitle,
                description = s.description,
                publishers = encode(s.publishers),
                imprints = encode(s.imprints),
                published = s.published,
                languages = encode(s.languages),
                subjects = encode(s.subjects),
                contributors = s.contributors.takeIf { it.isNotEmpty() }?.let { list ->
                    JSONArray(list.map { JSONObject().put("role", it.role).put("name", it.name) }).toString()
                },
                identifiers = s.identifiers.takeIf { it.isNotEmpty() }?.let { list ->
                    JSONArray(list.map { JSONObject().put("scheme", it.scheme).put("value", it.value) }).toString()
                },
                pageCount = s.pageCount,
                fileSizeBytes = s.fileSizeBytes,
                accessibility = s.accessibility?.let(::encodeAccessibility),
            )
        }

        private fun encode(values: List<String>): String? =
            values.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() }

        private fun strings(json: String?): List<String> = array(json)?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
        }.orEmpty()

        private fun <T> objects(json: String?, read: (JSONObject) -> T): List<T> = array(json)?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(read) }
        }.orEmpty()

        private fun array(json: String?): JSONArray? = json?.let { runCatching { JSONArray(it) }.getOrNull() }

        private fun encodeAccessibility(a: BookAccessibility): String = JSONObject().apply {
            fun list(key: String, values: List<String>) {
                if (values.isNotEmpty()) put(key, JSONArray(values))
            }
            list("conformsTo", a.conformsTo)
            list("accessModes", a.accessModes)
            list("accessModesSufficient", a.accessModesSufficient)
            list("features", a.features)
            list("hazards", a.hazards)
            list("exemptions", a.exemptions)
            a.summary?.let { put("summary", it) }
            a.certifiedBy?.let { put("certifiedBy", it) }
            a.certifierReport?.let { put("certifierReport", it) }
        }.toString()

        private fun decodeAccessibility(json: String): BookAccessibility? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            fun list(key: String) = strings(o.optJSONArray(key)?.toString())
            fun text(key: String) = o.optString(key).takeIf { it.isNotEmpty() }
            return BookAccessibility(
                conformsTo = list("conformsTo"),
                accessModes = list("accessModes"),
                accessModesSufficient = list("accessModesSufficient"),
                features = list("features"),
                hazards = list("hazards"),
                exemptions = list("exemptions"),
                summary = text("summary"),
                certifiedBy = text("certifiedBy"),
                certifierReport = text("certifierReport"),
            ).takeUnless { it.isEmpty }
        }
    }
}

@Dao
interface BookMetadataDao {
    @Query("SELECT * FROM book_metadata WHERE book_url = :bookUrl")
    fun observe(bookUrl: String): Flow<List<BookMetadata>>

    @Query("SELECT * FROM book_metadata WHERE book_url = :bookUrl")
    suspend fun forBook(bookUrl: String): List<BookMetadata>

    @Query("SELECT * FROM book_metadata WHERE book_url IN (:bookUrls) AND source = :source")
    suspend fun ofSource(bookUrls: List<String>, source: String): List<BookMetadata>

    @Upsert
    suspend fun upsert(row: BookMetadata)

    @Query("DELETE FROM book_metadata WHERE book_url = :bookUrl AND source = :source")
    suspend fun delete(bookUrl: String, source: String)

    @Query("DELETE FROM book_metadata WHERE book_url IN (:bookUrls) AND source = :source")
    suspend fun deleteSource(bookUrls: List<String>, source: String)

    @Query("DELETE FROM book_metadata WHERE book_url = :bookUrl")
    suspend fun forget(bookUrl: String)

    @Query("SELECT url, remote_uuid FROM books WHERE url IN (:bookUrls) AND remote_uuid IS NOT NULL")
    suspend fun catalogLinks(bookUrls: List<String>): List<CatalogLink>

    /**
     * Replaces the catalog rows of [rows], each keyed by book URL and
     * paired with the server id it was read for, a null row meaning the
     * catalog said nothing worth keeping. A book no longer linked to
     * that id is skipped: the refresh read the link before the network
     * wait, and a book cut loose since, or linked to another copy,
     * must not be described by what was fetched for the old one.
     */
    @Transaction
    suspend fun replaceCatalog(rows: Map<String, Pair<String, BookMetadata?>>) {
        val linked = catalogLinks(rows.keys.toList())
            .filter { rows[it.url]?.first == it.remoteUuid }
            .map { it.url }
        val stored = ofSource(linked, MetadataSource.CATALOG.key).associateBy { it.bookUrl }
        val gone = mutableListOf<String>()
        linked.forEach { url ->
            val row = rows.getValue(url).second
            val old = stored[url]
            when {
                row == null -> if (old != null) gone += url
                row != old -> upsert(row)
            }
        }
        if (gone.isNotEmpty()) deleteSource(gone, MetadataSource.CATALOG.key)
    }
}

/** A book's catalog link, as [BookMetadataDao.replaceCatalog] checks it. */
data class CatalogLink(
    val url: String,
    @ColumnInfo(name = "remote_uuid") val remoteUuid: String,
)
