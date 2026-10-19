package com.chmouel.liseur.translate

import org.json.JSONObject

internal data class GeminiUsage(val input: Long?, val cached: Long?, val output: Long?, val thoughts: Long?) {
    companion object {
        fun parse(root: JSONObject): GeminiUsage {
            val usage = root.optJSONObject("usageMetadata")
            fun tokens(name: String): Long? = when (val value = usage?.opt(name)) {
                is Int -> value.toLong().takeIf { it >= 0 }
                is Long -> value.takeIf { it in 0..Int.MAX_VALUE.toLong() }
                else -> null
            }
            return GeminiUsage(
                tokens("promptTokenCount"), tokens("cachedContentTokenCount"),
                tokens("candidatesTokenCount"), tokens("thoughtsTokenCount"),
            )
        }
    }
}

/** Constant-space, per-run measurements; no prompts or replies leave this object. */
internal class GeminiCacheDiagnostics {
    data class Tokens(val reports: Long = 0, val total: Long = 0) {
        fun add(value: Long?) = if (value == null) this else Tokens(reports + 1, total + value)
    }
    data class Prefix(val comparisons: Long = 0, val sharedBytes: Long = 0, val minimumBytes: Int? = null) {
        fun add(bytes: Int) = Prefix(comparisons + 1, sharedBytes + bytes, minOf(minimumBytes ?: bytes, bytes))
    }
    data class Snapshot(
        val attempts: Long = 0,
        val responses: Long = 0,
        val completed: Long = 0,
        val localSentenceHits: Long = 0,
        val elapsedMillis: Long = 0,
        val input: Tokens = Tokens(),
        val cached: Tokens = Tokens(),
        val output: Tokens = Tokens(),
        val thoughts: Tokens = Tokens(),
        val sameBookContext: Prefix = Prefix(),
        val changedBookContext: Prefix = Prefix(),
    )
    private var snapshot = Snapshot()
    private var previous: ByteArray? = null
    private var previousBook: String? = null

    @Synchronized
    fun started(body: JSONObject, book: String?) {
        val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        val user = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        // These are logical prompt bytes, not JSON bytes, provider tokenization or evidence of a cache hit.
        val prompt = (system + "\n" + user).toByteArray(Charsets.UTF_8)
        val bytes = prompt.copyOf(minOf(prompt.size, MAX_PREFIX_BYTES))
        val bookKey = translationKey(book.orEmpty())
        val last = previous
        snapshot = snapshot.copy(attempts = snapshot.attempts + 1)
        if (last != null) {
            var shared = 0
            while (shared < minOf(last.size, bytes.size) && last[shared] == bytes[shared]) shared++
            snapshot = if (bookKey == previousBook) {
                snapshot.copy(sameBookContext = snapshot.sameBookContext.add(shared))
            } else {
                snapshot.copy(changedBookContext = snapshot.changedBookContext.add(shared))
            }
        }
        previous = bytes
        previousBook = bookKey
    }

    @Synchronized
    fun response(usage: GeminiUsage) {
        snapshot = snapshot.copy(
            responses = snapshot.responses + 1,
            input = snapshot.input.add(usage.input),
            cached = snapshot.cached.add(usage.cached),
            output = snapshot.output.add(usage.output),
            thoughts = snapshot.thoughts.add(usage.thoughts),
        )
    }

    @Synchronized
    fun finished(elapsedMillis: Long, completed: Boolean) {
        snapshot = snapshot.copy(
            elapsedMillis = snapshot.elapsedMillis + elapsedMillis.coerceAtLeast(0),
            completed = snapshot.completed + if (completed) 1 else 0,
        )
    }

    @Synchronized
    fun snapshot(): Snapshot = snapshot

    @Synchronized
    fun cached(count: Int) {
        snapshot = snapshot.copy(localSentenceHits = snapshot.localSentenceHits + count)
    }

    @Synchronized
    fun close() {
        previous = null
        previousBook = null
    }

    private companion object {
        const val MAX_PREFIX_BYTES = 32_768
    }
}
