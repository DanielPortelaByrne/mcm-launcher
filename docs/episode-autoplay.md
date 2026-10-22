# Next episode autoplay

At the natural end of a Jellyfin episode, the player shows an episode still,
title, series/episode number, runtime, Play next and Stay here. The compact
300 dp card sits vertically on the right, inside the TV-safe margin, with the
artwork above the details and stacked playback controls. A three-second
ring countdown automatically starts the next episode. Play next starts it
immediately; Back or Stay here dismisses the card. Home/backgrounding cancels
the countdown and returning does not restart it silently. Seeking back into an
ended episode allows a fresh countdown when it ends again.

Episode metadata and artwork load during playback. Selection follows the
current episode, including rewatches, rather than waiting for Jellyfin Next Up
to reflect a completion report. Multi-episode files advance from IndexNumberEnd.
Known gaps/missing files do not get skipped within a season. The next regular
season can follow the last listed episode; specials stay in their own season.
Movies, live channels and series finales have no autoplay card.

The same activity/player is reused across episodes. Each episode gets its own
Jellyfin playback session, completion report and resume/Continue watching data.
Library startup uses a 750 ms first-picture buffer while retaining Media3's
normal long-term and rebuffer reserves. Actual picture startup depends on the
file/decoder/network in addition to the countdown.

The card uses existing launcher colours, typography and motion settings. The
ring updates smoothly with monotonic time; reduced-motion settings use discrete
updates without shortening the countdown. Play next receives initial remote
focus. Image failure leaves the rest of the card and actions usable.

## Verification

- 203 unit tests pass, including 13 autoplay/sequence/reporting regressions.
- Debug APK and separate instrumentation APK build successfully.
- A real upstairs Chromecast test played the final second of Rick and Morty
  S1E1, verified the card and automatic transition to S1E2, then checked Back
  cancellation and Home/background cancellation on S1E2. It verifies Jellyfin
  recorded S1E1's completion and that the same player instance was reused.
- The final three-second countdown build measured 4,119 ms from episode end to
  the next episode's first picture on the upstairs Chromecast HD, including
  decoder/network startup. Initial remote focus was verified on Play next.
- The same end-to-end test passed on the downstairs TCL after installation,
  measuring 7,062 ms including its slower file/decoder startup.
- Test cleanup drains playback reports, restores both episodes' original server
  watched/resume/play-count/favourite data, and restores local Continue watching,
  resume preferences and artwork. Session headers remain in memory.

Both TVs have the same verified APK, SHA-256
`34a78bb2af7809d6663746dfead196aca2503816b9f9b411572d23bf9517704d`.
The temporary test APK/files were removed afterward. MCM Home routing remains
configured; upstairs was restored to sleep, its state before verification.

The dedicated runner lives only in the separate test APK. Run it explicitly on
an idle, signed-in TV with Rick and Morty S1E1–3 in the library:

```sh
adb -s DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w com.example.tvlauncher.test/com.example.tvlauncher.AutoplayPlaybackInstrumentation
```

It saves `autoplay-card.png` and a timing JSON in the target app's private files
for visual inspection, then restores user state. Uninstall
`com.example.tvlauncher.test` after testing. The feature itself needs no server
configuration changes.
