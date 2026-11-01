#!/usr/bin/env python3
"""Music, gallery, download and player additions, on the emulator. python3 qa/features_e2e.py [section ...]"""
import os, re, subprocess, sys, time
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
    tap("Artists", exact=True); wait("Artist A", 5); tap_xy(*min((center(x) for x in nodes() if x.get("text") == "Artist A"), key=lambda c: c[1]));  # the dock shows it too; the list row is higher up
    wait("Song 3", 5); back(); time.sleep(.6)
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

def open_more():
    """The controls hide about as fast as a screen dump runs, so a tap on More can miss. Retry until the panel is really open."""
    for _ in range(8):
        tap_xy(540, 540); time.sleep(.5)
        more = find("More")
        if more is None: continue
        tap_xy(*center(more)); time.sleep(1.5)
        if find("Decoder") is not None: return
    raise AssertionError("More panel never opened")

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
    open_more(); wait("Next frame", 6)
    t0 = None
    n = find("Next frame"); 
    if n is not None:
        tap_xy(*center(n)); time.sleep(2.2); t0 = float(st().get("time-pos") or 0)
        tap_xy(*center(find("Next frame"))); time.sleep(2.2); t1 = float(st().get("time-pos") or 0)
        ok("frame step moves about one frame", 0 < t1 - t0 < 0.2, (t0, t1))
        tap_xy(*center(find("Previous frame"))); time.sleep(2.2); t2 = float(st().get("time-pos") or 0)
        ok("frame back steps back", t2 < t1, (t1, t2))
    ok("no crash in player", not crashed())

def sheet_download():
    """The configure sheet's own Download button: the last one on screen once the link has been read."""
    end = time.time() + 60
    while time.time() < end and find("Reading the link") is not None: time.sleep(1.5)
    tap_xy(*center([n for n in nodes() if n.get("text") == "Download"][-1]))

def dl(url, label, audio=False):
    home(); tab("Downloads"); time.sleep(1)
    e = [x for x in nodes() if x.get("class", "").endswith("EditText")][0]; tap_xy(*center(e)); typ(url); hide_keys()
    tap("Download", exact=True); time.sleep(2)
    if audio:
        _, y = center(wait("Best", 60, exact=True))
        adb("shell", "input", "swipe", "1000", str(y), "100", str(y), "300"); time.sleep(.8)
        tap("Audio MP3")
    sheet_download(); end = time.time() + 240; last = ""
    while time.time() < end:
        t = " | ".join(texts()); 
        if "Saved to your library" in t: return True, t
        if "Failed" in t: return False, t
        time.sleep(3)
    return False, "timeout " + t

def shot(name):
    with open(os.path.join(os.environ.get("SHOTS", "/tmp"), name + ".png"), "wb") as f:
        f.write(subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True).stdout)

def prefs(**kv):
    """Downloader settings written directly, so one run can test proxy and limit without typing through the settings page."""
    x = "".join(f'<string name="{k}">{v}</string>' for k, v in kv.items())
    xml = f"<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>{x}</map>"
    adb("shell", "am", "force-stop", PKG)
    open(os.environ["DL_PREFS"], "w").write(xml)
    adb("push", os.environ["DL_PREFS"], "/data/local/tmp/dl.xml")
    adb("shell", f"run-as {PKG} sh -c 'mkdir -p shared_prefs; cp /data/local/tmp/dl.xml shared_prefs/downloads.xml'")

def notif():
    return adb("shell", "dumpsys", "notification", "--noredact")

DIRECT = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_5MB.mp4"

def sec_link():
    """Opening a link from outside: it must show the sheet, and must not start by itself."""
    prefs()
    url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url, "-n", f"{PKG}/.MainActivity"); time.sleep(3)
    wait("Big Buck Bunny", 90); shot("link-sheet")
    ok("youtube link opens the sheet with the real title", True)
    ok("nothing started by itself", find("Cancel") is not None and "Downloading" not in " ".join(texts()), texts()[:14])
    tap("Cancel", exact=True); time.sleep(1)
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"shoebill://download?url={DIRECT}", "-n", f"{PKG}/.MainActivity"); time.sleep(3)
    wait("Quality", 30); ok("shoebill link opens the sheet", True)
    sheet_download(); time.sleep(.6); shot("hero")
    t = " ".join(texts()); n = notif()
    ok("notification names the job with cancel", "Cancel" in n and "shoebill" in n, n[:0])
    end = time.time() + 120
    while time.time() < end and "Saved to your library" not in " ".join(texts()): time.sleep(2)
    ok("finishes", "Saved to your library" in " ".join(texts()), texts()[:14])
    ok("finished notification", "Download finished" in notif())
    bad = "shoebill://download?url=javascript:alert(1)"
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"'{bad}'", "-n", f"{PKG}/.MainActivity"); time.sleep(2)
    ok("non-web link opens no sheet", find("Quality") is None, texts()[:10])
    ok("no crash in links", not crashed())

