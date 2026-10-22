# MCM request pipeline

The server worker reconciles explicit approved Seerr requests every 30 seconds.
It repairs missing season searches, checks native Sonarr/Radarr command state,
and retries unsuccessful searches after 15 minutes, 1 hour, 6 hours and then
daily. Opening the TV is not required. Existing or queued files, unmonitored
episodes, stopped movies, unreleased movies and deleted requests are excluded.
It does not search the rest of the library. Recovery keeps all release rejections
except the existing download occupying the queue, which is precisely what the
alternative is intended to replace.
Completed imports trigger a debounced Seerr recently-added scan, so availability
does not have to wait for the regular five-minute scan.
An active download with no connected seeders, availability below one complete
copy, and less than 64 KiB/s sustained progress for 30 minutes is checked for
alternatives, including when it already has partial data. Paused, queued,
forced, checking and completed torrents are protected. Poll outages reset the
observation window. Alternative searches back off for 15 minutes.

Recovery ranks acceptable alternatives by resolution tier (1080p, 720p, then
permitted SD), exact advertised seed count, custom-format score, then size.
Wrong seasons, multi-season releases, zero seeds, blocklisted releases and
unrequested/owned/unmonitored episodes remain excluded. A normalized release
name remembers failures across indexers for seven days. Only one trial per task
and at most two per day are allowed. The original torrent and its partial data
are retained until the alternative has a complete connected source and transfers
at least 128 KiB/s for two consecutive minutes, or completes. Both torrents are
rechecked before retiring the original through Sonarr/Radarr. A failed trial is
blocklisted instead, preserving the original. Native failed-download re-search
is disabled for those retirements to prevent duplicate searches. Queued trials
do not time out as failed downloads. A newly queued trial is placed first in the
normal download queue once; it never force-starts or pauses other downloads.
Trial intent is saved before submitting a
release so timeouts/restarts do not create repeated duplicate trials.

`GET /requests` reports search/handoff state separately from the download queue.
`POST /requests/{id}/search` retries a request owned by the signed-in user,
limited to one manual retry per minute. Both endpoints authenticate through the
existing Seerr `connect.sid` session; the POST also requires `X-MCM-Action: 1`.
No CORS access is granted. `/health` exposes only a boolean. API keys remain in
the server's existing private configuration. Logs contain request/command IDs,
never session cookies, API keys or API response bodies.

`GET /torrents` supplies verified qBittorrent states for managed downloads.
`POST /torrents/{hash}/next` and `/now` change only that torrent's priority or
force-start setting. They require the same Seerr session and action header.
Non-admin users are restricted to their own requested titles/seasons; Seerr
admins can control all downloads currently managed by Sonarr/Radarr. The server
uses the existing Sonarr download-client credential in memory and a cookie jar
supporting qBittorrent 5.2. No second qBittorrent login is needed on the TV.

Install `request_pipeline.py` in
`~/.local/share/media-stack/request-pipeline/`, and the supplied systemd unit in
`~/.config/systemd/user/`. Then run:

```sh
systemctl --user daemon-reload
systemctl --user enable --now request-pipeline.service
```

The authenticated HTTP endpoint listens on the Deck's LAN interface, port 5056.
The launcher derives that host from its Seerr configuration. There is no public
port forwarding. Stop with `systemctl --user disable --now request-pipeline`.
Test with `python3 -m unittest discover -s server/request-pipeline -v` from the
repository root.

Quality policy: new requests use the dedicated 1080p profile with 720p fallback.
For TV series first released before 2005, the worker switches only our default
profile to the dedicated older-TV profile, allowing SD when HD is unavailable.
Custom user-selected profiles are preserved. `configure_selection.py` groups
only MCM's dedicated profiles into resolution tiers, preserving the allowed
qualities and upgrade policy, and aligns Prowlarr indexer priorities at 25 so
source priority does not defeat peer comparisons. It saves private rollback
backups on the Deck before updating settings. Sonarr/Radarr select an
accepted release under these profiles; zero-seeder, wrong-season and unknown
releases remain rejected. Per-quality duration-based size limits remain active;
the former absolute 1.5 GB season/4 GB film caps have been removed.

FileMood is search-only because its RSS feed repeatedly failed coverage checks.
Explicit automatic and interactive searches remain enabled. Existing settings
were backed up privately on the Deck under `media-stack/pipeline-backups/`.
