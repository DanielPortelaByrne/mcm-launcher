# Fire TV installation - Camila & Brian

Branch: `feature/camila-brian`, for Camila and Brian's Fire TV Stick AFTSS
(Fire OS 7.7.1.7, Android 9 / API 28). One local **Camila & Brian** profile,
films from Letterboxd's *Movies everyone should watch at least once* list, and
recipes. Sideboard, personal project suggestions and music listening are removed.

The history below (2026-10-03) was recorded on the parents' AFTMM stick
(Android 7.1.2 / API 25) before this branch split from `feature/fire-tv`.
The LG webOS app is maintained separately on `feature/lg-webos`.

Compatibility fixes:
- Register screen broadcasts through AndroidX ContextCompat.
- Guard Android 8 adaptive icon APIs on older devices.
- Render synthetic icon glyphs to a bitmap instead of using Android 8
  fractional InsetDrawable constructors.

Build with `gradlew.bat :app:assembleDebug`. Install the debug APK with
ADB and open `com.example.tvlauncher/.MainActivity`.

Verified on the device: stable startup, profiles, selecting Eva and Daniel,
app-grid D-pad navigation, live film posters, Art browsing, pause/resume,
and Back navigation. Home now redirects to MCM through the persistent monitor
described below; the earlier tests are retained as investigation history.

## Home-routing test (2026-10-03)

The installed firmware is Fire OS 6.7.1.1 (NS6711/5908). Home routing was
requested and tested on this device, but remains unavailable through the
methods tested:

- `cmd package set-home-activity` returned success, but Home still opened Amazon.
- Disabling `com.amazon.tv.launcher`, or its exact Home activity, was rejected
  by Fire OS with `Cannot disable a protected package`.
- A restricted in-app accessibility redirect was installed temporarily, but
  Fire OS discarded the enabled service setting and never bound the service.

The trial service was removed from the app and the original Home/accessibility
settings restored. MCM remains installed and can be opened from Apps.
Home still opens Amazon. No root procedure or external always-running computer
relay was installed. The recovery scripts in `tools/` are retained for reference;
they were prepared for the trials, not as proof of a working Home mapping.

## Deeper investigation (2026-10-03)

**A non-root redirect works in automated tests.** This updates the earlier
conclusion: the blocked methods above do not rule out every Home workaround.

Device: AFTMM / mantis, Fire OS 6.7.1.1 (NS6711/5908), API 25,
reported security patch 2025-11-01. ADB runs as shell (UID 2000), `su` was
not found, and verified boot reports green. The physical eFuse state was not read.

### Working proof of concept

The shell can read ActivityManager's Home launch event and run
`am start -n com.example.tvlauncher/.MainActivity`. A computer-side monitor
detected one simulated Home press and returned MCM to the foreground.

Then `tools/fire-tv-home-trial.sh` ran directly on the Fire TV as the ADB shell
user. After disconnecting and reconnecting ADB, simulated Home presses from MCM
and Android Settings both returned MCM to `mResumedActivity`. No computer-side
monitor was running for those checks. Its 120-second expiration was also verified.
A subsequent 300-second trial confirmed physical-remote behavior: Daniel
initially saw Amazon Home, then reported that MCM reopened after about one second.

This redirects after Amazon Home starts, so its screen can briefly appear.
It does not replace the system launcher or intercept volume/D-pad input.
Other controls, long-press Home, sleep/wake, and extended reliability still need
confirmation. The script has no boot hook and cannot survive a reboot.

To reproduce from a shell that preserves the quoted remote command:

```sh
adb -s <device>:5555 push tools/fire-tv-home-trial.sh /data/local/tmp/mcm-home-trial.sh
adb -s <device>:5555 shell 'nohup sh /data/local/tmp/mcm-home-trial.sh 120 >/data/local/tmp/mcm-home-trial.log 2>&1 </dev/null &'
```

The duration is restricted to 15-300 seconds. Do not start overlapping trials.
Wait for `MCM Home trial finished` in the log before repeating. Expiration or a
reboot restores ordinary Home behavior automatically; no settings need undoing.

### Options assessed

| Route | Finding |
| --- | --- |
| Shell monitor on the stick | Proven during a bounded trial, including ADB disconnect. Needs a restart mechanism after reboot. |
| External ADB monitor | Proven once on this computer. An always-on host could reconnect after reboot; that deployment has not been built. |
| App-contained log monitor | Candidate for further development, not proven. Requires testing log permission, background activity launch, and boot startup on this firmware. |
| Accessibility tools | Home on Fire explicitly reports 6.7.1.1 unsupported; FTVLaunchX reports failure on 6.2.7.2 and later. Consistent with our failed trial. |
| Root / custom ROM / downgrade | No verified route found for this installed build. Existing Mantis unlock instructions concern vulnerable older firmware and USB access. A ROM download alone does not provide an unlock. |

