# Kiến trúc

## Tổng quan

```
Điện thoại Android (client)                Máy chủ GPU (backend)
┌─────────────────────────┐                ┌──────────────────────────┐
│ Compose UI              │  HTTPS/HTTP    │ FastAPI (app/main.py)  │
│  Tạo mới / Video / Cài  │◄──────────────►│  /api/v1/jobs ...      │
│ Room (dự án, lịch sử)   │  multipart     │  Job runner (thread)   │
│ WorkManager (poll nền)  │  upload        │   1. phân tích nhạc    │
│ Retrofit + OkHttp       │  polling       │   2. tách vocal (opt)  │
│ Media3 ExoPlayer        │  download MP4  │   3. chia đoạn 30s     │
└─────────────────────────┘                │   4. engine/đoạn       │
                                           │   5. FFmpeg nối + mux  │
                                           │      nhạc GỐC          │
                                           └──────────────────────────┘
```

## Vì sao chia đoạn 30s

MuseTalk/LatentSync xử lý video theo từng đoạn ngắn (giới hạn VRAM và bộ
nhớ). Backend chia nhạc thành các đoạn 30s, xử lý tuần tự, ghi lại đoạn nào
đã xong. Job lỗi giữa chừng → bắt đầu lại chỉ chạy các đoạn chưa xong.
Video cuối được nối bằng FFmpeg concat demuxer rồi ghép lại **nhạc gốc**
(`-map 1:a:0`), nên không đổi tốc độ/cao độ và không cắt đầu/cuối bài.

## Tiến trình thật

`progress` = 5% (phân tích) → 10% (tách vocal) → 15% (chuẩn bị) →
16% + 70% × (đoạn đã xong / tổng đoạn) → 88% (ghép) → 100% (xong).
App chỉ hiển thị số backend trả về; không có % giả.

## Engine

Mỗi engine là một adapter trong `backend/app/engines.py` với cùng một hàm:
`run_engine_segment(engine, source, audio, lip_audio, start, dur, out, ...)`.
Thêm engine mới = thêm adapter + khai báo trong `capabilities`, không sửa
app Android.

## Giới hạn đã biết (ghi rõ, không giấu)

- MuseTalk chỉ nhép môi trên video có sẵn; ảnh tĩnh cần tạo video nền trước
  (chuyển động đầu/chớp mắt không phải do MuseTalk tạo ra).
- LatentSync cũng vậy: chỉnh môi trên video có sẵn, không tạo chuyển động
  đầu/cơ thể.
- Đồng bộ môi–giọng tiếng Việt (nguyên âm kéo dài, ngân rung bolero) chưa
  được kiểm chứng — cần test với bài thật trên GPU trước khi khẳng định.
- Không có GPU = không có lip sync AI. Engine `test` chỉ kiểm tra pipeline.
