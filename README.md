# MCM Launcher - Fire TV (Camila & Brian)

MCM launcher for **Camila and Brian's Amazon Fire TV Stick**. Maintained on
`feature/camila-brian`, branched from `feature/fire-tv` before that branch was
personalised for Daniel's parents. The native Android app source is in `app/`.

## This household's version

The installed device is a **Fire TV Stick (AFTSS)** running **Fire OS 7.7.1.7**
(Android **9**, API **28**), reached over ADB through Tailscale. This version has
one local profile, **Camila & Brian**. Film picks come from Letterboxd's popular
public list *Movies everyone should watch at least once*, with posters and
availability. It keeps recipes, app search and organisation, and the
full-screen painting collection. The sideboard projects, the evening project
suggestion, and Now spinning (music listening) are removed.

Camila & Brian is a local MCM profile; it does not sign into streaming apps or
expose any saved accounts.

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
monitor and restarts it at boot. Home from Amazon Home was verified on this stick;
reboot startup has not yet been tested on Fire OS 7. Amazon Home can appear briefly
before MCM returns.
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

This implementation supports Amazon devices on Android API 25 to 28 (Fire OS 6
and 7). On Android 8+ the monitor starts as a foreground service with its own
notification channel.
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
