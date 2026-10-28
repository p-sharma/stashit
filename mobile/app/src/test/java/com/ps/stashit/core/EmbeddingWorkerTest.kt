package com.ps.stashit.core

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.ps.stashit.core.database.MAX_EMBEDDING_ATTEMPTS
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
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EmbeddingWorkerTest {
    private lateinit var context: Context
    private lateinit var repository: FakeSavedItemRepository
    private lateinit var embedder: StubTextEmbedder

    private val youtubeTitles = mutableMapOf<String, String>()
    private val youtubeHttpErrors = mutableMapOf<String, Int>()
    private val youtubeRequests = mutableListOf<String>()
    private val tiktokRequests = mutableListOf<String>()

    private val youtubeApi = object : YoutubeOEmbedApi {
        override suspend fun getOEmbed(url: String, format: String): YoutubeOEmbedResponse {
            youtubeRequests += url
            youtubeHttpErrors[url]?.let { throw HttpException(Response.error<Any>(it, "".toResponseBody())) }
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

    private fun FakeSavedItemRepository.item(id: Long) = items.value.single { it.id == id }

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
    fun `transient metadata failure defers embedding to a later run`() = runTest {
        repository.items.value = listOf(savedItem(id = 1, url = "https://youtube.com/shorts/x", caption = "pasta"))

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        assertTrue(embedder.embeddedDocuments.isEmpty())
        assertNull(repository.item(1).metadataFetchedAt)
        assertEquals(1, repository.item(1).embeddingAttempts)
    }

    @Test
    fun `server error counts as a transient metadata failure`() = runTest {
        val url = "https://youtube.com/shorts/x"
        youtubeHttpErrors[url] = 503
        repository.items.value = listOf(savedItem(id = 1, url = url, caption = "pasta"))

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        assertTrue(embedder.embeddedDocuments.isEmpty())
        assertNull(repository.item(1).metadataFetchedAt)
    }

    @Test
    fun `transient metadata failure on the last attempt embeds the caption without stamping metadata`() = runTest {
        repository.items.value = listOf(
            savedItem(
                id = 1,
                url = "https://youtube.com/shorts/x",
                caption = "pasta",
                embeddingAttempts = MAX_EMBEDDING_ATTEMPTS - 1
            )
        )

        val result = buildWorker().doWork()

        assertSuccess(result)
        assertEquals(listOf("pasta"), embedder.embeddedDocuments)
        assertNull(repository.item(1).metadataFetchedAt)
        assertTrue(repository.metadata.isEmpty())
    }

    @Test
    fun `unavailable metadata is stamped and the caption is embedded`() = runTest {
        val url = "https://youtube.com/shorts/private"
        youtubeHttpErrors[url] = 404
        repository.items.value = listOf(savedItem(id = 1, url = url, caption = "pasta"))

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

    @Test
    fun `item with no text counts as a failed attempt without stalling the worker`() = runTest {
        repository.items.value = listOf(
            savedItem(id = 1, platform = Platform.INSTAGRAM_REELS, caption = null),
            savedItem(id = 2, platform = Platform.INSTAGRAM_REELS, caption = "valid")
        )

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        assertEquals(1, repository.item(1).embeddingAttempts)
        assertArrayEquals(unitVector, repository.embeddings[2], 0f)
    }

    @Test
    fun `NaN embedding counts as a failed attempt without stalling the worker`() = runTest {
        embedder.givenDocument("broken", floatArrayOf(Float.NaN, 0f, 0f))
        repository.items.value = listOf(
            savedItem(id = 1, platform = Platform.INSTAGRAM_REELS, caption = "broken"),
            savedItem(id = 2, platform = Platform.INSTAGRAM_REELS, caption = "valid")
        )

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        assertNull(repository.embeddings[1])
        assertEquals(1, repository.item(1).embeddingAttempts)
        assertArrayEquals(unitVector, repository.embeddings[2], 0f)
    }

    @Test
    fun `failing item does not block metadata and embeddings for later items`() = runTest {
        val url = "https://youtube.com/shorts/b"
        youtubeTitles[url] = "Second"
        repository.items.value = listOf(
            savedItem(id = 1, platform = Platform.INSTAGRAM_REELS, caption = "boom"),
            savedItem(id = 2, url = url, caption = "later")
        )
        val failingEmbedder = object : TextEmbedder by embedder {
            override suspend fun embedDocument(text: String): FloatArray =
                if (text == "boom") throw IllegalStateException("write failed") else embedder.embedDocument(text)
        }

        val result = buildWorker(failingEmbedder).doWork()

        assertEquals(Result.retry(), result)
        assertEquals(1, repository.item(1).embeddingAttempts)
        assertEquals("Second", repository.metadata[2]?.title)
        assertArrayEquals(unitVector, repository.embeddings[2], 0f)
    }

    @Test
    fun `item that fails its last attempt is given up and the run succeeds`() = runTest {
        repository.items.value = listOf(
            savedItem(
                id = 1,
                platform = Platform.INSTAGRAM_REELS,
                caption = null,
                embeddingAttempts = MAX_EMBEDDING_ATTEMPTS - 1
            )
        )

        val result = buildWorker().doWork()

        assertSuccess(result)
        assertEquals(MAX_EMBEDDING_ATTEMPTS, repository.item(1).embeddingAttempts)
        assertTrue(repository.getPendingEmbedding(0, EmbeddingWorker.BATCH_SIZE).isEmpty())
    }

    @Test
    fun `items that used up their attempts are not processed`() = runTest {
        repository.items.value = listOf(
            savedItem(
                id = 1,
                platform = Platform.INSTAGRAM_REELS,
                caption = "given up",
                embeddingAttempts = MAX_EMBEDDING_ATTEMPTS
            )
        )

        val result = buildWorker().doWork()

        assertSuccess(result)
        assertEquals(0, result.processedCount())
        assertTrue(embedder.embeddedDocuments.isEmpty())
    }

    @Test
    fun `later attempt reuses stored metadata instead of calling oEmbed`() = runTest {
        val url = "https://youtube.com/shorts/c"
        repository.items.value = listOf(
            savedItem(
                id = 1,
                url = url,
                caption = "retry",
                extractedTitle = "Stored title",
                metadataFetchedAt = 1000L,
                embeddingAttempts = 1
            )
        )

        val result = buildWorker().doWork()

        assertSuccess(result)
        assertTrue(youtubeRequests.isEmpty())
        assertEquals(listOf("Stored title retry"), embedder.embeddedDocuments)
    }

    @Test
    fun `transcript is appended to the embedding text`() = runTest {
        repository.items.value = listOf(
            savedItem(id = 1, platform = Platform.INSTAGRAM_REELS, caption = "pasta", transcript = "boil the water")
        )

        buildWorker().doWork()

        assertEquals(listOf("pasta boil the water"), embedder.embeddedDocuments)
        assertArrayEquals(unitVector, repository.embeddings[1], 0f)
    }

    @Test
    fun `embedding computed before a transcript arrived is not stored`() = runTest {
        repository.items.value = listOf(savedItem(id = 1, platform = Platform.INSTAGRAM_REELS, caption = "pasta"))
        // Simulates CaptionWorker attaching a transcript while this item is being embedded.
        val racingEmbedder = object : TextEmbedder by embedder {
            override suspend fun embedDocument(text: String): FloatArray {
                repository.attachTranscript(1, "boil the water")
                return embedder.embedDocument(text)
            }
        }

        val result = buildWorker(racingEmbedder).doWork()

        assertSuccess(result)
        assertNull(repository.embeddings[1])
        assertTrue(repository.items.value.single().let { !it.isEmbedded && it.transcript == "boil the water" })
    }
}
