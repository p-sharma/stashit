"""
Short-Form Video Caption Service (YouTube Shorts + TikTok)
=============================================================

POST /captions  { "url": "https://www.youtube.com/shorts/..." }
POST /captions  { "url": "https://www.tiktok.com/@user/video/..." }

Scope: this service only serves short-form video.
  - The URL must be a YouTube Shorts link (youtube.com/shorts/VIDEO_ID,
    m.youtube.com/shorts/VIDEO_ID) or a TikTok video link
    (tiktok.com/@user/video/ID, vm.tiktok.com/..., vt.tiktok.com/...).
    Any other URL shape (regular /watch, youtu.be, bare IDs, etc.) is
    rejected — the request has to identify itself as one of these two.
  - The video must also be <= MAX_DURATION_SECONDS (default 120s). Longer
    videos are rejected before any caption or audio work is done.

Strategy, per platform:
  YouTube Shorts:
    1. Validate URL shape, look up duration via yt-dlp metadata (no
       download). Reject if over the limit.
    2. Try youtube-transcript-api for an existing caption track.
    3. If none exists, fall back to downloading audio (yt-dlp) and
       transcribing locally with faster-whisper.

  TikTok:
    1. Validate URL shape, then do a single yt-dlp metadata lookup
       (no download) that gives duration + any available caption tracks.
    2. Reject if duration is over the limit.
    3. If TikTok published a caption/subtitle track (manual or auto),
       fetch and parse it — no audio download needed.
    4. If none exists, fall back to downloading audio (yt-dlp) and
       transcribing locally with faster-whisper.

Run:
    pip install -r requirements.txt
    uvicorn main:app --reload --port 8000
"""

import logging
import re
import tempfile
from pathlib import Path
from typing import Optional

import requests
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from youtube_transcript_api import YouTubeTranscriptApi
from youtube_transcript_api._errors import (
    TranscriptsDisabled,
    NoTranscriptFound,
    VideoUnavailable,
)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("caption_service")

app = FastAPI(title="Short-Form Video Caption Service", version="2.0.0")

# Hard cap on video length this service will process, in seconds.
MAX_DURATION_SECONDS = 120

# --------------------------------------------------------------------------
# Request / response models
# --------------------------------------------------------------------------

class CaptionRequest(BaseModel):
    url: str = Field(
        ...,
        description="A YouTube Shorts URL or a TikTok video URL",
    )
    languages: list[str] = Field(
        default=["en"],
        description="Preferred language codes in priority order, e.g. ['en', 'de']",
    )
    allow_audio_fallback: bool = Field(
        default=True,
        description="If no caption track exists, download audio and transcribe it locally",
    )


class CaptionSegment(BaseModel):
    text: str
    start: float
    duration: float


class CaptionResponse(BaseModel):
    platform: str  # "youtube" | "tiktok"
    video_id: str
    source: str  # "youtube_captions" | "tiktok_captions" | "whisper_transcription"
    duration_seconds: float
    language: Optional[str] = None
    is_auto_generated: Optional[bool] = None
    full_text: str
    segments: list[CaptionSegment]


# --------------------------------------------------------------------------
# URL / platform detection
# --------------------------------------------------------------------------

YOUTUBE_SHORTS_RE = re.compile(
    r"(?:^|//)(?:www\.|m\.)?youtube\.com/shorts/([A-Za-z0-9_-]{11})"
)

# Canonical TikTok video URLs: tiktok.com/@user/video/1234567890123
TIKTOK_CANONICAL_RE = re.compile(
    r"(?:^|//)(?:www\.|m\.)?tiktok\.com/@[^/]+/video/(\d+)"
)

# Short links that redirect to the canonical URL: vm.tiktok.com/XXXX,
# vt.tiktok.com/XXXX. These don't carry the numeric ID in the URL itself,
# so we can't fully validate/extract without resolving them via yt-dlp.
TIKTOK_SHORT_LINK_RE = re.compile(
    r"(?:^|//)(?:vm|vt)\.tiktok\.com/"
)


