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


@pytest.mark.parametrize(
    "url",
    [
        "https://attacker.example/x?//vm.tiktok.com/abc",
        "https://attacker.example/#//www.tiktok.com/@u/video/123",
        "https://attacker.example/?r=//youtube.com/shorts/abcdefghijk",
        "https://attacker.example//vm.tiktok.com/abc",
        "http://169.254.169.254/x?//vm.tiktok.com/",
    ],
)
def test_detect_platform_rejects_embedded_supported_url(url):
    with pytest.raises(HTTPException) as exc:
        main.detect_platform(url)
    assert exc.value.status_code == 422


@pytest.mark.parametrize(
    "url",
    [
        "https://vm.tiktok.com.attacker.example/abc",
        "https://www.tiktok.com.attacker.example/@u/video/1",
        "https://www.youtube.com.attacker.example/shorts/abcdefghijk",
        "https://vm.tiktok.com@attacker.example/abc",
        "https://www.tiktok.com@attacker.example/@u/video/1",
        "https://vm.tiktok.com:8080/abc",
        "https://www.youtube.com:8443/shorts/abcdefghijk",
        "ftp://vm.tiktok.com/abc",
        "file:///vm.tiktok.com/abc",
        "//vm.tiktok.com/abc",
    ],
)
def test_detect_platform_rejects_lookalike_hosts_ports_and_schemes(url):
    with pytest.raises(HTTPException) as exc:
        main.detect_platform(url)
    assert exc.value.status_code == 422


@pytest.mark.parametrize(
    "url, expected",
    [
        ("https://vm.tiktok.com/abc", "https://vm.tiktok.com/abc"),
        ("http://vm.tiktok.com/abc", "https://vm.tiktok.com/abc"),
        ("HTTP://vm.tiktok.com/abc", "https://vm.tiktok.com/abc"),
        ("vm.tiktok.com/abc", "https://vm.tiktok.com/abc"),
        ("  tiktok.com/@u/video/1  ", "https://tiktok.com/@u/video/1"),
        ("ftp://vm.tiktok.com/abc", "ftp://vm.tiktok.com/abc"),
    ],
)
def test_normalize_url(url, expected):
    assert main.normalize_url(url) == expected


def test_extractor_allowlists_load_only_platform_extractors():
    """Uses the real yt-dlp (no network) so an extractor rename upstream
    fails here instead of turning every request into a 404."""
    yt_dlp = pytest.importorskip("yt_dlp")

    def loaded(allowed):
        ydl = yt_dlp.YoutubeDL({"quiet": True, "allowed_extractors": allowed})
        return set(ydl._ies)

    assert loaded(main.TIKTOK_EXTRACTORS) == {"TikTok", "TikTokVM"}
    assert loaded(main.YOUTUBE_EXTRACTORS) == {"Youtube"}


@pytest.mark.xfail(
    reason="Known bug: Shorts ID pattern has no end boundary, so a 12+ char ID "
           "is silently truncated to 11 chars."
)
def test_extract_youtube_video_id_rejects_overlong_id():
    with pytest.raises(HTTPException):
        main.extract_youtube_video_id("https://www.youtube.com/shorts/abcdefghijkl")
