#!/usr/bin/env python3
"""Prepare UltraTel launcher artwork for Android resources during the CI build.

Upload a square 512x512 PNG to branding/ultratel-launcher.png first. This file
is the launcher-safe symbol-only variant: no small lettering to be clipped.
The original Telegram alternate icon assets are not changed by this script.
"""
from pathlib import Path
import sys

from PIL import Image, ImageDraw, ImageChops


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "branding/ultratel-launcher.png"
RES = ROOT / "TMessagesProj/src/main/res"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def main():
    if not SOURCE.is_file():
        sys.exit(
            "UltraTel: falta branding/ultratel-launcher.png. "
            "Adicione a imagem ao PR antes de executar o workflow."
        )
    with Image.open(SOURCE) as original:
        if original.format != "PNG" or original.width != original.height:
            sys.exit("UltraTel: o icone precisa ser PNG quadrado.")
        icon = original.convert("RGBA")

    for dpi, side in DENSITIES.items():
        directory = RES / ("mipmap-" + dpi)
        directory.mkdir(parents=True, exist_ok=True)
        scaled = icon.resize((side, side), Image.Resampling.LANCZOS)
        scaled.save(directory / "ic_launcher.png", optimize=True)

        round_mask = Image.new("L", (side, side), 0)
        ImageDraw.Draw(round_mask).ellipse((0, 0, side - 1, side - 1), fill=255)
        round_icon = scaled.copy()
        round_icon.putalpha(ImageChops.multiply(scaled.getchannel("A"), round_mask))
        round_icon.save(directory / "ic_launcher_round.png", optimize=True)

    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)

    # Fit the official brand artwork within Android's adaptive-icon safe area.
    adaptive = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    adaptive.alpha_composite(icon.resize((288, 288), Image.Resampling.LANCZOS), (72, 72))
    adaptive.save(nodpi / "ultratel_adaptive_foreground.png", optimize=True)

    # Static launch window on Android versions below 12.
    icon.resize((144, 144), Image.Resampling.LANCZOS).save(
        nodpi / "ultratel_startup_logo.png", optimize=True
    )

    # Smooth zoom/bounce showing exactly the same UltraTel logo on Android 12+.
    # The first frame shows the complete logo even on static-only devices.
    for frame, side in enumerate((164, 174, 186, 199, 194, 192)):
        canvas = Image.new("RGBA", (288, 288), (0, 0, 0, 0))
        scaled_frame = icon.resize((side, side), Image.Resampling.LANCZOS)
        canvas.alpha_composite(
            scaled_frame, ((288 - side) // 2, (288 - side) // 2)
        )
        canvas.save(nodpi / f"ultratel_splash_frame_{frame}.png", optimize=True)

    print("UltraTel: launcher, adaptive, startup and animated splash assets ready.")


if __name__ == "__main__":
    main()
