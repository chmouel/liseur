package com.chmouel.liseur.translate

import com.chmouel.liseur.data.settings.AppSettings
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Executor

class MlKitTranslationTest {

    private val codes = setOf("en", "fr", "de", "zh", "pt", "no", "tl")

    @Test
    fun `Chinese is Simplified only and Portuguese regions are sources only`() {
        assertEquals("zh", MlKitLanguages.code("zh-Hans", codes, asTarget = true))
        assertEquals("zh", MlKitLanguages.code("zh-CN", codes, asTarget = false))
        assertNull(MlKitLanguages.code("zh-Hant", codes, asTarget = false))
        assertNull(MlKitLanguages.code("zh-TW", codes, asTarget = true))
        assertEquals("pt", MlKitLanguages.code("pt-BR", codes, asTarget = false))
        assertNull(MlKitLanguages.code("pt-BR", codes, asTarget = true))
        assertNull(MlKitLanguages.code("pt", codes, asTarget = true))
        assertNull(MlKitLanguages.code("sr-Latn", codes, asTarget = false))
        assertEquals("no", MlKitLanguages.code("nb", codes, asTarget = true))
        assertEquals("tl", MlKitLanguages.code("fil", codes, asTarget = true))
        assertEquals("fr", MlKitLanguages.code("fr-CA", codes, asTarget = true))
    }

    @Test
    fun `Norwegian and Filipino are offered under the tags the system uses`() {
        val targets = MlKitLanguages.targets(codes, downloaded = emptySet(), source = "en").keys
        assertTrue("nb" in targets && "fil" in targets)
        assertTrue("no" !in targets && "tl" !in targets)
        assertEquals("nb", MlKitLanguages.tag("no"))
    }

    @Test
    fun `lists Liseur's tags, with Portuguese regions as sources`() {
        val sources = MlKitLanguages.sources(codes)
        assertTrue("zh-Hans" in sources && "pt-BR" in sources && "pt-PT" in sources)
        assertTrue("zh" !in sources)
    }

    @Test
    fun `a pair is ready once both models are on the phone`() {
        val targets = MlKitLanguages.targets(codes, downloaded = setOf("en", "fr"), source = "fr")
        assertEquals(PairState.Ready, targets["en"])
        assertEquals(PairState.NeedsDownload, targets["de"])
        assertNull(targets["fr"])
        assertEquals(emptyMap<String, PairState>(), MlKitLanguages.targets(codes, setOf("en"), "zh-Hant"))
        assertNull(MlKitLanguages.pair(codes, "pt-BR", "pt"))
        assertNull(MlKitLanguages.pair(codes, null, "fr"))
    }

    @Test
    fun `a missing model stops translation without downloading it`() = runBlocking {
        val engine = FakeEngine(downloaded = mutableSetOf("en"))
        val service = MlKitTranslationService(engine, onWifi = { true })
        expect<TranslationError.NotDownloaded> { service.translate("Bonjour", "fr", "en") }
        assertEquals(0, engine.downloads)
        assertEquals(0, engine.opened)
    }

    @Test
    fun `an unsupported pair says so`() = runBlocking {
        val service = MlKitTranslationService(FakeEngine(mutableSetOf("en", "pt")), onWifi = { true })
        expect<TranslationError.Unsupported> { service.translate("Hi", "en", "pt-BR") }
        expect<TranslationError.Unsupported> { service.translate("Hi", null, "pt") }
    }

    @Test
    fun `a run reuses one translator, stops once a model is deleted, and closes it`() = runBlocking {
        val engine = FakeEngine(mutableSetOf("en", "fr"))
        val service = MlKitTranslationService(engine, onWifi = { true }, background = CoroutineScope(Dispatchers.Unconfined))
        val run = service.open("fr", "en", AppSettings())
        assertEquals("[fr>en] Un", run.translate("Un", null))
        assertEquals("[fr>en] Deux", run.translate("Deux", null))
        assertEquals(1, engine.opened)
        service.delete("fr")
        expect<TranslationError.NotDownloaded> { run.translate("Trois", null) }
        assertEquals(1, engine.closed)
        service.download("fr")
        assertEquals("[fr>en] Quatre", run.translate("Quatre", null))
        assertEquals(2, engine.opened)
        run.close()
        assertEquals(2, engine.closed)
    }

    @Test
    fun `a failing translator is closed and not reused`() = runBlocking {
        val engine = FakeEngine(mutableSetOf("en", "fr"), failNext = true)
        val service = MlKitTranslationService(engine, onWifi = { true }, background = CoroutineScope(Dispatchers.Unconfined))
        val run = service.open("fr", "en", AppSettings())
        expect<TranslationError.Failed> { run.translate("Un", null) }
        assertEquals(1, engine.closed)
        assertEquals("[fr>en] Deux", run.translate("Deux", null))
        assertEquals(2, engine.opened)
    }

