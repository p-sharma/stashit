package com.ps.stashit.core.metadata

import android.util.Log
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

class YoutubeMetaDataFetcher @Inject constructor(
    private val api: YoutubeOEmbedApi
) : MetadataFetcher {
    override suspend fun fetch(url: String): MetaDataResult = try {
        val response = api.getOEmbed(url)
        MetaDataResult.Found(RemoteMetaData(response.title, response.thumbnailUrl, response.authorName))
    } catch (e: HttpException) {
        Log.w(TAG, "oEmbed request for $url failed with HTTP ${e.code()}")
        if (e.isTransient()) MetaDataResult.Failed else MetaDataResult.Unavailable
    } catch (e: IOException) {
        Log.w(TAG, "oEmbed request for $url failed", e)
        MetaDataResult.Failed
    }

    private companion object {
        const val TAG = "YoutubeMetaDataFetcher"
    }
}
