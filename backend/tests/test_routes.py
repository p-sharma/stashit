from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from youtube_transcript_api._errors import RequestBlocked

import main
from main import CaptionSegment

SHORTS_URL = "https://www.youtube.com/shorts/abcdefghijk"
TIKTOK_URL = "https://www.tiktok.com/@user/video/7234567890123456789"

SEGMENTS = [CaptionSegment(text="hello", start=0.0, duration=1.0)]


class Recorder:
    """Callable stand-in that records its arguments and returns a fixed value."""

    def __init__(self, result=None):
        self.result = result
        self.calls = []

    def __call__(self, *args):
        self.calls.append(args)
        return self.result


@pytest.fixture
def youtube_captions(monkeypatch):
    rec = Recorder(result=(SEGMENTS, "hello", "en", True))
    monkeypatch.setattr(main, "fetch_youtube_captions", rec)
    return rec


@pytest.fixture
def tiktok_captions(monkeypatch):
    rec = Recorder(result=(SEGMENTS, "hello", "en", False))
    monkeypatch.setattr(main, "fetch_tiktok_captions", rec)
    return rec


@pytest.fixture
def audio(monkeypatch):
    rec = Recorder(result=([CaptionSegment(text="spoken", start=0.0, duration=2.0)], "spoken", "en"))
    monkeypatch.setattr(main, "transcribe_via_audio", rec)
    return rec


