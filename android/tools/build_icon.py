"""Builds Emrys's launcher icon, themed icon, notification icon, brand mark and widget mark from the sigil.

The sigil is ``docs/design/emrys-sigil.json``: a 13x13 Matrix frame, one brightness (0..1) per cell, an E inside a
halo ring of dots whose middle bar ends in a half-lit spark. From it this writes, as Android vector drawables:

- the adaptive icon layers ``ic_launcher_background``, ``ic_launcher_foreground`` (also the splash icon) and
  ``ic_launcher_monochrome`` (the themed icon);
- ``ic_emrys``, the status-bar and notification icon: the sigil as a one-colour silhouette;
- ``emrys_sigil``, the brand mark beside the wordmark: one white dot per lit cell, tinted where it is used;
- ``emrys_head``, the mark in its own colours for the widgets and the widget picker;

plus ``build/icon-preview.svg`` for review.

Usage: python3 tools/build_icon.py [--check]  (from android/)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable"
SIGIL = ROOT.parent / "docs" / "design" / "emrys-sigil.json"
PREVIEW = ROOT / "build" / "icon-preview.svg"


def rrect(x: float, y: float, w: float, h: float, r: float) -> str:
    return (
        f"M{x + r:g},{y:g}h{w - 2 * r:g}a{r:g},{r:g} 0 0 1 {r:g},{r:g}v{h - 2 * r:g}a{r:g},{r:g} 0 0 1 {-r:g},{r:g}"
        f"h{-(w - 2 * r):g}a{r:g},{r:g} 0 0 1 {-r:g},{-r:g}v{-(h - 2 * r):g}a{r:g},{r:g} 0 0 1 {r:g},{-r:g}z"
    )


def circle(cx: float, cy: float, r: float) -> str:
    return f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1 0 {2 * r:g},0a{r:g},{r:g} 0 1 0 {-2 * r:g},0z"


# Noir, from docs/design/EMRYS_DESIGN.md §3.
NOIR = "#0A0A0C"
PORCELAIN = "#F3F2EE"
HALO = "#B7C2FF"
HALO_HI = "#CDD5FF"

FRAME: list[list[float]] = json.loads(SIGIL.read_text(encoding="utf-8"))["frame"]
SIZE = len(FRAME)
CELLS = [(row, col, b) for row, line in enumerate(FRAME) for col, b in enumerate(line) if b > 0]
# The E is fully lit, the ring of dots around it is dimmer and the spark at the end of the middle bar is half-lit.
E = [(r, c) for r, c, b in CELLS if b == 1]
RING = [(r, c) for r, c, b in CELLS if b < 0.5]
SPARK = [(r, c) for r, c, b in CELLS if 0.5 <= b < 1]
if not (E and RING and len(SPARK) == 1):
    raise SystemExit("the sigil is no longer an E, a ring and one spark; redraw the icon for it")


def cell_centre(row: int, col: int, cx: float, cy: float, pitch: float) -> tuple[float, float]:
    """The centre of a Matrix cell in a sigil of [pitch] per cell centred on ([cx], [cy])."""
    return round(cx + (col - (SIZE - 1) / 2) * pitch, 3), round(cy + (row - (SIZE - 1) / 2) * pitch, 3)


def dots(cells: list[tuple[int, int]], cx: float, cy: float, pitch: float, radius: float) -> str:
    return "".join(circle(*cell_centre(r, c, cx, cy, pitch), round(radius, 3)) for r, c in cells)


def bars(cx: float, cy: float, pitch: float, width: float) -> str:
    """The E's lit cells fused into its strokes: each horizontal or vertical run of two or more is one rounded bar."""
    lit = set(E)
    runs = []
    for r in range(SIZE):
        c = 0
        while c < SIZE:
            if (r, c) in lit and (r, c + 1) in lit:
                start = c
                while (r, c + 1) in lit:
                    c += 1
                runs.append(((r, start), (r, c)))
            c += 1
    for c in range(SIZE):
        r = 0
        while r < SIZE:
            if (r, c) in lit and (r + 1, c) in lit:
                start = r
                while (r + 1, c) in lit:
                    r += 1
                runs.append(((start, c), (r, c)))
            r += 1
    r = round(width / 2, 3)
    out = []
    for a, b in runs:
        (x1, y1), (x2, y2) = cell_centre(*a, cx, cy, pitch), cell_centre(*b, cx, cy, pitch)
        if y1 == y2:
            out.append(f"M{x1:g},{y1 - r:g}h{round(x2 - x1, 3):g}a{r:g},{r:g} 0 0 1 0,{2 * r:g}h{round(x1 - x2, 3):g}a{r:g},{r:g} 0 0 1 0,{-2 * r:g}z")
        else:
            out.append(f"M{x1 - r:g},{y1:g}a{r:g},{r:g} 0 0 1 {2 * r:g},0v{round(y2 - y1, 3):g}a{r:g},{r:g} 0 0 1 {-2 * r:g},0z")
    return "".join(out)


