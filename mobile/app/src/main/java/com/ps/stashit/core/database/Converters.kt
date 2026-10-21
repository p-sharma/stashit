package com.ps.stashit.core.database

import androidx.room.TypeConverter
import java.nio.ByteBuffer

class Converters {
    @TypeConverter
    fun fromPlatform(platform: Platform): String = platform.name

    @TypeConverter
    fun toPlatform(value: String): Platform = runCatching {
        Platform.valueOf(value)
    }.getOrDefault(Platform.UNKNOWN)

    @TypeConverter
    fun fromEmbedding(embedding: FloatArray?): ByteArray? {
        if (embedding == null) return null
        val buffer = ByteBuffer.allocate(embedding.size * 4)
        embedding.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    @TypeConverter
    fun toEmbedding(bytes: ByteArray?): FloatArray? {
        if (bytes == null) return null
        val buffer = ByteBuffer.wrap(bytes)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }
}