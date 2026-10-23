# Stash It Android app

The Android app for Stash It. You share YouTube Shorts, TikToks, and Instagram Reels into the app, and it saves them for semantic search. Search runs on the device: saved items and queries are embedded locally with EmbeddingGemma, and nothing about your library is sent to a server. For the project as a whole, see the [project README](../README.md).

## What the app does

- **Capture.** The app registers as a share target for plain text. It takes the first URL from the shared text, works out the platform from the host, and keeps the rest of the text as the caption. A link that is already saved is not saved again.
- **Enrich.** A background worker fetches the title and thumbnail for YouTube and TikTok links from their public oEmbed endpoints. These are the app's only network calls. Instagram links are saved without a title or thumbnail.
- **Embed.** The same worker embeds the title and caption on the device and stores the vector with the item.
- **Search.** A query is embedded and compared against every saved item with cosine similarity. Items whose title or caption contains the query get a keyword boost.

## Building

Requirements:

- Android Studio, or the Android SDK on its own (compile and target SDK 37, min SDK 24)
- JDK 25, which the Gradle daemon toolchain is pinned to
- The EmbeddingGemma model file (see below). `*.task` files are gitignored, so it is not in the repo.

### Add the model asset

Download the EmbeddingGemma task file from https://storage.googleapis.com/mediapipe-models/text_embedder/embedding_gemma/int4int8/latest/embedding_gemma.task and place it at:

```
app/src/main/assets/models/embedding_gemma.task
```

### Build, install, and test

```bash
./gradlew assembleDebug               # build a debug APK
./gradlew installDebug                # build and install on a connected device or emulator
./gradlew testDebugUnitTest           # JVM unit tests
./gradlew connectedDebugAndroidTest   # instrumented tests (needs a device)
./gradlew lint
```

Only the template example tests exist so far.

---

## On-device text embedding

Local text embeddings use **EmbeddingGemma-300m** through the MediaPipe `TextEmbedder` task. Embedding runs fully offline and is used for semantic search over short items (typically 10–50 words each).

### API

| Method | Use for |
|---|---|
| `embedQuery(text)` | The user's search string |
| `embedDocument(text, title)` | Items being stored in the index |

Both return a **512-dimension, L2-normalized** `FloatArray`.

**These are not interchangeable.** See below.

---

### Implementation notes

#### Queries and documents use different prompts

EmbeddingGemma is a dual encoder trained asymmetrically. Queries and documents each get their own prompt prefix, and the model relies on them as a control signal:

```
query    →  task: search result | query: {text}
document →  title: {title | "none"} | text: {text}
```

Using one prefix for both — or the wrong prefix entirely — is the single biggest thing that degrades retrieval quality. It fails silently: you get plausible-looking vectors that rank badly.

> Prefixes from other embedding models (e.g. Nomic's `search_query:`) do **not** work here. They aren't recognized as control tokens, so they're tokenized as ordinary text and become a shared nuisance direction across every vector.

Pass a real `title` when you have one — the model card notes it measurably improves document-side quality.

Other supported task descriptions, if the use case changes:

| Task | Prompt |
|---|---|
| Retrieval / reranking | `task: search result \| query: ` |
| Semantic similarity (STS) | `task: sentence similarity \| query: ` |
| Classification | `task: classification \| query: ` |
| Clustering | `task: clustering \| query: ` |
| Code retrieval | `task: code retrieval \| query: ` |
| Summarization | `task: summarization \| query: ` |

#### Matryoshka truncation to 512 dims

EmbeddingGemma emits 768 dims natively and is trained with Matryoshka representation learning, so the leading 512 components are a valid standalone embedding. We truncate to halve index size and comparison cost.

**Truncation invalidates the normalization.** MediaPipe's `setL2Normalize(true)` normalizes the *768*-dim vector; after slicing to 512 the remainder is no longer unit length, and the error differs per item. We renormalize in `run()` after the copy. Skipping this quietly corrupts any cosine-as-dot-product comparison.

We also `require()` the raw output is at least 512 wide, because `copyOf` zero-pads rather than throwing — a shorter output would otherwise produce a silently broken index.

Supported cuts are 768 / 512 / 256 / 128. 256 is worth benchmarking on short items; the loss is often negligible.

#### Thread safety

The MediaPipe native handle is not safe for concurrent calls, and `Dispatchers.Default` is a thread pool. Every `embed` call is serialized behind a `Mutex`. Removing it will surface as intermittent native crashes during bulk indexing, not as a clean error.

#### Lifecycle

The embedder is created lazily on first use and holds a native interpreter. It implements `Closeable`; call `close()` when the owning scope ends. Because the model is mmap'd rather than copied, first-call cost is initialization only — but it's still non-trivial, so warm it during app startup if the first embed sits on a user-visible path.

---

### Changing anything above requires a full re-index

Prompt prefixes, dimension count, normalization, and the model file itself all define the vector space. Change one and every stored embedding becomes incomparable to new ones — retrieval degrades rather than erroring.

Keep a schema version alongside the index and re-embed the corpus on mismatch.

---

### Evaluating quality

Don't trust spot checks. The failure mode of a misconfigured embedder is *plausible but poorly ranked* results.

1. Assemble 20–30 real query → correct-item pairs.
2. Measure **recall@5**.
3. Plot the distribution of cosine similarity across *unrelated* pairs.

Healthy: unrelated pairs scattered well below matches, with clear separation. Degenerate: everything bunched in a narrow high band (0.80–0.95), which means ranking is close to arbitrary regardless of what the top-1 result looks like.

If the spread looks compressed, check the prefixes are actually being applied on the code path you think they are — that's the usual cause.

---

### History

Originally used MediaPipe's `bert_embedder.tflite` (MobileBERT, 512-dim). Input length was never the limitation — 50 words is ~70 WordPiece tokens, far inside any sequence limit. The problem was the model: a small general-purpose encoder with no contrastive retrieval objective, which compressed similarities into a narrow band and leaned heavily on surface word overlap.

Replaced with EmbeddingGemma-300m. Trade-off: substantially larger on disk and slower per call, so verify latency on the lowest-end target device.

---

### Troubleshooting

**`Model is not a valid Flatbuffer buffer`** — the asset is being compressed. Confirm the `noCompress` block is in the right module's Gradle file.

**Everything scores ~0.9** — prefixes aren't being applied, or query and document are getting the same one.

**Intermittent native crashes under load** — concurrent access; confirm the `Mutex` wraps the `embed` call itself, not just the surrounding coroutine.

**Model file disappears after a while** — only applies if using the manual extract-to-disk fallback. Never extract to `cacheDir`; Android deletes it under storage pressure. Use `noBackupFilesDir`, and write to a `.tmp` file then `renameTo()` so an interrupted copy doesn't leave a non-empty partial file that passes the existence check forever.