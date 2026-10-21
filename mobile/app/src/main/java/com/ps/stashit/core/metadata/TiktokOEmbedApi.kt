package com.ps.stashit.core.metadata


import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface TiktokOEmbedApi {
    @GET("oembed")
    suspend fun getOEmbed(
        @Query("url") url:String,
    ): TiktokOEmbedResponse
}

data class TiktokOEmbedResponse(
    val title: String,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("author_name") val authorName: String?
)