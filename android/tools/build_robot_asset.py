"""Builds app/src/main/assets/rivet/index.html from the robot page.

Three changes, nothing else: Google Fonts links become local @font-face rules (the app works offline),
an ``embedded`` class makes the stage fill the WebView, and bridge.js is appended after the page script.

Usage: python3 tools/build_robot_asset.py  (from android/)
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "robot" / "Rivet_the_Robot.html"
BRIDGE = ROOT / "robot" / "bridge.js"
OUT = ROOT / "app" / "src" / "main" / "assets" / "rivet" / "index.html"

FONTS = [
    ("IBM Plex Mono", 500, "ibm_plex_mono_medium"),
    ("IBM Plex Sans", 400, "ibm_plex_sans_regular"),
    ("IBM Plex Sans", 600, "ibm_plex_sans_semibold"),
]
EMBEDDED_CSS = (
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
        f"@font-face{{font-family:'{family}';font-weight:{weight};font-style:normal;font-display:swap;"
        f"src:url(fonts/{file}.ttf) format('truetype')}}"
        for family, weight, file in FONTS
    )
    source = source.replace("<style>", f"<style>{faces}{EMBEDDED_CSS}", 1)
    source = source.replace("<html", '<html class="embedded"', 1) if "<html class=" not in source else source
    if source.count("</body>") != 1:
        raise SystemExit("expected exactly one </body>")
    return source.replace("</body>", f"<script>\n{bridge}</script>\n</body>", 1)


def main() -> None:
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
