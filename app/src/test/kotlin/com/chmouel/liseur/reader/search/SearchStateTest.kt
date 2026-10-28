package com.chmouel.liseur.reader.search

import com.chmouel.liseur.reader.ReaderViewModel.SearchState
import org.junit.Assert.assertSame
import org.junit.Test

class SearchStateTest {
    private val states = listOf(
        SearchState.Running("whale", emptyList()),
        SearchState.Done("whale", emptyList()),
        SearchState.Failure("unavailable", "whale"),
    )

    @Test
    fun `editing the query hides previous results progress and errors`() {
        states.forEach { state ->
            assertSame(SearchState.Idle, state.forQuery("ship"))
            assertSame(SearchState.Idle, state.forQuery(""))
        }
    }

    @Test
    fun `matching queries preserve search state after trimming`() {
        states.forEach { state ->
            assertSame(state, state.forQuery(" whale "))
        }
    }
}
