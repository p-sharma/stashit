package com.ps.stashit.core.metadata

import javax.inject.Inject

class TiktokMetaDataFetcher @Inject constructor(
    private val api: TiktokOEmbedApi
) : MetadataFetcher {
    override suspend fun fetch(url: String): RemoteMetaData? = try {
        val response = api.getOEmbed(url)
        RemoteMetaData(response.title, response.thumbnailUrl, response.authorName)
    } catch (e: Exception) {
        null
    }
}