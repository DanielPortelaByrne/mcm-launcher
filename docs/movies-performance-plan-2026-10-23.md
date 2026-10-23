# Movies (Cinema) performance plan — 2026-10-23

Scope: the Movies overlay (`ui/MoviesPanel.kt` and its `design/cinema/*` views), streaming ("Stream now" via
`server/stream-service`), Jellyfin library playback (`ui/PlayerActivity.kt`), and general lag on the TCL Google TV
(low-end MediaTek SoC, weak GPU, ~2 GB RAM). Base branch: `main`.

Rule for the whole plan: **measure before and after each phase on the real TV** (an ADB device is usually connected
over TLS). Keep the existing look; where a visual effect has to get cheaper, keep it looking the same at rest.

---

## Phase 0 — Baseline measurements (do first, record numbers in this file)

1. Frame timing: `adb shell dumpsys gfxinfo com.example.tvlauncher reset`, then open Movies, scroll Home top to bottom
   with D-pad, flick through one row, open/close a title page; then `dumpsys gfxinfo com.example.tvlauncher` → record
   janky-frame %, 90th/99th percentile.
2. A Perfetto trace (`adb shell perfetto -o /data/misc/perfetto-traces/movies.pftrace -t 15s sched freq gfx view`) of the
   same scroll, to confirm where frame time goes (expected suspects below: FrostView blur, card build bursts, glows).
3. Add temporary `Log.i("Perf", …)` timings (remove or gate behind `BuildConfig.DEBUG` afterwards) for:
   - Movies `show()` → first row of posters drawn.
   - Each `loadShelf` (time waiting on `jellyfin.library()` vs JustWatch vs UI add).
   - Stream now: press → `/start` reply → PlayerActivity first frame (`STATE_READY`).
   - Library "Watch now" press → first frame.
4. Test with Jellyfin reachable **and** with the Deck asleep/unreachable (the second case is where the worst stalls are).

---

## Phase 1 — Loading: stop the page waiting on things it doesn't need (biggest wins, low risk)

### 1.1 Shelves must not block on Jellyfin
`MoviesPanel.loadShelf` calls `jellyfin.library()` before fetching each JustWatch shelf. `JellyfinClient.library()` is
`@Synchronized` and holds the lock across the network request (6 s connect + 10 s read, retried once on timeout).
`SocketTimeoutException` is not counted as "unreachable", so the 30 s backoff never applies to a sleeping Deck: every
row queues behind the lock again. With the Deck slow or asleep the entire Movies page can sit empty for 15–30 s+.
- Fetch and render the shelf from the catalogue immediately; do not call `library()` in `loadShelf`.
- When the library arrives (one background fetch started in `build()`), re-badge cards already on screen
  ("IN LIBRARY", Watch now on the hero). Keep a map title-key → card to update in place.
- In `JellyfinClient`: treat `SocketTimeoutException` (on connect) as unreachable for the backoff; don't hold the
  monitor while doing network I/O (single-flight with a shared in-flight future instead of `@Synchronized`).

### 1.2 Separate thread pools
`MoviesPanel.worker` is one 3-thread pool shared by shelves, library, storage, Downloads polling, title-state checks
and `seerr.startStream` (which can block 120 s). A pending stream plus a slow Jellyfin call starves the page.
- Shelf/catalogue loads on their own pool; long calls (`startStream`, Downloads refresh, deletes) on another.
- Never block a shelf load behind a stream start.

### 1.3 Catalogue cache: stale-while-revalidate, off SharedPreferences
`CatalogRepository` stores every shelf's raw JSON (20–40 KB each, dozens of shelves across pages) in one
SharedPreferences XML. Every `apply()` rewrites the whole file, and Android waits for pending `apply()` writes when an
activity pauses (QueuedWork) — which is exactly when the player launches. Also: an expired shelf blocks on the
network instead of showing the stale copy, and fresh JSON is parsed twice.
- One file per shelf in `cacheDir/catalog/` (atomic write via temp + rename). Migrate/delete the old prefs file once.
- Keep parsed shelves in an in-memory map for the session, so re-opening Movies/tabs does not re-parse.
- Expired shelf: return the stale copy immediately, refresh in the background, update the row only if it changed
  (or just keep it for the next open — simpler, acceptable).
- Parse once.

### 1.4 Re-opening Movies shouldn't rebuild everything
`show()` always calls `build()`, recreating every view. If the overlay is reopened within a few minutes on the same
destination, keep the existing page (scroll to top, focus hero). Rebuild only if stale or the destination changed.

