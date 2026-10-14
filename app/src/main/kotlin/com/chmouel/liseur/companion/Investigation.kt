package com.chmouel.liseur.companion

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class ChatMessage(val role: String, val content: String)

/** A chat completion: one reply to [messages], with [beforeRequest] run before every HTTP request it makes. */
fun interface CompanionChat {
    suspend fun reply(messages: List<ChatMessage>, beforeRequest: () -> Unit): String
}

/**
 * What a recap knows of a character, used only to find passages: the
 * [names] it goes by, each already found in the text it cites, and the
 * [refs] of those passages. Its prose is never sent.
 */
data class RecapHint(val unit: Int, val names: List<String>, val refs: List<String>)

data class Claim<P>(val text: String, val sources: List<Passage<P>>)

data class Relationship<P>(val name: String, val text: String, val sources: List<Passage<P>>)

data class Alias<P>(val name: String, val sources: List<Passage<P>>)

data class Candidate<P>(val name: String, val description: String, val sources: List<Passage<P>>)

sealed interface CompanionAnswer<out P> {
    data class Profile<P>(
        val name: String,
        val aliases: List<Alias<P>>,
        val identity: List<Claim<P>>,
        val backstory: List<Claim<P>>,
        val relationships: List<Relationship<P>>,
    ) : CompanionAnswer<P>

    data class Ambiguous<P>(val candidates: List<Candidate<P>>) : CompanionAnswer<P>

    /** The earlier chapters do not say enough about the name. */
    data object Insufficient : CompanionAnswer<Nothing>
}

sealed class CompanionError(message: String) : Exception(message) {
    /** The model's reply could not be used, even after being told why. */
    class Malformed : CompanionError("The model sent no usable answer")

    /** The lookup made as many requests as one may. */
    class TooManyRequests : CompanionError("The lookup made too many requests")
}

data class InvestigationLimits(
    val rounds: Int = 6,
    val operations: Int = 12,
    val sourceCharacters: Int = 64_000,
    val requests: Int = 10,
    val seedCharacters: Int = 20_000,
)

/**
 * Asks the model about one name, letting it search and read the passages
 * [scope] allows, and nothing else.
 *
 * Every passage is checked against [Eligibility] again here, whatever
 * the caller passed; the model sees each one under a ref issued for this
 * lookup only, and every statement it returns must cite refs it was
 * shown. Supporting quotes are the passages' own text, never the model's.
 */
