package com.chmouel.liseur.data.bookorbit

/**
 * A parsed EPUB CFI reported by BookOrbit.
 *
 * This type deliberately does not resolve a CFI or turn it into a Readium
 * locator. That needs the local publication and belongs to the next phase.
 * Keeping the full structure here lets the caller retain a foreign CFI
 * without pretending a percentage is an exact replacement for it.
 *
 * The grammar followed is EPUB CFI 1.1: a path is a step, then any number
 * of steps and indirections, then at most one terminating offset. A text
 * location assertion and side bias live inside the brackets after the
 * character offset (`/1:18[before,after;s=b]`), as foliate-js writes them.
 */
sealed interface BookOrbitCfi {
    val raw: String

    /** The exact CFI supplied by BookOrbit, assertions and escapes intact. */
    fun serialize(): String = raw

    data class Point(
        override val raw: String,
        val path: Path,
    ) : BookOrbitCfi

    /**
     * Both endpoints are kept: a restore may use [start], but annotations
     * need the whole range. [start] and [end] are local paths relative to
     * [parent] and may be a bare offset or an empty start path.
     */
    data class Range(
        override val raw: String,
        val parent: Path,
        val start: Path,
        val end: Path,
    ) : BookOrbitCfi

    data class Path(
        val components: List<Component>,
        val offset: Offset?,
    )

    sealed interface Component {
        data object Indirection : Component

        /**
         * An even [index] names an element, an odd one the text between
         * elements. [id] is the step's id assertion.
         */
        data class Step(
            val index: Int,
            val id: String? = null,
            val parameters: Map<String, List<String>> = emptyMap(),
        ) : Component
    }

    /**
     * A character offset with its optional text location assertion.
     * Unknown parameters are kept, as the specification asks readers to
     * ignore rather than refuse them.
     */
    data class Offset(
        val character: Int,
        val textBefore: String? = null,
        val textAfter: String? = null,
        val sideBias: SideBias? = null,
        val parameters: Map<String, List<String>> = emptyMap(),
    )

    enum class SideBias(val wireValue: String) {
        BEFORE("b"),
        AFTER("a"),
        ;

        companion object {
            fun fromWire(value: String): SideBias? = entries.firstOrNull { it.wireValue == value }
        }
    }

