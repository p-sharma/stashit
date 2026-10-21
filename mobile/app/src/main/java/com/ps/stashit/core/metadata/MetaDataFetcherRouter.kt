package com.ps.stashit.core.metadata

import com.ps.stashit.core.database.Platform
import com.ps.stashit.data.domain.SavedItem
import javax.inject.Inject

class MetaDataFetcherRouter @Inject constructor(
    private val youtube: YoutubeMetaDataFetcher,
    private val tiktok: TiktokMetaDataFetcher
) {
    suspend fun fetch(item: SavedItem): RemoteMetaData? = when (item.platform) {
        Platform.YOUTUBE_SHORTS -> youtube.fetch(item.url)
        Platform.TIKTOK -> tiktok.fetch(item.url)
        Platform.INSTAGRAM_REELS -> null //TODO
        Platform.UNKNOWN -> null
    }
}