# Stash It Caption Service

The backend for the Stash It app. It's a small FastAPI service that takes a YouTube Shorts or TikTok link and returns the video's captions as full text and as timed segments. When a video has no caption track, the service downloads the audio and transcribes it with Whisper.

The service only handles short-form video: Shorts and TikTok links, up to 120 seconds long. The Android app is in the sibling `mobile/` directory, and it does not call this service yet.

## Running locally

Requirements:

- Python 3.12+ (the Docker image uses 3.12)
- `ffmpeg` on your PATH, for the audio fallback
- [Deno](https://deno.com), which yt-dlp needs to extract YouTube videos

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

Interactive API docs are served at http://localhost:8000/docs.

## Tests

```bash
pip install -r requirements-dev.txt
pytest
```

The tests are in `tests/`. They make no network calls: yt-dlp, faster-whisper, `youtube-transcript-api`, and `requests` are all replaced with fakes. Each known bug (see [Known issues](#known-issues)) has a test marked `xfail(strict=True)`. These tests describe the correct behaviour, so a fix makes them pass unexpectedly and turns the run red. When that happens, remove the marker.

There is no linter or formatter set up yet.

## API

### `POST /captions`

Request:

```json
{
  "url": "https://www.youtube.com/shorts/VIDEO_ID",
  "languages": ["en"],
  "allow_audio_fallback": true
}
```

| Field | Default | Meaning |
|---|---|---|
| `url` | required | A YouTube Shorts or TikTok video URL |
| `languages` | `["en"]` | Preferred caption languages, in priority order. If none match, the first available track is used. |
| `allow_audio_fallback` | `true` | If no caption track exists, download the audio and transcribe it |

Accepted URL shapes:

- `youtube.com/shorts/<id>` (with or without `www.` or `m.`)
- `tiktok.com/@user/video/<id>`
- `vm.tiktok.com/...` and `vt.tiktok.com/...` short links

Any other link, including `youtube.com/watch` links, `youtu.be` links, and bare IDs, is rejected.

Before matching, a URL with no scheme gets `https://` added and an `http://` URL is upgraded to `https://`. Matching is anchored to `https://<host>/`, so the host must be exactly one of the hosts above: no other scheme, no port, no `user@` part, and no supported URL hidden inside another URL. yt-dlp is also limited to the platform's own extractors (`allowed_extractors`), so its generic extractor, which fetches any host, never runs.

Response:

```json
{
  "platform": "youtube",
  "video_id": "abc123XYZ_0",
  "source": "youtube_captions",
  "duration_seconds": 42.0,
  "language": "en",
  "is_auto_generated": true,
  "full_text": "...",
  "segments": [{ "text": "...", "start": 0.0, "duration": 2.1 }]
}
```

`source` tells you where the text came from: `youtube_captions`, `tiktok_captions`, or `whisper_transcription`.

Errors:

| Status | Cause |
|---|---|
| 422 | The URL isn't supported, or the video is longer than 120s |
| 404 | The video lookup failed, or the video has no captions and the audio fallback is disabled |
| 502 | The audio download failed, or the video's duration couldn't be determined |
| 500 | yt-dlp or faster-whisper isn't installed |

### `GET /health`

Returns `{"status": "ok"}`.

## How it works

1. **Validate.** The URL is checked against the accepted shapes. The video's duration is looked up with yt-dlp (metadata only, no download), and anything over `MAX_DURATION_SECONDS` (120) is rejected before any other work is done.
2. **Use existing captions.**
   - YouTube: captions are fetched with `youtube-transcript-api`.
   - TikTok: the same yt-dlp metadata call returns any caption tracks. Manual subtitles are tried before auto-generated ones, and the VTT/SRT file is downloaded and parsed.
3. **Fall back to audio.** If no captions exist, yt-dlp downloads the audio into a temp directory and ffmpeg converts it to mp3. faster-whisper (`base` model, CPU, int8) then transcribes it.

The Whisper model is loaded on every fallback request. The Docker image pre-downloads the model weights, so a cold start doesn't fetch them, but each fallback request still pays the cost of loading the model.

## Deployment: `deploy.sh`

### Purpose

`deploy.sh` deploys the service to Google Cloud Run with one command. It sets up everything the deploy needs in your current gcloud project, builds the container from the `Dockerfile` with Cloud Build, and prints the public URL. You can run it again to redeploy.

### One-time prerequisites

1. Install the [gcloud CLI](https://cloud.google.com/sdk/docs/install).
2. Run `gcloud auth login`.
3. Run `gcloud config set project YOUR_PROJECT_ID`.
4. Make sure billing is enabled on the project.

### Usage

```bash
./deploy.sh                      # generates a random API key and prints it
API_KEY=my-secret ./deploy.sh    # use your own key
```

These environment variables are optional overrides:

| Variable | Default |
|---|---|
| `API_KEY` | A random 48-character hex string (`openssl rand -hex 24`) |
| `SERVICE_NAME` | `yt-caption-service` |
| `REGION` | `us-east1` |
| `SA_NAME` | `yt-caption-runner` |

### What it does, step by step

1. **Checks for a project.** It reads the active gcloud project and exits if none is set.
2. **Sets the API key.** It uses `API_KEY` from the environment, or generates a new one and prints it.
3. **Enables Google Cloud APIs.** These are Cloud Run, Cloud Build, Artifact Registry, and IAM.
4. **Creates a service account.** It creates `SA_NAME@PROJECT.iam.gserviceaccount.com` if it doesn't exist yet, then waits 20 seconds for the account to propagate. The build and the running service both use this account, so the deploy doesn't depend on the project's default compute service account.
5. **Grants roles.** It grants the account `cloudbuild.builds.builder`, `logging.logWriter`, `artifactregistry.writer`, and `storage.objectAdmin` at the project level.
6. **Builds and deploys.** It runs `gcloud run deploy --source .` with these settings:
   - public access (`--allow-unauthenticated`)
   - 2 vCPU and 2 GiB of memory
   - a 300 s request timeout
   - concurrency 4
   - 0 to 3 instances
   - `API_KEY` set as an environment variable
7. **Prints the URLs.** It prints the service URL, plus ready-to-use `/health` and `/docs` links.

### Caveats

- **The API key is not enforced.** The script deploys the service publicly and sets `API_KEY`, but `main.py` never checks it. Until the service has a request-level key check, anyone with the URL can call it.
- **Running without `API_KEY` rotates the key.** Each run without `API_KEY` set generates and deploys a new key. Pass the existing key to keep it.
- **The roles apply to the whole project.** The roles in step 5 cover the whole project, not just this service. `storage.objectAdmin` in particular gives the runtime account read and write access to every bucket in the project.

## Known issues

Each of these has a strict `xfail` test that will start failing once the bug is fixed.

- **WebVTT timestamps without hours are read as 0.** `_vtt_timestamp_to_seconds` only handles `hh:mm:ss.mmm`. Cues written as `mm:ss.mmm`, which WebVTT allows, all get `start=0` and `duration=0`.
- **YouTube request-blocking errors return a 500.** `fetch_youtube_captions` only catches `TranscriptsDisabled`, `NoTranscriptFound`, and `VideoUnavailable`. `RequestBlocked` and `IpBlocked`, which YouTube often raises against cloud IPs, return an unhandled 500.
- **Long Shorts IDs are cut short.** The Shorts ID pattern has no end boundary, so a 12-character ID is accepted and cut to 11 characters.
