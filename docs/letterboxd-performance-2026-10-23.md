# Letterboxd performance and profile rings — 23 October 2026

Device: downstairs TCL / Smart TV Pro, `192.168.1.217:5555`, 1920×1080.

## Baseline

The original debug APK was saved locally as `artifacts/letterboxd-performance/baseline.apk` (ignored by Git).
The benchmark starts a new launcher process, visits list search, then opens Letterboxd three times.
It samples actual bound member data, all ten member images, and the first six film posters.
Accounts, followed members, image/page caches, and watch history are retained.

Original foreground measurements, in milliseconds:

| Open | Member data | All member artwork | First row data | First six posters |
| --- | ---: | ---: | ---: | ---: |
| First after process restart | 3191 | 3587 | 4155 | 10314 |
| Repeat 1 | 751 | 810 | 1101 | 2187 |
| Repeat 2 | 424 | 424 | 789 | 1817 |

The baseline captured 149 frames, 37 over 100 ms, with a 585 ms p95 frame duration.
These figures cover navigation through list search as well as the Letterboxd page, rather than steady scrolling alone.
An earlier shorter benchmark, which moved on before all row artwork finished, measured member data at 1848–3131 ms.

## Changes

- Separate saved-page work from the two network workers. Slow requests no longer prevent other pages reading saved data.
- Coalesce concurrent refreshes of the same URL, delivering the result to every waiting caller.
- Bound in-memory HTML/parsed-page caches; retain parsed public data on disk across launcher restarts.
  A SHA-256 fingerprint of the HTML and a versioned URL key invalidate parsed snapshots. Corrupt or incompatible
  snapshots fall back to parsing. URL keys deliberately do not depend on runtime lambda class names.
- Reuse compiled parser expressions and avoid repeatedly compiling text-cleaning patterns.
- Reserve row heights while loading, coalesce diary redraws, and build poster cards over multiple frames.
- Defer rows outside the nearby viewport and artwork far along horizontal rows. Discard loads for obsolete pages.
- Resolve favourite/poster URLs independently so one slow lookup does not hold up an entire group.
- Reserve an image worker for visible artwork; prefetch below the viewport remains ordinary priority.
  Visibility estimation also handles cached URLs returning before a card's first layout.
- Decode avatars and member favourites near their displayed dimensions; preserve existing image cache filenames
  while replacing per-byte formatter calls in SHA-1/SHA-256 hexadecimal conversion.
- Disable padding clipping through the avatar/card parents. The avatar keeps its original picture dimensions;
  its surrounding rings can draw fully through the padded ancestors.

## Verification

- Full unit suite: 332 tests, zero failures.
- Cache regressions cover cached reads during blocked network work, shared cold refreshes, stale-then-fresh delivery,
  offline disk reuse, visible images during unrelated blocked downloads, and compatibility of existing SHA-1 keys.
- Debug application and instrumentation APKs build successfully.
- On-device avatar rendering checks sample the complete focused ring every ten degrees through the real padded card parents.
  The ring check passed in the first foreground comparison and later rendering checks.
- `artifacts/lb-before.png` shows the original clipped rings. `artifacts/letterboxd-performance/after-rings.png`
  shows the complete unfocused rings from an initial improved foreground build.

The TV entered its screensaver during intermediate runs. Some captures contained the ambient screen rather than the
launcher; those timings are not valid foreground comparisons. The final runner requires window focus and actual
rendered frames and rejects obscured runs. The final comparison below passed those checks.

The improved debug application is installed on the downstairs TV. Existing unrelated player/glass changes in the
working tree were preserved and are included by the normal local build; they were not edited by this work.

## Reproduce

Build:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest '-PplaybackTestRunner=com.example.tvlauncher.LetterboxdPerformanceInstrumentation'
```

Install the application and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` using `adb -s 192.168.1.217:5555 install -r`.
With the TV free and the launcher visible:

```text
adb -s 192.168.1.217:5555 shell am instrument -w -e label after com.example.tvlauncher.test/com.example.tvlauncher.LetterboxdPerformanceInstrumentation
adb -s 192.168.1.217:5555 shell am instrument -w -e smoke true com.example.tvlauncher.test/com.example.tvlauncher.LetterboxdPerformanceInstrumentation
```

The first command records a process-start open and two repeat opens, then verifies the focused ring.
Repeat opens have a 2.5-second completion budget for member artwork and the first six posters; the process-start
open is reported separately. The second command checks both members' Watchlist, Liked and Diary tabs.
Results and a screenshot are saved in app-private `files/letterboxd-benchmark-<label>.json` and `.png`.
An instrumentation result containing `FAIL` or a canceled result code is a failed run even if ADB itself exits zero.

## Final foreground comparison

The final foreground run passed the repeat-open budgets and full avatar-ring rendering check.
Saved results: `artifacts/letterboxd-performance/final.json`; screenshot: `artifacts/letterboxd-performance/final.png`.
Both members' Watchlist, Liked and Diary tabs also passed the foreground smoke test: Eva 1629/1576/1286 ms;
Daniel 886/686/591 ms respectively. No watch actions were invoked.

| Open | Member data, before → after | All member artwork | First row data | First six posters |
| --- | ---: | ---: | ---: | ---: |
| First after process restart | 3191 → 1370 ms | 3587 → 2493 ms | 4155 → 1365 ms | 10314 → 2715 ms |
| Repeat 1 | 751 → 660 ms | 810 → 922 ms | 1101 → 917 ms | 2187 → 2045 ms |
| Repeat 2 | 424 → 355 ms | 424 → 582 ms | 789 → 578 ms | 1817 → 1287 ms |

The first row's artwork completes 74% sooner after a process restart, and member data arrives 57% sooner.
Repeat member-artwork timings varied and were slightly slower in this comparison; the improvement is primarily in
process-start loading, film-row completion, and stable layout. These are three-open samples on a shared TV, not a
statistically controlled frame-rate study.

The final sample captured 67 frames, 32 over 100 ms, with a 500 ms p95 frame duration. There is still visible frame
work during page construction; these measurements do not establish smooth 60-fps navigation. Cache misses and fresh
Letterboxd browser checks can still take seconds. The final installed debug APK SHA-256 is
`2f644215d4f407d61bbbabff4238c63b3f4d07340bb38ec18d644e65ee965a3c`.
