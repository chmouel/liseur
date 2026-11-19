package com.chmouel.liseur.insights

internal data class CrashContext(
    val screen: String? = null,
    val heapUsedMb: Long? = null,
    val heapMaxMb: Long? = null,
)

internal object ReportingPayload {
    fun properties(
        event: String,
        version: String,
        exception: List<Map<String, Any>>? = null,
        crashContext: CrashContext? = null,
    ): Map<String, Any>? {
        val feature = UsageEvent.entries.firstOrNull { it.wireName == event }?.feature
        if (feature == null && (event != "\$exception" || exception == null)) return null
        return buildMap {
            put("app_version", version)
            put("\$process_person_profile", false)
            if (feature != null) put("feature", feature)
            if (exception != null && event == "\$exception") {
                put("\$exception_list", sanitizeExceptions(exception))
                put("\$exception_level", "fatal")
                crashContext?.screen?.let { screen ->
                    symbol(screen).takeIf { it != "unknown" }?.let { put("screen", it) }
                }
                crashContext?.heapUsedMb?.takeIf { it >= 0 }?.let { put("heap_used_mb", it) }
                crashContext?.heapMaxMb?.takeIf { it > 0 }?.let { put("heap_max_mb", it) }
            }
        }
    }

    fun crashContext(properties: Map<String, Any>?): CrashContext? {
        if (properties == null) return null
        val screen = (properties["screen"] as? String)?.let { symbol(it).takeIf { s -> s != "unknown" } }
        val used = (properties["heap_used_mb"] as? Number)?.toLong()?.takeIf { it >= 0 }
        val max = (properties["heap_max_mb"] as? Number)?.toLong()?.takeIf { it > 0 }
        if (screen == null && used == null && max == null) return null
        return CrashContext(screen = screen, heapUsedMb = used, heapMaxMb = max)
    }

    private fun sanitizeExceptions(items: List<Map<String, Any>>): List<Map<String, Any>> =
        items.take(8).map { item ->
            val stack = item["stacktrace"] as? Map<*, *>
            val frames = stack?.get("frames") as? List<*> ?: emptyList<Any>()
            mapOf(
                "type" to symbol(item["type"]),
                "mechanism" to mapOf("type" to "UncaughtExceptionHandler", "handled" to false),
                "stacktrace" to mapOf("frames" to frames.take(64).mapNotNull { value ->
                    val frame = value as? Map<*, *> ?: return@mapNotNull null
                    buildMap<String, Any> {
                        put("platform", "java")
                        put("module", symbol(frame["module"]))
                        put("function", symbol(frame["function"]))
                        (frame["lineno"] as? Number)?.toInt()?.takeIf { it >= 0 }?.let { put("lineno", it) }
                    }
                }),
            )
        }

    private fun symbol(value: Any?): String = (value as? String)
        ?.takeIf { it.length <= 256 && it.matches(Regex("[A-Za-z0-9_.$<>/-]+")) } ?: "unknown"

    fun exception(error: Throwable): List<Map<String, Any>> {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val result = mutableListOf<Map<String, Any>>()
        var current: Throwable? = error
        while (current != null && result.size < 8 && seen.add(current)) {
            val item = current
            result += mapOf(
                "type" to item.javaClass.name,
                "mechanism" to mapOf("type" to "UncaughtExceptionHandler", "handled" to false),
                "stacktrace" to mapOf("frames" to item.stackTrace.take(64).reversed().map { frame ->
                    buildMap<String, Any> {
                        put("platform", "java")
                        put("module", frame.className.take(256))
                        put("function", frame.methodName.take(256))
                        if (frame.lineNumber >= 0) put("lineno", frame.lineNumber)
                    }
                }),
            )
            current = item.cause
        }
        return result
    }
}
