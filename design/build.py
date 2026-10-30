#!/usr/bin/env python3
"""«Anon Player» — one HTML: references → design system → screen flow → annex.  python3 build.py → out/anon-player.html

Format: phases → flows → screens at real size,
each screen: يصل إليها / ما يحدث / حالات أخرى / القواعد / تقود إلى; the design-system part draws the SAME classes the
screens use, and colours are read from scheme.json (M3 tonal-spot from seed, material-color-utilities), so the
system section cannot drift from the screens. Icons: Material Symbols Rounded SVGs in icons/ (like the Caelestia shell)."""
import html, itertools, json, math, os

_WC = itertools.count()

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out", "anon-player.html")
SCHEME = json.load(open(os.path.join(HERE, "scheme.json")))
THEMES = json.load(open(os.path.join(HERE, "themes.json")))  # 12 presets + 5 scheme variants, same generator
SEED = "#0B6E69"
DATE = "أكتوبر 2026"

ROLE = {  # css var → M3 role
    "bg": "surface", "sfl": "surfaceContainerLow", "sfc": "surfaceContainer", "sfh": "surfaceContainerHigh",
    "sfhh": "surfaceContainerHighest", "sfw": "surfaceContainerLowest", "on": "onSurface", "onv": "onSurfaceVariant",
    "ol": "outline", "olv": "outlineVariant", "pr": "primary", "onpr": "onPrimary", "prc": "primaryContainer",
    "onprc": "onPrimaryContainer", "sec": "secondary", "secc": "secondaryContainer", "onsecc": "onSecondaryContainer",
    "ter": "tertiary", "terc": "tertiaryContainer", "onterc": "onTertiaryContainer", "err": "error", "errc": "errorContainer",
    "onerrc": "onErrorContainer", "inv": "inverseSurface", "oninv": "inverseOnSurface",
}


def vars_for(mode):
    s = SCHEME[mode]
    return "".join(f"--{k}:{s[v]};" for k, v in ROLE.items()) + f"--amb:{SCHEME['amber']};"


# ---------------------------------------------------------------- shapes (M3 Expressive shape library, approximated)
def poly(n, a, steps=240, rot=-90):
    pts = []
    for i in range(steps):
        t = 2 * math.pi * i / steps
        r = 0.5 * (1 - a) + 0.5 * a * math.cos(n * (t - math.radians(rot)))
        pts.append((0.5 + r * math.cos(t), 0.5 + r * math.sin(t)))
    return "M" + "L".join(f"{x:.4f},{y:.4f}" for x, y in pts) + "Z"


SHAPES = {"cookie9": poly(9, .09), "cookie12": poly(12, .07), "clover4": poly(4, .22), "sunny8": poly(8, .05)}


def wave(w, h=20, amp=3.2, wl=18, x0=2, x1=None):
    x1 = w if x1 is None else x1
    y = h / 2
    d = [f"M{x0},{y + amp * math.sin(2 * math.pi * x0 / wl):.2f}"]
    x = x0
    while x < x1:
        x = min(x + 1.5, x1)
        d.append(f"L{x:.1f},{y + amp * math.sin(2 * math.pi * x / wl):.2f}")
    return "".join(d)


# ---------------------------------------------------------------- icons (Material Symbols Rounded, wght 400)
def load_icons():
    out = {}
    d = os.path.join(HERE, "icons")
    for f in sorted(os.listdir(d)):
        if f.endswith(".svg"):
            src = open(os.path.join(d, f)).read()
            out[f[:-4]] = src[src.index(">") + 1: src.rindex("</svg>")]
    return out


ICONS = load_icons()
NICONS = len([k for k in ICONS if not k.endswith("-fill")])
SPRITE = ('<svg width="0" height="0" style="position:absolute" aria-hidden="true"><defs>'
          + "".join(f'<symbol id="m-{k}" viewBox="0 -960 960 960">{v}</symbol>' for k, v in ICONS.items())
          + "".join(f'<clipPath id="c-{k}" clipPathUnits="objectBoundingBox"><path d="{v}"/></clipPath>' for k, v in SHAPES.items())
          + f'<symbol id="logo" viewBox="0 0 1 1"><path d="{SHAPES["cookie12"]}" fill="currentColor"/>'
            '<path d="M.42 .33v.34l.27-.17z" fill="var(--logo-play,#fff)" stroke="var(--logo-play,#fff)" stroke-width=".04" stroke-linejoin="round"/></symbol>'
          + "</defs></svg>")


def I(n, size=24, cls=""):
    return f'<svg class="ms {cls}" style="width:{size}px;height:{size}px"><use href="#m-{n}"/></svg>'


LOGO = '<svg class="logo"><use href="#logo"/></svg>'

