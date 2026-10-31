FROM node:22-bookworm
COPY --from=ghcr.io/astral-sh/uv:0.9.5 /uv /usr/local/bin/uv
ENV UV_PYTHON_INSTALL_DIR=/opt/python npm_config_cache=/opt/npm-cache
# Desktop Commander goes into npx's cache, the way it was validated; `npm install -g` fails on fsevents on Linux.
RUN uv venv /opt/bridge --python 3.12 \
    && uv pip install --python /opt/bridge/bin/python fastmcp==4.1.0 \
    && npm exec --yes --package=@wonderwhy-er/desktop-commander@0.3.1 -- node -e "" \
    && chown -R 1000:1000 /opt/npm-cache
COPY bridge.py /opt/bridge/bridge.py
# The laptop's user and home, where OpenCode and the workspace live.
USER 1000:1000
ENV HOME=/config
EXPOSE 8080
CMD ["/opt/bridge/bin/python", "/opt/bridge/bridge.py"]