Recommended next step: develop the proven log-event redirect into a restartable
solution, with a visible off switch and an escape to Amazon Home. An app using
ADB-granted `READ_LOGS`, or a local ADB helper, may remove the external-host
dependency, but neither should be described as supported before testing.
No rooting, flashing, firmware downgrade, factory reset, or boot persistence
was performed during this investigation.

### Primary references

- [Home on Fire compatibility](https://github.com/toolicious/home-on-fire#compatibility): explicitly excludes Fire OS 6.7.1.1.
- [FTVLaunchX](https://github.com/codefaktor/FTVLaunchX): older accessibility approach is no longer working on 6.2.7.2+.
- [External ADB monitor example](https://gist.github.com/Skyluker4/c6f7d5c726f202afb46605ae47981eaf): demonstrates the host-side approach; our Fire OS 6 uses ActivityManager rather than its ActivityTaskManager tag.
- [Kamakiri source](https://github.com/amonet-kamakiri/kamakiri) and [first-person Mantis ROM installation](https://journal.amazinaxel.com/2025/11-05-lineage-on-firetv): the latter explicitly warns that newer firmware patches the unlock. We did not verify any newer exploit for NS6711/5908.
- [Rootless Logcat source](https://github.com/tananaev/rootless-logcat) and [Android READ_LOGS permission](https://developer.android.com/reference/android/Manifest.permission#READ_LOGS): background for a possible app implementation, not proof of Fire OS compatibility.

## Persistent Home routing (2026-10-03)

The final implementation runs within MCM as `FireHomeService`, with an ongoing
notification and sticky service restart. It needs a one-time ADB grant of
`android.permission.READ_LOGS`; restart MCM after granting so its new process
receives the log-reading group. It reads only ActivityManager's system log
stream, checks fresh Home launch events, and opens MCM. Log contents are not
stored or transmitted. Accessibility and system Home settings are not modified.

`FireHomeBootReceiver` handles both early and normal boot plus app replacement.
The enabled preference lives in device-protected storage. The first normal-boot
test found MCM queued behind over 100 receivers, so early-boot support was added.
After another real reboot, the monitor was already foreground before MCM was
manually opened; pressing Home returned MCM from Amazon Home and Settings.

Menu within MCM opens D-pad-accessible controls:

- Turn routing on or off (the choice persists).
- Open Amazon Home, suppressing redirects for one minute.
- Open Fire TV settings or return to MCM.

Verified: APK build, 96 passing unit tests (including genuine Home events versus
other users, activities and diagnostics); app-level redirect; off switch; Amazon pause;
re-enable; real reboot startup; Home from Amazon and Settings. The earlier
shell prototype's physical-remote test is recorded above. Daniel confirmed the
persistent version returns to MCM with a few-second delay after reboot. Other
controls were not explicitly confirmed in that response. Extended sleep/wake reliability has not
yet been established. Force-stopping MCM intentionally prevents restart until
MCM is opened again, as Android normally handles force-stopped apps.

Recovery: use Menu -> Turn off MCM Home button. If MCM's controls are unavailable,
revoke its log permission and force-stop it via ADB, then press Home:

```sh
adb -s <device>:5555 shell pm revoke com.example.tvlauncher android.permission.READ_LOGS
adb -s <device>:5555 shell am force-stop com.example.tvlauncher
adb -s <device>:5555 shell input keyevent 3
```

Re-enabling after recovery requires regranting the permission and opening MCM.
No root, bootloader change, firmware flash, external relay, or shell boot hook
is used. `tools/fire-tv-home-trial.sh` remains a bounded diagnostic prototype;
do not run it alongside the permanent service. The two older restore scripts
address the abandoned default-Home/accessibility trials, not this log monitor.

## Explicit startup launch (2026-10-03)

Boot broadcasts now request an explicit MCM launch as well as starting the
monitor. The service waits for credential storage to unlock, with bounded
one-second retries for up to two minutes. It records the boot count after a
successful request so the delayed normal boot broadcast does not interrupt a
subsequently opened app. App updates and ordinary service restarts do not request
this launch. Turning Home routing off also disables startup launch.

Verified with a real reboot and no remote or simulated key input: Amazon Home
appeared during initial startup, then MCM became the resumed activity. The saved
`opened_boot` matched the current boot count, and the Home monitor was foreground.
The build and all 96 unit tests passed before installation.

This concerns Fire TV boot. Turning the television on while an independently
powered Fire Stick stays running is a different event and is not covered by this
boot callback.

## Fire OS 7 (2026-10-04)

Installed on the AFTSS stick (Fire OS 7.7.1.7, API 28). Fire OS 7 logs Home in
the same `START u0 {... cat=[android.intent.category.HOME] ... HomeActivity_vNext}`
form, so the matcher is unchanged. Android 9 needed three service changes: a
notification channel, `startForegroundService` from boot, and the
`FOREGROUND_SERVICE` permission. Verified: build and unit tests, routing toggled on
through Menu, Home from Amazon Home returned MCM as the resumed activity. Not yet
verified: a real reboot.