# ---------------------------------------------------------------- CSS
L = SCHEME["light"]
CSS = f"""
/* Layout: one calm RTL column tinted from the app's own light scheme; phone frames beside notes, landscape frames full width. */
:root{{
  --bg:{L['surfaceContainerLow']}; --card:{L['surfaceContainerLowest']}; --surface-alt:{L['surfaceContainerHigh']}; --border:{L['outlineVariant']};
  --text:{L['onSurface']}; --text-2:{L['onSurfaceVariant']}; --text-3:{L['outline']};
  --accent:{L['primary']}; --accent-dark:{L['onPrimaryContainer']}; --brand-bg:{L['primaryContainer']};
  --sec-c:{L['secondaryContainer']}; --on-sec-c:{L['onSecondaryContainer']}; --ter-c:{L['tertiaryContainer']}; --on-ter-c:{L['onTertiaryContainer']};
  --font:"Rubik","IBM Plex Sans Arabic","Noto Sans Arabic",system-ui,sans-serif;
  color-scheme:light;
}}
*{{box-sizing:border-box}}
html,body{{background:var(--bg);margin:0;color:var(--text)}}
body{{padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px))}}
.fx-doc{{max-width:880px;margin:0 auto;direction:rtl;font-family:var(--font)}}
svg.ms{{fill:currentColor;flex:none;display:block}}
svg.ms.flip{{transform:scaleX(-1)}}
svg.logo{{display:block;color:var(--accent)}}
.num{{font-variant-numeric:tabular-nums}}
a:focus-visible{{outline:2px solid var(--accent);outline-offset:2px}}

.jn{{position:sticky;top:env(safe-area-inset-top,0px);z-index:50;display:flex;align-items:center;gap:4px;padding:10px 0;background:var(--bg);border-bottom:1px solid var(--border);flex-wrap:wrap}}
body::before{{content:"";position:fixed;inset:0 0 auto;height:env(safe-area-inset-top,0px);z-index:60;background:var(--bg)}}
.jn .lg{{display:flex;align-items:center;gap:8px;margin-inline-end:auto;font:600 17px/1 var(--font)}}
.jn .lg svg{{width:30px;height:30px;--logo-play:#fff}}
.jn a{{font:500 14px/1 var(--font);color:var(--text-2);text-decoration:none;padding:10px 14px;border-radius:20px}}
.jn a:hover{{background:var(--sec-c);color:var(--on-sec-c)}}

.fx-phase{{padding-block:44px 40px;scroll-margin-top:60px}}
.fx-phase+.fx-phase{{border-top:1px solid var(--border)}}
.fx-cover{{padding:30px 0 34px}}
.fx-cover h1{{margin:0;font:700 52px/1.1 var(--font);letter-spacing:-.5px}}
.fx-cover h1 span{{color:var(--accent)}}
.fx-lede{{margin:16px 0 0;font:400 16.5px/1.85 var(--font);color:var(--text-2);max-width:68ch}}
.fx-hero{{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px;margin:28px 0 0;align-items:center}}
.fx-hero div{{aspect-ratio:1}}
.fx-stats{{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin:26px 0 0}}
.fx-stats div{{background:var(--card);border-radius:24px;padding:16px 18px}}
.fx-stats b{{display:block;font:600 32px/1 var(--font);color:var(--accent)}}
.fx-stats span{{font:400 13px/1.4 var(--font);color:var(--text-2)}}
.fx-plist{{margin:22px 0 0;display:grid;gap:3px}}
.fx-plist a{{display:grid;grid-template-columns:44px 1fr auto;gap:14px;align-items:center;background:var(--card);border-radius:6px;padding:12px 16px;color:inherit;text-decoration:none}}
.fx-plist a:first-child{{border-radius:24px 24px 6px 6px}}.fx-plist a:last-child{{border-radius:6px 6px 24px 24px}}
.fx-plist a:hover{{background:var(--sec-c)}}
.fx-plist i{{width:44px;height:44px;background:var(--brand-bg);color:var(--accent-dark);display:grid;place-items:center;font:600 18px/1 var(--font);font-style:normal;clip-path:url(#c-cookie9)}}
.fx-plist b{{font:600 16px/1.3 var(--font)}}
.fx-plist small{{display:block;font:400 12.5px/1.4 var(--font);color:var(--text-2)}}
.fx-plist em{{color:var(--text-3)}}
.fx-legend{{display:flex;flex-wrap:wrap;gap:8px 16px;margin:18px 0 0;font:400 13px/1.6 var(--font);color:var(--text-2)}}
.fx-legend span{{display:inline-flex;align-items:center;gap:6px}}
.fx-ph-hd{{display:grid;grid-template-columns:auto 1fr;gap:6px 18px;align-items:center;margin-bottom:8px}}
.fx-ph-hd .fx-no{{grid-row:span 2;width:76px;height:76px;background:var(--accent);color:#fff;display:grid;place-items:center;font:600 34px/1 var(--font);clip-path:url(#c-cookie9)}}
.fx-ph-hd small{{font:500 13.5px/1 var(--font);color:var(--accent)}}
.fx-ph-hd h2{{margin:0;font:700 32px/1.25 var(--font)}}
.fx-ph-goal{{margin:14px 0 0;font:400 16px/1.85 var(--font);color:var(--text-2);max-width:72ch}}
.fx-flow{{margin-top:40px}}
.fx-fl-hd{{display:flex;align-items:baseline;gap:10px;padding-bottom:12px;border-bottom:2px solid var(--text)}}
.fx-fl-hd .fx-n{{font:600 15px/1 var(--font);color:var(--accent)}}
.fx-fl-hd h3{{margin:0;font:600 23px/1.3 var(--font)}}
.fx-path{{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin:12px 0 4px;font:500 12.5px/1 var(--font);color:var(--text-3)}}
.fx-code{{display:inline-block;font:600 12.5px/1 var(--font);color:var(--accent-dark);background:var(--brand-bg);border-radius:8px;padding:5px 8px;white-space:nowrap}}
.fx-st{{display:inline-flex;align-items:center;gap:5px;font:500 12px/1 var(--font);border-radius:999px;padding:5px 10px;white-space:nowrap;background:var(--surface-alt);color:var(--text-2)}}
.fx-br{{display:inline-block;font:600 11.5px/1 var(--font);color:var(--on-ter-c);background:var(--ter-c);border-radius:7px;padding:5px 7px}}
.fx-chip{{display:inline-block;font:400 12px/1.35 var(--font);color:var(--text-2);background:var(--surface-alt);border-radius:8px;padding:6px 8px}}
.fx-row{{display:grid;grid-template-columns:280px minmax(0,1fr);gap:30px;align-items:start;margin-top:26px}}
.fx-row.fx-wide{{grid-template-columns:minmax(0,1fr)}}
.fx-fig{{overflow-x:auto;min-width:0}}
.fx-txt{{padding-top:8px;min-width:0}}
.fx-txt .fx-t{{display:flex;align-items:center;gap:9px;flex-wrap:wrap}}
.fx-txt h4{{margin:10px 0 0;font:600 21px/1.35 var(--font);flex-basis:100%}}
.fx-lbl{{margin:16px 0 6px;font:600 12px/1 var(--font);color:var(--text-3)}}
.fx-txt p{{margin:0;font:400 14.5px/1.75 var(--font)}}
.fx-txt ul{{margin:0;padding:0;list-style:none;display:grid;gap:7px}}
.fx-txt li{{position:relative;padding-inline-start:18px;font:400 14.5px/1.75 var(--font)}}
.fx-txt li::before{{content:"";position:absolute;inset-inline-start:0;top:9px;width:9px;height:9px;background:var(--accent);clip-path:url(#c-clover4)}}
.fx-chips{{display:flex;flex-wrap:wrap;gap:6px}}
.fx-row.fx-wide .fx-txt{{display:grid;grid-template-columns:1.3fr 1fr;gap:4px 34px;padding-top:0}}
.fx-row.fx-wide .fx-txt .fx-t{{grid-column:1/-1}}
.fx-arrow{{height:34px;width:280px;display:grid;place-items:center;margin-top:18px;color:var(--accent);opacity:.45}}
.fx-arrow svg{{width:22px;height:34px}}
.fx-ixw{{overflow-x:auto}}
table.fx-ix{{width:100%;border-collapse:separate;border-spacing:0;font:400 13.5px/1.6 var(--font);background:var(--card);border-radius:20px;overflow:hidden}}
table.fx-ix th{{background:var(--surface-alt);font:600 12px/1.3 var(--font);color:var(--text-2);text-align:start;padding:10px 12px}}
table.fx-ix td{{padding:10px 12px;border-top:1px solid var(--border);vertical-align:top}}
table.fx-ix td.fx-c{{white-space:nowrap}}
table.fx-ix td b{{font-weight:600}}
.fx-end{{margin:30px 0 0;font:400 12.5px/1.7 var(--font);color:var(--text-3);text-align:center}}

.dz p{{margin:0 0 12px;font:400 15px/1.9 var(--font);max-width:72ch}}
.dz h2.sec{{display:flex;align-items:baseline;gap:12px;margin:0 0 6px;font:700 34px/1.3 var(--font)}}
.dz h2.sec small{{font:600 14px/1 var(--font);color:var(--accent)}}
.dz .kicker{{font:400 15.5px/1.8 var(--font);color:var(--text-2);margin:0 0 22px;max-width:72ch}}
.dz h3{{margin:38px 0 12px;font:600 20px/1.35 var(--font);padding-bottom:10px;border-bottom:2px solid var(--text)}}
.dz .grid{{display:grid;gap:8px}}
.dz .g2{{grid-template-columns:repeat(2,minmax(0,1fr))}}.dz .g3{{grid-template-columns:repeat(3,minmax(0,1fr))}}
.dz .card2{{background:var(--card);border-radius:24px;padding:18px 20px;min-width:0}}
.dz .card2 > b{{display:flex;align-items:center;gap:12px;font:600 16px/1.4 var(--font);margin-bottom:8px}}
.dz .card2 > b .pi{{width:44px;height:44px;display:grid;place-items:center;background:var(--brand-bg);color:var(--accent-dark);clip-path:url(#c-cookie9);flex:none}}
.dz .card2 > span{{font:400 13.5px/1.75 var(--font);color:var(--text-2)}}
.dz code{{font:500 12px/1.5 ui-monospace,monospace;color:var(--accent-dark);background:var(--brand-bg);border-radius:6px;padding:1px 6px;direction:ltr;display:inline-block}}
.dz .take{{color:var(--accent);font-weight:600}} .dz .avoid{{color:#B3261E;font-weight:600}}
.sw{{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:6px}}
.sw div{{background:var(--card);border-radius:16px;overflow:hidden}}
.sw i{{display:block;height:52px}}
.sw b{{display:block;padding:8px 10px 0;font:500 12px/1.3 ui-monospace,monospace;direction:ltr;text-align:right;word-break:break-all}}
.sw code{{display:block!important;padding:2px 10px 9px!important;background:none!important;color:var(--text-2)!important;text-align:right}}
.sw-grp h5{{margin:18px 0 8px;font:600 15px/1.3 var(--font)}}
.ty{{display:grid;grid-template-columns:150px minmax(0,1fr);gap:12px 18px;align-items:baseline;background:var(--card);border-radius:24px;padding:20px}}
.ty small{{font:500 12px/1.4 var(--font);color:var(--text-3);direction:ltr;text-align:right}}
.rad{{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:8px}}
.rad div{{height:86px;background:var(--brand-bg);display:grid;place-items:end center;padding-bottom:8px;font:500 12px/1.3 var(--font);color:var(--accent-dark);text-align:center}}
.shp{{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}}
.shp div{{background:var(--card);border-radius:24px;padding:16px 10px;display:grid;justify-items:center;gap:10px;font:500 12.5px/1.4 var(--font);color:var(--text-2);text-align:center}}
.shp i{{display:block;width:76px;height:76px;background:var(--accent)}}
.icons{{display:grid;grid-template-columns:repeat(auto-fill,minmax(100px,1fr));gap:6px}}
.icons div{{background:var(--card);border-radius:16px;padding:12px 4px 9px;display:grid;justify-items:center;gap:7px;font:400 10.5px/1.2 ui-monospace,monospace;color:var(--text-3);direction:ltr;word-break:break-all;text-align:center}}
.icons svg{{color:var(--text)}}
.comp{{background:var(--card);border-radius:24px;padding:16px;display:grid;gap:12px;min-width:0}}
.comp > small{{font:500 12.5px/1.4 var(--font);color:var(--text-2)}}
.comp .stage{{border-radius:18px;padding:20px 14px;display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:center;overflow:hidden;position:relative;min-height:120px}}
.comp .stage.lt{{{vars_for('light')}background:var(--sfl);color:var(--on)}}
.comp .stage.dk{{{vars_for('dark')}background:#0A0F0F;color:var(--on)}}
.links{{display:grid;gap:3px}}
.links a{{display:flex;align-items:center;gap:12px;background:var(--card);border-radius:6px;padding:13px 16px;color:var(--text);text-decoration:none;font:500 14.5px/1.4 var(--font)}}
.links a:first-child{{border-radius:20px 20px 6px 6px}}.links a:last-child{{border-radius:6px 6px 20px 20px}}
.links a small{{margin-inline-start:auto;color:var(--text-3);font:400 12px/1 ui-monospace,monospace;direction:ltr}}
.links a:hover{{background:var(--sec-c)}}
@media (max-width:760px){{
  .fx-row{{grid-template-columns:minmax(0,1fr)}}
  .fx-stats{{grid-template-columns:repeat(2,minmax(0,1fr))}}
  .dz .g2,.dz .g3,.fx-row.fx-wide .fx-txt,.ty{{grid-template-columns:minmax(0,1fr)}}
  .rad,.shp{{grid-template-columns:repeat(2,minmax(0,1fr))}}
  .fx-cover h1{{font-size:38px}}
  .jn a{{padding:8px;font-size:13px}}
}}
@media (prefers-reduced-motion:reduce){{*{{animation:none!important;transition:none!important}}}}

/* ---------------- device frames: real size, scaled ---------------- */
.ph{{margin:0}}
.device{{width:280px;height:586px;border-radius:44px;padding:8px;background:#101414;box-shadow:0 0 0 1.2px #2A3332 inset,0 24px 40px -26px rgba(10,30,28,.55)}}
.vp{{width:264px;height:570px;border-radius:36px;overflow:hidden;position:relative}}
.scr{{width:390px;height:844px;transform:scale(.67692);transform-origin:top right;position:absolute;top:0;right:0;direction:rtl;
  font-family:var(--font);background:var(--bg);color:var(--on);display:flex;flex-direction:column;overflow:hidden}}
.ldev{{width:586px;height:280px;border-radius:40px;padding:8px;background:#101414;box-shadow:0 0 0 1.2px #2A3332 inset,0 24px 40px -26px rgba(10,30,28,.55)}}
.lvp{{width:570px;height:264px;border-radius:32px;overflow:hidden;position:relative;background:#000}}
.lscr{{width:844px;height:390px;transform:scale(.67536);transform-origin:top right;position:absolute;top:0;right:0;overflow:hidden;font-family:var(--font);color:#fff;direction:rtl}}
.scr{{{vars_for('light')}}}
.scr.dark,.lscr{{{vars_for('dark')}}}
.sbar{{height:48px;flex:none;display:flex;align-items:center;justify-content:space-between;padding:0 28px;font:500 15px/1 var(--font);position:relative;z-index:20}}
.sbar .cam{{position:absolute;top:14px;left:50%;margin-left:-11px;width:22px;height:22px;border-radius:50%;background:#0B0F0F}}
.sbar .st{{display:flex;gap:4px;align-items:center;direction:ltr}}
.gest{{position:absolute;bottom:8px;left:50%;margin-left:-66px;width:132px;height:5px;border-radius:3px;background:currentColor;opacity:.8;z-index:30}}

.f1{{background:linear-gradient(160deg,#20304A,#C96B45 58%,#2B1E2E)}}
.f2{{background:linear-gradient(200deg,#0D3B3A,#2E8C7E 50%,#0B1F24)}}
.f3{{background:linear-gradient(180deg,#EAD9B2,#C99B5B 55%,#6B4A2A)}}
.f4{{background:radial-gradient(circle at 70% 35%,#9DB4FF 0 6px,transparent 7px),linear-gradient(135deg,#1A1F3A,#3A4A8C 50%,#0A0D1C)}}
.f5{{background:linear-gradient(170deg,#F2EEE6,#BFD3D0 60%,#7C9A95)}}
.f6{{background:linear-gradient(150deg,#3B2A20,#B5643C 55%,#F3C47A)}}
.scene{{position:absolute;inset:0;background:radial-gradient(circle at 90% 26%,#FFE0A8 0 24px,#F6A458 25px 54px,transparent 55px),
  linear-gradient(#1C1A33 0%,#5A3B5E 30%,#E07A4F 52%,#283550 53%,#0F1826 100%)}}
.scene::after{{content:"";position:absolute;left:0;right:0;bottom:0;height:34%;background:#0B111C;clip-path:polygon(0 30%,10% 0,18% 40%,18% 100%,0 100%,70% 100%,70% 20%,82% 0,100% 35%,100% 100%)}}

/* ================= app components (M3 Expressive) ================= */
.sa{{--pad:16px}}
.ab{{display:flex;align-items:center;gap:4px;padding:8px 8px 0;flex:none}}
.ab .tt{{flex:1;min-width:0;padding:0 8px}}
.ab .tt b{{display:block;font:500 22px/1.25 var(--font)}}
.ab .tt small{{font:400 13px/1.3 var(--font);color:var(--onv)}}
.ab-lg{{padding:12px 12px 6px 20px;display:flex;align-items:center;gap:12px;flex:none}}
.ab-lg svg.logo{{width:44px;height:44px;color:var(--pr);--logo-play:var(--onpr)}}
.ab-lg b{{font:600 32px/1 var(--font);letter-spacing:-.3px}}
.ab-lg .sp{{flex:1}}
.ib{{width:48px;height:48px;border-radius:24px;display:grid;place-items:center;color:var(--onv);flex:none}}
.srch{{margin:10px var(--pad) 0;height:56px;border-radius:28px;background:var(--sfhh);display:flex;align-items:center;gap:14px;padding:0 6px 0 18px;font:400 16px/1 var(--font);color:var(--onv);flex:none}}
.srch .sp{{flex:1}}
.srch .av{{width:44px;height:44px;border-radius:22px;display:grid;place-items:center}}
.sec{{display:flex;align-items:center;justify-content:space-between;padding:22px 20px 10px;font:500 16px/1.3 var(--font)}}
.sec span{{font:500 14px/1 var(--font);color:var(--pr)}}
.car{{display:flex;gap:8px;padding:0 var(--pad);height:196px;flex:none}}
.car > div{{border-radius:28px;position:relative;overflow:hidden;flex:none}}
.car .big{{width:250px}}.car .mid{{width:62px}}.car .sm{{width:40px}}
.car .cap{{position:absolute;inset:auto 0 0 0;padding:40px 16px 18px;background:linear-gradient(transparent,rgba(0,0,0,.72));color:#fff}}
.car .cap b{{display:block;font:500 16px/1.3 var(--font)}}
.car .cap small{{font:400 12.5px/1 var(--font);opacity:.85}}
.car .pg{{position:absolute;left:16px;right:16px;bottom:9px;height:4px;border-radius:2px;background:rgba(255,255,255,.3);direction:ltr}}
.car .pg i{{display:block;height:100%;border-radius:2px;background:var(--amb)}}
.car .pl{{position:absolute;top:14px;left:14px;width:48px;height:48px;border-radius:16px;background:var(--prc);color:var(--onprc);display:grid;place-items:center}}
.seg{{display:grid;gap:2px;padding:0 var(--pad)}}
.li{{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:16px;align-items:center;padding:12px 16px;background:var(--sfw);border-radius:4px;min-height:72px}}
.scr.dark .li,.lscr .li,.stage.dk .li{{background:var(--sfc)}}
.seg .li:first-child{{border-radius:24px 24px 4px 4px}}.seg .li:last-child{{border-radius:4px 4px 24px 24px}}.seg .li:only-child{{border-radius:24px}}
.li .lead{{width:48px;height:48px;border-radius:16px;background:var(--secc);color:var(--onsecc);display:grid;place-items:center}}
.li b{{display:block;font:500 16px/1.3 var(--font)}}
.li small{{display:block;font:400 13px/1.35 var(--font);color:var(--onv)}}
.li .badge{{min-width:24px;height:24px;border-radius:12px;background:var(--pr);color:var(--onpr);font:500 12px/24px var(--font);text-align:center;padding:0 7px}}
.li .tv{{font:400 14px/1 var(--font);color:var(--onv)}}
.vr{{grid-template-columns:128px minmax(0,1fr) 24px;align-items:start;gap:12px;padding:10px}}
.vr .th{{height:72px;border-radius:12px;position:relative;overflow:hidden}}
.vr b{{font:500 14.5px/1.35 var(--font);direction:ltr;text-align:right;word-break:break-all;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}}
.vr b.ar{{direction:rtl}}
.vr .mt{{display:flex;flex-wrap:wrap;gap:5px;margin-top:6px;font:400 12.5px/1 var(--font);color:var(--onv);align-items:center}}
.vr .mo{{color:var(--onv)}}
.th .du{{position:absolute;bottom:6px;left:6px;background:rgba(0,0,0,.66);color:#fff;font:500 11.5px/1 var(--font);border-radius:6px;padding:4px 6px;direction:ltr}}
.th .pg{{position:absolute;bottom:0;left:0;right:0;height:4px;background:rgba(255,255,255,.35);direction:ltr}}
.th .pg i{{display:block;height:100%;background:var(--amb)}}
.tag{{font:500 10.5px/1 var(--font);background:var(--sfhh);border-radius:6px;padding:4px 6px;color:var(--onv)}}
.tag.hdr{{background:var(--terc);color:var(--onterc)}}
.dot{{width:8px;height:8px;border-radius:50%;background:var(--pr);display:inline-block}}
.seen{{color:var(--pr);display:inline-flex;align-items:center;gap:2px}}
.chips{{display:flex;gap:8px;padding:12px var(--pad) 8px;overflow:hidden;flex:none}}
.chip{{height:32px;padding:0 14px;border-radius:8px;border:1px solid var(--olv);display:inline-flex;align-items:center;gap:6px;font:500 13.5px/1 var(--font);white-space:nowrap;color:var(--onv)}}
.chip.on{{background:var(--secc);border-color:transparent;color:var(--onsecc);padding-inline-start:8px}}
.nb{{margin-top:auto;display:grid;grid-template-columns:repeat(3,1fr);padding:10px 8px 28px;background:var(--sfc);flex:none}}
.nb a{{display:grid;justify-items:center;gap:4px;font:500 12px/1 var(--font);color:var(--onv)}}
.nb a span{{width:56px;height:32px;border-radius:16px;display:grid;place-items:center}}
.nb a.on{{color:var(--on)}}
.nb a.on span{{background:var(--secc);color:var(--onsecc)}}
.btn{{height:56px;border-radius:28px;background:var(--pr);color:var(--onpr);display:inline-flex;align-items:center;justify-content:center;gap:8px;font:500 16px/1 var(--font);padding:0 24px;white-space:nowrap}}
.btn.sq{{border-radius:16px}}
.btn.tonal{{background:var(--secc);color:var(--onsecc)}}
.btn.text{{background:none;color:var(--pr)}}
.btn.out{{background:none;color:var(--onv);border:1px solid var(--olv)}}
.btn.xl{{height:72px;border-radius:36px;font-size:18px;padding:0 32px}}
.fab{{position:absolute;bottom:112px;left:16px;height:80px;padding:0 26px;border-radius:24px;background:var(--prc);color:var(--onprc);display:flex;align-items:center;gap:12px;font:500 17px/1 var(--font);box-shadow:0 8px 18px -8px rgba(0,0,0,.4)}}
.bg{{display:flex;gap:2px;direction:ltr}}
.bg span{{height:48px;min-width:48px;padding:0 14px;background:var(--secc);color:var(--onsecc);display:flex;align-items:center;justify-content:center;gap:6px;font:500 14px/1 var(--font);border-radius:8px;white-space:nowrap}}
.bg span:first-child{{border-radius:24px 8px 8px 24px}}.bg span:last-child{{border-radius:8px 24px 24px 8px}}
.bg span.on{{background:var(--pr);color:var(--onpr);border-radius:24px}}
.bg.full span{{flex:1;padding:0 8px}}
.sl{{position:relative;height:44px;direction:ltr}}
.sl i,.sl em{{position:absolute;height:16px;top:14px}}
.sl i{{left:0;background:var(--pr);border-radius:8px 2px 2px 8px}}
.sl em{{right:0;background:var(--secc);border-radius:2px 8px 8px 2px}}
.sl u{{position:absolute;top:0;width:4px;height:44px;border-radius:2px;background:var(--pr);margin-left:-2px}}
.sl .stop{{position:absolute;right:6px;top:20px;width:4px;height:4px;border-radius:2px;background:var(--pr)}}
.swt{{width:52px;height:32px;border-radius:16px;background:var(--sfhh);border:2px solid var(--ol);position:relative;flex:none;display:block}}
.swt::after{{content:"";position:absolute;top:6px;right:6px;width:16px;height:16px;border-radius:50%;background:var(--ol)}}
.swt.on{{background:var(--pr);border-color:var(--pr)}}
.swt.on::after{{right:auto;left:2px;top:2px;width:24px;height:24px;background:var(--onpr)}}
.swt.on svg{{position:absolute;left:6px;top:6px;color:var(--pr);z-index:1}}
.scrim{{position:absolute;inset:0;background:rgba(0,0,0,.32);z-index:5}}
.sheet{{position:absolute;left:0;right:0;bottom:0;background:var(--sfl);border-radius:28px 28px 0 0;z-index:6;padding:10px 0 34px}}
.sheet .hd{{width:32px;height:4px;border-radius:2px;background:var(--onv);opacity:.4;margin:6px auto 18px}}
.sheet .sh{{display:grid;grid-template-columns:96px minmax(0,1fr);gap:14px;align-items:center;padding:0 22px 16px}}
.sheet .sh .th{{height:54px;border-radius:12px}}
.sheet .sh b{{display:block;font:500 15px/1.35 var(--font);direction:ltr;text-align:right;word-break:break-all}}
.sheet .sh small{{font:400 12.5px/1.4 var(--font);color:var(--onv)}}
.act{{display:flex;align-items:center;gap:16px;padding:0 24px;height:52px;font:400 16px/1 var(--font)}}
.act svg{{color:var(--onv)}}
.act.red,.act.red svg{{color:var(--err)}}
.act small{{margin-inline-start:auto;font:400 13px/1 var(--font);color:var(--onv)}}
.card{{margin:0 var(--pad);background:var(--sfh);border-radius:28px;padding:20px}}
.card h5{{margin:0 0 4px;font:500 18px/1.3 var(--font)}}
.card p{{margin:0;font:400 14px/1.5 var(--font);color:var(--onv)}}
.inp{{margin-top:14px;height:56px;border-radius:16px 16px 4px 4px;background:var(--sfhh);display:flex;align-items:center;padding:0 16px;gap:12px;font:400 15px/1 var(--font);color:var(--onv);direction:ltr;border-bottom:2px solid var(--pr)}}
.ok{{font:500 12.5px/1 var(--font);color:var(--pr);display:inline-flex;align-items:center;gap:6px}}
.ok::before{{content:"";width:8px;height:8px;border-radius:50%;background:currentColor}}
.gh{{padding:22px 32px 8px;font:500 14px/1 var(--font);color:var(--pr)}}
.ldi{{width:48px;height:48px;background:var(--pr);clip-path:url(#c-sunny8);flex:none}}

/* ================= player ================= */
.pl-top{{position:absolute;top:0;left:0;right:0;height:120px;background:linear-gradient(rgba(0,0,0,.62),transparent);z-index:2}}
.pl-bot{{position:absolute;bottom:0;left:0;right:0;height:180px;background:linear-gradient(transparent,rgba(0,0,0,.72));z-index:2}}
.ptop{{position:absolute;top:16px;left:24px;right:20px;display:flex;align-items:center;gap:4px;z-index:3}}
.ptop .tt{{flex:1;min-width:0;padding:0 6px}}
.ptop .tt b{{display:block;font:500 17px/1.3 var(--font);direction:ltr;text-align:right;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}}
.ptop .tt small{{font:400 12.5px/1.3 var(--font);color:rgba(255,255,255,.72)}}
.pib{{width:48px;height:48px;border-radius:24px;display:grid;place-items:center;color:#fff;flex:none;position:relative}}
.pmid{{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);display:flex;align-items:center;gap:6px;direction:ltr;z-index:3}}
.pmid .tb{{width:64px;height:64px;border-radius:32px;background:rgba(255,255,255,.14);backdrop-filter:blur(12px);display:grid;place-items:center;color:#fff}}
.pmid .tb.s{{width:56px;height:56px}}
.pmid .play{{width:104px;height:80px;border-radius:24px;background:var(--prc);color:var(--onprc);display:grid;place-items:center}}
.pmid .play.round{{width:80px;border-radius:40px}}
.pbot{{position:absolute;left:24px;right:24px;bottom:14px;z-index:3}}
.tline{{display:flex;align-items:center;gap:12px;direction:ltr;font:500 13px/1 var(--font)}}
.tline .num{{min-width:56px}}
.wv{{display:block;overflow:visible;flex:none}}
.ftb{{display:flex;align-items:center;gap:2px;height:56px;padding:0 6px;border-radius:28px;background:rgba(26,33,32,.82);backdrop-filter:blur(14px);width:max-content}}
.ftb .pib{{width:44px;height:44px}}
.ftb .vd{{width:1px;height:24px;background:rgba(255,255,255,.18);margin:0 4px}}
.pchip{{height:40px;padding:0 14px;border-radius:20px;display:inline-flex;align-items:center;gap:6px;font:500 13.5px/1 var(--font);direction:ltr;color:#fff}}
.pchip.acc{{background:var(--pr);color:var(--onpr)}}
.prow2{{display:flex;justify-content:space-between;align-items:center;margin-top:6px}}
.sub{{position:absolute;left:0;right:0;bottom:96px;text-align:center;z-index:2;font:500 25px/1.4 var(--font);color:#fff;text-shadow:0 0 3px #000,0 0 3px #000,0 2px 4px #000}}
.toast{{position:absolute;z-index:4;background:var(--inv);color:var(--oninv);border-radius:16px;display:flex;align-items:center;gap:14px;padding:8px 8px 8px 16px;font:400 15px/1.3 var(--font)}}
.toast .tbtn{{height:40px;border-radius:20px;padding:0 14px;font:500 14px/40px var(--font);color:{L['primary']}}}
.vpill{{position:absolute;top:50%;transform:translateY(-50%);z-index:4;width:68px;height:232px;border-radius:34px;background:rgba(26,33,32,.8);backdrop-filter:blur(10px);overflow:hidden;display:flex;flex-direction:column;align-items:center;justify-content:flex-end}}
.vpill i{{position:absolute;left:6px;right:6px;bottom:6px;background:var(--pr);border-radius:28px}}
.vpill svg{{position:relative;margin-bottom:22px;color:var(--onpr)}}
.vpill b{{position:absolute;top:18px;font:500 15px/1 var(--font);color:#fff;direction:ltr}}
.seekpv{{position:absolute;top:24px;left:50%;transform:translateX(-50%);z-index:4;display:grid;justify-items:center;gap:8px}}
.seekpv .th{{width:208px;height:117px;border-radius:20px;outline:3px solid #fff}}
.seekpv b{{font:600 24px/1 var(--font);direction:ltr}}
.seekpv small{{font:400 13px/1 var(--font);color:rgba(255,255,255,.75);direction:ltr}}
.ripple{{position:absolute;top:0;bottom:0;width:260px;z-index:3;display:grid;place-items:center;background:radial-gradient(ellipse at 100% 50%,rgba(255,255,255,.18),transparent 70%)}}
.ripple b{{font:600 15px/1 var(--font);display:flex;gap:4px;align-items:center;direction:ltr}}
.panel{{position:absolute;top:8px;bottom:8px;right:8px;width:372px;background:var(--sfl);z-index:5;padding:14px 18px;border-radius:28px;display:flex;flex-direction:column;gap:8px;color:var(--on)}}
.panel h5{{margin:0 0 2px;font:500 19px/1.3 var(--font);display:flex;align-items:center;gap:10px}}
.panel h5 .pib{{margin-inline-start:auto;width:40px;height:40px;color:var(--onv)}}
.panel .lb{{font:500 12.5px/1 var(--font);color:var(--onv);margin-top:4px}}
.panel .seg{{padding:0}}
.panel .li{{min-height:46px;padding:8px 14px;gap:12px;background:var(--sfc)}}
.panel .li.on{{background:var(--secc);color:var(--onsecc)}}
.panel .li.on small{{color:var(--onsecc)}}
.panel .li b{{font-size:14.5px}}
.panel .li small{{direction:ltr;font-size:12px}}
.panel .bg span{{height:40px;font-size:13.5px}}
.tiles{{display:grid;grid-template-columns:repeat(3,1fr);gap:6px}}
.tiles div{{border-radius:20px;background:var(--sfc);padding:12px;display:grid;gap:6px;font:500 13px/1.25 var(--font)}}
.tiles div small{{font:400 11.5px/1 var(--font);color:var(--onv)}}
.tiles div.on{{background:var(--terc);color:var(--onterc)}}
.tiles div.on small{{color:var(--onterc)}}
.nextc{{position:absolute;bottom:24px;left:24px;z-index:4;width:340px;background:var(--sfh);color:var(--on);border-radius:28px;padding:12px;display:grid;grid-template-columns:132px minmax(0,1fr);gap:12px}}
.nextc .th{{height:74px;border-radius:16px}}
.nextc small{{font:500 12.5px/1 var(--font);color:var(--pr)}}
.nextc b{{display:block;margin-top:5px;font:500 14px/1.35 var(--font);direction:ltr;text-align:right;word-break:break-all}}
.nextc .r{{grid-column:1/-1;display:flex;gap:8px}}
.nextc .r .btn{{flex:1;height:44px;font-size:14.5px}}
.lockb{{position:absolute;top:20px;right:22px;z-index:4;width:64px;height:64px;display:grid;place-items:center;color:var(--onprc)}}
.lockb::before{{content:"";position:absolute;inset:0;background:var(--prc);clip-path:url(#c-cookie9)}}
.lockb svg{{position:relative}}
.lockb::after{{content:"";position:absolute;inset:-7px;border-radius:50%;background:conic-gradient(var(--pr) 0 45%,transparent 45%);-webkit-mask:radial-gradient(circle,transparent 35px,#000 36px);mask:radial-gradient(circle,transparent 35px,#000 36px)}}
/* themes */
.tsw{{width:62px;height:62px;border-radius:50%;position:relative;display:block;margin:0 auto}}
.tsw.on{{outline:3px solid var(--on);outline-offset:3px}}
.tsw.on::after{{content:"";position:absolute;inset:19px;border-radius:50%;background:var(--on)}}
.tsw.on svg{{position:absolute;inset:22px;width:18px!important;height:18px!important;color:var(--bg);z-index:1}}
.tgrid{{display:grid;grid-template-columns:repeat(4,1fr);gap:16px 0;padding:6px 16px}}
.tgrid small{{display:block;text-align:center;font:400 12px/1 var(--font);color:var(--onv);margin-top:8px}}
.prev{{margin:6px 16px 0;height:132px;border-radius:28px;background:var(--sfh);display:grid;grid-template-columns:1fr 1fr;gap:10px;padding:14px;flex:none}}
.prev div{{border-radius:18px}}
/* vault */
.vbanner{{margin:8px 16px 0;border-radius:20px;background:var(--terc);color:var(--onterc);padding:12px 16px;display:flex;gap:12px;align-items:center;font:400 13px/1.45 var(--font);flex:none}}
.vbanner b{{display:block;font:500 14.5px/1.3 var(--font)}}
.vgrid{{display:grid;grid-template-columns:repeat(3,1fr);gap:4px;padding:8px 16px}}
.vgrid div{{aspect-ratio:1;border-radius:6px;position:relative;overflow:hidden}}
.vgrid div:nth-child(1){{border-radius:20px 6px 6px 6px}}.vgrid div:nth-child(3){{border-radius:6px 20px 6px 6px}}
.vgrid .du{{position:absolute;bottom:5px;left:5px;background:rgba(0,0,0,.6);color:#fff;font:500 11px/1 var(--font);border-radius:5px;padding:3px 5px;direction:ltr}}
.vgrid .ph{{position:absolute;top:6px;right:6px;color:#fff;opacity:.9}}
/* subtitles */
.res .li{{grid-template-columns:auto minmax(0,1fr) auto;min-height:56px}}
.res b{{direction:ltr;text-align:right;font-size:14px!important;word-break:break-all}}
.res .meta{{display:flex;gap:6px;align-items:center;margin-top:4px;font:400 11.5px/1 var(--font);color:var(--onv)}}
.ptag{{font:500 11px/1 var(--font);border-radius:6px;padding:4px 6px;background:var(--sfhh);color:var(--onv)}}
.ptag.hash{{background:var(--terc);color:var(--onterc)}}
.tl{{position:absolute;left:24px;right:24px;direction:ltr;z-index:3}}
.tl .wave{{height:70px;border-radius:16px;background:rgba(255,255,255,.06);overflow:hidden}}
.tl .subs{{position:relative;height:44px;margin-top:6px}}
.tl .subs i{{position:absolute;top:0;height:44px;border-radius:10px;background:var(--secc);color:var(--onsecc);font:500 11px/44px var(--font);font-style:normal;padding:0 8px;white-space:nowrap;overflow:hidden}}
.tl .subs i.on{{background:var(--pr);color:var(--onpr)}}
.tl .ph{{position:absolute;top:-6px;bottom:-6px;width:3px;border-radius:2px;background:var(--amb)}}
/* motion demos in the doc (the app does this in Compose) */
@keyframes wv{{to{{transform:translateX(-18px)}}}}
@keyframes spin{{to{{transform:rotate(360deg)}}}}
.wvg{{animation:wv 1.1s linear infinite}}
.ldi{{animation:spin 2.4s linear infinite}}
.pmid .play,.btn,.bg span,.chip,.li{{transition:border-radius .35s cubic-bezier(.42,1.67,.21,.90),transform .35s cubic-bezier(.42,1.67,.21,.90),background-color .2s}}
.comp .play:hover,.comp .play:focus-visible{{border-radius:40px}}
.comp .btn:active,.comp .bg span:active{{transform:scale(.94);border-radius:12px}}
.comp .bg span:hover{{border-radius:24px}}
.zone{{position:absolute;z-index:3;border:2px dashed rgba(255,255,255,.6);border-radius:24px;display:grid;place-items:center;text-align:center;font:500 15px/1.5 var(--font);background:rgba(0,0,0,.35)}}
.home{{flex:1;position:relative;background:linear-gradient(170deg,#204A47,#0F2523 60%,#09110F);color:#fff}}
.home .apps{{display:grid;grid-template-columns:repeat(4,1fr);gap:26px 0;padding:60px 26px 0;justify-items:center}}
.home .apps span{{width:58px;height:58px;border-radius:50%;background:rgba(255,255,255,.16)}}
.home .apps span:nth-child(3n){{clip-path:url(#c-cookie9);border-radius:0}}
.pipw{{position:absolute;left:16px;bottom:236px;width:224px;height:126px;border-radius:20px;overflow:hidden;box-shadow:0 14px 30px -10px rgba(0,0,0,.7)}}
.pipw .ctl{{position:absolute;inset:0;background:rgba(0,0,0,.35);display:flex;align-items:center;justify-content:center;gap:20px;direction:ltr}}
.notif{{position:absolute;left:10px;right:10px;bottom:36px;background:var(--sfh);color:var(--on);border-radius:28px;padding:16px;display:grid;grid-template-columns:56px minmax(0,1fr);gap:12px;align-items:center}}
.notif .th{{height:56px;border-radius:14px}}
.notif b{{font:500 14px/1.3 var(--font);direction:ltr;text-align:right;display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}}
.notif small{{font:400 12px/1 var(--font);color:var(--onv)}}
.notif .wrow{{grid-column:1/-1;display:flex;align-items:center;gap:10px;direction:ltr}}
.notif .pp{{width:56px;height:44px;border-radius:14px;background:var(--pr);color:var(--onpr);display:grid;place-items:center;flex:none}}
"""

