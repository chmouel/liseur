@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package com.chmouel.liseur.data.library

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.fromEpubHref
import org.readium.r2.shared.util.use
import org.readium.r2.shared.util.xml.ElementNode
import org.readium.r2.shared.util.xml.XmlParser
import org.xmlpull.v1.XmlPullParserException

/** Original author names for display; Readium's names remain the input to work identity. */
suspend fun primaryEpubAuthors(publication: Publication): List<String>? = withContext(Dispatchers.IO) {
    if (!publication.conformsTo(Publication.Profile.EPUB)) return@withContext null
    val document = publication.packageDocument() ?: return@withContext null
    epubAuthors(document, publication.metadata.authors.size)?.primary
}

internal suspend fun epubAuthorIdentityMatches(
    publication: Publication,
    expectedIdentity: String?,
): Boolean = withContext(Dispatchers.IO) {
    if (expectedIdentity.isNullOrBlank() || !publication.conformsTo(Publication.Profile.EPUB)) {
        return@withContext false
    }
    val document = publication.packageDocument() ?: return@withContext false
    val aliases = epubAuthors(document, publication.metadata.authors.size)?.aliases
        ?: return@withContext false
    matchesJoinedAuthorIdentity(expectedIdentity, aliases)
}

private suspend fun Publication.packageDocument(): ElementNode? {
    try {
        fun parse(bytes: ByteArray): ElementNode = bytes.inputStream().use { XmlParser().parse(it) }
        val container = get(Url("META-INF/container.xml")!!)?.use {
            it.read().getOrNull()?.let(::parse)
        } ?: return null
        val path = opfPath(container)?.let { Url.fromEpubHref(it) } ?: return null
        return get(path)?.use {
            it.read().getOrNull()?.let(::parse)
        }
    } catch (_: IOException) {
        return null
    } catch (_: XmlPullParserException) {
        return null
    }
}

internal fun opfPath(container: ElementNode): String? =
    container.getFirst("rootfiles", OCF_NAMESPACE)
        ?.get("rootfile", OCF_NAMESPACE)
        ?.firstOrNull { it.getAttr("media-type").orEmpty().trim() == OPF_MEDIA_TYPE }
        ?.getAttr("full-path")

internal fun primaryEpubAuthors(document: ElementNode, expectedCount: Int): List<String>? {
    return epubAuthors(document, expectedCount)?.primary
}

private data class ParsedEpubAuthors(
    val primary: List<String>,
    val aliases: List<Set<String>>,
)

private fun epubAuthors(document: ElementNode, expectedCount: Int): ParsedEpubAuthors? {
    if (document.name != "package" || document.namespace != OPF_NAMESPACE) return null
    val metadata = document.getFirst("metadata", OPF_NAMESPACE) ?: return null
    val metas = metadata.get("meta", OPF_NAMESPACE)
    val roles = metas.filter {
        it.packageProperty(document) == META_VOCABULARY + "role"
    }
    val authorElements = metadata.getAll().mapNotNull { item ->
        if (item.namespace != DC_NAMESPACE) return@mapNotNull null
        val role = item.getAttrNs("role", OPF_NAMESPACE)
            ?: roles.firstOrNull { item.id != null && it.getAttr("refines") == "#${item.id}" }?.text?.trim()
        val isAuthor = item.name == "creator" || item.name == "contributor" && role == "aut"
        if (!isAuthor) return@mapNotNull null
        item.text?.trim()?.takeIf(String::isNotEmpty)?.let { name -> item to name }
    }
    val authors = authorElements.map { it.second }
    // A mismatch means this OPF uses a contributor convention we did not recognize.
    if (authors.size != expectedCount) return null
    val alternateScripts = metas.filter {
        it.packageProperty(document) == META_VOCABULARY + "alternate-script"
    }
    val aliases = authorElements.map { (element, primary) ->
        buildSet {
            add(primary)
            element.id?.let { id ->
                alternateScripts.asSequence()
                    .filter { it.getAttr("refines") == "#$id" }
                    .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
                    .forEach(::add)
            }
        }
    }
    return ParsedEpubAuthors(authors, aliases)
}

internal fun matchesJoinedAuthorIdentity(identity: String, aliases: List<Set<String>>): Boolean {
    if (aliases.isEmpty()) return false
    val failed = mutableSetOf<Pair<Int, Int>>()

    fun matches(authorIndex: Int, offset: Int): Boolean {
        if (authorIndex == aliases.size) return offset == identity.length
        val state = authorIndex to offset
        if (state in failed) return false
        for (alias in aliases[authorIndex]) {
            if (!identity.startsWith(alias, offset)) continue
            val end = offset + alias.length
            if (authorIndex == aliases.lastIndex) {
                if (end == identity.length) return true
            } else if (identity.startsWith(", ", end) && matches(authorIndex + 1, end + 2)) {
                return true
            }
        }
        failed += state
        return false
    }

    return matches(0, 0)
}

private fun ElementNode.packageProperty(document: ElementNode): String {
    val property = getAttr("property").orEmpty()
    if (':' !in property) return META_VOCABULARY + property
    val prefix = property.substringBefore(':')
    val vocabulary = Regex("(?:^|\\s)${Regex.escape(prefix)}:\\s+(\\S+)")
        .find(document.getAttr("prefix").orEmpty())?.groupValues?.get(1)
    return vocabulary?.plus(property.substringAfter(':')) ?: property
}

private const val OCF_NAMESPACE = "urn:oasis:names:tc:opendocument:xmlns:container"
private const val OPF_NAMESPACE = "http://www.idpf.org/2007/opf"
private const val DC_NAMESPACE = "http://purl.org/dc/elements/1.1/"
private const val META_VOCABULARY = "http://idpf.org/epub/vocab/package/meta/#"
private const val OPF_MEDIA_TYPE = "application/oebps-package+xml"
