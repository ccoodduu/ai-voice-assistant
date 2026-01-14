"""Audio input/output handling for voice assistant."""

import asyncio
import queue
import threading
from typing import AsyncGenerator

import pyaudio


class AudioStream:
    """Handles microphone input and speaker output."""

    FORMAT = pyaudio.paInt16
    CHANNELS = 1
    INPUT_RATE = 16000
    OUTPUT_RATE = 24000
    CHUNK_SIZE = 1024
    CHUNK_DURATION_MS = 100

    def __init__(self):
        self.audio = pyaudio.PyAudio()
        self.input_stream = None
        self.output_stream = None
        self.input_queue: queue.Queue[bytes] = queue.Queue()
        self.output_queue: queue.Queue[bytes] = queue.Queue()
        self._running = False
        self._input_thread = None
        self._output_thread = None

    def start(self):
        """Start audio streams."""
        self._running = True

        self.input_stream = self.audio.open(
            format=self.FORMAT,
            channels=self.CHANNELS,
            rate=self.INPUT_RATE,
            input=True,
            frames_per_buffer=self.CHUNK_SIZE,
        )

        self.output_stream = self.audio.open(
            format=self.FORMAT,
            channels=self.CHANNELS,
            rate=self.OUTPUT_RATE,
            output=True,
            frames_per_buffer=self.CHUNK_SIZE,
        )

        self._input_thread = threading.Thread(target=self._read_input, daemon=True)
        self._input_thread.start()

        self._output_thread = threading.Thread(target=self._write_output, daemon=True)
        self._output_thread.start()

    def _read_input(self):
        """Continuously read from microphone."""
        chunk_size = int(self.INPUT_RATE * self.CHUNK_DURATION_MS / 1000)

        while self._running and self.input_stream:
            try:
                data = self.input_stream.read(chunk_size, exception_on_overflow=False)
                self.input_queue.put(data)
            except Exception:
                break

    def _write_output(self):
        """Continuously write to speaker."""
        while self._running:
            try:
                data = self.output_queue.get(timeout=0.1)
                if self.output_stream:
                    self.output_stream.write(data)
            except queue.Empty:
                continue
            except Exception:
                break

    async def read_audio(self) -> AsyncGenerator[bytes, None]:
        """Async generator yielding audio chunks from microphone."""
        while self._running:
            try:
                data = self.input_queue.get_nowait()
                yield data
            except queue.Empty:
                await asyncio.sleep(0.01)

    def play_audio(self, data: bytes):
        """Queue audio data for playback."""
        self.output_queue.put(data)

    def stop(self):
        """Stop audio streams."""
        self._running = False

        if self.input_stream:
            self.input_stream.stop_stream()
            self.input_stream.close()
            self.input_stream = None

        if self.output_stream:
            self.output_stream.stop_stream()
            self.output_stream.close()
            self.output_stream = None

        self.audio.terminate()

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *args):
        self.stop()
