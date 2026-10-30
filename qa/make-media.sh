#!/bin/sh
# Test videos for qa/e2e.py on a device without the original phone files (e.g. the emulator).
# Usage: qa/make-media.sh  -> pushes a.mkv + b.mkv to /sdcard/Movies and prints their ids for ANON_VIDEO_A (a.mkv) / ANON_VIDEO_B (b.mkv).
set -e
d=$(mktemp -d); cd "$d"
printf '1\n00:00:01,000 --> 00:00:50,000\nTest subtitle line\n' > s.srt
ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=1280x720:rate=24 -f lavfi -i sine=f=440 -f lavfi -i sine=f=660 -i s.srt -t 90 \
  -map 0 -map 1 -map 2 -map 3 -c:v libx265 -pix_fmt yuv420p10le -preset ultrafast -x265-params log-level=error \
  -c:a:0 eac3 -c:a:1 ac3 -c:s ass a.mkv
ffmpeg -loglevel error -y -f lavfi -i testsrc=size=1280x720:rate=24 -f lavfi -i sine=f=300 -i s.srt -t 60 \
  -map 0 -map 1 -map 2 -c:v libx265 -preset ultrafast -x265-params log-level=error -c:a aac -c:s srt b.mkv
adb push a.mkv b.mkv /sdcard/Movies/ >/dev/null
for f in a b; do adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/$f.mkv >/dev/null; done
sleep 3
adb shell content query --uri content://media/external/video/media --projection _id:_display_name | grep -E 'a.mkv|b.mkv'
rm -rf "$d"
