"""Create unaltered, side-by-side screenshots from the same synthetic app fixture.

Source screenshots remain unchanged. Only metadata is removed from published PNGs;
labels and a neutral gutter sit outside each screenshot. No screenshot is redrawn.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SCREENS = ("home", "drawer", "history", "progress", "profile")
THEMES = ("light", "dark")


def pixel_hash(image: Image.Image) -> str:
    return hashlib.sha256(image.convert("RGBA").tobytes()).hexdigest()


def source(directory: Path, language: str, theme: str, screen: str) -> Path:
    return directory / f"warm-glass-{language}-{theme}-{screen}.png"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True)
    parser.add_argument("--after", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--language", choices=("ru", "kk", "en"), default="ru")
    parser.add_argument("--publish-reviewed", action="store_true")
    args = parser.parse_args()
    before = (ROOT / args.before).resolve()
    after = (ROOT / args.after).resolve()
    out = (ROOT / args.out).resolve()
    if not all(path.is_relative_to(ROOT / ".android") for path in (before, after)):
        raise ValueError("Use private source captures from .android")
    allowed = ROOT / ("docs/screenshots/warm-glass" if args.publish_reviewed else ".android")
    if not out.is_relative_to(allowed):
        raise ValueError("Output outside the approved review/publish directory")
    out.mkdir(parents=True, exist_ok=True)
    manifest = []
    for theme in THEMES:
        for screen in SCREENS:
            pair = []
            for label, directory in (("before", before), ("after", after)):
                path = source(directory, args.language, theme, screen)
                with Image.open(path) as image:
                    if image.convert("RGBA").getchannel("A").getextrema() != (255, 255):
                        raise ValueError("A gallery capture must contain the complete opaque app viewport")
                    pair.append(image.convert("RGB"))
            a, b = pair
            if a.size != b.size:
                raise ValueError(f"Mismatched geometry for {theme}/{screen}: {a.size} vs {b.size}")
            # Preserve every source pixel. Text is in a separate header/gutter.
            width, height = a.size
            header = max(48, round(width * .07))
            gutter = max(12, round(width * .02))
            canvas = Image.new("RGB", (2 * width + gutter, height + header), "#F7F2EA")
            canvas.paste(a, (0, header)); canvas.paste(b, (width + gutter, header))
            draw = ImageDraw.Draw(canvas)
            font = ImageFont.load_default(size=max(18, round(width * .028)))
            draw.text((16, header // 4), "BEFORE", fill="#332A24", font=font)
            draw.text((width + gutter + 16, header // 4), "AFTER", fill="#332A24", font=font)
            name = f"{args.language}-{theme}-{screen}-comparison.png"
            canvas.save(out / name)
            record = {"file": name, "screen": screen, "theme": theme,
                      "source_size_px": [width, height], "before_pixels_sha256": pixel_hash(a),
                      "after_pixels_sha256": pixel_hash(b), "source_pixels_modified": False}
            manifest.append(record)
            if args.publish_reviewed:
                for label, image in (("before", a), ("after", b)):
                    destination = out / f"{args.language}-{theme}-{screen}-{label}.png"
                    image.save(destination)
                    with Image.open(destination) as check:
                        if pixel_hash(check) != pixel_hash(image) or check.info:
                            raise ValueError("Publishing must preserve pixels and remove metadata")
    (out / "comparisons.json").write_text(json.dumps({"language": args.language,
        "synthetic_fixture": True, "published_after_review": args.publish_reviewed,
        "actual_talkback_verified": False, "comparisons": manifest}, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(manifest)} unchanged-pixel before/after comparisons")


if __name__ == "__main__":
    main()
