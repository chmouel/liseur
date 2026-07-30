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
