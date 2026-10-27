package com.ps.stashit.core.metadata

import android.util.Log
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

class TiktokMetaDataFetcher @Inject constructor(
    private val api: TiktokOEmbedApi
) : MetadataFetcher {
    override suspend fun fetch(url: String): RemoteMetaData? = try {
        val response = api.getOEmbed(url)
        RemoteMetaData(response.title, response.thumbnailUrl, response.authorName)
    } catch (e: HttpException) {
        Log.w(TAG, "oEmbed request for $url failed with HTTP ${e.code()}")
        null
    } catch (e: IOException) {
        Log.w(TAG, "oEmbed request for $url failed", e)
        null
    }

    private companion object {
        const val TAG = "TiktokMetaDataFetcher"
    }
}
