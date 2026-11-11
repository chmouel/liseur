package com.chmouel.liseur.tts

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Contributor
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class ReadAloudMetadataTest {

    @Test
    fun chaptersLinkToTheCoverInsteadOfCopyingIt() = runBlocking {
        val publication = Publication(
            Manifest(
                metadata = Metadata(
                    localizedTitle = LocalizedString("Dune"),
                    authors = listOf(Contributor(name = "Frank Herbert")),
                ),
                readingOrder = (1..3).map { Link(Url("chapter$it.xhtml")!!) },
            ),
        )
        val cover = Uri.parse("file:///cache/read-aloud-cover/1.jpg")

        val chapter = ReadAloudMetadata(cover).createMetadataFactory(publication).resourceMetadata(2)

        assertEquals(cover, chapter.artworkUri)
        assertNull(chapter.artworkData)
        assertEquals("Dune", chapter.title)
        assertEquals("Frank Herbert", chapter.artist)
        assertEquals(2, chapter.trackNumber)
    }
}
