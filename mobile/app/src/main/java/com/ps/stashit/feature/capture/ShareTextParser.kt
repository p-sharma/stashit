package com.ps.stashit.feature.capture

import android.util.Log
import com.ps.stashit.core.database.Platform

object ShareTextParser {
    private val urlRegex = Regex("""https?://\S+""")

    data class ParsedShare(
        val url: String,
        val platform: Platform,
        val caption: String?
    )

    fun parse(sharedText: String): ParsedShare? {
        val url = urlRegex.find(sharedText)?.value?.trimEnd(
            '.', ',', ')', ']', '"', '\''
        ) ?: return null
        val platform = detectPlatform(url)
        val caption = sharedText.replace(url, "").trim().ifBlank { null }
        return ParsedShare(url, platform, caption)
    }

    private fun detectPlatform(url: String): Platform = when {
        url.contains("youtube.com") || url.contains("youtu.be") -> Platform.YOUTUBE_SHORTS
        url.contains("tiktok.com") -> Platform.TIKTOK
        url.contains("instagram.com") -> Platform.INSTAGRAM_REELS
        else -> Platform.UNKNOWN
    }
}