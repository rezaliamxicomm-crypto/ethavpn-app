#!/usr/bin/env python3
"""SkyRay's icon, painted without any image library: a comet (bright head, tapering tail) over a night
sky that lightens towards the bottom right, with a few sparkles. The adaptive icon (Android 8+) is the
vector drawables in res/drawable; this script paints the same design into the fallback PNGs — the
legacy launcher icons (square and round), the notification (status-bar) icons in white and black, and
the TV banner. Run from the repo root after changing the design; commit the PNGs.

    python3 tools/skyray_icon.py
"""
import math, struct, zlib, pathlib

RES = pathlib.Path(__file__).resolve().parents[1] / "V2rayNG/app/src/main/res"
NAVY, MID, SKY = (10, 30, 94), (30, 79, 216), (74, 168, 240)        # #0A1E5E → #1E4FD8 → #4AA8F0
G0, G1 = (30.0, 0.0), (78.0, 108.0)                                  # the sky's gradient axis: dark top, light bottom-right
HEAD, R_HEAD, GLOW = (69.0, 38.0), 11.5, 26.0
TIP = (26.0, 77.0)
CTRL = (40.0, 44.0)                                                   # the tail's axis bows up and to the left
SPARKS_PLUS = [((26, 26), 5.0, 0.9), ((84, 62), 4.0, 0.75)]
SPARKS_DOT = [((22, 44), 1.3, 0.55), ((45, 20), 1.1, 0.6), ((90, 44), 1.2, 0.45)]


def bezier(p0, p1, p2, p3, n=48):
    pts = []
    for i in range(n + 1):
        t = i / n; u = 1 - t
        pts.append((u*u*u*p0[0] + 3*u*u*t*p1[0] + 3*u*t*t*p2[0] + t*t*t*p3[0],
                    u*u*u*p0[1] + 3*u*u*t*p1[1] + 3*u*t*t*p2[1] + t*t*t*p3[1]))
    return pts


def tail_polygon(n=40):
    """A tapered sweep: the axis is a quadratic curve HEAD → CTRL → TIP; the half-width is 8.5 at the
    head and 0 at the tip, thinning slowly (t^0.8), so the tail stays bold for most of its length."""
    def axis(t):
        u = 1 - t
        return (u*u*HEAD[0] + 2*u*t*CTRL[0] + t*t*TIP[0], u*u*HEAD[1] + 2*u*t*CTRL[1] + t*t*TIP[1])
    top, bottom = [], []
    for i in range(n + 1):
        t = i / n
        ax, ay = axis(t); bx, by = axis(min(1.0, t + 1e-3)); dx, dy = bx - ax, by - ay
        if dx == 0 and dy == 0: dx, dy = TIP[0] - HEAD[0], TIP[1] - HEAD[1]
        L = math.hypot(dx, dy); nx, ny = -dy / L, dx / L
        w = 8.5 * (1 - t) ** 0.8
        top.append((ax + nx * w, ay + ny * w)); bottom.append((ax - nx * w, ay - ny * w))
    return top + bottom[::-1]


def tail_path_data():
    """The same polygon as a vector-drawable pathData string."""
    pts = tail_polygon(24)
    return "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts) + " Z"


POLY = tail_polygon()
TAIL_LEN = math.hypot(TIP[0] - HEAD[0], TIP[1] - HEAD[1])


def row_spans(y):
    """x crossings of the tail polygon at height y (canvas units), even-odd."""
    xs = []
    n = len(POLY)
    for i in range(n):
        (x0, y0), (x1, y1) = POLY[i], POLY[(i + 1) % n]
        if (y0 <= y < y1) or (y1 <= y < y0):
            xs.append(x0 + (y - y0) * (x1 - x0) / (y1 - y0))
    xs.sort()
    return list(zip(xs[0::2], xs[1::2]))


def gradient(t):
    t = max(0.0, min(1.0, t))
    if t < 0.55:
        a, b, f = NAVY, MID, t / 0.55
    else:
        a, b, f = MID, SKY, (t - 0.55) / 0.45
    return [a[i] + (b[i] - a[i]) * f for i in range(3)]


