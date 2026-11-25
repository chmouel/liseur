package com.chmouel.liseur.drive

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Deterministic canonical JSON serializer and SHA-256 fingerprint helpers
 * matching `docs/DRIVE_SYNC.md` §1.2–1.3.
 */
internal object DriveCanonicalJson {

    private const val HEX_CHARS = "0123456789abcdef"

    /** Serializes a Kotlin value (`Map`, `List`, `JSONObject`, `JSONArray`, primitives, `null`) canonically. */
    fun encode(value: Any?): String = buildString {
        writeValue(this, value)
    }

    /** Formats a finite [Double] canonically (`-0.0` -> `"0"`, `1.0` -> `"1"`, `0.50` -> `"0.5"`). */
    fun formatDouble(value: Double): String {
        require(value.isFinite()) { "Canonical JSON rejects non-finite double: $value" }
        if (value == 0.0) return "0"
        val stripped = BigDecimal.valueOf(value).stripTrailingZeros()
        return if (stripped.compareTo(BigDecimal.ZERO) == 0) "0" else stripped.toPlainString()
    }

    /** Recursively canonicalizes an embedded JSON string (or returns `""` for empty strings). */
    fun canonicalizeJsonString(raw: String): String {
        if (raw.isEmpty()) return ""
        val parsed = runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: return raw
        return when (parsed) {
            is JSONObject, is JSONArray -> encode(parsed)
            else -> raw
        }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val unsigned = byte.toInt() and 0xff
                append(HEX_CHARS[unsigned ushr 4])
                append(HEX_CHARS[unsigned and 0x0f])
            }
        }
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /** `sha256Hex(canonicalJson({"op":"put","body":<bodyObject>}))` */
    fun contentFp(bodyObject: Map<String, Any?>): String = sha256Hex(
        encode(
            mapOf(
                "op" to DriveRecordState.OP_PUT,
                "body" to bodyObject,
            ),
        ),
    )

    /** `sha256Hex(canonicalJson({"op":"put","body":<jsonObject>}))` */
    fun contentFp(bodyJson: JSONObject): String = sha256Hex(
        encode(
            mapOf(
                "op" to DriveRecordState.OP_PUT,
                "body" to bodyJson,
            ),
        ),
    )

    fun putFp(body: Any, hlc: String): String = sha256Hex(
        encode(mapOf("op" to DriveRecordState.OP_PUT, "body" to body, "hlc" to hlc)),
    )

    /** `sha256Hex(canonicalJson({"op":"del","d":<domain>,"k":<key>,"hlc":<hlc>}))` */
    fun delFp(domain: String, key: String, hlc: String): String = sha256Hex(
        encode(
            mapOf(
                "op" to DriveRecordState.OP_DEL,
                "d" to domain,
                "k" to key,
                "hlc" to hlc,
            ),
        ),
    )

    /** `sha256Hex(canonicalJson({"op":"resolve","outcome":<outcome>,"resolvedFps":<sorted>,"hlc":<hlc>,"body":<bodyOrNull>}))` */
    fun resolveFp(
        outcome: String,
        resolvedFps: Collection<String>,
        hlc: String,
        body: Any?,
    ): String = sha256Hex(
        encode(
            mapOf(
                "op" to DriveRecordState.OP_RESOLVE,
                "outcome" to outcome,
                "resolvedFps" to resolvedFps.distinct().sorted(),
                "hlc" to hlc,
                "body" to body,
            ),
        ),
    )

    /** `sha256Hex("$domain|$recordKey|${minOf(fpA, fpB)}|${maxOf(fpA, fpB)}")` */
    fun conflictId(domain: String, recordKey: String, fpA: String, fpB: String): String {
        val low = minOf(fpA, fpB)
        val high = maxOf(fpA, fpB)
        return sha256Hex("$domain|$recordKey|$low|$high")
    }

    /** Deterministic `batchId` per `(deviceId, seq)` for `appProperties.batchId` deduplication. */
    fun batchId(deviceId: String, seq: Long): String =
        UUID.nameUUIDFromBytes("batch|$deviceId|$seq".toByteArray(Charsets.UTF_8)).toString()

    private fun writeValue(out: StringBuilder, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is String -> writeString(out, value)
            is Int -> out.append(value)
            is Long -> out.append(value)
            is Short -> out.append(value.toInt())
            is Byte -> out.append(value.toInt())
            is Double -> out.append(formatDouble(value))
            is Float -> out.append(formatDouble(value.toDouble()))
            is BigDecimal -> {
                val stripped = value.stripTrailingZeros()
                out.append(if (stripped.compareTo(BigDecimal.ZERO) == 0) "0" else stripped.toPlainString())
            }
            is Number -> out.append(formatDouble(value.toDouble()))
            is Map<*, *> -> {
                out.append('{')
                val sortedKeys = value.keys.map { it.toString() }.sorted()
                var first = true
                for (key in sortedKeys) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key)
                    out.append(':')
                    writeValue(out, value[key])
                }
                out.append('}')
            }
            is JSONObject -> {
                out.append('{')
                val sortedKeys = buildList {
                    val iter = value.keys()
                    while (iter.hasNext()) add(iter.next())
                }.sorted()
                var first = true
                for (key in sortedKeys) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key)
                    out.append(':')
                    writeValue(out, if (value.isNull(key)) null else value.get(key))
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    writeValue(out, item)
                }
                out.append(']')
            }
            is JSONArray -> {
                out.append('[')
                for (i in 0 until value.length()) {
                    if (i > 0) out.append(',')
                    writeValue(out, if (value.isNull(i)) null else value.get(i))
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported canonical JSON value: ${value.javaClass.name}")
        }
    }

    private fun writeString(out: StringBuilder, text: String) {
        out.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    val code = ch.code
                    if (code < 0x20) {
                        out.append("\\u00")
                        out.append(HEX_CHARS[(code ushr 4) and 0x0f])
                        out.append(HEX_CHARS[code and 0x0f])
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        out.append('"')
    }
}
