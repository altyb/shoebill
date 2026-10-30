"""One-time: trace ref/v1.png (Gemini reference) into vector paths -> traced.json. make.py builds everything from that.
Usage: python design/logo/trace.py"""
import json, pathlib, re, subprocess, tempfile
from PIL import Image
L = pathlib.Path(__file__).resolve().parent
im = Image.open(L / "ref/v1.png").convert("RGB")
SQ = (206, 206, 819, 819)                      # the dark rounded square in the reference (measured)
im = im.crop(SQ)
cyan = lambda c: c[2] > 150 and c[0] < 140 and c[2] - c[0] > 60

def trace(pred, src=None, margin=60):
    src = src or im
    m = Image.new("1", src.size, 1)
    pi, pm = src.load(), m.load()
    for y in range(src.height):
        for x in range(src.width):
            inner = margin < x < src.width - margin and margin < y < src.height - margin  # skip page past rounded corners
            if inner and pred(pi[x, y]): pm[x, y] = 0      # potrace traces black
    with tempfile.TemporaryDirectory() as d:
        p = pathlib.Path(d)
        m.save(p / "a.pbm")
        subprocess.run(["potrace", "-s", "--flat", "-t", "8", "-a", "1.0", "-O", "0.5", str(p / "a.pbm"), "-o", str(p / "a.svg")], check=True)
        s = (p / "a.svg").read_text()
    tr = re.search(r'transform="([^"]+)"', s).group(1)
    d = " ".join(re.findall(r' d="([^"]+)"', s))
    return {"transform": tr, "d": " ".join(d.split())}

hood = Image.open(L / "ref/round2/c/1.png").convert("RGB")   # second reference: the hooded figure, full-bleed 1024
out = {"size": im.width, "body": trace(lambda c: sum(c) > 300), "band": trace(cyan),
       "hood_size": hood.width, "hood": trace(lambda c: min(c) > 200, hood, 110)}
(L / "traced.json").write_text(json.dumps(out, indent=1))
print({k: len(v["d"]) for k, v in out.items() if isinstance(v, dict)})
