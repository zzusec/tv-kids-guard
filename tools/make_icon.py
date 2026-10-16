#!/usr/bin/env python3
"""Generate text-free launcher icons and TV banners (requires Pillow)."""

from pathlib import Path

from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
BG = (8, 43, 54, 255)
WHITE = (255, 255, 255, 255)
MINT = (99, 246, 196, 255)
SAMPLES = 4
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def draw_mark(draw, x, y, width):
    def box(left, top, right, bottom):
        return (x + left * width, y + top * width,
                x + right * width, y + bottom * width)

    stroke = max(1, round(width * 0.04))
    draw.rounded_rectangle(box(0, 0, 1, 0.64), radius=width * 0.07,
                           outline=WHITE, width=stroke)
    draw.rounded_rectangle(box(0.29, 0.68, 0.71, 0.72), radius=stroke / 2,
                           fill=WHITE)
    draw.ellipse(box(0.28, 0.10, 0.72, 0.54), outline=MINT, width=stroke)
    points = [(x + px * width, y + py * width)
              for px, py in [(0.39, 0.25), (0.50, 0.32), (0.61, 0.245)]]
    draw.line(points, fill=MINT, width=stroke, joint="curve")
    radius = stroke / 2
    for px, py in points:
        draw.ellipse((px - radius, py - radius, px + radius, py + radius), fill=MINT)


def render(width, height, banner=False):
    w, h = width * SAMPLES, height * SAMPLES
    if banner:
        image = Image.new("RGBA", (w, h), BG)
    else:
        image = Image.new("RGBA", (w, h))
        ImageDraw.Draw(image).rounded_rectangle((0, 0, w - 1, h - 1),
                                                radius=w * 0.22, fill=BG)
    mark_width = w * (0.49 if banner else 0.76)
    mark_height = mark_width * 0.72
    draw_mark(ImageDraw.Draw(image), (w - mark_width) / 2, (h - mark_height) / 2,
              mark_width)
    return image.resize((width, height), Image.Resampling.LANCZOS)


def main():
    for density, factor in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        size = round(48 * factor)
        icon = render(size, size)
        icon.save(folder / "ic_launcher.png")
        banner = render(round(320 * factor), round(180 * factor), banner=True)
        banner.save(folder / "tv_banner.png")
        print(f"{density}: icon {size}x{size}, banner {banner.width}x{banner.height}")


if __name__ == "__main__":
    main()
