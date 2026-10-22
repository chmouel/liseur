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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.navigator.media.tts.TtsEngine
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

    /** Holds back the audio for a sentence until released. */
    private val holds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private lateinit var publication: Publication
    private lateinit var playback: ReadAloudPlayback
    private var beforeOpen: suspend () -> Unit = {}
    private var tokenizerFactory: (Language?) -> TextTokenizer = BoundedSentenceTokenizer.factory()

    @Before
    fun setUp() = runBlocking {
        clock.start()
        val epub = File(folder.root, "book.epub").also(::writeEpub)
        publication = openPublication(epub)

        cache = SpeechCache(scope, synthesize)
        playback = newPlayback()
    }

    private suspend fun openPublication(epub: File): Publication {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val http = DefaultHttpClient()
        val assets = AssetRetriever(context.contentResolver, http)
        val asset = assets.retrieve(epub.toUrl(isDirectory = false)).getOrNull()!!
        return PublicationOpener(DefaultPublicationParser(context, http, assets, pdfFactory = null))
            .open(asset, allowUserInteraction = false).getOrNull()!!
    }

    private val synthesize: suspend (String) -> SpeechAudio = { text ->
        val nth = requested.getOrPut(text) { AtomicInteger() }.incrementAndGet()
        // A late answer, as from a slow network.
        delay(30)
        holds[text]?.await()
        if (failures[text] == nth) {
            failures.remove(text)
            throw SpeechError.Service(400)
        }
        SpeechAudio(text.toByteArray())
    }

    /** What each translated request was filed under, in the order asked. */
    private val translatedKeys: MutableList<SpeechKey> = Collections.synchronizedList(mutableListOf())

    /** Holds back a sentence's translation until released. */
    private val translationHolds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Fails the nth translation of a sentence, once. */
    private val translationFailures = ConcurrentHashMap<String, Int>()
    private val translationsAsked = ConcurrentHashMap<String, AtomicInteger>()
    private val translationHoldsByRequest = ConcurrentHashMap<Pair<String, Int>, CompletableDeferred<Unit>>()

    /** The text each translation was asked with as the one before it, in the order asked. */
    private val translatedAfter: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())

    /** A sentence's translation, which says where it was, so two places are told apart. */
    private fun translationOf(key: SpeechKey) = "T[${key.text}]#${key.place?.before.orEmpty().length}"

    /** Reads the book translated: each request translated by a fake, where it is, before its audio. */
    private fun readTranslated(prefetch: Boolean = false, repeatedCount: Int? = null) {
        runBlocking(main) { playback.close() }
        if (repeatedCount != null) {
            publication.close()
            val epub = File(folder.root, "repeated-book.epub").also { writeEpub(it, repeatedCount) }
            publication = runBlocking { openPublication(epub) }
        }
        cache = SpeechCache(scope, synthesize, prepare = { key, previous ->
            translatedKeys += key
            translatedAfter += key.text to previous
            val nth = translationsAsked.getOrPut(key.text) { AtomicInteger() }.incrementAndGet()
            translationHolds[key.text]?.await()
            translationHoldsByRequest[key.text to nth]?.await()
            if (translationFailures[key.text] == nth) {
                translationFailures.remove(key.text)
                throw com.chmouel.liseur.translate.TranslationError.Refused()
            }
            listOf(translationOf(key))
        })
        val prefetcher = if (prefetch) {
            UtterancePrefetcher<Locator>(scope, cache, { locator ->
                PublicationUtteranceCursor(publication, locator, tokenizerFactory, Language("en"), false)
            }, keyOf = { u -> u.locator?.let { SpeechKey.at(u.text, it) } ?: SpeechKey(u.text) })
        } else {
            null
        }
        playback = newPlayback(prefetcher, anchored = true)
    }

    private lateinit var cache: SpeechCache

    private val output = object : PcmOutput {
        override suspend fun play(pcm: ByteArray, fromFrame: Int) {
            delay(5)
            played += String(pcm)
        }

        override fun halt(): Int? = null
        override fun release() {}
    }

    private fun newPlayback(
        prefetcher: UtterancePrefetcher<Locator>? = null,
        anchored: Boolean = false,
        contextIdentity: suspend () -> String? = { null },
    ): ReadAloudPlayback {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return runBlocking(main) {
            ReadAloudPlayback(
                scope = scope,
                prefetcher = prefetcher,
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
                anchored = anchored,
                contextIdentity = contextIdentity,
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

    private fun awaitWaiting(expected: Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (playback.waiting.value != expected) {
            check(System.currentTimeMillis() < deadline) { "waiting never became $expected after $played" }
            Thread.sleep(10)
        }
    }

    private fun awaitTranslations(text: String, count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while ((translationsAsked[text]?.get() ?: 0) < count) {
            check(System.currentTimeMillis() < deadline) { "only asked ${translationsAsked[text]?.get() ?: 0} times for $text" }
            Thread.sleep(10)
        }
    }

    @Test
    fun aSentenceWhoseAudioIsLateIsWaitingUntilPaused() {
        holds[SECOND] = CompletableDeferred()
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(1)
        awaitWaiting(true)
        runBlocking(main) { playback.pause() }
        awaitWaiting(false)
        assertEquals(listOf(FIRST), played.toList())
    }

    @Test
    fun closingClearsTheWait() {
        holds[FIRST] = CompletableDeferred()
        runBlocking(main) { playback.start(chapterStart) }
        awaitWaiting(true)
        runBlocking(main) { playback.close() }
        assertFalse(playback.waiting.value)
    }

    @Test
    fun aRequestThisNavigatorDidNotSpeakIsNotWaitedOn() {
        runBlocking(main) { playback.onWaiting(TtsEngine.RequestId("elsewhere"), true) }
        assertFalse(playback.waiting.value)
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
    fun pausingWhileASentenceLoadsAndPlayingOnFetchesNothingTwice() {
        runBlocking(main) { playback.close() }
        playback = newPlayback(
            UtterancePrefetcher(scope, cache, { locator ->
                PublicationUtteranceCursor(publication, locator, tokenizerFactory, Language("en"), false)
            }),
        )
        holds[SECOND] = CompletableDeferred()
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(1)
        awaitWaiting(true)
        // Read ahead past the sentence waited on.
        val deadline = System.currentTimeMillis() + 10_000
        while (requested[FOURTH] == null) {
            check(System.currentTimeMillis() < deadline) { "never read ahead: $requested" }
            Thread.sleep(10)
        }
        runBlocking(main) { playback.pause() }
        awaitWaiting(false)
        holds.remove(SECOND)!!.complete(Unit)

        runBlocking(main) { playback.resume() }
        awaitPlayed(4)
        assertEquals(listOf(FIRST, SECOND, THIRD, FOURTH), played.take(4))
        listOf(FIRST, SECOND, THIRD, FOURTH).forEach { assertEquals(it, 1, requested[it]?.get()) }
    }

    @Test
    fun aPauseAfterASkipDoesNotForgetIt() {
        runBlocking(main) {
            val before = playback.skips
            playback.pause()
            playback.skipToNext()
            // The sleep timer pausing again while already paused.
            playback.pause()
            assertEquals(before + 1, playback.skips)
        }
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
    fun replayingChangedDirectionWhilePausedStaysPausedOnTheSameSentence() {
        runBlocking(main) {
            playback.start(chapterStart, target = {
                if (it.utterance == FIRST) playback.pause()
                it.utterance == THIRD
            })
            val before = playback.navigator.value
            cache.swap(synthesize, "Kore\u0000quiet delivery")

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


    @Test
    fun aTranslatedBookSpeaksEachSentenceTranslatedWhereItIs() {
        readTranslated()
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(7)
        val keys = translatedKeys.take(7)
        // Every request named its place in the book before its translation was asked.
        assertTrue(keys.all { it.place != null && it.place!!.href.endsWith("one.xhtml") })
        assertEquals(listOf(FIRST, SECOND, THIRD, FOURTH, REPEATED, REPEATED, LAST), keys.map { it.text })
        // The same words twice, told apart by where they are, and spoken as translated there.
        assertTrue(keys[4].place != keys[5].place)
        assertEquals(keys.map(::translationOf), played.take(7))
    }

    @Test
    fun anAdvancingRepeatedSentenceWaitsForItsOwnLocationButResumeCanReuseOne() {
        val first = chapterStart.copy(
            locations = chapterStart.locations.copy(position = 1),
            text = Locator.Text(before = "First occurrence", highlight = REPEATED),
        )
        val second = chapterStart.copy(
            locations = chapterStart.locations.copy(position = 2),
            text = Locator.Text(before = "Second occurrence", highlight = REPEATED),
        )

        assertFalse(matchesSpeechRequestLocation(REPEATED, first, REPEATED, first, allowSamePlace = false))
        assertTrue(matchesSpeechRequestLocation(REPEATED, second, REPEATED, first, allowSamePlace = false))
        assertTrue(matchesSpeechRequestLocation(REPEATED, first, REPEATED, first, allowSamePlace = true))
        // Skipping back from an occurrence waits for any other place, even one heard before.
        assertFalse(
            matchesSpeechRequestLocation(REPEATED, first, REPEATED, null, allowSamePlace = true, skipFromPlace = first),
        )
        assertTrue(
            matchesSpeechRequestLocation(REPEATED, second, REPEATED, second, allowSamePlace = false, skipFromPlace = first),
        )
    }

    @Test
    fun repeatedSentencesWithTheSameSurroundingsPlayThrough() {
        // Longer than Readium's context, so every repeat after the first has the same words before it.
        readTranslated(repeatedCount = 4)
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(9)
        assertTrue("playback failure: ${playback.failure.value}", playback.failure.value == null)

        val repeats = synchronized(translatedKeys) { translatedKeys.filter { it.text == REPEATED } }
        assertEquals(4, repeats.size)
        assertTrue(repeats[0] != repeats[1])
        assertEquals(1, repeats.drop(1).distinct().size)
        assertEquals(4, played.count { it.startsWith("T[$REPEATED]") })
        assertTrue(played[8].startsWith("T[$LAST]"))
    }

    @Test
    fun aLateTranslationHoldsTheVoiceOnItsSentenceThenSpeaksItOnce() {
        readTranslated()
        translationHolds[SECOND] = CompletableDeferred()
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(1)
        awaitWaiting(true)
        Thread.sleep(300)
        // Nothing said, nothing asked of the voice, and the place heard is still the first sentence.
        assertEquals(1, played.size)
        assertTrue(requested.keys.none { it.startsWith("T[$SECOND]") })
        assertEquals(SECOND, playback.navigator.value!!.location.value.utterance)
        assertEquals(FIRST, playback.heard.value!!.text.highlight)

        translationHolds.remove(SECOND)!!.complete(Unit)
        awaitPlayed(3)
        assertEquals(listOf(FIRST, SECOND, THIRD), translatedKeys.take(3).map { it.text })
        assertEquals(1, played.count { it.startsWith("T[$SECOND]") })
        assertEquals(1, translationsAsked[SECOND]!!.get())
    }

    @Test
    fun pausingWhileATranslationIsLateAndPlayingOnAsksItOnce() {
        readTranslated()
        translationHolds[SECOND] = CompletableDeferred()
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(1)
        awaitWaiting(true)
        runBlocking(main) { playback.pause() }
        awaitWaiting(false)
        // Arriving while paused, it is not played.
        translationHolds.remove(SECOND)!!.complete(Unit)
        Thread.sleep(200)
        assertEquals(1, played.size)

        runBlocking(main) { playback.resume() }
        awaitPlayed(2)
        assertEquals(translationOf(translatedKeys[1]), played[1])
        assertEquals(1, translationsAsked[SECOND]!!.get())
    }

    @Test
    fun aFailedTranslationPausesOnItsSentenceAndRetryingSpeaksIt() {
        readTranslated()
        translationFailures[THIRD] = 1
        runBlocking(main) { playback.start(chapterStart) }
        awaitFailure()
        val failure = playback.failure.value!!
        assertTrue(failure.error is SpeechTtsEngine.Error.Translation)
        assertEquals(THIRD, failure.anchor!!.text)
        // Nothing in the book's own words took its place.
        assertTrue(played.none { it == THIRD })
        assertEquals(2, played.size)
        movePastTheFailure()

        assertEquals(ReadAloudPlayback.Landing.Sentence, runBlocking(main) { playback.resume() })
        awaitPlayed(4)
        assertTrue(played[2].startsWith("T[$THIRD]"))
        assertTrue(played[3].startsWith("T[$FOURTH]"))
    }

    @Test
    fun aFailedTranslationIsPassedOnlyBySkipping() {
        readTranslated()
        translationFailures[THIRD] = 1
        runBlocking(main) { playback.start(chapterStart) }
        awaitFailure()
        // Left alone, the voice stays on the failed sentence.
        Thread.sleep(300)
        assertEquals(2, played.size)
        runBlocking(main) {
            playback.skipToNext()
            playback.resume()
        }
        awaitPlayed(3)
        assertTrue(played[2].startsWith("T[$FOURTH]"))
    }

    @Test
    fun theReadAheadFilesTranslationsWhereTheVoiceClaimsThem() {
        readTranslated(prefetch = true)
        translationHolds[SECOND] = CompletableDeferred()
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(1)
        // The voice waits on the second sentence while the read-ahead translates the fourth.
        val deadline = System.currentTimeMillis() + 10_000
        while (translatedKeys.none { it.text == FOURTH }) {
            check(System.currentTimeMillis() < deadline) { "never read ahead: $translatedKeys" }
            Thread.sleep(10)
        }
        assertEquals(1, played.size)
        val readAhead = translatedKeys.first { it.text == FOURTH }

        translationHolds.remove(SECOND)!!.complete(Unit)
        awaitPlayed(7)
        // Each sentence translated once, the repeated one once for each place.
        assertEquals(1, translationsAsked[FOURTH]!!.get())
        assertEquals(2, translationsAsked[REPEATED]!!.get())
        // The voice spoke the read-ahead's translation, so it claimed the place it was filed under.
        assertTrue(translationOf(readAhead) in played)
        assertEquals(1, requested.getValue(translationOf(readAhead)).get())
    }

    @Test
    fun aSentenceTheVoiceAsksForItselfIsTranslatedAfterTheOneBeforeIt() {
        readTranslated()
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(4)
        // Nothing comes before the first; each later one follows the one before it.
        assertEquals(
            listOf(FIRST to null, SECOND to FIRST, THIRD to SECOND, FOURTH to THIRD),
            translatedAfter.take(4),
        )
    }

    @Test
    fun aSentenceSkippedBackToIsNotTranslatedAfterTheOneSkippedFrom() {
        readTranslated()
        translationHolds[THIRD] = CompletableDeferred()
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(2)
        awaitWaiting(true)
        runBlocking(main) { playback.skipToPrevious() }
        translationHolds.remove(THIRD)!!.complete(Unit)
        awaitPlayed(3)
        assertEquals(SECOND to null, translatedAfter.last { it.first == SECOND })
    }

    @Test
    fun skippingBackTwiceUsesTheLiveReadiumPosition() {
        readTranslated()
        translationHoldsByRequest[REPEATED to 2] = CompletableDeferred()
        translationHoldsByRequest[REPEATED to 3] = CompletableDeferred()
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(5)
        awaitTranslations(REPEATED, 2)

        runBlocking(main) { playback.skipToPrevious() }
        awaitTranslations(REPEATED, 3)
        runBlocking(main) { playback.skipToPrevious() }
        awaitPlayed(6)

        val repeats = synchronized(translatedKeys) { translatedKeys.filter { it.text == REPEATED } }
        // The first skip landed on the earlier occurrence, not the one it left.
        assertEquals(repeats[0], repeats[2])
        // The second skip went back from there, to the sentence before it.
        assertEquals(translationOf(translatedKeys.last { it.text == FOURTH }), played[5])
        assertEquals(FOURTH to null, translatedAfter.last { it.first == FOURTH })
    }

    @Test
    fun skippingBackWhilePausedUsesTheLiveReadiumPosition() {
        readTranslated()
        val lateTranslation = CompletableDeferred<Unit>()
        translationHolds[FOURTH] = lateTranslation
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(3)
        awaitWaiting(true)

        runBlocking(main) { playback.pause() }
        awaitWaiting(false)
        runBlocking(main) {
            playback.skipToPrevious()
            playback.resume()
        }
        lateTranslation.complete(Unit)
        awaitPlayed(4)

        assertEquals(THIRD, translatedKeys.last().text)
    }

    @Test
    fun skippingForwardFromARepeatedSentenceLandsOnTheNextOccurrence() {
        readTranslated(repeatedCount = 3)
        translationHoldsByRequest[REPEATED to 1] = CompletableDeferred()
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(4)
        awaitTranslations(REPEATED, 1)

        runBlocking(main) { playback.skipToNext() }
        awaitPlayed(5)

        val repeats = synchronized(translatedKeys) { translatedKeys.filter { it.text == REPEATED } }
        assertNotEquals(repeats[0], repeats[1])
        assertEquals(translationOf(repeats[1]), played[4])
    }

    @Test
    fun skippingBackFromAnIdenticalSentenceWaitsForItsEarlierPlace() {
        readTranslated()
        val firstTranslation = CompletableDeferred<Unit>()
        translationHolds[REPEATED] = firstTranslation
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(4)
        awaitWaiting(true)

        val secondTranslation = CompletableDeferred<Unit>()
        translationHolds[REPEATED] = secondTranslation
        firstTranslation.complete(Unit)
        awaitPlayed(5)
        awaitWaiting(true)

        runBlocking(main) { playback.skipToPrevious() }
        secondTranslation.complete(Unit)
        awaitPlayed(6)
        assertEquals(translationOf(translatedKeys[4]), played[5])
    }

    @Test
    fun closingWhileATranslationIsLateSpeaksNothingMore() {
        readTranslated()
        translationHolds[SECOND] = CompletableDeferred()
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(1)
        awaitWaiting(true)
        runBlocking(main) { playback.close() }
        awaitWaiting(false)
        translationHolds.remove(SECOND)!!.complete(Unit)
        Thread.sleep(300)
        assertEquals(1, played.size)
        assertEquals(FIRST, playback.heard.value?.text?.highlight)
    }

    @Test
    fun untranslatedReadingWithActiveContextAnchorsRepeatedSentencesSeparately() {
        runBlocking(main) { playback.close() }
        val contextKeys = Collections.synchronizedList(mutableListOf<SpeechKey>())
        val instructionsSeen = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        cache = SpeechCache(
            scope = scope,
            synthesize = { text, instructions ->
                instructionsSeen += text to instructions
                synthesize(text)
            },
            context = { key, _ ->
                contextKeys += key
                "ctx:${key.place?.before}"
            },
        )
        val prefetcher = UtterancePrefetcher<Locator>(
            scope = scope,
            cache = cache,
            cursorAt = { locator ->
                PublicationUtteranceCursor(publication, locator, tokenizerFactory, Language("en"), false)
            },
            keyOf = { u -> u.locator?.let { SpeechKey.at(u.text, it, contextIdentity = "ctx-on") } ?: SpeechKey(u.text, contextIdentity = "ctx-on") },
        )
        playback = newPlayback(prefetcher = prefetcher, anchored = false, contextIdentity = { "ctx-on" })
        runBlocking(main) { playback.start(chapterStart) }
        awaitPlayed(6)

        val repeatKeys = synchronized(contextKeys) { contextKeys.filter { it.text == REPEATED } }
        assertEquals(2, repeatKeys.size)
        assertNotEquals(repeatKeys[0], repeatKeys[1])
        val repeatInstructions = synchronized(instructionsSeen) {
            instructionsSeen.filter { it.first == REPEATED }.map { it.second }
        }
        assertEquals(2, repeatInstructions.size)
        assertNotEquals(repeatInstructions[0], repeatInstructions[1])
    }

    private fun writeEpub(target: File, repeatedCount: Int = 2) {
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
                    <p>$FIRST $SECOND $THIRD $FOURTH ${List(repeatedCount) { REPEATED }.joinToString(" ")} $LAST ${TAIL.joinToString(" ")}</p>
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