def detect_platform(url: str) -> str:
    """Return 'youtube' or 'tiktok' based on URL shape, or reject."""
    url = url.strip()
    if YOUTUBE_SHORTS_RE.search(url):
        return "youtube"
    if TIKTOK_CANONICAL_RE.search(url) or TIKTOK_SHORT_LINK_RE.search(url):
        return "tiktok"
    raise HTTPException(
        status_code=422,
        detail=(
            "Only YouTube Shorts URLs (e.g. https://www.youtube.com/shorts/VIDEO_ID) "
            "and TikTok video URLs (e.g. https://www.tiktok.com/@user/video/1234567890123, "
            "or vm.tiktok.com/vt.tiktok.com short links) are supported. "
            f"Got: {url}"
        ),
    )


def extract_youtube_video_id(url: str) -> str:
    match = YOUTUBE_SHORTS_RE.search(url.strip())
    if not match:
        raise HTTPException(status_code=422, detail=f"Not a valid YouTube Shorts URL: {url}")
    return match.group(1)


# --------------------------------------------------------------------------
# yt-dlp helpers (shared)
# --------------------------------------------------------------------------

def _yt_dlp_extract_info(url: str) -> dict:
    """Metadata-only lookup (no download) via yt-dlp. Works for both
    YouTube and TikTok URLs."""
    try:
        import yt_dlp
    except ImportError:
        raise HTTPException(status_code=500, detail="yt-dlp is not installed.")

    ydl_opts = {"quiet": True, "skip_download": True, "noplaylist": True}
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            return ydl.extract_info(url, download=False)
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Could not look up video: {e}")


def _download_audio(url: str, video_id: str, tmpdir: str) -> str:
    """Download best audio for a video URL into tmpdir, return the file path."""
    try:
        import yt_dlp
    except ImportError:
        raise HTTPException(status_code=500, detail="yt-dlp is not installed; audio fallback unavailable.")

    out_template = str(Path(tmpdir) / f"{video_id}.%(ext)s")
    ydl_opts = {
        "format": "bestaudio/best",
        "outtmpl": out_template,
        "postprocessors": [{
            "key": "FFmpegExtractAudio",
            "preferredcodec": "mp3",
            "preferredquality": "192",
        }],
        "quiet": True,
        "noplaylist": True,
    }
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.extract_info(url, download=True)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Failed to download audio: {e}")

    audio_files = list(Path(tmpdir).glob(f"{video_id}.*"))
    if not audio_files:
        raise HTTPException(status_code=502, detail="Audio download produced no file.")
    return str(audio_files[0])


def _transcribe_audio_file(audio_path: str, languages: list[str]) -> tuple[list[CaptionSegment], str, str]:
    """Run faster-whisper on a local audio file. Returns (segments, full_text, language)."""
    try:
        from faster_whisper import WhisperModel
    except ImportError:
        raise HTTPException(
            status_code=500,
            detail="faster-whisper is not installed; audio fallback unavailable. "
                   "Install with: pip install faster-whisper",
        )

    # "base" is a reasonable speed/accuracy tradeoff; swap for "small"/"medium"
    # for better accuracy at the cost of speed.
    model = WhisperModel("base", device="cpu", compute_type="int8")
    lang_hint = languages[0] if languages else None
    segments_iter, info = model.transcribe(audio_path, language=lang_hint)

    segments = []
    text_parts = []
    for seg in segments_iter:
        segments.append(
            CaptionSegment(text=seg.text.strip(), start=seg.start, duration=seg.end - seg.start)
        )
        text_parts.append(seg.text.strip())

    return segments, " ".join(text_parts).strip(), info.language


def transcribe_via_audio(video_url: str, video_id: str, languages: list[str]) -> tuple[list[CaptionSegment], str, Optional[str]]:
    with tempfile.TemporaryDirectory() as tmpdir:
        audio_path = _download_audio(video_url, video_id, tmpdir)
        segments, full_text, language = _transcribe_audio_file(audio_path, languages)
        return segments, full_text, language


