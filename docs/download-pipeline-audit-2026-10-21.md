# Download pipeline audit — 21 October 2026

The fixes are deployed on the Steam Deck, upstairs Chromecast
(`192.168.1.220`) and downstairs TCL (`192.168.1.217`). Source changes are on `codex/cinema-left-navigation` in
`C:\Users\danie\AndroidTVLauncher`; they have not been pushed or merged.
The installed APK SHA-256 is
`9eff3efb4f05edd63b9254fe25b3a5e41b590910f8fc2f4b55dd1d2f546c280c`.

After reinstall, Google's recovery Home activity again overrode MCM upstairs.
Android refused component-level disabling. The user then explicitly approved
disabling the entire recovery/setup package for that TV user. Both
`com.google.android.tungsten.setupwraith` and `com.google.android.apps.tv.launcherx`
are now disabled for user 0 upstairs. The changes are reversible with `pm enable`.
Home was tested from Settings on both TVs and opened MCM. The installed APK hash
on each TV matches the tested build above; existing settings and sign-ins were
preserved by installing with `adb install -r`.

## The FileMood gaps

The active log files contained these warnings at the initial audit:

| Application | Warnings | Continuous span covered by the warning intervals (UTC) |
| --- | ---: | --- |
| Sonarr | 58 | 20 October 19:43:50 → 21 October 10:58:31 |
| Radarr | 2 | 21 October 09:36:49 → 10:37:49 |

UK/Ireland local time was UTC+1. Radarr's two individual intervals were
09:36:49–10:07:19 and 10:07:19–10:37:49 UTC. Sonarr's intervals were consecutive,
mostly about 15½ minutes each.

