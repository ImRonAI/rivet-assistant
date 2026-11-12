"""Ren's search and Discover for the phone: ren desktop's own routers, mounted as they are.

``POST /search/stream`` (Brave sections, Brave Answers, the Shopping and Search agents on GLM 5.3 Flash) and the
Discover feed come from ren's backend at the commit the image pins (``Dockerfile``); nothing of them is rewritten here.
Caddy puts this app at ``https://rivet.ron-ai.dev/ren/`` behind the phone's bearer token, and the app's bundled search
page (served from ``https://appassets.androidplatform.net``) is the one origin allowed to call it.
"""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from agent.api.endpoints import discover, search

#: The origin of the app's bundled pages (androidx.webkit's WebViewAssetLoader).
APP_ORIGIN = "https://appassets.androidplatform.net"

app = FastAPI(title="Ren search")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[APP_ORIGIN],
    allow_methods=["GET", "POST"],
    allow_headers=["authorization", "content-type"],
)
app.include_router(discover.router)
app.include_router(search.router)
