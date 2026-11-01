#!/usr/bin/env python3
"""Music, gallery, download and player additions, on the emulator. python3 qa/features_e2e.py [section ...]"""
import os, re, sys, time
sys.path.insert(0, os.path.dirname(__file__))
from ui import *
PKG = "dev.altayeb.shoebill"
results = []
def ok(n, c, extra=""):
    results.append((n, bool(c))); print(("PASS " if c else "FAIL ") + n + (" :: " + str(extra)[:320] if extra and not c else ""), flush=True)
def home():
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(.5)
    adb("shell", "am", "start", "-S", "-n", f"{PKG}/.MainActivity"); time.sleep(3)
def tab(label):
    n = find(label)
    if n is None: raise AssertionError(f"no tab {label}: {texts()[:14]}")
    tap_xy(*center(n)); time.sleep(1.5)
def media():
    return adb("shell", "dumpsys", "media_session")
def playing():
    m = media()
    return "state=PlaybackState {state=PLAYING" in m or "state=3" in m
def crashed():
    return "FATAL EXCEPTION" in adb("logcat", "-d", "-b", "crash", check=False)

def sec_music():
    home(); tab("Music"); time.sleep(1)
    if find("Allow", exact=True) is not None: tap("Allow", exact=True); time.sleep(2)
    wait("Song 1", 15); ok("music library lists tagged songs", True)
    ok("arabic title shows", find("أغنية عربية") is not None, texts()[:20])
    tap("Song 1"); time.sleep(3)
    ok("tap plays through media session", playing(), media()[:300])
    ok("notification session has the title", "Song 1" in media() or "Song 1" in adb("shell", "dumpsys", "notification", "--noredact"))
    n = find("Next")
    tap_xy(*center(n)); time.sleep(2)
    ok("next moves in the queue", "Song 2" in texts() or find("Song 2") is not None, texts()[:10])
    # background: leave the app, music keeps going
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(4)
    ok("keeps playing with the app in the background", playing())
    adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE"); time.sleep(1.5)
    ok("headset/media key pauses", not playing())
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2)
    tab("Music") if find("Song 1") is None else None
    time.sleep(1)
    tap("Artists", exact=True); wait("Artist A", 5); tap("Artist A"); wait("Song 3", 5); back(); time.sleep(.6)
    tap("Albums", exact=True); wait("Album X", 5); ok("albums and artists group", True)
    tap("Songs", exact=True); time.sleep(.5)
    e = [x for x in nodes() if x.get("class", "").endswith("EditText")][0]; tap_xy(*center(e)); typ("Song%s3"); time.sleep(1); hide_keys()
    ok("search filters", find("Song 3") is not None and find("Song 1") is None, texts()[:12])
    ok("no crash in music", not crashed())

def sec_nowplaying():
    home(); tab("Music"); wait("Song 1", 15); tap("Song 1"); time.sleep(2)
    n = find("Pause"); 
    h = wait("Song 1", 5); bars = [x for x in nodes() if x.get("text") == "Song 1"]
    tap_xy(*center(bars[-1])) if len(bars) == 1 else tap_xy(*center(bars[-1])); time.sleep(1.5)
    wait("Lyrics", 8); tap("Lyrics"); time.sleep(1.5)
    print("lyrics:", texts()[:10], flush=True)
    ok("beside .lrc read, or a clear way to pick one", find("Second line") is not None or find("Choose a lyrics file") is not None, texts()[:10])
    tap("Queue"); time.sleep(1); ok("queue lists the rest", find("Song 2") is not None, texts()[:10])
    tap("Sound"); time.sleep(1); ok("sound page has speed and sleep", find("15") is not None, texts()[:14])
    back(); adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE")

def sec_player():
    vid = adb("shell", "content", "query", "--uri", "content://media/external/video/media", "--projection", "_id:_display_name")
    m = re.search(r"_id=(\d+), _display_name=v1\.mp4", vid); assert m, vid[:300]
    log_clear()
    adb("shell", "am", "start", "-S", "-a", "android.intent.action.VIEW", "-t", "video/*", "-d", f"content://media/external/video/media/{m.group(1)}", "-n", f"{PKG}/.MainActivity"); time.sleep(6)
    def st():
        l = [x for x in adb("logcat", "-d", "-s", "BrandState").splitlines() if "BrandState:" in x]
        return dict(re.findall(r"(\S+?)=(\S*)", l[-1].split("BrandState: ", 1)[1])) if l else {}
    s = st(); ok("video plays", float(s.get("time-pos") or 0) > 0, s)
    if find("Got it", exact=True) is not None: tap("Got it", exact=True); time.sleep(1)  # Android's first full-screen hint
    for _ in range(6):  # the controls hide about as fast as a screen dump runs, so tap from this dump, not a new one
        tap_xy(540, 540); time.sleep(.6)
        more = find("More")
        if more is not None: tap_xy(*center(more)); break
    time.sleep(1.5); wait("Next frame", 6)
    t0 = None
    n = find("Next frame"); 
    if n is not None:
        tap_xy(*center(n)); time.sleep(2.2); t0 = float(st().get("time-pos") or 0)
        tap_xy(*center(find("Next frame"))); time.sleep(2.2); t1 = float(st().get("time-pos") or 0)
        ok("frame step moves about one frame", 0 < t1 - t0 < 0.2, (t0, t1))
        tap_xy(*center(find("Previous frame"))); time.sleep(2.2); t2 = float(st().get("time-pos") or 0)
        ok("frame back steps back", t2 < t1, (t1, t2))
    ok("no crash in player", not crashed())

def dl(url, label, audio=False):
    home(); tab("Downloads"); time.sleep(1)
    e = [x for x in nodes() if x.get("class", "").endswith("EditText")][0]; tap_xy(*center(e)); typ(url); hide_keys()
    if audio:
        _, y = center(wait("Best", 5, exact=True))
        adb("shell", "input", "swipe", "1000", str(y), "100", str(y), "300"); time.sleep(.8)
        tap("Audio MP3")
    tap("Download", exact=True); end = time.time() + 240; last = ""
    while time.time() < end:
        t = " | ".join(texts()); 
        if "Saved to your library" in t: return True, t
        if "Failed" in t: return False, t
        time.sleep(3)
    return False, "timeout " + t

def sec_download():
    home(); tab("Downloads")
    tap("Download", exact=True); time.sleep(1); ok("empty link names the field", find("Link: enter") is not None, texts()[:10])
    r, t = dl("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4", "direct")
    ok("direct file downloads to library", r, t)
    ok("file is in Movies/Shoebill", "Big_Buck" in adb("shell", "ls", "/sdcard/Movies/Shoebill", check=False), adb("shell", "ls", "/sdcard/Movies/Shoebill", check=False))
    r, t = dl("https://www.youtube.com/watch?v=aqz-KE-bpKQ", "yt", audio=True)
    ok("youtube audio as MP3 reaches Music/Shoebill", r and "mp3" in adb("shell", "ls", "/sdcard/Music/Shoebill", check=False), t)
    ok("no crash in downloads", not crashed())

S = dict(music=sec_music, nowplaying=sec_nowplaying, player=sec_player, download=sec_download)
adb("logcat", "-c", "-b", "crash", check=False)
for s in sys.argv[1:] or S:
    print("==", s, flush=True)
    try: S[s]()
    except Exception as e: ok(s + " section finished", False, f"{type(e).__name__}: {e}")
bad = [n for n, c in results if not c]
print(f"{len(results)-len(bad)}/{len(results)} passed", "FAILED: " + "; ".join(bad) if bad else "")
