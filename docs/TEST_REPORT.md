# TEST REPORT — AI Music Lip Sync Studio

Điền sau mỗi lần kiểm thử. Chỉ ghi kết quả đã chạy thật.

## 2026-11-23 — Giai đoạn 1–2 (xây dựng)

- [x] Build APK debug local thành công — `assembleDebug` BUILD SUCCESSFUL,
  APK 16MB, md5 `8f7a5f6972dcb2ff82a16a69df433843`
- [x] Chữ ký: apksigner verify — v2 scheme: true (1 signer, debug key)
- [x] minSdk 24 / targetSdk 36 / package `com.binh.lipsyncstudio` v0.1.0
  (kiểm bằng `aapt2 dump badging`)
- [x] Backend pytest `tests/test_pipeline.py`: **1 passed** — job đầy đủ
  (create → upload ảnh + nhạc → start → poll → result), MP4 đúng 6.0s,
  có cả video + audio (nhạc gốc)
- [x] Backend chạy thật bằng uvicorn (HTTP, không chỉ TestClient): health,
  capabilities, job flow hoàn chỉnh, MP4 ra đúng 6.000s, có audio
- [x] GitHub Actions build APK thành công — run 45049467470, 3m7s, artifact
  `lip-sync-studio-debug-apk` (sau khi sửa lỗi gói SDK `tools` lỗi thời)
- [x] GitHub Actions backend tests thành công — run 45049400534, 35s
- [ ] Cài được trên điện thoại Android thật (chưa test — chờ Bình cài)
- [ ] Chọn ảnh / video / MP3 từ điện thoại (chưa test trên máy thật)
- [ ] Đọc đúng thời lượng MP3 (chưa test trên máy thật)

## Chưa thể kiểm thử (cần máy chủ GPU)

- [ ] Xử lý đoạn hát thử ngắn bằng MuseTalk/LatentSync thật
- [ ] Kiểm tra đồng bộ môi với giọng tiếng Việt
- [ ] Xử lý bài hát 3–5 phút, kiểm tra điểm nối đoạn
- [ ] Hủy và tiếp tục job

## Lỗi đã gặp và đã sửa trong ngày

1. Proxy CA xoay → PKIX signature check failed: import lại PEM vào cacerts JDK 17.
2. KSP 2.4.0 chưa có trên plugin portal; kapt bị AGP 9.1 built-in Kotlin chặn
   → bỏ Room, lưu dự án bằng file JSON (ProjectStore).
3. Engine `test` với ảnh tĩnh ra video 0.0s (ảnh chỉ có 1 frame) → thêm
   `-loop 1` cho input ảnh.
4. `uvicorn app.main:app` lỗi `No module named 'engines'` → import 2 chế độ
   (`from app.engines` / `from engines`) trong main.py.
5. Worker `return try { while(true){} }` lỗi kiểu Unit → viết try dạng statement.
