# Ren's hub (app.py): FastMCP at the versions ren desktop pins, so the hub and the desktop speak the same MCP.
FROM python:3.12-slim-bookworm
RUN pip install --no-cache-dir fastmcp==3.4.0 mcp==1.26.0 uvicorn==0.54.0 websockets==15.0.1
COPY app.py /opt/hub/app.py
RUN useradd --create-home --uid 1000 ren
USER 1000:1000
WORKDIR /opt/hub
ENV PYTHONUNBUFFERED=1
EXPOSE 8300
CMD ["python", "app.py"]