# ---------------------------------------------------------------- screen helpers
SBAR = (f'<div class="sbar"><span class="num">9:41</span><span class="cam"></span>'
        f'<span class="st">{I("lan-fill", 16)}<svg width="24" height="12" viewBox="0 0 24 12"><rect x=".75" y=".75" width="20" height="10.5" rx="3" fill="none" stroke="currentColor" stroke-width="1.5"/><rect x="2.5" y="2.5" width="13" height="7" rx="1.5" fill="currentColor"/></svg></span></div>')


def phone(body, dark=False):
    return (f'<figure class="ph sa"><div class="device"><div class="vp"><div class="scr{" dark" if dark else ""}">'
            f'{SBAR}{body}<div class="gest"></div></div></div></div></figure>')


def land(body):
    return f'<figure class="ph sa"><div class="ldev"><div class="lvp"><div class="lscr"><div class="scene"></div>{body}</div></div></div></figure>'


def th(f, h, dur=None, prog=None):
    s = f'<div class="th {f}" style="height:{h}px">'
    if dur: s += f'<span class="du num">{dur}</span>'
    if prog is not None: s += f'<div class="pg"><i style="width:{prog}%"></i></div>'
    return s + "</div>"


def nbar(on):
    items = [("video_library", "المكتبة"), ("playlist_play", "القوائم"), ("lan", "الشبكة")]
    return '<div class="nb">' + "".join(
        f'<a class="{"on" if n == on else ""}"><span>{I(ic + ("-fill" if n == on else ""))}</span>{n}</a>' for ic, n in items) + "</div>"


