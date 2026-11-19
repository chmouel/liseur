package com.chmouel.liseur.vocabulary

import java.io.Reader
import java.io.Writer
import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

internal data class VocabularyRecord(
    val word: VocabularyWord,
    val occurrences: List<VocabularyOccurrence>,
    val reviews: List<VocabularyReview> = emptyList(),
)

private const val MAX_FILE_CHARS = 32 * 1024 * 1024
private val csvColumns = listOf("word", "definition", "sentence", "book", "language")

internal fun readVocabulary(reader: Reader, now: Long = System.currentTimeMillis()): List<VocabularyRecord> {
    val buffer = CharArray(8192)
    val contents = buildString {
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(length + count <= MAX_FILE_CHARS)
            append(buffer, 0, count)
        }
    }.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
    require(contents.isNotEmpty())
    return if (contents.startsWith('{')) readVocabularyJson(JSONObject(contents), now)
    else readVocabularyCsv(contents, now)
}

internal fun writeVocabularyJson(records: List<VocabularyRecord>, writer: Writer) {
    writer.write("{\"version\":1,\"words\":[")
    records.forEachIndexed { index, record ->
        if (index > 0) writer.write(",")
        val word = record.word
        writer.write(JSONObject().apply {
            put("word", word.word)
            put("language", word.language)
            put("definition", word.definition)
            put("translationLanguage", word.translationLanguage)
            put("notes", word.notes)
            put("addedAt", word.addedAt)
            put("dueAt", word.dueAt)
            put("reviewedAt", word.reviewedAt)
            put("streak", word.streak)
            put("intervalMinutes", word.intervalMinutes)
            put("repetitions", word.repetitions)
            put("definitionEdited", word.definitionEdited)
            put("occurrences", JSONArray(record.occurrences.map { source ->
                JSONObject().apply {
                    put("bookUrl", source.bookUrl)
                    put("bookTitle", source.bookTitle)
                    put("locationKey", source.locationKey)
                    put("locator", source.locator)
                    put("sentence", source.sentence)
                    put("lastSeenAt", source.lastSeenAt)
                    put("encounters", source.encounters)
                }
            }))
            put("reviews", JSONArray(record.reviews.map { review ->
                JSONObject().apply {
                    put("rating", review.rating)
                    put("reviewedAt", review.reviewedAt)
                    put("intervalMinutes", review.intervalMinutes)
                    put("dueAt", review.dueAt)
                    put("firstReview", review.firstReview)
                }
            }))
        }.toString())
    }
    writer.write("]}")
}

private fun readVocabularyJson(root: JSONObject, now: Long): List<VocabularyRecord> {
    require(root.getInt("version") == 1)
    return root.getJSONArray("words").objects { entry ->
        val word = importedWord(entry.getString("word"), entry.getString("language"), entry.optionalText("definition", 24000), now)
        VocabularyRecord(
            word = word.copy(
                translationLanguage = entry.optionalText("translationLanguage", 80)?.let(::vocabularyLanguage),
                notes = entry.optionalText("notes", 12000).orEmpty(),
                addedAt = entry.time("addedAt", now),
                dueAt = entry.time("dueAt", now),
                reviewedAt = if (entry.has("reviewedAt")) entry.time("reviewedAt", now) else null,
                streak = entry.getInt("streak").also { require(it in 0..14) },
                intervalMinutes = entry.interval(),
                repetitions = entry.getInt("repetitions").also { require(it in 0 until Int.MAX_VALUE) },
                definitionEdited = entry.getBoolean("definitionEdited"),
            ).withSearchText(),
            occurrences = entry.getJSONArray("occurrences").objects { src ->
                val locator = src.optionalText("locator", 32000)
                require(locator == null || Locator.fromJSON(JSONObject(locator)) != null)
                val title = src.optionalText("bookTitle", 4000).orEmpty()
                val sentence = src.optionalText("sentence", 4000)
                VocabularyOccurrence(
                    bookUrl = src.optionalText("bookUrl", 8000).orEmpty(),
                    bookTitle = title,
                    locator = locator,
                    locationKey = src.getString("locationKey").checked(32000),
                    sentence = sentence,
                    lastSeenAt = src.time("lastSeenAt", now),
                    encounters = src.getInt("encounters").also { require(it in 1 until Int.MAX_VALUE) },
                )
            },
            reviews = entry.getJSONArray("reviews").objects { review ->
                VocabularyReview(
                    wordId = 0,
                    rating = Recall.valueOf(review.getString("rating")).name,
                    reviewedAt = review.time("reviewedAt", now),
                    intervalMinutes = review.interval(),
                    dueAt = review.time("dueAt", now),
                    firstReview = review.getBoolean("firstReview"),
                )
            },
        )
    }
}

