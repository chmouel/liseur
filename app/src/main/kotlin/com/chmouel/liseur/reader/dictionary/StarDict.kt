package com.chmouel.liseur.reader.dictionary

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

/**
 * A parsed offline dictionary entry, structured so the reader's definition
 * card can set headwords, pronunciation, parts of speech, numbered senses
 * and etymologies with its own typography instead of raw HTML dumps.
 */
internal data class StarDictEntry(
    val headword: String,
    val pronunciation: String? = null,
    val sections: List<Section> = emptyList(),
    val notes: List<String> = emptyList(),
    val fallbackHtml: String? = null,
) {
    data class Section(
        val heading: String,
        val items: List<String>,
    )
}

internal class StarDict(directory: File) {
    private val metadata = File(directory, "ifo").readLines()
        .associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val name = metadata["bookname"].orEmpty()
    val author = metadata["author"].orEmpty()
    val website = metadata["website"].orEmpty()
    val wordCount = metadata["wordcount"]?.toIntOrNull() ?: 0
    val language = File(directory, "language").takeIf(File::isFile)?.readText()?.trim().orEmpty()
        .ifBlank { inferLanguage(name, metadata["description"].orEmpty()) }
    val isReaderDict = author.equals("reader.dict", ignoreCase = true) ||
        name.startsWith("reader.dict", ignoreCase = true) ||
        website.contains("reader-dict.com", ignoreCase = true)
    private val html = metadata["sametypesequence"] == "h"
    private val data = File(directory, "dict")
    private val index = Index(File(directory, "idx"), metadata["wordcount"], 8)
    private val synonyms = File(directory, "syn").takeIf(File::isFile)?.let {
        Index(it, metadata["synwordcount"], 4)
    }

    init {
        requirePack(
            name.isNotBlank() && metadata.containsKey("StarDict's dict ifo file") &&
                metadata["version"] in listOf("2.4.2", "3.0.0") &&
                metadata["idxoffsetbits"] in listOf(null, "32") &&
                metadata["sametypesequence"] in listOf("h", "m") &&
                metadata["idxfilesize"]?.toIntOrNull() == index.size &&
                (synonyms != null || metadata["synwordcount"] in listOf(null, "0")),
        )
        val dataSize = data.length()
        for (row in index.fields) {
            val offset = index.number(row)
            val size = index.number(row + 4)
            requirePack(size in 1..MAX_ENTRY && offset + size <= dataSize)
        }
        synonyms?.let { table ->
            for (row in table.fields) requirePack(table.number(row) < index.fields.size)
        }
    }

    fun lookup(word: String): List<StarDictEntry> {
        val normalized = Normalizer.normalize(word.trim(), Normalizer.Form.NFC)
        if (normalized.isEmpty()) return emptyList()
        val variants = buildList {
            add(normalized)
            val lower = normalized.lowercase(Locale.ROOT)
            if (lower != normalized) add(lower)
            val title = lower.replaceFirstChar { it.titlecase(Locale.ROOT) }
            if (title !in this) add(title)
        }

        val directMatches = linkedSetOf<Int>()
        for (variant in variants) {
            directMatches.addAll(index.find(variant.encodeToByteArray()))
        }

        val synonymTargets = linkedSetOf<Int>()
        synonyms?.let { table ->
            for (variant in variants) {
                for (synRow in table.find(variant.encodeToByteArray())) {
                    val target = table.number(table.fields[synRow]).toInt()
                    if (target !in directMatches) synonymTargets.add(target)
                }
            }
        }

        val ordered = buildList {
            addAll(directMatches)
            if (directMatches.isEmpty()) {
                // Inflected forms ("casas", "écoles") have no headword of their own:
                // prefer single-word lemmas over multi-word idioms that happen to list
                // the word in their synonym table.
                val sortedSynonyms = synonymTargets.sortedWith(
                    compareBy<Int>(
                        { ' ' in index.word(it) },
                        { index.word(it).length },
                    ),
                )
                addAll(sortedSynonyms.take(MAX_INFLECTION_ENTRIES))
            } else {
                // Direct headword exists ("agua", "casa"): include single-word lemmas
                // if this spelling is also an inflection of another word (e.g. "casas" -> "casa" + "casar"),
                // while skipping multi-word idioms ("tirarse al agua").
                val lemmas = synonymTargets.filter { target ->
                    val hw = index.word(target)
                    ' ' !in hw && hw.length <= normalized.length + 2
                }
                addAll(lemmas.take(MAX_DIRECT_LEMMAS))
            }
        }.take(MAX_TOTAL_ENTRIES)

        if (ordered.isEmpty()) return emptyList()
        return RandomAccessFile(data, "r").use { file ->
            ordered.map { ordinal ->
                val headword = index.word(ordinal)
                val row = index.fields[ordinal]
                file.seek(index.number(row))
                val raw = ByteArray(index.number(row + 4).toInt())
                file.readFully(raw)
                val content = raw.decodeToString()
                if (html) {
                    parseStarDictHtml(headword, content)
                } else {
                    parseStarDictPlain(headword, content)
                }
            }
        }
    }

