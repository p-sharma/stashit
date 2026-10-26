package com.ps.stashit.feature.search

import app.cash.turbine.test
import com.ps.stashit.data.domain.SearchResult
import com.ps.stashit.testutil.FakeSavedItemRepository
import com.ps.stashit.testutil.MainDispatcherRule
import com.ps.stashit.testutil.savedItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeSavedItemRepository
    private lateinit var viewModel: SearchViewModel

    private val pastaResult = SearchResult(savedItem(id = 1, caption = "pasta"), 0.9f)
    private val pizzaResult = SearchResult(savedItem(id = 2, caption = "pizza"), 0.8f)

    @Before
    fun setUp() {
        repository = FakeSavedItemRepository()
        repository.searchResults = { query ->
            when (query) {
                "pasta" -> listOf(pastaResult)
                "pizza" -> listOf(pizzaResult)
                else -> emptyList()
            }
        }
        viewModel = SearchViewModel(repository)
    }

    @Test
    fun `query in state updates immediately`() = runTest {
        viewModel.onEvent(SearchEvent.QueryChanged("pa"))

        assertEquals("pa", viewModel.state.value.query)
    }

    @Test
    fun `search waits for the 300ms debounce`() = runTest {
        viewModel.onEvent(SearchEvent.QueryChanged("pasta"))

        advanceTimeBy(299)
        assertTrue(repository.searchQueries.isEmpty())

        advanceTimeBy(2)
        assertEquals(listOf("pasta"), repository.searchQueries)
    }

    @Test
    fun `rapid typing triggers a single search for the final query`() = runTest {
        listOf("p", "pa", "pas", "past", "pasta").forEach {
            viewModel.onEvent(SearchEvent.QueryChanged(it))
            advanceTimeBy(100)
        }
        advanceUntilIdle()

        assertEquals(listOf("pasta"), repository.searchQueries)
    }

    @Test
    fun `results populate state after search completes`() = runTest {
        viewModel.onEvent(SearchEvent.QueryChanged("pasta"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(pastaResult), state.results)
        assertFalse(state.isSearching)
        assertTrue(state.hasSearched)
    }

    @Test
    fun `isSearching is true while the repository is working`() = runTest {
        repository.searchResults = { delay(1_000); listOf(pastaResult) }

        viewModel.onEvent(SearchEvent.QueryChanged("pasta"))
        advanceTimeBy(301)

        assertTrue(viewModel.state.value.isSearching)

        advanceUntilIdle()
        assertFalse(viewModel.state.value.isSearching)
    }

    @Test
    fun `a newer query cancels the in-flight search`() = runTest {
        repository.searchResults = { query ->
            delay(1_000)
            if (query == "pasta") listOf(pastaResult) else listOf(pizzaResult)
        }

        viewModel.onEvent(SearchEvent.QueryChanged("pasta"))
        advanceTimeBy(500)  // pasta search in flight
        viewModel.onEvent(SearchEvent.QueryChanged("pizza"))
        advanceUntilIdle()

        assertEquals(listOf("pasta", "pizza"), repository.searchQueries)
        assertEquals(listOf(pizzaResult), viewModel.state.value.results)
    }

    @Test
    fun `returning to the same query within the debounce window does not search again`() = runTest {
        viewModel.onEvent(SearchEvent.QueryChanged("pasta"))
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.QueryChanged("past"))
        advanceTimeBy(100)
        viewModel.onEvent(SearchEvent.QueryChanged("pasta"))
        advanceUntilIdle()

        assertEquals(listOf("pasta"), repository.searchQueries)
    }

    @Test
    fun `clearing the query clears results without searching`() = runTest {
        viewModel.onEvent(SearchEvent.QueryChanged("pasta"))
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.QueryChanged(""))
        advanceUntilIdle()

        assertEquals(listOf("pasta"), repository.searchQueries)
        assertTrue(viewModel.state.value.results.isEmpty())
        assertFalse(viewModel.state.value.isSearching)
    }

    @Test
    fun `opening a result emits OpenUrl`() = runTest {
        viewModel.effect.test {
            viewModel.onEvent(SearchEvent.OpenResult(pastaResult.item))
            runCurrent()

            assertEquals(SearchEffect.OpenUrl(pastaResult.item.url), awaitItem())
        }
    }

    /**
     * BUG: the query StateFlow starts at "", which passes through debounce on startup and
     * sets hasSearched = true. SearchScreen shows SearchPromptState only while
     * !hasSearched, so ~300ms after opening the tab the prompt is replaced by
     * NoResultsState("") before the user has typed anything.
     */
    @Test
    fun `opening the screen keeps the search prompt until the user types`() = runTest {
        advanceUntilIdle()

        assertFalse(viewModel.state.value.hasSearched)
        assertTrue(repository.searchQueries.isEmpty())
    }
}
