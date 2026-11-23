"""Engine adapters — one interface, swappable implementations.

Each engine renders ONE segment: (source image/video + audio slice)
-> segment mp4 (video only; the original audio is muxed at the end by
main.py, so a lip-sync engine can never change the song's pitch/speed).

An engine that is not installed/configured raises EngineNotReady with a
plain-language reason. The job then FAILS visibly — it never returns a
fake "success" video.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
from pathlib import Path

ENGINES_DIR = Path(os.environ.get("LIPSYNC_ENGINES_DIR", Path(__file__).resolve().parent.parent / "engines"))


class EngineNotReady(RuntimeError):
    pass


def ffmpeg_available() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def demucs_available() -> bool:
    return shutil.which("demucs") is not None


def gpu_info() -> dict:
    # nvidia-smi KHÔNG có trên máy không có GPU (vd: Render free,
    # python:3.11-slim chỉ cài ffmpeg). Không được để FileNotFoundError
    # lan ra ngoài: /api/v1/health và /api/v1/capabilities đều gọi hàm này,
    # mà Render health check cần 2xx — lỗi 500 là deploy bị hủy sau 15 phút.
    try:
        r = subprocess.run(["nvidia-smi", "--query-gpu=name,memory.total",
                            "--format=csv,noheader"], capture_output=True, text=True, timeout=15)
    except (FileNotFoundError, OSError, subprocess.TimeoutExpired):
        return {"present": False, "detail": "Không có GPU NVIDIA trên máy chủ này"}
    if r.returncode == 0 and r.stdout.strip():
        return {"present": True, "detail": r.stdout.strip()}
    return {"present": False, "detail": "Không có GPU NVIDIA trên máy chủ này"}


def _engine_config(name: str) -> dict:
    p = ENGINES_DIR / f"{name}.json"
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    return {}


def _check_configured(name: str, required_files: list[str]) -> dict:
    cfg = _engine_config(name)
    if not cfg:
        raise EngineNotReady(
            f"Engine {name} chưa được cấu hình trên máy chủ này. "
            f"Xem docs/GPU_DEPLOY.md để cài weights và tạo engines/{name}.json.")
    missing = [f for f in required_files if not Path(os.path.expandvars(f)).exists()]
    if missing:
        raise EngineNotReady(f"Engine {name} thiếu file: {', '.join(missing)} — kiểm tra lại đường dẫn weights.")
    return cfg


def available_engines() -> dict:
    out = {}
    for name in ("test", "musetalk", "latentsync"):
        if name == "test":
            out[name] = {"available": ffmpeg_available(),
                         "note": "TEST — kiểm tra pipeline bằng FFmpeg, KHÔNG phải AI lip sync"}
            continue
        cfg = _engine_config(name)
        ckpt = cfg.get("checkpoint", "")
        ok = bool(cfg) and (not ckpt or Path(os.path.expandvars(ckpt)).exists())
        out[name] = {"available": ok,
                     "configured": bool(cfg),
                     "note": cfg.get("note", "Cần GPU + weights, xem docs/GPU_DEPLOY.md")}
    return out


def separate_vocals(audio: Path, out_dir: Path) -> Path:
    """Demucs: tách vocal làm TÍN HIỆU cho lip sync. File trả về chỉ dùng
    cho engine; video cuối vẫn ghép nhạc gốc."""
    out_dir.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(["demucs", "-n", "htdemucs", "--two-stems=vocals",
                        "-o", str(out_dir), str(audio)],
                       capture_output=True, text=True, timeout=1800)
    if r.returncode != 0:
        raise RuntimeError(f"Tách vocal thất bại: {r.stderr[-300:]}")
    vocals = sorted(out_dir.rglob("vocals.wav"))
    if not vocals:
        raise RuntimeError("Demucs chạy xong nhưng không tạo được vocals.wav")
    return vocals[0]


def _ffmpeg_segment_video(source: Path, audio: Path, start: float, seg_dur: float,
                          out: Path, width: int, height: int, fps: int) -> None:
    """Engine 'test': cắt đúng đoạn video nguồn (hoặc ảnh tĩnh) + ghép đúng
    đoạn nhạc, resize/crop đúng tỉ lệ. Không giả vờ là lip sync."""
    vf = (f"scale={width}:{height}:force_original_aspect_ratio=increase,"
          f"crop={width}:{height},fps={fps},format=yuv420p")
    is_image = source.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp")
    # Image input is a single frame: loop it for the whole segment, else the
    # segment comes out ~0 s long.
    src_args = (["-loop", "1", "-framerate", str(fps)] if is_image else ["-ss", str(start)])
    r = subprocess.run(
        ["ffmpeg", "-y", *src_args, "-i", str(source),
         "-ss", str(start), "-i", str(audio),
         "-t", str(seg_dur), "-vf", vf, "-map", "0:v:0", "-map", "1:a:0",
         "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
         "-c:a", "aac", "-shortest", str(out)],
        capture_output=True, text=True, timeout=900)
    if r.returncode != 0:
        raise RuntimeError(f"Engine test thất bại ở đoạn {start}s: {r.stderr[-300:]}")


def _run_external(name: str, cfg: dict, source: Path, audio: Path, lip_audio: Path,
                  start: float, seg_dur: float, out: Path,
                  width: int, height: int, fps: int, quality: str, workdir: Path) -> None:
    """Run a real AI engine via its configured command template.

    Template placeholders: {python} {source} {audio} {lip_audio} {out}
    {start} {dur} {width} {height} {fps} {workdir} plus every key of the
    engine's JSON config (e.g. {checkpoint}, {config}).
    The command must write {out} (video-only is fine; audio is muxed later).
    """
    template = cfg.get("command")
    if not template:
        raise EngineNotReady(f"Engine {name} chưa có lệnh chạy trong engines/{name}.json")
    fmt = {"python": cfg.get("python", "python3"), "source": str(source),
           "audio": str(audio), "lip_audio": str(lip_audio), "out": str(out),
           "start": str(start), "dur": str(seg_dur), "width": str(width),
           "height": str(height), "fps": str(fps), "quality": quality,
           "workdir": str(workdir)}
    fmt.update({k: str(v) for k, v in cfg.items() if k not in ("command", "note")})
    # template is a full shell line; run it through bash -c so engines can
    # use their own venv activation / env vars.
    r = subprocess.run(["bash", "-c", template.format(**fmt)],
                       capture_output=True, text=True, timeout=3600)
    if r.returncode != 0:
        raise RuntimeError(f"Engine {name} lỗi ở đoạn {start}s: {(r.stderr or r.stdout)[-400:]}")
    if not out.exists() or out.stat().st_size == 0:
        raise RuntimeError(f"Engine {name} chạy xong nhưng không tạo được file đoạn {start}s")


def run_engine_segment(*, engine: str, mode: str, source: Path, audio: Path,
                       lip_audio: Path, start: float, seg_dur: float, out: Path,
                       width: int, height: int, fps: int, quality: str,
                       workdir: Path) -> None:
    if engine == "test":
        _ffmpeg_segment_video(source, audio, start, seg_dur, out, width, height, fps)
        return
    if engine == "musetalk":
        cfg = _check_configured("musetalk", [cfg_ckpt for cfg_ckpt in
                                             [_engine_config("musetalk").get("checkpoint", "")] if cfg_ckpt])
        _run_external("musetalk", cfg, source, audio, lip_audio, start, seg_dur,
                      out, width, height, fps, quality, workdir)
        return
    if engine == "latentsync":
        cfg = _check_configured("latentsync", [c for c in
                                               [_engine_config("latentsync").get("checkpoint", ""),
                                                _engine_config("latentsync").get("config", "")]
                                               if c])
        _run_external("latentsync", cfg, source, audio, lip_audio, start, seg_dur,
                      out, width, height, fps, quality, workdir)
        return
    raise ValueError(f"Engine không hỗ trợ: {engine}")
