FROM python:3.12-slim

WORKDIR /app

RUN pip install --no-cache-dir uv

COPY pyproject.toml .
COPY src/ src/

RUN uv pip install --system .

EXPOSE 8101

CMD ["studieplus-mcp", "--transport", "sse", "--port", "8101"]
