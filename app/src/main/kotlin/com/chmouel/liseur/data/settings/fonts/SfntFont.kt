package com.chmouel.liseur.data.settings.fonts

/**
 * What Liseur needs to know about a font file it has been handed.
 *
 * @param format decided by the file's magic, and the only thing allowed to
 *   decide the stored extension — a reader can name a file anything.
 * @param familyName the font's own name for itself, or null when it has no
 *   usable name record and the caller has to fall back to the picked
 *   filename.
 * @param weightRange the `wght` axis of a variable font, already clamped
 *   into the range CSS and Readium accept.
 */
data class SfntMetadata(
    val format: SfntFormat,
    val familyName: String?,
    val italic: Boolean,
    val weight: Int,
    val weightRange: IntRange?,
)

enum class SfntFormat(val extension: String) {
    TRUE_TYPE("ttf"),
    OPEN_TYPE("otf"),
}

/**
 * Just enough OpenType to answer "is this a font, and what is it called".
 *
 * Reads the `name`, `head`, `OS/2` and `fvar` tables and nothing else.
 * Deliberately pure Kotlin over a [ByteArray]: it is fed a file the reader
 * picked out of a file manager, which is to say a hostile byte string, and
 * being testable off a device is what makes it possible to say so with
 * confidence.
 *
 * Every read is bounds-checked and every malformed input returns null. It
 * never throws — the caller is a file picker callback, and a
 * [ArrayIndexOutOfBoundsException] there would take down the reader
 * because someone chose the wrong file.
 */
object SfntFont {

    /** Collections, WOFF and WOFF2 are recognised only so they can be refused. */
    fun parse(bytes: ByteArray): SfntMetadata? {
        val format = formatOf(bytes) ?: return null

        val numTables = bytes.u16(4) ?: return null
        if (numTables == 0 || numTables > MAX_TABLES) return null

        val tables = HashMap<String, IntRange>(numTables)
        for (i in 0 until numTables) {
            val record = TABLE_DIRECTORY + i * TABLE_RECORD
            val tag = bytes.ascii(record, 4) ?: return null
            val offset = bytes.u32(record + 8) ?: return null
            val length = bytes.u32(record + 12) ?: return null
            // Not a failure: a font is allowed tables Liseur has no use
            // for, and one of those being out of bounds says nothing about
            // the ones it does read. The tables that matter are checked
            // where they are used.
            if (!bytes.holds(offset, length)) continue
            tables.putIfAbsent(tag, offset until offset + length)
        }

        val head = tables["head"]?.let { readHead(bytes, it) }
        val os2 = tables["OS/2"]?.let { readOs2(bytes, it) }
        val fvar = tables["fvar"]?.let { readWeightAxis(bytes, it) }

        // A font with no readable name table is still a usable font; it
        // just has to borrow the name of the file it came in.
        val family = tables["name"]?.let { readFamilyName(bytes, it) }

        val italic = os2?.italic ?: head?.italic ?: false
        val bold = os2?.bold ?: head?.bold ?: false
        val weight = os2?.weightClass?.takeIf { it in CSS_WEIGHTS }
            ?: if (bold) BOLD_WEIGHT else NORMAL_WEIGHT

        return SfntMetadata(
            format = format,
            familyName = family,
            italic = italic,
            weight = weight,
            weightRange = fvar,
        )
    }

    private fun formatOf(bytes: ByteArray): SfntFormat? = when (bytes.u32(0)) {
        0x00010000, TAG_TRUE -> SfntFormat.TRUE_TYPE
        TAG_OTTO -> SfntFormat.OPEN_TYPE
        else -> null
    }

    // -- name ---------------------------------------------------------------