These warnings mean the returned RSS entries did not establish uninterrupted
coverage since the previous sync. They do not identify particular missing
episodes or prove a torrent disappeared. Explicit title searches are separate
from RSS monitoring. [Servarr's explanation](https://wiki.servarr.com/sonarr/faq).
FileMood now remains enabled for automatic/interactive searches, with its
unreliable RSS disabled through a separate Prowlarr sync profile.

## Why the requests had no torrents

- Seerr had six approved season requests: Rick and Morty S1/S2, Smiling Friends
  S2, and Sabrina S5/S6/S7. Sonarr and Radarr initially had empty download queues.
- Rick and Morty S2 and Sabrina S7 had no recorded episode search time.
- Sonarr imposed a 1,536 MB absolute release cap, including whole-season packs.
  Radarr imposed a 4,096 MB cap. Returned releases were being rejected for size.
- All requested shows required 1080p, rejecting SD releases of the older Sabrina
  series and 720p alternatives. Indexers also required five advertised seeders.
- 1337x was backing off after repeated Cloudflare failures. Radarr/Sonarr's
  qBittorrent connection tests passed.
- qBittorrent 5.2.4 uses a 204 login response and `QBT_SID_<port>` cookie. The
  launcher's old client required 200/`Ok.`/`SID`, so its login/priority controls
  could report failure after successful authentication.
- qBittorrent allowed only two active downloads and counted slow torrents
  against those slots.

## Changes deployed

- Replaced the absolute size caps with the existing duration/quality size rules.
- Applied 1080p preference with 720p fallback; shows first released before 2005
  can use SD through a separate profile. Deliberate custom profiles are kept.
- Minimum advertised seeders is one. Zero-seeder search results remain rejected;
  actual swarm availability is still checked separately in qBittorrent.
- Increased active downloads to four and active torrents to eight. Torrents
  below 128 KB/s download / 16 KB/s upload after 120 seconds do not consume
  active slots. The normal download speed remains uncapped.
- Added Knaben after it passed its connection test. EZTV failed with HTTP 451
  and was not added. Existing 1337x remains subject to its failure backoff.
- Added an authenticated server service on port 5056. It reconciles explicit
  approved Seerr requests every 30 seconds and retries unsuccessful searches
  after 15 minutes, 1 hour, 6 hours, then daily. The TV need not remain open.
- Added conservative replacement of unforced, entirely empty torrents that
  remain stalled without sources for 30 minutes, at most twice per task/day.
  Queued, paused, forced and partially downloaded torrents are preserved.
- Completed imports trigger a debounced Seerr recently-added scan.
- The launcher shows actual qBittorrent status, priority, progress, speed and
  seeders. Season torrents are grouped once rather than repeated per episode.
- Pending requests show search state and scheduled retries; unsuccessful
  searches offer Search again. A season's download cannot hide another season's
  request. Connection failures are distinguished from an empty request list.
- Priority controls use the existing Seerr session and a server-side downloader
  session. No downloader password is required on the TV. Legacy direct clients
  also support qBittorrent 5.2. Cancellation stops monitoring the selected files
  before removing their torrent, preventing automatic reacquisition.

The service source, unit, tests and operational notes are in
`server/request-pipeline/`. Previous settings are backed up privately on the Deck
under `/home/deck/.local/share/media-stack/pipeline-backups/`.

## Verification and remaining limits

All six original season searches grabbed a torrent after the settings repair.
Rick and Morty S1 (11 episodes), S2 (10) and Smiling Friends S2 (8) have imported;
Seerr marks all three requests complete. Smiling Friends was also verified
directly in Jellyfin. A subsequent Twin Peaks request was observed progressing
at about 11.6 MB/s; all eight S1 episodes subsequently appeared in Jellyfin.
The final direct Jellyfin check confirmed 37 episodes across these four seasons.

Sabrina's three torrents reached qBittorrent, but their swarms have scarce
sources. S6 was still fetching metadata; S5/S7 had partial progress and low
rates. The added Knaben source returned no S6 matches in its test search. Those
conditions are now visible on the TV; the Ethernet link cannot supply pieces
that no connected peer has. Instant starts or 500 Mbps cannot be guaranteed for
every title.

The launcher build passes 190 tests. The backend passes 17 focused tests,
including authentication/ownership, cancellation, deduplication, backoff,
qBittorrent 204 handling and safe empty-torrent recovery. Unauthenticated access
to request state returns 401. The TV's Download now action was verified to set
force-start on its selected torrent; its original normal handling was restored.
Download next was also exercised on the torrent already first in line, retaining
its normal queue handling and leaving the order unchanged.

## Follow-up: partial stalls and release selection (21 October, 13:23 Dublin)

The earlier empty-only recovery rule is superseded. Active torrents with no
connected seeders, availability below one complete copy, and under 64 KiB/s
sustained progress for 30 minutes can now trigger an alternative trial even
after partial progress. Queued, paused, checking, force-started and completed
downloads remain protected; gaps between polls reset the observation window.

Recovery retains the original while trying one alternative, within the existing
two-trials-per-task-per-day cap. It ranks acceptable results by resolution tier,
exact seed count, custom-format score and size. Only queue-conflict rejections
are bypassed; wrong seasons, multi-season packs, zero seeds, blocklists,
quality/size restrictions and unwanted/owned episodes remain excluded. Failed
release names are remembered across indexers for seven days. A trial gets the
next normal queue slot once, without force-starting or explicitly pausing any
other torrent. Intent is persisted before the release POST to avoid duplicate
trials after timeouts or restarts.

The original is retired only after a replacement completes, or has a complete
connected source transferring at least 128 KiB/s for two consecutive minutes.
Both torrents are rechecked immediately before retirement. Failed alternatives
are blocklisted while the original is preserved. Healthy redundant partials are
not blocklisted when a replacement completes. Native failure-triggered searches
are suppressed during these controlled retirements.

MCM's dedicated Sonarr profiles 7/8 and Radarr profile 7 now group allowed
qualities into SD (older TV only), 720p and 1080p tiers, with 1080p preferred.
Custom profiles and upgrade policies are preserved. All Prowlarr indexers now
use priority 25, verified after sync in both Sonarr and Radarr. Previously
TorrentProject2 and FileMood had priority 35, which lost comparisons before
seed counts were considered. Rollback data is private on the Deck under
`pipeline-backups/selection-20261021T121641Z/`. The reproducible configuration
script is `server/request-pipeline/configure_selection.py`.

Live verification:

- S5's old 21.9 GB DVD pack remained stalled at 0.95%, no connected seeds. A
  smaller 11.0 GB AMZN web release connected to three seeders, reached about
  5.1 MB/s and 6.3%, and the worker automatically retired the old pack after
  verifying sustained transfer. No completed library files were removed.
- S6's original had been re-grabbed across indexers by the old native retry.
  It received a normal queue slot; a distinct 11.2 GB AMZN alternative was
  submitted through the same recovery path. At this checkpoint both were
  fetching metadata. The original is retained until the trial is verified.
  The next check confirmed the alternative transferring at 3.8 MB/s with four
  connected seeders (1.7% complete); its sustained-health check was underway.
- S7 recovered a complete connected seeder and was kept. At the checkpoint it
  was 9.8% complete and transferring at about 1.3 MB/s.

The final backend passes 34 tests on the Deck, including partial stalls,
cross-indexer duplicate release names, cancellation during search, poll outages,
daily caps, queued/paused protection, both Arr rejection vocabularies, fresh
checks, sustained health, temporary recovery and non-blocklisting cleanup of
healthy redundant partials. This change is server-side and applies to both TVs
without another APK installation. It is deployed and copied into the current
`codex/cinema-left-navigation` branch alongside the launcher changes.
The deployed worker SHA-256 matches the repository copy. Live checks returned
200 for `/health` and 401 for unauthenticated `/requests` and `/torrents`.

Advertised seeds are not verified sources. The live S5 test proves a better
usable alternative existed; it does not establish that every advertised torrent
will connect, complete, or saturate the Deck's Ethernet connection.
