package com.ps.stashit.core.database

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `platform round-trips through its name`() {
        Platform.entries.forEach { platform ->
            assertEquals(platform, converters.toPlatform(converters.fromPlatform(platform)))
        }
    }

    @Test
    fun `unknown platform string falls back to UNKNOWN`() {
        assertEquals(Platform.UNKNOWN, converters.toPlatform("SNAPCHAT"))
        assertEquals(Platform.UNKNOWN, converters.toPlatform(""))
        assertEquals(Platform.UNKNOWN, converters.toPlatform("youtube_shorts"))
    }

    @Test
    fun `null embedding stays null in both directions`() {
        assertNull(converters.fromEmbedding(null))
        assertNull(converters.toEmbedding(null))
    }

    @Test
    fun `embedding round-trips exactly`() {
        val embedding = floatArrayOf(0.1f, -0.25f, 1f, 0f, -0f, Float.MIN_VALUE, Float.MAX_VALUE)

        val restored = converters.toEmbedding(converters.fromEmbedding(embedding))

        assertArrayEquals(embedding, restored, 0f)
    }

    @Test
    fun `512-dim embedding round-trips and uses 4 bytes per float`() {
        val embedding = FloatArray(512) { it / 512f - 0.5f }

        val bytes = converters.fromEmbedding(embedding)!!

        assertEquals(512 * 4, bytes.size)
        assertArrayEquals(embedding, converters.toEmbedding(bytes), 0f)
    }

    @Test
    fun `empty embedding round-trips to empty array`() {
        val bytes = converters.fromEmbedding(FloatArray(0))!!

        assertEquals(0, bytes.size)
        assertEquals(0, converters.toEmbedding(bytes)!!.size)
    }

    @Test
    fun `NaN and infinities survive the round-trip`() {
        val embedding = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)

        val restored = converters.toEmbedding(converters.fromEmbedding(embedding))!!

        assertTrue(restored[0].isNaN())
        assertEquals(Float.POSITIVE_INFINITY, restored[1])
        assertEquals(Float.NEGATIVE_INFINITY, restored[2])
    }

    @Test
    fun `encoding is big-endian`() {
        val bytes = converters.fromEmbedding(floatArrayOf(1f))!!

        // IEEE 754 1.0f = 0x3F800000
        assertArrayEquals(byteArrayOf(0x3F, 0x80.toByte(), 0x00, 0x00), bytes)
    }

    @Test
    fun `trailing bytes that do not form a full float are dropped`() {
        val bytes = converters.fromEmbedding(floatArrayOf(2f, 3f))!! + byteArrayOf(0x01, 0x02)

        assertArrayEquals(floatArrayOf(2f, 3f), converters.toEmbedding(bytes), 0f)
    }
}
