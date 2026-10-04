# MCM Launcher - Fire TV (parents' home)

MCM launcher for the **Amazon Fire TV Stick at Daniel's parents' house**.
`feature/parents-home` (this branch) is the parents' own home screen, built on
`feature/fire-tv`, which holds the Fire OS Home routing. The app source is in `app/`.

## This household's version

The installed device is an **AFTMM Fire TV Stick**, running Android **7.1.2**
(API **25**). This version has one local profile, **Mammy & Daddy**, app
search and organisation, Continue watching and the painting collection. Eva's
Letterboxd card, the MESA recipes / Tonight print, the project sideboard and
Now spinning belong to the London home and are not on this one.

## Parents' home

The home is meant to make them feel connected to the family. Under the apps,
in this order:

| Section | What it is | Source (all via one feed) |
|---|---|---|
| **Coming up** (hero) | A paper calendar: visits, birthdays, weekends away. Hidden when empty. | Family-only iCal + fixed events |
| **Daniel, lately** | Loose photo prints of Daniel's ordinary life; OK = full screen. | A link-shared Google Photos album |
| **Para Amélia** | A printed *Guia de TV*: Brazilian live news, the newest novela scene, an action film, Ária's programme. | Official YouTube channels, curated films |
| **Tonight at home** | One film and one documentary for both of them, daily. | Curated pool, Cinemeta posters |
| **Padraig's listening room** | LP sleeves (OK plays in Spotify), tonight's gig, latest podcast episodes. | Spotify links, YouTube search/channels |
| **Match programme** | Wolves always; Ireland only around a match. | ESPN public API |
| **Ideias de crochê** | Three pattern cards a day. | Wikimedia Commons (freely licensed) |
| **Ária's corner** | Four picture books: Ms Rachel and other calm, allow-listed channels. | Channel allow-list |
| **From the family album** | One framed old photograph a day. | A *separate*, curated album (never the library) |
| **At the Seantí** | A gig poster when something relevant is on. | Curated (no stable venue feed exists) |

Nothing moves on its own; the home only changes between visits. Sections with
nothing to show are simply not there, and a broken source never shows an error.

### How content gets to the TV

```
Google Photos album ──┐                      private repo mcm-parents-feed
Family calendar ──────┤   GitHub Actions     (scheduled every 30 min)
YouTube, ESPN, ... ───┴──► node src/main.mjs ──► feed.json in a secret gist
                                                      │  (HTTPS, ETag)
                                                      ▼
            Fire TV: ParentFeedRepository ── cached feed + cached images ── ParentsHome
```

- **The TV only reads one JSON document** (`data/parents/ParentFeed.kt`). It
  never signs in, never talks to Google Photos, and holds no API keys.
- **Stale-while-revalidate.** Home draws from the cached feed at once, then
  checks for a newer one in the background (at most every 5 minutes on resume,
  every 30 while Home stays open). A feed is only replaced by one that parses
  and validates; network errors and malformed feeds leave the cache untouched.
- **Images** are Google's resized renditions (never originals), downloaded once
  into private storage (`files/parents/img`, capped at 60 MB, pruned to what the
  feed still uses) and decoded at drawn size, RGB_565, off the main thread.

### Why the album needs a sync job

Since March 2025 the Google Photos Library API only returns photos an app
uploaded itself, and the Picker API needs someone to choose photos every time.
Neither can follow an album Daniel adds to from his phone. A *link-shared*
album's page carries its contents as structured data, which the feed job reads
(media keys, sizes, dates); see the `mcm-parents-feed` README. **Privacy
trade-off:** anyone with the album link can view that album. The link lives
only in that repo's Actions secrets; the TV and this public repo never see it.

**Routine: add photos to the album.** New photos appear on the TV within about
30–40 minutes; removed ones leave it the same way. Nothing else is needed.

### Pointing the TV at its feed (one-time, already done on the stick)

The feed address is not in the APK or in Git. Push it to the app over ADB:

```powershell
'{"feedUrl":"https://gist.githubusercontent.com/<user>/<gist id>/raw/feed.json"}' |
  Set-Content parents-config.json -Encoding ascii -NoNewline
adb -s <fire-tv-ip>:5555 shell mkdir -p /sdcard/Android/data/com.example.tvlauncher/files
adb -s <fire-tv-ip>:5555 push parents-config.json /sdcard/Android/data/com.example.tvlauncher/files/
adb -s <fire-tv-ip>:5555 shell am force-stop com.example.tvlauncher
adb -s <fire-tv-ip>:5555 shell am start -n com.example.tvlauncher/.MainActivity
```

MCM moves the address into private storage on its next start and deletes the
pushed file. To revoke: delete or replace the gist (then push the new address).

### Checking it

```powershell
adb -s <fire-tv-ip>:5555 logcat -s ParentFeed     # feed/image events; never addresses or tokens
gh run list -R DanielPortelaByrne/mcm-parents-feed --workflow sync.yml
gh workflow run sync.yml -R DanielPortelaByrne/mcm-parents-feed   # sync now
```

### Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Daniel, lately missing entirely | TV has no feed address yet, or has never been online | Provision the address (above); connect to Wi-Fi |
| "New photos from Daniel will appear here." | Album is empty, or the sync has not read it yet | Check the latest Actions run |
| New photo not showing after an hour | Sync failing (red run) | `gh run view --log`; `LINK_REVOKED` = sharing turned off; `LAYOUT_CHANGED` = Google changed the page (see feed repo README) |
| A section vanished | Its source failed and nothing was cached yet, or it is out of season | Nothing to do; it returns with data |
| Sleeve/poster blank | That one image failed to download | It is retried on the next visit |

