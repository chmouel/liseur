package com.chmouel.liseur.data.calibre

/**
 * The bits of calibre-web's responses the setup flow needs to read.
 *
 * The Kobo token is only reachable through the web UI, so part of this
 * is HTML scraping rather than an API. It is kept here, pure and
 * tested, so a calibre-web template change is a single obvious place to
 * fix rather than a mystery failure.
 */
object CalibreParsing {

    private val ACQUISITION = Regex(
        """<link[^>]*rel="http://opds-spec\.org/acquisition[^"]*"[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val HREF = Regex("""href="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val CSRF = Regex(
        """name="csrf_token"[^>]*value="([^"]*)"""",
        RegexOption.IGNORE_CASE,
    )
    private val CSRF_VALUE_FIRST = Regex(
        """value="([^"]*)"[^>]*name="csrf_token"""",
        RegexOption.IGNORE_CASE,
    )
    private val USER_ID = Regex("""generate_auth_token/(\d+)""")
    private val KOBO_TOKEN = Regex("""/kobo/([0-9a-fA-F]{32})""")

    /** True when the body really is an OPDS catalog and not, say, a login page. */
    fun isOpdsFeed(body: String): Boolean {
        val head = body.take(4096)
        return head.contains("<feed", ignoreCase = true) &&
            head.contains("http://www.w3.org/2005/Atom", ignoreCase = true)
    }

    /** The first book download link in a feed, used to probe download rights. */
    fun firstAcquisitionHref(feed: String): String? =
        ACQUISITION.findAll(feed)
            .mapNotNull { HREF.find(it.value)?.groupValues?.get(1) }
            .firstOrNull()
            ?.let(::unescapeXml)

    /** The CSRF token from a calibre-web form, needed to log in. */
    fun csrfToken(html: String): String? =
        (CSRF.find(html) ?: CSRF_VALUE_FIRST.find(html))?.groupValues?.get(1)?.let(::unescapeXml)

    /** The numeric user id, read off the profile page's Kobo setup link. */
    fun userId(profileHtml: String): Int? =
        USER_ID.find(profileHtml)?.groupValues?.get(1)?.toIntOrNull()

    /** The 32-character sync token from the Kobo setup page. */
    fun koboToken(html: String): String? =
        KOBO_TOKEN.find(html)?.groupValues?.get(1)?.lowercase()

    private fun unescapeXml(value: String) = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
