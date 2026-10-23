FROM python:3.12-slim

# Deno: JS runtime yt-dlp needs for YouTube extraction.
# Copied from the official image so there's no install script to maintain.
COPY --from=denoland/deno:bin /deno /usr/local/bin/deno

# ffmpeg: needed for the audio-extraction step of the Whisper fallback.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake the Whisper model into the image so the first fallback request
# doesn't have to download ~150MB at runtime (and cold starts stay shorter).
RUN python -c "from faster_whisper import WhisperModel; WhisperModel('base', device='cpu', compute_type='int8')"

COPY main.py .

ENV PYTHONUNBUFFERED=1

# Cloud Run sets $PORT (default 8080).
CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-8080}"]