    /**
     * The family name, preferring the typographic one.
     *
     * A font whose four styles are one family to a typographer but four
     * families to Windows carries both names: nameID 16 is the one a
     * reader would recognise, nameID 1 the compatibility spelling. Take 16
     * where it exists.
     */
    private fun readFamilyName(bytes: ByteArray, table: IntRange): String? {
        val base = table.first
        val count = bytes.u16(base + 2) ?: return null
        val storage = bytes.u16(base + 4)?.let { base + it } ?: return null
        if (count > MAX_NAME_RECORDS) return null

        var best: Candidate? = null
        for (i in 0 until count) {
            val record = base + NAME_RECORD_START + i * NAME_RECORD
            if (record + NAME_RECORD > table.last + 1) break

            val platform = bytes.u16(record) ?: break
            val encoding = bytes.u16(record + 2) ?: break
            val language = bytes.u16(record + 4) ?: break
            val nameId = bytes.u16(record + 6) ?: break
            if (nameId != NAME_TYPOGRAPHIC_FAMILY && nameId != NAME_FAMILY) continue

            val rank = rank(platform, encoding, language, nameId) ?: continue
            if (best != null && rank >= best.rank) continue

            val length = bytes.u16(record + 8) ?: break
            val offset = bytes.u16(record + 10) ?: break
            val value = decode(bytes, storage + offset, length, platform, encoding) ?: continue
            best = Candidate(rank, value)
        }
        return best?.value
    }

    private data class Candidate(val rank: Int, val value: String)

    /**
     * How much a name record is wanted, lower being better.
     *
     * nameID 16 outranks nameID 1 outright; within a nameID, the English
     * Windows record is the one nearly every font gets right, then any
     * Windows record, then the platform-independent Unicode one, and Mac
     * last because MacRoman can only be decoded for Latin.
     */
    private fun rank(platform: Int, encoding: Int, language: Int, nameId: Int): Int? {
        val nameRank = if (nameId == NAME_TYPOGRAPHIC_FAMILY) 0 else 100
        val platformRank = when {
            platform == PLATFORM_WINDOWS && encoding in WINDOWS_UNICODE && language == LANG_EN_US -> 0
            platform == PLATFORM_WINDOWS && encoding in WINDOWS_UNICODE -> 1
            platform == PLATFORM_UNICODE -> 2
            platform == PLATFORM_MAC && encoding == MAC_ROMAN -> 3
            else -> return null
        }
        return nameRank + platformRank
    }

    private fun decode(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        platform: Int,
        encoding: Int,
    ): String? {
        if (length == 0 || length > MAX_NAME_BYTES) return null
        if (!bytes.holds(offset, length)) return null
        val slice = bytes.copyOfRange(offset, offset + length)
        val text = when {
            platform == PLATFORM_MAC && encoding == MAC_ROMAN ->
                // Latin-1 is not MacRoman, but they agree on ASCII, which
                // is all a Mac-only family name is ever going to be here.
                String(slice, Charsets.ISO_8859_1)
            encoding == WINDOWS_UCS4 -> String(slice, Charsets.UTF_32BE)
            else -> String(slice, Charsets.UTF_16BE)
        }
        return FontNames.sanitize(text)
    }

    // -- head, OS/2, fvar ---------------------------------------------------

    private data class Style(val italic: Boolean, val bold: Boolean)

    private fun readHead(bytes: ByteArray, table: IntRange): Style? {
        val macStyle = bytes.u16(table.first + HEAD_MAC_STYLE) ?: return null
        if (table.first + HEAD_MAC_STYLE + 2 > table.last + 1) return null
        return Style(italic = macStyle and 0b10 != 0, bold = macStyle and 0b1 != 0)
    }

    private data class Os2(val italic: Boolean, val bold: Boolean, val weightClass: Int)

    private fun readOs2(bytes: ByteArray, table: IntRange): Os2? {
        if (table.last + 1 - table.first < OS2_MIN_LENGTH) return null
        val weightClass = bytes.u16(table.first + OS2_WEIGHT_CLASS) ?: return null
        val selection = bytes.u16(table.first + OS2_SELECTION) ?: return null
        return Os2(
            italic = selection and OS2_ITALIC != 0,
            bold = selection and OS2_BOLD != 0,
            weightClass = weightClass,
        )
    }