def glow(x: float, y: float, r: float, color: str, alpha: float) -> dict:
    """A soft light: a disc whose colour fades from [alpha] at its centre to nothing at its edge."""
    return {"d": circle(x, y, r), "fill": ("radial", x, y, r, [(0, color, alpha), (0.5, color, round(alpha * 0.3, 3)), (1, color, 0)])}


# The sigil on the icon: 13 cells of 3.75 over the 108 grid, so its ring of dots spans about 48 of the 72 a launcher
# shows, well inside the 66 every mask keeps. The E is porcelain in the halo's glow, the ring and the spark are halo.
# The E's dots are larger than the ring's, so the letter holds together at 48 px while the dots still read as a
# Matrix at full size. A faint hairline circle behind it echoes the stage's floor rings.
ICON_PITCH = 3.75
ICON_E = 0.48 * ICON_PITCH
ICON_RING = 0.36 * ICON_PITCH
SPARK_AT = cell_centre(*SPARK[0], 54, 54, ICON_PITCH)

BACKGROUND = [
    {"d": "M0,0h108v108h-108z", "fill": ("radial", 54, 40, 78, [(0, "#1C1C22", 1), (1, NOIR, 1)])},
    {"d": circle(54, 54, 30.5), "stroke": (PORCELAIN, 0.06), "width": 0.5},
]

FOREGROUND = [
    glow(54, 54, 36, HALO, 0.3),
    {"d": dots(RING, 54, 54, ICON_PITCH, ICON_RING), "fill": (HALO, 0.75)},
    glow(*SPARK_AT, 6, HALO, 0.55),
    # The Matrix's glow on its brightest cells: a soft halo round each of the E's dots.
    *(glow(*cell_centre(r, c, 54, 54, ICON_PITCH), 1.25 * ICON_PITCH, HALO, 0.3) for r, c in E),
    {"d": dots(E, 54, 54, ICON_PITCH, ICON_E), "fill": (PORCELAIN, 1)},
    {"d": dots(SPARK, 54, 54, ICON_PITCH, ICON_E), "fill": (HALO_HI, 1)},
]

# The themed icon: the launcher tints it one colour, so the ring keeps its dimmer weight through alpha.
MONOCHROME = [
    {"d": dots(RING, 54, 54, ICON_PITCH, ICON_RING), "fill": ("#FFFFFF", 0.6)},
    {"d": dots(E + SPARK, 54, 54, ICON_PITCH, ICON_E), "fill": ("#FFFFFF", 1)},
]

# The status bar (22 dp) and the notification header (18 dp) draw only alpha, at sizes where the E's 21 separate dots
# would blur into stripes. The silhouette keeps the sigil's grid but fuses the E into rounded strokes, sets the spark
# apart as one dot and keeps the ring as dots, which read as the sigil's halo ring down to 24 px.
STATUS_PITCH = 22 / SIZE
STATUS = [
    {
        "d": bars(12, 12, STATUS_PITCH, 0.86 * STATUS_PITCH) + dots(SPARK + RING, 12, 12, STATUS_PITCH, 0.42 * STATUS_PITCH),
        "fill": ("#FFFFFF", 1),
    },
]

# The widget mark: the sigil in its own colours at 24 dp, for the widgets' search bars and the widget picker.
MARK_PITCH = 24 / SIZE
MARK = [
    {"d": dots(RING, 12, 12, MARK_PITCH, 0.4 * MARK_PITCH), "fill": (HALO, 0.8)},
    {"d": dots(E, 12, 12, MARK_PITCH, 0.47 * MARK_PITCH), "fill": (PORCELAIN, 1)},
    {"d": dots(SPARK, 12, 12, MARK_PITCH, 0.47 * MARK_PITCH), "fill": (HALO_HI, 1)},
]


def argb(color: str, alpha: float) -> str:
    return f"#{round(alpha * 255):02X}{color[1:].upper()}"