    fun suggestions(word: String, checkCancellation: () -> Unit = {}): List<String> {
        val query = foldSuggestion(word.trim())
        if (query.length !in 3..32 || query.any { !it.isLetter() && it != '\'' && it != '-' }) {
            return emptyList()
        }
        val maxDistance = if (query.length < 6) 1 else 2
        val previous = IntArray(query.length + 1)
        val current = IntArray(query.length + 1)
        val matches = linkedMapOf<String, Int>()
        for (row in index.fields.indices) {
            if (row % 1024 == 0) checkCancellation()
            val headword = index.word(row, maxBytes = (query.length + maxDistance) * 4) ?: continue
            if (headword in matches || headword.any(Char::isWhitespace)) continue
            val candidate = foldSuggestion(headword)
            if (kotlin.math.abs(candidate.length - query.length) > maxDistance) continue
            val distance = suggestionDistance(query, candidate, maxDistance, previous, current)
            if (distance > maxDistance) continue
            val worst = matches.maxByOrNull { it.value }
            if (matches.size < 5 || worst != null && distance < worst.value) {
                if (matches.size == 5 && worst != null) matches.remove(worst.key)
                matches[headword] = distance
            }
        }
        return matches.entries.sortedBy { it.value }.map { it.key }
    }

    /**
     * A StarDict index read through a memory map, so the file's bytes live in
     * the page cache and not on the app heap; only one int per entry does.
     */
    private class Index(file: File, count: String?, private val fieldWidth: Int) {
        private val bytes: ByteBuffer = if (file.length() == 0L) {
            ByteBuffer.allocate(0)
        } else {
            FileChannel.open(file.toPath()).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        }
        val size = bytes.capacity()
        val fields = IntArray(
            count?.toIntOrNull()?.takeIf { it in 0..size / (fieldWidth + 2) }
                ?: throw IOException("Invalid StarDict entry count"),
        )

        init {
            var start = 0
            for (row in fields.indices) {
                var end = start
                while (end < size && bytes.get(end) != 0.toByte()) end++
                requirePack(end > start && end < size - fieldWidth)
                fields[row] = end + 1
                start = end + 1 + fieldWidth
            }
            requirePack(start == size)
        }

        fun word(row: Int): String = checkNotNull(word(row, Int.MAX_VALUE))

        fun word(row: Int, maxBytes: Int): String? {
            val start = if (row == 0) 0 else fields[row - 1] + fieldWidth
            val end = fields[row] - 1
            if (end - start > maxBytes) return null
            val out = ByteArray(end - start)
            for (i in out.indices) out[i] = bytes.get(start + i)
            return out.decodeToString()
        }

