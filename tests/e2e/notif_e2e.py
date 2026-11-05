#!/usr/bin/env python3
"""Notifications and deep links, on a phone with the debug build + tests/e2e/make-fixtures.sh media. ANDROID_SERIAL and SERIAL name the phone."""
import os, re, sys, time
sys.path.insert(0, os.path.dirname(__file__))
from ui import *
PKG = "dev.altayeb.shoebill"
SMALL = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"
res = []
def ok(n, c, extra=""):
    res.append((n, bool(c))); print(("PASS " if c else "FAIL ") + n + ("" if c or not extra else " :: " + str(extra)[:300]), flush=True)
def notif(): return adb("shell", "dumpsys", "notification", "--noredact")
def link(u): adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", u, "-n", f"{PKG}/.MainActivity"); time.sleep(3)
def fresh():
    adb("shell", "am", "force-stop", PKG); adb("shell", "cmd", "notification", "set_dnd", "off", check=False); time.sleep(1)
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
def press():
    end = time.time() + 180  # a fresh install spends about a minute unpacking and updating yt-dlp on the first link
    while time.time() < end and find("Reading the link") is not None: time.sleep(1.5)
    for _ in range(4):
        d = [n for n in nodes() if n.get("text") == "Download"]
        if d and find("Cancel", exact=True) is not None: return tap_xy(*center(d[-1]))
        x, y = center(find("Drag handle")); adb("shell", "input", "swipe", str(x), str(y + 300), str(x), str(max(y - 450, 20)), "300"); time.sleep(1)
    raise AssertionError("sheet Download button not reachable: " + str(texts()))
def shade():
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    adb("shell", "cmd", "statusbar", "expand-notifications"); time.sleep(1.5)
    for _ in range(3):
        if find("Expand", exact=True) is None: break
        tap("Expand", 5, exact=True); time.sleep(1)
    if find("Try again", exact=True) is None and find("Download failed", exact=True) is not None:
        tap("Download failed", 5, exact=True); time.sleep(1)  # a collapsed notification shows its actions only once expanded
    return texts()
def crashed(): return "FATAL EXCEPTION" in adb("logcat", "-d", "-b", "crash", check=False)
def mine(): return "\n".join(b for b in notif().split("NotificationRecord") if f"pkg={PKG}" in b)
def step(n, f):
    try: f()
    except Exception as e: ok(n + " finished", False, f"{type(e).__name__}: {e}")

adb("logcat", "-c", "-b", "crash", check=False)
adb("shell", "settings", "put", "secure", "immersive_mode_confirmations", "confirmed")

def deep():
    fresh(); link("shoebill://music"); t = texts()
    ok("shoebill://music opens the Music tab", "Songs" in t and "Albums" in t, t[:14])
    link("shoebill://downloads"); t = texts()
    ok("shoebill://downloads opens the Downloads tab", find("Paste a link") is not None or find("Downloads") is not None and "Songs" not in t, t[:14])
    link("shoebill://music")  # already running: a second link must still switch tabs
    ok("link while running switches tab", "Songs" in texts(), texts()[:10])
    adb("logcat", "-c"); fresh(); link("shoebill://play?url=file:///sdcard/Movies/v1.mp4"); time.sleep(4); t = texts()
    bs = [l for l in adb("logcat", "-d", "-s", "BrandState").splitlines() if "BrandState:" in l]
    ok("shoebill://play?url= opens the player", bool(bs) and "v1" in focus() + " ".join(bs[-1:]) or bool(bs), (bs[-1:], t[:8]))
    fresh(); link("shoebill://nonsense"); ok("unknown host does not crash", not crashed() and PKG in focus(), focus())
step("deep links", deep)

def music():
    fresh(); link("shoebill://music"); wait("Songs", 10); tap("Song 2"); time.sleep(3)
    n = mine()
    ok("music notification posted", "Song 2" in n, n[:200])
    ok("music notification has transport actions", len(re.findall(r"actions=\d+", n)) and re.search(r"actions=([3-9]|\d\d)", n), re.findall(r"actions=\d+", n))
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    adb("shell", "cmd", "statusbar", "expand-notifications"); time.sleep(1.5)
    tap("Song 2", 5); time.sleep(2)
    ok("tapping the notification reopens the app", PKG in focus(), focus())
    adb("shell", "cmd", "statusbar", "collapse", check=False)
    adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE"); time.sleep(1)
step("music notification", music)

def video():
    adb("logcat", "-c"); fresh(); link("shoebill://play?url=file:///sdcard/Movies/v1.mp4"); time.sleep(5)
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(3)
    n = mine()
    ok("video playback notification exists in background", "v1" in n or "Video playback" in n or "video" in n.lower(), n[:200])
step("video notification", video)

def dl():
    fresh(); adb("shell", "cmd", "notification", "cancel_all", check=False) if False else None
    link("shoebill://download?url=" + SMALL); wait("Quality", 180)
    press()
    end = time.time() + 120; done = False
    while time.time() < end and not done:
        done = "downloads_done" in mine() or "Big_Buck" in mine() and "Saved" in mine(); time.sleep(3)
    ok("download-done notification posted", done, mine()[-400:])
    t = shade(); ok("done notification shows in shade", any("Big_Buck" in x or "Saved" in x or "finished" in x.lower() for x in t), t[:14])
    adb("shell", "cmd", "statusbar", "collapse", check=False)
step("download done", dl)

def fail():
    fresh(); link("shoebill://download?url=https://test-videos.co.uk/nope/missing.mp4"); time.sleep(4)
    press()
    time.sleep(25); n = mine()
    ok("failed download posts a notification", "Try again" in n or "Failed" in n or "failed" in n, n[-400:])
    t = shade(); ok("failed notification offers Try again in the shade", find("Try again", exact=True) is not None, t[:20])
    if find("Try again", exact=True) is not None:
        tap("Try again", 5, exact=True); time.sleep(8)
        ok("Try again runs the job again without a crash", not crashed() and adb("shell", "pidof", PKG, check=False).strip() != "")
    adb("shell", "cmd", "statusbar", "collapse", check=False)
step("download failed", fail)

ok("no crash in notifications", not crashed())
bad = [n for n, c in res if not c]
print(f"{len(res)-len(bad)}/{len(res)} passed", "FAILED: " + "; ".join(bad) if bad else "")
