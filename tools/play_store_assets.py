#!/usr/bin/env python3
"""Generate and validate Google Play listing assets for The Aive."""
from __future__ import annotations

import argparse
import json
import math
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

NAVY = (13, 16, 38)
CORAL = (241, 90, 67)
ORANGE = (255, 157, 46)
CREAM = (245, 241, 232)
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
SAFE_ZONE_CENTER = 33.0
SAFE_ZONE_RADIUS = 33.0
PATH_TOKEN_RE = re.compile(
    r"[A-Za-z]|[-+]?(?:[0-9]+(?:[.][0-9]*)?|[.][0-9]+)(?:[eE][-+]?[0-9]+)?"
)
SAFE_PATH_COMMAND_ARITY = {"M": 2, "L": 2, "C": 6, "Q": 4, "Z": 0}
THEMED_PREVIEW_NAMES = (
    "themed-icon-light.png",
    "themed-icon-dark.png",
    "themed-icon-warm.png",
)


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


def vector_safe_group(root: ET.Element, name: str) -> ET.Element:
    children = list(root)
    assert len(children) == 1 and children[0].tag == "group", (
        f"{name} must contain exactly one top-level safe-zone group and no drawable siblings"
    )
    group = children[0]
    assert list(group), f"{name} contains no artwork"
    assert all(child.tag == "path" for child in group), (
        f"{name} safe-zone group may contain paths only; nested groups/clips can bypass proof"
    )
    return group


def flatten_path(
    segments: list[tuple[str, list[float]]],
    curve_steps: int = 24,
) -> list[tuple[list[tuple[float, float]], bool]]:
    subpaths: list[tuple[list[tuple[float, float]], bool]] = []
    points: list[tuple[float, float]] = []
    current = (0.0, 0.0)
    start = (0.0, 0.0)

    def flush(closed: bool = False) -> None:
        nonlocal points
        if points:
            subpaths.append((points, closed))
            points = []

    for command, operands in segments:
        if command == "M":
            flush()
            current = (operands[0], operands[1])
            start = current
            points = [current]
        elif command == "L":
            current = (operands[0], operands[1])
            points.append(current)
        elif command == "C":
            x0, y0 = current
            x1, y1, x2, y2, x3, y3 = operands
            for step in range(1, curve_steps + 1):
                t = step / curve_steps
                mt = 1.0 - t
                points.append(
                    (
                        mt**3 * x0 + 3 * mt**2 * t * x1 + 3 * mt * t**2 * x2 + t**3 * x3,
                        mt**3 * y0 + 3 * mt**2 * t * y1 + 3 * mt * t**2 * y2 + t**3 * y3,
                    )
                )
            current = (x3, y3)
        elif command == "Q":
            x0, y0 = current
            x1, y1, x2, y2 = operands
            for step in range(1, curve_steps + 1):
                t = step / curve_steps
                mt = 1.0 - t
                points.append(
                    (
                        mt**2 * x0 + 2 * mt * t * x1 + t**2 * x2,
                        mt**2 * y0 + 2 * mt * t * y1 + t**2 * y2,
                    )
                )
            current = (x2, y2)
        elif command == "Z":
            if points and points[-1] != start:
                points.append(start)
            current = start
            flush(closed=True)
        else:
            raise AssertionError(f"Unsupported path command {command!r}")

    flush()
    return subpaths


