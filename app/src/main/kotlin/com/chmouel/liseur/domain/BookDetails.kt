package com.chmouel.liseur.domain

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * What a book says about itself, from one source, and how two sources
 * become the one answer the details screen shows (ADR-0016).
 *
 * Every text field here is somebody else's: an OPF the app did not write,
 * or a catalog it does not run. So everything is bounded on the way in,
 * and bounded again after the merge.
 */
enum class MetadataSource(val key: String) {
    CATALOG("catalog"),
    FILE("file"),
    ;

    companion object {
        /** Null for a source this build does not know, which is ignored rather than deleted. */
        fun of(key: String): MetadataSource? = entries.firstOrNull { it.key == key }
    }
}

data class Contributor(val role: String, val name: String)

data class Identifier(val scheme: String, val value: String)

/** The typed accessibility fields a publication declares, already filtered to known values. */
data class BookAccessibility(
    val conformsTo: List<String> = emptyList(),
    val accessModes: List<String> = emptyList(),
    /** Each entry is one sufficient set, its modes joined with commas. */
    val accessModesSufficient: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    val hazards: List<String> = emptyList(),
    val exemptions: List<String> = emptyList(),
    val summary: String? = null,
    val certifiedBy: String? = null,
    val certifierReport: String? = null,
) {
    val isEmpty: Boolean
        get() = conformsTo.isEmpty() && accessModes.isEmpty() && accessModesSufficient.isEmpty() &&
            features.isEmpty() && hazards.isEmpty() && exemptions.isEmpty() &&
            summary == null && certifiedBy == null && certifierReport == null
}

data class MetadataSnapshot(
    val subtitle: String? = null,
    val description: String? = null,
    val publishers: List<String> = emptyList(),
    val imprints: List<String> = emptyList(),
    /** ISO-8601 at whatever precision arrived: `1962`, `1962-04` or `1962-04-12`. */
    val published: String? = null,
    val languages: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val contributors: List<Contributor> = emptyList(),
    val identifiers: List<Identifier> = emptyList(),
    val pageCount: Int? = null,
    /** Measured on this device; only the file source has one. */
    val fileSizeBytes: Long? = null,
    /** Only the file source has one. */
    val accessibility: BookAccessibility? = null,
)

object BookDetailsBounds {
    const val RAW_DESCRIPTION_UTF16 = 65_536
    const val DESCRIPTION_CODE_POINTS = 4_000
    const val SUBTITLE = 256
    const val NAMES = 8
    const val NAME = 256
    const val SUBJECTS = 32
    const val SUBJECT = 128
    const val CONTRIBUTORS = 64
    const val ROLE = 64
    const val IDENTIFIERS = 8
    const val SCHEME = 64
    const val IDENTIFIER_VALUE = 256
    const val LANGUAGES = 8
    const val LANGUAGE = 64
    const val A11Y_LIST = 32
    const val A11Y_ENTRY = 64
    const val A11Y_TEXT = 1_000
    const val MAX_PAGES = 100_000
    const val MAX_SERIES_POSITION = 10_000.0
    const val MAX_BYTES = 1L shl 53
}

/** The roles the screen names; everything else is shown as a contributor. */
object ContributorRoles {
    const val AUTHOR = "author"
    const val TRANSLATOR = "translator"
    const val EDITOR = "editor"
    const val ILLUSTRATOR = "illustrator"
    const val NARRATOR = "narrator"
    const val CONTRIBUTOR = "contributor"

    /** MARC relator codes, Komga's role names and liseur-sync's, onto the few the screen knows. */
    fun of(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        null, "", "aut", "author", "writer", "creator", "cre" -> AUTHOR
        "trl", "translator" -> TRANSLATOR
        "edt", "editor" -> EDITOR
        "ill", "illustrator", "art", "artist", "penciller", "penciler", "inker", "colorist",
        "letterer", "cover", "cov",
        -> ILLUSTRATOR
        "nrt", "narrator", "reader" -> NARRATOR
        else -> CONTRIBUTOR
    }
}

/**
 * Cuts [raw] to at most [limit] UTF-16 units without splitting a
 * surrogate pair. Applied before any markup is parsed, because the parse
 * is the expensive step.
 */
fun capUtf16(raw: String, limit: Int): String {
    if (raw.length <= limit) return raw
    var end = limit
    if (end > 0 && Character.isHighSurrogate(raw[end - 1])) end--
    return raw.substring(0, end)
}

/** At most [limit] code points, the last one an ellipsis when anything was cut. */
fun capCodePoints(text: String, limit: Int): String {
    if (text.codePointCount(0, text.length) <= limit) return text
    val end = text.offsetByCodePoints(0, limit - 1)
    return text.substring(0, end).trimEnd() + "…"
}