    @Test
    fun `a download makes the pair ready`() = runBlocking {
        val engine = FakeEngine(mutableSetOf("en"))
        val service = MlKitTranslationService(engine, onWifi = { true })
        assertEquals(PairState.NeedsDownload, service.targets("fr")["en"])
        service.download("fr", "en")
        assertEquals(PairState.Ready, service.targets("fr")["en"])
        assertEquals("[fr>en] Bonjour", service.translate("Bonjour", "fr", "en"))
        assertEquals(1, engine.closed)
    }

    @Test
    fun `Portuguese is never offered as a target`() {
        val targets = MlKitLanguages.targets(codes, downloaded = codes, source = "en")
        assertTrue(targets.keys.none { it.startsWith("pt") })
        assertTrue("fr" in targets)
    }

    @Test
    fun `a listing failure is reported when translating, not as an empty capability`() = runBlocking {
        val engine = FakeEngine(mutableSetOf("en", "fr"), failListing = true)
        val service = MlKitTranslationService(engine, onWifi = { true })
        service.refresh()
        assertEquals(PairState.Ready, service.targets("fr")["en"])
        expect<TranslationError.Failed> { service.translate("Bonjour", "fr", "en") }
        engine.failListing = false
        assertEquals("[fr>en] Bonjour", service.translate("Bonjour", "fr", "en"))
    }

    @Test
    fun `a listing failure after a change fails the change and forgets the old answer`() = runBlocking {
        val engine = FakeEngine(mutableSetOf("en", "fr"))
        val service = MlKitTranslationService(engine, onWifi = { true })
        assertEquals(PairState.Ready, service.targets("fr")["en"])
        engine.failListing = true
        expect<TranslationError.Failed> { service.delete("fr") }
        expect<TranslationError.Failed> { service.translate("Bonjour", "fr", "en") }
        engine.failListing = false
        expect<TranslationError.NotDownloaded> { service.translate("Bonjour", "fr", "en") }
        engine.failListing = true
        expect<TranslationError.Failed> { service.download("fr", "en") }
        engine.failListing = false
        assertEquals("[fr>en] Bonjour", service.translate("Bonjour", "fr", "en"))
    }

    @Test
    fun `a download outlives a caller that stops waiting`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(mutableSetOf("en"), downloadGate = gate)
        val service = MlKitTranslationService(engine, onWifi = { true }, background = CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val waiter = launch(Dispatchers.Default) { service.download("fr", "en") }
        while (engine.downloads == 0) yield()
        waiter.cancelAndJoin()
        gate.complete(Unit)
        withTimeout(5_000) { while (service.targets("fr")["en"] != PairState.Ready) delay(10) }
        // The deletion waits for the download rather than running under it.
        service.delete("fr")
        assertEquals(PairState.NeedsDownload, service.targets("fr")["en"])
    }

    @Test
    fun `a task ML Kit cancels fails as an ML Kit error, not as the caller's cancellation`() = runBlocking {
        val direct = Executor { it.run() }
        try {
            Tasks.forCanceled<Unit>().await(direct)
            fail("expected MlKitException")
        } catch (e: CancellationException) {
            fail("got a cancellation")
        } catch (e: MlKitException) {
            assertEquals(MlKitException.CANCELLED, e.errorCode)
        }
    }

    @Test
    fun `a caller that stops waiting on an ML Kit task is still cancelled`() = runBlocking {
        val direct = Executor { it.run() }
        val pending = TaskCompletionSource<Unit>()
        // Undispatched, so it is already waiting on the task when it is cancelled.
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { pending.task.await(direct) }
        waiter.cancelAndJoin()
        assertTrue(waiter.isCancelled)
        assertFalse(pending.task.isComplete)
    }

