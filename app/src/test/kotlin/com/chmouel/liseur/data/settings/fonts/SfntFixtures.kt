package com.chmouel.liseur.data.settings.fonts

import java.io.ByteArrayOutputStream

/**
 * Font files built out of bytes, for the tests that need one.
 *
 * Nothing binary is checked in: a reader of these tests can see exactly
 * which byte makes an assertion true, and a fixture cannot quietly stop
 * being the thing it claims to be. Shared between the parser's own tests
 * and the repository's, which needs files a real parse will accept.
 */
object SfntFixtures {

    const val TRUE_TYPE = 0x00010000
    const val OTTO = 0x4F54544F
    const val TRUE_TAG = 0x74727565
    const val NAME_FAMILY = 1
    const val NAME_TYPOGRAPHIC_FAMILY = 16
    const val OS2_ITALIC = 0x01
    const val OS2_BOLD = 0x20

    fun sfnt(
        magic: Int = TRUE_TYPE,
        names: List<NameRecord> = listOf(name(NAME_FAMILY, "Fixture")),
        weightClass: Int = 400,
        fsSelection: Int = 0,
        macStyle: Int = 0,
        withOs2: Boolean = true,
        weightAxis: Pair<Int, Int>? = null,
        axisTag: String = "wght",
    ): ByteArray {
        val tables = LinkedHashMap<String, ByteArray>()
        tables["head"] = ByteArray(54).also { it.putU16(44, macStyle) }
        if (withOs2) {
            tables["OS/2"] = ByteArray(96).also {
                it.putU16(4, weightClass)
                it.putU16(62, fsSelection)
            }
        }
        if (names.isNotEmpty()) tables["name"] = nameTable(names)
        weightAxis?.let { (min, max) -> tables["fvar"] = fvarTable(axisTag, min, max) }

        val directory = 12 + tables.size * 16
        val body = ByteArrayOutputStream()
        val offsets = LinkedHashMap<String, Pair<Int, Int>>()
        for ((tag, bytes) in tables) {
            offsets[tag] = (directory + body.size()) to bytes.size
            body.write(bytes)
            // Tables are four-byte aligned in a real font; pad so the
            // fixture is one too.
            while (body.size() % 4 != 0) body.write(0)
        }

        val out = ByteArray(directory + body.size())
        out.putU32(0, magic)
        out.putU16(4, tables.size)
        tables.keys.forEachIndexed { i, tag ->
            val record = 12 + i * 16
            tag.padEnd(4).toByteArray(Charsets.US_ASCII).copyInto(out, record)
            val (offset, length) = offsets.getValue(tag)
            out.putU32(record + 8, offset)
            out.putU32(record + 12, length)
        }
        body.toByteArray().copyInto(out, directory)
        return out
    }

    class NameRecord(
        val nameId: Int,
        val platform: Int,
        val encoding: Int,
        val language: Int,
        val bytes: ByteArray,
    )

    fun name(
        nameId: Int,
        value: String,
        platform: Int = 3,
        encoding: Int = 1,
        language: Int = 0x409,
        mac: Boolean = false,
        ucs4: Boolean = false,
    ) = NameRecord(
        nameId = nameId,
        platform = platform,
        encoding = encoding,
        language = language,
        bytes = when {
            mac -> value.toByteArray(Charsets.ISO_8859_1)
            ucs4 -> value.toByteArray(Charsets.UTF_32BE)
            else -> value.toByteArray(Charsets.UTF_16BE)
        },
    )

    fun nameTable(records: List<NameRecord>): ByteArray {
        val storage = 6 + records.size * 12
        val strings = ByteArrayOutputStream()
        val out = ByteArray(storage + records.sumOf { it.bytes.size })
        out.putU16(0, 0)
        out.putU16(2, records.size)
        out.putU16(4, storage)
        records.forEachIndexed { i, record ->
            val at = 6 + i * 12
            out.putU16(at, record.platform)
            out.putU16(at + 2, record.encoding)
            out.putU16(at + 4, record.language)
            out.putU16(at + 6, record.nameId)
            out.putU16(at + 8, record.bytes.size)
            out.putU16(at + 10, strings.size())
            strings.write(record.bytes)
        }
        strings.toByteArray().copyInto(out, storage)
        return out
    }

    fun fvarTable(tag: String, min: Int, max: Int): ByteArray {
        val axesOffset = 16
        val out = ByteArray(axesOffset + 20)
        out.putU32(0, 0x00010000)
        out.putU16(4, axesOffset)
        out.putU16(8, 1)
        out.putU16(10, 20)
        tag.toByteArray(Charsets.US_ASCII).copyInto(out, axesOffset)
        out.putFixed(axesOffset + 4, min)
        out.putFixed(axesOffset + 8, min)
        out.putFixed(axesOffset + 12, max)
        return out
    }

    fun ByteArray.tableOffset(tag: String): Int {
        val count = ((this[4].toInt() and 0xFF) shl 8) or (this[5].toInt() and 0xFF)
        for (i in 0 until count) {
            val record = 12 + i * 16
            val found = String(copyOfRange(record, record + 4), Charsets.US_ASCII)
            if (found == tag.padEnd(4)) {
                return (0..3).fold(0) { acc, b ->
                    (acc shl 8) or (this[record + 8 + b].toInt() and 0xFF)
                }
            }
        }
        throw AssertionError("no $tag table in the fixture")
    }

    fun ByteArray.putU16(at: Int, value: Int) {
        this[at] = (value ushr 8).toByte()
        this[at + 1] = value.toByte()
    }

    fun ByteArray.putU32(at: Int, value: Int) {
        this[at] = (value ushr 24).toByte()
        this[at + 1] = (value ushr 16).toByte()
        this[at + 2] = (value ushr 8).toByte()
        this[at + 3] = value.toByte()
    }

    fun ByteArray.putFixed(at: Int, whole: Int) {
        putU16(at, whole and 0xFFFF)
        putU16(at + 2, 0)
    }
}
