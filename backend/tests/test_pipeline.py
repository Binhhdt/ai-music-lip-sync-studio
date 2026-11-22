"""End-to-end pipeline test WITHOUT GPU/AI.

Uses engine 'test' (real FFmpeg): creates a short tone WAV + a test
image, runs the full job flow (create -> upload -> start -> poll ->
result), and checks the MP4 exists, has the original audio, and its
duration matches the song. This proves upload/chunking/mux/duration —
it does NOT prove lip sync quality.
"""
import io
import subprocess
import time
import wave
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "app"))
from main import app  # noqa: E402

client = TestClient(app)


def make_tone_wav(path: Path, seconds: float = 6.0):
    import math
    import struct
    sr = 16000
    n = int(sr * seconds)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        frames = b"".join(struct.pack("<h", int(12000 * math.sin(2 * math.pi * 440 * i / sr)))
                          for i in range(n))
        w.writeframes(frames)


def make_test_image(path: Path):
    subprocess.run(["ffmpeg", "-y", "-f", "lavfi", "-i", "color=c=0x336699:s=640x360:d=1",
                    "-frames:v", "1", str(path)], check=True, capture_output=True)


def test_full_job_flow(tmp_path: Path):
    img = tmp_path / "singer.png"
    wav = tmp_path / "song.wav"
    make_test_image(img)
    make_tone_wav(wav, 6.0)

    r = client.post("/api/v1/jobs", json={
        "mode": "image", "engine": "test", "aspect": "16:9",
        "resolution": "480p", "fps": 25, "quality": "fast"})
    assert r.status_code == 200, r.text
    job_id = r.json()["id"]

    with img.open("rb") as f:
        r = client.post(f"/api/v1/jobs/{job_id}/upload?kind=source",
                        files={"file": ("singer.png", f, "image/png")})
    assert r.status_code == 200, r.text
    with wav.open("rb") as f:
        r = client.post(f"/api/v1/jobs/{job_id}/upload?kind=audio",
                        files={"file": ("song.wav", f, "audio/wav")})
    assert r.status_code == 200, r.text

    r = client.post(f"/api/v1/jobs/{job_id}/start")
    assert r.status_code == 200, r.text

    deadline = time.time() + 180
    status = {}
    while time.time() < deadline:
        status = client.get(f"/api/v1/jobs/{job_id}").json()
        if status["status"] in ("done", "failed", "cancelled"):
            break
        time.sleep(1)
    assert status["status"] == "done", status

    r = client.get(f"/api/v1/jobs/{job_id}/result")
    assert r.status_code == 200
    out = tmp_path / "result.mp4"
    out.write_bytes(r.content)
    assert out.stat().st_size > 10_000

    probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries",
                            "format=duration", "-of", "csv=p=0", str(out)],
                           capture_output=True, text=True)
    dur = float(probe.stdout.strip())
    assert abs(dur - 6.0) < 1.0, f"duration {dur} != 6.0"

    # result must contain an audio stream (the original song)
    probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries",
                            "stream=codec_type", "-of", "csv=p=0", str(out)],
                           capture_output=True, text=True)
    assert "audio" in probe.stdout
