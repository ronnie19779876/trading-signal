#!/usr/bin/env python3
"""把 public/favicon.svg 的设计栅格化成多尺寸 favicon.ico（PIL 不认 SVG，这里按同样的参数重画）。

    python3 scripts/make-favicon.py

改了 SVG 的配色或字母，跟着改这里再重跑。"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

RED = (216, 30, 44, 255)          # #D81E2C，与 favicon.svg 一致
WHITE = (255, 255, 255, 255)
TEXT = "TS"
FONT = "/System/Library/Fonts/Supplemental/Arial Black.ttf"
CANVAS = 256                      # 先画大图再下采样，小尺寸边缘才干净
OUT = Path(__file__).resolve().parent.parent / "public" / "favicon.ico"
SIZES = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]


def render() -> Image.Image:
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle([0, 0, CANVAS - 1, CANVAS - 1], radius=int(CANVAS * 14 / 64), fill=RED)
    font = ImageFont.truetype(FONT, int(CANVAS * 0.56))
    left, top, right, bottom = draw.textbbox((0, 0), TEXT, font=font)
    draw.text(((CANVAS - (right - left)) / 2 - left, (CANVAS - (bottom - top)) / 2 - top), TEXT, font=font, fill=WHITE)
    return img


if __name__ == "__main__":
    render().save(OUT, format="ICO", sizes=SIZES)
    print(f"写入 {OUT}")
