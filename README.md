# MCM Launcher - TCL Google TV

The original MCM launcher for **Daniel and Eva's home in London**, installed
on their **TCL Google TV**. This is the native Android version, maintained on
`feature/spatial-motion-experiment`.

The launcher includes Daniel, Daniel (UK), and Eva profiles, Eva's public
Letterboxd watchlist with film artwork and availability, the Sideboard projects,
recipes, listening sections, installed-app discovery, app organisation, search,
and the painting collection with full-screen Art mode. Profiles select the
name shown in MCM; they do not sign in to streaming services.

## Household versions

| Household | Device | Branch | Main source |
| --- | --- | --- | --- |
| Daniel and Eva, London | TCL Google TV | [`feature/spatial-motion-experiment`](https://github.com/DanielPortelaByrne/mcm-launcher/tree/feature/spatial-motion-experiment) | `app/` |
| Mariana and Sean's house | LG webOS TV | [`feature/lg-webos`](https://github.com/DanielPortelaByrne/mcm-launcher/tree/feature/lg-webos) | `webos/` |
| Daniel's parents' house | Amazon Fire TV Stick | [`feature/fire-tv`](https://github.com/DanielPortelaByrne/mcm-launcher/tree/feature/fire-tv) | `app/` |

The LG version uses the Bandit and Aries profile and highly rated public film
picks. The Fire TV version retains the original Daniel and Eva experience,
with compatibility fixes for its older Android system. Each branch has its own
README and deployment notes. Device configuration is specific to each household.

## Build and open

Use JDK 17 and the Android SDK configured through `local.properties`.

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.tvlauncher/.MainActivity
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`; the application ID is
`com.example.tvlauncher`. Select the target TCL explicitly when multiple ADB
devices are connected. Installation alone does not change the system Home app;
see the default-launcher and physical deployment notes below.

The sections below retain the original design, architecture, and TCL setup
reference. Earlier emulator observations are historical checks, not guarantees
about a different TV or firmware.

## Design rationale

Two sources fed the design, both inspected live rather than guessed at (no
copies of either are stored in this repository, per instructions):

1. Photos of the actual living room this launcher is for. A warm, eclectic
   mid-century interior, not a "cold Bauhaus" one: a deep teal velvet
   sectional sofa; mustard and rust/burgundy cushions; a terracotta-and-cream
   checkerboard rug; honey-walnut open shelving and a low media console full
   of books and records; brass hardware and black window frames; warm amber
   globe floor lamps; cream walls.
2. The owner's Pinterest boards ("Room Design," "Midcentury artwork," "Wall
   art," "Mid Century Dining Table"). These confirmed and sharpened the room
   photos: orange/rust reads as the *dominant* accent (burnt-orange
   bathrooms, an orange sofa, orange mushroom lamps), checkerboard and
   striped rugs recur constantly, colour is used boldly and saturated (not
   muted), and -- most usefully for the UI -- MCM still-life art in these
   boards consistently backs its subject with a solid, opaque, contrasting
   geometric shape: a cactus in front of a solid sun disc, a plant in front
   of an arch. A literal Dieter Rams book, pinned next to a sculptural chrome
   object and bold primary-colour book spines, also confirmed the
   Rams/Braun restraint the original brief called for -- but restraint in
   *information discipline*, not in colour or graphic confidence.

That combination ruled out both the generic cold-blue/lime/purple "MCM"
preset *and* a first pass at this launcher that was too quiet and too flat:
muted tones, translucent icon rings, and a large empty negative-space area
read as "an Android grid with a dark theme," not as furniture. The current
version corrects that:

- **Design system (2026-09-22)**: every value is a token. Colour *roles* in `res/values/colors.xml`
  (surface, raised, selected, text, muted, accent, action, focus; teal, olive and walnut are for art only),
  an 8dp spacing grid, a 48dp page margin, a 40dp section gap and three radii in `res/values/dimens.xml`,
  and six text styles (Display, Title, Heading, Body, Caption, Eyebrow) in `design/Type.kt` and
  `res/values/themes.xml`. Icons are one set on a 24dp grid with 2dp round strokes (`res/drawable/ic_*`).
- **One focus rule**: whatever is focused lights in ivory. Text objects fill ivory with ink text; round app
  icons get a soft ivory halo and a lit name; image cards (Continue watching, paintings, prints, the recipe
  stack, the decks) get an ivory mat. All share the same lift and crossfade (`design/LauncherTheme.kt`).
- **Page**: one scrim from the left and bottom (`home_vignette.xml`), no boxes behind headings. The page and
  every rail fade at their edges instead of cutting (`ui/CalmScroll.kt`; this TV disables Android's own
  fading edges, so the fade is drawn there). One hint line, bottom-right, for the focused item's hold action
  (`ui/PageHint.kt`).
- **Overlays own the screen**: while All apps, Search, Edit your apps, a sheet or Who's watching is up, the page
  is hidden and the painting blurred (`MainActivity.syncObscured`).
- **Your apps holds exactly seven** (`PRIMARY_SHELF_LIMIT`); 7 apps + All apps fill the row between the
  margins. Moving an app past the line (Edit your apps, or hold OK on a More apps card) swaps it.
- **Icons**: Original by default; *Make it yours → Icons* switches to the MCM style (`design/IconStyle.kt`).
- **Only your apps**: the TV maker's preloaded utilities (`com.tcl.*`) are hidden. Live TV stays reachable
  from the header (`data/AppRepository.kt`).
- **Start-up**: the watchlist, app icons and painting thumbnails load off the main thread (cold start ~3.7 s,
  from ~9.9 s). Debug builds log each start-up step: `adb logcat -s Boot`.

The design audit, system and before/after screenshots: https://claude.ai/artifact/9BrLUYCg8iFjhhPHM7YgKq
(prompt in `docs/ui-polish-prompt.md`; source backup before the redesign in `artifacts/backups/`).

## Architecture

```
data/
  AppEntry.kt / AppRepository.kt -- installed launchable apps (owned only by default)
  Favorites.kt                   -- shelf order + overflow ("More apps")
  Watchlist.kt / FilmInfo.kt     -- Eva's Letterboxd watchlist; poster, UK availability, rating
  ArtLibrary.kt                  -- the MCM paintings
design/
  LauncherTheme.kt               -- surfaces, focus treatment, motion
  RoundIcon.kt / IconStyle.kt    -- round + MCM icon rendering, and the style switch
  Avatar.kt                      -- monogram avatars
system/
  CapabilityActions.kt           -- settings / inputs / Live TV / app info actions
  RemoteKeyService.kt            -- remote Settings button: press = Settings, hold = picture panel
  PictureControl.kt              -- TCL picture-quality calls (backlight, preset, contrast...)
  AccountsRepository.kt          -- Google accounts the owner has picked
ui/
  HeaderBar.kt, ShelfLayoutBuilder.kt, AllAppsPanel.kt, EditAppsPanel.kt,
  SearchPanel.kt, HomeCollection.kt, HomeBackdrop.kt, AccountMenu.kt, ArtModeOverlay.kt,
  PictureOverlay.kt
MainActivity.kt                  -- wires it together
```

Visual constants (colours, spacing, corner radius, icon sizing, motion
durations) are centralised in `res/values/colors.xml`, `res/values/dimens.xml`
and `design/LauncherTheme.kt` rather than scattered through the layouts.

### App discovery

Real installed apps only, never hardcoded tiles. Queries both
`CATEGORY_LAUNCHER` and `CATEGORY_LEANBACK_LAUNCHER` (TV apps commonly only
declare the latter), builds the launch `Intent` directly from the resolved
`ActivityInfo` (not `getLaunchIntentForPackage()`, which silently misses
LEANBACK-only apps), and excludes the system settings package (resolved
dynamically via `ACTION_SETTINGS`, not a hardcoded package name) since the
launcher already has its own settings affordance. See
`AndroidManifest.xml`'s `<queries>` block -- required for
`CATEGORY_LEANBACK_LAUNCHER` visibility under Android's package-visibility
rules.

### Favourites / overflow

`selectFavorites()` shows discovered apps on the favourites shelf up to
`PRIMARY_SHELF_LIMIT` (7), sorted alphabetically by default. Additional apps
appear in the featured rail; the All Apps tile opens a uniform directory grid
(`AllAppsPanel`).

## Rearranging apps

Two routes, both on the remote:

1. **Hold OK** on a shelf app to pick it up: it lifts, and Left/Right slide it along the shelf (neighbours glide aside). Let go of OK after sliding, or press OK again, to drop it; Back cancels. The drag keys are handled in `MainActivity.dispatchKeyEvent`, so focus can't wander mid-drag.
2. **Hold OK on a card in *More apps***: it lifts into the last slot of *Your apps* and the same drag starts, so Left/Right place it. The shelf holds 7, so the app that was last on the shelf drops into *More apps*. Back undoes it.
3. **All apps -> Edit your apps** (or *Make it yours -> Organise apps*): three groups (Your apps, More apps,
   Hidden). Up/Down move an app; crossing the Your apps line swaps it. *Hide/Show* takes it off home entirely.

## Accounts

The header avatar shows the current account; its dropdown lists your accounts, *Switch profile*
(the "Who's watching?" picker) and *Manage accounts*. The same full-screen picker appears once per
boot and after 10+ minutes of standby (`ui/WhoPicker.kt`). Google TV's own chooser lives in the
stock home, which is disabled (see below), so the launcher provides its own. Selecting an account
changes what this launcher shows; it does not change Google TV's system profile.

Profile photos: put a square image in `assets/home/avatars/` and name it in `accounts.json` (`"photo": "home/avatars/daniel.jpg"`); accounts without one get a coloured monogram. Only Daniel has a photo so far (cropped from the old Google TV chooser); Eva and Daniel (UK) need images.

Accounts are stored in the app's `accounts` prefs as `email|Display name`. Android hides Google
accounts from apps until they are picked in the system picker, so the first run was seeded with
`adb shell run-as com.example.tvlauncher` (three accounts); *Add an account here* also works.

## Eva's film card

OK on the card opens a menu; **Open in Stremio** goes to the film's page in Stremio (`stremio:///detail/movie/<IMDb id>`, with the IMDb id read from the Letterboxd page), falling back to Stremio's search if there is no id. Stremio has no "play now" link, so you press play on its page.

Picks a random film from Eva's public Letterboxd watchlist and shows the poster and the
Letterboxd average rating, plus where it streams or can be rented in the UK. Availability and
posters come from JustWatch's public GraphQL endpoint (`apis.justwatch.com`, country `GB`),
which is unofficial and could change; if it fails the card shows without them. Results are
cached for 12 hours.

## Default launcher

This launcher is the Home-button target. Google TV's home has a system priority that a
third-party app cannot outrank, so two packages are disabled for user 0:

```
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

(`setupwraith` also declares a HOME activity, `RecoveryActivity`, which otherwise wins once
`launcherx` is gone.) To go back to the stock home:

```
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
```

Side effects of disabling `launcherx`: no Google TV recommendations rows, no stock profile chooser
(replaced by `WhoPicker`), no "installing" cards, and the remote's Settings button does nothing
(restored below). A factory reset restores everything.

## Settings button (picture panel)

`launcherx` used to handle the remote's Settings key, so with it disabled the button went dead.
`system/RemoteKeyService` (an accessibility service that only filters keys and reads no screen
content, and runs in its own `:keys` process because every remote key waits on it) now handles the Settings button in every app (the TCL remote sends `KEYCODE_NOTIFICATION`; `KEYCODE_SETTINGS` is handled too):

- **Press**: opens Settings.
- **Hold** (550 ms): `ui/PictureOverlay` slides in from the right over whatever is playing. It never
  takes focus, so the app keeps playing. ▲▼ choose, ◀▶ adjust (hold to speed up), Back or Settings
  closes it, and it hides itself after 12 s idle. Rows: picture preset, backlight, brightness,
  contrast, colour, sharpness, colour temperature, then *More picture settings* (TCL's own panel)
  and *All settings*.

The same service maps the remote's shortcut buttons (`APP_KEYS` in `RemoteKeyService`; add more
the same way, and find a button's code with `adb logcat -s RemoteKeys`):

| Button  | Key code                 | Opens                                      |
|---------|--------------------------|--------------------------------------------|
| YouTube | `KEYCODE_BUTTON_3` (190) | SmartTube (YouTube if SmartTube is removed) |
| Netflix | 4062 (TCL)               | Netflix                                    |
| Media   | 4057 (TCL)               | Stremio (instead of TCL's Media Center)    |

Values are read and written through TCL's picture library (`system/PictureControl`, reflection over
`com.tcl.tv.pq.TvPqManager` / `TvBacklightManager` from the optional `com.tcl.tv.display` library),
using the same calls as TCL Settings: window 0, TCL's "apply to current source / all" choice,
exec + save. The preset row lists the SDR presets only (Standard, Vivid, Movie, Sport, Game, Mild).
HDR/Dolby content uses other preset numbers, which show as "Custom"; change those from *More picture
settings*. If the library can't be reached, holding Settings opens TCL's own panel instead
(`ShowWindowService`, `Type=picture`).

Setup (once, over ADB). TCL's app-boot guard blocks services of apps without its `AUTO_START` op
(this also affects `MediaWatcherService`), so grant that first:

```
adb shell appops set com.example.tvlauncher AUTO_START allow
adb shell settings put secure enabled_accessibility_services com.example.tvlauncher/com.example.tvlauncher.system.RemoteKeyService
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.example.tvlauncher android.permission.WRITE_SECURE_SETTINGS
```

The last line lets the launcher switch the service back on by itself: at boot this TV can drop it from the
enabled list (it starts before storage is unlocked), which leaves every mapped button dead. The service is
now `directBootAware`, and `MainActivity.ensureRemoteKeysEnabled` re-enables it on every return to Home.

ADB-injected keys (`input keyevent`) skip accessibility filtering, so test with the real remote.
Debug builds log every key (`adb logcat -s RemoteKeys`) and can open the panel with
`adb shell am broadcast -a com.example.tvlauncher.DEBUG_PICTURE` (add `--es key KEYCODE_DPAD_DOWN`
etc. to drive it).

## Personal home: sideboard, tonight, recipe cards

`ui/room/HomeRoom.kt` lays three pieces into the page (below *More apps*):

- **Project shelf** (`ProjectShelf`, `ProjectArtView`): projects as painted objects on a walnut
  ledge. Focus lifts and lights the object and shows its title and "Next:" line; OK opens an
  `InfoSheet`.
- **Tonight?** (`TonightCard`): a framed paper print with Watch / Make / Eat lines. One suggestion
  of each per day (`TonightPlanner`, stable all day). OK opens the matching sheet.
- **Recipe cards** (`RecipeCardStack`): a small stack of vintage cards, opening on tonight's
  recipe. OK shuffles through a bag (no repeats until all are seen).

Data is read through `ProjectSource`, `RecipeSource` and `FilmSource` (`data/home/HomeData.kt`),
currently backed by JSON in `app/src/main/assets/home/`:

- `projects.json` - `id`, `title`, `nextAction`, `art` (`figurine`, `tayto`, `mirror`, `sofa`,
  `generic`), optional `status` and `notes`.
- `recipes.json` - `id`, `title`, optional `cuisine`, `descriptor`, `theme` (`sun`, `arch`,
  `leaf`, `bands`, `circles`). `AssetMesaRecipeSource` is where the real MESA catalogue plugs in.
- `films.json` - `id`, `title`, optional `year`, `note` (placeholder picks).

To draw a new kind of project object, add an `ArtType` value and a `drawXxx` function in
`ProjectArtView`. The three project entries and film picks are placeholders.

## Now spinning (Eva and Daniel's turntables)

`ui/room/Turntables.kt` draws two painted record decks on little walnut sideboards, one for each of
you. A playing deck turns its record, swings the brass tonearm onto it and breathes a warm glow; a
paused deck keeps the arm down; "recently played" and "nothing" park the arm. Each has a propped
sleeve (real artwork when there is a URL, otherwise a painted cover made from the track's name)
and a brass plaque with title and artist. OK opens a detail sheet.

Everything maps into `PersonalListeningState` (`data/listening/Listening.kt`), with `Person`,
`MusicProvider`, `ListeningStatus` (`PLAYING_NOW`, `PAUSED`, `RECENTLY_PLAYED`, `NOTHING_AVAILABLE`)
and `observedAtMs` (epoch ms, since `Instant` needs API 26). Sources implement `ListeningSource`.

Right now it shows **demo data** (entries in `assets/home/listening.json`, cycling every 90 s).
For real listening, fill in `lastfmUser` and `lastfmApiKey` for a person in that file: Spotify
and Apple Music can both scrobble to Last.fm, which reports "now playing" and artwork, and
`LastFmListeningSource` reads it. It polls every `pollSeconds` (default 30) only while home is
visible. Last.fm does not report pause state or track position, so real data is `PLAYING_NOW`,
`RECENTLY_PLAYED` (within 3 hours) or `NOTHING_AVAILABLE`. The API key lives in the APK, so keep
this build private.

## Continue watching

Android gives a launcher no access to other apps' "continue watching" data (reading Watch Next
needs a privileged permission), so `system/MediaWatcherService` (a notification listener) watches
apps' **media sessions** instead and `data/ContinueWatching` stores what it sees: title, the artwork
the app supplies, and the position. It saves after 60 s of playback, refreshes every 20 s while
playing, drops titles you finish (95%), ignores music apps, and only knows about playback that
happened with this launcher installed. The row (`ui/ContinueRow.kt`) sits above *Your apps*, hidden
when empty. Long-press a card to remove it.

**Thumbnails for SmartTube / YouTube cards.** SmartTube reports the title, channel and length of what is
playing, but no video id, so the launcher (1) uses any image address the session offers (`ALBUM_ART_URI`
and similar; YouTube thumbnails are served from a fixed address per video id), and otherwise (2) searches
the YouTube Data API for the title and takes the thumbnail of the result whose title matches exactly (or
starts the same), preferring the same channel. No confident match means no thumbnail, never a wrong one.
Step 2 needs a free API key (Google Cloud console, "YouTube Data API v3"; the free quota is about 100
searches a day). Keep it on the TV only:
`adb shell "run-as com.example.tvlauncher sh -c 'mkdir -p shared_prefs; cat > shared_prefs/youtube.xml'"` with
`<map><string name="apiKey">YOUR_KEY</string></map>`.
**Clicking a card opens the actual video.** Each card records what it points at in `ResumeItem.mediaId`
(`data/ResumeLinks.kt`): `youtube:<video id>` for SmartTube and YouTube (found by the title search above),
and `imdb:<film id>` for films handed to Stremio. A click then opens `youtube.com/watch?v=<id>&t=<seconds>` in
the same app, or `stremio:///detail/movie/<id>/<id>`. A Stremio card without a film id opens Stremio's search for
the title. Other apps (Netflix and so on) expose no id, so their cards just open the app.
Cards show only the title and a progress bar. On this TCL TV the listener service only binds while the
launcher process is running (`TclAppBoot: forbid bind service`), which it is while it is the home app.

Setup (once, over ADB): `adb shell cmd notification allow_listener com.example.tvlauncher/com.example.tvlauncher.system.MediaWatcherService`

Limits: the card shows the app's poster/still, not a frame of the video, and OK **opens the app**.
It only jumps to the saved time if the app is still open in the background with that title
(`MainActivity.seekIfStillOpen`); otherwise you land on the app's own screen and resume there.

## PLAY: smart stream selection

Eva's film sheet has a **PLAY** button that picks the best version this TV and connection can
comfortably sustain and plays it in the launcher's own Media3 player. **Open in Stremio** stays as the
manual route, and **Change version** lists the ranked options.

Pipeline (`data/stream/`): film IMDb id (from Letterboxd) -> the addons you configured
(`StreamSources`, standard Stremio `/stream/movie/<id>.json`) -> `StreamParser` (resolution, source,
codec, HDR, audio, size from names; unknown stays unknown) -> `DeviceProfile` (panel height,
hardware decoders, HDR/Dolby Vision, audio) -> `NetworkEstimator` -> `StreamRanker` -> `PlayerActivity`.

How it decides:
1. Drop what the TV cannot show (resolution above the panel, unsupported codec, cam rips).
2. Estimate the bitrate each stream needs: file size / runtime (runtime from Cinemeta) times a peak
   factor (REMUX 1.5, BluRay 1.4, WEB 1.25), or a typical figure if size is unknown.
3. A stream is buffer-safe if it needs less than 65% of the bandwidth estimate. Too-heavy streams are
   only used if nothing else can play.
4. Score: resolution + source + HDR + codec + audio (only credited if the TV supports it), plus
   reliability from history (fast/slow startup, past buffering, past failures). All numbers are in
   `RankingWeights` (`StreamRanking.kt`).
5. The top stream's link is probed first (dead or expired links are dropped). The player tries up to
   three versions, then falls back to Stremio.

Signals feeding the ranking: the rolling bandwidth estimate (small ranged downloads plus playback
throughput, blended toward Android's link estimate when stale) and per-provider history
(`PlaybackStore`: startup time, buffering, failures; recency-weighted with a 30-day half-life).
Pausing, seeking and leaving are never counted as buffering or failure.

**Torrent health and learning.** Torrentio-style results carry a seeder count. For torrents: 0 seeders
is rejected, 1-2 costs 150 points, 3-7 costs 50, 30+ earns 30 and 100+ earns 60 (`RankingWeights`).
The best version is worked out in the background as soon as the film card shows, so PLAY is ready
when the sheet opens. When a film is handed to Stremio, `MediaWatcherService` watches Stremio's media
session (startup measured from when its player opens, stalls over 3 s, play time) and files one record
per session into the same per-source history the launcher's own player uses. A film that never starts
within 4 minutes counts as a failure; pausing and short seeks never do.

**Stream source = your Stremio account.** Press PLAY once on a film and, if Stremio isn't connected,
the launcher asks you to sign in (email and password, typed with the remote). It imports the movie
stream addons already installed in your Stremio (Torrentio and so on), so Stremio stays the single
source. Only the sign-in token and the addon list are stored; the password is not. For development a
hand-written `files/stream_addons.json` on the TV overrides this.

**How PLAY plays it.** Most addons return torrents. Those are handed to **Stremio's own player**
through a `magnet:` link: Stremio resolves the exact file and opens its page for it, one press from
playing, with its own subtitle, audio and speed controls. Stremio offers no way for another app to
press that play button. Direct `http(s)` links (rare) play in the launcher's own Media3 player instead,
which also saves a real video frame for Continue watching. Debug builds log each decision
(`adb logcat -s SmartStream`); URLs are never logged.

## Build

```
cd AndroidTVLauncher
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Install on the emulator

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Make it HOME in the emulator

```
adb shell cmd package set-home-activity com.example.tvlauncher/.MainActivity
adb shell pm disable-user --user 0 com.google.android.tvlauncher
adb shell input keyevent KEYCODE_HOME
```

The `pm disable-user` step is necessary because the stock launcher declares
a higher manifest intent-filter priority than a role/preferred-activity
registration can override (see prior POC notes in git history). It's
reversible:

```
adb shell pm enable com.google.android.tvlauncher
```

## Physical TCL Google TV deployment notes

The launcher is installed on the household TCL Google TV and verified on the
physical screen with the remote. The system's default Home app remains Google TV's;
the app is opened from its own icon (see *Default launcher* above).

1. To reinstall or verify a later build, sideload it first:
   `adb install -r mcm-launcher.apk`, launch it directly (tap its icon
   under Apps), confirm it renders and an app launches successfully --
   **before** changing anything about HOME resolution.
2. Identify the TCL's actual default-launcher package before changing
   anything:
   `adb shell pm resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME`
   It will almost certainly differ from the emulator's
   `com.google.android.tvlauncher` -- TCL's Google TV build ships its own
   OEM home package. Do not assume it matches; read the actual output.
3. Prefer the non-destructive path first: TCL/Google TV normally exposes a
   default-launcher picker under Settings > Apps > Default Apps > Home app
   (path may vary by firmware). If that picker lets you select this
   launcher without disabling anything, use it -- it's instantly
   reversible from the same menu.
4. Only if the OEM launcher also declares an elevated intent-filter
   priority (as the emulator's did) and the picker doesn't stick, consider
   the same `pm disable-user --user 0 <oem-package>` used above -- but only
   after step 1-3, and only with the exact package name confirmed in step
   2, and only knowing the rollback command
   (`pm enable <oem-package>`) up front.
5. Recovery must not depend on this launcher continuing to work: keep the
   OEM launcher package disabled (not uninstalled) if you do go that route,
   confirm `pm enable <oem-package>` restores it, and know that TCL sets
   also normally allow a factory-menu / recovery-mode reset as a last
   resort if adb access is lost.

None of this is automated by anything in this project -- it's a manual
checklist for when the owner is ready to try it on the real TV.

## Known OEM caveats

- TCL's Google TV firmware may ship a different settings package than
  `com.android.tv.settings`; the settings-affordance intent
  (`android.settings.SETTINGS`) is a standard AOSP action and should still
  resolve correctly, but hasn't been verified against TCL's firmware.
- The overflow ("All Apps") panel's real-world density (5/8/12/20+ apps)
  has only been verified mechanically, by temporarily lowering
  `PRIMARY_SHELF_LIMIT` to force overflow with the emulator's 3 real apps
  (see `artifacts/final-mcm-launcher/final_05_all_apps_overflow_demo.png`).
  It has not been visually reviewed with a real 12-20 app household
  library.
- Banner/icon assets (`ic_launcher.xml`, `ic_banner.xml`) are placeholder
  geometric marks from the original POC, not a finished app icon.

## Artifacts

- `app/build/outputs/apk/debug/app-debug.apk` -- latest built and tested APK.
- `artifacts/final-mcm-launcher/` contains screenshots and logs from the
  previous visual iteration; the current UI is built from the source layouts.
- `artifacts/screenshots/`, `artifacts/logs/`, and `artifacts/app-debug.apk`
  retain the earlier launcher POC and stock-home captures.

## Test coverage

Verified with `testDebugUnitTest assembleDebug`; installed and opened on both
the emulator and TCL Google TV. D-pad focus was checked through the home
navigation, hero action, featured rail, and favourites rail; the TCL screen
was checked with its installed app library. Search, All Apps, launch, edit,
network, settings, Live TV and art-mode behavior continue to use their
existing handlers.

One real bug was caught and fixed during this pass: immediately after
`adb install -r` + HOME, initial focus occasionally landed nowhere (or the
window's own default-focus assignment grabbed the settings dial instead of
the shelf), reproducing intermittently depending on timing. Fixed with a
defensive `onWindowFocusChanged()` fallback in `MainActivity.kt` that
re-asserts focus onto the first shelf item whenever the window regains
focus and nothing inside the shelf currently holds it. Reproduced and
re-verified clean across multiple repeated install+HOME cycles after the
fix.

## Motion

The launcher uses a slow, calm motion language (`design/Motion.kt`):

- **Pans** (page and rails) glide with a "swoosh" curve, ease-out with a long soft tail, 650 to 900 ms, and restart from the current position when the target changes, so held D-pad presses lag and flow rather than jump. `CalmScrollView` / `CalmHorizontalScrollView` disable Android's own snap-to-focus.
- **Focus** crossfades the surface (`TransitionDrawable`), tweens text colour, and lifts with a gentle scale and depth.
- **Select** dips slightly and settles with a soft overshoot (`Motion.press`).
- **Sheets, menus and overlays** float in with a rise and fade (`Motion.enter`).
- Everything respects the system animation scale (0 = instant).
