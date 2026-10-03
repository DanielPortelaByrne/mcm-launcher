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
