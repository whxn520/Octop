"""Generate Octop adaptive / legacy launcher icons from the brand mark."""

from __future__ import annotations

import os
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
SRC = Path(__file__).resolve().parents[3] / "dashboard" / "public" / "logo_horizontal_dark.png"


def load_white_mark() -> Image.Image:
    src = Image.open(SRC).convert("RGBA")
    mark = src.crop((0, 30, 230, 260))
    pixels = mark.load()
    assert pixels is not None
    w, h = mark.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = pixels[x, y]
            if a < 20:
                pixels[x, y] = (0, 0, 0, 0)
                continue
            if r > 160 and g < 120:
                strength = min(255, int(a * (0.55 + 0.45 * (r / 255.0))))
                pixels[x, y] = (255, 255, 255, strength if g < 100 else int(strength * 0.85))
            elif r > 200 and g > 180 and b > 180:
                pixels[x, y] = (0, 0, 0, 0)
            else:
                alpha = min(255, int((255 - min(r, g, b)) * a / 255))
                pixels[x, y] = (255, 255, 255, alpha)
    return mark


def make_foreground(mark: Image.Image, size: int, fill_ratio: float = 0.62) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    target = int(size * fill_ratio)
    mw, mh = mark.size
    scale = min(target / mw, target / mh)
    nw, nh = max(1, int(mw * scale)), max(1, int(mh * scale))
    resized = mark.resize((nw, nh), Image.Resampling.LANCZOS)
    ox = (size - nw) // 2
    oy = (size - nh) // 2
    canvas.paste(resized, (ox, oy), resized)
    return canvas


def make_background(size: int) -> Image.Image:
    # Official tile: light coral top-left → deep red bottom-right.
    c0 = (0xFF, 0x5E, 0x60)
    c1 = (0xFF, 0x03, 0x07)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    pix = img.load()
    assert pix is not None
    denom = max(1, size - 1)
    for y in range(size):
        for x in range(size):
            t = (0.55 * y + 0.45 * (denom - x)) / denom
            t = max(0.0, min(1.0, t))
            r = int(c0[0] + (c1[0] - c0[0]) * t)
            g = int(c0[1] + (c1[1] - c0[1]) * t)
            b = int(c0[2] + (c1[2] - c0[2]) * t)
            pix[x, y] = (r, g, b, 255)
    return img


def make_legacy(mark: Image.Image, size: int, radius_ratio: float = 0.22) -> Image.Image:
    bg = make_background(size)
    # Slightly larger mark on the full tile so it reads clearly under launcher masks.
    fg = make_foreground(mark, size, fill_ratio=0.68)
    composed = bg.copy()
    composed.alpha_composite(fg)
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    rad = int(size * radius_ratio)
    draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=rad, fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(composed, (0, 0), mask)
    return out


def main() -> None:
    mark = load_white_mark()
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)

    make_foreground(mark, 432).save(RES / "drawable" / "ic_launcher_foreground.png")
    make_background(432).save(RES / "drawable" / "ic_launcher_background.png")

    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in densities.items():
        d = RES / folder
        d.mkdir(parents=True, exist_ok=True)
        legacy = make_legacy(mark, size)
        legacy.save(d / "ic_launcher.png")
        # Round launcher uses circle crop on many OEMs; keep same asset.
        legacy.save(d / "ic_launcher_round.png")
        print(f"wrote {folder} {size}px")

    preview = make_legacy(mark, 512)
    preview.save(ROOT / "ic_launcher_preview.png")
    print("done")


if __name__ == "__main__":
    main()
