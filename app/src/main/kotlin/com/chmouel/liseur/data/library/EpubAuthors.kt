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
    try {
        fun parse(bytes: ByteArray): ElementNode = bytes.inputStream().use { XmlParser().parse(it) }
        val container = publication.get(Url("META-INF/container.xml")!!)?.use {
            it.read().getOrNull()?.let(::parse)
        } ?: return@withContext null
        val path = container.getFirst("rootfiles", OCF_NAMESPACE)
            ?.getFirst("rootfile", OCF_NAMESPACE)?.getAttr("full-path")
            ?.let { Url.fromEpubHref(it) } ?: return@withContext null
        val document = publication.get(path)?.use {
            it.read().getOrNull()?.let(::parse)
        } ?: return@withContext null
        primaryEpubAuthors(document, publication.metadata.authors.size)
    } catch (_: IOException) {
        null
    } catch (_: XmlPullParserException) {
        null
    }
}

internal fun primaryEpubAuthors(document: ElementNode, expectedCount: Int): List<String>? {
    if (document.name != "package" || document.namespace != OPF_NAMESPACE) return null
    val metadata = document.getFirst("metadata", OPF_NAMESPACE) ?: return null
    val roles = metadata.get("meta", OPF_NAMESPACE).filter {
        val property = it.getAttr("property").orEmpty()
        val expanded = if (':' in property) {
            val prefix = property.substringBefore(':')
            val vocabulary = Regex("(?:^|\\s)${Regex.escape(prefix)}:\\s+(\\S+)")
                .find(document.getAttr("prefix").orEmpty())?.groupValues?.get(1)
            vocabulary?.plus(property.substringAfter(':')) ?: property
        } else META_VOCABULARY + property
        expanded == META_VOCABULARY + "role"
    }
    val authors = metadata.getAll().mapNotNull { item ->
        if (item.namespace != DC_NAMESPACE) return@mapNotNull null
        val role = item.getAttrNs("role", OPF_NAMESPACE)
            ?: roles.firstOrNull { item.id != null && it.getAttr("refines") == "#${item.id}" }?.text?.trim()
        if (item.name != "creator" && !(item.name == "contributor" && role == "aut")) {
            return@mapNotNull null
        }
        item.text?.trim()?.takeIf { it.isNotEmpty() }
    }
    // A mismatch means this OPF uses a contributor convention we did not recognize.
    return authors.takeIf { it.size == expectedCount }
}

private const val OCF_NAMESPACE = "urn:oasis:names:tc:opendocument:xmlns:container"
private const val OPF_NAMESPACE = "http://www.idpf.org/2007/opf"
private const val DC_NAMESPACE = "http://purl.org/dc/elements/1.1/"
private const val META_VOCABULARY = "http://idpf.org/epub/vocab/package/meta/#"
