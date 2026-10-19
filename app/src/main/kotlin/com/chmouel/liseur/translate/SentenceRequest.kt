package com.chmouel.liseur.translate

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal data class SentenceRequest(
    val sentence: PageSentence,
    val identity: String,
    val context: String?,
    val facts: BookFacts?,
    val book: String?,
) {
    val key: String = (listOf(identity, context.orEmpty(), sentence.text) +
        listOfNotNull(book?.let { "book=${TranslationBookContext.identity(it)}" }))
        .joinToString("\u0000")
}

internal class SentenceRequests(
    private val translator: SentenceTranslator,
    private val preceding: PrecedingText?,
    private val book: (suspend (String) -> BookFacts?)?,
) {
    suspend fun context(sentence: PageSentence, fallback: String?, identity: String): String? {
        val size = translator.contextSize(identity) ?: return fallback
        if (size.sentences <= 0) return null
        val earlier = preceding?.before(sentence, size.sentences) ?: return fallback
        return TranslationContext.window(earlier, size)
    }

    suspend fun prepare(sentence: PageSentence, fallback: String?, identity: String): SentenceRequest {
        val facts = try {
            book?.invoke(sentence.href)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return SentenceRequest(sentence, identity, context(sentence, fallback, identity), facts, translator.book(identity, facts))
    }
}

internal fun checkTranslationLength(source: String, translation: String) {
    if (translation.length > maxOf(2_000L, source.length.toLong() * 8)) {
        throw TranslationError.Malformed("the translation is far longer than the sentence")
    }
}

internal fun translationKey(raw: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}
