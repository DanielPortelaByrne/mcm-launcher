# Series page: episode title cards (hand-off plan)

Repo: `C:\Users\danie\AndroidTVLauncher` (Android Views built in code, Cinema design system).
**Everything for this task is in `docs/ui-review/series-episodes/`.** That is this plan, the four mockup PNGs, and `_src/episodes.html` with its `_src/img/` pictures. Look at the PNGs before starting.
Mockups (1920×1080, mockup px = half a dp; convert with `Cinema.px`):
- `1-series-page-season1-play-focused.png`: page at rest, Season 1, Play focused on a part-watched episode
- `2-season2-tab-focused-mixed-states.png`: Season 2 tab focused, mixed episode states, season download button
- `3-scrolled-download-focused.png`: scrolled into episodes, Download focused
- `4-episode-card-states.png`: every card state, with sizes
- Source: `_src/episodes.html` (open `#A`..`#D` in a browser; its CSS values are the spec). Screens A–D are mockups 1–4. The pictures are stand-ins cut from the MobLand poster, not real stills.

## Goal
Replace the Seasons block (heading, big "Request Season N" pill, S1/S2 pills, full-width text rows) with:
1. **A season tab bar**: stripe marker, Fraunces tabs "Season 1  Season 2", info text on the right ("10 episodes · 1 in library · 1 downloading"), and a small season action button.
2. **A horizontal rail of 16:9 episode title cards**. Each card shows the episode's still, with a **Play** disc and a **Download** disc on top of the image. Under the image: the title, then "runtime · air date", and a 2-line overview only on the focused card.

## Where the code is
`app/src/main/java/com/example/tvlauncher/ui/MoviesPanel.kt`
- `seasonSection` (~1662): loads seasons. Keep the loading logic.
- `drawSeasons` (~1682): rewrite the layout (tab bar + rail). Keep the request and sign-in flows.
- `episodeRow` (~1775) + `episodeGlyph` (~1766): replace with `episodeCard(...)`. Keep `label()` state logic and the Sonarr request code; move them onto the buttons.
- `row()` (~1927) gives a horizontal scrolling `Row` with `.inner` (the season pills already use it). Use it for the rail.
- `reveal()` (~2036), `surfaces12`, `pill()` (~2012), `streamNow` (~1587), `JellyfinPlayback.play`.
Design tokens: `design/Cinema.kt` (CREAM, INK, SAFFRON, DIM, MUTED, READING, PLACEHOLDER, GLASS*, STRIPES, RINGS, `text`, `glass`, `icon`, `rowHeading`). Ring drawing to copy: `ui/PlayerChrome.kt:580`.
Icons: reuse `R.drawable.ic_cin_play`. Add `ic_cin_download`, `ic_cin_check` and `ic_cin_clock` vectors if missing. Path data is in `episodes.html` (`IC.dl`, `IC.check`, `IC.clock`), 24×24 viewBox, 1.7 stroke.

