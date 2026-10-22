# Handoff prompt: Cinema redesign implementation

**Run with:** Claude Code, **Opus 5.5** (`claude-opus-5-5`), **high** effort, plan mode first.
Why: a multi-file, View-based Android TV UI rewrite with custom Canvas drawing (wavy scrubber) and D-pad focus logic, judged against pixel mockups. This needs strong visual-fidelity judgement and careful focus handling. Sonnet is fine for the mechanical sub-steps (string/colour plumbing, unit tests) if you want to split work, but one Opus session driving the whole thing, committing per phase, will be the most coherent.

Working repo: `C:\Users\danie\AndroidTVLauncher` (package `com.example.tvlauncher`, Kotlin, **Android Views, not Compose**, minSdk 24, Media3 1.5.1).
Check `git status` and the current branch (`codex/cinema-left-navigation` at time of writing) first. The working tree has uncommitted changes in `PlayerActivity.kt`, `MoviesPanel.kt`, `HomeCollection.kt`, `FilmInfo.kt`, `FilmPage.kt`, Jellyfin and Seerr files. **Do not overwrite or revert them.** Build on top, and commit your work in small phase commits.

---

## Prompt (paste everything below this line)

You are implementing a visual redesign of the **Cinema** section (Movies, film detail, My library, Downloads, Search, stream-loading, and the video player) of this Android TV launcher, to match a set of approved mockups.

### Source of truth
Mockups (1920x1080 PNGs) are in `docs/ui-review/movies-reimagined/`. **Open and study every one before writing code.** They are the spec. The original screens they replace are in `docs/ui-review/movies/` (with `INDEX.md`) for before/after comparison. `_src/mock.html` is the HTML source of the mockups: read it for exact sizes, spacing, colours, gradients and the SVG geometry (for example `waveScrub`, `markSvg`, `ribbon`, `ARCH`). Render any screen with `_src/render.sh <screen-id>` if you need a fresh reference.

| Mockup | Screen | Existing code to look at |
| --- | --- | --- |
| `00-logo.png` | New Cinema logo (mark, wordmark, icon sizes) | rail logo drawable; `design/` |
| `01-movies-top.png` | Movies home: hero + Top 10 row | `ui/MoviesPanel.kt` |
| `02-movies-rows-toy-story-history.png` | Rows, focused poster state | `ui/MoviesPanel.kt` |
| `03-movies-history-films-row.png` | Row focus + "IN LIBRARY" badge | `ui/MoviesPanel.kt` |
| `10`/`12-detail-*.png` | Film detail (in library / not in library) | `ui/room/FilmSheet.kt`, `data/FilmPage.kt` |
| `11`/`13-detail-*-scrolled*.png` | Detail scrolled to "More like this" | same |
| `20-my-library-recently-added.png` | My library | `ui/MoviesPanel.kt` |
| `21-downloads.png` | Downloads queue | `data/movies/Queue.kt`, `MoviesPanel.kt` |
| `22-search.png` | Search with QWERTY + trending | `ui/SearchPanel.kt` |
| `23-search-voice-listening.png` | Voice search listening state | `ui/SearchPanel.kt`, `system/RemoteKeyService.kt` |
| `30-stream-loading-overlay.png` | Stream loading | `ui/PlayerActivity.kt`, `data/stream/*` |
| `31-player-controls.png` | Player, controls up | `ui/PlayerActivity.kt` |
| `32-player-scrubbing.png` | Scrubbing with frame preview | `ui/PlayerActivity.kt` |
| `33-player-paused.png` | Paused info screen | `ui/PlayerActivity.kt` |

Mockup caveat: poster and backdrop art in the mockups is cropped from screenshots, and chapter/scrub thumbnails are re-cropped stand-ins. Real art and frames come from the app's data. Stream specs in `33` and chapter names are placeholder values.

