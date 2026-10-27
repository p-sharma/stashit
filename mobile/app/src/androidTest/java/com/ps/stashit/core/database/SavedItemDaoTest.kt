package com.ps.stashit.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedItemDaoTest {
    private lateinit var db: StashItDatabase
    private lateinit var dao: SavedItemDao
    private val converters = Converters()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            StashItDatabase::class.java
        ).build()
        dao = db.saveItemDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun attachEmbeddingIfCurrent_storesFullSizeVector() = runBlocking {
        val id = dao.insert(SavedItemEntity(url = "https://youtube.com/shorts/a", platform = Platform.YOUTUBE_SHORTS))
        val embedding = FloatArray(768) { it / 768f }

        val updated = dao.attachEmbeddingIfCurrent(
            id = id,
            embedding = converters.fromEmbedding(embedding)!!,
            tags = "",
            classifiedAt = 1000,
            transcript = null
        )

        assertEquals(1, updated)
        val row = dao.getById(id)!!
        assertArrayEquals(embedding, row.embedding, 0f)
        assertEquals(1000L, row.classifiedAt)
    }

    @Test
    fun attachEmbeddingIfCurrent_dropsWriteWhenTranscriptChanged() = runBlocking {
        val id = dao.insert(SavedItemEntity(url = "https://youtube.com/shorts/b", platform = Platform.YOUTUBE_SHORTS))
        dao.attachTranscript(id, transcript = "newer transcript", fetchedAt = 2000)

        val updated = dao.attachEmbeddingIfCurrent(
            id = id,
            embedding = converters.fromEmbedding(FloatArray(768) { 1f })!!,
            tags = "",
            classifiedAt = 1000,
            transcript = null
        )

        assertEquals(0, updated)
        assertNull(dao.getById(id)!!.embedding)
    }
}
