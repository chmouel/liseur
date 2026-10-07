package com.chmouel.liseur.tts

import org.json.JSONArray
import org.json.JSONObject

/**
 * The values a JSON schema property allows, as DeepInfra's model
 * descriptions write them: an `enum`, possibly behind a local `$ref`, an
 * `anyOf`/`oneOf`/`allOf`, or an array's `items`. Only references into
 * the same schema's definitions are followed, never a URL, and only so
 * deep.
 */
internal object SchemaEnum {
    /**
     * The presets [property] accepts, or null when it names none, so any
     * text is taken. Throws [SpeechError.InvalidResponse] for a broken reference.
     */
    fun values(schema: JSONObject, property: JSONObject): List<String>? =
        domain(schema, property, emptySet(), 0).presets.takeIf { it.isNotEmpty() }

    /** The presets a node names, and whether it accepts a given value. */
    private class Domain(val presets: List<String>, val accepts: (String) -> Boolean)

    private val ANY = Domain(emptyList()) { true }

    private fun domain(schema: JSONObject, node: JSONObject, seen: Set<String>, depth: Int): Domain {
        if (depth > MAX_DEPTH) throw SpeechError.InvalidResponse("schema too deep")
        // Every branch is read, so a broken one fails even beside a good one.
        fun branches(key: String): List<Domain>? = node.optJSONArray(key)?.let { options ->
            (0 until options.length()).mapNotNull { options.optJSONObject(it) }.map { domain(schema, it, seen, depth + 1) }
        }
        val parts = buildList {
            node.optString("\$ref").takeIf { it.isNotEmpty() }?.let { ref ->
                if (ref in seen) throw SpeechError.InvalidResponse("schema loop")
                add(domain(schema, definition(schema, ref), seen + ref, depth + 1))
            }
            types(node)?.let { types ->
                // An array leaves its values to `items`; null or a number takes no voice name.
                add(Domain(emptyList()) { "string" in types || "array" in types })
            }
            node.optJSONArray("enum")?.let(::strings)?.let { add(Domain(it) { v -> v in it }) }
            branches("anyOf")?.let { add(combine(it) { b, v -> b.any { d -> d.accepts(v) } }) }
            branches("oneOf")?.let { add(combine(it) { b, v -> b.count { d -> d.accepts(v) } == 1 }) }
            branches("allOf")?.let { add(combine(it) { b, v -> b.all { d -> d.accepts(v) } }) }
            node.optJSONObject("items")?.let { add(domain(schema, it, seen, depth + 1)) }
        }
        // A node's own keywords must all hold.
        return combine(parts) { b, v -> b.all { d -> d.accepts(v) } }
    }

    /** Offers the [branches]' presets that the combination still [accepts]. */
    private fun combine(branches: List<Domain>, accepts: (List<Domain>, String) -> Boolean): Domain {
        if (branches.isEmpty()) return ANY
        val test = { v: String -> accepts(branches, v) }
        return Domain(branches.flatMap { it.presets }.distinct().filter(test), test)
    }

    private fun definition(schema: JSONObject, ref: String): JSONObject {
        for ((prefix, key) in listOf("#/definitions/" to "definitions", "#/\$defs/" to "\$defs")) {
            if (ref.startsWith(prefix)) {
                return schema.optJSONObject(key)?.optJSONObject(ref.removePrefix(prefix))
                    ?: throw SpeechError.InvalidResponse("schema reference")
            }
        }
        throw SpeechError.InvalidResponse("schema reference")
    }

    private fun types(node: JSONObject): Set<String>? = when (val type = node.opt("type")) {
        is String -> setOf(type)
        is JSONArray -> strings(type).toSet()
        else -> null
    }

    private fun strings(values: JSONArray): List<String> =
        (0 until values.length()).mapNotNull { values.opt(it) as? String }

    private const val MAX_DEPTH = 8
}