### Design system (extract into `design/` as shared tokens, don't hardcode per screen)
**Colour**
- Background espresso `#0B0807`; surfaces are translucent cream: glass `rgba(255,242,222,.075)`, glass-strong `.13`, hairline `rgba(255,242,222,.14)`.
- Text cream `#F6ECDB`, muted `#B6A896`, dim `#7F7366`.
- Accents: saffron `#EDBE56` / `#F6BF33`, terracotta `#E27A49` / `#E2562B`, orange `#F08A25`, maroon `#5D1710`, red `#C2281D`, teal `#3AA597` (sparingly).
- "Stripe set" (the 70s motif): saffron, orange, terracotta, red, maroon, applied as even bands, either vertical (row-title marker) or horizontal (eyebrow mark, carousel indicator, download progress bars).
- Ambient background on non-hero screens: soft warm radial at top-right, teal at bottom-left, plus the faint 70s arch columns (mustard `#E0A93A`, terracotta `#C4532A`, maroon `#6E2214`, olive `#4D5B2C`) at about 20% opacity, masked to fade toward the left.

**Type** (check `design/Type.kt` first; keep what exists if it already matches)
- Display/serif: Fraunces (titles, row headings, big numerals). Body/UI: Inter. Both are OFL: bundle as `res/font` if not present, otherwise fall back to the existing serif.
- Sizes at 1080p: hero title 104 to 128, row heading 38, body 23 to 26, eyebrow 15 to 17 uppercase with letter-spacing about 0.24em.
- Use dp/sp scaled from the 1080p mockup (the TV renders at 1920x1080, so dp = px at xhdpi-TV; verify density on the device).

**Components**
- Left nav rail: floating glass pill, 92px wide, inset 32px; icon buttons 64px; active = glass circle, saffron icon, and a vertical stripe-set bar on the left edge. Logo at top.
- Poster card: 16px radius. Focused = scale 1.1, cream ring 4px plus soft saffron outer ring, deeper shadow, title turns cream plus a year/genre line below.
- "IN LIBRARY" badge: dark glass pill, saffron text, check icon, bottom-left of the poster.
- Buttons: 76px tall full pill. Primary = cream fill with dark text; "Queue download" = terracotta gradient; secondary = glass with hairline; destructive = glass with terracotta-tinted text. Focus: extra 6px halo.
- Top 10 row: oversized outlined Fraunces numerals (saffron stroke, about 60% alpha) tucked behind the left of each poster.
- Detail poster: stacked offset stripe shadow (saffron, orange, terracotta, red at 10px steps).
- Hero indicators: 64x10 pills; active one is the stripe set.
- Downloads: stat tiles (Downloading / Queued / Finding sources / Awaiting release), glass request cards, 14px stripe-set progress bars (an indeterminate sweeping variant for "finding sources"), percentages in the serif.

**Logo** (`00-logo.png`): concentric rings (maroon, red, terracotta, orange, saffron) with a dark play triangle centre. SVG geometry is `markSvg` in `_src/mock.html`; import it as a vector drawable. "Cinema" is a placeholder wordmark; confirm the name with the user if it is shown anywhere.

