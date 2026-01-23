FROM python:3.12-slim

# Layer 1: Original system packages (CACHED from existing build)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    portaudio19-dev \
    libasound2-dev \
    libpulse-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --no-cache-dir uv

# Layer 2: WebRTC dependencies (new layer, apt-get is fast)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libopus0 \
    libopus-dev \
    libvpx-dev \
    libsrtp2-dev \
    pkg-config \
    && rm -rf /var/lib/apt/lists/*

# Layer 3: Python dependencies (cached if pyproject.toml unchanged)
COPY pyproject.toml .
RUN mkdir -p src && touch src/__init__.py && \
    uv pip install --system . && \
    rm -rf src

# Layer 4: Source code (volume-mounted in dev anyway)
COPY src/ src/

CMD ["ai-server"]
