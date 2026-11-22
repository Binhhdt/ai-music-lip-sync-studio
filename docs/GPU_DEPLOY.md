# Triển khai backend lên máy chủ GPU

Máy chủ cần: Ubuntu 22.04, GPU NVIDIA (MuseTalk ≥ 6–8 GB VRAM, LatentSync 1.5 ≥ 8 GB,
LatentSync 1.6 ≥ 18 GB), Python 3.10/3.11, FFmpeg, CUDA driver.

## 1. Cài backend

```bash
git clone https://github.com/Binhhdt/ai-music-lip-sync-studio.git
cd ai-music-lip-sync-studio/backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pip install torch --index-url https://download.pytorch.org/whl/cu121  # theo CUDA của máy
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## 2. Cài MuseTalk (mặc định — video có sẵn, nhanh)

```bash
cd ~
git clone https://github.com/TMElyralab/MuseTalk.git
cd MuseTalk
pip install -r requirements.txt
# Tải weights theo README của repo (musetalkV15, sd-vae-ft-mse, whisper, dwpose, face-parsing, s3fd)
```

Tạo file `backend/engines/musetalk.json` — **mẫu dưới đây phải kiểm chứng lại
theo README của repo MuseTalk tại thời điểm cài** (tên script/tham số có thể đổi
theo phiên bản). Adapter chạy lệnh trong `command` qua `bash -c`; lệnh phải ghi
video đoạn ra `{out}` từ video/ảnh nguồn `{source}` và đoạn nhạc `{lip_audio}`
(bản vocal đã tách nếu có, không thì nhạc gốc), bắt đầu ở giây `{start}`, dài `{dur}`s:

```json
{
  "note": "Kiểm chứng lệnh theo README MuseTalk trước khi dùng thật",
  "python": "/path/to/musetalk-venv/bin/python",
  "checkpoint": "/path/to/MuseTalk/models/musetalk/musetalkV15/unet.pth",
  "command": "cd /path/to/MuseTalk && {python} -m scripts.inference --video_path {source} --audio_path {lip_audio} --video_out_path {out} --start {start} --duration {dur}"
}
```

## 3. Cài LatentSync (chất lượng cao hơn, chậm hơn)

```bash
git clone https://github.com/bytedance/LatentSync.git
# Làm theo README: tải checkpoint ByteDance/LatentSync-1.5 (8 GB VRAM) hoặc 1.6 (18 GB)
```

`backend/engines/latentsync.json` (kiểm chứng lại theo README LatentSync):

```json
{
  "note": "Kiểm chứng lệnh theo README LatentSync trước khi dùng thật",
  "python": "/path/to/latentsync-venv/bin/python",
  "checkpoint": "/path/to/LatentSync/checkpoints/latentsync_unet.pt",
  "config": "/path/to/LatentSync/configs/unet/stage2.yaml",
  "command": "cd /path/to/LatentSync && {python} -m scripts.inference --unet_config_path {config} --inference_ckpt_path {checkpoint} --video_path {source} --audio_path {lip_audio} --video_out_path {out}"
}
```

Lưu ý: LatentSync xử lý cả video; với ảnh tĩnh cần tạo video nền từ ảnh trước
(backend sẽ bổ sung bước này khi engine được cấu hình — hiện tại mode `image`
với engine AI cần video nền do engine tự tạo theo lệnh trong config).

## 4. Tách vocal (tùy chọn)

```bash
pip install demucs
# Backend tự phát hiện lệnh `demucs`; nếu không có, job vẫn chạy bằng nhạc gốc và ghi chú rõ.
```

## 5. Chạy production

- Dùng `systemd` hoặc `tmux` giữ uvicorn chạy; đặt sau reverse proxy (Caddy/Nginx)
  với HTTPS. Không mở cổng 8000 trực tiếp ra Internet nếu chưa có xác thực.
- Biến môi trường: `LIPSYNC_DATA` (thư mục job), `LIPSYNC_SEGMENT_SECONDS`
  (mặc định 30), `LIPSYNC_MAX_AUDIO_SECONDS` (mặc định 600),
  `LIPSYNC_MAX_UPLOAD_MB` (mặc định 500).
- Dọn file tạm: job cũ tự xóa sau 7 ngày (chạy `scripts/cleanup.sh` bằng cron).

## 6. Kiểm thử trước khi giao cho app

1. `curl localhost:8000/api/v1/capabilities` — engine phải báo `available: true`.
2. Chạy 1 job mẫu 15s (`sample_only: true`) với video + nhạc thật.
3. Xem video: môi khớp giọng không, có nhòe/nhấp nháy ở điểm nối đoạn không.
4. Chạy 1 bài 3–5 phút, kiểm tra thời lượng MP4 = thời lượng nhạc (±1.5s).
5. Chỉ khi 4 bước trên đạt mới bật cho người dùng dùng engine AI.