### Behaviour changes requested (beyond restyling)
1. **Detail screens: no Back button.** Hardware Back already handles it. Remove the on-screen button and its focus stop; keep Watch now / Delete and Queue download / Stream now.
2. **Search: QWERTY** keyboard (number row, qwertyuiop, asdfghjkl, zxcvbnm, plus Clear / Space / Delete) as an in-app D-pad keyboard, replacing the system IME. Keep live results behaviour already in `SearchPanel`.
3. **Voice search:** mic button in the search field plus remote mic key (look at `RemoteKeyService` and how the TCL remote's voice key arrives, for example `KEYCODE_SEARCH` / `KEYCODE_VOICE_ASSIST`). Use `SpeechRecognizer` (needs `RECORD_AUDIO`, with a graceful "voice search unavailable" state if no recogniser or permission). Listening UI = `23`: ring mic, stripe-coloured waveform driven by `onRmsChanged`, live partial transcript in the field.
4. **Player** (`PlayerActivity`, three states):
   - **Controls (`31`):** wavy scrubber, chapter label, subtitle/audio/quality chips, stripe-ringed pause button.
   - **Scrubbing (`32`):** large preview frame above the playhead with timestamp and "Chapter N of M · name", faded neighbouring frames, resume-point marker. D-pad left/right scrubs; OK commits; Back cancels.
   - **Paused (`33`):** blurred dimmed frame, title/meta/synopsis, Resume and Start over, "Ends at" time, a **Stream** glass card (resolution, HDR, video codec and bitrate, audio, subtitles, size, buffer ahead, peers/seeds, speed, bitrate sparkline over the last 60 s), chapter row with the current chapter highlighted, wavy scrubber.
   - **Wavy scrubber spec (exact):** a sine wave, amplitude about 13px, wavelength about 150px, across the bar. Watched portion = three hard-stepped bands, outer maroon `#5D1710` (full width), middle `#E2562B` (2/3), centre `#F6BF33` (1/3), round caps, **no glow or shadow**. Unwatched portion = a single solid neutral `#4A423B` at 70% of the band width. Chapter markers are small rings (6px radius, 3px cream stroke, dark fill) on the wave. Playhead = solid off-white `#F7EFE0` circle (r 17) with only a soft radial glow (r 46, cream at about 55% to 0%), like a bulb: no bullseye, no rings. Implement as a custom `View` using `Canvas`/`Path` (sample the sine every 4px), with a clipped or second path for the watched part.
5. **Stream-loading screen (`30`):** replace the Android toast with the inline status text, a three-step trail (Finding peers, Connecting, Buffering), seeds/peers/speed chips, striped indeterminate bar, and the decorative ribbon (fades out, masked).

### Data: only show what is real
- Chapters and thumbnails: Jellyfin exposes chapters (`/Items/{id}?Fields=Chapters` plus `/Items/{id}/Images/Chapter/{index}`) and trickplay tiles; check `data/jellyfin/` and what the server in use actually returns. If chapters or thumbnails are missing, degrade gracefully (hide the chapter row, fall back to a time-only scrub preview). Do not fabricate data in production code.
- Stream stats: resolution, codec, bitrate, audio and subtitle info from the Media3 player (`Format`, `BandwidthMeter`); peers, seeds, speed and size from `data/movies/Qbit.kt` and `data/stream/*`. Fields without a source are hidden, not faked.
- Ratings, certificate, provider, and "Ends at" come from the existing film models; compute "Ends at" from the current time plus remaining duration.

### Constraints
- Android Views and the existing focus system (`SpatialNavigation`, `FastFocus`, `FocusUtils`, `CalmScroll`). Preserve D-pad behaviour and remembered focus; new focus visuals go through the shared helpers in `design/LauncherTheme.kt`.
- Performance on a TV SoC: avoid full-screen blur per frame (pre-blur or use a downscaled blurred bitmap for the paused screen), avoid overdraw, cache the arch/ribbon drawables, keep scrubbing at 60 fps (do not rebuild the wave `Path` every frame; rebuild on size change only).
- Do not regress the existing tests in `app/src/test` or `androidTest`. Add unit tests for any new pure logic (wave sampling, chapter lookup, "ends at", voice-state machine).
- Don't break the other launcher sections that share `design/`; theme changes must be scoped to Cinema or backwards compatible.
- Never put secrets in code.

### Process
1. **Plan first** (plan mode): read all mockups, `_src/mock.html`, and the existing Cinema code, then propose a phased plan with file-level changes and risks. Wait for approval.
2. Phases, each its own commit, each leaving the app building and runnable:
   1. Design tokens (colour, type, stripe/arch/ribbon drawables, logo vector), nav rail.
   2. Movies home, rows, poster and badge components.
   3. Film detail screens (Back button removed) and My library.
   4. Downloads, stream-loading screen.
   5. Search with QWERTY keyboard and voice.
   6. Player: wavy scrubber view, controls, scrubbing preview, paused info screen.
3. After each phase: `.\gradlew.bat :app:assembleDebug`, install on the emulator or the TCL (`adb -s <serial>`, select explicitly), capture `adb exec-out screencap -p`, and **compare side by side against the matching mockup**; fix deviations in spacing, colour, type and focus states. Note: the TV composites video in hardware, so player screenshots show a grey video plane; judge the controls only.
4. Finish with a summary: what matches the mockups, intentional deviations (with reasons), anything blocked by missing data, and a list of screenshots captured.

If a mockup detail is impossible or unreasonable on Android Views (for example a CSS backdrop blur), pick the closest native approach and say so, rather than silently diverging.
