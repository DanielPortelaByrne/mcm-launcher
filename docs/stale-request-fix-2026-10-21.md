# Stale request repair, 21 October 2026

Succession (TV TMDB 76331) had no Seerr requests, Arr queue items, torrents,
episode files, or monitored episodes. Sonarr series 4 nevertheless retained
`monitored: true` on its series and seasons 1–4. Seerr's Sonarr scanner imported
those empty monitored seasons at 03:30 UTC as processing (media 34). The launcher
rendered that imported status as a disabled Requested button, while Downloads
correctly had no actual request to cancel.

The deployed request pipeline now supports the explicit admin repair
`POST /media/{tv|movie}/{tmdbId}/clear-stale`. It requires a Seerr admin session
and the existing action header. It refuses real requests from any user, files,
monitored episodes, pending searches, Arr queue entries, matching client torrents,
4K states, or unavailable status services. It checks paginated queue records and
rechecks state around the mutation. A private server-side rollback snapshot is
saved before changes. Only empty Arr monitoring and the Seerr media record are
changed; no media-file, torrent, or Arr record deletion endpoint is used.

Succession was repaired successfully. All series/season monitoring is false,
and Seerr returns unknown/no request (status 1). A Sonarr scan was explicitly
started to verify the repair survives synchronization.

The Android title page now distinguishes imported monitoring as “Tracked · no
request” and offers “Clear stale request.” Failure to fetch status offers a retry.
The repair refreshes both the title button and season list. The app's clear call
allows 30 seconds for fresh server checks. Existing request/download cancellation
continues to work through Downloads.

Validation: 42 Python tests pass on Windows and on the Steam Deck (34 existing,
8 new). The Android stale-state, cancellation, and pipeline tests pass, and
`:app:assembleDebug` succeeds. The new server source SHA-256 is
`e05e2617e36b2da0bfe36ac73583e4c67e68cb552df5392c914ff6867ce9726c`.

Changes are in the active `C:\Users\danie\AndroidTVLauncher` working tree,
alongside the other task's ongoing film and autoplay changes. Existing changed
files were hash-checked before applying this fix. No commit, push, or APK install
was performed by this task. The Succession state repair is live for both TVs;
the new repair button will be included in the next launcher APK installation.
