package com.ps.stashit.feature.capture

import app.cash.turbine.test
import com.ps.stashit.core.database.Platform
import com.ps.stashit.testutil.FakeSavedItemRepository
import com.ps.stashit.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeSavedItemRepository
    private lateinit var viewModel: CaptureViewModel

    @Before
    fun setUp() {
        repository = FakeSavedItemRepository()
        viewModel = CaptureViewModel(repository)
    }

    @Test
    fun `initial status is Processing`() {
        assertEquals(CaptureStatus.Processing, viewModel.state.value.status)
    }

    @Test
    fun `valid share saves the parsed item`() = runTest {
        viewModel.onEvent(CaptureEvent.ShareReceived("Pasta night https://www.tiktok.com/@chef/video/1"))
        runCurrent()

        assertEquals(
            listOf(Triple("https://www.tiktok.com/@chef/video/1", Platform.TIKTOK, "Pasta night")),
            repository.savedCalls
        )
        assertEquals(CaptureStatus.Saved, viewModel.state.value.status)
    }

    @Test
    fun `valid share dismisses after 800ms`() = runTest {
        viewModel.effect.test {
            viewModel.onEvent(CaptureEvent.ShareReceived("https://youtu.be/abc"))
            runCurrent()

            advanceTimeBy(799)
            expectNoEvents()

            advanceTimeBy(2)
            assertEquals(CaptureEffect.Dismiss, awaitItem())
        }
    }

    @Test
    fun `share without a url fails without saving`() = runTest {
        viewModel.onEvent(CaptureEvent.ShareReceived("no link here"))
        runCurrent()

        assertEquals(CaptureStatus.Failed, viewModel.state.value.status)
        assertTrue(repository.savedCalls.isEmpty())
    }

    @Test
    fun `failed share dismisses after 1200ms`() = runTest {
        viewModel.effect.test {
            viewModel.onEvent(CaptureEvent.ShareReceived("no link here"))
            runCurrent()

            advanceTimeBy(1_199)
            expectNoEvents()

            advanceTimeBy(2)
            assertEquals(CaptureEffect.Dismiss, awaitItem())
        }
    }
}
