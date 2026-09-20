#!/usr/bin/env python3
"""Generate and validate Google Play listing assets for The Aive."""
from __future__ import annotations

import argparse
import math
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

NAVY = (13, 16, 38)
CORAL = (241, 90, 67)
ORANGE = (255, 157, 46)
CREAM = (245, 241, 232)
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        Path("/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
    ]
    for candidate in candidates:
        if candidate.is_file():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def draw_mark(draw: ImageDraw.ImageDraw, cx: float, cy: float, scale: float, monochrome: tuple[int, int, int] | None = None) -> None:
    main = monochrome or CORAL
    accent = monochrome or ORANGE
    dark = monochrome or NAVY
    r = 92 * scale
    draw.ellipse((cx-r, cy-r, cx+r, cy+r), fill=main)

    if monochrome is None:
        er = 13 * scale
        for ex in (cx-31*scale, cx+31*scale):
            draw.ellipse((ex-er, cy-23*scale-er, ex+er, cy-23*scale+er), fill=dark)
        draw.arc((cx-48*scale, cy-6*scale, cx+48*scale, cy+62*scale), 20, 160, fill=dark, width=max(2, int(10*scale)))

    nodes = [
        (-137,-95), (-154,-8), (-136,91),
        (137,-100), (156,-12), (137,94),
    ]
    starts = [
        (-66,-66), (-78,-8), (-65,62),
        (66,-66), (78,-8), (65,62),
    ]
    for (sx, sy), (nx, ny) in zip(starts, nodes):
        draw.line((cx+sx*scale, cy+sy*scale, cx+nx*scale, cy+ny*scale), fill=main, width=max(2, int(10*scale)))
        nr = 19 * scale
        draw.ellipse((cx+nx*scale-nr, cy+ny*scale-nr, cx+nx*scale+nr, cy+ny*scale+nr), fill=accent)


def generate_play_icon(output: Path) -> None:
    image = Image.new("RGBA", (512, 512), NAVY + (255,))
    draw = ImageDraw.Draw(image)
    draw_mark(draw, 256, 256, 1.02)
    image.save(output, optimize=True)


def generate_feature_graphic(output: Path) -> None:
    image = Image.new("RGB", (1024, 500), NAVY)
    draw = ImageDraw.Draw(image)

    # Quiet network field at the edges; core copy and focal mark stay centered.
    edge_nodes = [(55,90),(110,390),(935,95),(970,390),(105,235),(920,265)]
    for x, y in edge_nodes:
        draw.line((x, y, 280 if x < 512 else 744, 250), fill=(69, 48, 68), width=4)
        draw.ellipse((x-14,y-14,x+14,y+14), fill=ORANGE if y % 2 else CORAL)

    draw_mark(draw, 370, 250, 0.62)

    title = font(66, bold=True)
    subtitle = font(26)
    draw.text((515, 178), "The Aive", font=title, fill=CREAM)
    draw.text((519, 270), "Agentic workflow orchestration", font=subtitle, fill=(218, 211, 202))
    image.save(output, optimize=True)


def generate_themed_previews(output_dir: Path) -> None:
    themes = {
        "themed-icon-light.png": ((239, 228, 255), (82, 55, 110)),
        "themed-icon-dark.png": ((37, 30, 48), (219, 190, 255)),
        "themed-icon-warm.png": ((255, 228, 202), (112, 55, 20)),
    }
    for name, (background, foreground) in themes.items():
        image = Image.new("RGB", (512, 512), background)
        draw = ImageDraw.Draw(image)
        draw_mark(draw, 256, 256, 0.92, monochrome=foreground)
        image.save(output_dir / name, optimize=True)


def normalize_screenshot(source: Path, output: Path) -> None:
    image = Image.open(source).convert("RGB")
    image.save(output, optimize=True)


def validate_launcher_resources(repo: Path) -> None:
    adaptive_paths = [
        repo / "androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
        repo / "androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
    ]
    for path in adaptive_paths:
        root = ET.parse(path).getroot()
        refs = {child.tag: child.attrib.get(ANDROID_NS + "drawable") for child in root}
        assert refs.get("background") == "@color/ic_launcher_background", refs
        assert refs.get("foreground") == "@drawable/ic_launcher_foreground", refs
        assert refs.get("monochrome") == "@drawable/ic_launcher_monochrome", refs

    for name in ("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml"):
        path = repo / "androidApp/src/main/res/drawable" / name
        root = ET.parse(path).getroot()
        assert root.attrib[ANDROID_NS + "width"] == "108dp"
        assert root.attrib[ANDROID_NS + "height"] == "108dp"
        groups = root.findall("group")
        assert len(groups) == 1, f"{name} must use exactly one safe-zone group"
        group = groups[0]
        assert group.attrib[ANDROID_NS + "translateX"] == "21"
        assert group.attrib[ANDROID_NS + "translateY"] == "21"
        assert group.findall("path"), f"{name} contains no artwork"

    foreground_text = (repo / "androidApp/src/main/res/drawable/ic_launcher_foreground.xml").read_text()
    assert "M0,0h108v108" not in foreground_text, "foreground must not paint the background layer"


def validate_play_assets(output_dir: Path) -> None:
    icon = Image.open(output_dir / "play-icon-512.png")
    assert icon.size == (512, 512)
    assert icon.mode == "RGBA"
    assert (output_dir / "play-icon-512.png").stat().st_size <= 1024 * 1024

    feature = Image.open(output_dir / "feature-graphic-1024x500.png")
    assert feature.size == (1024, 500)
    assert feature.mode == "RGB"

    screenshots = sorted(output_dir.glob("screenshot-*.png"))
    assert len(screenshots) >= 2, "At least two screenshots are required by Google Play"
    for path in screenshots:
        image = Image.open(path)
        assert image.mode == "RGB", f"{path.name} must be 24-bit RGB"
        low, high = sorted(image.size)
        assert 320 <= low <= 3840
        assert high <= 3840
        assert high <= low * 2

    # Four >=1080px screenshots satisfy Google's current recommendation eligibility.
    recommended = [p for p in screenshots if min(Image.open(p).size) >= 1080]
    assert len(recommended) >= 4, "Generate four >=1080px screenshots for recommended-app surfaces"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--screenshot", action="append", type=Path, default=[])
    args = parser.parse_args()

    output = args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    validate_launcher_resources(args.repo)
    generate_play_icon(output / "play-icon-512.png")
    generate_feature_graphic(output / "feature-graphic-1024x500.png")
    generate_themed_previews(output)

    for index, source in enumerate(args.screenshot, start=1):
        normalize_screenshot(source, output / f"screenshot-{index:02d}.png")

    validate_play_assets(output)
    print(f"Validated Google Play asset set in {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
