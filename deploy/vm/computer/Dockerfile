FROM node:26.13.0-bookworm
COPY --from=toolbox install.sh /tmp/toolbox.sh
RUN sh /tmp/toolbox.sh && rm /tmp/toolbox.sh
ENV npm_config_cache=/opt/npm-cache
# Desktop Commander goes into npx's cache, the way it was validated; `npm install -g` fails on fsevents on Linux.
# So does OpenCode's Playwright MCP, with Chromium's system libraries; Chromium itself is in the laptop's home
# (~/.cache/ms-playwright, from `npx playwright@<its version> install chromium`), next to OpenCode.
RUN UV_PYTHON_INSTALL_DIR=/opt/python uv venv /opt/bridge --python 3.12 \
    && uv pip install --python /opt/bridge/bin/python fastmcp==4.1.0 elevenlabs==2.74.1 \
    && npm exec --yes --package=@wonderwhy-er/desktop-commander@0.3.1 -- node -e "" \
    && npm exec --yes --package=@playwright/mcp@0.0.83 -- node -e "" \
    && npx --yes playwright@1.64.0-alpha-1790635538000 install-deps chromium \
    && chown -R 1000:1000 /opt/npm-cache
COPY bridge.py hooks.py /opt/bridge/
# The laptop's user and home, where OpenCode, the workspace and Rust (rustup's ~/.cargo) live.
USER 1000:1000
ENV HOME=/config PATH=/config/.opencode/bin:/config/.cargo/bin:$PATH
EXPOSE 8080
CMD ["/opt/bridge/bin/python", "/opt/bridge/bridge.py"]
