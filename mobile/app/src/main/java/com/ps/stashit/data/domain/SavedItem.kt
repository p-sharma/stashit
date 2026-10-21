package com.ps.stashit.data.domain

import com.ps.stashit.core.database.Platform
import com.ps.stashit.core.database.SavedItemEntity

data class SavedItem(
    val id: Long = 0,
    val url: String,
    val platform: Platform,
    val caption: String?,
    val extractedTitle: String?,
    val tags: List<String>,
    val thumbnailUrl: String?,
    val  createdAt: Long,
    val isEmbedded: Boolean
)

data class SearchResult(
    val item: SavedItem,
    val score: Float
)

fun SavedItemEntity.toDomain(): SavedItem = SavedItem(
    id = id,
    url = url,
    platform = platform,
    caption = caption,
    extractedTitle = extractedTitle,
    tags = tags?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?: emptyList(),
    thumbnailUrl = thumbnailUrl,
    createdAt = createdAt,
    isEmbedded = embedding != null
)