def test_health(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


# Request validation

def test_missing_url_is_422(client):
    assert client.post("/captions", json={}).status_code == 422


@pytest.mark.parametrize(
    "url",
    ["https://www.youtube.com/watch?v=abcdefghijk", "https://youtu.be/abcdefghijk", "abcdefghijk"],
)
def test_unsupported_url_is_422_without_lookup(client, fake_yt_dlp, url):
    resp = client.post("/captions", json={"url": url})

    assert resp.status_code == 422
    assert fake_yt_dlp.calls == []


# YouTube

def test_youtube_captions(client, fake_yt_dlp, youtube_captions, audio):
    fake_yt_dlp.info = {"duration": 42}

    resp = client.post("/captions", json={"url": SHORTS_URL})

    assert resp.status_code == 200
    assert resp.json() == {
        "platform": "youtube",
        "video_id": "abcdefghijk",
        "source": "youtube_captions",
        "duration_seconds": 42.0,
        "language": "en",
        "is_auto_generated": True,
        "full_text": "hello",
        "segments": [{"text": "hello", "start": 0.0, "duration": 1.0}],
    }
    assert youtube_captions.calls == [("abcdefghijk", ["en"])]
    assert audio.calls == []


def test_youtube_passes_languages(client, fake_yt_dlp, youtube_captions):
    fake_yt_dlp.info = {"duration": 42}

    client.post("/captions", json={"url": SHORTS_URL, "languages": ["de", "en"]})

    assert youtube_captions.calls == [("abcdefghijk", ["de", "en"])]


def test_youtube_duration_at_limit_is_accepted(client, fake_yt_dlp, youtube_captions):
    fake_yt_dlp.info = {"duration": main.MAX_DURATION_SECONDS}

    assert client.post("/captions", json={"url": SHORTS_URL}).status_code == 200


def test_youtube_over_limit_is_422_before_caption_work(client, fake_yt_dlp, youtube_captions, audio):
    fake_yt_dlp.info = {"duration": main.MAX_DURATION_SECONDS + 1}

    resp = client.post("/captions", json={"url": SHORTS_URL})

    assert resp.status_code == 422
    assert "exceeds" in resp.json()["detail"]
    assert youtube_captions.calls == []
    assert audio.calls == []


def test_youtube_missing_duration_is_502(client, fake_yt_dlp, youtube_captions):
    fake_yt_dlp.info = {}

    assert client.post("/captions", json={"url": SHORTS_URL}).status_code == 502
    assert youtube_captions.calls == []


def test_youtube_lookup_failure_is_404(client, fake_yt_dlp, youtube_captions):
    fake_yt_dlp.error = RuntimeError("Video unavailable")

    assert client.post("/captions", json={"url": SHORTS_URL}).status_code == 404


def test_youtube_no_captions_fallback_disabled_is_404(client, fake_yt_dlp, youtube_captions, audio):
    fake_yt_dlp.info = {"duration": 42}
    youtube_captions.result = None

    resp = client.post("/captions", json={"url": SHORTS_URL, "allow_audio_fallback": False})

    assert resp.status_code == 404
    assert audio.calls == []


def test_youtube_no_captions_falls_back_to_audio(client, fake_yt_dlp, youtube_captions, audio):
    fake_yt_dlp.info = {"duration": 42}
    youtube_captions.result = None

    resp = client.post("/captions", json={"url": SHORTS_URL, "languages": ["en"]})

    assert resp.status_code == 200
    body = resp.json()
    assert body["source"] == "whisper_transcription"
    assert body["is_auto_generated"] is None
    assert body["full_text"] == "spoken"
    assert audio.calls == [("https://www.youtube.com/watch?v=abcdefghijk", "abcdefghijk", ["en"])]


def test_youtube_audio_fallback_end_to_end(client, fake_yt_dlp, fake_whisper, youtube_captions):
    fake_yt_dlp.info = {"duration": 42}
    fake_whisper.segments = [(0.0, 1.0, "real path")]
    youtube_captions.result = None

    resp = client.post("/captions", json={"url": SHORTS_URL})

    assert resp.status_code == 200
    assert resp.json()["full_text"] == "real path"
    assert [c.download for c in fake_yt_dlp.calls] == [False, True]


# TikTok

def test_tiktok_captions(client, fake_yt_dlp, tiktok_captions, audio):
    fake_yt_dlp.info = {"id": "7234567890123456789", "duration": 15}

    resp = client.post("/captions", json={"url": f"  {TIKTOK_URL}  "})

    assert resp.status_code == 200
    assert resp.json() == {
        "platform": "tiktok",
        "video_id": "7234567890123456789",
        "source": "tiktok_captions",
        "duration_seconds": 15.0,
        "language": "en",
        "is_auto_generated": False,
        "full_text": "hello",
        "segments": [{"text": "hello", "start": 0.0, "duration": 1.0}],
    }
    assert [c.url for c in fake_yt_dlp.calls] == [TIKTOK_URL]
    assert tiktok_captions.calls == [(fake_yt_dlp.info, ["en"])]
    assert audio.calls == []


def test_tiktok_missing_id_is_unknown(client, fake_yt_dlp, tiktok_captions):
    fake_yt_dlp.info = {"duration": 15}

    assert client.post("/captions", json={"url": TIKTOK_URL}).json()["video_id"] == "unknown"


def test_tiktok_duration_at_limit_is_accepted(client, fake_yt_dlp, tiktok_captions):
    fake_yt_dlp.info = {"id": "1", "duration": main.MAX_DURATION_SECONDS}

    assert client.post("/captions", json={"url": TIKTOK_URL}).status_code == 200


def test_tiktok_over_limit_is_422(client, fake_yt_dlp, tiktok_captions, audio):
    fake_yt_dlp.info = {"id": "1", "duration": main.MAX_DURATION_SECONDS + 1}

    resp = client.post("/captions", json={"url": TIKTOK_URL})

    assert resp.status_code == 422
    assert tiktok_captions.calls == []
    assert audio.calls == []


def test_tiktok_missing_duration_is_502(client, fake_yt_dlp, tiktok_captions):
    fake_yt_dlp.info = {"id": "1"}

    assert client.post("/captions", json={"url": TIKTOK_URL}).status_code == 502
    assert tiktok_captions.calls == []


def test_tiktok_lookup_failure_is_404(client, fake_yt_dlp):
    fake_yt_dlp.error = RuntimeError("blocked")

    assert client.post("/captions", json={"url": TIKTOK_URL}).status_code == 404


def test_tiktok_no_captions_fallback_disabled_is_404(client, fake_yt_dlp, tiktok_captions, audio):
    fake_yt_dlp.info = {"id": "1", "duration": 15}
    tiktok_captions.result = None

    resp = client.post("/captions", json={"url": TIKTOK_URL, "allow_audio_fallback": False})

    assert resp.status_code == 404
    assert audio.calls == []


def test_tiktok_fallback_uses_canonical_webpage_url(client, fake_yt_dlp, tiktok_captions, audio):
    short_link = "https://vm.tiktok.com/ZMabc123/"
    fake_yt_dlp.info = {"id": "1", "duration": 15, "webpage_url": TIKTOK_URL}
    tiktok_captions.result = None

    resp = client.post("/captions", json={"url": short_link, "languages": ["en"]})

    assert resp.status_code == 200
    assert resp.json()["source"] == "whisper_transcription"
    assert audio.calls == [(TIKTOK_URL, "1", ["en"])]


def test_tiktok_fallback_without_webpage_url_uses_request_url(client, fake_yt_dlp, tiktok_captions, audio):
    fake_yt_dlp.info = {"id": "1", "duration": 15}
    tiktok_captions.result = None

    client.post("/captions", json={"url": TIKTOK_URL})

    assert audio.calls[0][0] == TIKTOK_URL


# Known bugs

@pytest.mark.xfail(
    reason="Known bug (SSRF): an attacker URL containing '//vm.tiktok.com/' is "
           "accepted and passed verbatim to yt-dlp."
)
def test_embedded_tiktok_url_is_rejected_before_lookup(client, fake_yt_dlp, tiktok_captions):
    fake_yt_dlp.info = {"id": "1", "duration": 15}

    resp = client.post("/captions", json={"url": "http://169.254.169.254/x?//vm.tiktok.com/"})

    assert fake_yt_dlp.calls == []
    assert resp.status_code == 422


@pytest.mark.xfail(
    reason="Known bug: RequestBlocked/IpBlocked from youtube-transcript-api are "
           "not caught and surface as a 500."
)
def test_youtube_request_blocked_is_not_500(fake_yt_dlp, audio, monkeypatch):
    fake_yt_dlp.info = {"duration": 42}

    class BlockedApi:
        def list(self, video_id):
            raise RequestBlocked(video_id)

    monkeypatch.setattr(main, "YouTubeTranscriptApi", BlockedApi)
    client = TestClient(main.app, raise_server_exceptions=False)

    assert client.post("/captions", json={"url": SHORTS_URL}).status_code != 500