        fun number(position: Int): Long {
            var value = 0L
            for (i in position until position + 4) value = (value shl 8) or (bytes.get(i).toLong() and 255)
            return value
        }

        fun find(query: ByteArray): Set<Int> {
            var low = 0
            var high = fields.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (compare(middle, query) < 0) low = middle + 1 else high = middle
            }
            val matches = linkedSetOf<Int>()
            while (low < fields.size && matches.size < 16 && compare(low, query) == 0) matches.add(low++)
            return matches.sortedByDescending { exactEquals(it, query) }.toCollection(linkedSetOf())
        }

        private fun exactEquals(row: Int, query: ByteArray): Boolean {
            val start = if (row == 0) 0 else fields[row - 1] + fieldWidth
            val end = fields[row] - 1
            if (end - start != query.size) return false
            for (i in query.indices) if (bytes.get(start + i) != query[i]) return false
            return true
        }

        private fun compare(row: Int, query: ByteArray): Int {
            var position = if (row == 0) 0 else fields[row - 1] + fieldWidth
            for (character in query) {
                val candidate = bytes.get(position++)
                if (candidate == 0.toByte()) return -1
                val difference = folded(candidate) - folded(character)
                if (difference != 0) return difference
            }
            return if (bytes.get(position) == 0.toByte()) 0 else 1
        }

        private fun folded(byte: Byte): Int {
            val value = byte.toInt() and 255
            return if (value in 65..90) value + 32 else value
        }
    }

    companion object {
        private const val MAX_ENTRY = 2L * 1024 * 1024
        private const val MAX_INFLECTION_ENTRIES = 4
        private const val MAX_DIRECT_LEMMAS = 2
        private const val MAX_TOTAL_ENTRIES = 5
        private const val MAX_IGNORED_ENTRY = 64L * 1024 * 1024
        private const val MAX_ZIP_ENTRY = 1024L * 1024 * 1024
        private const val MAX_EXPANDED_ARCHIVE = 1200L * 1024 * 1024

        private val INLINE_MARKUP = Safelist.none().addTags(
            "b", "strong", "i", "em", "u", "s", "sub", "sup",
        )
        private val FALLBACK_MARKUP = Safelist.none().addTags(
            "p", "br", "b", "strong", "i", "em", "u", "s", "ol", "ul", "li", "sub", "sup", "blockquote",
        )

        fun unpack(input: InputStream, directory: File, checkCancelled: () -> Unit = {}): StarDict {
            var stem: String? = null
            val expandedBudget = ExpandedBudget(MAX_EXPANDED_ARCHIVE)
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    val extension = when {
                        name.endsWith(".dict.dz") -> "dict.dz"
                        else -> name.substringAfterLast('.', "")
                    }
                    if (!entry.isDirectory && extension in setOf("ifo", "idx", "syn", "dict", "dict.dz")) {
                        val prefix = name.removeSuffix(".$extension")
                        requirePack(stem == null || stem == prefix)
                        stem = prefix
                        val output = File(directory, extension.removeSuffix(".dz"))
                        requirePack(!output.exists())
                        val limit = when (extension) {
                            "ifo" -> 64L * 1024
                            "idx", "syn" -> 64L * 1024 * 1024
                            else -> 1024L * 1024 * 1024
                        }
                        output.outputStream().use { file ->
                            val destination = file.buffered()
                            if (extension == "dict.dz") {
                                val entryStream = CheckedZipEntryInputStream(
                                    zip,
                                    MAX_ZIP_ENTRY,
                                    checkCancelled,
                                    expandedBudget,
                                )
                                GZIPInputStream(entryStream).use {
                                    it.copyBounded(destination, limit, checkCancelled, expandedBudget)
                                }
                                // GZIPInputStream may stop at the first member. Drain any suffix
                                // through the same meter so it remains bounded and cancellable.
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    checkCancelled()
                                    if (entryStream.read(buffer) < 0) break
                                }
                            } else {
                                zip.copyBounded(destination, limit, checkCancelled, expandedBudget)
                            }
                            destination.flush()
                            file.fd.sync()
                        }
                    } else {
                        zip.copyBounded(
                            object : OutputStream() {
                                override fun write(byte: Int) = Unit
                                override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
                            },
                            MAX_IGNORED_ENTRY,
                            checkCancelled,
                            expandedBudget,
                        )
                    }
                    checkCancelled()
                    entry = zip.nextEntry
                }
            }
            requirePack(File(directory, "ifo").isFile && File(directory, "idx").isFile && File(directory, "dict").isFile)
            val packName = stem.orEmpty()
            val inferred = inferLanguageFromStem(packName)
            if (inferred.isNotBlank()) {
                File(directory, "language").outputStream().use { file ->
                    file.write(inferred.encodeToByteArray())
                    file.fd.sync()
                }
            }
            checkCancelled()
            return StarDict(directory)
        }

        private fun inferLanguageFromStem(stem: String): String {
            if (stem.startsWith("reader.dict-")) {
                return stem.removePrefix("reader.dict-").substringBefore('-').lowercase(Locale.ROOT)
            }
            val dictMatch = Regex("^dict-([a-z]{2,3})(?:-[a-z]{2,3})?$", RegexOption.IGNORE_CASE).find(stem)
            if (dictMatch != null) return dictMatch.groupValues[1].lowercase(Locale.ROOT)
            return ""
        }

        private fun inferLanguage(bookname: String, description: String): String {
            val readerDict = Regex("^reader\\.dict\\s+([A-Za-z]{2,3})\\b").find(bookname)
            if (readerDict != null) return readerDict.groupValues[1].lowercase(Locale.ROOT)
            val codeInParens = Regex("\\(([a-z]{2,3})\\)", RegexOption.IGNORE_CASE).find("$bookname $description")
            if (codeInParens != null) {
                val candidate = codeInParens.groupValues[1].lowercase(Locale.ROOT)
                if (Locale.getISOLanguages().contains(candidate)) return candidate
            }
            return ""
        }
    }
}

