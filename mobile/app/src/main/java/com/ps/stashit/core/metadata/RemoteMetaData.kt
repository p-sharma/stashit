package com.ps.stashit.core.metadata

data class RemoteMetaData(
    val title: String?,
    val thumbnailUrl: String?,
    val authorName: String?
)

interface MetadataFetcher {
    suspend fun fetch(url: String): RemoteMetaData?
}
