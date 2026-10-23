#!/usr/bin/env bash
# Library "Watch now" timing: opens the newest library film's title page and presses Watch now, then reads the Perf marks (library.play -> player.prepare -> player.firstFrame).
# Usage: tools/perf/watch_perf.sh <adb-serial> <label>
set -u
S="$1"; LABEL="$2"
OUT="${PERF_OUT:-build/perf}/$LABEL"; mkdir -p "$OUT"
PKG=com.example.tvlauncher
A="adb -s $S"
$A shell input keyevent 224
$A shell am force-stop $PKG
$A shell am start -W -n $PKG/.MainActivity > /dev/null
sleep 6
# The debug route opens the title page of the newest library film, with its Watch now button focused.
$A shell am start -n $PKG/.MainActivity --ez debug_cinema true --es debug_cinema_dest detail-library > /dev/null 2>&1
sleep 15
$A exec-out screencap -p > "$OUT/detail.png"
# Only press OK on a play button (never Delete).
f=$($A exec-out uiautomator dump /dev/tty 2>/dev/null | grep -o '<node[^>]*focused="true"[^>]*>' | grep -o 'content-desc="[^"]*"')
case "$f" in *'"Watch now"'*|*'"Continue'*) ;; *) echo "focused: $f; not pressing anything"; exit 1;; esac
$A logcat -c
$A logcat -v time -s Perf:I PlayerActivity:I > "$OUT/watch.log" &
LOGPID=$!
$A shell input keyevent 23
sleep 25
$A exec-out screencap -p > "$OUT/playing.png"
$A shell 'input keyevent 4; sleep 1.5; input keyevent 4; sleep 1; input keyevent 4'
kill $LOGPID 2>/dev/null
sed 's/.*Perf *([0-9 ]*): //' "$OUT/watch.log" | grep -E "library.play|player\."