def seek(w, frac, wavy=True, buf=None, ab=None, chapters=(0.12, 0.31, 0.55, 0.78)):
    """M3E wavy linear progress: wavy active part, gap, flat inactive track, stop dot. Always LTR (ق-04)."""
    x, h = w * frac, 24
    s = [f'<svg class="wv" width="{w}" height="{h}" viewBox="0 0 {w} {h}">',
         f'<path d="M{x + 10:.1f},{h / 2}H{w - 2}" stroke="rgba(255,255,255,.28)" stroke-width="4" stroke-linecap="round"/>']
    if buf: s.append(f'<path d="M{x + 10:.1f},{h / 2}H{w * buf:.1f}" stroke="rgba(255,255,255,.5)" stroke-width="4" stroke-linecap="round"/>')
    for c in chapters:
        if c * w > x + 12: s.append(f'<rect x="{c * w - 2:.1f}" y="{h / 2 - 3}" width="4" height="6" fill="#000" opacity=".55"/>')
    if ab:
        a, b = ab
        s.append(f'<rect x="{a * w:.1f}" y="{h / 2 - 2}" width="{(b - a) * w:.1f}" height="4" fill="var(--amb)" opacity=".7"/>'
                 f'<rect x="{a * w - 2:.1f}" y="2" width="4" height="{h - 4}" rx="2" fill="var(--amb)"/><rect x="{b * w - 2:.1f}" y="2" width="4" height="{h - 4}" rx="2" fill="var(--amb)"/>')
    if wavy:  # wave drawn one wavelength longer and slid left inside a clip, so it travels while playing
        cid = f"wc{next(_WC)}"
        s.append(f'<clipPath id="{cid}"><rect x="0" y="0" width="{x - 4:.1f}" height="{h}"/></clipPath>'
                 f'<g clip-path="url(#{cid})"><path class="wvg" d="{wave(w, h, x1=x + 18)}" fill="none" stroke="var(--pr)" stroke-width="4" stroke-linecap="round"/></g>')
    else:
        s.append(f'<path d="M2,{h / 2}H{x - 6:.1f}" fill="none" stroke="var(--pr)" stroke-width="4" stroke-linecap="round"/>')
    s.append(''
             f'<rect x="{x - 2:.1f}" y="0" width="4" height="{h}" rx="2" fill="var(--pr)"/>'
             f'<circle cx="{w - 4}" cy="{h / 2}" r="2" fill="var(--pr)"/></svg>')
    return "".join(s)


TITLE = "Interstellar.2014.1080p.BluRay.x265.mkv"
SUBT = "Movies · HEVC 10-bit · EAC3 5.1"


def ptop(title=TITLE, sub=SUBT, icons=("subtitles", "music_note", "more_vert")):
    return (f'<div class="pl-top"></div><div class="ptop"><span class="pib">{I("arrow_back", 24, "flip")}</span><span class="tt"><b>{title}</b><small>{sub}</small></span>'
            + "".join(f'<span class="pib">{I(x)}</span>' for x in icons) + "</div>")


def pbot(frac=.24, w=644, tools=True, t0="41:07", t1="-2:07:56"):
    t = f'<div class="tline"><span class="num">{t0}</span>{seek(w, frac, buf=frac + .14)}<span class="num" style="text-align:right">{t1}</span></div>'
    if tools:
        t += (f'<div class="prow2"><div class="ftb">{"".join(f"<span class=pib>{I(x)}</span>" for x in ("lock", "screen_rotation", "fit_screen", "picture_in_picture_alt"))}</div>'
              f'<div class="ftb"><span class="pchip">{I("subtitles", 18)}عربي</span><span class="pchip">{I("speed", 18)}1.0×</span>'
              f'<span class="pchip acc">{I("memory", 18)}HW+</span></div></div>')
    return f'<div class="pl-bot"></div><div class="pbot">{t}</div>'


PMID = (f'<div class="pmid"><span class="tb s">{I("skip_previous-fill", 26)}</span><span class="tb">{I("replay_10", 30)}</span>'
        f'<span class="play">{I("pause", 40)}</span><span class="tb">{I("forward_10", 30)}</span><span class="tb s">{I("skip_next-fill", 26)}</span></div>')

FOLDERS = [("Camera", "214 فيديو · 18.4 GB", "3"), ("Telegram Video", "96 فيديو · 7.1 GB", "5"),
           ("Movies", "23 فيديو · 61.2 GB", ""), ("WhatsApp Video", "412 فيديو · 4.9 GB", "12")]
MOVIES = [
    ("f3", "Dune.Part.Two.2024.2160p.HDR.x265.mkv", "2:46:10", None, '<span class="tag">4K</span><span class="tag hdr">HDR</span>', "18.9 GB", True, False),
    ("f4", TITLE, "2:49:03", 24, '<span class="tag">1080p</span><span class="tag">ترجمة</span>', "4.2 GB", False, False),
    ("f2", "رحلة إلى سواكن · الحلقة 3.mp4", "48:20", None, '<span class="tag">720p</span>', "612 MB", False, True),
    ("f6", "Spirited.Away.2001.1080p.mkv", "2:04:51", None, '<span class="tag">1080p</span><span class="tag">ترجمة</span>', "3.1 GB", True, False),
    ("f1", "The.Good.Place.S02E05.720p.mkv", "22:14", 71, '<span class="tag">720p</span>', "402 MB", False, False),
]


def vrow(f, name, dur, prog, tags, size, new, seen, q=None):
    nm = name.replace(q, f'<mark style="background:var(--prc);color:var(--onprc);border-radius:4px">{q}</mark>') if q else name
    ar = "ar" if any("؀" <= ch <= "ۿ" for ch in name) else ""
    extra = (' <span class="dot"></span>' if new else "") + (f' <span class="seen">{I("check", 16)}شوهد</span>' if seen else "")
    return (f'<div class="li vr">{th(f, 72, dur, prog)}<span><b class="{ar}">{nm}</b>'
            f'<span class="mt">{tags}<span class="num">{size}</span>{extra}</span></span><span class="mo">{I("more_vert", 22)}</span></div>')


def folder_list(rows=FOLDERS):
    return '<div class="seg">' + "".join(
        f'<div class="li"><span class="lead">{I("folder-fill")}</span><span><b>{a}</b><small class="num">{b}</small></span>'
        f'{f"<span class=badge>{c}</span>" if c else "<span></span>"}</div>' for a, b, c in rows) + "</div>"


# ---------------------------------------------------------------- screens
S = {}

S["ش-01"] = phone(
    '<div style="flex:1;display:flex;flex-direction:column;padding:6px 24px 30px">'
    '<div style="position:relative;height:330px;margin-top:10px">'
    '<div class="f4" style="position:absolute;top:10px;right:0;width:190px;height:190px;clip-path:url(#c-cookie12)"></div>'
    '<div class="f3" style="position:absolute;top:150px;left:0;width:170px;height:170px;clip-path:url(#c-clover4)"></div>'
    '<div class="f2" style="position:absolute;top:196px;right:40px;width:124px;height:124px;border-radius:50%"></div>'
    '<div class="f6" style="position:absolute;top:24px;left:22px;width:118px;height:118px;border-radius:36px"></div>'
    '</div>'
    f'<div style="display:flex;align-items:center;gap:12px"><svg class="logo" style="width:52px;height:52px;color:var(--pr);--logo-play:var(--onpr)"><use href="#logo"/></svg>'
    '<b style="font:600 34px/1 var(--font);letter-spacing:-.3px">Anon</b></div>'
    '<h2 style="margin:16px 0 0;font:500 29px/1.3 var(--font)">كل فيديوهاتك.<br>بلا إعلانات، بلا حساب.</h2>'
    '<p style="margin:10px 0 0;font:400 15.5px/1.7 var(--font);color:var(--onv)">MKV وHEVC و4K HDR وترجمة ASS تُعرض كما صُنعت. لا شيء يغادر جهازك.</p>'
    '<div style="margin-top:auto;display:grid;gap:6px"><span class="btn xl">السماح بالوصول إلى الفيديو</span>'
    '<span class="btn text">افتح ملفاً واحداً فقط</span></div></div>')

S["ش-02"] = phone(
    f'<div class="ab-lg">{LOGO}<b>Anon</b><span class="sp"></span><span class="ib">{I("settings")}</span></div>'
    f'<div class="srch">{I("search")}<span>ابحث في الفيديو</span><span class="sp"></span><span class="av">{I("history")}</span></div>'
    '<div class="sec">تابع المشاهدة<span>الكل</span></div>'
    '<div class="car">'
    f'<div class="big f4"><span class="pl">{I("play_arrow-fill", 28)}</span><div class="cap"><b>Interstellar</b><small class="num">بقي 2:07:56</small></div><div class="pg"><i style="width:24%"></i></div></div>'
    '<div class="mid f1"></div><div class="sm f6"></div></div>'
    f'<div class="sec">المجلدات<span>{I("sort", 20)}</span></div>' + folder_list() + nbar("المكتبة"))

S["ش-03"] = phone(
    f'<div class="ab"><span class="ib">{I("arrow_back", 24, "flip")}</span><span class="tt"><b>Movies</b><small class="num">23 فيديو · 61.2 GB</small></span>'
    f'<span class="ib">{I("search")}</span><span class="ib">{I("grid_view")}</span></div>'
    f'<div class="chips"><span class="chip on">{I("check", 18)}الكل</span><span class="chip">لم يُشاهد</span><span class="chip">4K</span><span class="chip">مع ترجمة</span></div>'
    '<div class="seg">' + "".join(vrow(*m) for m in MOVIES) + "</div>", dark=True)

S["ش-04"] = phone(
    f'<div class="srch">{I("arrow_back", 24, "flip")}<span style="direction:ltr;color:var(--on)">inter</span>'
    f'<span style="width:2px;height:24px;background:var(--pr);margin-inline-start:-10px"></span><span class="sp"></span><span class="av">{I("close")}</span></div>'
    f'<div class="chips"><span class="chip on">{I("check", 18)}كل المجلدات</span><span class="chip">Movies</span><span class="chip">Telegram Video</span></div>'
    '<div class="sec" style="padding-top:12px">النتائج · 2</div><div class="seg">'
    + vrow("f4", TITLE, "2:49:03", 24, '<span class="tag">1080p</span>', "Movies", False, False, q="Inter")
    + vrow("f5", "Interview_final_v2.mp4", "14:05", None, '<span class="tag">1080p</span>', "Download", False, False, q="Inter")
    + '</div><div class="sec">بحثت مؤخراً</div><div class="seg">'
    + "".join(f'<div class="li" style="min-height:56px"><span style="color:var(--onv)">{I("history")}</span><b>{t}</b><span style="color:var(--onv)">{I("arrow_back", 20)}</span></div>' for t in ("سواكن", "S02E05", "عرس"))
    + "</div>")

S["ش-05"] = phone(
    f'<div class="ab"><span class="ib">{I("arrow_back", 24, "flip")}</span><span class="tt"><b>Movies</b><small class="num">23 فيديو · 61.2 GB</small></span></div>'
    '<div class="seg" style="margin-top:12px">' + "".join(vrow(*m) for m in MOVIES[:4]) + "</div>"
    '<div class="scrim"></div><div class="sheet"><div class="hd"></div>'
    f'<div class="sh">{th("f4", 54)}<span><b>{TITLE}</b><small class="num">2:49:03 · 1080p · HEVC · 4.2 GB</small></span></div>'
    f'<div style="padding:0 16px 10px"><div class="bg full">'
    f'<span class="on">{I("play_arrow-fill", 20)}تابع</span><span>{I("restart_alt", 20)}من البداية</span><span>{I("music_note", 20)}صوت</span></div></div>'
    + "".join(f'<div class="act{" red" if r else ""}">{I(ic)}{t}{f"<small>{s}</small>" if s else ""}</div>' for ic, t, s, r in [
        ("info", "معلومات الملف", "", False), ("edit", "إعادة تسمية", "", False), ("share", "مشاركة", "", False),
        ("shield_lock", "نقل إلى الخزنة", "", False), ("delete", "نقل إلى السلة", "30 يوماً", True)])
    + "</div>")

S["ش-06"] = phone(
    f'<div class="ab"><span class="tt"><b style="font-size:28px">الشبكة</b></span><span class="ib">{I("settings")}</span></div>'
    '<div style="height:14px"></div>'
    f'<div class="card"><h5>شغّل من رابط</h5><p>HTTP وHLS وRTSP وقوائم m3u.</p><div class="inp">{I("link", 22)}https:// أو rtsp://</div></div>'
    '<div class="sec">الخوادم المحفوظة<span>تعديل</span></div><div class="seg">'
    f'<div class="li"><span class="lead">{I("lan-fill")}</span><span><b>NAS البيت</b><small class="num">SMB · 192.168.1.20</small></span><span class="ok">متصل</span></div>'
    f'<div class="li"><span class="lead">{I("lan")}</span><span><b>ملفات العمل</b><small>WebDAV · dav.example.com</small></span><span class="tv">أمس</span></div>'
    f'<div class="li"><span class="lead">{I("cast")}</span><span><b>Mi Box</b><small>DLNA · اكتُشف تلقائياً</small></span><span class="ok">متاح</span></div></div>'
    f'<div class="fab">{I("add", 28)}أضف خادماً</div>' + nbar("الشبكة"))

S["ش-07"] = land(f'<div class="toast" style="bottom:28px;left:28px"><div class="ldi" style="width:28px;height:28px;background:{L["primary"]}"></div>'
                 f'<span>تابع من <b class="num">41:07</b></span><span class="tbtn">من البداية</span></div>')
S["ش-08"] = land(ptop() + PMID + pbot())
S["ش-09"] = land(
    f'<div class="vpill" style="left:30px"><b>70%</b><i style="height:62%"></i>{I("light_mode", 26)}</div>'
    '<div class="seekpv"><div class="th f2"></div><b class="num">+00:45</b><small class="num">41:52 / 2:49:03</small></div>'
    f'<div class="ripple" style="right:0"><b>{I("forward_10", 22)}+20</b></div>'
    + pbot(.25, tools=False, t0="41:52", t1="-2:07:11"))
S["ش-10"] = land(
    '<div class="sub" style="right:388px">لن نفشل في البقاء.<br>سنتجاوز هذا كما فعلنا دائماً.</div>'
    '<div class="panel">'
    f'<h5>{I("subtitles")}الترجمة<span class="pib">{I("close", 22)}</span></h5>'
    f'<div class="seg"><div class="li on">{I("check", 20)}<b>عربي</b><small>Interstellar.ar.srt</small></div>'
    '<div class="li"><span style="width:20px"></span><b>English SDH</b><small>ASS · مدمجة</small></div>'
    '<div class="li"><span style="width:20px"></span><b>بلا ترجمة</b><span></span></div></div>'
    f'<div class="bg full"><span>{I("folder_open", 18)}من الجهاز</span><span>{I("download", 18)}بحث أونلاين</span></div>'
    '<div class="lb">التأخير · <span class="num">+0.3 ث</span></div><div class="bg full"><span>−0.5</span><span>−0.1</span><span class="on">+0.3</span><span>+0.1</span><span>+0.5</span></div>'
    '</div>')
S["ش-11"] = land(
    '<div class="panel">'
    f'<h5>{I("music_note")}الصوت والسرعة<span class="pib">{I("close", 22)}</span></h5>'
    '<div class="lb">السرعة · <span class="num">1.25×</span></div>'
    '<div class="bg full"><span>0.5</span><span>0.75</span><span>1</span><span class="on">1.25</span><span>1.5</span><span>2</span></div>'
    '<div class="sl"><i style="width:calc(27% - 6px)"></i><u style="left:27%"></u><em style="width:calc(73% - 6px)"></em><span class="stop"></span></div>'
    '<div class="lb">مسار الصوت</div>'
    f'<div class="seg"><div class="li on">{I("check", 20)}<b>English</b><small>EAC3 5.1</small></div>'
    '<div class="li"><span style="width:20px"></span><b>العربية</b><small>AAC 2.0</small></div></div>'
    '<div class="lb">تضخيم · <span class="num">150%</span></div>'
    '<div class="sl"><i style="width:calc(50% - 6px)"></i><u style="left:50%"></u><em style="width:calc(50% - 6px)"></em><span class="stop"></span></div>'
    '</div>')
S["ش-12"] = land(
    '<div class="panel">'
    f'<h5>{I("tune")}خيارات التشغيل<span class="pib">{I("close", 22)}</span></h5>'
    '<div class="lb">فك الترميز</div>'
    f'<div class="bg full"><span>HW</span><span class="on">{I("check", 18)}HW+</span><span>SW</span></div>'
    '<div class="tiles" style="margin-top:4px">'
    f'<div class="on">{I("repeat")}تكرار A-B<small class="num">20:12 ← 27:40</small></div>'
    f'<div>{I("bedtime")}مؤقت النوم<small>إيقاف</small></div>'
    f'<div>{I("fit_screen")}نسبة العرض<small>ملائم</small></div>'
    f'<div class="on">{I("headphones")}في الخلفية<small>يعمل</small></div>'
    f'<div>{I("equalizer")}المعادل<small>مسطّح</small></div>'
    f'<div>{I("photo_camera")}لقطة<small>PNG</small></div>'
    '</div></div>'
    f'<div class="pl-bot"></div><div class="pbot" style="right:400px"><div class="tline"><span class="num">22:40</span>{seek(340, .13, ab=(.2, .3))}</div></div>')
