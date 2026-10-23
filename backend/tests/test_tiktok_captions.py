from types import SimpleNamespace

import pytest
import requests

import main

VTT = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nhello\n\n00:00:01.000 --> 00:00:02.000\nworld\n"


@pytest.fixture
def fake_get(monkeypatch):
    """Patch requests.get. Map URL -> body text, or -> Exception to raise."""
    responses = {}
    calls = []

    def get(url, timeout=None):
        calls.append(SimpleNamespace(url=url, timeout=timeout))
        body = responses[url]
        if isinstance(body, Exception):
            raise body
        return SimpleNamespace(text=body, raise_for_status=lambda: None)

    monkeypatch.setattr(main.requests, "get", get)
    get.responses = responses
    get.calls = calls
    return get


def track(url, ext="vtt"):
    return [{"ext": ext, "url": url}]


def test_returns_none_when_no_tracks(fake_get):
    assert main.fetch_tiktok_captions({}, ["en"]) is None
    assert main.fetch_tiktok_captions({"subtitles": None, "automatic_captions": None}, ["en"]) is None
    assert fake_get.calls == []


def test_manual_track(fake_get):
    fake_get.responses["https://cdn/en.vtt"] = VTT
    info = {"subtitles": {"en": track("https://cdn/en.vtt")}}

    segments, full_text, lang, is_generated = main.fetch_tiktok_captions(info, ["en"])

    assert [s.text for s in segments] == ["hello", "world"]
    assert full_text == "hello world"
    assert lang == "en"
    assert is_generated is False
    assert fake_get.calls[0].timeout == 15


def test_auto_track_marked_generated(fake_get):
    fake_get.responses["https://cdn/auto.vtt"] = VTT
    info = {"automatic_captions": {"en": track("https://cdn/auto.vtt")}}

    *_, is_generated = main.fetch_tiktok_captions(info, ["en"])

    assert is_generated is True


def test_manual_preferred_over_auto_even_in_non_preferred_language(fake_get):
    fake_get.responses["https://cdn/de.vtt"] = VTT
    fake_get.responses["https://cdn/en-auto.vtt"] = VTT
    info = {
        "subtitles": {"de": track("https://cdn/de.vtt")},
        "automatic_captions": {"en": track("https://cdn/en-auto.vtt")},
    }

    _, _, lang, is_generated = main.fetch_tiktok_captions(info, ["en"])

    assert (lang, is_generated) == ("de", False)


def test_language_priority_order(fake_get):
    fake_get.responses["https://cdn/de.vtt"] = VTT
    fake_get.responses["https://cdn/fr.vtt"] = VTT
    info = {"subtitles": {"fr": track("https://cdn/fr.vtt"), "de": track("https://cdn/de.vtt")}}

    _, _, lang, _ = main.fetch_tiktok_captions(info, ["es", "de", "fr"])

    assert lang == "de"


def test_falls_back_to_first_available_language(fake_get):
    fake_get.responses["https://cdn/ja.vtt"] = VTT
    info = {"subtitles": {"ja": track("https://cdn/ja.vtt"), "ko": track("https://cdn/ko.vtt")}}

    _, _, lang, _ = main.fetch_tiktok_captions(info, ["en"])

    assert lang == "ja"


def test_prefers_vtt_or_srt_format(fake_get):
    fake_get.responses["https://cdn/en.srt"] = VTT
    info = {"subtitles": {"en": [
        {"ext": "json3", "url": "https://cdn/en.json3"},
        {"ext": "srt", "url": "https://cdn/en.srt"},
    ]}}

    assert main.fetch_tiktok_captions(info, ["en"]) is not None
    assert [c.url for c in fake_get.calls] == ["https://cdn/en.srt"]


def test_uses_first_format_when_no_vtt_or_srt(fake_get):
    fake_get.responses["https://cdn/en.ttml"] = VTT
    info = {"subtitles": {"en": [
        {"ext": "ttml", "url": "https://cdn/en.ttml"},
        {"ext": "json3", "url": "https://cdn/en.json3"},
    ]}}

    assert main.fetch_tiktok_captions(info, ["en"]) is not None
    assert [c.url for c in fake_get.calls] == ["https://cdn/en.ttml"]


@pytest.mark.parametrize(
    "manual_formats",
    [
        [],
        [{"ext": "vtt"}],
        [{"ext": "vtt", "url": ""}],
    ],
    ids=["no-formats", "missing-url", "empty-url"],
)
def test_unusable_manual_track_falls_through_to_auto(fake_get, manual_formats):
    fake_get.responses["https://cdn/auto.vtt"] = VTT
    info = {
        "subtitles": {"en": manual_formats},
        "automatic_captions": {"en": track("https://cdn/auto.vtt")},
    }

    *_, is_generated = main.fetch_tiktok_captions(info, ["en"])

    assert is_generated is True


def test_download_error_falls_through_to_auto(fake_get):
    fake_get.responses["https://cdn/manual.vtt"] = requests.ConnectionError("boom")
    fake_get.responses["https://cdn/auto.vtt"] = VTT
    info = {
        "subtitles": {"en": track("https://cdn/manual.vtt")},
        "automatic_captions": {"en": track("https://cdn/auto.vtt")},
    }

    *_, is_generated = main.fetch_tiktok_captions(info, ["en"])

    assert is_generated is True


def test_http_error_status_falls_through(monkeypatch):
    def raise_for_status():
        raise requests.HTTPError("403")

    monkeypatch.setattr(
        main.requests, "get",
        lambda url, timeout=None: SimpleNamespace(text=VTT, raise_for_status=raise_for_status),
    )
    info = {"subtitles": {"en": track("https://cdn/en.vtt")}}

    assert main.fetch_tiktok_captions(info, ["en"]) is None


def test_unparseable_track_falls_through_to_auto(fake_get):
    fake_get.responses["https://cdn/manual.vtt"] = "not a caption file"
    fake_get.responses["https://cdn/auto.vtt"] = VTT
    info = {
        "subtitles": {"en": track("https://cdn/manual.vtt")},
        "automatic_captions": {"en": track("https://cdn/auto.vtt")},
    }

    *_, is_generated = main.fetch_tiktok_captions(info, ["en"])

    assert is_generated is True


def test_returns_none_when_every_track_fails(fake_get):
    fake_get.responses["https://cdn/manual.vtt"] = requests.Timeout("slow")
    fake_get.responses["https://cdn/auto.vtt"] = ""
    info = {
        "subtitles": {"en": track("https://cdn/manual.vtt")},
        "automatic_captions": {"en": track("https://cdn/auto.vtt")},
    }

    assert main.fetch_tiktok_captions(info, ["en"]) is None
