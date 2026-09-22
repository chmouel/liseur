package com.chmouel.liseur.data.bookorbit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.jsoup.Jsoup
import org.jsoup.nodes.DocumentType
import org.jsoup.parser.Parser
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * The package facts needed to later resolve a BookOrbit CFI against a local
 * EPUB. It intentionally leaves document-tree resolution to Phase 2.
 *
 * Hrefs are archive entry names: resolved against the OPF, percent-decoded
 * and without fragment or query, so they compare with what is in the zip.
 */
data class BookOrbitEpubPackage(
    val packagePath: String,
    val manifest: Map<String, ManifestItem>,
    val spine: List<SpineItem>,
    val spineStep: BookOrbitCfi.Component.Step,
) {
    data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String?,
    )

    data class SpineItem(
        val idref: String,
        val linear: Boolean,
        val href: String,
        val step: BookOrbitCfi.Component.Step,
    )

    companion object {
        /**
         * Reads the package of the EPUB at [epub].
         *
         * `ZipFile` reads the central directory, which is what Readium
         * trusts, so a duplicate local header cannot show this code a
         * different OPF from the one the reader opens. Only the container
         * and the OPF are decompressed, each under [MAX_XML_BYTES].
         */
        fun parse(epub: File): BookOrbitEpubPackage =
            ZipFile(epub).use { zip ->
                fun read(name: String): ByteArray {
                    val entry = zip.getEntry(name) ?: throw ParseException("EPUB has no $name")
                    if (entry.isDirectory) throw ParseException("EPUB entry $name is a directory")
                    return zip.getInputStream(entry).use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_XML_BYTES) throw ParseException("EPUB entry $name is too large")
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                }
                val packagePath = parseContainer(read(CONTAINER_PATH))
                parsePackage(packagePath, read(packagePath))
            }

        fun parsePackage(packagePath: String, opf: ByteArray): BookOrbitEpubPackage {
            val normalizedPackagePath = normalize(packagePath)
            val root = xml(opf).documentElement
            if (!root.isOpf("package")) throw ParseException("OPF root is not package")
            val manifestElement = root.childrenNamed("manifest", OPF_NAMESPACE).singleOrNull()
                ?: throw ParseException("OPF has no unique manifest")
            val items = linkedMapOf<String, ManifestItem>()
            val remote = mutableSetOf<String>()
            manifestElement.childrenNamed("item", OPF_NAMESPACE).forEach { item ->
                val id = item.required("id")
                if (id in items || id in remote) throw ParseException("manifest id $id is duplicated")
                val href = item.required("href")
                // EPUB 3 allows remote resources in the manifest. They have
                // no archive entry, so they are left out rather than joined
                // under the package folder as if they were local.
                if (SCHEME.containsMatchIn(href)) {
                    remote += id
                    return@forEach
                }
                items[id] = ManifestItem(
                    id = id,
                    href = resolve(normalizedPackagePath, href),
                    mediaType = item.getAttribute("media-type").ifBlank { null },
                )
            }
            val spineElement = root.childrenNamed("spine", OPF_NAMESPACE).singleOrNull()
                ?: throw ParseException("OPF has no unique spine")
            val spine = spineElement.childrenNamed("itemref", OPF_NAMESPACE).map { itemref ->
                val idref = itemref.required("idref")
                if (idref in remote) throw ParseException("spine item $idref is remote")
                val item = items[idref] ?: throw ParseException("spine references missing item $idref")
                SpineItem(
                    idref = idref,
                    linear = itemref.getAttribute("linear").trim().lowercase() != "no",
                    href = item.href,
                    step = itemref.cfiStep(),
                )
            }
            return BookOrbitEpubPackage(normalizedPackagePath, items, spine, spineElement.cfiStep())
        }

        /**
         * The default rendition: the first rootfile whose media type is the
         * OPF's, as the OCF specification defines it.
         */
        fun parseContainer(container: ByteArray): String {
            val root = xml(container).documentElement
            if (!root.isNamed("container", OCF_NAMESPACE)) throw ParseException("container root is not container")
            val rootfile = root.childrenNamed("rootfiles", OCF_NAMESPACE)
                .flatMap { it.childrenNamed("rootfile", OCF_NAMESPACE) }
                .firstOrNull { it.getAttribute("media-type").trim() == OPF_MEDIA_TYPE }
                ?: throw ParseException("container has no OPF rootfile")
            return normalize(percentDecode(rootfile.required("full-path")))
        }

        /**
         * Jsoup safely identifies declarations before strict DOM parsing.
         * Android does not support every DocumentBuilderFactory feature,
         * so these switches are best effort; the preflight allows only bare
         * DOCTYPE declarations and the resolver refuses external references.
         */
        private fun xml(bytes: ByteArray): org.w3c.dom.Document {
            if (bytes.size > MAX_XML_BYTES) throw ParseException("EPUB XML is too large")
            val preflight = Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
            if (preflight.childNodes().filterIsInstance<DocumentType>().any {
                    !BARE_DOCTYPE.matches(it.outerHtml())
                }
            ) {
                throw ParseException("EPUB XML document type must not contain identifiers or declarations")
            }
            try {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    bestEffort("http://xml.org/sax/features/external-general-entities", false)
                    bestEffort("http://xml.org/sax/features/external-parameter-entities", false)
                    bestEffort("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                    bestEffort(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    runCatching { isXIncludeAware = false }
                    isExpandEntityReferences = false
                }
                val builder = factory.newDocumentBuilder().apply {
                    setEntityResolver { _, _ -> throw SAXException("external XML entity") }
                    setErrorHandler(object : DefaultHandler() {
                        override fun fatalError(error: org.xml.sax.SAXParseException): Nothing = throw error
                    })
                }
                return builder.parse(ByteArrayInputStream(bytes)).also {
                    it.doctype?.let { type ->
                        if (type.publicId != null || type.systemId != null || !type.internalSubset.isNullOrEmpty()) {
                            throw ParseException("EPUB XML document type must not contain identifiers or declarations")
                        }
                    }
                }
            } catch (error: SAXException) {
                throw ParseException("EPUB XML is not well-formed: ${error.message}")
            } catch (error: ParserConfigurationException) {
                throw ParseException("EPUB XML parser is unavailable: ${error.message}")
            }
        }

        private fun DocumentBuilderFactory.bestEffort(feature: String, value: Boolean) {
            runCatching { setFeature(feature, value) }
        }

        private fun Element.isOpf(name: String): Boolean = isNamed(name, OPF_NAMESPACE)

        private fun Element.cfiStep(): BookOrbitCfi.Component.Step = BookOrbitCfi.Component.Step(
            index = (parentNode.childNodes.elements().indexOf(this) + 1) * 2,
            id = getAttribute("id").ifEmpty { null },
        )

        private fun Element.isNamed(name: String, namespace: String): Boolean =
            localName == name && namespaceURI == namespace

        private fun Element.childrenNamed(name: String, namespace: String): List<Element> =
            childNodes.elements().filter { it.isNamed(name, namespace) }

        private fun NodeList.elements(): List<Element> =
            (0 until length).mapNotNull { item(it) as? Element }

        private fun Element.required(name: String): String =
            getAttribute(name).trim().takeIf { it.isNotEmpty() }
                ?: throw ParseException("XML element $localName has no $name")

        private fun resolve(base: String, href: String): String {
            if (href.startsWith('/')) throw ParseException("manifest href $href is absolute")
            val path = percentDecode(href.substringBefore('#').substringBefore('?'))
            if (path.isEmpty()) throw ParseException("manifest item has an empty href")
            val directory = base.substringBeforeLast('/', "")
            return normalize(if (directory.isEmpty()) path else "$directory/$path")
        }

        /**
         * Percent-decoding as for a URL path: `+` stays a plus. Unescaped
         * text is copied a run at a time so surrogate pairs stay whole, and
         * escapes that do not decode to valid UTF-8 are refused rather than
         * replaced, which would name an entry the archive does not hold.
         */
        private fun percentDecode(value: String): String {
            if ('%' !in value) return value
            val bytes = ByteArrayOutputStream()
            var at = 0
            while (at < value.length) {
                val escape = value.indexOf('%', at)
                if (escape != at) {
                    val end = if (escape < 0) value.length else escape
                    bytes.write(value.substring(at, end).toByteArray(Charsets.UTF_8))
                    at = end
                    continue
                }
                val hex = value.substring(at + 1, (at + 3).coerceAtMost(value.length))
                if (hex.length != 2 || !hex.all { it in HEX }) throw ParseException("href has a bad escape")
                bytes.write(hex.toInt(16))
                at += 3
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw ParseException("href escapes are not valid UTF-8")
            }
        }

        private fun normalize(path: String): String {
            if (path.startsWith('/')) throw ParseException("EPUB path must not be absolute")
            val result = mutableListOf<String>()
            path.split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> if (result.isEmpty()) {
                        throw ParseException("EPUB path escapes its archive")
                    } else {
                        result.removeAt(result.lastIndex)
                    }
                    else -> result += part
                }
            }
            return result.joinToString("/").takeIf { it.isNotEmpty() }
                ?: throw ParseException("EPUB path is empty")
        }

        private const val HEX = "0123456789abcdefABCDEF"
        private val BARE_DOCTYPE = Regex("<!DOCTYPE[ \\t\\r\\n]+[^\\s\\[<>]+[ \\t\\r\\n]*>")
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        private const val CONTAINER_PATH = "META-INF/container.xml"
        private const val OCF_NAMESPACE = "urn:oasis:names:tc:opendocument:xmlns:container"
        private const val OPF_NAMESPACE = "http://www.idpf.org/2007/opf"
        private const val OPF_MEDIA_TYPE = "application/oebps-package+xml"
        private const val MAX_XML_BYTES = 4 * 1024 * 1024
    }

    class ParseException(message: String) : IllegalArgumentException(message)
}