S["ش-13"] = land(
    f'<div class="lockb">{I("lock-fill", 28)}</div>'
    '<div class="toast" style="top:30px;right:104px;padding:12px 18px">مقفل. اضغط مطوّلاً على القفل لفتحه</div>')
S["ش-14"] = land(
    '<div style="position:absolute;inset:0;background:rgba(0,0,0,.5);z-index:1"></div>'
    + ptop("The.Good.Place.S02E05.720p.mkv", "Telegram Video · انتهى", ("more_vert",))
    + f'<div class="pmid"><span class="play round">{I("restart_alt", 38)}</span></div>'
    '<div class="nextc"><div class="th f6"></div><span><small>التالي خلال <span class="num">5</span></small>'
    '<b>The.Good.Place.S02E06.720p.mkv</b></span>'
    f'<div class="r"><span class="btn">{I("play_arrow-fill", 20)}شغّل الآن</span><span class="btn tonal">إلغاء</span></div></div>')

S["ش-15"] = phone(
    '<div class="home"><div class="apps">' + "<span></span>" * 12 + '</div>'
    f'<div class="pipw"><div class="scene"></div><div class="ctl">{I("replay_10", 26)}{I("pause", 30)}{I("forward_10", 26)}</div></div>'
    f'<div class="notif"><div class="th f4"></div><span><b>{TITLE}</b><small>Anon · يعمل في الخلفية</small></span>'
    f'<div class="wrow"><span class="pp">{I("pause", 26)}</span>{seek(214, .24)}{I("skip_next-fill", 26)}</div></div></div>', dark=True)
S["ش-16"] = phone(
    '<div style="flex:1;display:flex;flex-direction:column;align-items:center;padding:80px 30px 40px;text-align:center">'
    f'<span style="width:104px;height:104px;background:var(--prc);color:var(--onprc);display:grid;place-items:center;clip-path:url(#c-cookie9)">{I("shield_lock-fill", 46)}</span>'
    '<h2 style="margin:26px 0 0;font:500 28px/1.3 var(--font)">الخزنة</h2>'
    '<p style="margin:8px 0 0;font:400 15px/1.7 var(--font);color:var(--onv)">ما تنقله هنا لا يظهر في المكتبة ولا في البحث ولا في «تابع المشاهدة».</p>'
    f'<span style="margin-top:auto;width:88px;height:88px;border-radius:50%;background:var(--sfhh);color:var(--pr);display:grid;place-items:center">{I("fingerprint", 46)}</span>'
    '<span style="margin-top:14px;font:400 14px/1 var(--font);color:var(--onv)">المس مستشعر البصمة</span>'
    '<span class="btn text" style="margin-top:16px">استخدم الرمز</span></div>', dark=True)


def setrow(ic, t, s, tail):
    return f'<div class="li" style="min-height:64px"><span style="color:var(--onv)">{I(ic)}</span><span><b>{t}</b>{f"<small>{s}</small>" if s else ""}</span>{tail or "<span></span>"}</div>'


ON = f'<span class="swt on">{I("check", 16)}</span>'
S["ش-17"] = phone(
    f'<div class="ab"><span class="ib">{I("arrow_back", 24, "flip")}</span><span class="tt"><b style="font-size:26px">الإعدادات</b></span></div>'
    '<div style="flex:1;overflow:hidden">'
    '<div class="gh">المشغّل</div><div class="seg">'
    + setrow("memory", "فك الترميز", "HW+ ثم SW عند الفشل", '<span class="tv">HW+</span>')
    + setrow("history", "استئناف تلقائي", "من حيث توقفت، لكل ملف", ON)
    + setrow("skip_next", "التالي في المجلد", "", ON) + '</div>'
    '<div class="gh">الإيماءات والترجمة</div><div class="seg">'
    + setrow("swipe", "الإيماءات", "سطوع · صوت · تقديم · تكبير", '<span class="tv">5</span>')
    + setrow("closed_caption", "شكل الترجمة", "الخط والحجم والحدّ", "") + '</div>'
    '<div class="gh">المظهر والخصوصية</div><div class="seg">'
    + setrow("palette", "ألوان من الخلفية", "Material You", ON)
    + setrow("visibility_off", "سجل المشاهدة", "على الجهاز فقط", ON) + "</div></div>")


def tsw(name, mode="light", on=False, size=None):
    c = THEMES["presets"][name][mode]
    st = f"width:{size}px;height:{size}px;" if size else ""
    return (f'<span class="tsw{" on" if on else ""}" style="{st}background:linear-gradient({c["p"]} 0 50%,transparent 50%),'
            f'linear-gradient(90deg,{c["sc"]} 0 50%,{c["tc"]} 50%)">{I("check", 18) if on else ""}</span>')


S["ش-18"] = phone(
    f'<div class="ab"><span class="ib">{I("arrow_back", 24, "flip")}</span><span class="tt"><b style="font-size:26px">المظهر</b></span></div>'
    '<div class="prev"><div style="background:var(--pr)"></div><div style="display:grid;gap:10px"><div style="background:var(--secc)"></div><div style="background:var(--terc)"></div></div></div>'
    f'<div class="gh">مصدر الألوان</div><div style="padding:0 16px"><div class="bg full"><span>{I("image", 18)}من الخلفية</span><span class="on">{I("palette", 18)}جاهزة</span><span>{I("format_color_fill", 18)}مخصّص</span></div></div>'
    '<div class="tgrid">' + "".join(f'<div>{tsw(n, on=(n == "Anon"))}<small>{n}</small></div>' for n in list(THEMES["presets"])[:8]) + '</div>'
    '<div class="gh">النمط</div><div class="chips" style="padding-top:0;flex-wrap:wrap">'
    f'<span class="chip on">{I("check", 18)}هادئ</span><span class="chip">نابض</span><span class="chip">تعبيري</span><span class="chip">وفيّ</span><span class="chip">أحادي</span></div>'
    '<div class="gh">الوضع</div><div style="padding:0 16px"><div class="bg full"><span>النظام</span><span>فاتح</span><span class="on">داكن</span></div></div>'
    '<div class="seg" style="margin-top:10px">' + setrow("dark_mode", "أسود حقيقي", "لشاشات AMOLED", ON) + "</div>")

IMG_BADGE = f'<span class="ph">{I("image", 18)}</span>'
VTILES = [("f4", "2:49:03", True), ("f6", "", False), ("f2", "0:42", True), ("f3", "", False), ("f1", "12:08", True), ("f5", "", False),
          ("f6", "3:15", True), ("f4", "", False), ("f3", "1:04:22", True)]
S["ش-19"] = phone(
    f'<div class="ab"><span class="ib">{I("arrow_back", 24, "flip")}</span><span class="tt"><b>الخزنة</b><small class="num">31 عنصراً · 6.4 GB</small></span>'
    f'<span class="ib">{I("lock")}</span><span class="ib">{I("settings")}</span></div>'
    f'<div class="vbanner">{I("encrypted", 28)}<span><b>مشفّرة على هذا الجهاز</b>AES-256-GCM · المفتاح داخل StrongBox ولا يغادره</span></div>'
    f'<div class="chips"><span class="chip on">{I("check", 18)}الكل</span><span class="chip">{I("movie", 18)}فيديو</span><span class="chip">{I("image", 18)}صور</span></div>'
    '<div class="vgrid">' + "".join(f'<div class="{f}">{f"<span class=du>{d}</span>" if d else ""}{"" if v else IMG_BADGE}</div>' for f, d, v in VTILES) + '</div>'
    f'<div class="fab" style="bottom:40px">{I("add", 28)}أضف إلى الخزنة</div>', dark=True)

S["ش-20"] = phone(
    f'<div class="ab"><span class="ib">{I("arrow_back", 24, "flip")}</span><span class="tt"><b style="font-size:24px">إعدادات الخزنة</b></span></div>'
    '<div style="flex:1;overflow:hidden">'
    '<div class="gh">الدخول</div><div class="seg">'
    + setrow("fingerprint", "البصمة", "مع رمز من 6 أرقام احتياطاً", ON)
    + setrow("password", "رمز تمويه", "رمز ثانٍ يفتح خزنة فارغة", ON)
    + setrow("visibility_off", "مدخل مخفي", "ضغط مطوّل على الشعار، لا زر ظاهر", ON) + '</div>'
    '<div class="gh">الحماية</div><div class="seg">'
    + setrow("lock_clock", "قفل تلقائي", "عند إطفاء الشاشة أو الخروج", '<span class="tv">فوراً</span>')
    + setrow("no_photography", "منع لقطات الشاشة", "والتسجيل وصورة التطبيقات الأخيرة", ON)
    + setrow("delete", "مسح بعد محاولات خاطئة", "", '<span class="tv">إيقاف</span>') + '</div>'
    '<div class="gh">النسخ والمفاتيح</div><div class="seg">'
    + setrow("backup", "نسخة احتياطية مشفّرة", "بكلمة مرور منفصلة · Argon2id", "")
    + setrow("key", "المفتاح الرئيسي", "Android Keystore · StrongBox", '<span class="ok">بالعتاد</span>') + "</div></div>", dark=True)

PROVIDERS = [("Interstellar.2014.1080p.BluRay.x265-RARBG.ar.srt", "OpenSubtitles", True, "12.4k"),
             ("Interstellar.2014.1080p.BluRay.x264.ar.srt", "SubSource", False, "8.1k"),
             ("Interstellar (2014) Arabic.srt", "SubDL", False, "5.6k"),
             ("Interstellar.2014.BDRip.ara.srt", "Podnapisi", False, "1.2k")]
S["ش-21"] = land(
    '<div class="panel" style="width:470px">'
    f'<h5>{I("download")}بحث الترجمات<span class="pib">{I("close", 22)}</span></h5>'
    f'<div class="chips" style="padding:0;flex-wrap:wrap"><span class="chip on">{I("translate", 18)}عربي</span><span class="chip">الكل</span><span class="chip">OpenSubtitles</span><span class="chip">SubDL</span><span class="chip">SubSource</span><span class="chip">Podnapisi</span></div>'
    '<div class="seg res">' + "".join(
        f'<div class="li{" on" if i == 0 else ""}">{"<div class=ldi style=width:24px;height:24px></div>" if i == 0 else I("subtitles", 22)}'
        f'<span><b>{n}</b><span class="meta"><span class="ptag{" hash" if h else ""}">{"تطابق البصمة" if h else "تطابق الاسم"}</span><span class="ptag">{p}</span>{I("download", 14)}<span class="num">{d}</span></span></span><span></span></div>'
        for i, (n, p, h, d) in enumerate(PROVIDERS[:3])) + '</div>'
    f'<div class="bg full"><span>{I("graphic_eq", 18)}أنشئ من الصوت</span><span>{I("translate", 18)}ترجم إلى العربية</span></div>'
    '</div>')

SUBS_TL = [(4, 15, "لن نفشل"), (17, 32, "سنتجاوز هذا"), (36, 50, "كما فعلنا دائماً"), (55, 71, "المجرّة لا تنتظر"), (75, 92, "انظر إلى الساعة")]
WAVE_BARS = "".join(f'<rect x="{i * 6}" y="{35 - hgt}" width="3" height="{hgt * 2}" rx="1.5" fill="rgba(255,255,255,.55)"/>'
                    for i, hgt in enumerate(int(4 + 26 * abs(math.sin(i * .37) * math.cos(i * .11))) for i in range(133)))
S["ش-22"] = land(
    '<div style="position:absolute;inset:0;background:rgba(10,15,15,.92);z-index:1"></div>'
    + ptop("مزامنة الترجمة", "Interstellar.ar.srt · Windows-1256 اكتُشف تلقائياً", ("text_fields", "more_vert"))
    + f'<div class="tl" style="top:96px"><div class="wave"><svg width="796" height="70" viewBox="0 0 796 70">{WAVE_BARS}</svg></div>'
    '<div class="subs">' + "".join(f'<i class="{"on" if k == 1 else ""}" style="left:{a}%;width:{b - a}%">{t}</i>' for k, (a, b, t) in enumerate(SUBS_TL)) + '</div>'
    '<span class="ph" style="left:24%"></span></div>'
    '<div class="pbot" style="bottom:20px"><div class="prow2" style="margin:0">'
    f'<div class="bg"><span>−0.5</span><span>−0.1</span><span class="on num">+1.42 ث</span><span>+0.1</span><span>+0.5</span></div>'
    f'<div style="display:flex;gap:8px"><span class="btn tonal" style="height:48px">{I("timer", 20)}هذا السطر الآن</span>'
    f'<span class="btn" style="height:48px">{I("sync", 20)}مزامنة تلقائية</span></div></div></div>')

# ---------------------------------------------------------------- flow content
RULES = {
    "ق-01": "لا إعلانات ولا حساب ولا تتبّع. لا اتصال بالإنترنت إلا لما يطلبه المستخدم: رابط، أو خادم، أو بحث ترجمة.",
    "ق-02": "أقل إذن ممكن: READ_MEDIA_VIDEO فقط. الحذف وإعادة التسمية عبر نافذة تأكيد النظام، لا إذن «كل الملفات».",
    "ق-03": "موضع كل ملف يُحفظ كل 5 ثوانٍ وعند الخروج. الاستئناف تلقائي، و«من البداية» يبقى 4 ثوانٍ.",
    "ق-04": "الزمن لا ينعكس: شريط التقدّم وأزرار التقديم والترجيع من اليسار إلى اليمين حتى في الواجهة العربية.",
    "ق-05": "الإيماءات بجهة الشاشة الفعلية: يسار = سطوع، يمين = صوت، أفقي = تقديم، نقرتان على الطرف = ±10 ث، ضمّ = تكبير. ولكل إيماءة زر بديل.",
    "ق-06": "الأدوات تختفي بعد 3 ثوانٍ بلا لمس، ولا تختفي ولوحة مفتوحة أو الإصبع على الشريط.",
    "ق-07": "لوحات المشغّل لا توقف الفيديو، وكل تغيير يظهر فوراً على الصورة.",
    "ق-08": "فك الترميز HW+ افتراضياً. إن فشل ملف يتحوّل إلى SW ويُعلم المستخدم، ويُحفظ الاختيار لذلك الملف.",
    "ق-09": "القفل يمنع كل لمس عدا زر القفل، ويُفتح بضغط مطوّل ثانيتين. قفل الأطفال يضيف تثبيت الشاشة.",
    "ق-10": "الخزنة معزولة من البداية: الملف يُشفَّر إلى مجلد التطبيق الخاص ولا يدخل MediaStore أبداً، فلا يراه المعرض ولا أي تطبيق، ولا يظهر في المكتبة أو البحث أو «تابع المشاهدة» أو الإشعارات.",
    "ق-11": "لا حذف فوري: الحذف ينقل إلى سلة النظام 30 يوماً.",
    "ق-12": "التطبيق يتذكّر لكل ملف: الموضع، والترجمة وتأخيرها، ومسار الصوت، وفك الترميز، ونسبة العرض. لا يُسأل المستخدم مرتين.",
    "ق-13": "التشفير: AES-256-GCM متدفّق بمقاطع 1MB (Tink StreamingAead)، مفتاح لكل ملف، والمفاتيح ملفوفة بمفتاح رئيسي في Android Keystore / StrongBox لا يغادر العتاد. الأسماء والمصغّرات مشفّرة أيضاً.",
    "ق-14": "التشغيل يفكّ التشفير في الذاكرة مقطعاً مقطعاً أثناء العرض مع القفز لأي موضع، ولا نسخة مفكوكة على القرص أبداً. لقطات الشاشة وتسجيلها ممنوعة (FLAG_SECURE).",
    "ق-15": "النقل إلى الخزنة: يُشفَّر الأصل ويُتحقَّق منه بفكّ تجريبي كامل، ثم يُحذف الأصل نهائياً بتأكيد النظام (استثناء من ق-11).",
    "ق-16": "لا استعادة بلا مفتاح: نسيان الرمز أو مسح الجهاز يُفقد الخزنة، إلا بنسخة احتياطية مشفّرة بكلمة مرور (Argon2id). يُقال هذا صراحة عند إعداد الخزنة.",
    "ق-17": "مزوّدو الترجمة يُستدعون بطلب المستخدم فقط، ويمكنه وضع مفتاح API خاص به. النتائج مرتّبة: تطابق البصمة أولاً، ثم الاسم، ثم عدد التنزيلات.",
    "ق-18": "الثيم = مصدر الألوان (الخلفية / جاهزة / مخصّص) + النمط + الوضع + التباين، ويُطبَّق فوراً بلا إعادة تشغيل. المشغّل داكن دائماً ويأخذ لون الثيم.",
}