## Data changes
1. `data/movies/Seerr.kt`: add `stillPath: String?` to `EpisodeInfo` (and `runtime: Int?` if Seerr's season payload has it). Parse `stillPath` in `SeerrSeasons.parseEpisodes`. URL: `https://image.tmdb.org/t/p/w500$stillPath`.
2. Image choice per episode:
   - If the episode is in the library (`mine`) and has a Primary image, use the Jellyfin URL: `$base/Items/{id}/Images/Primary?maxWidth=480&quality=85`. Send it with the same sign-in headers posters use (`e.posterHeaders` / Jellyfin auth).
   - Otherwise use the TMDB still.
   - With neither, show the placeholder.
   Add `episodeImageUrl(...)` to `JellyfinApi`/`JellyfinClient` next to `posterUrl`.
3. Runtime: use Jellyfin `RunTimeTicks` for library episodes (`runtimeMin` is already parsed for items; check that `episodesUrl` returns it, and add `RunTimeTicks` to `Fields` if not). Otherwise use the Seerr runtime. If neither exists, leave it out.
4. Resume bar: `JellyfinItem.resumeMs` / runtime gives the progress fraction. Show "· 32 min left" in the meta line.
5. Download %: optional. Match `Queue.kt` items (`progress()`, S/E label at line ~43) to the episode. Without a match, show "DOWNLOADING" with no % and an indeterminate (static ¼) ring.
6. Load stills with `images.load(url, headers, maxWidth = Cinema.px(400))` when the card is built. The LRU cache already handles reuse. Guard against a stale season (`token != loading`) as the current code does.

## Card spec (mockup px)
- Card 400 wide. Still 400×225, radius 18, `PLACEHOLDER` fill, gap 40 between cards. Rail starts at x=96 (page margin), like the hero.
- Scrim over the still: top 45% → transparent at 28%, transparent → 55% at the bottom (BG colour).
- Top-left "E4" chip: Inter 15/700 caps, tracking .14, fill rgba(12,8,6,.66), radius 16.
- Top-right status badge, 13/700 caps, tracking .14, on dark glass:
  - `✓ IN LIBRARY` in saffron
  - `DOWNLOADING 42%` in saffron
  - `REQUESTED` in muted
  - No badge for Request / stream-only.
- Centred action pair, gap 22:
  - **Play** disc 78, **Download** disc 62.
  - At rest: fill rgba(12,8,6,.58), 1.5 inset cream 30%, cream icon.
  - **Focused disc**: CREAM fill, INK icon, plus 4 stripe rings of 5 (saffron, orange, terra, red) and a drop shadow. This matches the player's pause button.
- Download disc by state:
  - Request: download icon, focusable.
  - Requested: saffron clock, focusable. OK shows a toast "Requested, waiting for a release".
  - Downloading: saffron progress ring (3.5 stroke) around a saffron download icon, focusable.
  - In library: saffron check, **not focusable**.
  - No tmdb id / can't request: hide the disc and centre Play alone.
- Resume bar: 7 high on the bottom edge of the still. Track cream 20%, fill uses the horizontal stripe bands (`StripeDrawable(horizontalBands = true)`).
- Unaired (air date in the future): still greyscale and darkened, no discs, centred chip "AIRS 21 DEC 2026". One focus stop that does nothing.
- No still: dark gradient placeholder, a large Fraunces episode number at bottom-left in cream 10%, and a 12-high stripe foot at 55% alpha. The discs still show.
- Text under the still:
  - Title: Inter 23/500, `CARD_TITLE`, 1 line, ellipsized. On focus: CREAM, weight 600.
  - Meta: 18, DIM, items joined with dot separators (`Cinema.meta`-style). On focus: MUTED.
  - Overview: 18/300, READING, 2 lines, **only on the focused card**. Reserve the height so the rail doesn't jump.
- **Card lit** while either of its discs has focus:
  - The still scales 1.06 with `Motion` focus timings.
  - Ring: 4 cream + 5 saffron at 28% alpha, plus the 90-blur saffron glow used by focused posters. Reuse the poster focus drawable if there is one.
  - Unlit cards: no outline. Cinema draws no strokes at rest.

## Season tab bar spec
- Stripe marker 14×30 (as `rowHeading`), then one tab per season: Fraunces 36, height 62, radius 31.
  - Unselected: DIM.
  - Selected: CREAM, with a 40×6 stripe underline.
  - Focused: CREAM fill and INK text (the underline hides).
  - Moving focus onto a tab selects that season, as the pills do now.
- Right side:
  - Info text: 21 MUTED, first item CREAM 500, dot-separated.
  - Season action, height 60 (`.btn.sm`):
    - `Availability.NONE`: terra gradient "Download Season N", which keeps the current `seerr.request` flow.
    - Otherwise a glass, disabled label: "Requested ✓" / "Downloading…" / "Partly downloaded" / "In your library". In the all-in-library case, show "All in your library" in the info text and no button (mockup 1).
- Tabs and action are in one horizontal focus row. Down goes to the rail, Up goes to the hero buttons.
- Drop the "Seasons" heading. The tab bar replaces it. Top margin 60 from the hero buttons.

## Focus and D-pad
- Focus stops on a card: Play, then Download. There is no separate row stop; the current 3-stop row goes away.
- Right: Play → Download → next card's Play. Left reverses. Skip any disc that isn't focusable.
- Down from the tab bar lands on the **resume episode's Play** if the season has one, otherwise the first card's Play.
- Up from any card returns to the selected season tab.
- Call `reveal()` on focus so the focused card scrolls fully into view, with the page margin kept on the left.
- Show a tooltip above a focused Download disc ("Download S2 E4"), as in mockup 3. Saffron fill, INK text, 16/600. This is optional but cheap.
- When focus is in the rail, show hints at the bottom: `◀ ▶ Episodes · OK Play / download · ▲ Seasons`, using `Cinema.keyHints`. Optional.

## Actions (keep the behaviour, move it to the buttons)
- Play:
  - In library (`mine != null`): `JellyfinPlayback.play(activity, jellyfin, mine, "${name} · S${s} E${n}", "jf:${id}")`.
  - Otherwise: `streamNow(e, view, s to n, ...)`.
- Download: the current row-click branch. Handle `!seerr.isSignedIn` with the sign-in sheet, then `sonarr.requestEpisodes`. On success, update `states` and rebind only that card (clock state, REQUESTED badge). The toasts stay as they are.
- Season action: the current season request button code, unchanged.
- Optional hero tweak (mockup 1): label "Watch now" as "Resume S1 E2" when Jellyfin has a resume point.

## Suggested structure
- Pull state out into a pure function in a new file `ui/EpisodeCardState.kt`:
  `fun episodeCardState(inLibrary, sonarrState, seasonAvailability, airDate, today, canRequest): CardState`
  with `CardState` = LIBRARY / DOWNLOADING / REQUESTED / REQUEST / STREAM_ONLY / UNAIRED, plus the badge text and which discs are focusable. Unit-test it in `app/src/test/.../ui/`.
- Put `EpisodeCard` (view builder + `bind(state)` + `setStill(bitmap)`) in a new file, `ui/EpisodeCard.kt`, so `MoviesPanel.kt` stops growing.
- Add tests for `parseEpisodes` with `stillPath` present and missing (`data/` tests).

## Done when
- `./gradlew testDebugUnitTest assembleDebug` pass.
- On the TV (adb, as in earlier sessions), MobLand shows the tab bar and cards matching mockups 1–3:
  - Real stills load.
  - Play and Download work from the remote.
  - Requesting an episode flips it to REQUESTED without reloading the season.
  - Up, Down, Left and Right follow the focus rules above.
- Screenshots taken in the same three positions as the mockups and compared side by side.
- A series with no Seerr sign-in still lists library episodes (stills from Jellyfin, Play only).
