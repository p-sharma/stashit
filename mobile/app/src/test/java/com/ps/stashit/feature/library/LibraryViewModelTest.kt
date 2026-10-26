package com.ps.stashit.feature.library

import app.cash.turbine.test
import com.ps.stashit.testutil.FakeSavedItemRepository
import com.ps.stashit.testutil.MainDispatcherRule
import com.ps.stashit.testutil.savedItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeSavedItemRepository
    private lateinit var viewModel: LibraryViewModel

    private val first = savedItem(id = 1)
    private val second = savedItem(id = 2)

    @Before
    fun setUp() {
        repository = FakeSavedItemRepository()
        viewModel = LibraryViewModel(repository)
    }

    @Test
    fun `starts loading and not empty`() {
        assertTrue(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.isEmpty)
    }

    @Test
    fun `empty repository ends loading and reports empty`() = runTest {
        runCurrent()

        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.isEmpty)
    }

    @Test
    fun `items from the repository are reflected in state`() = runTest {
        repository.items.value = listOf(first, second)
        runCurrent()

        assertEquals(listOf(first, second), viewModel.state.value.items)
        assertFalse(viewModel.state.value.isEmpty)
    }

    @Test
    fun `later repository emissions update state`() = runTest {
        runCurrent()

        repository.items.value = listOf(first)
        runCurrent()

        assertEquals(listOf(first), viewModel.state.value.items)
    }

    @Test
    fun `delete removes the item and offers undo`() = runTest {
        repository.items.value = listOf(first, second)
        runCurrent()

        viewModel.effect.test {
            viewModel.onEvent(LibraryEvent.DeleteItem(first))
            runCurrent()

            assertEquals(LibraryEffect.ShowUndoDelete(first), awaitItem())
        }
        assertEquals(listOf(first), repository.deleted)
        assertEquals(listOf(second), viewModel.state.value.items)
    }

    @Test
    fun `opening an item emits OpenUrl`() = runTest {
        viewModel.effect.test {
            viewModel.onEvent(LibraryEvent.OpenItem(first))
            runCurrent()

            assertEquals(LibraryEffect.OpenUrl(first.url), awaitItem())
        }
    }

    @Test
    fun `refresh is a no-op`() = runTest {
        repository.items.value = listOf(first)
        runCurrent()

        viewModel.effect.test {
            viewModel.onEvent(LibraryEvent.Refresh)
            runCurrent()

            expectNoEvents()
        }
        assertEquals(listOf(first), viewModel.state.value.items)
    }
}