# --------------------------------------------------------------------------
# YouTube-specific
# --------------------------------------------------------------------------

def get_youtube_duration(video_id: str) -> float:
    info = _yt_dlp_extract_info(f"https://www.youtube.com/watch?v={video_id}")
    duration = info.get("duration")
    if duration is None:
        raise HTTPException(status_code=502, detail="Video duration is unavailable; cannot verify length.")
    return float(duration)


def fetch_youtube_captions(video_id: str, languages: list[str]) -> Optional[tuple[list[CaptionSegment], str, str, bool]]:
    """Returns (segments, full_text, language_code, is_generated), or None if unavailable."""
    ytt_api = YouTubeTranscriptApi()
    try:
        transcript_list = ytt_api.list(video_id)
        try:
            transcript = transcript_list.find_transcript(languages)
        except NoTranscriptFound:
            transcript = next(iter(transcript_list))
        fetched = transcript.fetch()
    except (TranscriptsDisabled, NoTranscriptFound, VideoUnavailable):
        return None

    segments = [
        CaptionSegment(text=s.text, start=s.start, duration=s.duration) for s in fetched
    ]
    full_text = " ".join(s.text.replace("\n", " ").strip() for s in segments).strip()
    return segments, full_text, transcript.language_code, transcript.is_generated


# --------------------------------------------------------------------------
# TikTok-specific
# --------------------------------------------------------------------------

# Matches "00:00:01.234" or "00:00:01,234" timestamps in a WEBVTT/SRT file.
_VTT_TIME_RE = re.compile(r"(\d{2}):(\d{2}):(\d{2})[.,](\d{3})")


def _vtt_timestamp_to_seconds(ts: str) -> float:
    m = _VTT_TIME_RE.match(ts)
    if not m:
        return 0.0
    h, mnt, s, ms = (int(x) for x in m.groups())
    return h * 3600 + mnt * 60 + s + ms / 1000


def parse_vtt(content: str) -> list[CaptionSegment]:
    """Minimal WEBVTT/SRT parser: extracts (text, start, duration) cues."""
    segments = []
    blocks = re.split(r"\n\s*\n", content.strip())
    for block in blocks:
        lines = [ln for ln in block.splitlines() if ln.strip()]
        if not lines:
            continue
        cue_line_idx = None
        for i, line in enumerate(lines):
            if "-->" in line:
                cue_line_idx = i
                break
        if cue_line_idx is None:
            continue
        start_str, end_str = [p.strip() for p in lines[cue_line_idx].split("-->")[:2]]
        start_str = start_str.split(" ")[0]
        end_str = end_str.split(" ")[0]
        start = _vtt_timestamp_to_seconds(start_str)
        end = _vtt_timestamp_to_seconds(end_str)
        text = " ".join(lines[cue_line_idx + 1:]).strip()
        # Strip any inline VTT formatting tags like <b>, <00:00:01.000>, etc.
        text = re.sub(r"<[^>]+>", "", text).strip()
        if not text:
            continue
        segments.append(CaptionSegment(text=text, start=start, duration=max(end - start, 0.0)))
    return segments


