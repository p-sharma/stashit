package com.ps.stashit.core

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.ps.stashit.core.database.Platform
import com.ps.stashit.core.metadata.MetaDataFetcherRouter
import com.ps.stashit.core.metadata.TiktokMetaDataFetcher
import com.ps.stashit.core.metadata.TiktokOEmbedApi
import com.ps.stashit.core.metadata.TiktokOEmbedResponse
import com.ps.stashit.core.metadata.YoutubeMetaDataFetcher
import com.ps.stashit.core.metadata.YoutubeOEmbedApi
import com.ps.stashit.core.metadata.YoutubeOEmbedResponse
import com.ps.stashit.core.ml.TextEmbedder
import com.ps.stashit.testutil.FakeSavedItemRepository
import com.ps.stashit.testutil.StubTextEmbedder
import com.ps.stashit.testutil.savedItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EmbeddingWorkerTest {
    private lateinit var context: Context
    private lateinit var repository: FakeSavedItemRepository
    private lateinit var embedder: StubTextEmbedder

    private val youtubeTitles = mutableMapOf<String, String>()
    private val youtubeRequests = mutableListOf<String>()
    private val tiktokRequests = mutableListOf<String>()

    private val youtubeApi = object : YoutubeOEmbedApi {
        override suspend fun getOEmbed(url: String, format: String): YoutubeOEmbedResponse {
            youtubeRequests += url
            val title = youtubeTitles[url] ?: throw IOException("oEmbed unavailable")
            return YoutubeOEmbedResponse(title, "https://thumb/$url", "author")
        }
    }

    private val tiktokApi = object : TiktokOEmbedApi {
        override suspend fun getOEmbed(url: String): TiktokOEmbedResponse {
            tiktokRequests += url
            return TiktokOEmbedResponse("TikTok title", null, null)
        }
    }

    private val router = MetaDataFetcherRouter(YoutubeMetaDataFetcher(youtubeApi), TiktokMetaDataFetcher(tiktokApi))
    private val unitVector = floatArrayOf(1f, 0f, 0f)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Bounded so a pending queue that never drains fails the test instead of spinning forever.
        repository = FakeSavedItemRepository(maxPendingCalls = 20)
        embedder = StubTextEmbedder(dimension = 3, default = unitVector)
    }

    private fun buildWorker(textEmbedder: TextEmbedder = embedder): EmbeddingWorker =
        TestListenableWorkerBuilder<EmbeddingWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker =
                    EmbeddingWorker(appContext, workerParameters, repository, textEmbedder, router)
            })
            .build()

    private fun Result.processedCount(): Int =
        outputData.getInt(EmbeddingWorker.KEY_PROCESSED_COUNT, -1)

    private fun assertSuccess(result: Result) {
        assertTrue("expected Result.Success but was $result", result is Result.Success)
    }

    @Test
    fun `no pending items succeeds with zero processed`() = runTest {
        val result = buildWorker().doWork()

        assertSuccess(result)
        assertEquals(0, result.processedCount())
        assertTrue(embedder.embeddedDocuments.isEmpty())
    }

    @Test
    fun `embeds every pending item and reports the count`() = runTest {
        repository.items.value = listOf(
            savedItem(id = 1, platform = Platform.INSTAGRAM_REELS, caption = "one"),
            savedItem(id = 2, platform = Platform.INSTAGRAM_REELS, caption = "two")
        )

        val result = buildWorker().doWork()

        assertSuccess(result)
        assertEquals(2, result.processedCount())
        assertArrayEquals(unitVector, repository.embeddings[1], 0f)
        assertArrayEquals(unitVector, repository.embeddings[2], 0f)
        assertTrue(repository.items.value.all { it.isEmbedded })
    }

    @Test
    fun `fetched title is stored and prepended to the caption for embedding`() = runTest {
        val url = "https://youtube.com/shorts/a"
        youtubeTitles[url] = "Carbonara"
        repository.items.value = listOf(savedItem(id = 1, url = url, caption = "in 60 seconds"))

        buildWorker().doWork()

        assertEquals(listOf(url), youtubeRequests)
        assertEquals("Carbonara", repository.metadata[1]?.title)
        assertEquals(listOf("Carbonara in 60 seconds"), embedder.embeddedDocuments)
    }

    @Test
    fun `routes TikTok items to the TikTok fetcher`() = runTest {
        repository.items.value = listOf(
            savedItem(id = 1, url = "https://www.tiktok.com/@a/video/1", platform = Platform.TIKTOK)
        )

        buildWorker().doWork()

        assertEquals(listOf("https://www.tiktok.com/@a/video/1"), tiktokRequests)
        assertTrue(youtubeRequests.isEmpty())
        assertEquals(listOf("TikTok title"), embedder.embeddedDocuments)
    }

    @Test
    fun `failed metadata fetch falls back to the caption`() = runTest {
        repository.items.value = listOf(savedItem(id = 1, url = "https://youtube.com/shorts/x", caption = "pasta"))

        val result = buildWorker().doWork()

        assertSuccess(result)
        assertTrue(repository.metadata.containsKey(1))
        assertNull(repository.metadata[1])
        assertEquals(listOf("pasta"), embedder.embeddedDocuments)
    }

    @Test
    fun `instagram items skip the network and embed the caption`() = runTest {
        repository.items.value = listOf(savedItem(id = 1, platform = Platform.INSTAGRAM_REELS, caption = "reel"))

        buildWorker().doWork()

        assertTrue(youtubeRequests.isEmpty())
        assertTrue(tiktokRequests.isEmpty())
        assertEquals(listOf("reel"), embedder.embeddedDocuments)
    }

    @Test
    fun `processes more than one batch`() = runTest {
        val count = EmbeddingWorker.BATCH_SIZE * 2 + 5
        repository.items.value = (1L..count).map {
            savedItem(id = it, platform = Platform.INSTAGRAM_REELS, caption = "item $it")
        }

        val result = buildWorker().doWork()

        assertSuccess(result)
        assertEquals(count, result.processedCount())
        // three non-empty batches plus the final empty one
        assertEquals(4, repository.pendingCalls)
    }

    @Test
    fun `embedder failure asks WorkManager to retry`() = runTest {
        repository.items.value = listOf(savedItem(id = 1, platform = Platform.INSTAGRAM_REELS, caption = "x"))
        val failingEmbedder = object : TextEmbedder by embedder {
            override suspend fun embedDocument(text: String): FloatArray = throw IllegalStateException("model not loaded")
        }

        val result = buildWorker(failingEmbedder).doWork()

        assertEquals(Result.retry(), result)
        assertTrue(repository.embeddings.isEmpty())
    }

    /**
     * BUG: an item whose text is blank is skipped with `continue` and keeps embedding = NULL.
     * getPendingEmbedding returns it again on every pass, so `pending` is never empty and the
     * while(true) loop never exits (re-fetching metadata each pass). In production this spins
     * forever; here the fake repository's call cap turns it into a Retry.
     */
    @Test
    fun `item with no text does not stall the worker`() = runTest {
        repository.items.value = listOf(
            savedItem(id = 1, platform = Platform.INSTAGRAM_REELS, caption = null),
            savedItem(id = 2, platform = Platform.INSTAGRAM_REELS, caption = "valid")
        )

        val result = buildWorker().doWork()

        assertSuccess(result)
        assertEquals(1, result.processedCount())
    }

    /** BUG: same loop as above, triggered by a NaN embedding instead of blank text. */
    @Test
    fun `NaN embedding does not stall the worker`() = runTest {
        embedder.givenDocument("broken", floatArrayOf(Float.NaN, 0f, 0f))
        repository.items.value = listOf(
            savedItem(id = 1, platform = Platform.INSTAGRAM_REELS, caption = "broken"),
            savedItem(id = 2, platform = Platform.INSTAGRAM_REELS, caption = "valid")
        )

        val result = buildWorker().doWork()

        assertSuccess(result)
        assertEquals(1, result.processedCount())
        assertNull(repository.embeddings[1])
    }
}