class Investigation<P>(
    private val chat: CompanionChat,
    private val scope: AskingScope,
    passages: List<Passage<P>>,
    private val limits: InvestigationLimits = InvestigationLimits(),
) {
    private val eligible = passages.filter { Eligibility.allows(scope, it.range) }
    private val positions = eligible.withIndex().associate { it.value.ref to it.index }

    /**
     * The profile of [name], or why there is none. [also] is the name
     * first selected when [name] is a candidate it turned out to be
     * ambiguous between. [hints] come from recaps.
     */
    suspend fun run(name: String, also: String? = null, hints: List<RecapHint> = emptyList()): CompanionAnswer<P> =
        Session(name, also).run(hints)

    private fun mentioning(phrase: String): List<Int> {
        val needle = PassageText.fold(phrase).takeIf { it.isNotEmpty() } ?: return emptyList()
        val exact = eligible.indices.filter { eligible[it].folded.contains(" $needle ") }
        if (exact.isNotEmpty()) return exact
        val core = needle.split(' ')
            .filter { it.length >= MIN_NAME_PART && it !in HONORIFICS }
            .distinct()
        if (core.isEmpty()) return emptyList()
        val together = eligible.indices.filter { i -> core.all { eligible[i].folded.contains(" $it ") } }
        if (together.isNotEmpty()) return together
        return eligible.indices.filter { i -> core.any { eligible[i].folded.contains(" $it ") } }
    }

    private fun hintedFor(asked: List<String>, hints: List<RecapHint>): List<Int> =
        hints.asSequence()
            .filter { it.unit < scope.lo }
            .filter { hint -> hint.names.any { n -> asked.any { PassageText.mentions(n, it) || PassageText.mentions(it, n) } } }
            .flatMap { it.refs.asSequence() }
            .mapNotNull { positions[it] }
            .distinct()
            .take(SEED_HINTS)
            .toList()

    private inner class Session(private val name: String, private val also: String?) {
        private val history = mutableListOf<ChatMessage>()
        private val issued = LinkedHashMap<Int, String>()
        private val byIssued = HashMap<String, Int>()
        private val shown = HashSet<Int>()
        private val snipped = HashSet<Int>()
        private var source = 0
        private var rounds = 0
        private var operations = 0
        private var requests = 0
        private var corrected = false
        private var finishing = false

        suspend fun run(hints: List<RecapHint>): CompanionAnswer<P> {
            val asked = listOfNotNull(name, also)
            val mentions = asked.flatMap(::mentioning).distinct().sorted()
            val hinted = hintedFor(asked, hints)
            if (mentions.isEmpty() && hinted.isEmpty()) return CompanionAnswer.Insufficient
            history += ChatMessage("system", CompanionPrompt.system(limits))
            history += ChatMessage("user", seed(mentions, hinted))
            while (true) {
                val reply = request()
                when (val parsed = parse(reply)) {
                    is Parsed.Invalid -> {
                        if (corrected) throw CompanionError.Malformed()
                        corrected = true
                        val last = history.removeAt(history.lastIndex)
                        history += last.copy(content = last.content + "\n\n" + CompanionPrompt.correction(parsed.code, finishing))
                    }
                    is Parsed.Actions -> {
                        rounds++
                        history += ChatMessage("assistant", parsed.canonical)
                        val results = execute(parsed.actions)
                        finishing = rounds >= limits.rounds || operations >= limits.operations || source >= limits.sourceCharacters
                        history += ChatMessage("user", results + if (finishing) "\n\n" + CompanionPrompt.FINISH else "")
                    }
                    is Parsed.Answer -> return typed(parsed.answer)
                }
            }
        }

        // Built from this lookup's own passages, so of its type.
        @Suppress("UNCHECKED_CAST")
        private fun typed(answer: CompanionAnswer<*>) = answer as CompanionAnswer<P>

        private suspend fun request(): String = chat.reply(history.toList()) {
            if (++requests > limits.requests) throw CompanionError.TooManyRequests()
        }

        private fun seed(mentions: List<Int>, hinted: List<Int>): String {
            val anchors = (acrossChapters(mentions).take(SEED_MENTIONS) + hinted).distinct()
            // Each anchor comes with the passages around it when they fit, alone otherwise.
            var budget = limits.seedCharacters
            val chosen = sortedSetOf<Int>()
            for (anchor in anchors) {
                val group = around(anchor, SEED_CONTEXT, SEED_WINDOW).filterNot { it in chosen }
                for (candidate in listOf(group, listOf(anchor).filterNot { it in chosen })) {
                    val length = candidate.sumOf { eligible[it].text.length }
                    if (length <= budget) {
                        budget -= length
                        chosen += candidate
                        break
                    }
                }
            }
            val blocks = chosen.joinToString("\n") { show(it) }
            return CompanionPrompt.seed(name, also, scope, limits) + "\n\n" + blocks
        }

        // The first mention in each chapter (from latest to earliest so recent chapters lead), then the second, and so on.
        private fun acrossChapters(mentions: List<Int>): List<Int> {
            val byChapter = mentions.groupBy { eligible[it].range.lo }.toSortedMap(compareByDescending { it }).values.toList()
            return (0 until (byChapter.maxOfOrNull { it.size } ?: 0)).flatMap { k -> byChapter.mapNotNull { it.getOrNull(k) } }
        }

        // Eligible passages around [at] in the same resource: at least [minReach] each way, then further while under [window] characters and [MAX_REACH].
        private fun around(at: Int, minReach: Int, window: Int): List<Int> {
            val resource = eligible[at].resource
            var lo = at
            var hi = at
            var size = eligible[at].text.length
            for (step in 1..MAX_REACH) {
                val prev = (at - step).takeIf { it in eligible.indices && eligible[it].resource == resource }
                val next = (at + step).takeIf { it in eligible.indices && eligible[it].resource == resource }
                if (prev == null && next == null) break
                if (prev != null && (step <= minReach || size + eligible[prev].text.length <= window)) {
                    lo = prev
                    size += eligible[prev].text.length
                }
                if (next != null && (step <= minReach || size + eligible[next].text.length <= window)) {
                    hi = next
                    size += eligible[next].text.length
                }
                if (step >= minReach && size >= window) break
            }
            return (lo..hi).toList()
        }

        private fun refOf(i: Int): String = issued.getOrPut(i) { "S${issued.size + 1}".also { byIssued[it] = i } }

        // A whole passage, counted once against the budget.
        private fun show(i: Int): String {
            shown += i
            source += eligible[i].text.length
            return CompanionPrompt.block(refOf(i), eligible[i].range, eligible[i].text)
        }

        private fun execute(actions: List<Action>): String {
            val out = StringBuilder()
            actions.forEachIndexed { n, action ->
                out.append("Action ${n + 1}: ")
                if (operations >= limits.operations) {
                    out.append("not run, the action limit is reached.\n")
                    return@forEachIndexed
                }
                operations++
                when (action) {
                    is Action.Search -> search(action.query, out)
                    is Action.Read -> read(action.ref, out)
                }
            }
            return out.toString().trimEnd()
        }

        private fun search(query: String, out: StringBuilder) {
            val words = PassageText.fold(query).split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty() || words.size > PassageText.MAX_NAME_WORDS) {
                out.append("search refused, use one to ${PassageText.MAX_NAME_WORDS} words.\n")
                return
            }
            val all = acrossChapters(eligible.indices.filter { i -> words.all { eligible[i].folded.contains(" $it ") } })
            if (all.isEmpty()) {
                out.append("search found nothing.\n")
                return
            }
            val unseen = all.filterNot { it in shown || it in snipped }
            val hits = (unseen + all.filter { it in shown || it in snipped }).take(SEARCH_HITS).sorted()
            out.append("search found ${all.size} passage(s).\n")
            for (i in hits) {
                if (i in shown || i in snipped) {
                    out.append("${refOf(i)} already shown.\n")
                    continue
                }
                val snippet = snippet(eligible[i].text, words.first())
                if (source + snippet.length > limits.sourceCharacters) {
                    out.append("Not shown, the source text limit is reached.\n")
                    source = limits.sourceCharacters
                    return
                }
                snipped += i
                source += snippet.length
                out.append(CompanionPrompt.block(refOf(i), eligible[i].range, snippet)).append('\n')
            }
        }

        private fun read(ref: String, out: StringBuilder) {
            val at = byIssued[ref]
            if (at == null) {
                out.append("read refused, $ref is not a ref you were shown.\n")
                return
            }
            val fresh = around(at, READ_CONTEXT, READ_WINDOW).filterNot { it in shown }
            if (fresh.isEmpty()) {
                out.append("${refOf(at)} and its neighbours are already shown.\n")
                return
            }
            out.append("read ${refOf(at)} with its neighbours.\n")
            for (i in fresh) {
                if (source + eligible[i].text.length > limits.sourceCharacters) {
                    out.append("Not shown, the source text limit is reached.\n")
                    source = limits.sourceCharacters
                    return
                }
                out.append(show(i)).append('\n')
            }
        }

        private fun parse(reply: String): Parsed {
            val json = try {
                val start = reply.indexOf('{')
                val end = reply.lastIndexOf('}')
                if (start < 0 || end < start) return Parsed.Invalid("not_json")
                JSONObject(reply.substring(start, end + 1))
            } catch (_: JSONException) {
                return Parsed.Invalid("not_json")
            }
            if (json.has("actions")) {
                if (finishing) return Parsed.Invalid("actions_after_limit")
                return actions(json.optJSONArray("actions"))
            }
            return when (json.optString("answer")) {
                "insufficient" -> Parsed.Answer(CompanionAnswer.Insufficient)
                "profile" -> profile(json)
                "ambiguous" -> ambiguous(json)
                else -> Parsed.Invalid("unknown_reply")
            }
        }

        private fun actions(array: JSONArray?): Parsed {
            if (array == null || array.length() == 0) return Parsed.Invalid("bad_action")
            val actions = mutableListOf<Action>()
            for (k in 0 until minOf(array.length(), MAX_ACTIONS_PER_ROUND)) {
                val o = array.optJSONObject(k) ?: return Parsed.Invalid("bad_action")
                actions += when (o.optString("op")) {
                    "search" -> o.optString("query").trim().take(PassageText.MAX_NAME_LENGTH)
                        .takeIf { it.isNotEmpty() }?.let(Action::Search)
                    "read" -> o.optString("ref").trim().takeIf { REF.matches(it) }?.let(Action::Read)
                    else -> null
                } ?: return Parsed.Invalid("bad_action")
            }
            // The model's reply goes back as the actions read, not as it wrote it.
            val canonical = JSONObject().put(
                "actions",
                JSONArray().apply {
                    actions.forEach {
                        put(
                            when (it) {
                                is Action.Search -> JSONObject().put("op", "search").put("query", it.query)
                                is Action.Read -> JSONObject().put("op", "read").put("ref", it.ref)
                            },
                        )
                    }
                },
            ).toString()
            return Parsed.Actions(actions, canonical)
        }

        // The passages [refs] cite, or null when any was not shown in this lookup.
        private fun sources(refs: JSONArray?): List<Passage<P>>? {
            if (refs == null || refs.length() == 0) return null
            val found = mutableListOf<Passage<P>>()
            for (k in 0 until refs.length()) {
                val i = byIssued[refs.optString(k).trim()] ?: return null
                if (i !in shown && i !in snipped) return null
                val passage = eligible[i]
                if (!Eligibility.allows(scope, passage.range)) return null
                if (passage !in found) found += passage
            }
            return found
        }

        private fun claims(array: JSONArray?, max: Int): Pair<List<Claim<P>>, Boolean> {
            if (array == null) return emptyList<Claim<P>>() to false
            var rejected = false
            val out = mutableListOf<Claim<P>>()
            for (k in 0 until minOf(array.length(), max)) {
                val o = array.optJSONObject(k)
                val text = o?.optString("text")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_CLAIM }
                val cited = o?.let { sources(it.optJSONArray("refs")) }
                if (text == null || cited == null) {
                    rejected = true
                } else {
                    out += Claim(text, cited)
                }
            }
            return out to rejected
        }

        private fun profile(json: JSONObject): Parsed {
            val (identity, badIdentity) = claims(json.optJSONArray("identity"), MAX_CLAIMS)
            val (backstory, badBackstory) = claims(json.optJSONArray("backstory"), MAX_CLAIMS)
            if (identity.isEmpty() && backstory.isEmpty()) {
                return Parsed.Invalid(if (badIdentity || badBackstory) "bad_citation" else "empty_profile")
            }
            val relationships = mutableListOf<Relationship<P>>()
            json.optJSONArray("relationships")?.let { array ->
                for (k in 0 until minOf(array.length(), MAX_CLAIMS)) {
                    val o = array.optJSONObject(k) ?: continue
                    val text = o.optString("text").trim().takeIf { it.isNotEmpty() && it.length <= MAX_CLAIM }
                        ?: continue
                    val cited = sources(o.optJSONArray("refs")) ?: continue
                    // A person the passages never name is not shown, whatever the model knows of them.
                    val other = PassageText.name(o.optString("name")) ?: continue
                    if (cited.none { PassageText.mentions(it, other) }) continue
                    relationships += Relationship(other, text, cited)
                }
            }
            val aliases = mutableListOf<Alias<P>>()
            json.optJSONArray("aliases")?.let { array ->
                for (k in 0 until minOf(array.length(), MAX_ALIASES)) {
                    val o = array.optJSONObject(k) ?: continue
                    val cited = sources(o.optJSONArray("refs")) ?: continue
                    val alias = PassageText.name(o.optString("name")) ?: continue
                    if (PassageText.fold(alias) == PassageText.fold(name)) continue
                    if (cited.none { PassageText.mentions(it, alias) }) continue
                    if (aliases.none { PassageText.fold(it.name) == PassageText.fold(alias) }) aliases += Alias(alias, cited)
                }
            }
            return Parsed.Answer(CompanionAnswer.Profile(name, aliases, identity, backstory, relationships))
        }

        private fun ambiguous(json: JSONObject): Parsed {
            val array = json.optJSONArray("candidates") ?: return Parsed.Invalid("bad_candidates")
            val candidates = mutableListOf<Candidate<P>>()
            for (k in 0 until minOf(array.length(), MAX_CANDIDATES)) {
                val o = array.optJSONObject(k) ?: return Parsed.Invalid("bad_candidates")
                val cited = sources(o.optJSONArray("refs")) ?: return Parsed.Invalid("bad_citation")
                val description = o.optString("description").trim().takeIf { it.isNotEmpty() && it.length <= MAX_CLAIM }
                    ?: return Parsed.Invalid("bad_candidates")
                val candidate = PassageText.name(o.optString("name")) ?: continue
                if (cited.none { PassageText.mentions(it, candidate) }) continue
                if (candidates.none { PassageText.fold(it.name) == PassageText.fold(candidate) }) {
                    candidates += Candidate(candidate, description, cited)
                }
            }
            if (candidates.size < 2) return Parsed.Invalid("bad_candidates")
            return Parsed.Answer(CompanionAnswer.Ambiguous(candidates))
        }
    }

    private sealed interface Action {
        data class Search(val query: String) : Action

        data class Read(val ref: String) : Action
    }

    private sealed interface Parsed {
        data class Invalid(val code: String) : Parsed

        data class Actions(val actions: List<Action>, val canonical: String) : Parsed

        data class Answer(val answer: CompanionAnswer<*>) : Parsed
    }

    private companion object {
        const val SEED_MENTIONS = 10
        const val SEED_HINTS = 6
        const val SEED_CONTEXT = 1
        const val READ_CONTEXT = 2
        const val SEED_WINDOW = 1_200
        const val READ_WINDOW = 2_400
        const val MAX_REACH = 8
        const val MIN_NAME_PART = 3
        const val SEARCH_HITS = 10
        const val MAX_ACTIONS_PER_ROUND = 4
        const val MAX_CLAIM = 500
        const val MAX_CLAIMS = 12
        const val MAX_ALIASES = 8
        const val MAX_CANDIDATES = 8
        const val SNIPPET = 400
        val REF = Regex("S[0-9]{1,4}")
        val HONORIFICS = setOf(
            "mr", "mrs", "ms", "miss", "master", "sir", "madam", "madame", "monsieur", "mademoiselle",
            "herr", "frau", "signor", "signora", "don", "dona", "lord", "lady", "dame", "ser",
            "king", "queen", "prince", "princess", "duke", "duchess", "count", "countess", "baron", "baroness",
            "captain", "colonel", "general", "major", "lieutenant", "sergeant", "corporal", "admiral", "commander",
            "doctor", "dr", "professor", "prof", "father", "mother", "brother", "sister", "uncle", "aunt",
            "saint", "st", "the", "of", "de", "du", "des", "von", "van", "der", "den", "el", "al", "la", "le",
        )

        fun snippet(text: String, word: String): String {
            if (text.length <= SNIPPET) return text
            val at = text.lowercase().indexOf(word).coerceAtLeast(0)
            var start = (at - SNIPPET / 3).coerceAtLeast(0)
            var end = (start + SNIPPET).coerceAtMost(text.length)
            start = (end - SNIPPET).coerceAtLeast(0)
            if (start > 0) start = text.indexOf(' ', start).takeIf { it in start until at.coerceAtLeast(start + 1) }?.plus(1) ?: start
            if (end < text.length) end = text.lastIndexOf(' ', end).takeIf { it > at } ?: end
            if (start > 0 && text[start - 1].isHighSurrogate()) start--
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            return (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
        }
    }
}