internal fun writeVocabularyCsv(records: List<VocabularyRecord>, writer: Writer) {
    writer.write("#separator:comma\n#html:false\n#columns:${csvColumns.joinToString(",")}\n")
    records.forEach { (word, sources) ->
        if (sources.isEmpty()) {
            writeCsvRow(writer, listOf(word.word, word.definition.orEmpty(), "", "", word.language))
        } else {
            sources.forEach {
                writeCsvRow(writer, listOf(word.word, word.definition.orEmpty(), it.sentence.orEmpty(), it.bookTitle, word.language))
            }
        }
    }
}

private fun writeCsvRow(writer: Writer, fields: List<String>) {
    writer.write(fields.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
    writer.write("\r\n")
}

private fun readVocabularyCsv(contents: String, now: Long): List<VocabularyRecord> {
    var start = 0
    while (contents.getOrNull(start) == '#') {
        val end = contents.indexOf('\n', start).let { if (it < 0) contents.length else it }
        val header = contents.substring(start, end).trimEnd('\r')
        if (header != "#separator:comma" && header != "#html:false" && !header.startsWith("#columns:")) break
        start = (end + 1).coerceAtMost(contents.length)
    }
    val rows = parseVocabularyCsv(contents.substring(start))
    return rows.drop(if (rows.firstOrNull()?.map { it.trim().lowercase() } == csvColumns) 1 else 0)
        .map { row ->
            require(row.size == csvColumns.size)
            val (text, definition, sentence, book, language) = row
            val word = importedWord(text, language.ifBlank { "und" }, definition.checked(24000).ifBlank { null }, now)
            val source = if (sentence.isBlank() && book.isBlank()) emptyList() else listOf(
                VocabularyOccurrence(
                    bookUrl = if (book.isBlank()) "" else "import:${Normalizer.normalize(book, Normalizer.Form.NFC)}",
                    bookTitle = book.checked(4000),
                    locator = null,
                    locationKey = sentence.checked(4000),
                    sentence = sentence.ifBlank { null },
                    lastSeenAt = now,
                ),
            )
            VocabularyRecord(word, source)
        }
}

private fun parseVocabularyCsv(input: String): List<List<String>> = buildList {
    var position = 0
    while (position < input.length) {
        val row = mutableListOf<String>()
        while (true) {
            val field = StringBuilder()
            if (input.getOrNull(position) == '"') {
                position++
                while (true) {
                    require(position < input.length)
                    val character = input[position++]
                    if (character == '"') {
                        if (input.getOrNull(position) != '"') break
                        position++
                    }
                    field.append(character)
                }
            } else {
                while (position < input.length && input[position] != ',' && input[position] != '\n' && input[position] != '\r') {
                    require(input[position] != '"')
                    field.append(input[position++])
                }
            }
            row += field.toString()
            when (input.getOrNull(position)) {
                ',' -> position++
                '\r', '\n' -> {
                    if (input[position++] == '\r' && input.getOrNull(position) == '\n') position++
                    break
                }
                null -> break
                else -> throw IllegalArgumentException("Invalid CSV field")
            }
        }
        if (row.size != 1 || row[0].isNotBlank()) add(row)
    }
}

private fun importedWord(text: String, language: String, definition: String?, now: Long): VocabularyWord {
    val tag = vocabularyLanguage(language.checked(80))
    require(tag != "und" || language.equals("und", ignoreCase = true))
    val normalized = requireNotNull(normalizeVocabularyWord(text, tag))
    return VocabularyWord(word = text.trim(), normalized = normalized, language = tag, definition = definition,
        translationLanguage = null, addedAt = now, dueAt = now)
}

private fun String.checked(limit: Int): String = also { require(length <= limit && '\u0000' !in this) }
private fun JSONObject.optionalText(key: String, limit: Int): String? =
    if (isNull(key)) null else getString(key).checked(limit)
private fun JSONObject.interval(): Int = getInt("intervalMinutes").also { require(it in 0..2_764_800) }
private fun JSONObject.time(key: String, now: Long): Long =
    getLong(key).also { require(it in 0..(now + 200L * 365 * 24 * 60 * 60 * 1000)) }

private inline fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> =
    List(length()) { transform(getJSONObject(it)) }
