#!/usr/bin/env python3
"""The Google Play listing graphics, painted from the same drawing as the icon (tools/skyray_icon.py):
the 512x512 app icon (a full square; Play applies its own mask) and the 1024x500 feature graphic.
Run from the repo root; the files land in store/play/.

    python3 tools/play_assets.py
"""
import importlib.util, pathlib, struct, zlib
ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("skyray_icon", ROOT / "tools/skyray_icon.py"); I = importlib.util.module_from_spec(spec); spec.loader.exec_module(I)
OUT = ROOT / "store/play"


def png(path, w, h, rows):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + r for r in rows)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    print(f"{path.relative_to(ROOT)}  {w}x{h}")


if __name__ == "__main__":
    png(OUT / "icon-512.png", *I.paint(512, "none", "icon", ss=2))
    s = 500 / 108.0
    png(OUT / "feature-1024x500.png", *I.paint((1024, 500), "none", "sky_comet", scale=s, offset=((1024 / s - 108) / 2, 0.0), ss=2))   # centred: x = px / scale - offset
