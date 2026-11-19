package com.chmouel.liseur.data.remote

import java.io.EOFException
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

internal const val BOOK_HEADER_BYTES = 4096

internal fun isHtmlBookResponse(header: ByteArray): Boolean {
    val charset = when {
        header.size < 2 -> Charsets.UTF_8
        header[0] == 0xff.toByte() && header[1] == 0xfe.toByte() -> Charsets.UTF_16LE
        header[0] == 0xfe.toByte() && header[1] == 0xff.toByte() -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    val text = header.toString(charset).removePrefix("\uFEFF")
    var offset = 0
    while (offset < text.length) {
        if (text[offset].isWhitespace()) {
            offset++
            continue
        }
        val close = when {
            text.startsWith("<!--", offset) -> "-->"
            text.startsWith("<?xml", offset, ignoreCase = true) -> "?>"
            else -> return HTML_START.matchAt(text, offset) != null
        }
        val end = text.indexOf(close, offset + 4)
        if (end < 0) return false
        offset = end + close.length
    }
    return false
}

private val HTML_START = Regex("""(?:<!doctype\s+html\b|<(?:html|head|body|title|meta)\b)""", RegexOption.IGNORE_CASE)

internal fun isEpub(file: File): Boolean = try {
    ZipFile(file).use { zip ->
        val type = zip.getEntry("mimetype") ?: return@use false
        val container = zip.getEntry("META-INF/container.xml") ?: return@use false
        !container.isDirectory && container.size > 0 &&
            type.size == EPUB_TYPE.length.toLong() &&
            zip.getInputStream(type).use { stream ->
                EPUB_TYPE.all { stream.read() == it.code } && stream.read() == -1
            }
    }
} catch (_: ZipException) {
    false
} catch (_: EOFException) {
    false
}

private const val EPUB_TYPE = "application/epub+zip"
