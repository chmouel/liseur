package com.chmouel.liseur.translate

import kotlinx.coroutines.CancellationException

/**
 * A translation asked with an address or key that changed before its
 * reply came is asked again with the new one, so the reply shown is never
 * the old connection's, even for the very same passage.
 */
internal class TranslationRequests(private val generation: (owner: String) -> Int) {
    /** [block]'s reply or failure, asked again while [owner]'s generation moved on during it. */
    suspend fun run(owner: String?, block: suspend () -> String): String {
        repeat(ATTEMPTS) {
            val before = owner?.let(generation)
            // A failure is the old connection's as much as a reply is, so it is judged the same way.
            val outcome = try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (owner == null || generation(owner) == before) return outcome.getOrThrow()
        }
        throw TranslationError.Changed()
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}
