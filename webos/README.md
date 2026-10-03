# MCM Home for LG webOS

LG webOS port of the `AndroidTVLauncher` project, built for the
LG 50UA73006LA running webOS 10.3.1 / firmware 33.31.61.

The app uses the Android project's existing paintings, colour roles, recipe data, and film collection. It discovers the LG TV's installed
apps and icons, launches apps and HDMI inputs, saves app order/hidden apps and
the Bandit and Aries profile locally, and supports D-pad navigation, long-press app menus, search,
painting selection, and full-screen rotating art.

This LG version has one local profile, **Bandit and Aries**. Existing personal
profile preferences are replaced on launch. Sideboard and Now Spinning are removed. Film picks use Karsten Runquist’s
public Letterboxd watchlist (`kurstboy`), filtered to films averaging at least 4.0/5 on Letterboxd.
A saved snapshot of qualifying films is bundled as offline fallback.
The first watchlist page is cached on the TV for six hours.
Recipe summaries remain available.
This port does not include Android's Media3 player, Google
account selection, Android media-session watching, or the Stremio stream-ranking
pipeline. Local profile selection changes only MCM's display name.

## Build and checks

```powershell
npm ci
npm run check
npm test
npm run package
```

The installable IPK is in `artifacts/`. The app ID is
`com.daniel.mcm.home`. Rooted Homebrew Channel must remain installed: its
documented root execution service performs app discovery and app launches.

## Home-button mapping

Remote input observation confirmed the owner's IR remote sends Home as Linux
key code 773 through `LGE RCU` (`/dev/input/event0` on this TV). The adapter uses
the Magic Mapper runtime from `afonsojramos/magic-mapper-webos`, with its pinned
upstream `andrewfraley/magic_mapper` source. The input device is changed to
`LGE RCU`; the webOS 25 passthrough device remains `LGE M-RCU - Builtin [1]`.
Only Home is configured. Other remote events pass through.

Persistent mapping is started by `/var/lib/webosbrew/init.d/80-mcm-home`.
The process writes its PID and log under `/var/lib/mcm-home/`; its live status
is `/tmp/mcm-mapper/status.json`. No LG system application is removed or replaced.

## Restore LG Home

Inside MCM: **Make it yours → Restore the LG Home button → Restore**.

Alternatively, over the TV's authenticated SSH connection:

```sh
/var/lib/mcm-home/restore-home.sh
```

This disables the startup hook and terminates the mapper, releasing the input
device. MCM stays installed. **LG Home** in MCM's footer or settings also opens
the stock home without changing the mapping.

## Local tooling

`tools/tv.cjs` supports SSH execution and SFTP upload/download using JSON on
stdin. It pins the TV's SSH host fingerprint on first connection. It uses the
documented initial Homebrew SSH password unless `LG_TV_PASSWORD` is supplied.
No personal password, account token, or Google credentials are copied into MCM.

The Android project and its pre-existing uncommitted edits are left intact.

Dependencies and references:

- https://github.com/webosbrew/webos-homebrew-channel
- https://github.com/afonsojramos/magic-mapper-webos
- https://github.com/andrewfraley/magic_mapper
- https://webostv.developer.lge.com/develop/tools/cli-dev-guide

LG source lives in `webos/` on branch `feature/lg-webos`.

The initial Your apps order is YouTube, Fire TV Stick (HDMI 1), then
PlayStation 5 (HDMI 2). The HDMI shortcuts have device icons and switch inputs.
Existing preferences are migrated once; later app reordering stays editable.

The recommended film hero uses Letterboxd landscape artwork with a readable
overlay. Artwork is fetched only for the selected film, cached (up to 40 images),
and falls back to the plain card if unavailable.

The recommendation card shows the verified average rating. Unrated titles and
films below 4.0 are excluded. Ratings are refreshed with the six-hour watchlist
cache; recommendations currently draw from its first page.