def SC(code, dev, title, reach, does, states=(), rules=(), nxt=()):
    return dict(code=code, dev=dev, title=title, reach=reach, does=list(does), states=list(states), rules=list(rules), nxt=list(nxt))


PHASES = [
dict(n=1, name="البداية والمكتبة", where="الجوال · عمودي",
     goal="أول فتح يطلب إذناً واحداً ثم يعرض المكتبة. الرئيسية تبدأ بما توقّفت عنده في دوّارة كبيرة، ثم المجلدات كما هي على الجهاز في قائمة مجمّعة على طريقة أندرويد 16.",
     flows=[
  dict(name="أول فتح", screens=[
    SC("ش-01", "ph", "الترحيب وإذن الوصول", "أول تشغيل للتطبيق فقط.",
       ["أشكال Material التعبيرية (كعكة، برسيم، دائرة) تحمل لقطات فيديو: هوية Anon من أول شاشة.",
        "وعد في سطرين، وزر كبير واحد يطلب READ_MEDIA_VIDEO.",
        "«افتح ملفاً واحداً» لمن يرفض: منتقي ملفات النظام بلا إذن دائم."],
       states=["رفض الإذن: زر يفتح إعدادات التطبيق", "سماح جزئي (أندرويد 14+): شريط «اختر المزيد»"], rules=["ق-01", "ق-02"], nxt=["ش-02"]),
  ]),
  dict(name="التصفّح", screens=[
    SC("ش-02", "ph", "الرئيسية", "بعد الإذن، وكل فتح لاحق.",
       ["شريط علوي مرن: شعار Anon كبير، يصغر إلى عنوان عند التمرير.",
        "شريط بحث بشكل حبّة، مثل Caelestia وأندرويد 16.",
        "«تابع المشاهدة» دوّارة M3: عنصر كبير وعنصران يضيقان، بشريط كهرماني لما بقي.",
        "المجلدات قائمة مجمّعة: زوايا 24 للطرفين و4 بين العناصر، والرقم = ملفات جديدة."],
       states=["لا فيديو على الجهاز", "لا شيء لمتابعته: تختفي الدوّارة", "يجري الفحص: مؤشر تحميل يتبدّل شكله"], rules=["ق-03", "ق-10"], nxt=["ش-03", "ش-04", "ش-06", "ش-07"]),
    SC("ش-03", "ph", "محتوى مجلد (بالوضع الداكن)", "من مجلد في الرئيسية.",
       ["مصغّرة بالمدة، ثم اسم الملف كاملاً كما هو، ودقّته وحجمه.",
        "شارات من الملف نفسه: 4K، HDR بلون ثالثي، ترجمة مدمجة.",
        "شرائح تصفية، وتبديل قائمة/شبكة.",
        "نقرة = تشغيل، ضغطة مطوّلة = تحديد متعدد، ⋮ = الخيارات."],
       states=["تحديد متعدد: شريط أدوات عائم", "عرض الشبكة", "مجلد فارغ"], rules=["ق-03"], nxt=["ش-05", "ش-07"]),
    SC("ش-04", "ph", "البحث", "من شريط البحث.",
       ["يتحوّل شريط البحث إلى شاشة كاملة بانتقال نابضي.",
        "يبحث في الأسماء أثناء الكتابة ويلوّن الجزء المطابق.",
        "آخر عمليات البحث محفوظة على الجهاز فقط."],
       states=["لا نتائج: اقتراح البحث في كل المجلدات"], rules=["ق-10"], nxt=["ش-07"]),
    SC("ش-05", "ph", "خيارات الفيديو", "من ⋮ أو ضغطة مطوّلة.",
       ["رأس اللوحة: المدة والدقة والترميز والحجم.",
        "مجموعة أزرار متصلة للأفعال الثلاثة الأهم: تابع، من البداية، صوت فقط.",
        "ثم معلومات، إعادة تسمية، مشاركة، الخزنة، والحذف بالأحمر آخراً."],
       states=["عدة ملفات محددة: الأوامر الجماعية فقط"], rules=["ق-02", "ق-10", "ق-11"], nxt=["ش-07", "ش-16"]),
  ]),
  dict(name="الشبكة", screens=[
    SC("ش-06", "ph", "الشبكة والروابط", "من شريط التنقل.",
       ["بطاقة رابط واحدة: HTTP وHLS وRTSP وm3u.",
        "الخوادم المحفوظة بحالتها في قائمة مجمّعة: SMB وWebDAV وFTP، وDLNA تُكتشف وحدها.",
        "زر عائم ممتد «أضف خادماً» بحجم M3E المتوسط."],
       states=["كلمة سر خاطئة", "الخادم غير متاح", "لا خوادم بعد"], rules=["ق-01"], nxt=["ش-07"]),
  ]),
]),
dict(n=2, name="المشغّل", where="الجوال · أفقي · داكن دائماً",
     goal="الصورة أولاً. الأدوات تظهر بنقرة وتختفي وحدها. زر التشغيل أكبر عنصر ويتبدّل شكله بين مربّع مستدير ودائرة. الشريط موجي أثناء التشغيل ومستقيم عند الإيقاف، فيقرأ المشاهد الحالة من الشكل قبل الأيقونة.",
     flows=[
  dict(name="التشغيل", screens=[
    SC("ش-07", "land", "بدء التشغيل والاستئناف", "من أي فيديو.",
       ["يفتح الفيديو بتحوّل الحاوية من مصغّرته.",
        "يستأنف من آخر موضع، مع إشعار «من البداية» بمؤشر يتبدّل شكله خلال 4 ثوانٍ.",
        "لا أدوات عند البدء."],
       states=["ملف تالف: رسالة وتجربة SW", "أول تشغيل: لا إشعار"], rules=["ق-03", "ق-08", "ق-12"], nxt=["ش-08"]),
    SC("ش-08", "land", "الأدوات ظاهرة", "نقرة واحدة على الصورة.",
       ["أعلى: رجوع، اسم الملف وترميزه، الترجمة، الصوت، المزيد.",
        "الوسط: مجموعة أزرار، والتشغيل/الإيقاف أعرضها بلون الحاوية الأساسية.",
        "شريط موجي M3E بفواصل الفصول والجزء المحمَّل.",
        "شريطا أدوات عائمان: أدوات الشاشة، وشرائح الترجمة والسرعة وفك الترميز."],
       states=["موقوف: الشريط مستقيم والزر دائري", "بث شبكة: مؤشر تحميل"], rules=["ق-04", "ق-06"], nxt=["ش-09", "ش-10", "ش-11", "ش-12", "ش-13", "ش-15"]),
    SC("ش-09", "land", "الإيماءات", "سحب أو نقر على الصورة.",
       ["سحب عمودي يساراً = سطوع، يميناً = صوت، بعمود يمتلئ ونسبة.",
        "سحب أفقي = تقديم بمعاينة مصغّرة وفرق الوقت.",
        "نقرتان على الطرف = ±10 ث بموجة تتراكم +10، +20، +30.",
        "ضمّ = تكبير، ونقرتان في الوسط = تشغيل/إيقاف."],
       states=["وصل الصوت 100%: اهتزاز ثم تضخيم حتى 200%"], rules=["ق-05"], nxt=["ش-08"]),
  ]),
  dict(name="اللوحات", screens=[
    SC("ش-10", "land", "الترجمة", "من أيقونة الترجمة أو شريحة «عربي».",
       ["لوحة جانبية عائمة بزوايا 28 فوق الفيديو، والترجمة تنزاح عنها.",
        "المسارات قائمة مجمّعة، والمختار بحاوية ثانوية وعلامة صح.",
        "من الجهاز أو بحث أونلاين (OpenSubtitles) في مجموعة متصلة.",
        "التأخير بأزرار متصلة بخطوة 0.1 ث، ويتغيّر النص فوراً."],
       states=["لا ترجمة مدمجة", "البحث بلا نتائج", "لا إنترنت"], rules=["ق-01", "ق-07", "ق-12"], nxt=["ش-08"]),
    SC("ش-11", "land", "الصوت والسرعة", "من أيقونة الصوت أو شريحة السرعة.",
       ["السرعة: شرائح سريعة متصلة، ومنزلق M3E سميك بمقبض عمودي.",
        "مسارات الصوت بترميزها وقنواتها.",
        "تضخيم حتى 200% بنفس المنزلق."],
       states=["سماعة بلوتوث: ضبط تأخير الصوت"], rules=["ق-07", "ق-12"], nxt=["ش-08"]),
    SC("ش-12", "land", "خيارات التشغيل", "من ⋮ أعلى المشغّل.",
       ["فك الترميز HW / HW+ / SW مجموعة متصلة تتبدّل أثناء التشغيل.",
        "بلاطات: تكرار A-B (حدوده كهرمانية على الشريط)، مؤقت النوم، نسبة العرض، الخلفية، المعادل، لقطة.",
        "البلاطة المفعّلة بلون الحاوية الثالثية."],
       states=["HW غير مدعوم لهذا الملف: الخيار معطّل بسببه"], rules=["ق-07", "ق-08"], nxt=["ش-08"]),
    SC("ش-13", "land", "القفل وقفل الأطفال", "من زر القفل في الأدوات.",
       ["كل الأدوات تختفي، ويبقى زر القفل بشكل كعكة.",
        "الفتح بضغط مطوّل ثانيتين، بحلقة تمتلئ حوله.",
        "قفل الأطفال يضيف تثبيت الشاشة: لا خروج ولا إشعارات."],
       rules=["ق-09"], nxt=["ش-08"]),
    SC("ش-14", "land", "نهاية الفيديو", "عند انتهاء الملف.",
       ["بطاقة «التالي» من نفس المجلد بعدّ تنازلي 5 ثوانٍ.",
        "«شغّل الآن» زر أساسي و«إلغاء» زر نغمي، وإعادة التشغيل في الوسط."],
       states=["آخر ملف في المجلد: إعادة فقط", "التالي معطّل في الإعدادات"], nxt=["ش-07", "ش-03"]),
  ]),
  dict(name="الترجمات", screens=[
    SC("ش-21", "land", "بحث الترجمات من عدة مزوّدين", "من «بحث أونلاين» في لوحة الترجمة.",
       ["بحث واحد في OpenSubtitles وSubDL وSubSource وPodnapisi معاً، وJimaku للأنمي.",
        "«تطابق البصمة» (hash الملف) أولاً لأنه يطابق النسخة بالضبط، ثم تطابق الاسم.",
        "نقرة = تنزيل وتطبيق فوراً دون إيقاف الفيديو.",
        "بلا نتائج؟ «أنشئ من الصوت» بـ Whisper على الجهاز، و«ترجم إلى العربية»."],
       states=["حصة المزوّد انتهت: يُخفى ويُقترح غيره", "لا إنترنت", "ينشئ من الصوت: نسبة التقدّم"], rules=["ق-01", "ق-07", "ق-17"], nxt=["ش-22", "ش-08"]),
    SC("ش-22", "land", "مزامنة الترجمة وأدواتها", "من «أدوات» في لوحة الترجمة، أو بعد تنزيل ترجمة.",
       ["موجة الصوت وكتل الترجمة على خط زمني واحد، ورأس التشغيل كهرماني.",
        "«مزامنة تلقائية»: يطابق الكلام في الصوت مع الكتل ويحسب الإزاحة وفرق السرعة.",
        "«هذا السطر الآن»: تنقر حين يُقال السطر فتُضبط كل الترجمة.",
        "الترميز يُكتشف تلقائياً (Windows-1256 للعربي القديم)، وتحرير النص، وترجمة مزدوجة بلغتين، وتحويل 23.976 ↔ 25."],
       states=["لا كلام واضح في الصوت: المزامنة اليدوية فقط"], rules=["ق-04", "ق-07", "ق-12"], nxt=["ش-08"]),
  ]),
]),
dict(n=3, name="خارج المشغّل", where="الجوال · عمودي",
     goal="الفيديو يتبع المستخدم: صورة داخل صورة، وصوت في الخلفية بإشعار وسائط بشريط موجي مثل أندرويد 16. وما يخصّه يبقى خاصاً.",
     flows=[
  dict(name="الخلفية", screens=[
    SC("ش-15", "ph", "صورة داخل صورة وإشعار التشغيل", "زر PiP، أو الخروج للرئيسية أثناء التشغيل.",
       ["نافذة عائمة بزوايا 20 وأزرار ترجيع وإيقاف وتقديم.",
        "إشعار وسائط بشريط موجي وزر تشغيل بشكل مربّع مستدير."],
       states=["التشغيل في الخلفية مغلق: يتوقف الفيديو", "ملف من الخزنة: لا مصغّرة في الإشعار"], rules=["ق-10"], nxt=["ش-08"]),
  ]),
  dict(name="الإعدادات والمظهر", screens=[
    SC("ش-17", "ph", "الإعدادات", "من أيقونة الترس.",
       ["مجموعات مجمّعة بأسلوب أندرويد 16: المشغّل، الإيماءات والترجمة، المظهر والخصوصية.",
        "كل خيار بوصف سطر واحد، والمفاتيح بعلامة صح داخل المقبض."],
       rules=["ق-01", "ق-08", "ق-12"], nxt=["ش-18", "ش-20"]),
    SC("ش-18", "ph", "المظهر", "من «المظهر» في الإعدادات.",
       ["معاينة حيّة أعلى الشاشة تتغيّر مع كل اختيار.",
        "مصدر الألوان: من خلفية الجهاز، أو 12 مجموعة جاهزة، أو لون مخصّص بمنتقي ألوان.",
        "النمط (مثل Caelestia): هادئ، نابض، تعبيري، وفيّ، أحادي.",
        "الوضع: النظام / فاتح / داكن، وأسود حقيقي لشاشات AMOLED، والتباين: عادي / متوسط / عالٍ."],
       states=["أندرويد أقدم من 12: «من الخلفية» غير متاح"], rules=["ق-18"], nxt=["ش-17"]),
  ]),
  dict(name="الخزنة المشفّرة", screens=[
    SC("ش-16", "ph", "فتح الخزنة", "من المدخل المخفي: ضغط مطوّل على الشعار.",
       ["بصمة، ورمز من 6 أرقام احتياطاً.",
        "رمز التمويه يفتح خزنة فارغة تبدو حقيقية.",
        "المفتاح يُفكّ داخل العتاد بعد البصمة فقط."],
       states=["5 محاولات خاطئة: انتظار 30 ثانية", "أول مرة: إعداد الرمز وتحذير ق-16"], rules=["ق-13", "ق-16"], nxt=["ش-19"]),
    SC("ش-19", "ph", "داخل الخزنة", "بعد الفتح.",
       ["فيديو وصور في شبكة واحدة، والمصغّرات تُفكّ في الذاكرة فقط.",
        "شريط ثالثي يقول بوضوح أين المفتاح وما الخوارزمية.",
        "«أضف إلى الخزنة» يشفّر ويتحقّق ثم يحذف الأصل من المعرض.",
        "التشغيل من هنا يفكّ التشفير أثناء العرض، بنفس سرعة الملف العادي."],
       states=["خزنة فارغة", "يشفّر الآن: تقدّم لكل ملف", "قُفلت تلقائياً: العودة إلى ش-16"], rules=["ق-10", "ق-13", "ق-14", "ق-15"], nxt=["ش-07", "ش-20"]),
    SC("ش-20", "ph", "إعدادات الخزنة", "من الترس داخل الخزنة.",
       ["الدخول: البصمة، رمز التمويه، المدخل المخفي.",
        "الحماية: قفل تلقائي، منع لقطات الشاشة، مسح اختياري بعد محاولات خاطئة.",
        "نسخة احتياطية مشفّرة بكلمة مرور منفصلة، ومعلومة المفتاح الرئيسي."],
       rules=["ق-13", "ق-16"], nxt=["ش-19"]),
  ]),
]),
]

