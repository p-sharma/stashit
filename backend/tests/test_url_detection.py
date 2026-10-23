import pytest
from fastapi import HTTPException

import main


@pytest.mark.parametrize(
    "url",
    [
        "https://www.youtube.com/shorts/abcdefghijk",
        "https://youtube.com/shorts/abcdefghijk",
        "https://m.youtube.com/shorts/abcdefghijk",
        "http://www.youtube.com/shorts/abcdefghijk",
        "youtube.com/shorts/abcdefghijk",
        "https://www.youtube.com/shorts/abc_def-123?feature=share",
        "  https://www.youtube.com/shorts/abcdefghijk  ",
    ],
)
def test_detect_platform_accepts_youtube_shorts(url):
    assert main.detect_platform(url) == "youtube"


@pytest.mark.parametrize(
    "url",
    [
        "https://www.tiktok.com/@user.name/video/7234567890123456789",
        "https://tiktok.com/@user/video/1",
        "https://m.tiktok.com/@user/video/1",
        "tiktok.com/@user/video/1?is_from_webapp=1",
        "https://vm.tiktok.com/ZMabc123/",
        "https://vt.tiktok.com/ZSabc123/",
    ],
)
def test_detect_platform_accepts_tiktok(url):
    assert main.detect_platform(url) == "tiktok"


@pytest.mark.parametrize(
    "url",
    [
        "https://www.youtube.com/watch?v=abcdefghijk",
        "https://youtu.be/abcdefghijk",
        "abcdefghijk",
        "https://www.youtube.com/shorts/tooshort",
        "https://notyoutube.com/shorts/abcdefghijk",
        "https://www.tiktok.com/@user",
        "https://www.tiktok.com/@user/video/notdigits",
        "https://example.com/video/123",
        "",
    ],
)
def test_detect_platform_rejects_unsupported(url):
    with pytest.raises(HTTPException) as exc:
        main.detect_platform(url)
    assert exc.value.status_code == 422


def test_detect_platform_error_includes_url():
    with pytest.raises(HTTPException) as exc:
        main.detect_platform("https://youtu.be/abcdefghijk")
    assert "https://youtu.be/abcdefghijk" in exc.value.detail


@pytest.mark.parametrize(
    "url, expected",
    [
        ("https://www.youtube.com/shorts/abcdefghijk", "abcdefghijk"),
        ("https://m.youtube.com/shorts/abc_def-123?feature=share", "abc_def-123"),
        (" youtube.com/shorts/abcdefghijk ", "abcdefghijk"),
    ],
)
def test_extract_youtube_video_id(url, expected):
    assert main.extract_youtube_video_id(url) == expected


def test_extract_youtube_video_id_rejects_non_shorts():
    with pytest.raises(HTTPException) as exc:
        main.extract_youtube_video_id("https://www.youtube.com/watch?v=abcdefghijk")
    assert exc.value.status_code == 422


@pytest.mark.xfail(
    reason="Known bug: URL patterns are unanchored, so a supported URL embedded "
           "in another host's query/fragment is accepted (TikTok branch passes "
           "the full URL to yt-dlp: SSRF)."
)
@pytest.mark.parametrize(
    "url",
    [
        "https://attacker.example/x?//vm.tiktok.com/abc",
        "https://attacker.example/#//www.tiktok.com/@u/video/123",
        "https://attacker.example/?r=//youtube.com/shorts/abcdefghijk",
    ],
)
def test_detect_platform_rejects_embedded_supported_url(url):
    with pytest.raises(HTTPException):
        main.detect_platform(url)


@pytest.mark.xfail(
    reason="Known bug: Shorts ID pattern has no end boundary, so a 12+ char ID "
           "is silently truncated to 11 chars."
)
def test_extract_youtube_video_id_rejects_overlong_id():
    with pytest.raises(HTTPException):
        main.extract_youtube_video_id("https://www.youtube.com/shorts/abcdefghijkl")
