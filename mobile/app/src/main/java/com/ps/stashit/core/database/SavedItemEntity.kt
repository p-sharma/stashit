package com.ps.stashit.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_items",
    indices = [Index(value = ["url"], unique = true)]
)
data class SavedItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val url: String,
    val platform: Platform,
    val caption: String? = null,
    val extractedTitle: String? = null,

    //LLM generated tags at save time, e.g. "recipe, pasta, cooking"
    val tags: String? = null,

    //Embedding is nullable because it's filled in async by WorkManager
    // after the item is captured
    val embedding: FloatArray? = null,
    val embeddingModelVersion: Int? = null,

    val thumbnailUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val classifiedAt: Long? = null,
    val metadataFetchedAt: Long? = null,

    //Spoken captions from the caption service, filled in async by CaptionWorker
    val transcript: String? = null,
    @ColumnInfo(defaultValue = "PENDING")
    val transcriptStatus: TranscriptStatus = TranscriptStatus.PENDING,
    val transcriptFetchedAt: Long? = null
) {

    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SavedItemEntity) return false

        return id == other.id && url == other.url && embedding.contentEquals(other.embedding)
    }
}

enum class Platform {
    YOUTUBE_SHORTS, TIKTOK, INSTAGRAM_REELS, UNKNOWN
}

enum class TranscriptStatus {
    PENDING, DONE, UNAVAILABLE, SKIPPED
}
