# Fire TV installation - Daniel's parents' house

At Daniel's parents' house, the original Daniel, Daniel (UK), and Eva launcher
was installed on the
Amazon Fire TV Stick AFTMM running Android 7.1.2 / API 25.

Branch: `feature/fire-tv`. This uses the original native Android app,
including Eva's watchlist, projects, recipes, and listening sections.
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
and Back navigation. The Amazon Home button has not been remapped.

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
