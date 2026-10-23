#!/usr/bin/env bash
# "Stream now" timing: opens the title page of the first film on the Films page that is not in the library, moves to
# Stream now and presses it, then reads the Perf marks (stream.press -> /start reply -> player.firstFrame).
# This starts a real torrent on the stream service. Usage: tools/perf/stream_perf.sh <adb-serial> <label> [wait-s] [detail-film-N]
set -u
S="$1"; LABEL="$2"; WAIT="${3:-90}"; DEST="${4:-detail-film}"
OUT="${PERF_OUT:-build/perf}/$LABEL"; mkdir -p "$OUT"
PKG=com.example.tvlauncher
A="adb -s $S"
$A shell input keyevent 224
$A shell am force-stop $PKG
$A shell am start -W -n $PKG/.MainActivity > /dev/null
sleep 6
$A shell am start -n $PKG/.MainActivity --ez debug_cinema true --es debug_cinema_dest "$DEST" > /dev/null 2>&1
sleep 12
focused() { $A exec-out uiautomator dump /dev/tty 2>/dev/null | grep -o '<node[^>]*focused="true"[^>]*>' | grep -o 'content-desc="[^"]*"'; }
# Only ever press OK on Stream now: the button left of it queues a real download.
for key in 19 19 22 22 21; do
  case "$(focused)" in *'"Stream now"'*) break;; esac
  $A shell input keyevent $key; sleep 1.5
done
$A exec-out screencap -p > "$OUT/detail.png"
case "$(focused)" in *'"Stream now"'*) ;; *) echo "Stream now is not focused; not pressing anything"; exit 1;; esac
$A logcat -c
$A logcat -v time -s Perf:I StreamNow:I PlayerActivity:I > "$OUT/stream.log" &
LOGPID=$!
$A shell input keyevent 23
for i in $(seq 1 "$WAIT"); do
  sleep 1
  grep -q "player.firstFrame" "$OUT/stream.log" 2>/dev/null && break
done
sleep 3
$A exec-out screencap -p > "$OUT/playing.png"
kill $LOGPID 2>/dev/null
sed 's/.*Perf *([0-9 ]*): //' "$OUT/stream.log" | grep -E "stream\.|player\.|StreamNow"
