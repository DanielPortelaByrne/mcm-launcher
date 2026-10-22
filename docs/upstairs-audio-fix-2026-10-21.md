# Upstairs Chromecast audio fix — 21 October 2026

## Cause

YouTube was producing stereo PCM over HDMI. The RCA TV's HDMI audio profile
advertised two-channel PCM, feeding the soundbar connected to the TV's analogue
output. The Chromecast HD had no AC-3, E-AC-3, or DTS decoder, confirmed using
`MediaCodecList`, while AAC and Opus decoders were available.

The recently played Westworld episode had six-channel AC-3; Sabrina had
two-channel E-AC-3. The Movies player could select and render the picture while
leaving the unsupported audio track unselected. A valid picture stopped the
startup timeout, so silence did not trigger the existing playback fallback.

## Change

- Detect an available but unselected Jellyfin audio track and retry with stereo
  AAC produced by Jellyfin. The new stream copies the original video.
- Keep the existing full video-conversion fallback, with stereo audio.
- Remove the player listener before release and ignore failure callbacks while
  exiting, preventing playback retries on a released player.
- Add stereo URL regression tests and a dedicated playback instrumentation
  runner. The existing autoplay runner remains the default.

Only the upstairs Chromecast was updated. No commands were sent to the
downstairs TV, and no server/network/audio-output settings were changed.

## Verification

- Build succeeded; 210 unit tests passed, zero failures or errors.
- Separate HLS sample returned HTTP 200 for the master, media playlist and
  segment. The manifest retained HEVC video and advertised AAC audio.
- Westworld/AC-3: automatic conversion, decoded AAC, 2 channels, first frame,
  and 6,009 ms of position advancement during the playback check.
- Sabrina/E-AC-3: automatic conversion, decoded AAC, 2 channels, first frame,
  and 6,001 ms of advancement.
- Below Deck/AAC: direct playback, no audio conversion, 2 channels, first frame,
  and 5,943 ms of advancement.
- Android playback diagnostics showed the converted Movies audio using stereo
  PCM at 48 kHz, routed to HDMI Out, the same output used by YouTube.
- The test restores episode watched flags, play counts, resume positions and
  local playback history. No test episode was left playing.
- Upstairs was left awake on MCM Home, matching its initial awake state.
- Audible sound at the physical soundbar still needs Daniel's confirmation.

Installed APK SHA-256:
`c6fb7017d2d522f663cf9cc2a972f01bcd66c63f079da98a563c6d20695f5e5a`

Rollback APK: `upstairs-before-audio-fix.apk` in the current task workspace.
Pre-change copies of the already modified source files are in
`audio-fix-originals/` there. No commits or pushes were made.

## Re-run the audio smoke test

From the launcher project, in PowerShell:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest '-PplaybackTestRunner=com.example.tvlauncher.AudioPlaybackInstrumentation'
adb -s 192.168.1.220:44513 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 192.168.1.220:44513 shell am instrument -w -e itemId ITEM_ID -e expectConversion true com.example.tvlauncher.test/com.example.tvlauncher.AudioPlaybackInstrumentation
```

Use only on an idle, positively identified upstairs TV. Set `expectConversion`
to `false` when checking a directly playable AAC file. The test APK reads the
existing sign-in in memory and never prints credentials.
