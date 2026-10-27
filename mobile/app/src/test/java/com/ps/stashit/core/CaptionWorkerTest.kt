package com.ps.stashit.core

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.ps.stashit.core.captions.CaptionApi
import com.ps.stashit.core.captions.CaptionFetcher
import com.ps.stashit.core.captions.CaptionRequest
import com.ps.stashit.core.captions.CaptionResponse
import com.ps.stashit.core.database.Platform
import com.ps.stashit.core.database.TranscriptStatus
import com.ps.stashit.testutil.FakeSavedItemRepository
import com.ps.stashit.testutil.savedItem
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CaptionWorkerTest {
    private lateinit var context: Context
    private lateinit var repository: FakeSavedItemRepository
    private lateinit var embeddingScheduler: EmbeddingScheduler

    /** Per-url behaviour: return the transcript, or throw the exception. */
    private val responses = mutableMapOf<String, () -> String?>()
    private val requests = mutableListOf<CaptionRequest>()

    private val captionApi = object : CaptionApi {
        override suspend fun getCaptions(request: CaptionRequest): CaptionResponse {
            requests += request
            val fullText = responses.getValue(request.url)()
            return CaptionResponse("youtube", "id", "youtube_captions", 30.0, "en", true, fullText, emptyList())
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = FakeSavedItemRepository()
        embeddingScheduler = mockk(relaxed = true)
    }

    private fun buildWorker(): CaptionWorker =
        TestListenableWorkerBuilder<CaptionWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker =
                    CaptionWorker(appContext, workerParameters, repository, CaptionFetcher(captionApi), embeddingScheduler)
            })
            .build()

    private fun givenPending(id: Long, url: String, platform: Platform = Platform.YOUTUBE_SHORTS) {
        repository.items.value += savedItem(id = id, url = url, platform = platform, isEmbedded = true)
        repository.transcriptStatuses[id] = TranscriptStatus.PENDING
    }

    private fun httpError(code: Int): Nothing =
        throw HttpException(Response.error<Any>(code, "{}".toResponseBody()))

    private fun Result.attachedCount(): Int =
        outputData.getInt(CaptionWorker.KEY_ATTACHED_COUNT, -1)

    @Test
    fun `no pending items succeeds without triggering embedding`() = runTest {
        val result = buildWorker().doWork()

        assertTrue(result is Result.Success)
        assertEquals(0, result.attachedCount())
        verify(exactly = 0) { embeddingScheduler.triggerImmediate() }
    }

    @Test
    fun `transcript is stored, embedding cleared, and re-embedding triggered`() = runTest {
        givenPending(1, "https://youtube.com/shorts/a")
        responses["https://youtube.com/shorts/a"] = { "  boil the water  " }

        val result = buildWorker().doWork()

        assertTrue(result is Result.Success)
        assertEquals(1, result.attachedCount())
        val item = repository.items.value.single()
        assertEquals("boil the water", item.transcript)
        assertTrue(!item.isEmbedded)
        assertEquals(TranscriptStatus.DONE, repository.transcriptStatuses[1])
        verify(exactly = 1) { embeddingScheduler.triggerImmediate() }
    }

    @Test
    fun `request asks for English with audio fallback`() = runTest {
        givenPending(1, "https://www.tiktok.com/@a/video/1", Platform.TIKTOK)
        responses["https://www.tiktok.com/@a/video/1"] = { "hello" }

        buildWorker().doWork()

        assertEquals(listOf(CaptionRequest("https://www.tiktok.com/@a/video/1", listOf("en"), true)), requests)
    }

    @Test
    fun `404 and 422 mark the item unavailable without retrying`() = runTest {
        givenPending(1, "https://youtube.com/shorts/a")
        givenPending(2, "https://youtube.com/watch?v=b")
        responses["https://youtube.com/shorts/a"] = { httpError(404) }
        responses["https://youtube.com/watch?v=b"] = { httpError(422) }

        val result = buildWorker().doWork()

        assertTrue(result is Result.Success)
        assertEquals(TranscriptStatus.UNAVAILABLE, repository.transcriptStatuses[1])
        assertEquals(TranscriptStatus.UNAVAILABLE, repository.transcriptStatuses[2])
        verify(exactly = 0) { embeddingScheduler.triggerImmediate() }
    }

    @Test
    fun `blank full_text marks the item unavailable`() = runTest {
        givenPending(1, "https://youtube.com/shorts/a")
        responses["https://youtube.com/shorts/a"] = { "   " }

        buildWorker().doWork()

        assertEquals(TranscriptStatus.UNAVAILABLE, repository.transcriptStatuses[1])
    }

    @Test
    fun `server and network errors leave the item pending and retry`() = runTest {
        givenPending(1, "https://youtube.com/shorts/a")
        givenPending(2, "https://youtube.com/shorts/b")
        givenPending(3, "https://youtube.com/shorts/c")
        responses["https://youtube.com/shorts/a"] = { httpError(502) }
        responses["https://youtube.com/shorts/b"] = { throw IOException("timeout") }
        responses["https://youtube.com/shorts/c"] = { "hello" }

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        assertEquals(TranscriptStatus.PENDING, repository.transcriptStatuses[1])
        assertEquals(TranscriptStatus.PENDING, repository.transcriptStatuses[2])
        // A transient failure must not block the rest of the batch.
        assertEquals(TranscriptStatus.DONE, repository.transcriptStatuses[3])
        verify(exactly = 1) { embeddingScheduler.triggerImmediate() }
    }

    @Test
    fun `items not pending a transcript are never requested`() = runTest {
        repository.items.value = listOf(savedItem(id = 1, platform = Platform.INSTAGRAM_REELS))
        repository.transcriptStatuses[1] = TranscriptStatus.SKIPPED

        buildWorker().doWork()

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `processes more than one batch`() = runTest {
        val count = CaptionWorker.BATCH_SIZE * 2 + 5
        (1L..count).forEach {
            givenPending(it, "https://youtube.com/shorts/$it")
            responses["https://youtube.com/shorts/$it"] = { "text $it" }
        }

        val result = buildWorker().doWork()

        assertEquals(count, result.attachedCount())
    }
}