    @Test
    fun `ML Kit failing to start on the phone is an ML Kit error, and cancelling still cancels`() {
        for (thrown in listOf(IllegalStateException("not started"), UnsatisfiedLinkError("no libtranslate_jni"))) {
            try {
                working { throw thrown }
                fail("expected MlKitException")
            } catch (e: MlKitException) {
                assertTrue(e.cause === thrown)
            }
        }
        try {
            working { throw CancellationException("stop") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    private suspend inline fun <reified E : TranslationError> expect(crossinline block: suspend () -> Unit) {
        try {
            block()
            fail("expected ${E::class.simpleName}")
        } catch (e: TranslationError) {
            assertTrue("got ${e::class.simpleName}", e is E)
        }
    }

    @Test
    fun `the languages page splits what is on the phone from the rest, finds names without accents, and marks Portuguese`() {
        val names = mapOf("en" to "English", "fr" to "Français", "de" to "Deutsch", "zh" to "Chinese (Simplified)", "pt" to "Portuguese", "no" to "Norwegian", "tl" to "Filipino")
        val all = MlKitLanguages.entries(codes, setOf("en", "fr"), names::getValue, "", naturalOrder())
        assertEquals(listOf("Chinese (Simplified)", "Deutsch", "English", "Filipino", "Français", "Norwegian", "Portuguese"), all.map { it.name })
        assertEquals(setOf("en", "fr"), all.filter { it.onPhone }.map { it.code }.toSet())
        assertEquals(listOf("pt"), all.filter { it.sourceOnly }.map { it.code })
        assertEquals(listOf("fr"), MlKitLanguages.entries(codes, emptySet(), names::getValue, " fran ", naturalOrder()).map { it.code })
        assertEquals(listOf("de"), MlKitLanguages.entries(codes, emptySet(), names::getValue, "DE", naturalOrder()).map { it.code })
    }

    @Test
    fun `a language fetched from the page lands on the phone and leaves no mark`() = runBlocking {
        val engine = FakeEngine(mutableSetOf("en"))
        val service = MlKitTranslationService(engine, onWifi = { true }, background = CoroutineScope(SupervisorJob() + Dispatchers.Default))
        service.fetch("de")
        withTimeout(5_000) { while (service.models.value?.contains("de") != true || service.changes.value.isNotEmpty()) delay(10) }
        assertEquals(setOf("en", "de"), engine.downloaded)
    }

    @Test
    fun `without Wi-Fi the page asks for it and nothing downloads`() = runBlocking {
        val engine = FakeEngine(mutableSetOf("en"))
        val service = MlKitTranslationService(engine, onWifi = { false })
        service.fetch("de")
        assertEquals(Change.Failed(Change.Why.Wifi), service.changes.value["de"])
        assertEquals(0, engine.downloads)
    }

    @Test
    fun `a failed download stays on its row until it is tried again`() = runBlocking {
        val engine = FakeEngine(mutableSetOf("en"))
        engine.failDownload = MlKitDownloadFailed.Reason.Space
        val service = MlKitTranslationService(engine, onWifi = { true })
        expectAny { service.download("de") }
        assertEquals(Change.Failed(Change.Why.Space), service.changes.value["de"])
        engine.failDownload = null
        service.download("de")
        assertNull(service.changes.value["de"])
    }

    @Test
    fun `a listing that shows a failed change done clears its failure`() = runBlocking {
        val engine = FakeEngine(mutableSetOf("en", "fr"))
        val service = MlKitTranslationService(engine, onWifi = { true })
        engine.failListing = true
        expect<TranslationError.Failed> { service.download("de") }
        expect<TranslationError.Failed> { service.delete("fr") }
        assertEquals(Change.Failed(Change.Why.Download), service.changes.value["de"])
        assertEquals(Change.Failed(Change.Why.Delete), service.changes.value["fr"])
        engine.failListing = false
        service.load()
        assertEquals(emptyMap<String, Change>(), service.changes.value)
    }

    @Test
    fun `a deletion asked during a download waits for it and shows as waiting`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(mutableSetOf("en", "fr"), downloadGate = gate)
        val service = MlKitTranslationService(engine, onWifi = { true }, background = CoroutineScope(SupervisorJob() + Dispatchers.Default))
        service.fetch("de")
        withTimeout(5_000) { while (service.changes.value["de"] != Change.Downloading) delay(10) }
        service.remove("fr")
        delay(50)
        assertEquals(Change.Waiting, service.changes.value["fr"])
        assertTrue("fr" in engine.downloaded)
        gate.complete(Unit)
        withTimeout(5_000) { while (service.changes.value.isNotEmpty()) delay(10) }
        assertEquals(setOf("en", "de"), engine.downloaded)
    }

    private suspend fun expectAny(block: suspend () -> Unit) {
        try {
            block()
            fail("expected a failure")
        } catch (e: MlKitDownloadFailed) {
            // Expected.
        }
    }

    private inner class FakeEngine(
        val downloaded: MutableSet<String>,
        var failNext: Boolean = false,
        var failListing: Boolean = false,
        val downloadGate: CompletableDeferred<Unit>? = null,
    ) : MlKitEngine {
        var downloads = 0
        var opened = 0
        var closed = 0

        override fun languages() = codes

        override suspend fun downloaded(): Set<String> {
            if (failListing) throw TranslationError.Failed("test")
            return downloaded.toSet()
        }

        var failDownload: MlKitDownloadFailed.Reason? = null

        override suspend fun download(code: String) {
            downloads++
            downloadGate?.await()
            failDownload?.let { throw MlKitDownloadFailed(it) }
            downloaded += code
        }

        override suspend fun delete(code: String) {
            downloaded -= code
        }

        override fun open(source: String, target: String): MlKitTranslator {
            opened++
            return object : MlKitTranslator {
                override suspend fun translate(text: String): String {
                    if (failNext) {
                        failNext = false
                        throw TranslationError.Failed("test")
                    }
                    return "[$source>$target] $text"
                }

                override fun close() {
                    closed++
                }
            }
        }
    }
}
