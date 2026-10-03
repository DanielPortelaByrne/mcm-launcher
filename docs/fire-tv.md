# Fire TV installation

The original Daniel, Daniel (UK), and Eva launcher was installed on the
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