### 1.5 Library playback starts without an extra round-trip
`PlayerActivity` with a Jellyfin report fetches `client.item(...)` (Chapters, Trickplay, MediaSources…) **before**
`startAttempt(0)`. That serialises a full item request in front of every library play.
- Include `MediaSources,UserData` in the library list query (`JellyfinApi.libraryUrl`) so `sourceId` and resume
  position are already known; start `prepare()` immediately; fetch the item details (chapters/trickplay) in parallel;
  if the fresh resume position differs by > 5 s, `seekTo` it before playback begins (or keep `playWhenReady` false until
  it arrives, with a short cap like 1.5 s).

### 1.6 Downloads page
Every 15 s it rebuilds all rows (`removeAllViews`) and does several sequential network calls, including one
`seerr.titleInfo` per unknown title, serially. 
- Fetch unknown title infos in parallel (bounded) and persist `knownTitles` to disk.
- Update existing rows in place (progress, status text) instead of removing/re-adding; only add/remove changed rows.

---

## Phase 2 — Images

`ui/RemoteImages.kt` is memory-only, per instance, with no request de-duplication, no disk cache, no cancellation and
no priority. Every app restart re-downloads every poster/backdrop; PlayerActivity creates new instances (one at
`buildLoading()` that is never closed → leaks a 3-thread pool on every stream launch).
- Make it one process-wide instance (application-scoped object), used by MoviesPanel and PlayerActivity.
- Add a disk cache (`cacheDir/images/`, key = SHA-1 of URL, size-capped LRU ~150 MB, store the encoded bytes).
- De-duplicate in-flight requests for the same URL (multiple callbacks).
- LIFO / priority queue so the most recently requested (on-screen) images load first; allow cancel by tag when a page
  is rebuilt (generation) so old pages don't keep 50 downloads queued.
