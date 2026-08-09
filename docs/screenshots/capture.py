#!/usr/bin/env python3
"""Capture a screenshot from the connected device and normalise it for the README.

    python3 capture.py 01-model-picker

Pulls via `adb exec-out screencap -p`, scales the long edge down to a 1080-wide phone
resolution, and re-encodes until it fits under 500 KB.
"""

import os
import subprocess
import sys
from io import BytesIO

from PIL import Image

TARGET_WIDTH = 1080
MAX_BYTES = 500 * 1024
OUT_DIR = "docs/screenshots"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")


def capture() -> Image.Image:
    raw = subprocess.run(
        [ADB, "exec-out", "screencap", "-p"], capture_output=True, check=True
    ).stdout
    if not raw:
        sys.exit("screencap returned nothing — is the device unlocked?")
    return Image.open(BytesIO(raw)).convert("RGB")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: capture.py <name-without-extension>")
    name = sys.argv[1]

    image = capture()
    original = image.size
    if image.width > TARGET_WIDTH:
        height = round(image.height * TARGET_WIDTH / image.width)
        image = image.resize((TARGET_WIDTH, height), Image.LANCZOS)

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, f"{name}.png")

    # Quantising to a palette is what actually gets a flat-UI screenshot under the limit;
    # PNG optimisation alone leaves these around a megabyte.
    for colors in (256, 192, 128, 96, 64):
        image.convert("RGB").quantize(colors=colors, method=Image.MEDIANCUT).save(
            path, "PNG", optimize=True
        )
        size = os.path.getsize(path)
        if size <= MAX_BYTES:
            print(f"{path}  {original[0]}x{original[1]} -> {image.size[0]}x{image.size[1]}  "
                  f"{size // 1024} KB  ({colors} colours)")
            return

    print(f"{path}  {os.path.getsize(path) // 1024} KB — STILL OVER 500 KB", file=sys.stderr)


if __name__ == "__main__":
    main()