def fetch_tiktok_captions(info: dict, languages: list[str]) -> Optional[tuple[list[CaptionSegment], str, str, bool]]:
    """Look for a manual or auto-generated caption track in yt-dlp's
    metadata for a TikTok video. Returns (segments, full_text, language, is_generated) or None."""
    manual = info.get("subtitles") or {}
    auto = info.get("automatic_captions") or {}

    def pick_track(tracks: dict) -> Optional[tuple[str, dict]]:
        if not tracks:
            return None
        for lang in languages:
            if lang in tracks:
                return lang, tracks[lang]
        # fall back to first available language
        lang = next(iter(tracks))
        return lang, tracks[lang]

    for tracks, is_generated in ((manual, False), (auto, True)):
        picked = pick_track(tracks)
        if not picked:
            continue
        lang, formats = picked
        # Prefer a vtt/srt-like format; otherwise take whatever's first.
        fmt = next((f for f in formats if f.get("ext") in ("vtt", "srt")), None) or (formats[0] if formats else None)
        if not fmt or not fmt.get("url"):
            continue
        try:
            resp = requests.get(fmt["url"], timeout=15)
            resp.raise_for_status()
        except Exception as e:
            logger.warning("Failed to download TikTok caption track: %s", e)
            continue
        segments = parse_vtt(resp.text)
        if not segments:
            continue
        full_text = " ".join(s.text for s in segments).strip()
        return segments, full_text, lang, is_generated

    return None


# --------------------------------------------------------------------------
# Routes
# --------------------------------------------------------------------------

@app.post("/captions", response_model=CaptionResponse)
def get_captions(req: CaptionRequest):
    url = req.url.strip()
    platform = detect_platform(url)

    if platform == "youtube":
        video_id = extract_youtube_video_id(url)
        logger.info("[youtube] Checking duration for video_id=%s", video_id)
        duration = get_youtube_duration(video_id)
        if duration > MAX_DURATION_SECONDS:
            raise HTTPException(
                status_code=422,
                detail=f"Video is {duration:.0f}s long, exceeds the {MAX_DURATION_SECONDS}s limit.",
            )

        result = fetch_youtube_captions(video_id, req.languages)
        if result is not None:
            segments, full_text, language, is_generated = result
            return CaptionResponse(
                platform="youtube",
                video_id=video_id,
                source="youtube_captions",
                duration_seconds=duration,
                language=language,
                is_auto_generated=is_generated,
                full_text=full_text,
                segments=segments,
            )

        if not req.allow_audio_fallback:
            raise HTTPException(status_code=404, detail="No captions available and audio fallback disabled.")

        logger.info("[youtube] Falling back to audio transcription for %s", video_id)
        segments, full_text, language = transcribe_via_audio(
            f"https://www.youtube.com/watch?v={video_id}", video_id, req.languages
        )
        return CaptionResponse(
            platform="youtube",
            video_id=video_id,
            source="whisper_transcription",
            duration_seconds=duration,
            language=language,
            is_auto_generated=None,
            full_text=full_text,
            segments=segments,
        )

    else:  # platform == "tiktok"
        logger.info("[tiktok] Looking up metadata for %s", url)
        info = _yt_dlp_extract_info(url)
        video_id = str(info.get("id") or "unknown")
        duration = info.get("duration")
        if duration is None:
            raise HTTPException(status_code=502, detail="Video duration is unavailable; cannot verify length.")
        duration = float(duration)
        if duration > MAX_DURATION_SECONDS:
            raise HTTPException(
                status_code=422,
                detail=f"Video is {duration:.0f}s long, exceeds the {MAX_DURATION_SECONDS}s limit.",
            )

        result = fetch_tiktok_captions(info, req.languages)
        if result is not None:
            segments, full_text, language, is_generated = result
            return CaptionResponse(
                platform="tiktok",
                video_id=video_id,
                source="tiktok_captions",
                duration_seconds=duration,
                language=language,
                is_auto_generated=is_generated,
                full_text=full_text,
                segments=segments,
            )

        if not req.allow_audio_fallback:
            raise HTTPException(status_code=404, detail="No captions available and audio fallback disabled.")

        logger.info("[tiktok] Falling back to audio transcription for %s", video_id)
        canonical_url = info.get("webpage_url") or url
        segments, full_text, language = transcribe_via_audio(canonical_url, video_id, req.languages)
        return CaptionResponse(
            platform="tiktok",
            video_id=video_id,
            source="whisper_transcription",
            duration_seconds=duration,
            language=language,
            is_auto_generated=None,
            full_text=full_text,
            segments=segments,
        )


@app.get("/health")
def health():
    return {"status": "ok"}