def rasterize_monochrome_vector(root: ET.Element, size: int = 512) -> Image.Image:
    group = vector_safe_group(root, "ic_launcher_monochrome.xml")
    viewport = float(root.attrib[ANDROID_NS + "viewportWidth"])
    translate_x = float(group.attrib.get(ANDROID_NS + "translateX", "0"))
    translate_y = float(group.attrib.get(ANDROID_NS + "translateY", "0"))
    supersample = 4
    raster_size = size * supersample
    scale = raster_size / viewport
    mask = Image.new("L", (raster_size, raster_size), 0)
    draw = ImageDraw.Draw(mask)

    def pixel_point(point: tuple[float, float]) -> tuple[float, float]:
        return (
            (point[0] + translate_x) * scale,
            (point[1] + translate_y) * scale,
        )

    for index, path in enumerate(group.findall("path")):
        segments = parse_safe_path(
            path.attrib.get(ANDROID_NS + "pathData", ""),
            "ic_launcher_monochrome.xml",
            index,
        )
        subpaths = flatten_path(segments)
        fill = path.attrib.get(ANDROID_NS + "fillColor")
        fill_alpha = float(path.attrib.get(ANDROID_NS + "fillAlpha", "1"))
        stroke = path.attrib.get(ANDROID_NS + "strokeColor")
        stroke_alpha = float(path.attrib.get(ANDROID_NS + "strokeAlpha", "1"))
        stroke_width = float(path.attrib.get(ANDROID_NS + "strokeWidth", "0"))

        if fill == "#FFFFFFFF" and fill_alpha > 0:
            value = round(255 * fill_alpha)
            for points, closed in subpaths:
                if closed and len(points) >= 3:
                    draw.polygon([pixel_point(point) for point in points], fill=value)

        if stroke == "#FFFFFFFF" and stroke_alpha > 0 and stroke_width > 0:
            value = round(255 * stroke_alpha)
            width = max(1, round(stroke_width * scale))
            radius = width / 2.0
            for points, closed in subpaths:
                rendered = [pixel_point(point) for point in points]
                if len(rendered) < 2:
                    continue
                draw.line(rendered, fill=value, width=width, joint="curve")
                if not closed and path.attrib.get(ANDROID_NS + "strokeLineCap") == "round":
                    for x, y in (rendered[0], rendered[-1]):
                        draw.ellipse((x-radius, y-radius, x+radius, y+radius), fill=value)

    return mask.resize((size, size), Image.Resampling.LANCZOS)


def generate_themed_previews(repo: Path, output_dir: Path) -> None:
    monochrome = ET.parse(
        repo / "androidApp/src/main/res/drawable/ic_launcher_monochrome.xml"
    ).getroot()
    mask = rasterize_monochrome_vector(monochrome)
    assert mask.getbbox() is not None, "monochrome resource rasterized to an empty mask"

    themes = {
        "themed-icon-light.png": ((239, 228, 255), (82, 55, 110)),
        "themed-icon-dark.png": ((37, 30, 48), (219, 190, 255)),
        "themed-icon-warm.png": ((255, 228, 202), (112, 55, 20)),
    }
    for name, (background, foreground) in themes.items():
        image = Image.new("RGB", (512, 512), background)
        foreground_layer = Image.new("RGB", image.size, foreground)
        image.paste(foreground_layer, (0, 0), mask)
        image.save(output_dir / name, optimize=True)


def normalize_screenshot(source: Path, output: Path) -> None:
    image = Image.open(source).convert("RGB")
    image.save(output, optimize=True)


def parse_safe_path(path_data: str, name: str, index: int) -> list[tuple[str, list[float]]]:
    tokens = PATH_TOKEN_RE.findall(path_data)
    residual = PATH_TOKEN_RE.sub("", path_data)
    residual = re.sub(r"[\s,]+", "", residual)
    assert not residual, f"{name} path {index} contains unsupported path syntax: {residual!r}"

    segments: list[tuple[str, list[float]]] = []
    cursor = 0
    while cursor < len(tokens):
        command = tokens[cursor]
        assert command in SAFE_PATH_COMMAND_ARITY, (
            f"{name} path {index} uses unsupported command {command!r}; "
            "safe-zone proof permits absolute M/L/C/Q/Z only"
        )
        cursor += 1
        arity = SAFE_PATH_COMMAND_ARITY[command]
        operands = tokens[cursor:cursor + arity]
        assert len(operands) == arity, f"{name} path {index} has incomplete {command} operands"
        assert all(not token.isalpha() for token in operands), (
            f"{name} path {index} must spell out every path command explicitly"
        )
        cursor += arity
        segments.append((command, [float(token) for token in operands]))

    assert segments, f"{name} path {index} contains no geometry"
    return segments


