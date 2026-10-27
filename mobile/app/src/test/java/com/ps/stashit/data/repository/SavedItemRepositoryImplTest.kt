package com.ps.stashit.data.repository

import com.ps.stashit.core.CaptionScheduler
import com.ps.stashit.core.EmbeddingScheduler
import com.ps.stashit.core.database.Platform
import com.ps.stashit.core.database.TranscriptStatus
import com.ps.stashit.core.metadata.RemoteMetaData
import com.ps.stashit.testutil.FakeSavedItemDao
import com.ps.stashit.testutil.StubTextEmbedder
import com.ps.stashit.testutil.savedItem
import com.ps.stashit.testutil.savedItemEntity
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SavedItemRepositoryImplTest {
    private lateinit var dao: FakeSavedItemDao
    private lateinit var embedder: StubTextEmbedder
    private lateinit var scheduler: EmbeddingScheduler
    private lateinit var captionScheduler: CaptionScheduler
    private lateinit var repository: SavedItemRepositoryImpl

    private val queryVector = floatArrayOf(1f, 0f, 0f)

    @Before
    fun setUp() {
        dao = FakeSavedItemDao()
        embedder = StubTextEmbedder(dimension = 3)
        scheduler = mockk(relaxed = true)
        captionScheduler = mockk(relaxed = true)
        repository = SavedItemRepositoryImpl(dao, embedder, scheduler, captionScheduler)
    }

    private suspend fun givenEmbedded(
        id: Long,
        embedding: FloatArray,
        caption: String? = null,
        title: String? = null,
        transcript: String? = null
    ) {
        dao.insert(
            savedItemEntity(
                id = id,
                caption = caption,
                extractedTitle = title,
                embedding = embedding,
                transcript = transcript
            )
        )
    }

    // region search

    @Test
    fun `blank query returns empty without embedding`() = runTest {
        givenEmbedded(1, queryVector)

        assertTrue(repository.search("").isEmpty())
        assertTrue(repository.search("   ").isEmpty())
        assertTrue(embedder.embeddedQueries.isEmpty())
    }

    @Test
    fun `query is embedded with embedQuery`() = runTest {
        repository.search("pasta")

        assertEquals(listOf("pasta"), embedder.embeddedQueries)
    }

    @Test
    fun `results are ranked by cosine similarity and low scores are filtered`() = runTest {
        embedder.givenQuery("q", queryVector)
        givenEmbedded(1, floatArrayOf(0.8f, 0.6f, 0f))   // cos = 0.8
        givenEmbedded(2, floatArrayOf(2f, 0f, 0f))       // cos = 1.0 (not unit length)
        givenEmbedded(3, floatArrayOf(0.6f, 0.8f, 0f))   // cos = 0.6, below threshold
        givenEmbedded(4, floatArrayOf(0f, 1f, 0f))       // cos = 0

        val results = repository.search("q")

        assertEquals(listOf(2L, 1L), results.map { it.item.id })
        assertEquals(1.0f, results[0].score, 1e-5f)
        assertEquals(0.8f, results[1].score, 1e-5f)
    }

    @Test
    fun `rows without embedding are never returned`() = runTest {
        embedder.givenQuery("pasta", queryVector)
        dao.insert(savedItemEntity(id = 1, caption = "pasta", embedding = null))

        assertTrue(repository.search("pasta").isEmpty())
    }

    @Test
    fun `keyword match bypasses the similarity threshold and gets a 0_3 boost`() = runTest {
        embedder.givenQuery("pasta", queryVector)
        givenEmbedded(1, floatArrayOf(0f, 1f, 0f), caption = "Best pasta ever")  // cos = 0

        val results = repository.search("pasta")

        assertEquals(listOf(1L), results.map { it.item.id })
        assertEquals(0.3f, results[0].score, 1e-5f)
    }

    @Test
    fun `keyword match is case-insensitive and ignores surrounding whitespace in the query`() = runTest {
        givenEmbedded(1, floatArrayOf(0f, 1f, 0f), caption = "Best PASTA ever")

        assertEquals(listOf(1L), repository.search("  Pasta ").map { it.item.id })
    }

    @Test
    fun `keyword match also checks the extracted title`() = runTest {
        givenEmbedded(1, floatArrayOf(0f, 1f, 0f), title = "Carbonara in 60 seconds")

        assertEquals(listOf(1L), repository.search("carbonara").map { it.item.id })
    }

    @Test
    fun `keyword match also checks the transcript`() = runTest {
        givenEmbedded(1, floatArrayOf(0f, 1f, 0f), transcript = "first we render the guanciale")

        assertEquals(listOf(1L), repository.search("guanciale").map { it.item.id })
    }

    @Test
    fun `keyword boost can lift an item above a better semantic match`() = runTest {
        embedder.givenQuery("pasta", queryVector)
        givenEmbedded(1, floatArrayOf(0.9f, 0.43589f, 0f))                         // cos ~0.9, no keyword
        givenEmbedded(2, floatArrayOf(0.8f, 0.6f, 0f), caption = "pasta night")   // 0.8 + 0.3

        val results = repository.search("pasta")

        assertEquals(listOf(2L, 1L), results.map { it.item.id })
    }

    @Test
    fun `combined score is capped at 1`() = runTest {
        embedder.givenQuery("pasta", queryVector)
        givenEmbedded(1, queryVector, caption = "pasta")

        assertEquals(1f, repository.search("pasta").single().score, 0f)
    }

    @Test
    fun `limit caps the number of results`() = runTest {
        embedder.givenQuery("q", queryVector)
        (1L..5L).forEach { givenEmbedded(it, queryVector) }

        assertEquals(3, repository.search("q", limit = 3).size)
        assertEquals(5, repository.search("q").size)
    }

    @Test
    fun `zero vectors score 0 instead of NaN`() = runTest {
        embedder.givenQuery("q", FloatArray(3))
        givenEmbedded(1, queryVector, caption = "q")

        val result = repository.search("q").single()

        assertEquals(0.3f, result.score, 1e-5f)
    }

    // endregion

    // region saveItem

    @Test
    fun `saving a new url inserts a pending row and triggers embedding`() = runTest {
        val id = repository.saveItem("https://youtu.be/a", Platform.YOUTUBE_SHORTS, "caption")

        val row = dao.getById(id)!!
        assertEquals("https://youtu.be/a", row.url)
        assertEquals(Platform.YOUTUBE_SHORTS, row.platform)
        assertEquals("caption", row.caption)
        assertNull(row.embedding)
        verify(exactly = 1) { scheduler.triggerImmediate() }
    }

    @Test
    fun `saving a YouTube or TikTok url queues a transcript fetch`() = runTest {
        val youtubeId = repository.saveItem("https://youtube.com/shorts/a", Platform.YOUTUBE_SHORTS, null)
        val tiktokId = repository.saveItem("https://www.tiktok.com/@a/video/1", Platform.TIKTOK, null)

        assertEquals(TranscriptStatus.PENDING, dao.getById(youtubeId)!!.transcriptStatus)
        assertEquals(TranscriptStatus.PENDING, dao.getById(tiktokId)!!.transcriptStatus)
        verify(exactly = 2) { captionScheduler.triggerImmediate() }
    }

    @Test
    fun `saving an unsupported platform skips the transcript fetch`() = runTest {
        val instagramId = repository.saveItem("https://instagram.com/reel/a", Platform.INSTAGRAM_REELS, null)
        val unknownId = repository.saveItem("https://example.com/a", Platform.UNKNOWN, null)

        assertEquals(TranscriptStatus.SKIPPED, dao.getById(instagramId)!!.transcriptStatus)
        assertEquals(TranscriptStatus.SKIPPED, dao.getById(unknownId)!!.transcriptStatus)
        verify(exactly = 0) { captionScheduler.triggerImmediate() }
    }

    @Test
    fun `saving an existing url returns the existing id without inserting`() = runTest {
        val firstId = repository.saveItem("https://youtu.be/a", Platform.YOUTUBE_SHORTS, null)

        val secondId = repository.saveItem("https://youtu.be/a", Platform.YOUTUBE_SHORTS, null)

        assertEquals(firstId, secondId)
        assertEquals(1, dao.all.size)
    }

    @Test
    fun `re-saving keeps the original caption`() = runTest {
        repository.saveItem("https://youtu.be/a", Platform.YOUTUBE_SHORTS, "original")

        repository.saveItem("https://youtu.be/a", Platform.YOUTUBE_SHORTS, "new")

        assertEquals("original", dao.all.single().caption)
    }

    @Test
    fun `re-saving fills a missing caption`() = runTest {
        repository.saveItem("https://youtu.be/a", Platform.YOUTUBE_SHORTS, null)

        repository.saveItem("https://youtu.be/a", Platform.YOUTUBE_SHORTS, "new")

        assertEquals("new", dao.all.single().caption)
    }

    @Test
    fun `re-saving bumps createdAt so the item moves to the top`() = runTest {
        dao.insert(savedItemEntity(id = 1, url = "https://youtu.be/a", createdAt = 1000))

        repository.saveItem("https://youtu.be/a", Platform.YOUTUBE_SHORTS, null)

        assertTrue(dao.all.single().createdAt > 1000)
    }

    @Test
    fun `re-saving an existing url does not trigger embedding`() = runTest {
        dao.insert(savedItemEntity(id = 1, url = "https://youtu.be/a"))

        repository.saveItem("https://youtu.be/a", Platform.YOUTUBE_SHORTS, null)

        verify(exactly = 0) { scheduler.triggerImmediate() }
        verify(exactly = 0) { captionScheduler.triggerImmediate() }
    }

    // endregion

    // region attach / delete / observe

    @Test
    fun `attachEmbedding stores vector, joined tags and classifiedAt`() = runTest {
        dao.insert(savedItemEntity(id = 1))
        val vector = floatArrayOf(0.1f, 0.2f, 0.3f)

        repository.attachEmbedding(1, vector, listOf("food", "pasta"), embeddedTranscript = null)

        val row = dao.getById(1)!!
        assertArrayEquals(vector, row.embedding, 0f)
        assertEquals("food,pasta", row.tags)
        assertNotNull(row.classifiedAt)
    }

    @Test
    fun `attachEmbedding for a missing id is a no-op`() = runTest {
        repository.attachEmbedding(99, floatArrayOf(1f), emptyList(), embeddedTranscript = null)

        assertTrue(dao.all.isEmpty())
    }

    @Test
    fun `attachEmbedding is dropped when the transcript changed after embedding started`() = runTest {
        dao.insert(savedItemEntity(id = 1))
        repository.attachTranscript(1, "spoken words")

        repository.attachEmbedding(1, floatArrayOf(1f), emptyList(), embeddedTranscript = null)

        assertNull(dao.getById(1)!!.embedding)
    }

    @Test
    fun `attachTranscript stores the text and clears the embedding for re-embedding`() = runTest {
        dao.insert(savedItemEntity(id = 1, embedding = floatArrayOf(1f)))

        repository.attachTranscript(1, "spoken words")

        val row = dao.getById(1)!!
        assertEquals("spoken words", row.transcript)
        assertEquals(TranscriptStatus.DONE, row.transcriptStatus)
        assertNotNull(row.transcriptFetchedAt)
        assertNull(row.embedding)
    }

    @Test
    fun `markTranscriptUnavailable keeps the embedding`() = runTest {
        dao.insert(savedItemEntity(id = 1, embedding = floatArrayOf(1f)))

        repository.markTranscriptUnavailable(1)

        val row = dao.getById(1)!!
        assertEquals(TranscriptStatus.UNAVAILABLE, row.transcriptStatus)
        assertNotNull(row.embedding)
    }

    @Test
    fun `getPendingTranscript returns only pending YouTube and TikTok rows`() = runTest {
        dao.insert(savedItemEntity(id = 1, platform = Platform.YOUTUBE_SHORTS))
        dao.insert(savedItemEntity(id = 2, platform = Platform.TIKTOK))
        dao.insert(savedItemEntity(id = 3, platform = Platform.INSTAGRAM_REELS))
        dao.insert(savedItemEntity(id = 4, transcriptStatus = TranscriptStatus.SKIPPED))
        dao.insert(savedItemEntity(id = 5, transcriptStatus = TranscriptStatus.DONE))

        val pending = repository.getPendingTranscript(afterId = 0, batchSize = 10)

        assertEquals(listOf(1L, 2L), pending.map { it.id })
    }

    @Test
    fun `attachMetaData writes title and thumbnail`() = runTest {
        dao.insert(savedItemEntity(id = 1))

        repository.attachMetaData(1, RemoteMetaData("Title", "https://thumb", "author"))

        val row = dao.getById(1)!!
        assertEquals("Title", row.extractedTitle)
        assertEquals("https://thumb", row.thumbnailUrl)
        assertNotNull(row.metadataFetchedAt)
    }

    /** Characterization: a failed fetch (null metadata) overwrites an existing title with null. */
    @Test
    fun `attachMetaData with null metadata clears existing title`() = runTest {
        dao.insert(savedItemEntity(id = 1, extractedTitle = "Existing"))

        repository.attachMetaData(1, null)

        assertNull(dao.getById(1)!!.extractedTitle)
        assertNotNull(dao.getById(1)!!.metadataFetchedAt)
    }

    @Test
    fun `delete removes the row`() = runTest {
        dao.insert(savedItemEntity(id = 1))

        repository.delete(savedItem(id = 1))

        assertTrue(dao.all.isEmpty())
    }

    @Test
    fun `deleting a missing item is a no-op`() = runTest {
        dao.insert(savedItemEntity(id = 1))

        repository.delete(savedItem(id = 2))

        assertEquals(1, dao.all.size)
    }

    @Test
    fun `getPendingEmbedding returns only unembedded rows up to batch size`() = runTest {
        dao.insert(savedItemEntity(id = 1, embedding = queryVector))
        (2L..5L).forEach { dao.insert(savedItemEntity(id = it)) }

        val pending = repository.getPendingEmbedding(batchSize = 2)

        assertEquals(2, pending.size)
        assertTrue(pending.none { it.isEmbedded })
    }

    @Test
    fun `observeAll maps entities to domain newest first`() = runTest {
        dao.insert(savedItemEntity(id = 1, createdAt = 1, tags = "a,b"))
        dao.insert(savedItemEntity(id = 2, createdAt = 2))

        val items = repository.observeAll().first()

        assertEquals(listOf(2L, 1L), items.map { it.id })
        assertEquals(listOf("a", "b"), items[1].tags)
    }

    @Test
    fun `observePendingEmbeddingCount counts rows without embedding`() = runTest {
        dao.insert(savedItemEntity(id = 1, embedding = queryVector))
        dao.insert(savedItemEntity(id = 2))

        assertEquals(1, repository.observePendingEmbeddingCount().first())
    }

    // endregion
}
