package com.chmouel.liseur.data.remote

/**
 * Joining a link from a server onto the address we reach that server at.
 *
 * Shared, because the awkward case is the same for both: behind a
 * reverse proxy a server can advertise a scheme, a host or a path
 * prefix that the phone cannot use, so what comes back in a feed is
 * rebuilt onto the URL that actually answered.
 */
object RemoteUrl {

    /**
     * Turns what a user typed into a base URL: adds a scheme when none
     * was given, drops a trailing slash, and keeps any path prefix,
     * because a self-hosted server is often reverse-proxied under one.
     *
     * Returns null when the input cannot be a URL at all.
     */
    fun normaliseBase(input: String, defaultScheme: String = "https"): String? {
        val trimmed = input.trim().substringBefore('?').substringBefore('#')
        if (trimmed.isEmpty()) return null

        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains("://") -> return null
            else -> "$defaultScheme://$trimmed"
        }

        val schemeEnd = withScheme.indexOf("://") + 3
        val host = withScheme.substring(schemeEnd).substringBefore('/')
        if (host.isEmpty() || host.startsWith(":")) return null

        return withScheme.trimEnd('/')
    }

    /** Swaps `https` for `http`, used only after the user asks for it. */
    fun withHttp(baseUrl: String): String =
        if (baseUrl.startsWith("https://")) {
            "http://" + baseUrl.removePrefix("https://")
        } else {
            baseUrl
        }

    /** An API path against the server, e.g. `/api/v1/books/42/file`. */
    fun api(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    fun resolve(baseUrl: String, href: String): String {
        val base = baseUrl.trimEnd('/')
        val prefix = base.substring(base.indexOf("://") + 3).substringAfter('/', "")

        val path = when {
            href.startsWith("http://", true) || href.startsWith("https://", true) ->
                href.substring(href.indexOf("://") + 3).substringAfter('/', "")
            href.startsWith("/") -> href.removePrefix("/")
            else -> return "$base/$href"
        }

        // Behind a proxy the server already includes the path prefix that
        // the base URL carries; joining both would duplicate it.
        val relative = when {
            prefix.isEmpty() -> path
            path == prefix -> ""
            path.startsWith("$prefix/") -> path.removePrefix("$prefix/")
            else -> path
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }
}