internal fun parseStarDictHtml(headword: String, rawHtml: String): StarDictEntry {
    val body = Jsoup.parseBodyFragment(rawHtml).body()
    body.select("script, style, img, svg, iframe, object").remove()

    var pronunciation: String? = null
    val sections = mutableListOf<StarDictEntry.Section>()
    val notes = mutableListOf<String>()
    var pendingHeading: String? = null

    fun flushHeadingAsNote() {
        val orphan = pendingHeading
        if (orphan != null) {
            notes += orphan
            pendingHeading = null
        }
    }

    val children = body.childNodes()
    for (node in children) {
        when (node) {
            is TextNode -> {
                val text = cleanText(node.text())
                if (text.isBlank()) continue
                if (pronunciation == null && sections.isEmpty() && pendingHeading == null && looksLikePronunciation(text)) {
                    pronunciation = text
                } else {
                    flushHeadingAsNote()
                    notes += org.jsoup.nodes.Entities.escape(text)
                }
            }
            is Element -> {
                val tag = node.tagName().lowercase(Locale.ROOT)
                when (tag) {
                    "p", "div", "h1", "h2", "h3", "h4" -> {
                        val heading = extractSectionHeading(node)
                        if (heading != null) {
                            flushHeadingAsNote()
                            pendingHeading = heading
                        } else {
                            val text = cleanText(node.text())
                            if (pronunciation == null && sections.isEmpty() && pendingHeading == null && looksLikePronunciation(text)) {
                                pronunciation = text
                            } else {
                                val cleaned = cleanInlineHtml(node.html())
                                if (cleaned.isNotBlank()) {
                                    flushHeadingAsNote()
                                    notes += cleaned
                                }
                            }
                        }
                    }
                    "ol", "ul" -> {
                        val items = extractListItems(node)
                        if (items.isNotEmpty()) {
                            sections += StarDictEntry.Section(
                                heading = pendingHeading.orEmpty(),
                                items = items,
                            )
                            pendingHeading = null
                        }
                    }
                    "dl" -> {
                        val items = node.select("> dd")
                            .map { cleanInlineHtml(it.html()) }
                            .filter(String::isNotBlank)
                        if (items.isNotEmpty()) {
                            sections += StarDictEntry.Section(
                                heading = pendingHeading.orEmpty(),
                                items = items,
                            )
                            pendingHeading = null
                        }
                    }
                    else -> {
                        val cleaned = cleanInlineHtml(node.outerHtml())
                        if (cleaned.isNotBlank()) {
                            flushHeadingAsNote()
                            notes += cleaned
                        }
                    }
                }
            }
        }
    }

    if (sections.isEmpty() && pendingHeading != null) {
        // An entry like "<b>School</b>" with no list underneath: treat it as a direct sense,
        // not a dangling heading.
        val cleaned = cleanInlineHtml(body.html())
        return StarDictEntry(
            headword = headword,
            pronunciation = pronunciation,
            sections = if (cleaned.isNotBlank()) {
                listOf(StarDictEntry.Section(heading = "", items = listOf(cleaned)))
            } else {
                emptyList()
            },
        )
    }
    flushHeadingAsNote()

    if (sections.isEmpty() && notes.isEmpty()) {
        val fallback = Jsoup.clean(body.html(), Safelist.none().addTags(
            "p", "br", "b", "strong", "i", "em", "u", "s", "ol", "ul", "li", "sub", "sup", "blockquote",
        )).trim()
        return StarDictEntry(
            headword = headword,
            pronunciation = pronunciation,
            fallbackHtml = fallback.ifBlank { null },
        )
    }

    return StarDictEntry(
        headword = headword,
        pronunciation = pronunciation,
        sections = sections,
        notes = notes,
    )
}