- Key the memory cache by URL + target size.
- Backdrops: JustWatch is asked for profile `S1440` and decoded at full size even though `HeroBackdrop` renders at
  half resolution. Decode with `inSampleSize` *and* `inTargetDensity/inDensity` scaling to the real target width
  (~ the view's width / 2), not a power-of-two floor of 1280.
- Remove the leaked `RemoteImages(this)` in `PlayerActivity.buildLoading()`.

---

## Phase 3 — Scrolling and focus smoothness (GPU/UI thread)

Confirm each with the Phase 0 trace before changing; keep visuals identical at rest.

### 3.1 Frosted rail (`design/cinema/FrostView.kt`) — likely the single biggest per-frame cost
On every page scroll event, row scroll, poster arrival and focus change, `FrostView` re-records the entire page and
ambient background into a RenderNode and applies a 44 px GPU blur; focus changes keep it redrawing every frame for
450 ms. On this GPU that's a full blur pass per frame while scrolling.
- Throttle: while the page or a row is scrolling, don't re-blur every frame — re-blur at most every ~100 ms and once
  when scrolling settles; stop calling `frost.refresh()` per poster image (once per row is enough).
- Blur a downscaled copy (record at 1/4 scale, then scale up — visually the same for a 44 px blur, ~16× cheaper).
- Only record the strip of the sources actually under the rail (clip to the rail's rect before `draw`).
- Fallback switch: if the trace still shows it, use the tinted no-blur path while scrolling.

### 3.2 Card builds in bursts
`loadShelf` adds up to 16 `PosterCard`s (each: nested layouts, outline clip, elevation, foreground gradient, two
TextViews) in one main-thread pass, and starts all 16 image loads at once, when a row comes within two screens.
- Add cards across frames (e.g. 4 per frame via `post`/Choreographer), visible ones first.
- Load images only for cards within ~1.5 screen widths horizontally; load the rest as the row scrolls.
- Reduce `fillNear` lookahead from 2 screens to ~1 screen if Phase 0 shows bursts during scroll.
- (Bigger, only if still janky after the above: move rows to `RecyclerView` — already a dependency — keeping the
  existing focus rules in `CalmHorizontalScrollView`/`FastFocus`.)

### 3.3 Focus effects
- `PosterCard`/`GlowCard`/`CinemaButton`/`EpisodeCard` each own a `BlurMaskFilter` glow and animate `translationZ`
  (elevation 14 → 34) on focus, which recomputes shadows every animation frame. Replace the per-card blur with a
  shared pre-rendered glow bitmap (nine-patch or cached Bitmap per size), drawn under the poster; animate scale only
  and set the elevation once at the end (or drop the extra elevation; the glow carries the effect).
- `PosterCard.onFocusChanged` calls `Cinema.weight(...)` (changes typeface variation → relayout of the text). Use two
  pre-built TextPaint states or keep weight constant and change only colour, to avoid a relayout on every D-pad press.

### 3.4 Hero
- `HeroBackdrop.rebuild()` allocates a new half-resolution ARGB bitmap on every turn (every 9 s) on the main thread.
  Reuse the cache bitmap when the size is unchanged.
- Pause the hero turn timer while the page is scrolled away from it (it already skips, but still posts every 9 s).

---

## Phase 4 — Streaming ("Stream now")

### 4.1 Make the press feel instant: pre-warm the search
Today: press → `POST /start` → Prowlarr search (up to 60 s, usually 5–20 s) → qBittorrent add → reply. Nothing is
shown except "searching" while the search runs.
- Server (`server/stream-service/stream_service.py`): cache search results per (title, year, episode) for ~20 min.
  Add `POST /prepare` (same auth as `/start`) that runs the search and caches it, returns immediately.
- App: when a title page opens for a non-library film (or an episode card is focused), fire `/prepare` in the
  background. `/start` then usually skips the search.
- Add tests in `test_stream_service.py` for the cache (hit, expiry, per-episode keys).

### 4.2 Pick releases that can actually stream
`score()` ranks by resolution and source first; seeders only add `log10(seeders)`. A big 2160p file with few seeders
wins over a well-seeded 1080p WEB-DL and then buffers.
- Add a streaming score: estimate bitrate = size / runtime (runtime from the request, or a per-type default), and
  strongly prefer releases whose bitrate is comfortably below what that seeder count typically sustains; raise the
  seeders weight; keep the bad-audio penalty. Keep the download pipeline's own scoring unchanged.
- Unit-test with a few realistic result sets.

### 4.3 Client buffer for streams
`STREAM_TARGET_BUFFER_BYTES = 96 MB` caps the buffer long before the 90 s max for high-bitrate files (a 4K file at
40 Mbps fits ~19 s). Either keep the byte cap and prefer ≤1080p (4.2), or raise the cap only if heap allows
(`Runtime.maxMemory()` check). Don't blow the heap on a 2 GB TV.

### 4.4 Seeking in an unfinished stream
Sequential download never jumps; a seek past the downloaded run waits up to `STALL_SECONDS` (60 s) and then the
connection ends. Short term: for streams, clamp/limit the scrubber to the downloaded range (status already reports
progress) and say so in the chrome. Long term (separate, larger task): libtorrent piece deadlines.

### 4.5 Status polling
`pollStatus`/`statsTick` start a new `Thread` per poll and a fresh connection each time. Use one scheduled executor
and keep-alive; fine to keep the 1 s / 5 s cadence.

---

## Phase 5 — Networking hygiene (do alongside or after Phase 1)

- Jellyfin, Seerr and the stream service are addressed as `steamdeck.local`. Android's resolver caches lookups only
  briefly, and mDNS lookups on this TV may cost hundreds of ms or more each. Measure a lookup (Phase 0). If slow,
  cache the resolved address for a few minutes in one place (e.g. resolve once, rewrite the host to the IP for
  requests and for ExoPlayer, keep the `Host` header) and re-resolve on connection failure.
- Make sure HTTP connections are reused (read the body fully and close the stream before `disconnect()`; check that
  keep-alive actually happens with a trace or server logs). If it doesn't, consider moving to one shared OkHttp client
  (connection pool, custom DNS cache, disk cache for images) and `media3-datasource-okhttp` for the player — only if
  the measurements justify the dependency.

---

## Out of scope / not recommended now
- Replacing the hand-built views with Compose or Leanback.
- Changing the download pipeline (`server/request-pipeline`).

## Done means
- Phase 0 numbers and after-numbers recorded in this file for each phase that shipped.
- Unit tests pass (`gradlew testDebugUnitTest`), server tests pass (`python -m pytest server/stream-service`).
- Installed and checked on the TCL TV: Movies opens with rows showing in < 1 s from cache, with the Deck asleep too;
  scrolling Home has clearly fewer janky frames; Stream now on a pre-warmed title starts noticeably faster.
