#!/bin/sh
# Fixtures features_e2e.py expects (songs Song 1-3, Arabic title, 20 s song; v1/white/whitep videos). Pushes to $ANDROID_SERIAL.
set -e
d=$(mktemp -d); cd "$d"; q="ffmpeg -loglevel error -y"
song() { $q -f lavfi -i sine=f=$2 -t ${5:-60} -metadata title="$1" -metadata artist="$3" -metadata album="$4" -c:a libmp3lame "$6"; }
song "Song 1" 300 "Artist A" "Album X" 20 s1.mp3
song "Song 2" 400 "Artist A" "Album X" 60 s2.mp3
song "Song 3" 500 "Artist A" "Album X" 60 s3.mp3
song "أغنية عربية" 600 "Artist B" "Album Y" 60 ar.mp3
vid() { $q -f lavfi -i "$1" -f lavfi -i sine=f=440 -t ${3:-20} -c:v libx264 -pix_fmt yuv420p -preset ultrafast -c:a aac "$2"; }
vid testsrc2=size=1280x720:rate=24 v1.mp4 300
vid testsrc2=size=1280x720:rate=24 short.mp4 3
printf "1\n00:00:01,000 --> 00:00:10,000\nEN\n" > en.srt; printf "1\n00:00:01,000 --> 00:00:10,000\nAR\n" > ar.srt
$q -f lavfi -i testsrc=size=1280x720:rate=24 -f lavfi -i sine=f=300 -f lavfi -i sine=f=600 -t 30 -map 0 -map 1 -map 2 -c:v libx264 -preset ultrafast -c:a aac -metadata:s:a:0 language=eng -metadata:s:a:1 language=ara langs.mp4
$q -f lavfi -i testsrc=size=1280x720:rate=24 -f lavfi -i sine=f=300 -i en.srt -i ar.srt -t 30 -map 0 -map 1 -map 2 -map 3 -c:v libx264 -preset ultrafast -c:a aac -c:s mov_text -metadata:s:s:0 language=eng -metadata:s:s:1 language=ara subs.mp4
vid color=white:size=1280x720:rate=24 white.mp4
vid color=white:size=720x1280:rate=24 whitep.mp4
adb shell mkdir -p /sdcard/Music/QA; adb push s1.mp3 /sdcard/Music/QA/ >/dev/null; adb push s2.mp3 s3.mp3 ar.mp3 /sdcard/Music/ >/dev/null
adb push v1.mp4 white.mp4 whitep.mp4 short.mp4 langs.mp4 subs.mp4 /sdcard/Movies/ >/dev/null
for f in Music/QA/s1.mp3 Music/s2.mp3 Music/s3.mp3 Music/ar.mp3 Movies/v1.mp4 Movies/white.mp4 Movies/whitep.mp4 Movies/short.mp4 Movies/langs.mp4 Movies/subs.mp4; do
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/$f >/dev/null; done
sleep 3
