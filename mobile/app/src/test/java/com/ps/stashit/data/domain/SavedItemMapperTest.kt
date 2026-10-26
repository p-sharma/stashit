package com.ps.stashit.data.domain

import com.ps.stashit.core.database.Platform
import com.ps.stashit.testutil.savedItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedItemMapperTest {

    @Test
    fun `maps scalar fields`() {
        val entity = savedItemEntity(
            id = 7,
            url = "https://www.tiktok.com/@x/video/1",
            platform = Platform.TIKTOK,
            caption = "caption",
            extractedTitle = "title",
            createdAt = 42
        ).copy(thumbnailUrl = "https://thumb")

        val item = entity.toDomain()

        assertEquals(7L, item.id)
        assertEquals("https://www.tiktok.com/@x/video/1", item.url)
        assertEquals(Platform.TIKTOK, item.platform)
        assertEquals("caption", item.caption)
        assertEquals("title", item.extractedTitle)
        assertEquals("https://thumb", item.thumbnailUrl)
        assertEquals(42L, item.createdAt)
    }

    @Test
    fun `null tags map to empty list`() {
        assertEquals(emptyList<String>(), savedItemEntity(tags = null).toDomain().tags)
    }

    @Test
    fun `empty tags string maps to empty list`() {
        assertEquals(emptyList<String>(), savedItemEntity(tags = "").toDomain().tags)
    }

    @Test
    fun `tags are split on comma and trimmed`() {
        assertEquals(
            listOf("recipe", "pasta", "cooking"),
            savedItemEntity(tags = "recipe, pasta ,cooking").toDomain().tags
        )
    }

    @Test
    fun `blank tag segments are dropped`() {
        assertEquals(listOf("a", "b"), savedItemEntity(tags = "a,, ,b,").toDomain().tags)
    }

    @Test
    fun `isEmbedded reflects embedding presence`() {
        assertFalse(savedItemEntity(embedding = null).toDomain().isEmbedded)
        assertTrue(savedItemEntity(embedding = floatArrayOf(0.1f)).toDomain().isEmbedded)
        assertTrue(savedItemEntity(embedding = FloatArray(0)).toDomain().isEmbedded)
    }
}