def validate_vector_safe_zone(root: ET.Element, name: str) -> None:
    group = vector_safe_group(root, name)
    assert group.attrib[ANDROID_NS + "translateX"] == "21"
    assert group.attrib[ANDROID_NS + "translateY"] == "21"

    for attribute in ("scaleX", "scaleY", "rotation", "pivotX", "pivotY"):
        assert ANDROID_NS + attribute not in group.attrib, (
            f"{name} safe-zone group must not apply {attribute}; "
            "critical artwork coordinates are validated directly in the circular 66dp box"
        )

    for index, path in enumerate(group.findall("path")):
        path_data = path.attrib.get(ANDROID_NS + "pathData", "")
        assert path_data, f"{name} path {index} is missing pathData"
        segments = parse_safe_path(path_data, name, index)

        stroke_color = path.attrib.get(ANDROID_NS + "strokeColor")
        stroke_alpha = float(path.attrib.get(ANDROID_NS + "strokeAlpha", "1"))
        stroke_width = float(path.attrib.get(ANDROID_NS + "strokeWidth", "0"))
        visible_stroke = (
            stroke_color not in (None, "@android:color/transparent")
            and stroke_alpha > 0
            and stroke_width > 0
        )
        if visible_stroke:
            assert path.attrib.get(ANDROID_NS + "strokeLineJoin") == "round", (
                f"{name} path {index} must use round joins for circular safe-zone proof"
            )
            assert path.attrib.get(ANDROID_NS + "strokeLineCap") == "round", (
                f"{name} path {index} must use round caps for circular safe-zone proof"
            )

        margin = stroke_width / 2.0 if visible_stroke else 0.0
        allowed_radius = SAFE_ZONE_RADIUS - margin

        # A circle is convex. Lines and quadratic/cubic Béziers remain inside the convex hull
        # of their endpoints/control points, so proving every operand point is inside the
        # stroke-padded circle proves the complete rendered geometry is inside it.
        for command, operands in segments:
            if command == "Z":
                continue
            for x, y in zip(operands[0::2], operands[1::2]):
                radius = math.hypot(x - SAFE_ZONE_CENTER, y - SAFE_ZONE_CENTER)
                assert radius <= allowed_radius + 1e-6, (
                    f"{name} path {index} {command} point ({x},{y}) is {radius:.3f}dp "
                    f"from center; maximum is {allowed_radius:.3f}dp"
                )


def validate_monochrome_vector(root: ET.Element) -> None:
    allowed = {"#FFFFFFFF", "@android:color/transparent"}
    group = vector_safe_group(root, "ic_launcher_monochrome.xml")
    visible_paint = False

    for index, path in enumerate(group.findall("path")):
        fill = path.attrib.get(ANDROID_NS + "fillColor")
        stroke = path.attrib.get(ANDROID_NS + "strokeColor")
        for attribute, value in (("fillColor", fill), ("strokeColor", stroke)):
            if value is not None:
                assert value in allowed, (
                    f"ic_launcher_monochrome.xml path {index} uses non-monochrome "
                    f"{attribute}={value!r}"
                )

        fill_alpha = float(path.attrib.get(ANDROID_NS + "fillAlpha", "1"))
        stroke_alpha = float(path.attrib.get(ANDROID_NS + "strokeAlpha", "1"))
        stroke_width = float(path.attrib.get(ANDROID_NS + "strokeWidth", "0"))
        if fill == "#FFFFFFFF" and fill_alpha > 0:
            visible_paint = True
        if stroke == "#FFFFFFFF" and stroke_alpha > 0 and stroke_width > 0:
            visible_paint = True

    assert visible_paint, "ic_launcher_monochrome.xml must contain visible nontransparent paint"


def validate_launcher_manifest(repo: Path) -> None:
    manifest = ET.parse(repo / "androidApp/src/main/AndroidManifest.xml").getroot()
    application = manifest.find("application")
    assert application is not None, "AndroidManifest.xml has no <application>"
    assert application.attrib.get(ANDROID_NS + "icon") == "@mipmap/ic_launcher"
    assert application.attrib.get(ANDROID_NS + "roundIcon") == "@mipmap/ic_launcher_round"


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

    vector_roots: dict[str, ET.Element] = {}
    for name in ("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml"):
        path = repo / "androidApp/src/main/res/drawable" / name
        root = ET.parse(path).getroot()
        vector_roots[name] = root
        assert root.attrib[ANDROID_NS + "width"] == "108dp"
        assert root.attrib[ANDROID_NS + "height"] == "108dp"
        assert root.attrib[ANDROID_NS + "viewportWidth"] == "108"
        assert root.attrib[ANDROID_NS + "viewportHeight"] == "108"
        validate_vector_safe_zone(root, name)

    validate_monochrome_vector(vector_roots["ic_launcher_monochrome.xml"])
    validate_launcher_manifest(repo)

    foreground_text = (repo / "androidApp/src/main/res/drawable/ic_launcher_foreground.xml").read_text()
    assert "M0,0h108v108" not in foreground_text, "foreground must not paint the background layer"


