from types import SimpleNamespace

import pytest
from youtube_transcript_api._errors import (
    NoTranscriptFound,
    TranscriptsDisabled,
    VideoUnavailable,
)

import main

VIDEO_ID = "abcdefghijk"


class FakeTranscript:
    def __init__(self, language_code, is_generated=False, snippets=(), fetch_error=None):
        self.language_code = language_code
        self.is_generated = is_generated
        self.snippets = [SimpleNamespace(text=t, start=s, duration=d) for t, s, d in snippets]
        self.fetch_error = fetch_error

    def fetch(self):
        if self.fetch_error is not None:
            raise self.fetch_error
        return self.snippets


class FakeTranscriptList:
    def __init__(self, transcripts):
        self.transcripts = transcripts

    def find_transcript(self, languages):
        for lang in languages:
            for t in self.transcripts:
                if t.language_code == lang:
                    return t
        raise NoTranscriptFound(VIDEO_ID, languages, None)

    def __iter__(self):
        return iter(self.transcripts)


@pytest.fixture
def fake_api(monkeypatch):
    """Patch YouTubeTranscriptApi. Set `.result` to a FakeTranscriptList or an Exception."""
    state = SimpleNamespace(result=None, listed=[])

    class FakeApi:
        def list(self, video_id):
            state.listed.append(video_id)
            if isinstance(state.result, Exception):
                raise state.result
            return state.result

    monkeypatch.setattr(main, "YouTubeTranscriptApi", FakeApi)
    return state


def test_returns_preferred_language(fake_api):
    fake_api.result = FakeTranscriptList([
        FakeTranscript("de", snippets=[("hallo", 0.0, 1.0)]),
        FakeTranscript("en", is_generated=True, snippets=[("hello", 0.0, 1.0), ("world", 1.0, 2.0)]),
    ])

    segments, full_text, lang, is_generated = main.fetch_youtube_captions(VIDEO_ID, ["en"])

    assert fake_api.listed == [VIDEO_ID]
    assert [(s.text, s.start, s.duration) for s in segments] == [("hello", 0.0, 1.0), ("world", 1.0, 2.0)]
    assert full_text == "hello world"
    assert (lang, is_generated) == ("en", True)


def test_falls_back_to_first_transcript(fake_api):
    fake_api.result = FakeTranscriptList([
        FakeTranscript("ja", snippets=[("konnichiwa", 0.0, 1.0)]),
        FakeTranscript("ko", snippets=[("annyeong", 0.0, 1.0)]),
    ])

    _, full_text, lang, is_generated = main.fetch_youtube_captions(VIDEO_ID, ["en"])

    assert (full_text, lang, is_generated) == ("konnichiwa", "ja", False)


def test_full_text_flattens_newlines_but_segments_keep_them(fake_api):
    fake_api.result = FakeTranscriptList([
        FakeTranscript("en", snippets=[("line one\nline two ", 0.0, 1.0), (" next", 1.0, 1.0)]),
    ])

    segments, full_text, _, _ = main.fetch_youtube_captions(VIDEO_ID, ["en"])

    assert segments[0].text == "line one\nline two "
    assert full_text == "line one line two next"


@pytest.mark.parametrize(
    "error",
    [TranscriptsDisabled(VIDEO_ID), VideoUnavailable(VIDEO_ID), NoTranscriptFound(VIDEO_ID, ["en"], None)],
    ids=["disabled", "unavailable", "not-found"],
)
def test_returns_none_when_list_fails(fake_api, error):
    fake_api.result = error
    assert main.fetch_youtube_captions(VIDEO_ID, ["en"]) is None


def test_returns_none_when_fetch_fails(fake_api):
    fake_api.result = FakeTranscriptList([
        FakeTranscript("en", fetch_error=TranscriptsDisabled(VIDEO_ID)),
    ])
    assert main.fetch_youtube_captions(VIDEO_ID, ["en"]) is None