# ---------------------------------------------------------------- research content
REFS = [
    ("MX Player", "المرجع الأول للميزات",
     "تبديل HW / HW+ / SW أثناء التشغيل، إيماءات الترجمة، قفل الأطفال، طقم إيماءات كامل، فك ترميز واسع.",
     "الإعلانات، وتحوّله إلى خدمة بثّ بتبويبات محتوى، وأشرطة أدوات مزدحمة، وأذونات واسعة."),
    ("VLC", "مرجع الصيغ والشبكة",
     "يقرأ كل شيء تقريباً، تصفّح SMB وUPnP، معادل بإعدادات جاهزة.",
     "واجهة كثيفة قديمة الطراز، وإعدادات متاهة."),
    ("mpvEx", "أقرب مشروع تقنياً (Apache-2.0)",
     "libmpv داخل Compose بنمط M3 Expressive، عرض الشجرة والمجلدات، إعداد mpv.conf وسكربتات Lua.",
     "إعدادات المحترفين ظاهرة للجميع؛ نخفيها تحت «متقدّم»."),
    ("Next Player", "مرجع مكتبة M3 نظيفة",
     "مكتبة هادئة، استئناف لكل ملف، إعدادات معقولة.",
     "Media3 يعتمد على عتاد الجهاز فيفشل في صيغ يقرؤها mpv."),
    ("YouTube", "مرجع الإيماءات",
     "موجة النقرتين المتراكمة، الفصول على الشريط، معاينة المصغّرة أثناء السحب.",
     "تغيير الإيماءات المألوفة فجأة؛ إعادة تصميم 2024–2025 أغضبت المستخدمين حين تغيّر سلوك الصوت."),
    ("Infuse / Plex", "مرجع الإحساس الفاخر",
     "بطاقة «التالي» بعدّ تنازلي، مكتبة غنية بالمعلومات، تفاصيل مرتّبة.",
     "استهلاك البطارية (Infuse) والاشتراكات الإجبارية."),
    ("أندرويد 16 / Pixel", "المرجع البصري لـ M3 Expressive",
     "القوائم المجمّعة بزوايا 24/4، الشريط الموجي في إشعار الوسائط، زر تشغيل يتبدّل شكله، شريط تنقّل أقصر.",
     "—"),
    ("Caelestia shell", "مرجع الهوية (على جهازك)",
     "ألوان M3 tonal-spot ديناميكية بكل أدوار surfaceContainer، أيقونات Material Symbols Rounded، نوابض expressive (fast / default / slow).",
     "—"),
]

SUB_PROVIDERS = [
    ("OpenSubtitles.com", "الأكبر، وبحث ببصمة الملف (hash) يطابق النسخة بالضبط", "REST API بمفتاح، حصة تنزيل يومية؛ حساب المستخدم يرفعها"),
    ("SubDL", "أقرب بديل لـ Subscene بعد إغلاقه (مايو 2024)، أفلام ومسلسلات", "API بمفتاح مجاني"),
    ("SubSource", "مجتمع Subscene السابق، قوي في العربية", "API بمفتاح من الملف الشخصي"),
    ("Podnapisi", "أكثر من 2.2 مليون ترجمة بـ 101 لغة، أفلام قديمة وSDH", "بحث JSON"),
    ("Jimaku", "الأنمي", "API بمفتاح"),
    ("Addic7ed", "حلقات المسلسلات الحديثة", "بلا API رسمي (عبر Gestdown)"),
    ("على الجهاز", "Whisper ينشئ الترجمة من الصوت، ثم ترجمة إلى العربية، بلا إنترنت", "whisper.cpp مدمج، نموذج يُنزَّل مرة"),
]
VAULT_YES = ["من يفتح المعرض أو مدير الملفات أو أي تطبيق آخر: الملفات ليست في MediaStore أصلاً.",
             "من يسحب الملفات بكابل USB أو نسخة احتياطية: يجد بيانات مشفّرة بأسماء عشوائية.",
             "سرقة الهاتف وهو مقفل: المفتاح لا يُفكّ بلا بصمتك أو رمزك.",
             "لقطات الشاشة وتسجيلها وصورة التطبيقات الأخيرة."]
VAULT_NO = ["هاتف مخترق بصلاحيات root أثناء فتح الخزنة.",
            "كاميرا أخرى تصوّر الشاشة.",
            "نسيان الرمز بلا نسخة احتياطية: الخزنة تضيع، ولا أحد يستطيع فتحها، ولا نحن."]

PRINCIPLES = [
    ("movie-fill", "الصورة أولاً", "حول الفيديو ظلام. الأدوات تظهر حين تُطلب وتختفي وحدها، ولا لوحة توقف الفيديو."),
    ("swipe", "لكل إيماءة زر", "الإيماءة اختصار لا شرط. كل ما يُفعل بالسحب له زر ظاهر، فلا يضيع مستخدم جديد."),
    ("history", "لا تسأل مرتين", "الموضع والترجمة وتأخيرها والصوت وفك الترميز تُحفظ لكل ملف."),
    ("shield_lock-fill", "خاص افتراضياً", "بلا حساب ولا إعلانات ولا تتبّع. لا شبكة إلا بطلب المستخدم."),
]

LINKS = [
    ("Dribbble · video player app", "https://dribbble.com/search/video-player-app"),
    ("Dribbble · material you player", "https://dribbble.com/search/material-you-player"),
    ("Dribbble · tag video player", "https://dribbble.com/tags/video_player"),
    ("Material 3 Expressive · الإرشادات", "https://m3.material.io/"),
    ("Material Symbols · الأيقونات", "https://fonts.google.com/icons"),
    ("Eleken · أنماط واجهات مشغّلات الفيديو (2026)", "https://www.eleken.co/blog-posts/video-player-ui"),
    ("mpvEx · المصدر", "https://github.com/marlboro-advance/mpvEx"),
    ("mpv-android · المحرّك", "https://github.com/mpv-android/mpv-android"),
]

MOTION = [  # Caelestia / M3E motion tokens: name, spring (damping / stiffness), bezier fallback, duration, use
    ("expressiveFastSpatial", "0.6 / 800", "cubic-bezier(.42,1.67,.21,.90)", "350ms", "ضغط الأزرار، تبدّل شكل التشغيل، الشرائح"),
    ("expressiveDefaultSpatial", "0.8 / 380", "cubic-bezier(.38,1.21,.22,1.00)", "500ms", "اللوحات الجانبية، الورقة السفلية، البحث"),
    ("expressiveSlowSpatial", "0.8 / 200", "cubic-bezier(.39,1.29,.35,.98)", "650ms", "فتح الفيديو من مصغّرته"),
    ("expressiveFastEffects", "1 / 3800", "cubic-bezier(.31,.94,.34,1.00)", "150ms", "ظهور الأدوات واختفاؤها"),
    ("expressiveDefaultEffects", "1 / 1600", "cubic-bezier(.34,.80,.34,1.00)", "200ms", "تلاشي الألوان، تبديل الأيقونات"),
    ("expressiveSlowEffects", "1 / 800", "cubic-bezier(.34,.88,.34,1.00)", "300ms", "الإشعارات، التلاشي إلى الأسود"),
]


def e(t): return html.escape(t, quote=False)


def research():
    pr = "".join(f'<div class="card2"><b><span class="pi">{I(ic, 22)}</span>{t}</b><span>{d}</span></div>' for ic, t, d in PRINCIPLES)
    rows = "".join(f'<tr><td class="fx-c"><b>{n}</b><br><small style="color:var(--text-3)">{r}</small></td>'
                   f'<td><span class="take">نأخذ:</span> {t}</td><td><span class="avoid">نتجنّب:</span> {a}</td></tr>' for n, r, t, a in REFS)
    links = "".join(f'<a href="{u}" target="_blank" rel="noopener">{I("link", 20)}{n}<small>{u.split("/")[2]}</small></a>' for n, u in LINKS)
    return ('<section class="fx-phase dz" id="research"><h2 class="sec"><small>الجزء 1</small>المراجع والبحث</h2>'
            '<p class="kicker">قبل رسم أي شاشة: ماذا يفعل غيرنا جيداً، وأين يخطئ، وما المبادئ التي تحكم كل قرار بعدها. '
            'الشاشات في الجزء 3 تشير إلى هذه المبادئ وإلى القواعد في الملحق.</p>'
            '<h3>أربعة مبادئ</h3><div class="grid g2">' + pr + '</div>'
            '<h3>تحليل المنافسين والمراجع</h3><div class="fx-ixw"><table class="fx-ix"><tr><th>المرجع</th><th>ما نأخذه</th><th>ما نتجنّبه</th></tr>' + rows + '</table></div>'
            '<h3>أين يربح Anon</h3><div class="grid g3">'
            f'<div class="card2"><b><span class="pi">{I("memory", 22)}</span>محرّك mpv</b><span>صيغ MX وأكثر، وترجمة ASS كما صُنعت، وHDR بتحويل ألوان صحيح.</span></div>'
            f'<div class="card2"><b><span class="pi">{I("palette", 22)}</span>تصميم أندرويد 16</b><span>لا مشغّل مفتوح المصدر يطبّق M3 Expressive كاملاً بأشكاله ونوابضه وشريطه الموجي.</span></div>'
            f'<div class="card2"><b><span class="pi">{I("translate", 22)}</span>العربية أولاً</b><span>واجهة RTL حقيقية مع زمن لا ينعكس، وبحث ترجمة عربية من داخل المشغّل.</span></div></div>'
            '<h3>مزوّدو الترجمة</h3><p>MX وVLC يبحثان في OpenSubtitles فقط. Anon يبحث في الكل معاً ويرتّب بالتطابق. شروط كل API تُراجَع قبل الإطلاق.</p>'
            '<div class="fx-ixw"><table class="fx-ix"><tr><th>المزوّد</th><th>قوّته</th><th>الوصول</th></tr>' + "".join(
                f'<tr><td class="fx-c"><b>{a}</b></td><td>{b}</td><td>{c}</td></tr>' for a, b, c in SUB_PROVIDERS) + '</table></div>'
            '<h3>الخزنة: ممّ تحمي وممّ لا تحمي</h3><p>الكود مفتوح المصدر ولا يضعف ذلك الخزنة: الأمان في المفتاح المحبوس داخل عتاد الهاتف، لا في سرّية الكود (مبدأ Kerckhoffs). '
            'قراءة الكود لا تعطي أحداً المفتاح.</p><div class="grid g2">'
            f'<div class="card2"><b><span class="pi">{I("shield_lock-fill", 22)}</span>تحمي من</b><ul>' + "".join(f"<li>{x}</li>" for x in VAULT_YES) + '</ul></div>'
            f'<div class="card2"><b><span class="pi" style="background:#F9DEDC;color:#8C1D18">{I("info", 22)}</span>لا تحمي من</b><ul>' + "".join(f"<li>{x}</li>" for x in VAULT_NO) + '</ul></div></div>'
            '<h3>مصادر الإلهام</h3><p>روابط للبحث البصري. لقطات Dribbble ملك أصحابها، لذا هي روابط لا صور منسوخة.</p>'
            f'<div class="links">{links}</div></section>')


