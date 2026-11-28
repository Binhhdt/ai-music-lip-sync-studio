# Mẫu Noel 15 giây — test lip sync thật khi có máy GPU

Thư mục này là project mẫu sẵn để test lip sync với nhạc Noel, khỏi mất
công set up lại khi có máy GPU.

## Cần chuẩn bị (Bình gửi hoặc tự bỏ vào thư mục này)

- `singer.jpg` (hoặc `singer.png`) — ảnh chân dung ca sĩ
- `song-15s.mp3` — đoạn nhạc Noel 15 giây muốn thử (cắt từ bài đầy đủ)

Chưa có 2 file này thì script sẽ báo thiếu, không chạy.

## Cách chạy (từ máy có backend GPU đang chạy)

```bash
export LIPSYNC_URL=http://<IP-may-GPU>:8000   # VD: http://192.168.1.10:8000
./run-sample.sh
```

Script làm đúng luồng thật của app: tạo job → upload ảnh + nhạc →
start → poll trạng thái → tải `noel-15s-result.mp4` về thư mục này.

## Cài đặt mẫu (xem `job.json`)

- Chế độ: `video` nếu có video ca sĩ, đổi `mode` thành `image` nếu chỉ có ảnh
- Engine: `musetalk` (đổi thành `latentsync` nếu máy GPU đủ VRAM)
- Tỉ lệ 9:16 (TikTok/Shorts), 720p, 25fps, `sample_only: true` (chỉ 15s đầu)
- Tách vocal: tắt mặc định (bật lên nếu máy có Demucs)

Đạt mẫu 15s rồi mới chạy bài Noel đầy đủ 3–5 phút.