def paint(size, shape="square", mode="icon", canvas=108.0, offset=(0.0, 0.0), scale=None, ss=3):
    """RGBA rows. shape: square (rounded corners) | circle | none. mode: icon (sky + comet + sparkles),
    comet (comet only on transparent, white), comet_black, sky_comet (sky without mask + comet, for the banner)."""
    w = size[0] if isinstance(size, tuple) else size
    h = size[1] if isinstance(size, tuple) else size
    scale = scale or (w / canvas)
    rows = []
    for py in range(h):
        acc = [[0.0, 0.0, 0.0, 0.0] for _ in range(w)]
        for sy in range(ss):
            y = (py + (sy + 0.5) / ss) / scale - offset[1]
            spans = row_spans(y)
            for px in range(w):
                for sx in range(ss):
                    x = (px + (sx + 0.5) / ss) / scale - offset[0]
                    # mask
                    if shape == "circle":
                        m = 1.0 if math.hypot(x - canvas / 2, y - canvas / 2) <= canvas / 2 else 0.0
                    elif shape == "square":
                        rr = canvas * 0.19; cx = min(max(x, rr), canvas - rr); cy = min(max(y, rr), canvas - rr)
                        m = 1.0 if math.hypot(x - cx, y - cy) <= rr else 0.0
                    else:
                        m = 1.0
                    if m == 0.0:
                        continue
                    if mode in ("icon", "sky_comet"):
                        gx, gy = G1[0] - G0[0], G1[1] - G0[1]
                        r, g, b = gradient(((x - G0[0]) * gx + (y - G0[1]) * gy) / (gx * gx + gy * gy)); a = 1.0
                    else:
                        r = g = b = 0.0; a = 0.0
                    # the tail
                    inside = any(x0 <= x <= x1 for x0, x1 in spans)
                    if inside:
                        u = min(1.0, math.hypot(x - HEAD[0], y - HEAD[1]) / TAIL_LEN)
                        ta = 1.0 - 0.65 * (u ** 1.6)
                        if mode in ("comet", "comet_black"):
                            ta = 1.0 if mode == "comet_black" else max(ta, 0.35)
                        c = 0.0 if mode == "comet_black" else 255.0
                        r, g, b, a = r + (c - r) * ta, g + (c - g) * ta, b + (c - b) * ta, a + (1 - a) * ta
                    # the head and its glow
                    d = math.hypot(x - HEAD[0], y - HEAD[1])
                    if d < GLOW and mode in ("icon", "sky_comet"):
                        ga = 0.45 * (1 - max(0.0, d - R_HEAD) / (GLOW - R_HEAD)) ** 2
                        r, g, b = r + (255 - r) * ga, g + (255 - g) * ga, b + (255 - b) * ga
                    if d <= R_HEAD:
                        c = 0.0 if mode == "comet_black" else 255.0
                        r, g, b, a = c, c, c, 1.0
                    # sparkles (sky modes only)
                    if mode in ("icon", "sky_comet"):
                        for (cx_, cy_), s_, al in SPARKS_PLUS:
                            if (abs(x - cx_) <= s_ / 2 and abs(y - cy_) <= 0.65) or (abs(y - cy_) <= s_ / 2 and abs(x - cx_) <= 0.65):
                                r, g, b = r + (255 - r) * al, g + (255 - g) * al, b + (255 - b) * al
                        for (cx_, cy_), rad, al in SPARKS_DOT:
                            if math.hypot(x - cx_, y - cy_) <= rad:
                                r, g, b = r + (255 - r) * al, g + (255 - g) * al, b + (255 - b) * al
                    cell = acc[px]
                    cell[0] += r * a; cell[1] += g * a; cell[2] += b * a; cell[3] += a
        n = ss * ss
        row = bytearray()
        for cell in acc:
            a = cell[3] / n
            if a <= 0:
                row += b"\x00\x00\x00\x00"
            else:
                row += bytes((int(round(cell[0] / n / a)), int(round(cell[1] / n / a)), int(round(cell[2] / n / a)), int(round(a * 255))))
        rows.append(bytes(row))
    return w, h, rows


def png(path, w, h, rows):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + r for r in rows)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data); print(f"{path.relative_to(RES)}  {w}x{h}")


if __name__ == "__main__":
    import sys
    if sys.argv[1:] == ["--path"]:
        print(tail_path_data()); sys.exit(0)
    for dpi, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        png(RES / f"mipmap-{dpi}/ic_launcher.png", *paint(px, "square"))
        png(RES / f"mipmap-{dpi}/ic_launcher_round.png", *paint(px, "circle"))
    # notification icons: the comet alone, drawn larger inside the box (24 dp = the whole canvas)
    for dpi, px in (("mdpi", 24), ("hdpi", 36), ("xhdpi", 48), ("xxhdpi", 72), ("xxxhdpi", 96)):
        png(RES / f"drawable-{dpi}/ic_stat_name.png", *paint(px, "none", "comet", canvas=76.0, offset=(-16.0, -18.0)))
        png(RES / f"drawable-{dpi}/ic_stat_name_black.png", *paint(px, "none", "comet_black", canvas=76.0, offset=(-16.0, -18.0)))
    # the TV banner (320x180): the sky across the banner, the comet in the middle
    s = 180 / 108.0
    png(RES / "mipmap-xhdpi/ic_banner.png", *paint((320, 180), "none", "sky_comet", scale=s, offset=((320 / s - 108) / 2, 0.0)))   # centred: x = px / scale - offset
    png(RES / "mipmap-xhdpi/ic_banner_foreground.png", *paint((320, 180), "none", "comet", scale=s, offset=((320 / s - 108) / 2, 0.0)))
