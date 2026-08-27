# syntax=docker/dockerfile:1.7
FROM python:3.12-slim AS google-ai-source
ARG EVENTS_BOT_REF=8710e56fa3685f6c30a90cd062d532dce0348cce
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN git init /source \
    && cd /source \
    && git remote add origin https://github.com/onedayonemasterpiece/events-bot-new.git \
    && git fetch --depth 1 origin "${EVENTS_BOT_REF}" \
    && git checkout --detach FETCH_HEAD \
    && test -f google_ai/client.py

FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/opt/events-bot-new \
    RECORD_IDEA_HUB_DATA_DIR=/data \
    GOOGLE_AI_ALLOW_RESERVE_FALLBACK=0 \
    GOOGLE_AI_LOCAL_LIMITER_FALLBACK=0 \
    GOOGLE_AI_LOCAL_LIMITER_ON_RESERVE_ERROR=0
WORKDIR /app
COPY --from=google-ai-source /source/google_ai /opt/events-bot-new/google_ai
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .
RUN useradd --create-home --uid 10001 app \
    && mkdir -p /data \
    && chown -R app:app /data /app
USER app
EXPOSE 8080
VOLUME ["/data"]
CMD ["uvicorn", "record_idea_hub.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8080"]
