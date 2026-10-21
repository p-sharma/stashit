package com.ps.stashit.core.metadata

import android.util.Log
import javax.inject.Inject

class YoutubeMetaDataFetcher @Inject constructor(
    private val api: YoutubeOEmbedApi
) : MetadataFetcher {
    override suspend fun fetch(url: String): RemoteMetaData? = try {
        val response = api.getOEmbed(url)
        RemoteMetaData(response.title, response.thumbnailUrl, response.authorName)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}