/**
 * Tidies a description that has already been turned from markup into
 * text: no object replacement characters left from images, no runs of
 * blank lines, and no more than [BookDetailsBounds.DESCRIPTION_CODE_POINTS].
 */
fun cleanDescription(text: String?): String? {
    if (text == null) return null
    val lines = text.replace("\uFFFC", "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .map { it.replace(HORIZONTAL_SPACE, " ").trim() }
    val kept = mutableListOf<String>()
    for (line in lines) {
        if (line.isEmpty() && (kept.isEmpty() || kept.last().isEmpty())) continue
        kept += line
    }
    while (kept.lastOrNull()?.isEmpty() == true) kept.removeAt(kept.lastIndex)
    val joined = kept.joinToString("\n").ifEmpty { return null }
    return capCodePoints(joined, BookDetailsBounds.DESCRIPTION_CODE_POINTS)
}

/**
 * A publication date at the precision it was given, or null when it is
 * not a date. Anything after the day (a time, a zone) is dropped: a book
 * is published on a day, and keeping the zone would move it to another.
 *
 * Years before 1000 are refused: calibre writes 0101-01-01 for "unknown".
 */
fun normalizePublished(raw: String?): String? {
    val match = DATE.matchEntire(raw?.trim() ?: return null) ?: return null
    val (yearText, monthText, dayText) = match.destructured
    val year = yearText.toInt()
    if (year < 1000) return null
    if (monthText.isEmpty()) return yearText
    val month = monthText.toInt()
    if (month !in 1..12) return null
    if (dayText.isEmpty()) return YearMonth.of(year, month).toString()
    val day = dayText.toInt()
    return runCatching { LocalDate.of(year, month, day).toString() }.getOrNull()
}

/** The canonical spelling of a language tag, or the tag as written when it names nothing. */
fun canonicalLanguage(tag: String): String {
    val trimmed = tag.trim()
    val canonical = Locale.forLanguageTag(trimmed).toLanguageTag()
    return if (canonical == "und") trimmed else canonical
}

/**
 * An identifier split into its scheme and value. `urn:isbn:…` and
 * `urn:uuid:…` say their scheme inline; a bare string with no scheme is
 * kept with an empty one.
 */
fun identifierOf(raw: String?, scheme: String? = null): Identifier? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val given = scheme?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    if (given != null) return Identifier(given.removePrefix("urn:"), value)
    val urn = URN.matchEntire(value)
    if (urn != null) return Identifier(urn.groupValues[1].lowercase(Locale.ROOT), urn.groupValues[2].trim())
    return Identifier("", value)
}

fun validPageCount(count: Int?): Int? = count?.takeIf { it in 1..BookDetailsBounds.MAX_PAGES }

fun validByteSize(bytes: Long?): Long? = bytes?.takeIf { it in 0..BookDetailsBounds.MAX_BYTES }

fun validSeriesPosition(position: Double?): Double? =
    position?.takeIf { it.isFinite() && it >= 0 && it <= BookDetailsBounds.MAX_SERIES_POSITION }

/** One source's snapshot with every field normalised, deduplicated and bounded. */
fun MetadataSnapshot.bounded(): MetadataSnapshot = merged(this, null)

/**
 * The answer for one book from its two sources.
 *
 * Single values come from the catalog first, as the series do: a calibre
 * library has usually been curated, and the blurb on the server is the
 * one the reader chose. Lists are merged, catalog first, so a catalog
 * author never hides the file's translator. When a merged list is over
 * its bound the file keeps at least half of it.
 */
fun resolveMetadata(rows: Map<MetadataSource, MetadataSnapshot>): MetadataSnapshot? {
    if (rows.isEmpty()) return null
    return merged(rows[MetadataSource.CATALOG], rows[MetadataSource.FILE])
}

