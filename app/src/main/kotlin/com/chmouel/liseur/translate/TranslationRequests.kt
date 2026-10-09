package com.chmouel.liseur.translate

import kotlinx.coroutines.CancellationException

/**
 * A translation asked with an address or key that changed before its
 * reply came is asked again with the new one, so the reply shown is never
 * the old connection's, even for the very same passage.
 */
internal class TranslationRequests(private val generation: (owner: String) -> Int) {
    /**
     * [block]'s reply or failure, asked again while the connection it used
     * changed during it. Each attempt asks [owner] whose connection that
     * is, before and after, since an attempt may land on another server
     * than the one before it.
     */
    suspend fun run(owner: suspend () -> String?, block: suspend () -> String): String =
        run(owner, { it }) { block() }.second

    /**
     * [block]'s reply or failure with what it was asked under, asked again
     * while that changed during it: [bind] gives it before and after each
     * attempt, and [owner] names the connection it uses.
     */
    suspend fun <B> run(bind: suspend () -> B, owner: (B) -> String?, block: suspend (B) -> String): Pair<B, String> {
        repeat(ATTEMPTS) {
            val bound = bind()
            val asked = owner(bound)
            val before = asked?.let(generation)
            // A failure is the old connection's as much as a reply is, so it is judged the same way.
            val outcome = try {
                Result.success(block(bound))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (bind() == bound && (asked == null || generation(asked) == before)) return bound to outcome.getOrThrow()
        }
        throw TranslationError.Changed()
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}