def vector_path(shape: dict) -> str:
    attrs = [f'android:pathData="{shape["d"]}"']
    inner = ""
    if "stroke" in shape:
        color, alpha = shape["stroke"]
        attrs += [f'android:strokeColor="{argb(color, alpha)}"', f'android:strokeWidth="{shape["width"]:g}"']
    fill = shape.get("fill")
    if isinstance(fill, tuple) and fill[0] in ("linear", "radial"):
        if fill[0] == "linear":
            _, x1, y1, x2, y2, stops = fill
            geometry = f'android:type="linear" android:startX="{x1:g}" android:startY="{y1:g}" android:endX="{x2:g}" android:endY="{y2:g}"'
        else:
            _, cx, cy, r, stops = fill
            geometry = f'android:type="radial" android:centerX="{cx:g}" android:centerY="{cy:g}" android:gradientRadius="{r:g}"'
        items = "".join(f'\n                <item android:offset="{o:g}" android:color="{argb(c, a)}" />' for o, c, a in stops)
        inner = (
            '\n        <aapt:attr name="android:fillColor">'
            f"\n            <gradient {geometry}>{items}\n            </gradient>"
            "\n        </aapt:attr>\n    "
        )
    elif fill:
        color, alpha = fill
        attrs.append(f'android:fillColor="{argb(color, alpha)}"')
    head = "    <path\n        " + "\n        ".join(attrs)
    return head + (f">{inner}</path>" if inner else " />")


def vector(shapes: list[dict], size: float, viewport: float) -> str:
    body = "\n".join(vector_path(s) for s in shapes)
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by tools/build_icon.py from docs/design/emrys-sigil.json; edit the shapes there. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        f'    android:width="{size:g}dp" android:height="{size:g}dp"\n'
        f'    android:viewportWidth="{viewport:g}" android:viewportHeight="{viewport:g}">\n'
        f"{body}\n</vector>\n"
    )


def brand_mark() -> str:
    """The brand mark as the stage draws the frame: one white dot per lit cell, alpha from its brightness."""
    pitch = 24 / SIZE
    r = 0.45 * pitch

    def dot(row: int, col: int) -> str:
        x, y = (col + 0.5) * pitch, (row + 0.5) * pitch
        return f"M{x - r:.3f},{y:.3f}a{r:.3f},{r:.3f} 0,1 0,{2 * r:.3f},0a{r:.3f},{r:.3f} 0,1 0,{-2 * r:.3f},0"

    paths = [
        f'    <path android:fillColor="#FFFFFFFF" android:fillAlpha="{level:g}" android:pathData="'
        + "".join(dot(row, col) for row, col, b in CELLS if b == level)
        + '" />'
        for level in sorted({b for _, _, b in CELLS})
    ]
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- The Emrys sigil from docs/design/emrys-sigil.json: one dot per lit Matrix cell, alpha from its brightness."
        " Tint it where it is used. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
        + "\n".join(paths)
        + "\n</vector>\n"
    )


def svg_paint(fill: tuple, ident: str, defs: list[str]) -> tuple[str, str]:
    if fill[0] == "linear":
        _, x1, y1, x2, y2, stops = fill
        tag = f'<linearGradient id="{ident}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" gradientUnits="userSpaceOnUse">'
        end = "</linearGradient>"
    elif fill[0] == "radial":
        _, cx, cy, r, stops = fill
        tag = f'<radialGradient id="{ident}" cx="{cx}" cy="{cy}" r="{r}" gradientUnits="userSpaceOnUse">'
        end = "</radialGradient>"
    else:
        return fill[0], f"{fill[1]:g}"
    defs.append(tag + "".join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in stops) + end)
    return f"url(#{ident})", "1"


def preview() -> str:
    defs: list[str] = []
    out = []
    for i, s in enumerate(BACKGROUND + FOREGROUND):
        if "stroke" in s:
            color, alpha = s["stroke"]
            out.append(f'<path d="{s["d"]}" fill="none" stroke="{color}" stroke-opacity="{alpha}" stroke-width="{s["width"]}"/>')
        else:
            paint, opacity = svg_paint(s["fill"], f"g{i}", defs)
            out.append(f'<path d="{s["d"]}" fill="{paint}" fill-opacity="{opacity}"/>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><defs>{"".join(defs)}</defs>{"".join(out)}</svg>\n'


OUTPUTS = {
    "ic_launcher_background.xml": vector(BACKGROUND, 108, 108),
    "ic_launcher_foreground.xml": vector(FOREGROUND, 108, 108),
    "ic_launcher_monochrome.xml": vector(MONOCHROME, 108, 108),
    "ic_emrys.xml": vector(STATUS, 24, 24),
    "emrys_sigil.xml": brand_mark(),
    "emrys_head.xml": vector(MARK, 24, 24),
}


def main() -> None:
    stale = []
    for name, text in OUTPUTS.items():
        path = DRAWABLE / name
        if "--check" in sys.argv:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                stale.append(name)
            continue
        path.write_text(text, encoding="utf-8")
    if stale:
        raise SystemExit(f"stale icon drawables: {', '.join(stale)}; run tools/build_icon.py")
    if "--check" not in sys.argv:
        PREVIEW.parent.mkdir(parents=True, exist_ok=True)
        PREVIEW.write_text(preview(), encoding="utf-8")
        print(f"wrote {len(OUTPUTS)} drawables and {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
