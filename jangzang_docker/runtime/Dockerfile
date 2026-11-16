FROM docker.io/library/python:3.12-slim-bookworm@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH=/app/src \
    RUNTIME_DATA_DIR=/var/lib/agent-runtime

WORKDIR /app
COPY requirements.lock .
RUN python -m pip install --no-cache-dir --only-binary=:all: -r requirements.lock \
    && python -m pip check

COPY src/ src/
RUN groupadd --gid 10001 runtime \
    && useradd --uid 10001 --gid runtime --create-home runtime \
    && install -d -o runtime -g runtime "${RUNTIME_DATA_DIR}"

USER runtime
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "agent_runtime.api:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
