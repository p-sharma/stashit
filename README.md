# Stash It

Stash It is an Android app for saving short-form videos and finding them again later by meaning, not just by exact words. When you see a YouTube Short, TikTok, or Instagram Reel worth keeping, you share it to Stash It from the platform's app. Later you can search your saved videos in plain language, for example "that pasta recipe with lemon", and get back the closest matches.

Search runs entirely on the phone. Each saved video is turned into a text embedding by an on-device model (EmbeddingGemma), and queries are matched against those embeddings locally. Your saved items and search queries are never sent to a server.

## How it works

1. **Save.** Share a video link to Stash It. The app stores the link and any text that was shared with it.
2. **Enrich.** In the background, the app fetches the video's title and thumbnail from YouTube's or TikTok's public oEmbed endpoints. Instagram links are saved but not enriched.
3. **Embed.** The title and shared text are turned into an embedding on the device.
4. **Search.** Your query is embedded the same way and compared against every saved item. Exact keyword matches get a boost.

Today the searchable text is only the title and whatever text was shared with the link. The backend service in this repo can fetch a video's captions, or transcribe its audio when there are none. That would give search far more to work with, but **the app does not call the backend yet**.

## Repository layout

| Directory | What it is | Docs |
|---|---|---|
| [`mobile/`](mobile/) | The Android app (Kotlin, Jetpack Compose, Room, WorkManager, MediaPipe) | [mobile/README.md](mobile/README.md) |
| [`backend/`](backend/) | Caption service for YouTube Shorts and TikTok (Python, FastAPI, yt-dlp, faster-whisper), deployed to Google Cloud Run | [backend/README.md](backend/README.md) |

The two parts are built, tested, and deployed separately. Each README covers its own setup.

## Platform support

| Platform | Save in app | Title and thumbnail | Captions from backend |
|---|---|---|---|
| YouTube Shorts | Yes | Yes | Yes |
| TikTok | Yes | Yes | Yes |
| Instagram Reels | Yes | No | No |

The backend only accepts videos up to 120 seconds long.
