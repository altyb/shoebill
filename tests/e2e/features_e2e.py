#!/usr/bin/env python3
"""Music, gallery, download and player additions, on the emulator. python3 tests/e2e/features_e2e.py [section ...]"""
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
    # The header repeats the tab's name: the bar is the lowest one.
    ns = [n for n in nodes() if label.lower() in ((n.get("text") or "") + (n.get("content-desc") or "")).lower()]
    if not ns: raise AssertionError(f"no tab {label}: {texts()[:14]}")
    tap_xy(*max((center(n) for n in ns), key=lambda c: c[1])); time.sleep(1.5)
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
    tap("15"); time.sleep(1.5); ok("sleep timer shows a countdown", find("Stops in") is not None, texts()[:16]); shot("sound"); tap("Off", exact=True); time.sleep(.5)
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
    if find("Clear finished") is not None: tap("Clear finished"); time.sleep(1)  # saved history would satisfy the check below
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
        box = lambda l: list(map(int, re.findall(r"\d+", find(l, exact=True, ns=ns).get("bounds"))))
        w = im.width; pv, nx = box("Previous"), box("Next")
        ok(f"{name} transport is centred on the screen", abs(pv[0] - (w - nx[2])) <= 2, (pv, nx, w))
        ok(f"{name} transport buttons are the same size", pv[2] - pv[0] == nx[2] - nx[0] and box("Back 10 seconds")[2] - box("Back 10 seconds")[0] == pv[2] - pv[0], (pv, nx))
        ok(f"{name} nothing leaves the screen", nx[2] <= w and pv[0] >= 0)
        for label in ("Back", "Subtitles", "More", "Previous", "Back 10 seconds", "Forward 10 seconds", "Next", "Lock", "Rotate"):
            n = find(label, exact=True, ns=ns)
            if n is None: ok(f"{name} {label} is on screen", False, [x.get("content-desc") for x in ns if x.get("content-desc")]); continue
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            lum = im.getpixel((x1 + 4, (y1 + y2) // 2))
            ok(f"{name} {label} sits on a dark surface", lum < 120, lum)
    adb("shell", f"run-as {PKG} rm files/qa_keep_ui")
    ok("no crash on white", not crashed())

def squeezed():
    """Text squeezed into a narrow column (taller than wide with a short label), the 'O f f' bug. Also two-line button labels."""
    bad = []
    for n in nodes():
        t = n.get("text") or ""
        if 2 <= len(t) <= 16:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            if x2 > x1 and y2 - y1 > (x2 - x1) * 1.3: bad.append((t, x2 - x1, y2 - y1))
    return bad

def sec_panels():
    """Every player panel, scrolled through, on a landscape and a portrait video, at the current screen size."""
    from PIL import Image
    v = adb("shell", "content", "query", "--uri", "content://media/external/video/media", "--projection", "_id:_display_name")
    adb("shell", f"run-as {PKG} sh -c 'mkdir -p files; touch files/qa_keep_ui'")
    for name in ("v1", "whitep"):
        m = re.search(r"_id=(\d+), _display_name=" + name + r"\.mp4", v); assert m, name
        adb("shell", "am", "start", "-S", "-a", "android.intent.action.VIEW", "-t", "video/*", "-d", f"content://media/external/video/media/{m.group(1)}", "-n", f"{PKG}/.MainActivity"); time.sleep(7)
        if find("Got it", exact=True) is not None: tap("Got it", exact=True); time.sleep(1)
        for label in ("More", "Audio", "Subtitles"):
            if find(label, exact=True) is None: tap_xy(500, 500); time.sleep(1.2)
            tap(label, exact=True); time.sleep(1.5); shot(f"panel-{label}-{name}")
            w, h = Image.open(os.path.join(os.environ["SHOTS"], f"panel-{label}-{name}.png")).size
            for i in range(4):
                b = squeezed(); ok(f"{label} {name} page {i}: no squeezed labels", not b, b)
                adb("shell", "input", "swipe", str(w - 200), str(h * 3 // 4), str(w - 200), str(h // 4), "400"); time.sleep(1)
            shot(f"panel-{label}-{name}-end")
            back(); time.sleep(1)  # Close has scrolled away
    adb("shell", f"run-as {PKG} rm files/qa_keep_ui")
    ok("no crash in panels", not crashed())

def sec_pages():
    """Home tabs and every settings page at the current screen size, scrolled through."""
    from PIL import Image
    def sweep(name):
        shot("page-" + name); w, h = Image.open(os.path.join(os.environ["SHOTS"], f"page-{name}.png")).size
        for i in range(3):
            b = squeezed(); ok(f"{name} {i}: no squeezed labels", not b, b)
            adb("shell", "input", "swipe", str(w // 2), str(h * 3 // 4), str(w // 2), str(h // 4), "400"); time.sleep(.8)
        shot("page-" + name + "-end")
    home()
    for t in ("Library", "Playlists", "Network", "Music", "Downloads"): tab(t); time.sleep(.5); sweep(t)
    tap_xy(*center(find("Settings"))); time.sleep(1.5); sweep("hub")
    adb("shell", "input", "swipe", "500", "600", "500", "1800", "300"); time.sleep(.8)
    for t in ("Video player", "Music", "Downloads", "General", "About"):
        for _ in range(2): adb("shell", "input", "swipe", "500", "700", "500", "2000", "200"); time.sleep(.5)
        for _ in range(4):
            if find(t, exact=True) is not None: break
            adb("shell", "input", "swipe", "500", "1500", "500", "900", "400"); time.sleep(.8)
        tap(t, exact=True); time.sleep(1.2); sweep("set-" + t.replace(" ", "")); back(); time.sleep(1)
    for _ in range(2): adb("shell", "input", "swipe", "500", "700", "500", "2000", "200"); time.sleep(.5)
    tap("Appearance", exact=True); time.sleep(1.2); sweep("appearance"); back()
    ok("no crash in pages", not crashed())

def raw_prefs(name, xml):
    p = os.environ["DL_PREFS"]; open(p, "w").write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>" + xml + "</map>")
    adb("shell", "am", "force-stop", PKG); adb("push", p, "/data/local/tmp/x.xml")
    adb("shell", f"run-as {PKG} sh -c 'mkdir -p shared_prefs; cp /data/local/tmp/x.xml shared_prefs/{name}.xml'")

def music_list():
    home(); tab("Music"); time.sleep(2)
    return " ".join(texts())

def sec_settings():
    """Each setting must change what the app does, not just show a switch."""
    SMALL = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"
    def dl_once():
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"shoebill://download?url={SMALL}", "-n", f"{PKG}/.MainActivity"); time.sleep(3)
        wait("Quality", 60); sheet_download(); end = time.time() + 90
        while time.time() < end:
            t = " ".join(texts())
            if "Saved to your library" in t or "Failed" in t: return t
            time.sleep(2)
        return t
    files = lambda: adb("shell", "ls", "/sdcard/Movies/Shoebill", check=False)
    # music
    raw_prefs("settings", '<int name="minSongSec" value="30" />')
    t = music_list(); ok("min length hides 20 s songs", "Song 1" not in t, t[:160])
    raw_prefs("settings", '<set name="hiddenFolders"><string>QA</string></set>')
    t = music_list(); ok("hidden folder is gone", "Song 1" not in t, t[:160])
    raw_prefs("settings", '<int name="musicStartTab" value="1" />')
    t = music_list(); ok("library opens on the chosen tab", "Album X" in t, t[:160])
    raw_prefs("settings", "")
    music_list(); tap("Songs", exact=True); time.sleep(1); tap("Song 2"); time.sleep(3)
    adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE"); time.sleep(2)
    adb("shell", "am", "force-stop", PKG); time.sleep(1)
    music_list(); time.sleep(3); n = [x for x in nodes() if x.get("text") == "Song 2"]
    ok("queue comes back after a restart", len(n) >= 2 or find("Play", exact=True) is not None, texts()[:12])
    # downloads
    raw_prefs("downloads", '<int name="filename" value="1" /><boolean name="restrictNames" value="true" /><boolean name="noDup" value="true" />')
    adb("shell", f"run-as {PKG} rm -f files/download-archive.txt files/download-history.json")
    adb("shell", "am", "force-stop", PKG)
    before = files(); t = dl_once(); ok("download with file name options", "Saved to your library" in t, t[:220])
    new = [x for x in files().split() if x not in before.split()]
    ok("title-only, simple file name", any("[" not in x and " " not in x for x in new), new)
    time.sleep(1); tap("Clear finished"); time.sleep(1)
    t = dl_once(); ok("second time is skipped as a duplicate", "Already downloaded" in t, t[:260])
    raw_prefs("downloads", '<boolean name="incognito" value="true" /><string name="extra">--retries 2</string>')
    adb("shell", f"run-as {PKG} rm -f files/download-history.json")
    adb("shell", "am", "force-stop", PKG)
    t = dl_once(); ok("download with extra options", "Saved to your library" in t, t[:220])
    hist = adb("shell", f"run-as {PKG} sh -c 'cat files/download-history.json 2>/dev/null'", check=False)
    ok("private mode writes no history", "Big_Buck" not in hist, hist[:120])
    raw_prefs("downloads", ""); ok("no crash in settings", not crashed())

def sec_video():
    """Video settings change real playback, read from the debug BrandState line and the window."""
    def play(name, prefs_xml):
        raw_prefs("settings", prefs_xml)
        v = adb("shell", "content", "query", "--uri", "content://media/external/video/media", "--projection", "_id:_display_name")
        m = re.search(r"_id=(\d+), _display_name=" + name + r"\.mp4", v); assert m, name
        adb("logcat", "-b", "main", "-c")
        adb("shell", "am", "start", "-S", "-a", "android.intent.action.VIEW", "-t", "video/*", "-d", f"content://media/external/video/media/{m.group(1)}", "-n", f"{PKG}/.MainActivity"); time.sleep(7)
        if find("Got it", exact=True) is not None: tap("Got it", exact=True); time.sleep(1)
    def st():
        l = [x for x in adb("logcat", "-d", "-s", "BrandState").splitlines() if "BrandState:" in x]
        return dict(re.findall(r"(\S+?)=(\S*)", l[-1].split("BrandState: ", 1)[1])) if l else {}
    def rot(): return re.search(r"mCurrentRotation=ROTATION_(\d+)", adb("shell", "dumpsys", "window"))[1]
    play("v1", '<float name="defaultSpeed" value="1.5" />'); ok("starting speed applies", float(st()["speed"]) == 1.5, st().get("speed"))
    play("v1", ""); proc = subprocess.Popen(["adb", "-s", SERIAL, "shell", "input", "swipe", "1200", "500", "1200", "500", "2600"]); time.sleep(2.2)
    held = st().get("speed"); proc.wait(); time.sleep(1.5); ok("hold plays at 2x, then returns", float(held) == 2.0 and float(st()["speed"]) == 1.0, (held, st().get("speed")))
    play("v1", '<float name="holdSpeed" value="0.0" />'); proc = subprocess.Popen(["adb", "-s", SERIAL, "shell", "input", "swipe", "1200", "500", "1200", "500", "2600"]); time.sleep(2.2)
    held = st().get("speed"); proc.wait(); ok("hold can be turned off", float(held) == 1.0, held)
    play("v1", '<boolean name="gestureSeek" value="false" />'); t0 = float(st()["time-pos"]); adb("shell", "input", "swipe", "500", "500", "1900", "500", "400"); time.sleep(2.5)
    ok("sideways seek off stays put", float(st()["time-pos"]) - t0 < 10, (t0, st().get("time-pos")))
    play("v1", ""); t0 = float(st()["time-pos"]); adb("shell", "input", "swipe", "500", "500", "1900", "500", "400"); time.sleep(2.5)
    ok("sideways seek on still jumps", float(st()["time-pos"]) - t0 > 20, (t0, st().get("time-pos")))
    play("v1", '<int name="maxVolume" value="100" />'); adb("shell", "input", "swipe", "1900", "900", "1900", "100", "400"); time.sleep(2.5)
    ok("volume limit holds", float(st()["volume"]) <= 100.0, st().get("volume"))
    play("short", '<boolean name="loopOne" value="true" />'); time.sleep(4); a1 = float(st()["time-pos"]); time.sleep(2.5)
    ok("repeat keeps a 3 s file playing", st().get("pause") == "no" and float(st()["time-pos"]) < 3.1, (a1, st().get("time-pos"), st().get("pause")))
    play("langs", '<string name="audioLang">ar</string>'); ok("preferred audio language picks track 2", st().get("aid") == "2", st().get("aid"))
    raw_prefs("playback", "")  # the app remembers each file's track; forget it for this check
    play("langs", ""); ok("without it, track 1", st().get("aid") == "1", st().get("aid"))
    play("whitep", ""); ok("default turns a tall video sideways", rot() in ("90", "270"), rot())
    play("whitep", '<int name="orientation" value="2" />'); time.sleep(2); ok("follow the video stays upright", rot() == "0", rot())
    play("v1", '<int name="orientation" value="2" />'); time.sleep(2); ok("follow the video turns a wide one", rot() in ("90", "270"), rot())
    play("v1", '<int name="orientation" value="3" />'); ok("portrait setting", rot() == "0", rot())
    raw_prefs("settings", ""); adb("shell", "input", "keyevent", "KEYCODE_HOME"); ok("no crash in video settings", not crashed())

def sec_pip():
    """Leaving a playing video opens the small window, with three buttons; the setting and Android's own switch both stop it."""
    def go(xml):
        raw_prefs("settings", xml)
        v = adb("shell", "content", "query", "--uri", "content://media/external/video/media", "--projection", "_id:_display_name")
        m = re.search(r"_id=(\d+), _display_name=v1\.mp4", v)
        adb("shell", "am", "start", "-S", "-a", "android.intent.action.VIEW", "-t", "video/*", "-d", f"content://media/external/video/media/{m.group(1)}", "-n", f"{PKG}/.MainActivity"); time.sleep(7)
        adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(3)
        return "WINDOWING_MODE_PINNED" in adb("shell", "dumpsys", "activity", "recents") or "windowingMode=pinned" in adb("shell", "dumpsys", "window", "windows") or "mode=pinned" in adb("shell", "dumpsys", "activity", "activities")
    ok("leaving a playing video opens PiP", go(""))
    shot("pip")
    ok("the setting turns it off", not go('<boolean name="autoPip" value="false" />'))
    adb("shell", "appops", "set", PKG, "PICTURE_IN_PICTURE", "deny")
    ok("Android's switch off also stops it, with no crash", not go("") and not crashed())
    adb("shell", "am", "force-stop", PKG); adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(4)
    tap_xy(*center(find("Settings"))); time.sleep(1.5); tap("Video player"); time.sleep(1.5)
    for _ in range(3):
        if find("Picture-in-picture is off") is not None: break
        adb("shell", "input", "swipe", "500", "1500", "500", "900", "300"); time.sleep(.6)
    ok("settings show the shortcut when Android blocks it", find("Picture-in-picture is off") is not None, texts()[:10])
    adb("shell", "appops", "set", PKG, "PICTURE_IN_PICTURE", "allow"); adb("shell", "input", "keyevent", "KEYCODE_HOME")
    ok("no crash in PiP", not crashed())

def sec_more():
    """The settings that only count if real behaviour changes."""
    def vid(name, xml, keep=False):
        raw_prefs("settings", xml)
        adb("shell", f"run-as {PKG} sh -c 'rm -f files/qa_keep_ui; mkdir -p files" + ("; touch files/qa_keep_ui" if keep else "") + "'")
        v = adb("shell", "content", "query", "--uri", "content://media/external/video/media", "--projection", "_id:_display_name")
        m = re.search(r"_id=(\d+), _display_name=" + name + r"\.mp4", v); assert m, name
        adb("logcat", "-b", "main", "-c")
        adb("shell", "am", "start", "-S", "-a", "android.intent.action.VIEW", "-t", "video/*", "-d", f"content://media/external/video/media/{m.group(1)}", "-n", f"{PKG}/.MainActivity"); time.sleep(7)
    def st():
        l = [x for x in adb("logcat", "-d", "-s", "BrandState").splitlines() if "BrandState:" in x]
        return dict(re.findall(r"(\S+?)=(\S*)", l[-1].split("BrandState: ", 1)[1])) if l else {}
    def attrs(): return adb("shell", "dumpsys", "window", "windows")
    vid("v1", '<boolean name="showRemaining" value="false" />', keep=True); time.sleep(1.5)
    t = texts(); ok("full length instead of time left", any(x == "05:00" for x in t) and not any(x.startswith("-") and ":" in x for x in t), t[-10:])
    vid("v1", "", keep=True); time.sleep(1.5); ok("time left by default", any(x.startswith("-0") for x in texts()), texts()[-10:])
    raw_prefs("playback", "")
    vid("subs", '<string name="subLang">ar</string>'); ok("preferred subtitle language picks track 2", st().get("sid") == "2", st().get("sid"))
    raw_prefs("playback", "")
    vid("subs", ""); ok("without it, no forced track 2", st().get("sid") != "2", st().get("sid"))
    vid("v1", '<boolean name="rememberBrightness" value="true" /><float name="brightness" value="0.2" />'); a = attrs()
    ok("remembered brightness applied", "sb=0.2" in a or "screenBrightness=0.2" in a, re.findall(r"sb=[0-9.]+", a)[:3])
    vid("v1", ""); adb("shell", "input", "swipe", "400", "900", "400", "100", "400"); time.sleep(1.5); ok("left swipe changes brightness", "sb=" in attrs())
    vid("v1", '<boolean name="gestureBrightness" value="false" />'); adb("shell", "input", "swipe", "400", "900", "400", "100", "400"); time.sleep(1.5); ok("brightness gesture off", "sb=" not in attrs())
    vid("v1", '<boolean name="gestureVolume" value="false" />'); v0 = st().get("volume"); adb("shell", "input", "swipe", "1900", "900", "1900", "100", "400"); time.sleep(2.5); ok("volume gesture off", st().get("volume") == v0, (v0, st().get("volume")))
    adb("shell", f"run-as {PKG} rm -f files/qa_keep_ui")
    # music
    def song(xml, name="Song 2"):
        raw_prefs("settings", xml); raw_prefs("music_queue", "")
        home(); tab("Music"); wait(name, 15); tap(name); time.sleep(5)
    def now(): return re.search(r"description=([^,\n]*)", media())
    song(""); bars = [x for x in nodes() if x.get("text") == "Song 2"]; tap_xy(*center(bars[-1])); time.sleep(2)
    tap("Previous", exact=True); time.sleep(2); ok("Previous restarts the song first", "Song 2" in media(), (now() or [0, ""])[1])
    song('<boolean name="rewindBeforePrevious" value="false" />'); bars = [x for x in nodes() if x.get("text") == "Song 2"]; tap_xy(*center(bars[-1])); time.sleep(2)
    tap("Previous", exact=True); time.sleep(2); ok("off: goes to the previous song", "Song 1" in media() or "أغنية" in media(), (now() or [0, ""])[1])
    song('<boolean name="keepScreenOnMusic" value="true" />'); bars = [x for x in nodes() if x.get("text") == "Song 2"]; tap_xy(*center(bars[-1])); time.sleep(2)
    a = attrs(); ok("screen stays on with the song open", "KEEP_SCREEN_ON" in a, "")
    song(""); bars = [x for x in nodes() if x.get("text") == "Song 2"]; tap_xy(*center(bars[-1])); time.sleep(2); ok("screen timeout normal by default", "KEEP_SCREEN_ON" not in attrs())
    def pos():
        m = re.search(r"state=PlaybackState \{state=\w+\(?\d*\)?, position=(\d+)", media()); return int(m[1]) if m else -1
    def speed_test(xml):
        raw_prefs("settings", xml); raw_prefs("music_queue", ""); home(); tab("Music"); wait("Gap song", 15); tap("Gap song"); time.sleep(9)
        p = pos(); adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE"); return p if p >= 0 else None
    off_ = speed_test(""); on_ = speed_test('<boolean name="skipSilence" value="true" />')
    ok("skip silence jumps ahead", on_ is not None and off_ is not None and on_ > off_ + 3000, (off_, on_))
    raw_prefs("settings", ""); ok("no crash in more", not crashed())

def sec_moredl():
    SMALL = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"
    def go(url, wait_s=240):
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"'{url}'", "-n", f"{PKG}/.MainActivity"); time.sleep(3)
        wait("Quality", 90); sheet_download(); end = time.time() + wait_s
        while time.time() < end:
            t = " ".join(texts())
            if "Saved to your library" in t or "Failed" in t: return t
            time.sleep(3)
        return "timeout " + t
    def clear():
        adb("shell", f"run-as {PKG} rm -f files/download-history.json files/download-archive.txt files/cookies.txt")
    clear(); raw_prefs("downloads", '<boolean name="ipv4" value="true" /><int name="threads" value="4" />')
    t = go(SMALL); ok("IPv4 and parallel parts download", "Saved to your library" in t, t[:240])
    clear(); adb("shell", "am", "force-stop", PKG)
    adb("shell", f"run-as {PKG} sh -c 'mkdir -p files; echo \"# Netscape HTTP Cookie File\" > files/cookies.txt'")
    t = go(SMALL); ok("download with a cookies file", "Saved to your library" in t, t[:240])
    clear(); raw_prefs("downloads", '<boolean name="wifiOnly" value="true" />')
    adb("shell", "cmd", "connectivity", "airplane-mode", "disable", check=False)
    adb("shell", "svc", "wifi", "disable"); time.sleep(5)
    t = go(SMALL, 40); blocked = "metered" in t
    adb("shell", "svc", "wifi", "enable"); time.sleep(6)
    ok("Wi-Fi only refuses mobile data", blocked, t[:240])
    clear(); raw_prefs("downloads", '<string name="quality">P480</string><string name="container">mp4</string><string name="codec">h264</string><boolean name="skipSponsors" value="true" /><string name="limit">5M</string>')
    before = adb("shell", "ls", "/sdcard/Movies/Shoebill", check=False).split("\n")
    t = go("https://www.youtube.com/watch?v=aqz-KE-bpKQ", 400); ok("YouTube at 480p with MP4, H.264, SponsorBlock", "Saved to your library" in t, t[:300])
    new = [x for x in adb("shell", "ls", "/sdcard/Movies/Shoebill", check=False).split("\n") if x and x not in before]
    ok("file type is MP4", any(x.endswith(".mp4") for x in new), new[:3])
    if new:
        adb("pull", "/sdcard/Movies/Shoebill/" + new[0], os.environ["SHOTS"] + "/yt.mp4", check=False)
        pr = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name,height", "-of", "csv=p=0", os.environ["SHOTS"] + "/yt.mp4"], capture_output=True, text=True).stdout.strip()
        ok("codec H.264 at 480p or under", pr.startswith("h264") and int(pr.split(",")[1]) <= 480, pr)
    raw_prefs("downloads", ""); ok("no crash in moredl", not crashed())

def sec_download():
    home(); tab("Downloads")
    tap("Download", exact=True); time.sleep(1); ok("empty link names the field", find("Link: enter") is not None, texts()[:10])
    r, t = dl("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4", "direct")
    ok("direct file downloads to library", r, t)
    ok("file is in Movies/Shoebill", "Big_Buck" in adb("shell", "ls", "/sdcard/Movies/Shoebill", check=False), adb("shell", "ls", "/sdcard/Movies/Shoebill", check=False))
    r, t = dl("https://www.youtube.com/watch?v=aqz-KE-bpKQ", "yt", audio=True)
    ok("youtube audio as MP3 reaches Music/Shoebill", r and "mp3" in adb("shell", "ls", "/sdcard/Music/Shoebill", check=False), t)
    ok("no crash in downloads", not crashed())

S = dict(music=sec_music, nowplaying=sec_nowplaying, player=sec_player, download=sec_download, link=sec_link, network=sec_network, extras=sec_extras, white=sec_white, panels=sec_panels, pages=sec_pages, settings=sec_settings, video=sec_video, pip=sec_pip, more=sec_more, moredl=sec_moredl)
adb("logcat", "-c", "-b", "crash", check=False)
for s in sys.argv[1:] or S:
    print("==", s, flush=True)
    try: S[s]()
    except Exception as e: ok(s + " section finished", False, f"{type(e).__name__}: {e}")
bad = [n for n, c in results if not c]
print(f"{len(results)-len(bad)}/{len(results)} passed", "FAILED: " + "; ".join(bad) if bad else "")
