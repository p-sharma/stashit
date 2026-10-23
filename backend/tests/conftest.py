"""Shared fixtures.

yt-dlp and faster-whisper are imported inside the functions that use them,
so they are faked by installing stand-in modules into sys.modules rather
than patching attributes on main. Setting a sys.modules entry to None makes
the import raise ImportError, which is how the "not installed" paths are
exercised.
"""

import sys
import types
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

import main


class FakeYtDlp:
    """Records every extract_info call and returns `info`, or raises `error`.

    On download=True it writes a file named after yt-dlp's outtmpl with
    `download_ext` substituted, mimicking the real post-processed output.
    Set `download_ext` to None to simulate a download that produced nothing.
    """

    def __init__(self):
        self.info = {}
        self.error = None
        self.download_ext = "mp3"
        self.calls = []

    def module(self):
        fake = self

        class YoutubeDL:
            def __init__(self, opts):
                self.opts = opts

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def extract_info(self, url, download=False):
                fake.calls.append(SimpleNamespace(url=url, download=download, opts=self.opts))
                if fake.error is not None:
                    raise fake.error
                if download and fake.download_ext:
                    out = self.opts["outtmpl"].replace("%(ext)s", fake.download_ext)
                    Path(out).write_bytes(b"fake audio")
                return fake.info

        mod = types.ModuleType("yt_dlp")
        mod.YoutubeDL = YoutubeDL
        return mod


class FakeWhisper:
    """Stand-in for faster_whisper. `segments` is a list of (start, end, text)."""

    def __init__(self):
        self.segments = []
        self.language = "en"
        self.model_args = None
        self.calls = []

    def module(self):
        fake = self

        class WhisperModel:
            def __init__(self, size, device, compute_type):
                fake.model_args = (size, device, compute_type)

            def transcribe(self, audio_path, language=None):
                fake.calls.append(SimpleNamespace(audio_path=audio_path, language=language))
                segs = (SimpleNamespace(start=s, end=e, text=t) for s, e, t in fake.segments)
                return segs, SimpleNamespace(language=fake.language)

        mod = types.ModuleType("faster_whisper")
        mod.WhisperModel = WhisperModel
        return mod


@pytest.fixture
def fake_yt_dlp(monkeypatch):
    fake = FakeYtDlp()
    monkeypatch.setitem(sys.modules, "yt_dlp", fake.module())
    return fake


@pytest.fixture
def fake_whisper(monkeypatch):
    fake = FakeWhisper()
    monkeypatch.setitem(sys.modules, "faster_whisper", fake.module())
    return fake


@pytest.fixture
def client():
    return TestClient(main.app)