def sec_network():
    """Real proxy on the laptop, reached from the emulator at 10.0.2.2. The run fails if it saw no traffic."""
    log = os.environ["PROXY_LOG"]; open(log, "w").close()
    prefs(proxy="http://10.0.2.2:" + os.environ["PROXY_PORT"], limit="500K")
    home(); tab("Downloads"); time.sleep(1)
    e = [x for x in nodes() if x.get("class", "").endswith("EditText")][0]; tap_xy(*center(e)); typ(DIRECT); hide_keys()
    tap("Download", exact=True); time.sleep(3); sheet_download(); t0 = time.time(); time.sleep(1)
    end = t0 + 120; speeds = set()
    while time.time() < end:
        t = " ".join(texts()); m = re.search(r"[0-9.]+[KM]iB/s", t)
        if m: speeds.add(m.group(0))
        if "Saved to your library" in t or "Failed" in t: break
        time.sleep(1)
    took = time.time() - t0
    ok("download through the proxy", "Saved to your library" in t, t[:200])
    ok("proxy saw the traffic", "test-videos.co.uk" in open(log).read(), open(log).read()[:120])
    ok("limit holds: 5 MB at 500K takes over 7 s", took > 7 and "Saved to your library" in t, (took, sorted(speeds)[:5]))
    open(log, "w").close()
    prefs(proxy="http://10.0.2.2:1")
    home(); tab("Downloads"); time.sleep(1)
    e = [x for x in nodes() if x.get("class", "").endswith("EditText")][0]; tap_xy(*center(e)); typ(DIRECT); hide_keys()
    tap("Download", exact=True); time.sleep(3); end = time.time() + 60
    while time.time() < end and find("Reading the link") is not None: time.sleep(1.5)
    t = " ".join(texts()); ok("dead proxy shows an error in the sheet", "ERROR" in t or "Could not read" in t, t[:220])
    prefs()
    ok("no crash in network", not crashed())

def sec_extras():
    """Tabs, album grid, chapters, formats, playlist picker, saved history."""
    home(); tab("Music"); wait("Song 1", 15)
    tap("Albums", exact=True); time.sleep(1); ok("album grid opens", find("Album X") is not None, texts()[:12]); shot("albums")
    tap("Album X"); wait("Song 3", 6); ok("album opens its songs", True); back(); time.sleep(.5)
    vid = adb("shell", "content", "query", "--uri", "content://media/external/video/media", "--projection", "_id:_display_name")
    m = re.search(r"_id=(\d+), _display_name=ch\.mp4", vid); assert m, "seed ch.mp4"
    adb("shell", "am", "start", "-S", "-a", "android.intent.action.VIEW", "-t", "video/*", "-d", f"content://media/external/video/media/{m.group(1)}", "-n", f"{PKG}/.MainActivity"); time.sleep(6)
    open_more(); wait("Chapters", 8); ok("chapters listed with titles", find("Intro part") is not None and find("Second part") is not None, texts()[:14]); shot("chapters")
    tap("Second part"); time.sleep(2)
    l = [x for x in adb("logcat", "-d", "-s", "BrandState").splitlines() if "BrandState:" in x]
    pos = float(re.search(r"time-pos=([0-9.]+)", l[-1])[1]); ok("chapter jumps to its start", 120 <= pos < 135, pos)
    url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
    adb("shell", "am", "start", "-S", "-a", "android.intent.action.VIEW", "-d", url, "-n", f"{PKG}/.MainActivity"); time.sleep(3)
    wait("All formats", 90); tap("All formats"); time.sleep(1); shot("formats")
    labels = [t for t in texts() if re.search(r"\d+p · ", t)]
    ok("real formats listed", len(labels) >= 3, texts()[:16])
    tap("Cancel", exact=True); time.sleep(1)
    pl = os.environ["PLAYLIST"]
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"'{pl}'", "-n", f"{PKG}/.MainActivity"); time.sleep(3)
    wait("selected", 90); shot("playlist"); first = texts()
    ok("playlist picker counts videos", any(re.match(r"\d+ of \d+ selected", t) for t in first), first[:10])
    tap("Cancel", exact=True); time.sleep(1)
    before = " ".join(texts())
    adb("shell", "am", "start", "-S", "-n", f"{PKG}/.MainActivity"); time.sleep(3); tab("Downloads"); time.sleep(2)
    t = texts(); ok("history survives a restart", "Saved to your library" in " ".join(t), t[:12])
    tap("Audio", exact=True); time.sleep(1); ok("audio filter leaves the video out", find("Big_Buck_Bunny") is None, texts()[:12]); shot("history")
    ok("no crash in extras", not crashed())

