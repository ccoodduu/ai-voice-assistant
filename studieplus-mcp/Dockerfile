FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libffi-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --no-cache-dir uv

COPY pyproject.toml .
COPY src/ src/

RUN uv pip install --system .

# Cloud Run sets PORT automatically, default to 8080
ENV MCP_TRANSPORT=sse
ENV PORT=8080

EXPOSE 8080

CMD ["python", "-m", "mcp_server.server"]
