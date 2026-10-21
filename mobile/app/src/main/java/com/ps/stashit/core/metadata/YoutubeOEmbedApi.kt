package com.ps.stashit.core.metadata

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query
import javax.inject.Inject


interface YoutubeOEmbedApi {
    @GET("oembed")
    suspend fun getOEmbed(
        @Query("url") url: String,
        @Query("format") format: String = "json"
    ): YoutubeOEmbedResponse
}

data class YoutubeOEmbedResponse(
    val title: String,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("author_name") val authorName: String?
)


