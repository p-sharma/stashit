package com.ps.stashit.feature.capture

import com.ps.stashit.core.database.Platform
import java.net.URI
import java.net.URISyntaxException

object ShareTextParser {
    private val urlRegex = Regex("""https?://\S+""")

    data class ParsedShare(
        val url: String,
        val platform: Platform,
        val caption: String?
    )

    fun parse(sharedText: String): ParsedShare? {
        val match = urlRegex.find(sharedText) ?: return null
        val url = match.value.trimEnd('.', ',', ')', ']', '"', '\'')
        val platform = detectPlatform(url)
        // Remove the raw match so punctuation trimmed from the url doesn't end up in the caption.
        val caption = sharedText.removeRange(match.range).trim().ifBlank { null }
        return ParsedShare(url, platform, caption)
    }

    private fun detectPlatform(url: String): Platform {
        val host = try {
            URI(url).host?.lowercase()
        } catch (e: URISyntaxException) {
            null
        } ?: return Platform.UNKNOWN

        return when {
            host.isOnDomain("youtube.com") || host.isOnDomain("youtu.be") -> Platform.YOUTUBE_SHORTS
            host.isOnDomain("tiktok.com") -> Platform.TIKTOK
            host.isOnDomain("instagram.com") -> Platform.INSTAGRAM_REELS
            else -> Platform.UNKNOWN
        }
    }

    private fun String.isOnDomain(domain: String): Boolean = this == domain || endsWith(".$domain")
}
