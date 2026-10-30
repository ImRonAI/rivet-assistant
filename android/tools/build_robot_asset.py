"""Builds app/src/main/assets/emrys/index.html from the original robot page.

Four changes, nothing else: Google Fonts links become local @font-face rules (the app works offline),
an ``embedded`` class hides the page's own move dock, telemetry strip and hint (the app draws its own), bridge.js is appended
after the page script, and the stage layer (the ElevenLabs UI voice layer built from android/stage-ui into
assets/emrys/vendor/stage-ui.{js,css} by ``npm ci && npm run build`` there) is linked from the head and loaded after
the bridge. The built layer is checked in, so this script needs no Node; it only requires the files to be there.

Usage: python3 tools/build_robot_asset.py  (from android/); ``--check`` fails if the page is stale.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "robot" / "Emrys_the_Robot.html"
BRIDGE = ROOT / "robot" / "bridge.js"
OUT = ROOT / "app" / "src" / "main" / "assets" / "emrys" / "index.html"
VENDOR = OUT.parent / "vendor"
STAGE_FILES = ("stage-ui.js", "stage-ui.css", "perlin-noise.png")

FONTS = [
    ("Instrument Serif", 400, "normal", "instrument_serif_regular"),
    ("Instrument Serif", 400, "italic", "instrument_serif_italic"),
    ("Geist", 400, "normal", "geist_regular"),
    ("Geist", 500, "normal", "geist_medium"),
    ("Geist", 600, "normal", "geist_semibold"),
    ("Geist Mono", 400, "normal", "geist_mono_regular"),
    ("Geist Mono", 500, "normal", "geist_mono_medium"),
]
EMBEDDED_CSS = (
    "html.embedded .hud,html.embedded .dock,html.embedded .hint{display:none}"
    "html.embedded .app{grid-template-rows:minmax(0,1fr);height:100%}"
    "html.embedded,html.embedded body{overflow:hidden}"
)


def build(source: str, bridge: str) -> str:
    links = re.findall(r'<link rel="(?:preconnect|stylesheet)" href="https://fonts\.[^"]+"[^>]*>\n?', source)
    if len(links) != 3:
        raise SystemExit(f"expected 3 Google Fonts links, found {len(links)}")
    for link in links:
        source = source.replace(link, "", 1)
    faces = "".join(
        f"@font-face{{font-family:'{family}';font-weight:{weight};font-style:{style};font-display:swap;"
        f"src:url(fonts/{file}.ttf) format('truetype')}}"
        for family, weight, style, file in FONTS
    )
    source = source.replace("<style>", f"<style>{faces}{EMBEDDED_CSS}", 1)
    source = source.replace("<html", '<html class="embedded"', 1) if "<html class=" not in source else source
    if source.count("</head>") != 1:
        raise SystemExit("expected exactly one </head>")
    source = source.replace("</head>", '<link rel="stylesheet" href="vendor/stage-ui.css"></head>', 1)
    if source.count("</body>") != 1:
        raise SystemExit("expected exactly one </body>")
    return source.replace(
        "</body>", f'<script>\n{bridge}</script>\n<script src="vendor/stage-ui.js"></script>\n</body>', 1
    )


def main() -> None:
    missing = [name for name in STAGE_FILES if not (VENDOR / name).is_file()]
    if missing:
        raise SystemExit(f"missing {', '.join(missing)} in {VENDOR}; run npm ci && npm run build in android/stage-ui")
    html = build(SOURCE.read_text(encoding="utf-8"), BRIDGE.read_text(encoding="utf-8"))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    if "--check" in sys.argv:
        if OUT.read_text(encoding="utf-8") != html:
            raise SystemExit(f"{OUT} is stale; run tools/build_robot_asset.py")
        return
    OUT.write_text(html, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)} ({len(html)} chars)")


if __name__ == "__main__":
    main()
