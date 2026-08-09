#!/usr/bin/env python3
"""Generates .github/social-preview.png, the card GitHub shows when the repo is linked.

GitHub renders the social preview at 1280x640 and crops it to varying aspect ratios depending
on where the link is unfurled, so everything that has to survive stays inside a wide margin.

    python3 docs/social-preview.py

Requires Pillow. Regenerate and commit the PNG whenever the tagline changes.
"""

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT = 1280, 640
MARGIN = 88

# GitHub's own dark canvas, so the card sits flush against the page around it.
BACKGROUND = "#0D1117"
ACCENT = "#1F6FEB"
HEADING = "#E6EDF3"
BODY = "#9198A1"
RULE = "#21262D"

FONT_DIR = "/System/Library/Fonts/Supplemental"
BOLD = f"{FONT_DIR}/Arial Bold.ttf"
REGULAR = f"{FONT_DIR}/Arial.ttf"

TAGLINE = [
    "An Android Ollama client for developers who want their",
    "local models to actually do things.",
]

POINTS = [
    "Asks /api/show what each model can do, instead of guessing from names.",
    "Reads and writes your GitHub — writes stay off until you allow them.",
    "API keys encrypted with an Android Keystore key that never leaves.",
]


def cloud(draw: ImageDraw.ImageDraw, x: float, y: float, scale: float) -> None:
    """A cirrus mark: three overlapping puffs on a flat base."""
    s = scale
    draw.ellipse([x + 4 * s, y + 26 * s, x + 46 * s, y + 68 * s], fill=ACCENT)
    draw.ellipse([x + 26 * s, y + 8 * s, x + 78 * s, y + 60 * s], fill=ACCENT)
    draw.ellipse([x + 58 * s, y + 26 * s, x + 100 * s, y + 68 * s], fill=ACCENT)
    draw.rectangle([x + 22 * s, y + 46 * s, x + 82 * s, y + 68 * s], fill=ACCENT)


def main() -> None:
    image = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw = ImageDraw.Draw(image)

    wordmark = ImageFont.truetype(BOLD, 78)
    tagline = ImageFont.truetype(BOLD, 40)
    point = ImageFont.truetype(REGULAR, 26)
    footer = ImageFont.truetype(REGULAR, 24)

    # A single accent bar carries the brand colour; a full gradient reads as a template.
    draw.rectangle([0, 0, 10, HEIGHT], fill=ACCENT)

    y = MARGIN
    # Drawn rather than set as ☁, because the text fonts here carry no emoji glyph and Pillow
    # would render a tofu box.
    cloud(draw, MARGIN, y + 22, scale=1.0)
    draw.text((MARGIN + 128, y), "Cirrus", font=wordmark, fill=HEADING)
    y += 118

    for line in TAGLINE:
        draw.text((MARGIN, y), line, font=tagline, fill=HEADING)
        y += 52

    y += 34
    draw.line([(MARGIN, y), (WIDTH - MARGIN, y)], fill=RULE, width=2)
    y += 40

    for text in POINTS:
        draw.ellipse([MARGIN + 2, y + 10, MARGIN + 12, y + 20], fill=ACCENT)
        draw.text((MARGIN + 32, y), text, font=point, fill=BODY)
        y += 46

    draw.text(
        (MARGIN, HEIGHT - MARGIN - 10),
        "github.com/klaibercore/cirrus  ·  Apache-2.0  ·  Kotlin · Jetpack Compose",
        font=footer,
        fill=BODY,
    )

    image.save(".github/social-preview.png", "PNG", optimize=True)
    print(f"wrote .github/social-preview.png ({WIDTH}x{HEIGHT})")


if __name__ == "__main__":
    main()
