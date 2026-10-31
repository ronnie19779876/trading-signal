#!/usr/bin/env python3
"""Rasterize the design of public/favicon.svg into a multi-size favicon.ico (PIL cannot read SVG, so this
redraws it with the same parameters).

    python3 scripts/make-favicon.py

If you change the SVG's colors or letters, change them here too and rerun."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

RED = (216, 30, 44, 255)          # #D81E2C, same as favicon.svg
WHITE = (255, 255, 255, 255)
TEXT = "TS"
FONT = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"   # weight matches the SVG's 700 (on 09-19 the user found Arial Black too heavy)
CANVAS = 256                      # draw large, then downsample, so small sizes get clean edges
OUT = Path(__file__).resolve().parent.parent / "public" / "favicon.ico"
SIZES = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]


def render() -> Image.Image:
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle([0, 0, CANVAS - 1, CANVAS - 1], radius=int(CANVAS * 14 / 64), fill=RED)
    font = ImageFont.truetype(FONT, int(CANVAS * 0.50))
    left, top, right, bottom = draw.textbbox((0, 0), TEXT, font=font)
    draw.text(((CANVAS - (right - left)) / 2 - left, (CANVAS - (bottom - top)) / 2 - top), TEXT, font=font, fill=WHITE)
    return img


if __name__ == "__main__":
    render().save(OUT, format="ICO", sizes=SIZES)
    print(f"写入 {OUT}")
