"""AI Music Lip Sync Studio — backend.

FastAPI job server. The Android app is only a client; all heavy AI work
runs here, on a GPU server.

Engines are modular (see engines.py):
  - "test":     real FFmpeg pipeline, NOT lip sync. Only verifies
                upload -> chunking -> mux -> MP4 with the ORIGINAL audio.
                Used by CI and by the local smoke test. Never presented
                to the user as AI lip sync.
  - "musetalk": MuseTalk (MIT code, weights "any purpose, even
                commercially" per upstream README). Needs GPU + weights.
  - "latentsync": LatentSync (Apache-2.0 code; weights OpenRAIL++-M;
                built-in InsightFace detector is non-commercial — replace
                it for commercial use). Needs 8 GB VRAM (v1.5) / 18 GB (v1.6).

If an AI engine is not installed/configured on this server the job FAILS
with a clear message instead of returning a fake video.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import threading
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

try:  # chạy dạng package: uvicorn app.main:app (từ thư mục backend/)
    from app.engines import (
        available_engines,
        demucs_available,
        ffmpeg_available,
        gpu_info,
        run_engine_segment,
        separate_vocals,
    )
except ImportError:  # chạy dạng module lẻ: pytest chèn backend/app vào sys.path
    from engines import (
        available_engines,
        demucs_available,
        ffmpeg_available,
        gpu_info,
        run_engine_segment,
        separate_vocals,
    )

DATA_DIR = Path(os.environ.get("LIPSYNC_DATA", Path(__file__).resolve().parent.parent / "data"))
DATA_DIR.mkdir(parents=True, exist_ok=True)
SEGMENT_SECONDS = int(os.environ.get("LIPSYNC_SEGMENT_SECONDS", "30"))
MAX_AUDIO_SECONDS = int(os.environ.get("LIPSYNC_MAX_AUDIO_SECONDS", "600"))
MAX_UPLOAD_MB = int(os.environ.get("LIPSYNC_MAX_UPLOAD_MB", "500"))

app = FastAPI(title="AI Music Lip Sync Studio API", version="1.0.0")

# In-memory run state (jobs themselves are persisted as meta.json).
_cancel_flags: dict[str, bool] = {}
_locks: dict[str, threading.Lock] = {}


# ---------------------------------------------------------------- models
class JobCreate(BaseModel):
    mode: str = Field("video", pattern="^(video|image)$")
    engine: str = "musetalk"
    aspect: str = Field("16:9", pattern="^(16:9|9:16|1:1)$")
    resolution: str = Field("720p", pattern="^(480p|720p|1080p)$")
    fps: int = Field(25, ge=24, le=30)
    quality: str = Field("balanced", pattern="^(fast|balanced|high)$")
    sample_only: bool = False  # only process the first 15 s (test mode)
    separate_vocal: bool = False


class JobStatus(BaseModel):
    id: str
    status: str
    stage: str = ""
    progress: int = 0
    message: str = ""
    engine: str = ""
    mode: str = ""
    duration_seconds: float = 0.0
    segments_total: int = 0
    segments_done: int = 0
    has_result: bool = False
    error: str = ""


# ---------------------------------------------------------------- helpers
def job_dir(job_id: str) -> Path:
    return DATA_DIR / "jobs" / job_id


def meta_path(job_id: str) -> Path:
    return job_dir(job_id) / "meta.json"


def load_meta(job_id: str) -> dict:
    p = meta_path(job_id)
    if not p.exists():
        raise HTTPException(404, "Job không tồn tại")
    return json.loads(p.read_text(encoding="utf-8"))


def save_meta(job_id: str, meta: dict) -> None:
    meta_path(job_id).write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")


def new_meta(job_id: str, req: JobCreate) -> dict:
    return {
        "id": job_id, "status": "awaiting_upload", "stage": "Đang chờ tải file lên",
        "progress": 0, "message": "", "engine": req.engine, "mode": req.mode,
        "aspect": req.aspect, "resolution": req.resolution, "fps": req.fps,
        "quality": req.quality, "sample_only": req.sample_only,
        "separate_vocal": req.separate_vocal, "duration_seconds": 0.0,
        "segments_total": 0, "segments_done": 0, "has_result": False,
        "error": "", "created_at": time.time(), "source_name": "", "audio_name": "",
    }


def ffprobe_duration(path: Path) -> float:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
        capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        raise RuntimeError(f"Không đọc được thời lượng file: {r.stderr.strip()[:200]}")
    return float(r.stdout.strip())


def resolution_size(res: str, aspect: str) -> tuple[int, int]:
    h = {"480p": 480, "720p": 720, "1080p": 1080}[res]
    if aspect == "16:9":
        return (round(h * 16 / 9), h)
    if aspect == "9:16":
        return (h, round(h * 16 / 9)) if False else (round(h * 9 / 16), h)
    return (h, h)


def update(job_id: str, **kw) -> dict:
    meta = load_meta(job_id)
    meta.update(kw)
    save_meta(job_id, meta)
    return meta


# ---------------------------------------------------------------- API
@app.get("/api/v1/health")
def health():
    return {"status": "ok", "ffmpeg": ffmpeg_available(), "gpu": gpu_info(),
            "engines": available_engines(), "time": time.time()}


@app.get("/api/v1/capabilities")
def capabilities():
    return {
        "engines": available_engines(),
        "demucs": demucs_available(),
        "ffmpeg": ffmpeg_available(),
        "gpu": gpu_info(),
        "max_audio_seconds": MAX_AUDIO_SECONDS,
        "max_upload_mb": MAX_UPLOAD_MB,
        "segment_seconds": SEGMENT_SECONDS,
        "aspects": ["16:9", "9:16", "1:1"],
        "resolutions": ["480p", "720p", "1080p"],
        "fps": [24, 25, 30],
        "modes": ["video", "image"],
        "note": ("Engine 'test' KHÔNG phải AI lip sync — chỉ kiểm tra pipeline. "
                 "MuseTalk/LatentSync cần GPU + tải weights, xem docs/GPU_DEPLOY.md."),
    }


@app.post("/api/v1/jobs", response_model=JobStatus)
def create_job(req: JobCreate):
    if req.engine not in ("test", "musetalk", "latentsync"):
        raise HTTPException(400, f"Engine không hỗ trợ: {req.engine}")
    job_id = uuid.uuid4().hex[:16]
    job_dir(job_id).mkdir(parents=True)
    meta = new_meta(job_id, req)
    save_meta(job_id, meta)
    return JobStatus(**meta)


@app.get("/api/v1/jobs")
def list_jobs():
    out = []
    for p in sorted((DATA_DIR / "jobs").glob("*/meta.json"), key=lambda x: x.stat().st_mtime, reverse=True)[:50]:
        m = json.loads(p.read_text(encoding="utf-8"))
        out.append({k: m.get(k) for k in ("id", "status", "stage", "progress", "engine", "mode",
                                          "duration_seconds", "has_result", "created_at")})
    return {"jobs": out}


@app.get("/api/v1/jobs/{job_id}", response_model=JobStatus)
def get_job(job_id: str):
    return JobStatus(**load_meta(job_id))


@app.post("/api/v1/jobs/{job_id}/upload")
async def upload_file(job_id: str, kind: str, file: UploadFile = File(...)):
    meta = load_meta(job_id)
    if kind not in ("source", "audio"):
        raise HTTPException(400, "kind phải là 'source' hoặc 'audio'")
    if meta["status"] not in ("awaiting_upload", "queued"):
        raise HTTPException(409, f"Job đang ở trạng thái {meta['status']}, không nhận file nữa")
    dest = job_dir(job_id) / ("source" + Path(file.filename or "").suffix.lower() if kind == "source"
                              else "audio" + Path(file.filename or "").suffix.lower())
    size = 0
    with dest.open("wb") as f:
        while True:
            chunk = await file.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            if size > MAX_UPLOAD_MB * 1024 * 1024:
                dest.unlink(missing_ok=True)
                raise HTTPException(413, f"File vượt quá {MAX_UPLOAD_MB} MB")
            f.write(chunk)
    meta["source_name" if kind == "source" else "audio_name"] = file.filename or dest.name
    save_meta(job_id, meta)
    return {"ok": True, "kind": kind, "size_bytes": size, "stored_as": dest.name}


@app.post("/api/v1/jobs/{job_id}/start", response_model=JobStatus)
def start_job(job_id: str):
    meta = load_meta(job_id)
    if meta["status"] not in ("awaiting_upload", "queued", "failed", "cancelled"):
        raise HTTPException(409, f"Job đang ở trạng thái {meta['status']}")
    d = job_dir(job_id)
    sources = list(d.glob("source.*"))
    audios = list(d.glob("audio.*"))
    if not sources or not audios:
        raise HTTPException(400, "Thiếu file nguồn (ảnh/video) hoặc file nhạc — tải đủ 2 file rồi mới bắt đầu")
    update(job_id, status="queued", stage="Đã xếp hàng chờ xử lý", progress=1, error="")
    _cancel_flags.pop(job_id, None)
    t = threading.Thread(target=_run_job, args=(job_id,), daemon=True)
    t.start()
    return JobStatus(**load_meta(job_id))


@app.post("/api/v1/jobs/{job_id}/cancel", response_model=JobStatus)
def cancel_job(job_id: str):
    meta = load_meta(job_id)
    if meta["status"] in ("done", "failed"):
        raise HTTPException(409, "Job đã kết thúc, không hủy được")
    _cancel_flags[job_id] = True
    update(job_id, status="cancelled", stage="Đã hủy", message="Người dùng đã hủy công việc")
    return JobStatus(**load_meta(job_id))


@app.get("/api/v1/jobs/{job_id}/result")
def get_result(job_id: str):
    meta = load_meta(job_id)
    result = job_dir(job_id) / "result.mp4"
    if meta["status"] != "done" or not result.exists():
        raise HTTPException(404, "Chưa có video kết quả")
    return FileResponse(result, media_type="video/mp4", filename=f"lipsync-{job_id}.mp4")


# ---------------------------------------------------------------- runner
def _cancelled(job_id: str) -> bool:
    return _cancel_flags.get(job_id, False)


def _run_job(job_id: str) -> None:
    d = job_dir(job_id)
    try:
        meta = load_meta(job_id)
        source = next(d.glob("source.*"))
        audio = next(d.glob("audio.*"))

        # 1. Phân tích âm thanh
        update(job_id, status="analyzing", stage="Đang phân tích âm thanh", progress=5)
        duration = ffprobe_duration(audio)
        if duration <= 0:
            raise RuntimeError("File nhạc không có thời lượng hợp lệ")
        if duration > MAX_AUDIO_SECONDS:
            raise RuntimeError(f"Bài hát dài {duration:.0f}s, vượt giới hạn {MAX_AUDIO_SECONDS}s của máy chủ này")
        if meta["sample_only"]:
            duration = min(duration, 15.0)
        update(job_id, duration_seconds=duration)

        # 2. Tách vocal (tùy chọn, chỉ làm tín hiệu cho lip sync)
        lip_audio = audio
        if meta["separate_vocal"]:
            update(job_id, status="separating", stage="Đang tách giọng hát", progress=10)
            if not demucs_available():
                update(job_id, message="Máy chủ chưa cài Demucs — dùng nhạc gốc làm tín hiệu lip sync")
            else:
                lip_audio = separate_vocals(audio, d / "vocals")
                update(job_id, message="Đã tách vocal xong (chỉ dùng làm tín hiệu, video cuối vẫn ghép nhạc gốc)")

        # 3. Chuẩn bị + chia đoạn
        update(job_id, status="preparing", stage="Đang chuẩn bị khuôn mặt và chia đoạn", progress=15)
        n_seg = max(1, int(duration + SEGMENT_SECONDS - 1) // SEGMENT_SECONDS)
        update(job_id, segments_total=n_seg, segments_done=0)
        w, h = resolution_size(meta["resolution"], meta["aspect"])
        seg_dir = d / "segments"
        seg_dir.mkdir(exist_ok=True)

        # 4. Xử lý từng đoạn (bỏ qua đoạn đã xong để tiếp tục sau lỗi)
        update(job_id, status="lipsync", stage="Đang xử lý lip sync", progress=16)
        seg_files: list[Path] = []
        for i in range(n_seg):
            if _cancelled(job_id):
                return
            start = i * SEGMENT_SECONDS
            seg_dur = min(SEGMENT_SECONDS, duration - start)
            out_seg = seg_dir / f"seg_{i:03d}.mp4"
            seg_files.append(out_seg)
            if out_seg.exists() and out_seg.stat().st_size > 0:
                update(job_id, segments_done=i + 1,
                       progress=16 + int(70 * (i + 1) / n_seg))
                continue
            run_engine_segment(
                engine=meta["engine"], mode=meta["mode"], source=source,
                audio=audio, lip_audio=lip_audio, start=start, seg_dur=seg_dur,
                out=out_seg, width=w, height=h, fps=meta["fps"],
                quality=meta["quality"], workdir=d,
            )
            if _cancelled(job_id):
                return
            update(job_id, segments_done=i + 1,
                   progress=16 + int(70 * (i + 1) / n_seg))

        # 5. Ghép các đoạn + ghép nhạc GỐC (không dùng vocal đã tách)
        update(job_id, status="muxing", stage="Đang ghép video và nhạc gốc", progress=88)
        concat_list = d / "concat.txt"
        concat_list.write_text("".join(f"file '{f}'\n" for f in seg_files), encoding="utf-8")
        merged = d / "merged.mp4"
        r = subprocess.run(
            ["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(concat_list),
             "-c", "copy", str(merged)], capture_output=True, text=True, timeout=600)
        if r.returncode != 0:
            raise RuntimeError(f"Ghép đoạn thất bại: {r.stderr[-300:]}")
        result = d / "result.mp4"
        r = subprocess.run(
            ["ffmpeg", "-y", "-i", str(merged), "-i", str(audio),
             "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac",
             "-t", str(duration), "-shortest", str(result)],
            capture_output=True, text=True, timeout=600)
        if r.returncode != 0:
            raise RuntimeError(f"Ghép nhạc gốc thất bại: {r.stderr[-300:]}")
        out_dur = ffprobe_duration(result)
        if abs(out_dur - duration) > 1.5:
            raise RuntimeError(f"Video ra dài {out_dur:.1f}s, lệch khỏi nhạc {duration:.1f}s — không giao")
        update(job_id, status="done", stage="Hoàn thành", progress=100,
               has_result=True,
               message=f"Xong: {out_dur:.1f}s, engine {meta['engine']}" +
                       (" (TEST — không phải AI lip sync)" if meta["engine"] == "test" else ""))
    except Exception as e:  # noqa: BLE001 — job failure must be reported, not crash server
        if _cancelled(job_id):
            update(job_id, status="cancelled", stage="Đã hủy")
        else:
            update(job_id, status="failed", stage="Thất bại", error=str(e)[:500],
                   message=f"Lỗi: {e}"[:500])


@app.get("/")
def root():
    return {"name": "AI Music Lip Sync Studio API", "version": "1.0.0",
            "docs": "/docs", "health": "/api/v1/health"}