private fun merged(first: MetadataSnapshot?, second: MetadataSnapshot?): MetadataSnapshot {
    val b = BookDetailsBounds
    fun text(pick: (MetadataSnapshot) -> String?, limit: Int): String? =
        listOfNotNull(first, second).firstNotNullOfOrNull { snapshot ->
            pick(snapshot)?.let(::collapse)?.takeIf { it.isNotEmpty() }?.let { capCodePoints(it, limit) }
        }

    fun names(pick: (MetadataSnapshot) -> List<String>, count: Int, limit: Int): List<String> = mergeCapped(
        first?.let(pick).orEmpty().mapNotNull { name(it, limit) },
        second?.let(pick).orEmpty().mapNotNull { name(it, limit) },
        count,
        ::nameKey,
    )

    return MetadataSnapshot(
        subtitle = text({ it.subtitle }, b.SUBTITLE),
        description = listOfNotNull(first, second).firstNotNullOfOrNull { cleanDescription(it.description) },
        publishers = names({ it.publishers }, b.NAMES, b.NAME),
        imprints = names({ it.imprints }, b.NAMES, b.NAME),
        published = listOfNotNull(first, second).firstNotNullOfOrNull { normalizePublished(it.published) },
        languages = mergeCapped(
            first?.languages.orEmpty().mapNotNull(::language),
            second?.languages.orEmpty().mapNotNull(::language),
            b.LANGUAGES,
        ) { it.lowercase(Locale.ROOT) },
        subjects = names({ it.subjects }, b.SUBJECTS, b.SUBJECT),
        contributors = mergeCapped(
            first?.contributors.orEmpty().mapNotNull(::contributor),
            second?.contributors.orEmpty().mapNotNull(::contributor),
            b.CONTRIBUTORS,
        ) { it.role + "\u0000" + nameKey(it.name) },
        identifiers = mergeCapped(
            first?.identifiers.orEmpty().mapNotNull(::identifier),
            second?.identifiers.orEmpty().mapNotNull(::identifier),
            b.IDENTIFIERS,
            ::identifierKey,
        ),
        pageCount = validPageCount(first?.pageCount) ?: validPageCount(second?.pageCount),
        fileSizeBytes = (second ?: first)?.fileSizeBytes?.let(::validByteSize),
        accessibility = (second ?: first)?.accessibility?.let(::boundedAccessibility),
    )
}

/**
 * [first] then whatever [second] adds, each deduplicated by [key]. Over
 * [cap], [second] keeps at least half the room, so the first list cannot
 * spend the whole budget.
 */
internal fun <T> mergeCapped(first: List<T>, second: List<T>, cap: Int, key: (T) -> String): List<T> {
    val seen = HashSet<String>()
    val a = first.filter { seen.add(key(it)) }
    val b = second.filter { seen.add(key(it)) }
    if (a.size + b.size <= cap) return a + b
    val firstShare = maxOf(cap - b.size, cap / 2).coerceAtMost(a.size)
    return a.take(firstShare) + b.take(cap - firstShare)
}

private fun boundedAccessibility(a: BookAccessibility): BookAccessibility? {
    val b = BookDetailsBounds
    fun list(values: List<String>) = values.map(String::trim)
        .filter { it.isNotEmpty() && it.length <= b.A11Y_ENTRY }
        .distinct()
        .take(b.A11Y_LIST)

    fun text(value: String?, limit: Int) =
        value?.let(::collapse)?.takeIf { it.isNotEmpty() }?.let { capCodePoints(it, limit) }

    return BookAccessibility(
        conformsTo = list(a.conformsTo),
        accessModes = list(a.accessModes),
        accessModesSufficient = list(a.accessModesSufficient),
        features = list(a.features),
        hazards = list(a.hazards),
        exemptions = list(a.exemptions),
        summary = text(a.summary, b.A11Y_TEXT),
        certifiedBy = text(a.certifiedBy, b.NAME),
        certifierReport = text(a.certifierReport, b.A11Y_TEXT),
    ).takeUnless { it.isEmpty }
}

private fun name(raw: String, limit: Int): String? =
    collapse(raw).takeIf { it.isNotEmpty() }?.let { capCodePoints(it, limit) }

private fun language(raw: String): String? =
    raw.trim().takeIf { it.isNotEmpty() && it.length <= BookDetailsBounds.LANGUAGE }?.let(::canonicalLanguage)

private fun contributor(raw: Contributor): Contributor? {
    val name = name(raw.name, BookDetailsBounds.NAME) ?: return null
    return Contributor(capCodePoints(ContributorRoles.of(raw.role), BookDetailsBounds.ROLE), name)
}

private fun identifier(raw: Identifier): Identifier? {
    val value = raw.value.trim().takeIf { it.isNotEmpty() && it.length <= BookDetailsBounds.IDENTIFIER_VALUE }
        ?: return null
    val scheme = raw.scheme.trim().lowercase(Locale.ROOT)
    if (scheme.length > BookDetailsBounds.SCHEME) return null
    return Identifier(scheme, value)
}

private fun identifierKey(id: Identifier): String {
    val value = id.value.trim().lowercase(Locale.ROOT)
    val compared = if (id.scheme == "isbn") value.filterNot { it == '-' || it.isWhitespace() } else value
    return id.scheme + "\u0000" + compared
}

private fun nameKey(name: String): String = collapse(name).lowercase(Locale.ROOT)

private fun collapse(text: String): String = text.replace(WHITESPACE, " ").trim()

private val WHITESPACE = Regex("\\s+")
private val HORIZONTAL_SPACE = Regex("[\\t\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000 ]+")
private val DATE = Regex("""(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?(?:[T ].*)?""")
private val URN = Regex("""(?i)urn:([a-z0-9][a-z0-9-]{0,31}):(.+)""")
