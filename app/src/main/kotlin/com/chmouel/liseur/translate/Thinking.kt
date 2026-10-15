package com.chmouel.liseur.translate

import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl
import org.json.JSONObject

/**
 * Asking a thinking model not to think first. A passage to translate or a
 * question about a book gains little from it, and it can take a sentence
 * from a second to a minute and cost most of the reply's tokens.
 *
 * There is no common field for it, and a strict server refuses one it
 * does not know, so it is asked only of the services known to take one.
 * Each has a ladder of ways, the most thorough first: a model that refuses
 * one is asked the next, then with none, the lowest effort standing in
 * where a model cannot stop thinking at all. What a model took is kept
 * for as long as the app runs, so only its first request climbs.
 */
internal object Thinking {
    /** A refusal of the way asked, rather than of the request: what the next rung is asked after. */
    fun refused(code: Int, message: String?): Boolean =
        code in setOf(400, 422) &&
            (message?.contains("thinking", ignoreCase = true) == true || message?.contains("reasoning", ignoreCase = true) == true)

    /** The fields that ask an OpenAI-compatible server at [base] not to think; empty where none is known. */
    fun openAiCompatible(base: HttpUrl): List<JSONObject> = when (base.host) {
        // GLM-5.3 and later always think, and take an effort instead.
        "api.z.ai", "open.bigmodel.cn" -> listOf(
            JSONObject().put("thinking", JSONObject().put("type", "disabled")),
            effort("low"),
        )
        // Some models can stop; others require at least minimal or low effort.
        "api.openai.com" -> listOf(effort("none"), effort("minimal"), effort("low"))
        "generativelanguage.googleapis.com" -> listOf(effort("minimal"), effort("none"), effort("low"))
        // gpt-oss goes no lower than low.
        "api.groq.com" -> listOf(effort("none"), effort("low"))
        "api.deepinfra.com" -> listOf(effort("none"))
        "openrouter.ai" -> listOf(JSONObject().put("reasoning", JSONObject().put("effort", "none")))
        else -> emptyList()
    }

    /** The Gemini API's thinking configurations, in the order [openAiCompatible] asks its endpoint. */
    val GEMINI: List<JSONObject> = listOf(
        JSONObject().put("thinkingLevel", "minimal"),
        JSONObject().put("thinkingBudget", 0),
        JSONObject().put("thinkingLevel", "low"),
    )

    private fun effort(level: String) = JSONObject().put("reasoning_effort", level)

    private val taken = ConcurrentHashMap<String, Int>()

    /**
     * What [ask] answers with the first of [ways] the model under [key]
     * does not refuse, or with none once all are refused. [ask] is given
     * the way, or null for none; [refused] tells a refusal. Only a
     * successful [accepted] reply remembers the rung for [key].
     */
    suspend fun <R> climb(key: String, ways: List<JSONObject>, refused: (R) -> Boolean, accepted: (R) -> Boolean, ask: suspend (JSONObject?) -> R): R {
        if (ways.isEmpty()) return ask(null)
        var rung = taken[key]?.coerceAtMost(ways.size) ?: 0
        while (true) {
            val reply = ask(ways.getOrNull(rung))
            if (!refused(reply)) {
                if (accepted(reply)) taken[key] = rung
                return reply
            }
            if (rung == ways.size) return reply
            rung++
        }
    }

    /** Forgets what every model took, for tests. */
    fun forget() = taken.clear()
}