    companion object {
        /**
         * Parses a CFI that can identify a text or element position.
         * Temporal and spatial offsets fail as unsupported: using either
         * as a text position would be a lossy guess.
         */
        fun parse(raw: String): BookOrbitCfi {
            if (!raw.startsWith(PREFIX) || !raw.endsWith(')')) {
                throw ParseException("CFI must be wrapped in epubcfi(...)")
            }
            val body = raw.substring(PREFIX.length, raw.length - 1)
            if (body.isEmpty()) throw ParseException("CFI is empty")
            val parts = splitUnescaped(body, ',', outsideBrackets = true)
            return when (parts.size) {
                1 -> Point(raw, parsePath(parts.single(), "point", local = false))
                3 -> {
                    // The indirection that crosses into a content document
                    // may sit at the end of the parent (foliate's
                    // `/6/4!,/4/..,/6/..`) or open each endpoint
                    // (`/6/4,!/4/..,!/4/..`); either way it applies to the
                    // parent's last step.
                    val parent = parsePath(parts[0], "range parent", local = false, openEnded = true)
                    if (parent.offset != null) throw ParseException("range parent must not end in an offset")
                    val parentRedirects = parent.components.last() is Component.Indirection
                    val start = if (parts[1].isEmpty()) {
                        Path(emptyList(), null)
                    } else {
                        parsePath(parts[1], "range start", local = true)
                    }
                    val end = parsePath(parts[2], "range end", local = true)
                    listOf(start, end).forEach { endpoint ->
                        val opens = endpoint.components.firstOrNull() is Component.Indirection
                        if (parentRedirects && opens) throw ParseException("range repeats its indirection")
                        if (parentRedirects && endpoint.components.isEmpty()) {
                            throw ParseException("range parent ends in an indirection with no step after it")
                        }
                    }
                    Range(raw = raw, parent = parent, start = start, end = end)
                }
                else -> throw ParseException("CFI range must have a parent, start and end")
            }
        }

        private fun parsePath(
            source: String,
            label: String,
            local: Boolean,
            openEnded: Boolean = false,
        ): Path {
            if (source.isEmpty()) throw ParseException("$label path is empty")
            if (!local && !source.startsWith('/')) throw ParseException("$label must start with a step")
            val components = mutableListOf<Component>()
            var offset: Offset? = null
            var at = 0
            while (at < source.length) {
                if (offset != null) throw ParseException("$label continues after its offset")
                when (source[at]) {
                    '!' -> {
                        val previous = components.lastOrNull()
                        val leadsLocalPath = local && previous == null
                        if (previous !is Component.Step && !leadsLocalPath) {
                            throw ParseException("$label has an indirection without a preceding step")
                        }
                        components += Component.Indirection
                        at++
                    }
                    '/' -> {
                        at++
                        val (index, next) = integer(source, at, "$label step")
                        at = next
                        var id: String? = null
                        var parameters = emptyMap<String, List<String>>()
                        if (source.getOrNull(at) == '[') {
                            val section = bracketed(source, at, label)
                            at = section.next
                            val assertion = assertion(section.body, label)
                            if (assertion.values.size > 1) {
                                throw ParseException("$label step assertion has more than one id")
                            }
                            id = assertion.values.firstOrNull()?.ifEmpty { null }
                            parameters = assertion.parameters
                        }
                        components += Component.Step(index, id, parameters)
                    }
                    ':' -> {
                        at++
                        val (character, next) = integer(source, at, "$label offset")
                        at = next
                        var assertion = Assertion(emptyList(), emptyMap())
                        if (source.getOrNull(at) == '[') {
                            val section = bracketed(source, at, label)
                            at = section.next
                            assertion = assertion(section.body, label)
                        }
                        if (assertion.values.size > 2) {
                            throw ParseException("$label text assertion has more than two values")
                        }
                        val side = assertion.parameters["s"]?.let { values ->
                            values.singleOrNull()?.let(SideBias::fromWire)
                                ?: throw ParseException("$label has an invalid side bias")
                        }
                        offset = Offset(
                            character = character,
                            textBefore = assertion.values.getOrNull(0)?.ifEmpty { null },
                            textAfter = assertion.values.getOrNull(1)?.ifEmpty { null },
                            sideBias = side,
                            parameters = assertion.parameters - "s",
                        )
                    }
                    '~', '@' -> throw UnsupportedException("$label uses temporal or spatial CFI offsets")
                    else -> throw ParseException("$label has an unexpected '${source[at]}'")
                }
            }
            if (components.lastOrNull() is Component.Indirection && offset == null && !openEnded) {
                throw ParseException("$label ends in an indirection")
            }
            if (!local && components.none { it is Component.Step }) {
                throw ParseException("$label has no steps")
            }
            return Path(components, offset)
        }

        private fun integer(source: String, start: Int, label: String): Pair<Int, Int> {
            var at = start
            while (source.getOrNull(at)?.isDigit() == true) at++
            if (at == start) throw ParseException("$label has no value")
            val value = source.substring(start, at).toIntOrNull()
                ?: throw ParseException("$label is too large")
            return value to at
        }

        /** The still-escaped text between `[` at [open] and its `]`. */
        private fun bracketed(source: String, open: Int, label: String): Section {
            var at = open + 1
            while (at < source.length) {
                when (source[at]) {
                    '^' -> at += 2
                    ']' -> return Section(source.substring(open + 1, at), at + 1)
                    '[' -> throw ParseException("$label has an unescaped '[' in an assertion")
                    else -> at++
                }
            }
            throw ParseException("$label has an unterminated assertion")
        }

        /**
         * Splits an assertion into values and `;name=value` parameters.
         * The split happens on the escaped text, so `^;` and `^,` stay
         * part of a value instead of being read as delimiters.
         */
        private fun assertion(body: String, label: String): Assertion {
            val pieces = splitUnescaped(body, ';', outsideBrackets = false)
            val values = pieces.first().let { head ->
                if (head.isEmpty()) emptyList() else splitUnescaped(head, ',', outsideBrackets = false).map(::unescape)
            }
            val parameters = linkedMapOf<String, List<String>>()
            pieces.drop(1).forEach { parameter ->
                val equals = unescapedEquals(parameter)
                if (equals <= 0) throw ParseException("$label has a malformed assertion parameter")
                val name = unescape(parameter.substring(0, equals))
                if (name.any(Char::isWhitespace)) {
                    throw ParseException("$label has a malformed assertion parameter")
                }
                if (name in parameters) {
                    throw ParseException("$label has a duplicate assertion parameter $name")
                }
                parameters[name] = splitUnescaped(parameter.substring(equals + 1), ',', outsideBrackets = false)
                    .map(::unescape)
            }
            return Assertion(values, parameters)
        }

        private fun unescapedEquals(parameter: String): Int {
            var at = 0
            while (at < parameter.length) {
                when (parameter[at]) {
                    '^' -> at++
                    '=' -> return at
                }
                at++
            }
            return -1
        }

        private fun splitUnescaped(source: String, delimiter: Char, outsideBrackets: Boolean): List<String> {
            val pieces = mutableListOf<String>()
            var start = 0
            var depth = 0
            var at = 0
            while (at < source.length) {
                val char = source[at]
                when {
                    char == '^' -> {
                        if (at + 1 >= source.length) throw ParseException("CFI ends in a dangling escape")
                        at++
                    }
                    outsideBrackets && char == '[' -> depth++
                    outsideBrackets && char == ']' -> {
                        if (depth == 0) throw ParseException("unmatched CFI assertion bracket")
                        depth--
                    }
                    char == delimiter && depth == 0 -> {
                        pieces += source.substring(start, at)
                        start = at + 1
                    }
                }
                at++
            }
            if (depth != 0) throw ParseException("unterminated CFI assertion")
            pieces += source.substring(start)
            return pieces
        }

        private fun unescape(value: String): String {
            val out = StringBuilder(value.length)
            var at = 0
            while (at < value.length) {
                val char = value[at++]
                if (char == '^') out.append(value[at++]) else out.append(char)
            }
            return out.toString()
        }

        private data class Section(val body: String, val next: Int)

        private data class Assertion(
            val values: List<String>,
            val parameters: Map<String, List<String>>,
        )

        private const val PREFIX = "epubcfi("
    }

    open class ParseException(message: String) : IllegalArgumentException(message)

    class UnsupportedException(message: String) : ParseException(message)
}

/**
 * A foreign CFI tied to the precise BookOrbit file and local binding that
 * supplied it. It is an unresolved record until Phase 2 verifies it against
 * the downloaded EPUB, so callers must not treat [parsed] as a locator.
 */
data class BookOrbitForeignCfi(
    val accountKey: String,
    val bookId: Long,
    val fileId: Long,
    val bindingRevision: Long,
    val raw: String,
    val parsed: BookOrbitCfi?,
    val unresolvedReason: String?,
) {
    init {
        require((parsed == null) == (unresolvedReason != null))
    }

    companion object {
        fun capture(
            accountKey: String,
            bookId: Long,
            fileId: Long,
            bindingRevision: Long,
            raw: String,
        ): BookOrbitForeignCfi {
            val (parsed, reason) = try {
                BookOrbitCfi.parse(raw) to null
            } catch (error: BookOrbitCfi.ParseException) {
                null to (error.message ?: "unparseable CFI")
            }
            return BookOrbitForeignCfi(accountKey, bookId, fileId, bindingRevision, raw, parsed, reason)
        }
    }
}