    /**
     * The `wght` axis of a variable font, clamped into what CSS allows.
     *
     * Readium's `setFontWeight(ClosedRange<Int>)` asserts `1..1000`, so an
     * unclamped range out of a font with a nonsense `fvar` would throw
     * while the navigator was being configured — the reader would lose the
     * book, not the font. Clamping here means the assertion can never be
     * reached from a file a reader picked.
     */
    private fun readWeightAxis(bytes: ByteArray, table: IntRange): IntRange? {
        val base = table.first
        val axesOffset = bytes.u16(base + 4) ?: return null
        val axisCount = bytes.u16(base + 8) ?: return null
        val axisSize = bytes.u16(base + 10) ?: return null
        if (axisCount == 0 || axisCount > MAX_AXES || axisSize < FVAR_AXIS) return null

        for (i in 0 until axisCount) {
            val axis = base + axesOffset + i * axisSize
            if (!bytes.holds(axis, FVAR_AXIS)) return null
            if (bytes.ascii(axis, 4) != "wght") continue

            val min = bytes.fixed(axis + 4) ?: return null
            val max = bytes.fixed(axis + 12) ?: return null
            val low = min.coerceIn(CSS_WEIGHTS)
            val high = max.coerceIn(CSS_WEIGHTS)
            // A font claiming a maximum below its minimum has told us
            // nothing usable. Better a static weight than a range Readium
            // will refuse.
            return if (low <= high) low..high else null
        }
        return null
    }

    // -- bounds-checked reads -----------------------------------------------

    private fun ByteArray.holds(offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset <= size && length <= size - offset

    private fun ByteArray.u8(index: Int): Int? =
        if (index in indices) this[index].toInt() and 0xFF else null

    private fun ByteArray.u16(index: Int): Int? {
        if (!holds(index, 2)) return null
        return (u8(index)!! shl 8) or u8(index + 1)!!
    }

    private fun ByteArray.u32(index: Int): Int? {
        if (!holds(index, 4)) return null
        val value = (u8(index)!!.toLong() shl 24) or
            (u8(index + 1)!!.toLong() shl 16) or
            (u8(index + 2)!!.toLong() shl 8) or
            u8(index + 3)!!.toLong()
        // Offsets are unsigned 32-bit in the spec but no font Liseur will
        // ever open is 2 GB, and an Int keeps every later comparison free
        // of overflow.
        return if (value > Int.MAX_VALUE) null else value.toInt()
    }

    /** A 16.16 fixed-point number, rounded — `fvar` states weights this way. */
    private fun ByteArray.fixed(index: Int): Int? {
        if (!holds(index, 4)) return null
        val whole = ((u8(index)!! shl 8) or u8(index + 1)!!).toShort().toInt()
        val fraction = ((u16(index + 2)!!).toDouble() / 65536.0)
        return Math.round(whole + fraction).toInt()
    }

    private fun ByteArray.ascii(index: Int, length: Int): String? {
        if (!holds(index, length)) return null
        return String(copyOfRange(index, index + length), Charsets.US_ASCII)
    }

    private const val TAG_OTTO = 0x4F54544F      // 'OTTO'
    private const val TAG_TRUE = 0x74727565      // 'true'

    private const val TABLE_DIRECTORY = 12
    private const val TABLE_RECORD = 16
    private const val MAX_TABLES = 512

    private const val NAME_RECORD_START = 6
    private const val NAME_RECORD = 12
    private const val MAX_NAME_RECORDS = 4096
    private const val MAX_NAME_BYTES = 1024
    private const val NAME_FAMILY = 1
    private const val NAME_TYPOGRAPHIC_FAMILY = 16

    private const val PLATFORM_UNICODE = 0
    private const val PLATFORM_MAC = 1
    private const val PLATFORM_WINDOWS = 3
    private const val MAC_ROMAN = 0
    private const val WINDOWS_UCS4 = 10
    private val WINDOWS_UNICODE = setOf(1, WINDOWS_UCS4)
    private const val LANG_EN_US = 0x0409

    private const val HEAD_MAC_STYLE = 44

    private const val OS2_WEIGHT_CLASS = 4
    private const val OS2_SELECTION = 62
    private const val OS2_MIN_LENGTH = 64
    private const val OS2_ITALIC = 0x01
    private const val OS2_BOLD = 0x20

    private const val FVAR_AXIS = 20
    private const val MAX_AXES = 64

    private const val NORMAL_WEIGHT = 400
    private const val BOLD_WEIGHT = 700
    private val CSS_WEIGHTS = 1..1000
}
