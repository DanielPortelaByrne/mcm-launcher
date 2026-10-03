# MCM Launcher - Fire TV

The original Daniel and Eva MCM launcher, adapted for the **Amazon Fire TV
Stick at Daniel's parents' house**. Maintained on `feature/fire-tv`.
The active native Android app source is in `app/`.

## This household's version

The installed device is an **AFTMM Fire TV Stick**, running Android **7.1.2**
(API **25**). This version keeps the original **Daniel**, **Daniel (UK)**, and
**Eva** profiles, Eva's public Letterboxd watchlist, film posters and availability,
Sideboard projects, recipes, listening sections, app search and organisation,
and the full-screen painting collection.

This retains the original Android experience. The Bandit and Aries profile,
removed sections, HDMI shelf shortcuts, and 4.0-star film filter belong to the
LG household's version and are not applied here. Selecting a profile changes
MCM's selected account; it does not sign into streaming apps.

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
**The remote's Home button still opens Amazon Home**; it has not been remapped.

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
