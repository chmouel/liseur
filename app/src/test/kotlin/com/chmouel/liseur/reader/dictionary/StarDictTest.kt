package com.chmouel.liseur.reader.dictionary

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StarDictTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `looks up headwords, non-ASCII casing, inflections, and strips active markup`() {
        val pack = StarDict.unpack(
            archive(
                headwords = listOf(
                    "École" to """/ekɔl/
                        <p><b>Nom commun</b> <i>f.</i></p>
                        <ol><li>√ Établissement d'enseignement.<script>alert(1)</script><img src="https://example.com/p.png"></li></ol>
                        <p>Du latin <i>schola</i>.</p>""",
                ),
                synonyms = listOf("écoles" to 0),
            ).inputStream(),
            temporaryFolder.newFolder(),
        )

        assertEquals("reader.dict FR", pack.name)
        assertEquals("fr", pack.language)
        assertTrue(pack.isReaderDict)

        // NFC decomposition + non-ASCII case folding ("e\u0301cole" -> "école" -> "École")
        val entry = pack.lookup("e\u0301cole").single()
        assertEquals("École", entry.headword)
        assertEquals("/ekɔl/", entry.pronunciation)
        assertEquals("Nom commun f.", entry.sections.single().heading)
        assertEquals(listOf("Établissement d'enseignement."), entry.sections.single().items)
        assertEquals(listOf("Du latin <i>schola</i>."), entry.notes)

        // Inflection lookup through .syn resolves to the base headword
        assertEquals(entry, pack.lookup("écoles").single())
        assertTrue(pack.lookup("absent").isEmpty())
    }

    @Test
    fun `filters out multi-word idiom noise when a direct headword exists`() {
        val sortedHeadwords = listOf(
            "agua" to "<p><b>Sustantivo</b></p><ol><li>H<sub>2</sub>O</li></ol>",
            "aguar" to "<p><b>Verbo</b></p><ol><li>Mezclar con agua</li></ol>",
            "tirarse al agua" to "<p><b>Locución</b></p><ol><li>Decidirse</li></ol>",
        )
        val pack = StarDict.unpack(
            archive(
                stem = "reader.dict-es",
                bookname = "reader.dict ES",
                headwords = sortedHeadwords,
                synonyms = listOf("agua" to 1, "agua" to 2),
            ).inputStream(),
            temporaryFolder.newFolder(),
        )

        val results = pack.lookup("agua")
        assertEquals(listOf("agua", "aguar"), results.map(StarDictEntry::headword))
    }

    @Test
    fun `ignores harmless extra files in StarDict archives`() {
        val pack = StarDict.unpack(
            archive(extraZipEntries = mapOf("README.txt" to "Generated StarDict".encodeToByteArray())).inputStream(),
            temporaryFolder.newFolder(),
        )

        assertEquals(1, pack.wordCount)
        assertFalse(pack.lookup("école").isEmpty())
    }

    @Test
    fun `bounds decompression of ignored zip entries`() {
        val oversizedIgnoredFile = ByteArray(65 * 1024 * 1024)
        assertThrows(IOException::class.java) {
            StarDict.unpack(
                archive(extraZipEntries = mapOf("README.txt" to oversizedIgnoredFile)).inputStream(),
                temporaryFolder.newFolder(),
            )
        }
    }

    @Test
    fun `rejects synonym indexes that point outside the headword table`() {
        assertThrows(IOException::class.java) {
            StarDict.unpack(
                archive(synonyms = listOf("écoles" to 9)).inputStream(),
                temporaryFolder.newFolder(),
            )
        }
    }

    @Test
    fun `primary language matching handles Bokmål and regional tags`() {
        assertTrue(OfflineDictionary.samePrimary("nb", "no"))
        assertTrue(OfflineDictionary.samePrimary("pt-BR", "pt"))
        assertFalse(OfflineDictionary.samePrimary("fr", "en"))
    }

    private fun archive(
        stem: String = "reader.dict-fr",
        bookname: String = "reader.dict FR",
        headwords: List<Pair<String, String>> = listOf("école" to "<b>School</b>"),
        synonyms: List<Pair<String, Int>> = listOf("écoles" to 0),
        extraZipEntries: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val dictBytes = ByteArrayOutputStream()
        val offsets = mutableListOf<Triple<String, Int, Int>>()
        for ((word, html) in headwords) {
            val encoded = html.encodeToByteArray()
            offsets += Triple(word, dictBytes.size(), encoded.size)
            dictBytes.write(encoded)
        }
        val index = binary {
            for ((word, offset, size) in offsets) {
                write(word.encodeToByteArray())
                writeByte(0)
                writeInt(offset)
                writeInt(size)
            }
        }
        val synBytes = binary {
            for ((word, target) in synonyms) {
                write(word.encodeToByteArray())
                writeByte(0)
                writeInt(target)
            }
        }
        val metadata = """
            StarDict's dict ifo file
            version=3.0.0
            bookname=$bookname
            author=reader.dict
            wordcount=${offsets.size}
            idxfilesize=${index.size}
            synwordcount=${synonyms.size}
            sametypesequence=h
        """.trimIndent().encodeToByteArray()
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(dictBytes.toByteArray()) }
        }.toByteArray()

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val files = linkedMapOf(
                "$stem.ifo" to metadata,
                "$stem.idx" to index,
                "$stem.syn" to synBytes,
                "$stem.dict.dz" to compressed,
            ) + extraZipEntries
            for ((name, data) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun binary(write: DataOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use(write)
        return output.toByteArray()
    }
}