def design_system():
    def swatches(mode, keys):
        s = SCHEME[mode]
        return '<div class="sw">' + "".join(f'<div><i style="background:{s[k]}"></i><b>{k}</b><code>{s[k]}</code></div>' for k in keys) + "</div>"
    main = ["primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "secondaryContainer", "onSecondaryContainer",
            "tertiaryContainer", "onTertiaryContainer", "error"]
    surf = ["surface", "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer", "surfaceContainerHigh",
            "surfaceContainerHighest", "onSurface", "onSurfaceVariant", "outlineVariant"]
    type_rows = [("Display L · 57", "font:400 46px/1.1 var(--font)", "Anon"), ("Headline L · 32", "font:500 32px/1.25 var(--font)", "تابع المشاهدة"),
                 ("Title L · 22", "font:500 22px/1.3 var(--font)", "Movies"), ("Title M · 16", "font:500 16px/1.4 var(--font)", "الخوادم المحفوظة"),
                 ("Body L · 16", "font:400 16px/1.6 var(--font)", "يفتح الفيديو من حيث توقّفت."), ("Body M · 14", "font:400 14px/1.5 var(--font)", "23 فيديو · 61.2 GB"),
                 ("Label L · 14", "font:500 14px/1 var(--font)", "من البداية"), ("Label S · 11", "font:500 11px/1 var(--font)", "4K · HDR")]
    ty = "".join(f"<small>{a}</small><span style=\"{b}\">{c}</span>" for a, b, c in type_rows)
    rad = "".join(f'<div style="border-radius:{r}px">{n}<br>{r}</div>' for n, r in [("small", 8), ("medium", 12), ("large", 16), ("x-large", 28), ("full", 43)])
    shp = "".join(f'<div><i style="clip-path:url(#c-{k})"></i>{n}</div>' for k, n in [("cookie9", "كعكة 9 · المراحل والقفل"), ("cookie12", "كعكة 12 · الشعار"),
                                                                                       ("clover4", "برسيم 4 · النقاط والترحيب"), ("sunny8", "مشمس 8 · مؤشر التحميل")])
    icons = "".join(f'<div>{I(k, 28)}{k}</div>' for k in sorted(ICONS) if not k.endswith("-fill"))
    mo = "".join(f'<tr><td class="fx-c"><code>{a}</code></td><td class="fx-c num">{b}</td><td class="fx-c"><code>{c}</code></td><td class="fx-c num">{d}</td><td>{u}</td></tr>' for a, b, c, d, u in MOTION)
    nav_demo = "".join(f'<a class="{"on" if i == 0 else ""}"><span>{I(ic)}</span>{n}</a>' for i, (ic, n) in enumerate([("video_library-fill", "المكتبة"), ("playlist_play", "القوائم"), ("lan", "الشبكة")]))
    comps = [
        ("أزرار M3E بأحجامها", "lt", '<span class="btn xl">كبير XL</span><span class="btn">أساسي</span><span class="btn tonal">نغمي</span><span class="btn out">محيط</span><span class="btn text">نصّي</span><span class="btn sq">مربّع</span>'),
        ("مجموعة أزرار متصلة · السرعة وفك الترميز", "dk", '<div class="bg"><span>0.5</span><span>0.75</span><span>1</span><span class="on">1.25</span><span>1.5</span><span>2</span></div><div class="bg"><span>HW</span><span class="on">HW+</span><span>SW</span></div>'),
        ("زر التشغيل يتبدّل شكله · يعمل ← موقوف", "dk", f'<div class="pmid" style="position:static;transform:none"><span class="play">{I("pause", 40)}</span></div><span style="color:var(--onv)">{I("arrow_back", 24)}</span><div class="pmid" style="position:static;transform:none"><span class="play round">{I("play_arrow-fill", 40)}</span></div>'),
        ("شريط موجي · يعمل / موقوف / A-B", "dk", f'<div style="display:grid;gap:8px">{seek(300, .45, buf=.6)}{seek(300, .45, wavy=False, buf=.6)}{seek(300, .2, ab=(.3, .55))}</div>'),
        ("شريط أدوات عائم", "dk", f'<div class="ftb">{"".join(f"<span class=pib>{I(x)}</span>" for x in ("lock", "screen_rotation", "fit_screen", "picture_in_picture_alt"))}<span class="vd"></span><span class="pchip acc">{I("memory", 18)}HW+</span></div>'),
        ("منزلق M3E ومفتاح", "lt", f'<div style="width:220px"><div class="sl"><i style="width:calc(40% - 6px)"></i><u style="left:40%"></u><em style="width:calc(60% - 6px)"></em><span class="stop"></span></div></div><span class="swt on">{I("check", 16)}</span><span class="swt"></span>'),
        ("شرائح وشريط تنقّل", "lt", f'<div class="chips" style="padding:0"><span class="chip on">{I("check", 18)}الكل</span><span class="chip">4K</span></div><div class="nb" style="width:300px;margin:0;border-radius:20px;padding:10px 8px">{nav_demo}</div>'),
        ("قائمة مجمّعة · أندرويد 16", "lt", f'<div style="width:330px">{folder_list(FOLDERS[:3]).replace("padding:0 var(--pad)", "")}</div>'),
        ("مؤشر تحميل يتبدّل شكله", "lt", '<div class="ldi"></div><div class="ldi" style="clip-path:url(#c-cookie9)"></div><div class="ldi" style="clip-path:url(#c-clover4)"></div><div class="ldi" style="border-radius:50%;clip-path:none"></div>'),
        ("إشعار وسائط", "dk", f'<div class="notif" style="position:static;width:330px"><div class="th f4"></div><span><b>{TITLE}</b><small>Anon</small></span><div class="wrow"><span class="pp">{I("pause", 26)}</span>{seek(200, .3)}{I("skip_next-fill", 26)}</div></div>'),
    ]
    comp = "".join(f'<div class="comp"><small>{t}</small><div class="stage {c}">{h}</div></div>' for t, c, h in comps)
    gest = land(
        '<div class="zone" style="left:20px;top:20px;bottom:20px;width:200px">سحب عمودي<br><b>السطوع</b><br><br>نقرتان<br><b>−10 ث</b></div>'
        '<div class="zone" style="right:20px;top:20px;bottom:20px;width:200px">سحب عمودي<br><b>الصوت</b><br><br>نقرتان<br><b>+10 ث</b></div>'
        '<div class="zone" style="left:236px;right:236px;top:20px;bottom:20px">نقرة: الأدوات<br>نقرتان: تشغيل/إيقاف<br>ضمّ: تكبير<br>سحب أفقي: تقديم</div>')
    return ('<section class="fx-phase dz" id="system"><h2 class="sec"><small>الجزء 2</small>نظام التصميم</h2>'
            '<p class="kicker">Material 3 Expressive، بنفس منطق Caelestia الذي تستخدمه: ألوان tonal-spot تُولَّد من لون واحد، كل أدوار surfaceContainer، '
            'أيقونات Material Symbols Rounded، ونوابض expressive. الألوان أدناه تُقرأ من scheme.json الذي تُرسم منه الشاشات نفسها، فلا يختلف هذا القسم عنها.</p>'
            f'<h3>الألوان · البذرة <code>{SEED}</code> · tonal-spot</h3>'
            '<p>في Compose: <code>dynamicColorScheme()</code> على أندرويد 12+ عندما يكون «ألوان من الخلفية» مفعّلاً (الافتراضي)، وإلا هذا المخطط. '
            f'الكهرماني <code>{SCHEME["amber"]}</code> لون مخصّص منسجم مع البذرة (harmonize)، لشيء واحد فقط: ما شوهد وما بقي.</p>'
            '<div class="sw-grp"><h5>الفاتح · الأدوار الأساسية</h5>' + swatches("light", main) + '</div>'
            '<div class="sw-grp"><h5>الفاتح · الأسطح</h5>' + swatches("light", surf) + '</div>'
            '<div class="sw-grp"><h5>الداكن · الأدوار الأساسية (المشغّل داكن دائماً)</h5>' + swatches("dark", main) + '</div>'
            '<div class="sw-grp"><h5>الداكن · الأسطح</h5>' + swatches("dark", surf) + '</div>'
            '<h3>الثيمات · ليست لوناً واحداً</h3>'
            '<p>MX Player يعطي 8 ألوان مع الأسود والأبيض. Anon يعطي: ألوان خلفية الجهاز، أو 12 مجموعة جاهزة، أو أي لون بمنتقي ألوان، '
            'ثم 5 أنماط (نفس اختيار Caelestia)، و3 أوضاع مع أسود AMOLED، و3 مستويات تباين. كل مجموعة تُولَّد بنفس المولّد، فالتباين صحيح في كل الحالات.</p>'
            '<div class="sw-grp"><h5>12 مجموعة جاهزة · فاتح ثم داكن</h5><div class="comp"><div class="stage lt" style="display:grid;grid-template-columns:repeat(6,1fr);gap:18px 6px">'
            + "".join(f'<div style="display:grid;justify-items:center;gap:6px">{tsw(n, size=48)}<small style="font:400 11.5px/1 var(--font);color:var(--onv)">{n}</small></div>' for n in THEMES["presets"])
            + '</div><div class="stage dk" style="display:grid;grid-template-columns:repeat(6,1fr);gap:18px 6px">'
            + "".join(f'<div style="display:grid;justify-items:center;gap:6px">{tsw(n, "dark", size=48)}<small style="font:400 11.5px/1 var(--font);color:var(--onv)">{n}</small></div>' for n in THEMES["presets"])
            + '</div></div></div>'
            '<div class="sw-grp"><h5>5 أنماط من نفس اللون · داكن</h5><div class="grid" style="grid-template-columns:repeat(5,minmax(0,1fr))">'
            + "".join(f'<div class="card2" style="padding:10px;background:{v["dark"]["bg"]}"><div style="display:grid;gap:4px">'
                      f'<i style="display:block;height:34px;border-radius:12px;background:{v["dark"]["p"]}"></i>'
                      f'<i style="display:block;height:22px;border-radius:8px;background:{v["dark"]["pc"]}"></i>'
                      f'<span style="display:grid;grid-template-columns:1fr 1fr;gap:4px"><i style="display:block;height:22px;border-radius:8px;background:{v["dark"]["sc"]}"></i><i style="display:block;height:22px;border-radius:8px;background:{v["dark"]["tc"]}"></i></span></div>'
                      f'<small style="display:block;margin-top:8px;font:500 12px/1 var(--font);color:#fff;text-align:center">{ar}</small></div>'
                      for v, ar in zip(THEMES["variants"].values(), ["هادئ", "نابض", "تعبيري", "وفيّ", "أحادي"]))
            + '</div></div>'
            '<h3>الخط · Rubik</h3><p>Rubik هو خط Caelestia نفسه، ويدعم العربية واللاتينية بروح واحدة، فأسماء الملفات الإنجليزية والواجهة العربية متناسقة. يُضمَّن في التطبيق (رخصة OFL).</p>'
            f'<div class="ty">{ty}</div>'
            '<h3>الزوايا والأشكال</h3><div class="rad">' + rad + '</div><div class="shp" style="margin-top:8px">' + shp + '</div>'
            '<p style="margin-top:12px">في Compose: <code>MaterialShapes.Cookie9Sided</code> و<code>Clover4Leaf</code> و<code>Sunny</code>، و<code>Morph</code> لتبدّل الأشكال.</p>'
            '<h3>المكوّنات</h3><div class="grid g2">' + comp + '</div>'
            '<p style="margin-top:12px">في Compose (material3 1.4+): <code>MaterialExpressiveTheme</code>، <code>ButtonGroup</code>، <code>LinearWavyProgressIndicator</code>، '
            '<code>HorizontalFloatingToolbar</code>، <code>LoadingIndicator</code>، <code>Slider</code> بالمقبض الجديد، و<code>HorizontalMultiBrowseCarousel</code>.</p>'
            f'<h3>الأيقونات · Material Symbols Rounded · {NICONS}</h3>'
            '<p>وزن 400، ممتلئة للعنصر النشط (تبويب مختار، تشغيل)، ومحيطية لغيره. سهم الرجوع ينعكس في RTL، وأيقونات الزمن لا تنعكس.</p>'
            f'<div class="icons">{icons}</div>'
            '<h3>الحركة · نوابض M3 Expressive</h3><p>نفس رموز Caelestia. النابض أولاً، ومنحنى bezier بديل حيث لا ينفع النابض. مع «تقليل الحركة» تصبح كلها تلاشياً 150ms.</p>'
            '<div class="fx-ixw"><table class="fx-ix"><tr><th>الرمز</th><th>تخميد / صلابة</th><th>bezier بديل</th><th>المدة</th><th>يُستخدم في</th></tr>' + mo + '</table></div>'
            '<h3>خريطة الإيماءات</h3><p>بجهة الشاشة الفعلية مهما كانت لغة الواجهة (ق-05). الإيماءة تبدأ بعد 12dp من الحركة، والاتجاه يُقفل بعد أول حركة، وإيماءات النظام من الحواف لها الأولوية.</p>'
            f'<div class="fx-ixw">{gest}</div></section>')


# ---------------------------------------------------------------- flow assembly
ARROW = '<div class="fx-arrow"><svg viewBox="0 0 22 34" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M11 2v28M4 23l7 8 7-8"/></svg></div>'
DEV = {"ph": ("smartphone", "عمودي"), "land": ("stay_current_landscape", "أفقي")}
ORD = ["", "الأولى", "الثانية", "الثالثة"]


def screen(sc):
    ic, lab = DEV[sc["dev"]]
    t = [f'<div class="fx-txt"><div class="fx-t"><span class="fx-code">{sc["code"]}</span><span class="fx-st">{I(ic, 14)}{lab}</span><h4>{e(sc["title"])}</h4></div>']
    left = [f'<div><div class="fx-lbl">يصل إليها</div><p>{e(sc["reach"])}</p>',
            '<div class="fx-lbl">ما يحدث</div><ul>' + "".join(f"<li>{e(x)}</li>" for x in sc["does"]) + "</ul></div>"]
    right = []
    if sc["states"]:
        right.append('<div class="fx-lbl">حالات أخرى</div><div class="fx-chips">' + "".join(f'<span class="fx-chip">{e(x)}</span>' for x in sc["states"]) + "</div>")
    if sc["rules"]:
        right.append('<div class="fx-lbl">القواعد</div><div class="fx-chips">' + "".join(f'<span class="fx-br">{r}</span>' for r in sc["rules"]) + "</div>")
    if sc["nxt"]:
        right.append('<div class="fx-lbl">تقود إلى</div><div class="fx-chips">' + "".join(f'<span class="fx-code">{e(x)}</span>' for x in sc["nxt"]) + "</div>")
    t += left + ["<div>" + "".join(right) + "</div>", "</div>"]
    cls = "fx-row" if sc["dev"] == "ph" else "fx-row fx-wide"
    return f'<div class="{cls}"><div class="fx-fig">{S[sc["code"]]}</div>{"".join(t)}</div>'


def cover():
    n = sum(len(f["screens"]) for p in PHASES for f in p["flows"])
    parts = [("1", "المراجع والبحث", f"{len(REFS)} مراجع · 4 مبادئ", "#research"), ("2", "نظام التصميم", "ألوان · خط · أشكال · مكوّنات · أيقونات · حركة", "#system"),
             ("3", "تسلسل الشاشات", f"{n} شاشة في {len(PHASES)} مراحل", "#flow"), ("4", "الملحق", "فهرس الشاشات والقواعد", "#annex")]
    rows = "".join(f'<a href="{h}"><i>{a}</i><span><b>{b}</b><small>{c}</small></span><em>{I("arrow_back", 20)}</em></a>' for a, b, c, h in parts)
    hero = ('<div class="fx-hero">'
            '<div class="f4" style="clip-path:url(#c-cookie12)"></div><div class="f3" style="border-radius:28%"></div>'
            '<div class="f2" style="clip-path:url(#c-clover4)"></div><div class="f6" style="border-radius:50%"></div>'
            '<div class="f1" style="clip-path:url(#c-cookie9)"></div></div>')
    return ('<div class="fx-cover"><h1>Anon <span>Player</span></h1>'
            '<p class="fx-lede">مشغّل فيديو لأندرويد، مفتوح المصدر، بلا إعلانات ولا حساب. المحرّك libmpv (FFmpeg + libass)، والواجهة Kotlin وJetpack Compose '
            'بنظام Material 3 Expressive، بنفس روح Caelestia shell: ألوان ديناميكية، أيقونات Material Symbols، أشكال تتبدّل، ونوابض.</p>'
            + hero +
            f'<div class="fx-stats"><div><b>{n}</b><span>شاشة بمقاس حقيقي</span></div><div><b>{len(RULES)}</b><span>قاعدة</span></div>'
            f'<div><b>{NICONS}</b><span>أيقونة</span></div><div><b>{len(REFS)}</b><span>مراجع</span></div></div>'
            f'<div class="fx-plist">{rows}</div>'
            '<div class="fx-legend"><span><span class="fx-code">ش-08</span> رمز الشاشة</span><span><span class="fx-br">ق-05</span> قاعدة، نصّها في الملحق</span>'
            '<span><span class="fx-st">مقاسات حقيقية</span> عمودي 390×844 · أفقي 844×390</span></div></div>')


def phase(p, first):
    out = [f'<section class="fx-phase" id="{"flow" if first else "p" + str(p["n"])}">']
    if first: out.append('<div class="dz"><h2 class="sec"><small>الجزء 3</small>تسلسل الشاشات</h2></div><div style="height:22px"></div>')
    out.append(f'<div class="fx-ph-hd"><div class="fx-no">{p["n"]}</div><small>المرحلة {ORD[p["n"]]} · {e(p["where"])}</small><h2>{e(p["name"])}</h2></div>'
               f'<p class="fx-ph-goal">{e(p["goal"])}</p>')
    for i, f in enumerate(p["flows"], 1):
        path = ' <span>←</span> '.join(f'<span class="fx-code">{s["code"]}</span>' for s in f["screens"])
        out.append(f'<div class="fx-flow"><div class="fx-fl-hd"><span class="fx-n">{p["n"]}.{i}</span><h3>{e(f["name"])}</h3></div><div class="fx-path">{path}</div>')
        for j, s in enumerate(f["screens"]):
            if j: out.append(ARROW)
            out.append(screen(s))
        out.append("</div>")
    out.append("</section>")
    return "".join(out)


def annex():
    allsc = [(p, f, s) for p in PHASES for f in p["flows"] for s in f["screens"]]
    rows = "".join(f'<tr><td class="fx-c"><span class="fx-code">{s["code"]}</span></td><td>{e(s["title"])}</td><td class="fx-c">{p["n"]} · {e(f["name"])}</td><td class="fx-c">{DEV[s["dev"]][1]}</td></tr>'
                   for p, f, s in allsc)
    br = "".join(f'<tr><td class="fx-c"><span class="fx-br">{k}</span></td><td>{e(v)}</td></tr>' for k, v in RULES.items())
    return ('<section class="fx-phase dz" id="annex"><h2 class="sec"><small>الجزء 4</small>الملحق</h2>'
            '<h3>فهرس الشاشات</h3><div class="fx-ixw"><table class="fx-ix"><tr><th>الرمز</th><th>الشاشة</th><th>المرحلة · المسار</th><th>الاتجاه</th></tr>'
            + rows + '</table></div><h3>القواعد</h3><div class="fx-ixw"><table class="fx-ix"><tr><th>القاعدة</th><th>النص</th></tr>' + br + '</table></div>'
            f'<p class="fx-end">Anon Player · {DATE} · أسماء الملفات والأرقام في الشاشات أمثلة</p></section>')


def build():
    nav = (f'<nav class="jn"><span class="lg">{LOGO}Anon Player</span><a href="#research">المراجع</a><a href="#system">نظام التصميم</a>'
           '<a href="#flow">الشاشات</a><a href="#annex">الملحق</a></nav>')
    body = nav + cover() + research() + design_system() + "".join(phase(p, i == 0) for i, p in enumerate(PHASES)) + annex()
    page = ('<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
            '<title>Anon Player Design</title>'
            '<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>'
            '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Rubik:wght@400;500;600;700&display=swap">'
            f'<style>{CSS}</style></head><body>{SPRITE}<main class="fx-doc">{body}</main></body></html>')
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "w").write(page)
    print(OUT, len(page))


if __name__ == "__main__":
    build()
