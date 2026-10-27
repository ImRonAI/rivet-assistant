# Rivet brain: the WebSocket gateway, session supervisors, and agents.
FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:0.11.32 /uv /usr/local/bin/uv

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
# A TLS-intercepting proxy's CA can be supplied for the build only (never stored in the image):
#   docker build --secret id=ca_bundle,src=/path/to/ca.pem ...
RUN --mount=type=secret,id=ca_bundle,required=false \
    if [ -f /run/secrets/ca_bundle ]; then export SSL_CERT_FILE=/run/secrets/ca_bundle; fi; \
    uv sync --frozen --no-dev --no-install-project
COPY src ./src
RUN --mount=type=secret,id=ca_bundle,required=false \
    if [ -f /run/secrets/ca_bundle ]; then export SSL_CERT_FILE=/run/secrets/ca_bundle; fi; \
    uv sync --frozen --no-dev

RUN useradd --create-home --uid 10001 rivet && mkdir -p /data && chown rivet /data
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

# Sessions, memory and skills live on a volume; one machine per volume (sessions are single-writer).
ENV PATH="/app/.venv/bin:$PATH" \
    RIVET_SESSION_DIR=/data/sessions \
    RIVET_SKILLS_DIR=/data/skills \
    RIVET_HOST=0.0.0.0 \
    PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request,os; urllib.request.urlopen(f'http://127.0.0.1:{os.environ[\"PORT\"]}/healthz')"
# Starts as root only to take ownership of the mounted volume, then runs as `rivet`.
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["rivet-brain"]
