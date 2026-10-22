#!/bin/bash
# usage: render.sh id [id...]  (default: all). Renders mock.html#id to ../id.png at 1920x1080 via headless Edge
cd "$(dirname "$0")"
E="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
ids=("$@"); [ ${#ids[@]} -eq 0 ] && ids=(01-movies-top 02-movies-rows-toy-story-history 03-movies-history-films-row 10-detail-tenzing-in-library 11-detail-tenzing-scrolled-more-like-this 12-detail-backrooms-not-in-library 13-detail-backrooms-scrolled 20-my-library-recently-added 21-downloads 22-search 23-search-voice-listening 30-stream-loading-overlay 31-player-controls 32-player-scrubbing 33-player-paused 00-logo)
for id in "${ids[@]}"; do
  out="$PWD/../$id.png"; rm -f "$out"
  "$E" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --window-size=1920,1080 --virtual-time-budget=10000 --allow-file-access-from-files --screenshot="$(cygpath -w "$PWD/../$id.png")" "file:///$(cygpath -m "$PWD/mock.html")#$id" >/dev/null 2>&1
  for i in $(seq 1 60); do [ -s "$out" ] && break; sleep 0.5; done; sleep 1; echo "$id"
done
