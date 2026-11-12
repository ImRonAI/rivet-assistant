# Ren's search and Discover for the phone, as ren desktop runs them: ren's own backend (github.com/ImRonAI/TheBrowser)
# at one commit, unmodified, with its pinned Strands source and requirements. app.py mounts only the two routers.
FROM python:3.14-slim-bookworm
ARG REN_COMMIT=b112adf5ff7563b0e39455f6110de91a77d5d438
RUN apt-get update && apt-get install -y --no-install-recommends git build-essential && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/ren
RUN git clone --filter=blob:none --no-checkout https://github.com/ImRonAI/TheBrowser.git . \
    && git sparse-checkout set agent deploy \
    && git checkout --detach "$REN_COMMIT"
RUN bash deploy/prepare-strands.sh && pip install --no-cache-dir -r agent/api/requirements.txt
COPY app.py /opt/ren/ren_search.py
# ren keeps a search's session (for its follow-up questions) under SESSION_STORAGE_DIR; compose mounts a volume there.
RUN useradd --create-home --uid 1000 ren && install -d -o ren -g ren /home/ren/sessions
USER 1000:1000
ENV PYTHONPATH=/opt/ren PYTHONUNBUFFERED=1 SESSION_STORAGE_DIR=/home/ren/sessions
EXPOSE 8200
CMD ["python", "-m", "uvicorn", "ren_search:app", "--host", "0.0.0.0", "--port", "8200"]
