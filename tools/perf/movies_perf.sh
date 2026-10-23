#!/usr/bin/env bash
# Movies performance run (docs/movies-performance-plan-2026-10-23.md, Phase 0).
# Usage: tools/perf/movies_perf.sh <adb-serial> <label> [jellyfin-override-url]
# Cold-starts the launcher, opens Movies, records the Perf timings, then scripts a D-pad pass over Movies Home
# (down through every row, along one row and back, into a title page and out) and records gfxinfo jank stats.
# A third argument (e.g. http://10.255.255.1:8096) points Jellyfin at an address that never answers, for this run only.
set -u
S="$1"; LABEL="$2"; JF="${3:-}"
OUT="${PERF_OUT:-build/perf}/$LABEL"; mkdir -p "$OUT"
PKG=com.example.tvlauncher
A="adb -s $S"
extra=(); [ -n "$JF" ] && extra=(--es debug_jellyfin_server "$JF")

$A shell input keyevent 224   # wake the TV; activities stay stopped while it sleeps
$A shell am force-stop $PKG
$A logcat -c
# The TV's log ring buffer is small and noisy, so the timings are streamed to a file as they happen.
$A logcat -v time -s Perf:I > "$OUT/perf.log" &
LOGPID=$!
$A shell am start -W -n $PKG/.MainActivity -a android.intent.action.MAIN -c android.intent.category.HOME > /dev/null
sleep 6
# Cold open of Movies (the process is fresh; posters come from the network, shelves from the catalogue cache).
$A shell am start -n $PKG/.MainActivity --ez debug_cinema true "${extra[@]}" > /dev/null
sleep 15
echo "=== warm" >> "$OUT/perf.log"
# Warm reopen: same process, so memory caches are warm.
$A shell am start -n $PKG/.MainActivity --ez debug_cinema true "${extra[@]}" > /dev/null
sleep 10

# Scripted scroll. Focus starts on the featured title's first button.
$A shell dumpsys gfxinfo $PKG reset > /dev/null
$A shell 'k(){ input keyevent $1; sleep $2; }
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14; do k 20 0.35; done
for i in 1 2 3 4 5 6 7 8; do k 22 0.3; done
for i in 1 2 3 4 5 6 7 8; do k 21 0.3; done
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14; do k 19 0.35; done
k 20 0.6; k 23 2.5; k 4 1.5'
$A shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo.txt"
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Slow UI|Number Slow bitmap|Number Slow issue|Number Frame deadline missed" "$OUT/gfxinfo.txt" | head -12
kill $LOGPID 2>/dev/null
echo "--- open timings (cold, then after === warm)"; grep -E "movies.open|=== warm|library [0-9]{3,}|catalog [0-9]{3,}" "$OUT/perf.log" | sed 's/.*Perf *([0-9 ]*): //' | head -40
