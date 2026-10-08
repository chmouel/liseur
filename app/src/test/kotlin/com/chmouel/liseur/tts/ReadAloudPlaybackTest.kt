package com.chmouel.liseur.tts

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Resuming after a failed sentence and starting from a selection, driven
 * through Readium's real navigator: only the network and the speaker are
 * fakes.
 */
@OptIn(ExperimentalReadiumApi::class)
@Config(sdk = [35], application = Application::class)
// Readium's player checks it is called on the main looper's thread, so that
// thread runs on its own, as on a device, and the clock keeps real time.
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
@RunWith(RobolectricTestRunner::class)
class ReadAloudPlaybackTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val main = Dispatchers.Main

    @Volatile private var ticking = true
    private val clock = Thread {
        while (ticking) {
            Thread.sleep(TICK_MS)
            ShadowSystemClock.advanceBy(Duration.ofMillis(TICK_MS))
        }
    }.apply { isDaemon = true }
    private val scope = CoroutineScope(SupervisorJob() + main)

    private val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val requested = ConcurrentHashMap<String, AtomicInteger>()

    /** Fails the nth request for a sentence, once. */
    private val failures = ConcurrentHashMap<String, Int>()
    private val chapterStops = AtomicInteger()

    private lateinit var publication: Publication
    private lateinit var playback: ReadAloudPlayback
    private var beforeOpen: suspend () -> Unit = {}
    private var tokenizerFactory: (Language?) -> TextTokenizer = BoundedSentenceTokenizer.factory()

    @Before
    fun setUp() = runBlocking {
        clock.start()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val http = DefaultHttpClient()
        val assets = AssetRetriever(context.contentResolver, http)
        val epub = File(folder.root, "book.epub").also(::writeEpub)
        val asset = assets.retrieve(epub.toUrl(isDirectory = false)).getOrNull()!!
        publication = PublicationOpener(DefaultPublicationParser(context, http, assets, pdfFactory = null))
            .open(asset, allowUserInteraction = false).getOrNull()!!

        val cache = SpeechCache(scope, { text ->
            val nth = requested.getOrPut(text) { AtomicInteger() }.incrementAndGet()
            // A late answer, as from a slow network.
            delay(30)
            if (failures[text] == nth) {
                failures.remove(text)
                throw SpeechError.Service(503)
            }
            SpeechAudio(text.toByteArray())
        })
        val output = object : PcmOutput {
            override suspend fun play(pcm: ByteArray) {
                delay(5)
                played += String(pcm)
            }

            override fun halt() {}
            override fun release() {}
        }
        playback = withContext(main) {
            ReadAloudPlayback(
                scope = scope,
                opener = { initial, observer, listener ->
                    beforeOpen()
                    val provider = SpeechTtsEngineProvider(
                        scope = scope,
                        cache = cache,
                        output = { output },
                        voices = setOf(SpeechTtsEngine.Voice("Kore", Language("en"))),
                        observer = observer,
                    )
                    TtsNavigatorFactory(context, publication, provider, tokenizerFactory)
                        ?.createNavigator(listener, initial)
                        ?.getOrNull()
                },
                onChapterEnded = { chapterStops.incrementAndGet() },
            )
        }
    }

    @After
    fun tearDown() {
        runBlocking(main) { playback.close() }
        scope.cancel()
        publication.close()
        ticking = false
        clock.join()
    }

    private val chapterStart: Locator get() = publication.locatorFromLink(publication.readingOrder[0])!!

    private fun awaitPlayed(count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (played.size < count && playback.failure.value == null) {
            check(System.currentTimeMillis() < deadline) { "only played $played" }
            Thread.sleep(10)
        }
    }

    private fun awaitFailure() {
        val deadline = System.currentTimeMillis() + 10_000
        while (playback.failure.value == null) {
            check(System.currentTimeMillis() < deadline) { "no failure after $played" }
            Thread.sleep(10)
        }
    }

    private fun awaitChapterStop() {
        val deadline = System.currentTimeMillis() + 10_000
        while (chapterStops.get() == 0 || playback.navigator.value!!.playback.value.playWhenReady) {
            check(System.currentTimeMillis() < deadline) { "never stopped at chapter end after $played" }
            Thread.sleep(10)
        }
    }

    @Test
    fun chapterTimerPausesBeforeTheNextChapterAndResumeContinuesThere() {
        runBlocking(main) {
            playback.start(chapterStart, target = { it.utterance == TAIL.last() })
            playback.stopAtChapterEnd(true)
        }
        awaitChapterStop()
        assertEquals(listOf(TAIL.last()), played.toList())
        assertFalse(playback.navigator.value!!.playback.value.playWhenReady)
        assertEquals(null, requested[NEXT_CHAPTER])

        runBlocking(main) { playback.resume() }
        awaitPlayed(2)
        assertEquals(NEXT_CHAPTER, played[1])
        assertEquals(1, chapterStops.get())
    }

    @Test
    fun chapterTimerArmedBeforeOpeningSurvivesAPausedVoiceReplay() {
        runBlocking(main) {
            playback.stopAtChapterEnd(true)
            playback.start(chapterStart, target = {
                playback.pause()
                it.utterance == TAIL.last()
            })
            playback.replay()
            assertTrue(played.isEmpty())
            playback.resume()
        }
        awaitChapterStop()
        assertEquals(listOf(TAIL.last()), played.toList())
        assertFalse(playback.navigator.value!!.playback.value.playWhenReady)
    }

    @Test
    fun cancellingTheChapterTimerAllowsPlaybackIntoTheNextChapter() {
        runBlocking(main) {
            playback.stopAtChapterEnd(true)
            playback.start(chapterStart, target = { it.utterance == TAIL.last() })
            playback.stopAtChapterEnd(false)
        }
        awaitPlayed(2)
        assertEquals(listOf(TAIL.last(), NEXT_CHAPTER), played.take(2))
        assertEquals(0, chapterStops.get())
    }

    /** Readium's player steps past a failed sentence unless it is paused first; make sure it has. */
    private fun movePastTheFailure() {
        val failed = playback.failure.value!!.anchor!!
        // The navigator itself, as Readium's player does; the playback's own skips are the reader's.
        runBlocking(main) { playback.navigator.value!!.skipToNextUtterance() }
        val deadline = System.currentTimeMillis() + 5_000
        while (failed.isAt(playback.navigator.value!!.location.value)) {
            check(System.currentTimeMillis() < deadline) { "never left the failed sentence" }
            Thread.sleep(10)
        }
    }

    @Test
    fun resumingAfterAFailedThirdSentenceSpeaksItFirst() {
        failures[THIRD] = 1
        runBlocking(main) { playback.start(chapterStart) }
        awaitFailure()
        assertEquals(listOf(FIRST, SECOND), played.toList())
        assertEquals(THIRD, playback.failure.value!!.anchor!!.text)
        movePastTheFailure()

        assertEquals(ReadAloudPlayback.Landing.Sentence, runBlocking(main) { playback.resume() })
        awaitPlayed(4)
        assertEquals(listOf(FIRST, SECOND, THIRD, FOURTH), played.take(4))
    }

    @Test
    fun aFailedRepeatedSentenceResumesAtTheOccurrenceThatFailed() {
        failures[REPEATED] = 2
        runBlocking(main) { playback.start(chapterStart) }
        awaitFailure()
        assertEquals(listOf(FIRST, SECOND, THIRD, FOURTH, REPEATED), played.toList())
        movePastTheFailure()

        assertEquals(ReadAloudPlayback.Landing.Sentence, runBlocking(main) { playback.resume() })
        awaitPlayed(7)
        assertEquals(listOf(REPEATED, LAST), played.drop(5).take(2))
    }

    @Test
    fun aFailureDeepInAParagraphRetriesTheFailedSentenceFirst() {
        val failed = TAIL[25]
        failures[failed] = 1
        runBlocking(main) { playback.start(chapterStart) }
        awaitFailure()
        assertEquals(failed, playback.failure.value!!.anchor!!.text)
        val heard = played.size
        movePastTheFailure()

        assertEquals(ReadAloudPlayback.Landing.Sentence, runBlocking(main) { playback.resume() })
        awaitPlayed(heard + 1)
        assertEquals(failed, played[heard])
    }

    @Test
    fun retriesAskedForTogetherTakeTurns() {
        failures[THIRD] = 1
        runBlocking(main) { playback.start(chapterStart) }
        awaitFailure()
        movePastTheFailure()

        val landings = runBlocking(main) {
            val first = async { playback.resume() }
            val second = async { playback.resume() }
            listOf(first.await(), second.await())
        }
        assertEquals(listOf(ReadAloudPlayback.Landing.Sentence, ReadAloudPlayback.Landing.Sentence), landings)
        awaitPlayed(4)
        assertEquals(listOf(FIRST, SECOND, THIRD, FOURTH), played.take(4))
    }

    @Test
    fun aPauseAfterTwoRetriesStopsTheOneStillWaiting() {
        failures[THIRD] = 1
        runBlocking(main) { playback.start(chapterStart) }
        awaitFailure()
        movePastTheFailure()
        val opening = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeOpen = {
            opening.complete(Unit)
            release.await()
        }

        runBlocking(main) {
            val first = async { playback.resume() }
            val second = async { playback.resume() }
            opening.await()
            beforeOpen = {}
            playback.pause()
            release.complete(Unit)
            first.await()
            second.await()
        }
        assertFalse(playback.navigator.value!!.playback.value.playWhenReady)
        assertEquals(listOf(FIRST, SECOND), played.toList())
    }

    @Test
    fun skippingAfterAFailurePlaysOnFromTheSkip() {
        failures[THIRD] = 1
        runBlocking(main) { playback.start(chapterStart) }
        awaitFailure()

        runBlocking(main) { playback.skipToNext() }
        assertEquals(null, playback.failure.value)
        runBlocking(main) { playback.resume() }
        awaitPlayed(3)
        assertTrue(played[2] != THIRD)
    }

    @Test
    fun aRetryThatCannotFindTheFailedSentenceStaysPaused() {
        failures[THIRD] = 1
        runBlocking(main) { playback.start(chapterStart) }
        awaitFailure()
        val failed = playback.failure.value
        tokenizerFactory = { language ->
            val sentences = BoundedSentenceTokenizer.factory()(language)
            object : TextTokenizer {
                override fun tokenize(data: String): List<IntRange> =
                    sentences.tokenize(data).filter { data.substring(it) != THIRD }
            }
        }

        assertEquals(ReadAloudPlayback.Landing.Failed, runBlocking(main) { playback.resume() })
        assertFalse(playback.navigator.value!!.playback.value.playWhenReady)
        assertEquals(failed, playback.failure.value)
        assertEquals(listOf(FIRST, SECOND), played.toList())
    }

    @Test
    fun pausingWhileTheNavigatorOpensPreventsPlayback() = runBlocking(main) {
        val opening = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeOpen = {
            opening.complete(Unit)
            release.await()
        }
        val start = async { playback.start(chapterStart) }
        opening.await()
        playback.pause()
        release.complete(Unit)

        assertEquals(ReadAloudPlayback.Landing.Sentence, start.await())
        assertFalse(playback.navigator.value!!.playback.value.playWhenReady)
        assertTrue(played.isEmpty())
    }

    @Test
    fun pausingDuringAlignmentKeepsTheTargetPausedUntilResume() {
        runBlocking(main) {
            val landing = playback.start(chapterStart, target = {
                if (it.utterance == FIRST) playback.pause()
                it.utterance == THIRD
            })
            assertEquals(ReadAloudPlayback.Landing.Sentence, landing)
            assertFalse(playback.navigator.value!!.playback.value.playWhenReady)
            assertTrue(played.isEmpty())
            playback.resume()
        }
        awaitPlayed(1)
        assertEquals(THIRD, played.first())
    }

    @Test
    fun replayingWhilePausedStaysPausedOnTheSameSentence() {
        runBlocking(main) {
            playback.start(chapterStart, target = {
                if (it.utterance == FIRST) playback.pause()
                it.utterance == THIRD
            })
            val before = playback.navigator.value

            assertEquals(ReadAloudPlayback.Landing.Sentence, playback.replay())
            assertTrue(playback.navigator.value !== before)
            assertEquals(THIRD, playback.navigator.value!!.location.value.utterance)
            assertFalse(playback.navigator.value!!.playback.value.playWhenReady)
            assertTrue(played.isEmpty())
            playback.resume()
        }
        awaitPlayed(1)
        assertEquals(THIRD, played.first())
    }

    @Test
    fun replayingInAnotherLanguageFindsTheSentenceCutDifferently() {
        runBlocking(main) {
            playback.start(chapterStart, target = {
                if (it.utterance == FIRST) playback.pause()
                it.utterance == THIRD
            })
            // The new language's tokenizer reads two sentences at a time.
            tokenizerFactory = { language ->
                val sentences = BoundedSentenceTokenizer.factory()(language)
                object : TextTokenizer {
                    override fun tokenize(data: String): List<IntRange> =
                        sentences.tokenize(data).chunked(2) { it.first().first..it.last().last }
                }
            }

            assertEquals(ReadAloudPlayback.Landing.Sentence, playback.replay(recut = true))
            assertTrue(playback.navigator.value!!.location.value.utterance.startsWith(THIRD))
            assertFalse(playback.navigator.value!!.playback.value.playWhenReady)
            assertTrue(played.isEmpty())
            playback.resume()
        }
        awaitPlayed(1)
        assertTrue(played.first().startsWith(THIRD))
    }

    @Test
    fun chapterTimerSurvivesAReplayInAnotherLanguage() {
        runBlocking(main) {
            playback.stopAtChapterEnd(true)
            playback.start(chapterStart, target = {
                playback.pause()
                it.utterance == TAIL.last()
            })
            assertEquals(ReadAloudPlayback.Landing.Sentence, playback.replay(recut = true))
            assertTrue(played.isEmpty())
            playback.resume()
        }
        awaitChapterStop()
        assertEquals(listOf(TAIL.last()), played.toList())
        assertFalse(playback.navigator.value!!.playback.value.playWhenReady)
    }

    @Test
    fun pausingWhileTheFallbackOpensPreventsPlayback() = runBlocking(main) {
        val opening = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var opens = 0
        beforeOpen = {
            if (++opens == 2) {
                opening.complete(Unit)
                release.await()
            }
        }
        val start = async { playback.start(chapterStart, target = { false }, maxSteps = 1) }
        opening.await()
        playback.pause()
        release.complete(Unit)

        assertEquals(ReadAloudPlayback.Landing.ElementStart, start.await())
        assertFalse(playback.navigator.value!!.playback.value.playWhenReady)
        assertTrue(played.isEmpty())
    }

    @Test
    fun aSelectionStartsAtTheSentenceItIsIn() {
        val selection = chapterStart.copy(
            text = Locator.Text(
                before = "$FIRST $SECOND The third",
                highlight = "sentence is the one that",
            ),
        )
        val sentences = DefaultTextContentTokenizer(TextUnit.Sentence, Language("en"))
        val target = SelectionTarget.of(selection, sentences)!!

        assertEquals(ReadAloudPlayback.Landing.Sentence, runBlocking(main) { playback.start(selection, target::matches) })
        awaitPlayed(2)
        assertEquals(listOf(THIRD, FOURTH), played.take(2))
    }

    @Test
    fun aRepeatedSelectionIsFoundWhereItWasSelected() {
        val selection = chapterStart.copy(
            text = Locator.Text(before = "$FOURTH $REPEATED ", highlight = REPEATED),
        )
        val target = SelectionTarget.of(selection, DefaultTextContentTokenizer(TextUnit.Sentence, Language("en")))!!

        runBlocking(main) { playback.start(selection, target::matches) }
        awaitPlayed(2)
        assertEquals(listOf(REPEATED, LAST), played.take(2))
    }

    @Test
    fun aCloseMatchLaterWinsOverALooseOneEarlier() {
        val landing = runBlocking(main) {
            playback.start(chapterStart, target = { it.utterance == FOURTH }, looser = { it.utterance == SECOND })
        }
        assertEquals(ReadAloudPlayback.Landing.Sentence, landing)
        awaitPlayed(1)
        assertEquals(FOURTH, played.first())
    }

    @Test
    fun aLooseMatchIsUsedWhenNoCloseOneIsFound() {
        val landing = runBlocking(main) {
            playback.start(chapterStart, target = { false }, maxSteps = 10, looser = { it.utterance == THIRD })
        }
        assertEquals(ReadAloudPlayback.Landing.Sentence, landing)
        awaitPlayed(1)
        assertEquals(THIRD, played.first())
    }

    @Test
    fun aSelectionThatIsNotFoundPlaysFromTheElementStart() {
        val selection = chapterStart.copy(text = Locator.Text(highlight = "Words nowhere in this book"))
        val target = SelectionTarget.of(selection, DefaultTextContentTokenizer(TextUnit.Sentence, Language("en")))!!

        assertEquals(
            ReadAloudPlayback.Landing.ElementStart,
            runBlocking(main) { playback.start(selection, target::matches) },
        )
        awaitPlayed(1)
        assertTrue(played.first() == FIRST)
    }

    private fun writeEpub(target: File) {
        ZipOutputStream(target.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.setMethod(ZipOutputStream.STORED)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()
            zip.setMethod(ZipOutputStream.DEFLATED)
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""",
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="en">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">urn:uuid:5b1e2a0c-1111-4222-8333-444455556666</dc:identifier>
                    <dc:title>Read Aloud</dc:title>
                    <dc:language>en</dc:language>
                    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="c1" href="one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="two.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>""",
            )
            put(
                "OEBPS/one.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>One</title></head>
                  <body>
                    <p>$FIRST $SECOND $THIRD $FOURTH $REPEATED $REPEATED $LAST ${TAIL.joinToString(" ")}</p>
                  </body>
                </html>""",
            )
            put(
                "OEBPS/two.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Two</title></head>
                  <body><p>$NEXT_CHAPTER</p></body>
                </html>""",
            )
        }
    }

    private companion object {
        const val TICK_MS = 5L
        const val FIRST = "The first sentence here is long enough to stand alone."
        const val SECOND = "A second sentence follows it closely, also long enough."
        const val THIRD = "The third sentence is the one that fails on the first try."
        const val FOURTH = "The fourth sentence comes after it and closes the opening."
        const val REPEATED = "The same line is repeated here, word for word, twice."
        const val LAST = "And these last words of the paragraph close it all."
        const val NEXT_CHAPTER = "The next chapter starts with a sentence that should wait for play."
        val TAIL = (1..30).map { "Sentence number $it carries this long paragraph further along." }
    }
}