The Bandit and Aries profile, HDMI shelf shortcuts, and 4.0-star film filter
belong to the LG household's version. Mammy & Daddy is a local MCM profile;
it does not sign into streaming apps or expose saved Daniel/Eva accounts.

## Build and install

Use JDK 17 and the Android SDK configured in `local.properties`.
Enable ADB debugging on the target Fire TV and approve the computer's connection.
Use its current IP address rather than assuming a saved address is still valid.

```powershell
.\gradlew.bat :app:assembleDebug
adb connect <fire-tv-ip>:5555
adb -s <fire-tv-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <fire-tv-ip>:5555 shell am start -n com.example.tvlauncher/.MainActivity
```

App ID: `com.example.tvlauncher`. The APK is in
`app/build/outputs/apk/debug/app-debug.apk`. Open MCM from the Fire TV app list.
**Home routing is enabled on the installed Fire TV.** MCM runs a local Home-event
monitor and restarts it at boot. Reboot startup and Home from Amazon/Settings
were verified on the stick. Amazon Home can appear briefly before MCM returns.
No root or always-on computer is required.

MCM also opens automatically once per Fire TV boot while Home routing is enabled.
It waits for app storage to unlock; late boot callbacks do not reopen it over
another app. Turning Home routing off also disables this startup launch.

Press **Menu in MCM** to turn routing on/off, open Amazon Home with a one-minute
pause, or open Fire TV settings. On a new installation, grant the one-time
permission below, restart MCM, then enable routing through Menu:

```sh
adb -s <fire-tv-ip>:5555 shell pm grant com.example.tvlauncher android.permission.READ_LOGS
adb -s <fire-tv-ip>:5555 shell am force-stop com.example.tvlauncher
adb -s <fire-tv-ip>:5555 shell am start -n com.example.tvlauncher/.MainActivity
```

This implementation supports Amazon devices on Android API 25 (Fire OS 6).
It filters Home events locally and does not save or transmit logs.
See [persistent Home routing](docs/fire-tv.md#persistent-home-routing-2026-10-03)
for verification and recovery.

## Compatibility and verification

Three Android 7 startup issues were fixed on this branch: screen-receiver
registration, access to Android 8 adaptive icons, and fractional icon insets.
The original TCL worktree and LG deployment were left untouched.

The build passed and the installed launcher was tested visibly on the Fire TV:
profile selection, app-grid D-pad navigation, live film posters, Art browsing,
pause/resume, and Back navigation. No new crashes occurred during those checks.
See [Fire TV deployment notes](docs/fire-tv.md) for details.

## Other household versions

- [Daniel and Eva's London TCL Google TV](https://github.com/DanielPortelaByrne/mcm-launcher/tree/feature/spatial-motion-experiment): original Android branch.
- [Mariana and Sean's LG webOS TV](https://github.com/DanielPortelaByrne/mcm-launcher/tree/feature/lg-webos): Bandit and Aries webOS port.

The inherited `webos/` directory is not the Fire TV build target. Make Fire TV
changes in `app/`; maintain the LG port on its own branch.

## Apps that need another country (VpnPilot)

Some of the parents' apps only play from abroad: Amélia's Brazilian TV (**+SBT**, **Globoplay**, **RecordPlus**)
and Padraig's **BBC Sounds** (UK). Each person has their own PrivadoVPN account, in their own copy of the app:

- **PrivadoVPN Brasil** (`io.privado.android.br`): Amélia's account, location São Paulo, Split Tunnelling in
  Tunnel mode with +SBT, Globoplay and RecordPlus only.
- **PrivadoVPN UK** (`io.privado.android.uk`): Padraig's account, location London, Tunnel mode with BBC Sounds only.

`tools/privado-copy/build.sh <PrivadoVPN.apk> <br|uk> "<label>"` builds a copy: its own package and label,
and its widget service (`WidgetVpnService`) opened to MCM only (a signature permission; MCM and the copies are
signed with the same local key). Re-running it updates a copy in place and keeps its sign-in; a new copy is
signed in with PrivadoVPN's TV code (code.privadovpn.com). The original PrivadoVPN app is not installed.

Opening any of those apps from MCM (app tiles, family cards, TV do Brasil) goes through `VpnPilot`: a note on
screen (Portuguese for Amélia, English for Padraig), the other copy is told to disconnect and this one to
connect (CURRENT_CONNECT_STATE 2 / 1, as their widget does), and the app opens once the new VPN is up
(~6-13 s). Android runs one VPN at a time and does not say whose it is, so MCM remembers which route it brought
up. Fire OS lets no app press another app's buttons (accessibility services are refused, adb will not accept
connections from the stick itself), which is why the copies are driven through their widget service. Each
free account has 10 GB a month, spent only by its own apps.

+SBT is sideloaded and lightly patched: the Android TV build **1.25.42** (`br.com.sbt.mais`, APKMirror bundle).
Newer builds (1.26+) carry Google Play's anti-tamper wrapper and crash on Fire TV. SBT's own live channel is
broken at SBT's end (its ad-inserted stream times out; the direct stream behind it is a dead event feed) and
+SBT opens on it, so `tools/sbt/mcm_sbt.js` reorders the channel list to put it last: +SBT opens on SBT News.
`tools/sbt/build.sh <bundle.apkm> [device]` rebuilds it (re-signed with the local debug key; the APKs land in
`artifacts/sbt/`). Keep +SBT logged out: logged out it skips "Quem vai assistir?" and opens straight on live TV.
After reinstalling +SBT, restart PrivadoVPN so its split tunnel picks up the new install. The note needs the
overlay permission:

```sh
adb -s <fire-tv-ip>:5555 shell appops set com.example.tvlauncher SYSTEM_ALERT_WINDOW allow
```