def validate_play_assets(repo: Path, output_dir: Path) -> None:
    icon = Image.open(output_dir / "play-icon-512.png")
    assert icon.size == (512, 512)
    assert icon.mode == "RGBA"
    assert (output_dir / "play-icon-512.png").stat().st_size <= 1024 * 1024

    feature = Image.open(output_dir / "feature-graphic-1024x500.png")
    assert feature.size == (1024, 500)
    assert feature.mode == "RGB"

    monochrome_root = ET.parse(
        repo / "androidApp/src/main/res/drawable/ic_launcher_monochrome.xml"
    ).getroot()
    monochrome_mask = rasterize_monochrome_vector(monochrome_root)
    themes = {
        "themed-icon-light.png": ((239, 228, 255), (82, 55, 110)),
        "themed-icon-dark.png": ((37, 30, 48), (219, 190, 255)),
        "themed-icon-warm.png": ((255, 228, 202), (112, 55, 20)),
    }
    for name, (background, foreground) in themes.items():
        path = output_dir / name
        image = Image.open(path)
        assert image.size == (512, 512), f"{path.name} must be 512x512"
        assert image.mode == "RGB", f"{path.name} must be 24-bit RGB"
        expected = Image.new("RGB", image.size, background)
        expected.paste(Image.new("RGB", image.size, foreground), (0, 0), monochrome_mask)
        assert list(image.getdata()) == list(expected.getdata()), (
            f"{path.name} must be rendered from ic_launcher_monochrome.xml"
        )

    screenshots = sorted(output_dir.glob("screenshot-*.png"))
    assert len(screenshots) == 4, "Final Google Play proof requires exactly four production screenshots"
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


def write_validation_report(repo: Path, output_dir: Path) -> None:
    screenshots = []
    for path in sorted(output_dir.glob("screenshot-*.png")):
        with Image.open(path) as image:
            screenshots.append(
                {
                    "file": path.name,
                    "width": image.width,
                    "height": image.height,
                    "mode": image.mode,
                }
            )

    themed_previews = []
    for name in THEMED_PREVIEW_NAMES:
        path = output_dir / name
        with Image.open(path) as image:
            themed_previews.append(
                {
                    "file": name,
                    "width": image.width,
                    "height": image.height,
                    "mode": image.mode,
                }
            )

    report = {
        "adaptiveIcon": {
            "viewportDp": [108, 108],
            "safeZoneDp": {"centerX": 54, "centerY": 54, "radius": SAFE_ZONE_RADIUS},
            "localSafeZoneCenterDp": [SAFE_ZONE_CENTER, SAFE_ZONE_CENTER],
            "localSafeZoneRadiusDp": SAFE_ZONE_RADIUS,
            "foreground": "@drawable/ic_launcher_foreground",
            "monochrome": "@drawable/ic_launcher_monochrome",
            "manifestIcon": "@mipmap/ic_launcher",
            "manifestRoundIcon": "@mipmap/ic_launcher_round",
            "pathDataValidatedInsideCircularSafeZone": True,
            "monochromeSingleColorValidated": True,
            "themedPreviewsDerivedFromMonochromeResource": True,
        },
        "googlePlay": {
            "icon": {"file": "play-icon-512.png", "width": 512, "height": 512, "mode": "RGBA"},
            "featureGraphic": {
                "file": "feature-graphic-1024x500.png",
                "width": 1024,
                "height": 500,
                "mode": "RGB",
            },
            "themedPreviews": themed_previews,
            "screenshots": screenshots,
        },
    }
    (output_dir / "validation-report.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--screenshot", action="append", type=Path, default=[])
    args = parser.parse_args()

    output = args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    for stale in output.glob("screenshot-*.png"):
        stale.unlink()
    validate_launcher_resources(args.repo)
    generate_play_icon(output / "play-icon-512.png")
    generate_feature_graphic(output / "feature-graphic-1024x500.png")
    generate_themed_previews(args.repo, output)

    for index, source in enumerate(args.screenshot, start=1):
        normalize_screenshot(source, output / f"screenshot-{index:02d}.png")

    validate_play_assets(args.repo, output)
    write_validation_report(args.repo, output)
    print(f"Validated Google Play asset set in {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
