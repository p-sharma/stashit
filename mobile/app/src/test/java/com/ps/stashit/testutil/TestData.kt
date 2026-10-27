package com.ps.stashit.testutil

import com.ps.stashit.core.database.Platform
import com.ps.stashit.core.database.SavedItemEntity
import com.ps.stashit.core.database.TranscriptStatus
import com.ps.stashit.data.domain.SavedItem

fun savedItem(
    id: Long = 1,
    url: String = "https://www.youtube.com/shorts/abc$id",
    platform: Platform = Platform.YOUTUBE_SHORTS,
    caption: String? = null,
    transcript: String? = null,
    extractedTitle: String? = null,
    tags: List<String> = emptyList(),
    isEmbedded: Boolean = false
) = SavedItem(
    id = id,
    url = url,
    platform = platform,
    caption = caption,
    transcript = transcript,
    extractedTitle = extractedTitle,
    tags = tags,
    thumbnailUrl = null,
    createdAt = 0L,
    isEmbedded = isEmbedded
)

fun savedItemEntity(
    id: Long = 1,
    url: String = "https://www.youtube.com/shorts/abc$id",
    platform: Platform = Platform.YOUTUBE_SHORTS,
    caption: String? = null,
    extractedTitle: String? = null,
    tags: String? = null,
    embedding: FloatArray? = null,
    createdAt: Long = 0L,
    transcript: String? = null,
    transcriptStatus: TranscriptStatus = TranscriptStatus.PENDING
) = SavedItemEntity(
    id = id,
    url = url,
    platform = platform,
    caption = caption,
    extractedTitle = extractedTitle,
    tags = tags,
    embedding = embedding,
    createdAt = createdAt,
    transcript = transcript,
    transcriptStatus = transcriptStatus
)
