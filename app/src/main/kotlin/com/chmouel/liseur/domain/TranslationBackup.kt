package com.chmouel.liseur.domain

import android.util.JsonReader
import android.util.JsonToken
import com.chmouel.liseur.translate.KeptTranslation
import com.chmouel.liseur.translate.TranslatedSentence
import java.io.Reader

data class BackedUpTranslation(
    val record: KeptTranslation,
    val title: String?,
    val author: String?,
    val rows: List<TranslatedSentence>,
    val failures: List<String>,
)

/** Parses the staged payload token by token; no second, whole JSON string is allocated. */
fun decodeTranslationBackup(input: Reader): List<BackedUpTranslation> = JsonReader(input).use { reader ->
    val books = mutableListOf<BackedUpTranslation>()
    val ids = mutableSetOf<String>()
    var format = false
    var application = false
    var hasBooks = false
    reader.beginObject()
    val fields = mutableSetOf<String>()
    while (reader.hasNext()) {
        val name = reader.nextName()
        require(fields.add(name))
        when (name) {
            "format" -> { require(reader.number() == 1L); format = true }
            "application" -> { require(reader.text() == "liseur"); application = true }
            "books" -> {
                hasBooks = true
                reader.beginArray()
                while (reader.hasNext()) {
                    require(books.size < MAX_TRANSLATION_BOOKS)
                    val book = reader.book()
                    require(ids.add(book.record.bookUrl))
                    books += book
                }
                reader.endArray()
            }
            else -> error("Unknown translation backup field")
        }
    }
    reader.endObject()
    require(format && application && hasBooks && reader.peek() == JsonToken.END_DOCUMENT)
    books
}

private fun JsonReader.book(): BackedUpTranslation {
    var url: String? = null
    var title: String? = null
    var author: String? = null
    var source: String? = null
    var target: String? = null
    var identity: String? = null
    var total: Long? = null
    var failed: Long? = null
    var rows: List<TranslatedSentence>? = null
    var failures: List<String>? = null
    val keys = mutableSetOf<String>()
    val fields = mutableSetOf<String>()
    beginObject()
    while (hasNext()) {
        val field = nextName()
        require(fields.add(field))
        when (field) {
            "book_id" -> url = text()
            "title" -> title = nullableText()
            "author" -> author = nullableText()
            "source" -> source = nullableText()
            "target" -> target = text()
            "identity" -> identity = text()
            "total" -> total = number()
            "failed" -> failed = number()
            "rows" -> {
                val values = mutableListOf<TranslatedSentence>()
                beginArray()
                while (hasNext()) {
                    require(values.size < MAX_TRANSLATION_ROWS)
                    var key: String? = null
                    var translation: String? = null
                    var peer: Boolean? = null
                    var usedAt: Long? = null
                    val rowFields = mutableSetOf<String>()
                    beginObject()
                    while (hasNext()) {
                        val name = nextName()
                        require(rowFields.add(name))
                        when (name) {
                            "key" -> key = sha().also { require(keys.add(it)) }
                            "translation" -> translation = text(MAX_TRANSLATION_LENGTH)
                            "peer" -> peer = nextBoolean()
                            "used_at" -> usedAt = number()
                            else -> error("Unknown translation row field")
                        }
                    }
                    endObject()
                    values += TranslatedSentence("", requireNotNull(key), requireNotNull(translation),
                        requireNotNull(usedAt), if (requireNotNull(peer)) 0 else 1)
                }
                endArray()
                rows = values
            }
            "failures" -> {
                val values = mutableListOf<String>()
                beginArray()
                while (hasNext()) {
                    require(values.size < MAX_TRANSLATION_ROWS)
                    values += sha().also { require(keys.add(it)) }
                }
                endArray()
                failures = values
            }
            else -> error("Unknown translation book field")
        }
    }
    endObject()
    val count = requireNotNull(total)
    val errors = requireNotNull(failed)
    require(count <= Int.MAX_VALUE && errors <= count)
    val saved = requireNotNull(rows)
    val refused = requireNotNull(failures)
    val bookUrl = requireNotNull(url)
    return BackedUpTranslation(
        KeptTranslation(bookUrl, source, requireNotNull(target), requireNotNull(identity), 0,
            count.toInt(), (count - errors).toInt(), errors.toInt(), "restored", null, 0, 0),
        title, author, saved.map { it.copy(bookUrl = bookUrl) }, refused,
    )
}

private fun JsonReader.text(limit: Int = 64 * 1024): String {
    require(peek() == JsonToken.STRING)
    return nextString().also { require(it.isNotBlank() && it.length <= limit) }
}

private fun JsonReader.nullableText(): String? =
    if (peek() == JsonToken.NULL) { nextNull(); null } else {
        require(peek() == JsonToken.STRING)
        nextString().also { require(it.length <= 64 * 1024) }
    }

private fun JsonReader.number(): Long {
    require(peek() == JsonToken.NUMBER)
    return nextString().also { require(it.matches(Regex("0|[1-9][0-9]*"))) }.toLong()
}

private fun JsonReader.sha(): String = text(64).also { require(it.matches(Regex("[0-9a-f]{64}"))) }

const val MAX_TRANSLATION_ROWS = 1_000_000
const val MAX_TRANSLATION_BOOKS = 10_000
// Live passages are at most 2,000 characters, with an eightfold answer allowance.
const val MAX_TRANSLATION_LENGTH = 16_000
