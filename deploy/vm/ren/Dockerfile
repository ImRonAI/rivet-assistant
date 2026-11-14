# ren desktop's backend for the phone, as ren desktop runs it (its deploy/systemd/ron-api.service runs
# `python agent/api/main.py`): ren's own code (github.com/ImRonAI/TheBrowser) at one commit, unmodified, with its pinned
# Strands source and requirements. compose.yaml sets what ren's deploy/ron.env.example leaves to the host.
FROM python:3.14-slim-bookworm
ARG REN_COMMIT=b112adf5ff7563b0e39455f6110de91a77d5d438
RUN apt-get update && apt-get install -y --no-install-recommends git build-essential && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/ren
RUN git clone --filter=blob:none --no-checkout https://github.com/ImRonAI/TheBrowser.git . \
    && git sparse-checkout set agent deploy \
    && git checkout --detach "$REN_COMMIT"
# The second install is ren's own pin for the MCP packages (deploy/vps-install.sh); electron-playwright-mcp, which it
# installs next, drives ren's Electron browser and isn't here.
RUN bash deploy/prepare-strands.sh && pip install --no-cache-dir -r agent/api/requirements.txt \
    && pip install --no-cache-dir 'fastmcp[apps]==3.4.0' 'mcp==1.26.0' 'pyyaml>=6.0.3' 'httpx>=0.28.1'
RUN useradd --create-home --uid 1000 ren && install -d -o ren -g ren /home/ren/data
USER 1000:1000
ENV PYTHONPATH=/opt/ren PYTHONUNBUFFERED=1 API_HOST=0.0.0.0 API_PORT=8765
EXPOSE 8765
CMD ["python", "agent/api/main.py"]