private fun parseStarDictPlain(headword: String, content: String): StarDictEntry {
    val lines = content.lines().map { it.trim() }.filter(String::isNotBlank)
    if (lines.isEmpty()) return StarDictEntry(headword = headword)
    return StarDictEntry(
        headword = headword,
        sections = listOf(
            StarDictEntry.Section(
                heading = "",
                items = lines.map { org.jsoup.nodes.Entities.escape(it) },
            ),
        ),
    )
}

private fun extractSectionHeading(element: Element): String? {
    if (element.tagName().lowercase(Locale.ROOT) in setOf("h1", "h2", "h3", "h4")) {
        return cleanText(element.text()).takeIf(String::isNotBlank)
    }
    // reader.dict wraps part-of-speech headers in `<p><b>Noun</b> <i>f.</i></p>`.
    val firstChild = element.childNodes().firstOrNull { it !is TextNode || it.text().isNotBlank() }
    if (firstChild is Element && firstChild.tagName().lowercase(Locale.ROOT) in setOf("b", "strong")) {
        val full = cleanText(element.text())
        if (full.length in 1..60 && !full.endsWith('.')) {
            return full
        }
        // Allow gender abbreviations like "Nomen Substantivum f."
        val bold = cleanText(firstChild.text())
        val rest = full.removePrefix(bold).trim()
        if (bold.length in 1..48 && rest.length <= 16) {
            return full
        }
    }
    return null
}

