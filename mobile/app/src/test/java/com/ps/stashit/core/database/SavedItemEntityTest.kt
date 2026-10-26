package com.ps.stashit.core.database

import com.ps.stashit.testutil.savedItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SavedItemEntityTest {

    @Test
    fun `entities with equal content embeddings are equal`() {
        val a = savedItemEntity(embedding = floatArrayOf(1f, 2f))
        val b = savedItemEntity(embedding = floatArrayOf(1f, 2f))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `entities with different embeddings are not equal`() {
        assertNotEquals(
            savedItemEntity(embedding = floatArrayOf(1f, 2f)),
            savedItemEntity(embedding = floatArrayOf(1f, 3f))
        )
    }

    @Test
    fun `null embedding differs from non-null embedding`() {
        assertNotEquals(
            savedItemEntity(embedding = null),
            savedItemEntity(embedding = floatArrayOf(1f))
        )
    }

    @Test
    fun `entities with different id or url are not equal`() {
        assertNotEquals(savedItemEntity(id = 1), savedItemEntity(id = 2, url = savedItemEntity(id = 1).url))
        assertNotEquals(savedItemEntity(url = "https://a"), savedItemEntity(url = "https://b"))
    }

    /**
     * Characterization: equals() only compares id, url and embedding. Two rows that differ in
     * caption, title, tags or timestamps are considered equal. Anything that dedupes on equality
     * (distinctUntilChanged, StateFlow) would drop such updates.
     */
    @Test
    fun `equals ignores caption, title, tags and timestamps`() {
        val original = savedItemEntity(caption = "old", extractedTitle = "old", tags = "a", createdAt = 1)
        val changed = original.copy(caption = "new", extractedTitle = "new", tags = "b", createdAt = 2)

        assertEquals(original, changed)
    }
}
