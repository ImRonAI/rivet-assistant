"""Builds Emrys's launcher icon, monochrome icon, notification icon and head mark from one shape list.

Writes the adaptive icon layers (``ic_launcher_background``, ``ic_launcher_foreground``,
``ic_launcher_monochrome``), the status-bar icon ``ic_emrys``, and ``emrys_head`` (the full-colour head used in
the app and the widget) as Android vector drawables, plus ``icon-preview.svg`` for review.

Usage: python3 tools/build_icon.py [--check]  (from android/)
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable"
PREVIEW = ROOT / "build" / "icon-preview.svg"


def rrect(x: float, y: float, w: float, h: float, r: float) -> str:
    return (
        f"M{x + r:g},{y:g}h{w - 2 * r:g}a{r:g},{r:g} 0 0 1 {r:g},{r:g}v{h - 2 * r:g}a{r:g},{r:g} 0 0 1 {-r:g},{r:g}"
        f"h{-(w - 2 * r):g}a{r:g},{r:g} 0 0 1 {-r:g},{-r:g}v{-(h - 2 * r):g}a{r:g},{r:g} 0 0 1 {r:g},{-r:g}z"
    )


def circle(cx: float, cy: float, r: float) -> str:
    return f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1 0 {2 * r:g},0a{r:g},{r:g} 0 1 0 {-2 * r:g},0z"


def ellipse(cx: float, cy: float, rx: float, ry: float) -> str:
    return f"M{cx - rx:g},{cy:g}a{rx:g},{ry:g} 0 1 0 {2 * rx:g},0a{rx:g},{ry:g} 0 1 0 {-2 * rx:g},0z"


# Paints: a colour "#RRGGBB" with an alpha, or a gradient ("linear", x1, y1, x2, y2, stops) /
# ("radial", cx, cy, r, stops) with stops [(offset, "#RRGGBB", alpha)].
SHELL = ("linear", 0, 35, 0, 73, [(0, "#F6F8F9", 1), (1, "#CDD3D9", 1)])
EAR = ("linear", 0, 46, 0, 62, [(0, "#FF7A45", 1), (1, "#E5480F", 1)])
LED_GLOW = ("radial", 54, 26, 8, [(0, "#7DF3FF", 0.55), (1, "#7DF3FF", 0)])

BACKGROUND = [
    {"d": "M0,0h108v108h-108z", "fill": ("radial", 54, 48, 66, [(0, "#222A34", 1), (1, "#0B0E12", 1)])},
    {"d": "M0,0h108v108h-108z", "fill": ("radial", 54, 54, 34, [(0, "#5FE4F5", 0.22), (1, "#5FE4F5", 0)])},
    {"d": ellipse(54, 81, 22, 4.4), "stroke": ("#E8ECF0", 0.10), "width": 0.8},
    {"d": ellipse(54, 81, 12.5, 2.4), "stroke": ("#E8ECF0", 0.08), "width": 0.8},
]

FOREGROUND = [
    {"d": ellipse(54, 79.5, 15, 2.4), "fill": ("#000000", 0.45)},
    {"d": circle(54, 26, 8), "fill": LED_GLOW},
    {"d": rrect(52.9, 27, 2.2, 9.5, 1.1), "fill": ("#9AA3AD", 1)},
    {"d": circle(54, 26, 3.4), "fill": ("#7DF3FF", 1)},
    {"d": circle(53, 25, 1), "fill": ("#FFFFFF", 0.8)},
    {"d": rrect(27.5, 46, 6, 16, 2.6), "fill": EAR},
    {"d": rrect(74.5, 46, 6, 16, 2.6), "fill": EAR},
    {"d": rrect(46.5, 72, 15, 4.5, 1.6), "fill": ("#2B3038", 1)},
    {"d": rrect(32, 35, 44, 38, 11), "fill": SHELL},
    {
        "d": "M43,35h22a11,11 0 0 1 11,11v1.2a13,13 0 0 0 -11,-6.2h-22a13,13 0 0 0 -11,6.2v-1.2a11,11 0 0 1 11,-11z",
        "fill": ("#FFFFFF", 0.55),
    },
    {"d": rrect(37, 41, 34, 25, 8), "fill": ("#0B111A", 1)},
    {"d": rrect(37, 41, 34, 25, 8), "stroke": ("#2B3038", 1), "width": 1.2},
    {"d": "M45,41h9l-9,25h-0.5a8,8 0 0 1 -6,-2.7z", "fill": ("#FFFFFF", 0.05)},
    {"d": rrect(42.6, 45.5, 9.8, 15, 4.9), "fill": ("#5FE4F5", 0.22)},
    {"d": rrect(55.6, 45.5, 9.8, 15, 4.9), "fill": ("#5FE4F5", 0.22)},
    {"d": rrect(44.5, 47.5, 6, 11, 3), "fill": ("#78F4FF", 1)},
    {"d": rrect(57.5, 47.5, 6, 11, 3), "fill": ("#78F4FF", 1)},
    {"d": rrect(45.6, 48.6, 1.6, 3.4, 0.8), "fill": ("#FFFFFF", 0.7)},
    {"d": rrect(58.6, 48.6, 1.6, 3.4, 0.8), "fill": ("#FFFFFF", 0.7)},
]

# One-colour silhouette for themed icons and the status bar: the visor is a hole, the eyes sit in it.
SILHOUETTE = [
    {"d": circle(54, 26, 3.6) + rrect(52.9, 27, 2.2, 9.5, 1.1), "fill": ("#FFFFFF", 1)},
    {"d": rrect(27.5, 46, 6, 16, 2.6) + rrect(74.5, 46, 6, 16, 2.6), "fill": ("#FFFFFF", 1)},
    {"d": rrect(32, 35, 44, 38, 11) + rrect(37, 41, 34, 25, 8), "fill": ("#FFFFFF", 1), "evenodd": True},
    {"d": rrect(44.5, 47.5, 6, 11, 3) + rrect(57.5, 47.5, 6, 11, 3), "fill": ("#FFFFFF", 1)},
]


def argb(color: str, alpha: float) -> str:
    return f"#{round(alpha * 255):02X}{color[1:].upper()}"


def vector_path(shape: dict) -> str:
    attrs = [f'android:pathData="{shape["d"]}"']
    inner = ""
    if shape.get("evenodd"):
        attrs.append('android:fillType="evenOdd"')
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


def vector(shapes: list[dict], size: float, viewport: float, shift: tuple[float, float] = (0, 0), tint: str | None = None) -> str:
    body = "\n".join(vector_path(s) for s in shapes)
    if shift != (0, 0):
        indented = "\n".join("    " + line for line in body.splitlines())
        body = f'    <group android:translateX="{shift[0]:g}" android:translateY="{shift[1]:g}">\n{indented}\n    </group>'
    tint_attr = f'\n    android:tint="{tint}"' if tint else ""
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by tools/build_icon.py; edit the shapes there. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        f'    android:width="{size:g}dp" android:height="{size:g}dp"\n'
        f'    android:viewportWidth="{viewport:g}" android:viewportHeight="{viewport:g}"{tint_attr}>\n'
        f"{body}\n</vector>\n"
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


# The head spans roughly x 27..81 and y 18..77 of the 108 grid; the 60-unit crops centre it.
HEAD_SHIFT = (-24.0, -19.0)

OUTPUTS = {
    "ic_launcher_background.xml": vector(BACKGROUND, 108, 108),
    "ic_launcher_foreground.xml": vector(FOREGROUND, 108, 108),
    "ic_launcher_monochrome.xml": vector(SILHOUETTE, 108, 108),
    "ic_emrys.xml": vector(SILHOUETTE, 24, 60, HEAD_SHIFT),
    "emrys_head.xml": vector(FOREGROUND[1:], 60, 60, HEAD_SHIFT),
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
