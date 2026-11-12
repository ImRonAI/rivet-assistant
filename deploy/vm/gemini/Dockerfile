# The Strands agents (Gemini, NVIDIA NIM, Mistral) run on the computer image, so the harness's shell, files and tools are the computer's, in its home.
FROM emrys-computer:0.3.1
USER root
RUN UV_PYTHON_INSTALL_DIR=/opt/python uv venv /opt/gemini --python 3.12 \
    && uv pip install --python /opt/gemini/bin/python "strands-agents[bidi,bidi-google,gemini,mistral,openai,web-fetch]==1.64.0" \
        strands-harness==0.3.4 mistralai==2.10.1 openai==2.50.0 fastapi==0.162.0 uvicorn==0.54.0 elevenlabs==2.75.0
COPY app.py providers.py mistral_model.py media_capabilities.py context_windows.py /opt/gemini/
USER 1000:1000
WORKDIR /config/workspace
EXPOSE 8100
CMD ["/opt/gemini/bin/python", "/opt/gemini/app.py"]
