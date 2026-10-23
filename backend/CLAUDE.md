# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Caption/transcript service for the Stash It app. A single-file FastAPI app (`main.py`) that takes a YouTube Shorts or TikTok URL and returns the video's caption text plus timed segments. The git root is one level up (`StashIt/`); the sibling `mobile/` Android app has its own `CLAUDE.md`. The mobile app does not call this service yet.

## Commands

```bash
source .venv/bin/activate               # local venv is Python 3.13; the Docker image uses 3.12
pip install -r requirements.txt
uvicorn main:app --reload --port 8000   # local dev; OpenAPI UI at /docs

curl -X POST localhost:8000/captions -H 'Content-Type: application/json' \
  -d '{"url": "https://www.youtube.com/shorts/VIDEO_ID"}'

./deploy.sh                             # build + deploy to Cloud Run (needs gcloud auth + project set)
API_KEY=... SERVICE_NAME=... REGION=... ./deploy.sh
```

There are no tests, linter, or formatter configured.

Local audio fallback needs `ffmpeg` on PATH. yt-dlp needs a JS runtime (Deno) for YouTube extraction; the Dockerfile copies it in, but you must install it yourself for local runs.

## Architecture

Endpoints: `POST /captions` (`CaptionRequest` -> `CaptionResponse`) and `GET /health`.

### Request flow

1. `detect_platform` accepts only these URL shapes, matched by regex:
   - `youtube.com/shorts/<11-char id>`
   - `tiktok.com/@user/video/<id>`
   - `vm.tiktok.com` / `vt.tiktok.com` short links

   Everything else (`/watch`, `youtu.be`, bare IDs) gets a 422. This restriction is intentional.
2. Duration is checked against `MAX_DURATION_SECONDS = 120` before any caption or audio work. If duration is missing, the request fails with 502.
3. Caption source, per platform:
   - **YouTube**: `youtube-transcript-api` tries `req.languages` in order. If none match, it takes the first available track. The duration lookup is a separate yt-dlp metadata call.
   - **TikTok**: one yt-dlp metadata call provides the ID, duration, and caption tracks. `fetch_tiktok_captions` tries manual subtitles first, then `automatic_captions`. It downloads the VTT/SRT URL with `requests` and parses it with the in-file `parse_vtt`. Short links get their real video ID only from yt-dlp's `info["id"]`.
4. Audio fallback (when no caption track exists and `allow_audio_fallback`): yt-dlp downloads the audio, ffmpeg converts it to mp3 in a `TemporaryDirectory`, and faster-whisper `base` transcribes it on CPU with int8. `source` in the response says which path was used: `youtube_captions`, `tiktok_captions`, or `whisper_transcription`.

### Conventions and gotchas

- Errors are raised as `HTTPException` from deep inside helpers, not mapped at the route. Status codes carry meaning:
  - 422: bad URL or video too long
  - 404: lookup failed, or no captions and fallback disabled
  - 502: download failure or duration unavailable
  - 500: an optional dependency is missing
- `yt_dlp` and `faster_whisper` are imported lazily inside functions so the app can start without them.
- `WhisperModel` is constructed on every fallback request, not cached. The Dockerfile pre-downloads the `base` weights so cold starts don't fetch about 150MB, but each request still pays the model load cost. If you change the model size in `_transcribe_audio_file`, update the Dockerfile bake step too.
- The YouTube and TikTok branches in `get_captions` are near-duplicates. Keep them in sync when you change response shape or limits.

### Deployment

`deploy.sh` deploys to Cloud Run with `--source .`, which builds from the Dockerfile. It:
- creates a dedicated `yt-caption-runner` service account;
- deploys with `--allow-unauthenticated`;
- sets 2 vCPU / 2Gi memory, concurrency 4, and 0 to 3 instances;
- passes `API_KEY` as an env var.

**`main.py` never reads `API_KEY` and has no `X-API-Key` check**, even though comments in `deploy.sh` say that check protects the service. As deployed, the endpoint is public.
