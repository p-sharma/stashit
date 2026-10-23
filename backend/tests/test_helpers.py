import sys
from pathlib import Path

import pytest
from fastapi import HTTPException

import main


# _yt_dlp_extract_info

def test_extract_info_returns_metadata_without_download(fake_yt_dlp):
    fake_yt_dlp.info = {"id": "1", "duration": 30}

    assert main._yt_dlp_extract_info("https://x") == {"id": "1", "duration": 30}

    [call] = fake_yt_dlp.calls
    assert call.url == "https://x"
    assert call.download is False
    assert call.opts["skip_download"] is True
    assert call.opts["noplaylist"] is True


def test_extract_info_lookup_error_is_404(fake_yt_dlp):
    fake_yt_dlp.error = RuntimeError("private video")

    with pytest.raises(HTTPException) as exc:
        main._yt_dlp_extract_info("https://x")

    assert exc.value.status_code == 404
    assert "private video" in exc.value.detail


def test_extract_info_without_yt_dlp_is_500(monkeypatch):
    monkeypatch.setitem(sys.modules, "yt_dlp", None)

    with pytest.raises(HTTPException) as exc:
        main._yt_dlp_extract_info("https://x")

    assert exc.value.status_code == 500


# get_youtube_duration

def test_get_youtube_duration_uses_watch_url(fake_yt_dlp):
    fake_yt_dlp.info = {"duration": 42}

    assert main.get_youtube_duration("abcdefghijk") == 42.0
    assert fake_yt_dlp.calls[0].url == "https://www.youtube.com/watch?v=abcdefghijk"


def test_get_youtube_duration_missing_is_502(fake_yt_dlp):
    fake_yt_dlp.info = {"id": "abcdefghijk"}

    with pytest.raises(HTTPException) as exc:
        main.get_youtube_duration("abcdefghijk")

    assert exc.value.status_code == 502


# _download_audio

def test_download_audio_returns_file_in_tmpdir(fake_yt_dlp, tmp_path):
    path = main._download_audio("https://x", "vid123", str(tmp_path))

    assert Path(path) == tmp_path / "vid123.mp3"
    assert Path(path).exists()
    [call] = fake_yt_dlp.calls
    assert call.download is True
    assert call.opts["format"] == "bestaudio/best"
    assert call.opts["outtmpl"] == str(tmp_path / "vid123.%(ext)s")
    assert call.opts["postprocessors"][0]["preferredcodec"] == "mp3"


def test_download_audio_error_is_502(fake_yt_dlp, tmp_path):
    fake_yt_dlp.error = RuntimeError("403 Forbidden")

    with pytest.raises(HTTPException) as exc:
        main._download_audio("https://x", "vid123", str(tmp_path))

    assert exc.value.status_code == 502
    assert "403 Forbidden" in exc.value.detail


def test_download_audio_no_file_is_502(fake_yt_dlp, tmp_path):
    fake_yt_dlp.download_ext = None

    with pytest.raises(HTTPException) as exc:
        main._download_audio("https://x", "vid123", str(tmp_path))

    assert exc.value.status_code == 502


def test_download_audio_without_yt_dlp_is_500(monkeypatch, tmp_path):
    monkeypatch.setitem(sys.modules, "yt_dlp", None)

    with pytest.raises(HTTPException) as exc:
        main._download_audio("https://x", "vid123", str(tmp_path))

    assert exc.value.status_code == 500


# _transcribe_audio_file

def test_transcribe_audio_file(fake_whisper):
    fake_whisper.segments = [(0.0, 1.5, " hello "), (1.5, 4.0, "world ")]
    fake_whisper.language = "en"

    segments, full_text, language = main._transcribe_audio_file("/a.mp3", ["en", "de"])

    assert [(s.text, s.start, s.duration) for s in segments] == [
        ("hello", 0.0, 1.5),
        ("world", 1.5, 2.5),
    ]
    assert full_text == "hello world"
    assert language == "en"
    assert fake_whisper.model_args == ("base", "cpu", "int8")
    assert fake_whisper.calls[0].audio_path == "/a.mp3"
    assert fake_whisper.calls[0].language == "en"


def test_transcribe_audio_file_without_languages_has_no_hint(fake_whisper):
    fake_whisper.language = "fr"

    segments, full_text, language = main._transcribe_audio_file("/a.mp3", [])

    assert fake_whisper.calls[0].language is None
    assert (segments, full_text, language) == ([], "", "fr")


def test_transcribe_audio_file_without_whisper_is_500(monkeypatch):
    monkeypatch.setitem(sys.modules, "faster_whisper", None)

    with pytest.raises(HTTPException) as exc:
        main._transcribe_audio_file("/a.mp3", ["en"])

    assert exc.value.status_code == 500


# transcribe_via_audio

def test_transcribe_via_audio_end_to_end_cleans_up(fake_yt_dlp, fake_whisper):
    fake_whisper.segments = [(0.0, 1.0, "hi")]

    segments, full_text, language = main.transcribe_via_audio("https://x", "vid123", ["en"])

    assert full_text == "hi"
    assert language == "en"
    audio_path = Path(fake_whisper.calls[0].audio_path)
    assert audio_path.name == "vid123.mp3"
    assert not audio_path.parent.exists()
