# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Read README.md before changing anything in `core/ml/`, embedding storage, or search. It documents the model asset setup and the EmbeddingGemma rules (prompt prefixes, Matryoshka truncation, thread safety, re-index triggers).

## Project

Stash It is an Android app (single `:app` module, package `com.ps.stashit`). Users share short-video links (YouTube Shorts, TikTok, Instagram Reels) into the app, and it indexes them for on-device semantic search. The git root is one level up (`StashIt/`), with a sibling `backend/` directory; this file covers `mobile/` only.

Stack: Kotlin, Jetpack Compose (Material3), Hilt, Room, WorkManager, Retrofit/OkHttp, Coil 3, MediaPipe `tasks-text` TextEmbedder (EmbeddingGemma-300m). AGP 9.x, Gradle 9.5 wrapper, KSP for Room/Hilt, Java 11 target, minSdk 24 / compile+target SDK 37. Dependency versions live in `gradle/libs.versions.toml`.

## Commands

```bash
./gradlew assembleDebug                 # build debug APK
./gradlew installDebug                  # build + install on connected device/emulator
./gradlew testDebugUnitTest             # JVM unit tests
./gradlew testDebugUnitTest --tests "com.ps.stashit.ExampleUnitTest"   # single test class
./gradlew connectedDebugAndroidTest     # instrumented tests (needs device)
./gradlew lint
```

Only template example tests exist today. `*.task` and `*.tflite` are gitignored, so the model asset from the README's Quick start must be present before building.

## Architecture

### Capture -> enrich -> embed -> search pipeline

1. **Capture**: `feature/capture/CaptureActivity` is the `ACTION_SEND text/plain` share target. `ShareTextParser` pulls the first URL out of the shared text, guesses `Platform` from the host, and treats the rest of the text as the caption. `SavedItemRepository.saveItem` dedupes by URL (the unique index on `saved_items.url`). New rows are inserted with `embedding = NULL`, and the repository calls `EmbeddingScheduler.triggerImmediate()`.
2. **Enrich + embed (background)**: `core/EmbeddingWorker` (`@HiltWorker`) loops over `getPendingEmbedding(BATCH_SIZE)`, which returns rows where `embedding IS NULL`. For each item it:
   - fetches oEmbed metadata via `MetaDataFetcherRouter` (YouTube and TikTok Retrofit APIs; Instagram/Unknown return null);
   - writes the title and thumbnail;
   - builds the text from title plus caption;
   - calls `TextEmbedder.embedDocument`;
   - stores the vector with `attachEmbedding`.
   Items whose text is blank or whose embedding contains NaN are skipped with `continue`. They keep `embedding = NULL`, so the next batch fetches them again. Keep this in mind when you change the loop's termination.
3. **Search**: `SavedItemRepository.search` embeds the query with `embedQuery`, loads **all** embedded rows (`getAllEmbedded`), and runs a brute-force cosine scan in Kotlin. It adds a +0.3 keyword boost when the query appears as a substring of caption or title. Results are filtered by `MIN_SIMILARITY_THRESHOLD = 0.75`, but exact keyword matches always pass. There is no vector index.

### WorkManager + Hilt wiring

The default WorkManager initializer is removed in `AndroidManifest.xml` (`tools:node="remove"`). `StashItApplication` implements `Configuration.Provider` with `HiltWorkerFactory`. A new worker therefore needs `@HiltWorker` + `@AssistedInject`; otherwise injection silently fails. `EmbeddingScheduler.schedulePeriodicSweep()` exists but nothing calls it.

### Embeddings storage

`Converters` serializes `FloatArray` to a big-endian `ByteArray` BLOB. `SavedItemEntity` overrides `equals`/`hashCode` because of the array field. `embeddingModelVersion` and `clearAllEmbeddings()` exist for re-index-on-model-change, but nothing writes the version or calls the clear yet. The Room DB uses `fallbackToDestructiveMigration(true)` with `exportSchema = false`, so a schema version bump wipes user data.

### TextEmbedder

The `core/ml/TextEmbedder` interface has `embedDocument`, `embedQuery`, `embed`, and `dimension`. `TextEmbedderModule` binds `LocalRagTextEmbedder` (EmbeddingGemma) as a singleton. The other implementations are not wired into DI:
- `RealTextEmbedder`: legacy MobileBERT, references a `bert_embedder.tflite` that is not in assets.
- `FakeTextEmbedder`: hash-based, 384 dims.
- `ModelAssetInstaller`: references gecko/sentencepiece assets that are not present.

The EmbeddingGemma rules (prompt prefixes, re-index triggers, `Mutex`) are in README.md. **The current code differs from the README in a few places.** Check these before you rely on either one:
- `LocalRagTextEmbedder.embed` truncates 768 -> 512 with `copyOf` but does **not** renormalize, and it has no `require(raw.size >= 512)` guard. The README says both exist. Search uses full cosine, so it still ranks correctly. A dot-product shortcut would not.
- The document prompt is `"title: | text: ..."`. It passes no title and does not use the `"none"` placeholder the README specifies.
- The model is copied to `cacheDir`, which the README explicitly warns against because Android can evict it. The copy is also not atomic: a partial file passes the `length() != 0` check.

### UI layer

The UI uses MVI through `core/ui/BaseViewModel<State, Event, Effect>`:
- state is a `StateFlow` updated with `setState { copy(...) }`;
- one-shot effects go through a buffered `Channel`;
- screens call `onEvent`.

Each feature (`feature/capture`, `feature/library`, `feature/search`) has `*Screen`, `*State` (which also defines its Event/Effect types), and a `@HiltViewModel` `*ViewModel`. `MainActivity` switches between Library and Search with a bottom `NavigationBar` driven by a `remember`'d tab index. Navigation-compose is not used. Search input is debounced by 300 ms in `SearchViewModel`.

Layering: `core/database` holds the Room entity/DAO, `data/domain` holds the `SavedItem`/`SearchResult` domain models plus the `toDomain()` mapper, and `data/repository` holds the repository interface + impl (bound in `RepositoryModule`). ViewModels and the worker depend only on `SavedItemRepository`.
