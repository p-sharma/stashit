package com.ps.stashit.feature.capture

import com.ps.stashit.core.database.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTextParserTest {

    private fun parse(text: String) = requireNotNull(ShareTextParser.parse(text)) { "expected a parse result for '$text'" }

    @Test
    fun `returns null when there is no url`() {
        assertNull(ShareTextParser.parse("just some text"))
        assertNull(ShareTextParser.parse(""))
        assertNull(ShareTextParser.parse("www.youtube.com/shorts/abc"))
        assertNull(ShareTextParser.parse("ftp://example.com/file"))
    }

    @Test
    fun `bare url has no caption`() {
        val parsed = parse("https://youtube.com/shorts/abc123")

        assertEquals("https://youtube.com/shorts/abc123", parsed.url)
        assertNull(parsed.caption)
    }

    @Test
    fun `url surrounded by whitespace has no caption`() {
        assertNull(parse("   https://youtube.com/shorts/abc123 \n").caption)
    }

    @Test
    fun `text around the url becomes the caption`() {
        val parsed = parse("Best pasta recipe https://www.tiktok.com/@chef/video/123")

        assertEquals("https://www.tiktok.com/@chef/video/123", parsed.url)
        assertEquals("Best pasta recipe", parsed.caption)
    }

    @Test
    fun `http scheme is accepted`() {
        assertEquals("http://youtu.be/abc", parse("http://youtu.be/abc").url)
    }

    @Test
    fun `query string is preserved in the url`() {
        assertEquals(
            "https://www.instagram.com/reel/xyz/?igsh=abc123",
            parse("https://www.instagram.com/reel/xyz/?igsh=abc123").url
        )
    }

    @Test
    fun `trailing punctuation is trimmed from the url`() {
        listOf(".", ",", ")", "]", "\"", "'", ".)").forEach { suffix ->
            assertEquals(
                "suffix '$suffix'",
                "https://youtu.be/abc",
                parse("https://youtu.be/abc$suffix").url
            )
        }
    }

    @Test
    fun `url wrapped in parentheses is extracted`() {
        assertEquals("https://youtu.be/abc", parse("(https://youtu.be/abc)").url)
    }

    @Test
    fun `only the first url is used`() {
        val parsed = parse("https://youtu.be/first https://www.tiktok.com/@a/video/2")

        assertEquals("https://youtu.be/first", parsed.url)
        assertEquals(Platform.YOUTUBE_SHORTS, parsed.platform)
        assertEquals("https://www.tiktok.com/@a/video/2", parsed.caption)
    }

    @Test
    fun `detects platform from host`() {
        val cases = mapOf(
            "https://www.youtube.com/shorts/abc" to Platform.YOUTUBE_SHORTS,
            "https://m.youtube.com/shorts/abc" to Platform.YOUTUBE_SHORTS,
            "https://youtu.be/abc" to Platform.YOUTUBE_SHORTS,
            "https://www.tiktok.com/@a/video/1" to Platform.TIKTOK,
            "https://vm.tiktok.com/ZMabc/" to Platform.TIKTOK,
            "https://www.instagram.com/reel/xyz/" to Platform.INSTAGRAM_REELS,
            "https://vimeo.com/123" to Platform.UNKNOWN
        )
        cases.forEach { (url, expected) ->
            assertEquals(url, expected, parse(url).platform)
        }
    }

    /**
     * BUG: platform detection uses String.contains on the whole URL, so a link to another
     * host that mentions youtube.com anywhere (path, query) is classified as YouTube and
     * sent to the YouTube oEmbed endpoint.
     */
    @Test
    fun `platform detection uses the host, not any substring of the url`() {
        assertEquals(Platform.UNKNOWN, parse("https://example.com/?ref=youtube.com").platform)
        assertEquals(Platform.UNKNOWN, parse("https://notyoutube.com/shorts/abc").platform)
        assertEquals(Platform.UNKNOWN, parse("https://example.com/tiktok.com/video").platform)
    }

    /**
     * BUG: trailing punctuation is trimmed from the url but the caption is built by
     * removing the trimmed url from the original text, so the punctuation is left behind
     * as the caption (or as noise appended to it).
     */
    @Test
    fun `punctuation trimmed from the url does not leak into the caption`() {
        assertNull(parse("https://youtu.be/abc.").caption)
        assertEquals("Watch this", parse("Watch this https://youtu.be/abc.").caption)
    }
}