private fun extractListItems(list: Element): List<String> {
    val out = mutableListOf<String>()
    for (child in list.children()) {
        when (child.tagName().lowercase(Locale.ROOT)) {
            "li" -> {
                val copy = child.clone()
                val nested = copy.select("> ol, > ul").toList()
                nested.forEach(Node::remove)
                val main = cleanSenseMarkup(copy.html())
                if (main.isNotBlank()) out += main
                for (sub in nested) {
                    for (subItem in sub.select("> li")) {
                        val subText = cleanSenseMarkup(subItem.html())
                        if (subText.isNotBlank()) out += "— $subText"
                    }
                }
            }
            "ol", "ul" -> {
                // reader.dict sometimes nests `<ol style="list-style-type:lower-alpha">` directly inside `<ol>`.
                for (subItem in child.select("> li")) {
                    val subText = cleanSenseMarkup(subItem.html())
                    if (subText.isNotBlank()) out += "— $subText"
                }
            }
        }
    }
    return out
}

private fun cleanSenseMarkup(html: String): String {
    val cleaned = cleanInlineHtml(html)
        .replace(LEADING_CHECKMARK, "")
        .replace(TEMPLATE_LEAK, "")
        .trim()
    return cleaned
}

private fun cleanInlineHtml(html: String): String =
    Jsoup.clean(
        html,
        Safelist.none().addTags("b", "strong", "i", "em", "u", "s", "sub", "sup"),
    ).replace(TEMPLATE_LEAK, "").replace(MULTI_SPACE, " ").trim()

private fun cleanText(text: String): String =
    text.replace('\u00A0', ' ').replace(MULTI_SPACE, " ").trim()

private fun looksLikePronunciation(text: String): Boolean =
    text.length <= 96 && ('/' in text || ('[' in text && ']' in text))

private val LEADING_CHECKMARK = Regex("^√\\s*")
private val TEMPLATE_LEAK = Regex(":Template:[A-Za-z0-9_-]+\\s*")
private val MULTI_SPACE = Regex("\\s+")

private fun requirePack(condition: Boolean) {
    if (!condition) throw IOException("Invalid or unsupported StarDict archive")
}

internal class ExpandedBudget(private val limit: Long) {
    private var total = 0L

    fun consume(size: Int) {
        total += size
        requirePack(total <= limit)
    }
}

internal class CheckedZipEntryInputStream(
    input: InputStream,
    private val limit: Long,
    private val checkCancelled: () -> Unit,
    private val budget: ExpandedBudget,
) : FilterInputStream(input) {
    private var total = 0L

    override fun read(): Int {
        checkCancelled()
        checkBudget()
        val value = super.read()
        if (value >= 0) consume(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        checkCancelled()
        checkBudget()
        val size = super.read(buffer, offset, length)
        if (size > 0) consume(size)
        return size
    }

    override fun close() = Unit

    private fun consume(size: Int) {
        total += size
        requirePack(total <= limit)
        budget.consume(size)
    }

    private fun checkBudget() {
        requirePack(total <= limit)
        budget.consume(0)
    }
}

private fun InputStream.copyBounded(
    output: OutputStream,
    limit: Long,
    checkCancelled: () -> Unit,
    budget: ExpandedBudget,
) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        checkCancelled()
        val size = read(buffer)
        if (size < 0) return
        total += size
        requirePack(total <= limit)
        budget.consume(size)
        output.write(buffer, 0, size)
    }
}


private val suggestionMarks = Regex("""\p{M}+""")

private fun foldSuggestion(text: String): String =
    Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(suggestionMarks, "")

private fun suggestionDistance(
    query: String,
    candidate: String,
    limit: Int,
    first: IntArray,
    second: IntArray,
): Int {
    var previous = first
    var current = second
    for (column in previous.indices) previous[column] = column
    candidate.forEachIndexed { row, letter ->
        current[0] = row + 1
        var minimum = current[0]
        for (column in 1..query.length) {
            current[column] = minOf(
                current[column - 1] + 1,
                previous[column] + 1,
                previous[column - 1] + if (letter == query[column - 1]) 0 else 1,
            )
            minimum = minOf(minimum, current[column])
        }
        if (minimum > limit) return limit + 1
        val completed = current
        current = previous
        previous = completed
    }
    return previous[query.length]
}
