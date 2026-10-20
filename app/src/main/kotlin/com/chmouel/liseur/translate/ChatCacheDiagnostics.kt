package com.chmouel.liseur.translate

/** Token counts one chat completion reports; null where it says nothing. */
data class ChatUsage(val input: Long?, val cached: Long?, val output: Long?)

/**
 * What a whole-book run on an OpenAI-compatible server was billed for and
 * how much of it the provider served from its prompt cache, for a debug
 * log line. Constant-space and per run; no prompt or reply is kept.
 */
internal class ChatCacheDiagnostics {
    data class Tokens(val reports: Long = 0, val total: Long = 0) {
        fun add(value: Long?) = if (value == null) this else Tokens(reports + 1, total + value)
    }

    data class Snapshot(
        val responses: Long = 0,
        val localSentenceHits: Long = 0,
        val input: Tokens = Tokens(),
        val cached: Tokens = Tokens(),
        val output: Tokens = Tokens(),
    )

    private var snapshot = Snapshot()

    @Synchronized
    fun response(usage: ChatUsage) {
        snapshot = snapshot.copy(
            responses = snapshot.responses + 1,
            input = snapshot.input.add(usage.input),
            cached = snapshot.cached.add(usage.cached),
            output = snapshot.output.add(usage.output),
        )
    }

    @Synchronized
    fun cached(count: Int) {
        snapshot = snapshot.copy(localSentenceHits = snapshot.localSentenceHits + count)
    }

    @Synchronized
    fun snapshot(): Snapshot = snapshot
}
