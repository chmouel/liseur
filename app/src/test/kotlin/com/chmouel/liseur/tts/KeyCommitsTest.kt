package com.chmouel.liseur.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyCommitsTest {

    @Test
    fun aRemovalSubmittedDuringASaveRunsAfterItAndWins() = runTest {
        val commits = KeyCommits(this)
        val saved = mutableMapOf<String, String>()
        val ran = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        commits.submit("a", {
            gate.await()
            saved["a"] = "key"
            ran += "save"
        })
        commits.submit("a", {
            saved.remove("a")
            ran += "remove"
        })
        advanceUntilIdle()
        assertEquals(emptyList<String>(), ran)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("save", "remove"), ran)
        assertEquals(emptyMap<String, String>(), saved)
    }

    @Test
    fun aFailureIsTheServersUntilItsNextSuccess() = runTest {
        val commits = KeyCommits(this)
        val results = mutableListOf<Boolean>()
        commits.submit("a", { error("disk full") }, { results += it })
        advanceUntilIdle()
        assertEquals("a", commits.failure.value)

        commits.submit("b", {}, { results += it })
        advanceUntilIdle()
        assertEquals("a", commits.failure.value)

        commits.submit("a", {}, { results += it })
        advanceUntilIdle()
        assertNull(commits.failure.value)
        assertEquals(listOf(false, true, true), results)
    }

    private class Saves {
        val calls = mutableListOf<Pair<String, String>>()
        val pending = mutableListOf<(Boolean) -> Unit>()
        val save: (String, String, (Boolean) -> Unit) -> Unit = { owner, key, done ->
            calls += owner to key
            pending += done
        }
    }

    @Test
    fun leavingTheFieldTwiceSavesTheKeyOnce() {
        val draft = KeyDraft()
        val saves = Saves()
        draft.edit("sk-1", "a")
        draft.commit(saves.save)
        draft.commit(saves.save)
        assertEquals(listOf("a" to "sk-1"), saves.calls)

        saves.pending.single()(true)
        assertEquals("", draft.text)
    }

    @Test
    fun textTypedWhileASaveRunsStaysInTheField() {
        val draft = KeyDraft()
        val saves = Saves()
        draft.edit("sk-1", "a")
        draft.commit(saves.save)
        draft.edit("sk-12", "a")
        saves.pending.single()(true)
        assertEquals("sk-12", draft.text)

        draft.commit(saves.save)
        assertEquals(listOf("a" to "sk-1", "a" to "sk-12"), saves.calls)
    }

    @Test
    fun aFailedSaveKeepsTheKeyToBeSavedAgain() {
        val draft = KeyDraft()
        val saves = Saves()
        draft.edit("sk-1", "a")
        draft.commit(saves.save)
        saves.pending.single()(false)
        assertEquals("sk-1", draft.text)

        draft.commit(saves.save)
        assertEquals(listOf("a" to "sk-1", "a" to "sk-1"), saves.calls)
    }

    @Test
    fun aKeyTypedForOneServerIsSavedThereWhenAnotherIsShown() {
        val draft = KeyDraft()
        val saves = Saves()
        draft.edit("s", "a")
        // The server shown changes as the rest is pasted.
        draft.edit("sk-1", "b")
        draft.follow("b", saves.save)
        assertEquals(listOf("a" to "sk-1"), saves.calls)
        assertEquals("", draft.text)

        draft.edit("sk-2", "b")
        draft.follow("b", saves.save)
        assertEquals(1, saves.calls.size)
    }

    @Test
    fun aRemovedKeyIsNeverSavedLater() {
        val draft = KeyDraft()
        val saves = Saves()
        draft.edit("sk-1", "a")
        draft.clear()
        draft.commit(saves.save)
        draft.follow("b", saves.save)
        assertEquals(emptyList<Pair<String, String>>(), saves.calls)
    }
}