def sec_white():
    """Controls on a pure white picture: the pixels just inside each control's box must be dark enough for white icons."""
    from PIL import Image
    adb("shell", f"run-as {PKG} sh -c 'mkdir -p files; touch files/qa_keep_ui'")  # debug builds only: controls stay up for the measuring
    v = adb("shell", "content", "query", "--uri", "content://media/external/video/media", "--projection", "_id:_display_name")
    for name in ("white.mp4", "whitep.mp4"):
        m = re.search(r"_id=(\d+), _display_name=" + name.replace(".", r"\."), v); assert m, name
        adb("shell", "am", "start", "-S", "-a", "android.intent.action.VIEW", "-t", "video/*", "-d", f"content://media/external/video/media/{m.group(1)}", "-n", f"{PKG}/.MainActivity"); time.sleep(6)
        if find("Got it", exact=True) is not None: tap("Got it", exact=True); time.sleep(1)
        tap_xy(540, 540); time.sleep(1.5)
        if find("More", exact=True) is None: tap_xy(540, 540); time.sleep(1.5)
        ns = nodes(); shot(name)
        im = Image.open(os.path.join(os.environ["SHOTS"], name + ".png")).convert("L")
        for label in ("Back", "Subtitles", "More", "Previous", "Back 10 seconds", "Forward 10 seconds", "Next", "Lock", "Rotate"):
            n = find(label, exact=True, ns=ns)
            if n is None: ok(f"{name} {label} is on screen", False, [x.get("content-desc") for x in ns if x.get("content-desc")]); continue
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            lum = im.getpixel((x1 + 4, (y1 + y2) // 2))
            ok(f"{name} {label} sits on a dark surface", lum < 120, lum)
    adb("shell", f"run-as {PKG} rm files/qa_keep_ui")
    ok("no crash on white", not crashed())

def sec_download():
    home(); tab("Downloads")
    tap("Download", exact=True); time.sleep(1); ok("empty link names the field", find("Link: enter") is not None, texts()[:10])
    r, t = dl("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4", "direct")
    ok("direct file downloads to library", r, t)
    ok("file is in Movies/Shoebill", "Big_Buck" in adb("shell", "ls", "/sdcard/Movies/Shoebill", check=False), adb("shell", "ls", "/sdcard/Movies/Shoebill", check=False))
    r, t = dl("https://www.youtube.com/watch?v=aqz-KE-bpKQ", "yt", audio=True)
    ok("youtube audio as MP3 reaches Music/Shoebill", r and "mp3" in adb("shell", "ls", "/sdcard/Music/Shoebill", check=False), t)
    ok("no crash in downloads", not crashed())

S = dict(music=sec_music, nowplaying=sec_nowplaying, player=sec_player, download=sec_download, link=sec_link, network=sec_network, extras=sec_extras, white=sec_white)
adb("logcat", "-c", "-b", "crash", check=False)
for s in sys.argv[1:] or S:
    print("==", s, flush=True)
    try: S[s]()
    except Exception as e: ok(s + " section finished", False, f"{type(e).__name__}: {e}")
bad = [n for n, c in results if not c]
print(f"{len(results)-len(bad)}/{len(results)} passed", "FAILED: " + "; ".join(bad) if bad else "")
