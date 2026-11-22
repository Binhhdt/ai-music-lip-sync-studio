# AI Music Lip Sync Studio

Ứng dụng Android tạo video ca sĩ AI hát nhép theo bài hát MP3/WAV (3–5 phút),
phục vụ đăng YouTube / TikTok / Facebook. App Android là client; xử lý AI nặng
chạy trên backend Python + FastAPI có GPU.

> **Nói thẳng trước:** Muse (Conan) là môi trường lập trình — **không có GPU,
> không có API lip sync chính thức**. Muse viết code, build APK, quản lý GitHub.
> Lip sync thật do **MuseTalk / LatentSync** chạy trên máy chủ GPU riêng.
> Trong repo này, engine `test` chỉ kiểm tra pipeline bằng FFmpeg và được ghi
> rõ là **không phải AI lip sync** — không bao giờ trả video giả làm kết quả AI.

## Trạng thái thật (cập nhật khi build xong — xem `docs/TEST_REPORT.md`)

| Hạng mục | Trạng thái |
|---|---|
| App Android (chọn ảnh/video/MP3, cài đặt, upload, theo dõi, tải kết quả) | ✅ Giai đoạn 1–2 — APK debug v0.1.0 build thành công 23/11/2026 (16MB, minSdk 24, ký v2) |
| Backend FastAPI (jobs API v1, chunking 30s, nối đoạn, ghép nhạc gốc) | ✅ Giai đoạn 1–2 — pytest 1 passed + chạy thật bằng uvicorn, MP4 ra đúng thời lượng, có nhạc gốc |
| Engine `test` (FFmpeg, kiểm tra pipeline end-to-end) | ✅ chạy thật, không GPU |
| Engine MuseTalk | ⚠️ Adapter có sẵn, **chưa cài weights/GPU** — job sẽ báo lỗi rõ ràng thay vì trả video giả |
| Engine LatentSync | ⚠️ Adapter có sẵn, **chưa cài weights/GPU** |
| Tách vocal Demucs | ⚠️ Tùy chọn, chạy khi máy chủ có `demucs`, không thì bỏ qua có ghi chú |
| Kiểm thử lip sync với giọng thật / bài 3–5 phút | ❌ Chưa thể — cần máy chủ GPU (xem `docs/GPU_DEPLOY.md`) |

## Cấu trúc

```
android-app/          App Kotlin + Jetpack Compose, minSdk 24
backend/              FastAPI: app/main.py, app/engines.py, tests/
backend/engines/      File cấu hình engine (musetalk.json, latentsync.json) — tạo khi deploy GPU
docs/                 ARCHITECTURE.md, GPU_DEPLOY.md, LICENSES.md, TEST_REPORT.md
.github/workflows/    build-apk.yml (build APK debug), backend-test.yml
```

## API backend (v1)

- `GET /api/v1/health` — trạng thái server, GPU, engine
- `GET /api/v1/capabilities` — engine nào sẵn sàng, giới hạn
- `POST /api/v1/jobs` — tạo job (JSON: mode, engine, aspect, resolution, fps, quality, sample_only, separate_vocal)
- `POST /api/v1/jobs/{id}/upload?kind=source|audio` — tải file lên
- `POST /api/v1/jobs/{id}/start` — bắt đầu xử lý (tiếp tục được sau lỗi: đoạn đã xong không chạy lại)
- `GET /api/v1/jobs/{id}` — trạng thái + % tiến trình thật (theo số đoạn đã xong)
- `POST /api/v1/jobs/{id}/cancel` — hủy
- `GET /api/v1/jobs/{id}/result` — tải MP4 kết quả (nhạc gốc, không đổi cao độ/tốc độ)

## Chạy backend local (chỉ engine test, không cần GPU)

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
# kiểm tra: curl http://localhost:8000/api/v1/health
```

## Build APK

- Tự động: push lên GitHub → Actions build APK debug → tải ở tab Actions → Artifacts.
- Local: `cd android-app && ./gradlew assembleDebug` (cần JDK 17 + Android SDK).
- Cài trên điện thoại: tải APK → mở file → cho phép "Cài đặt từ nguồn không xác định".

## Giấy phép engine (kiểm chứng 23/11/2026)

- **MuseTalk**: code MIT; weights "available for any purpose, even commercially"
  theo README upstream. Các dependency (Whisper, VAE, DWPose, S3FD...) tuân theo
  giấy phép riêng của chúng.
- **LatentSync**: code Apache-2.0; weights OpenRAIL++-M (có giới hạn sử dụng);
  face detector InsightFace mặc định chỉ dùng cho nghiên cứu phi thương mại —
  cần thay detector khác nếu dùng thương mại. VRAM: 8 GB (v1.5) / 18 GB (v1.6).
- Chỉ dùng ảnh/video của chính mình hoặc có quyền sử dụng. Không dùng app để
  giả mạo người thật.

## Chi phí

- App + backend + engine mã nguồn mở: **miễn phí**.
- Chi phí duy nhất: **thuê máy chủ GPU** để chạy MuseTalk/LatentSync
  (VD: RunPod / Vast.ai / Paperspace — giá theo giờ, Bình tự chọn; repo không
  gắn sẵn nhà cung cấp nào). Không có API key nào trong code.
