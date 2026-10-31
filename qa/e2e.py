#!/usr/bin/env python3
"""End-to-end player checks on a real phone. Asserts the player's real state from the debug `BrandState` log line
(MpvPlayer logs it once per second), and finds buttons by their content-desc through uiautomator — no blind taps.

    python3 qa/e2e.py            # all cases
    python3 qa/e2e.py T4 T6      # some cases
Results: table on stdout + qa/e2e-report.md. Needs the debug build installed (./gradlew installFullDebug) and adb on PATH. Keeps app data unless SHOEBILL_CLEAR=1.
Buttons are found by their Arabic labels: set the app language to Arabic (Settings → Language) first.
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

SERIAL = os.environ["ANDROID_SERIAL"]  # the device to test
PKG = os.environ.get("SHOEBILL_PKG", "dev.altayeb.shoebill")
# SHOEBILL_CLEAR=1 wipes app data first (pm clear) and allows the monkey case. Never on a phone whose vault matters.
CLEAN = os.environ.get("SHOEBILL_CLEAR") == "1"
ACT = f"{PKG}/dev.altayeb.shoebill.MainActivity"
HERE = os.path.dirname(os.path.abspath(__file__))
# MediaStore ids of two test videos: A = HEVC 10-bit + two audio tracks + ASS subtitles, B = HEVC + one subtitle.
# qa/make-media.sh creates both and prints their ids.
VID_A, VID_B = int(os.environ["SHOEBILL_VIDEO_A"]), int(os.environ["SHOEBILL_VIDEO_B"])
B_MIN = float(os.environ.get("SHOEBILL_B_MIN", 1))  # B's length in minutes, to tell the two files apart


def adb(*a, check=False):
    r = subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True, encoding="utf-8", errors="replace")
    if check and r.returncode:
        raise RuntimeError(r.stderr)
    return r.stdout


def sh(cmd):
    return adb("shell", cmd)


def open_video(vid, fresh=True):
    adb("logcat", "-c")
    sh(f"am start {'-S ' if fresh else ''}-a android.intent.action.VIEW -t 'video/*' "
       f"-d content://media/external/video/media/{vid} -n {ACT}")
    time.sleep(6)


def state():
    """Last BrandState line → dict."""
    lines = [l for l in adb("logcat", "-d", "-s", "BrandState").splitlines() if "BrandState:" in l]
    if not lines:
        return {}
    body = lines[-1].split("BrandState: ", 1)[1]
    return dict(re.findall(r"(\S+?)=(\S*)", body))


def t(s):
    try:
        return float(s.get("time-pos", "nan"))
    except ValueError:
        return float("nan")


def dur(s):
    try:
        return float(s.get("duration") or 0)
    except ValueError:  # "null" while loading
        return 0.0


def wait_state(secs=1.6):
    time.sleep(secs)
    return state()


def dump():
    # uiautomator often fails to get an idle state while video is playing: retry
    for _ in range(4):
        sh("rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        xml = adb("exec-out", "cat", "/sdcard/ui.xml")
        try:
            root = ET.fromstring(xml[xml.index("<"):])
            if len(list(root.iter("node"))) > 1:
                return root
        except Exception:
            pass
        time.sleep(0.5)
    return None


def nodes(root):
    return [] if root is None else list(root.iter("node"))


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(label, root=None, exact=True):
    root = root if root is not None else dump()
    for n in nodes(root):
        for attr in ("content-desc", "text"):
            v = n.get(attr) or ""
            if (v == label) if exact else (label in v):
                return n
    return None


def tap_xy(x, y):
    sh(f"input tap {x} {y}")


def screen():
    w, h = map(int, re.findall(r"(\d+)x(\d+)", sh("wm size"))[-1])
    return (max(w, h), min(w, h))  # player is landscape


def ui():
    """controls / panel / locked as reported by the app (user-data/shoebill/ui in the BrandState line)."""
    raw = state().get("user-data/shoebill/ui", "").strip('"')  # mpv returns user-data strings JSON-quoted
    return dict(kv.split("=", 1) for kv in raw.split(",") if "=" in kv)


def show_controls():
    if ui().get("controls") != "1":
        w, h = screen()
        tap_xy(w // 2, h // 4)  # above the centre play button
        time.sleep(1.2)  # wait for the next BrandState tick


def hide_controls():
    """Gestures that start on the top/bottom bars are ignored while controls show."""
    for _ in range(6):  # the first state line can lag on a slow device: wait until the app reports its UI
        if ui():
            break
        time.sleep(1)
    for _ in range(3):  # a tap during loading can be swallowed: check it took
        if ui().get("controls") != "1":
            return
        w, h = screen()
        tap_xy(w // 2, h // 4)
        time.sleep(1.6)


POS = {}  # label -> (x, y): overlay buttons don't move, so remember where they are


def tap(label, exact=True):
    """Tap an overlay button or panel item. Overlay buttons auto-hide after 3 s and a uiautomator dump takes ~2 s,
    so buttons are located once and then tapped right after making the controls visible."""
    if label not in POS:
        n = None
        for _ in range(3):  # controls can auto-hide during the ~2 s dump: retry
            show_controls()
            n = find(label, exact=exact)
            if n is not None:
                break
        if n is None:
            return False
        POS[label] = center(n)
        in_panel = ui().get("panel", "none") != "none"
        if in_panel:  # panel rows can move between panels: don't cache them
            del POS[label]
            tap_xy(*center(n)); time.sleep(0.8)
            return True
    show_controls()
    tap_xy(*POS[label])
    time.sleep(0.8)
    return True


def labels():
    return sorted({(n.get("content-desc") or n.get("text")) for n in nodes(dump()) if (n.get("content-desc") or n.get("text"))})


def back():
    sh("input keyevent 4")
    time.sleep(1.2)


def shot(name):
    os.makedirs(os.path.join(HERE, "shots"), exist_ok=True)
    with open(os.path.join(HERE, "shots", f"e2e-{name}.png"), "wb") as f:
        f.write(subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True).stdout)


def crashes():
    return [l for l in adb("logcat", "-d").splitlines() if "FATAL EXCEPTION" in l or ("AndroidRuntime" in l and "E " in l) or "ANR in" in l]


# ------------------------------------------------------------------ cases
RESULTS = []


def case(cid, name):
    def deco(f):
        f.cid, f.title = cid, name
        return f
    return deco


@case("T1", "plays: time advances, HW decoder in use")
def t1():
    open_video(VID_A)
    a = state(); b = wait_state(3)
    ok = t(b) - t(a) > 2 and b.get("pause") == "no"
    return ok, f"t {t(a):.1f}→{t(b):.1f}, hwdec={b.get('hwdec-current')}"


@case("T2", "pause / play button")
def t2():
    tap("إيقاف مؤقت"); s1 = wait_state(); s2 = wait_state(2)
    paused_ok = s1.get("pause") == "yes" and abs(t(s2) - t(s1)) < 0.2
    tap("تشغيل"); s3 = wait_state(2)
    return paused_ok and s3.get("pause") == "no", f"pause={s1.get('pause')} frozen={abs(t(s2)-t(s1))<0.2} resume={s3.get('pause')}"


@case("T3", "+10 s button twice")
def t3():
    tap("+10 ث"); time.sleep(1.5)  # locate the button first (this one seeks too)
    a, ta = state(), time.time(); tap("+10 ث"); tap("+10 ث"); b = wait_state(1.2)
    d = t(b) - t(a) - (time.time() - ta)
    return 17 < d < 23, f"jump {d:.1f}s beyond playback"


@case("T4", "double-tap right third → +10 accumulating")
def t4():
    hide_controls()
    w, h = screen(); x, y = int(w * 0.85), h // 2
    a, ta = state(), time.time(); sh(f"input tap {x} {y}; input tap {x} {y}"); b = wait_state(1.5)
    d = t(b) - t(a) - (time.time() - ta)
    return 7 < d < 13, f"jump {d:.1f}s beyond playback"


@case("T5", "subtitle track off and back — video keeps playing")
def t5():
    if not tap("الترجمة"):
        return False, "subtitles button not found"
    root = dump()
    opts = [n.get("text") for n in nodes(root) if n.get("text")]
    off = next((o for o in opts if o in ("بلا ترجمة", "إيقاف", "لا شيء", "بدون")), None)
    if off is None:
        return False, f"no 'off' option; panel texts: {opts[:12]}"
    tap(off); s1 = wait_state(); s2 = wait_state(2)
    playing = s1.get("pause") == "no" and t(s2) - t(s1) > 1.5
    sid_off = s1.get("sid") == "no"
    back()  # close panel
    return playing and sid_off, f"sid={s1.get('sid')} playing={playing}"


@case("T6", "audio track switch — keeps playing")
def t6():
    a = state()
    if not tap("الصوت"):
        return False, "audio button not found"
    root = dump()
    tracks = [n for n in nodes(root) if (n.get("text") or "").strip() and n.get("clickable") == "true"]
    texts = [n.get("text") for n in nodes(root) if n.get("text")]
    target = next((n for n in nodes(root) if re.search(r"truehd|eac3|e-ac-3|aac|ac3", (n.get("text") or ""), re.I)), None)
    if target is None:
        return False, f"no audio track rows; texts: {texts[:15]}"
    # pick a row that is not the current one: try each until aid changes
    cands = [n for n in nodes(root) if re.search(r"truehd|eac3|e-ac-3|aac|ac3", (n.get("text") or ""), re.I)]
    for n in cands:
        tap_xy(*center(n)); time.sleep(1)
        if state().get("aid") != a.get("aid"):
            break
    s1 = wait_state(); s2 = wait_state(2)
    back()
    return s1.get("aid") != a.get("aid") and t(s2) - t(s1) > 1.5, f"aid {a.get('aid')}→{s1.get('aid')} playing={t(s2)-t(s1)>1.5}"


@case("T7", "decoder SW → HW → HW+ live")
def t7():
    if not tap("المزيد"):
        return False, "more button not found"
    out = []
    ok = True
    for label, want in (("SW", "no"), ("HW", "mediacodec"), ("HW+", "mediacodec-copy")):
        n = find(label)
        if n is None:
            return False, f"'{label}' not in options panel; {labels()[:15]}"
        tap_xy(*center(n)); s1 = wait_state(2.5); s2 = wait_state(2)
        moving = t(s2) - t(s1) > 1.5
        out.append(f"{label}:{s1.get('hwdec-current')} moving={moving}")
        ok &= moving and (s1.get("hwdec-current") or "").startswith(want if want != "mediacodec" else "mediacodec") and \
            (want != "mediacodec" or s1.get("hwdec-current") == "mediacodec")
    shot("t7-decoder")
    back()
    return ok, "; ".join(out)


@case("T8", "volume drag on the right side")
def t8():
    hide_controls()
    w, h = screen(); x = int(w * 0.8)
    a = state()
    sh(f"input motionevent DOWN {x} {int(h*0.7)}")
    for f in (0.65, 0.6, 0.55, 0.5, 0.45, 0.4):
        sh(f"input motionevent MOVE {x} {int(h*f)}")
    shot("t8-volume")
    sh(f"input motionevent UP {x} {int(h*0.4)}")
    b = wait_state(1.2)
    return float(b.get("volume", 0)) != float(a.get("volume", 0)), f"volume {a.get('volume')}→{b.get('volume')}"


@case("T9", "resume after leaving and reopening")
def t9():
    open_video(VID_B)
    time.sleep(20); a = state()
    back(); back()
    open_video(VID_B, fresh=False)
    b = state()
    return abs(t(b) - t(a)) < 12, f"left at {t(a):.1f}, reopened at {t(b):.1f}"


@case("T10", "per-file memory: subtitle off survives reopen")
def t10():
    if not tap("الترجمة"):
        return False, "subtitles button not found"
    opts = [n.get("text") for n in nodes(dump()) if n.get("text")]
    off = next((o for o in opts if o in ("بلا ترجمة", "إيقاف", "لا شيء", "بدون")), None)
    if off is None:
        return False, f"no off option {opts[:10]}"
    tap(off); time.sleep(6)  # let the 5 s save tick run
    back(); back(); back()
    open_video(VID_B, fresh=False)
    s = state()
    return s.get("sid") == "no", f"sid after reopen={s.get('sid')}"


@case("T11", "open another file while one plays")
def t11():
    open_video(VID_A)
    for _ in range(10):  # let the first file finish loading on a slow device
        if dur(state()) > 0:
            break
        time.sleep(1.5)
    open_video(VID_B, fresh=False)
    ok, d = False, 0.0
    for _ in range(10):  # a slow device (emulator) needs longer than open_video's 6 s to switch files
        d = dur(state())  # files are fd numbers to mpv, so identify by duration (B_MIN)
        ok = abs(d / 60 - B_MIN) < max(0.2, B_MIN * 0.15)
        if ok:
            break
        time.sleep(1.5)
    return ok, f"duration {d/60:.1f} min"


@case("T12", "open/close player 10× — no crash, memory")
def t12():
    def pss():
        m = re.search(r"TOTAL PSS:\s+(\d+)", sh(f"dumpsys meminfo {PKG}")) or re.search(r"TOTAL\s+(\d+)", sh(f"dumpsys meminfo {PKG}"))
        return int(m.group(1)) // 1024 if m else -1
    open_video(VID_B); back(); before = pss()
    for _ in range(10):
        open_video(VID_B, fresh=False); back()
    after = pss(); c = crashes()
    return not c and (after < 0 or after - before < 150), f"PSS {before}→{after} MB, crashes={len(c)}"


@case("T13", "monkey 1500 events × 2 seeds")
def t13():
    adb("logcat", "-c")
    out = ""
    for seed in (7, 42):
        out += sh(f"monkey -p {PKG} -s {seed} --throttle 120 --pct-syskeys 0 -v 1500")
    c = crashes(); bad = "CRASH" in out or "NOT RESPONDING" in out
    return not c and not bad, f"crash lines={len(c)} monkey_says_crash={bad}"


@case("T14", "lock, then hold the lock button 2.6 s to unlock")
def t14():
    open_video(VID_A)  # the case before (T12/T13) may leave no player open
    if not tap("قفل"):
        return False, "lock button not found"
    time.sleep(2.5)
    locked = ui().get("locked") == "1"
    n = find("قفل: اضغط مطولاً للفتح")
    if n is None:
        show_controls(); n = find("قفل: اضغط مطولاً للفتح")
    if n is None:
        return False, f"locked={locked}, unlock button not found"
    x, y = center(n)
    sh(f"input swipe {x} {y} {x} {y} 2600")
    time.sleep(2.5)
    return locked and ui().get("locked") == "0", f"locked={locked} after hold locked={ui().get('locked')}"


CASES = [t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14]


def main():
    want = set(sys.argv[1:])
    adb("shell", "settings put system screen_off_timeout 600000")
    # clean app state: the monkey case (T13) toggles real settings (it once turned auto-resume off)
    if CLEAN:
        sh(f"pm clear {PKG}")
    sh(f"pm grant {PKG} android.permission.READ_MEDIA_VIDEO")
    sh(f"run-as {PKG} sh -c 'mkdir -p files && touch files/qa_keep_ui'")  # controls stay up until tapped away
    if want and "T1" not in want:
        open_video(VID_A)  # T1 is what normally opens the first video
    for f in CASES:
        if want and f.cid not in want:
            continue
        if f.cid == "T13" and not CLEAN:  # random taps change real settings: only on a cleared app
            continue
        adb("logcat", "-c") if f.cid in ("T1", "T9", "T11", "T13") else None
        try:
            ok, info = f()
        except Exception as e:  # a broken case must not stop the run
            ok, info = False, f"error: {e}"
        if ui().get("panel", "none") != "none":
            back()  # a failed case must not leave a panel open for the next one
        c = crashes()
        if c:
            ok, info = False, info + f" | CRASH: {c[0][:120]}"
        RESULTS.append((f.cid, f.title, "PASS" if ok else "FAIL", info))
        print(f"{f.cid:4} {'PASS' if ok else 'FAIL'}  {f.title} — {info}", flush=True)
    with open(os.path.join(HERE, "e2e-report.md"), "w") as fh:
        fh.write("| ID | Case | Result | Detail |\n|---|---|---|---|\n")
        for r in RESULTS:
            fh.write("| " + " | ".join(r) + " |\n")
    print(f"\n{sum(r[2]=='PASS' for r in RESULTS)}/{len(RESULTS)} passed")


if __name__ == "__main__":
    main()
