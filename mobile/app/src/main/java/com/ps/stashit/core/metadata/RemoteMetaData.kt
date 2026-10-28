package com.ps.stashit.core.metadata

import retrofit2.HttpException

data class RemoteMetaData(
    val title: String?,
    val thumbnailUrl: String?,
    val authorName: String?
)

sealed interface MetaDataResult {
    data class Found(val metaData: RemoteMetaData) : MetaDataResult

    /** The platform has no metadata for this URL (private, deleted, unsupported); refetching won't help. */
    data object Unavailable : MetaDataResult

    /** Network error, server error or rate limit; a later run may succeed. */
    data object Failed : MetaDataResult
}

interface MetadataFetcher {
    suspend fun fetch(url: String): MetaDataResult
}

internal fun HttpException.isTransient(): Boolean = code() == 408 || code() == 429 || code() >= 500
