package com.chmouel.liseur.translate

import com.chmouel.liseur.data.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

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
        run.close()
        assertEquals(1, engine.closed)
    }

    @Test
    fun `a failing translator is closed and not reused`() = runBlocking {
        val engine = FakeEngine(mutableSetOf("en", "fr"), failNext = true)
        val service = MlKitTranslationService(engine, onWifi = { true }, background = CoroutineScope(Dispatchers.Unconfined))
        val run = service.open("fr", "en", AppSettings())
        expect<TranslationError.DeviceFailed> { run.translate("Un", null) }
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
        expect<TranslationError.DeviceFailed> { service.translate("Bonjour", "fr", "en") }
        engine.failListing = false
        assertEquals("[fr>en] Bonjour", service.translate("Bonjour", "fr", "en"))
    }

    @Test
    fun `a listing failure after a change fails the change and forgets the old answer`() = runBlocking {
        val engine = FakeEngine(mutableSetOf("en", "fr"))
        val service = MlKitTranslationService(engine, onWifi = { true })
        assertEquals(PairState.Ready, service.targets("fr")["en"])
        engine.failListing = true
        expect<TranslationError.DeviceFailed> { service.delete("fr") }
        expect<TranslationError.DeviceFailed> { service.translate("Bonjour", "fr", "en") }
        engine.failListing = false
        expect<TranslationError.NotDownloaded> { service.translate("Bonjour", "fr", "en") }
        engine.failListing = true
        expect<TranslationError.DeviceFailed> { service.download("fr", "en") }
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

    private suspend inline fun <reified E : TranslationError> expect(crossinline block: suspend () -> Unit) {
        try {
            block()
            fail("expected ${E::class.simpleName}")
        } catch (e: TranslationError) {
            assertTrue("got ${e::class.simpleName}", e is E)
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
            if (failListing) throw TranslationError.DeviceFailed("test")
            return downloaded.toSet()
        }

        override suspend fun download(source: String, target: String) {
            downloads++
            downloadGate?.await()
            downloaded += listOf(source, target)
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
                        throw TranslationError.DeviceFailed("test")
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
