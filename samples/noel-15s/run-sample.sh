#!/usr/bin/env bash
# Chạy mẫu Noel 15s trọn luồng backend (cần máy GPU đã cài engine).
# Đặt singer.jpg/png + song-15s.mp3 vào cùng thư mục rồi chạy script này.
set -euo pipefail
cd "$(dirname "$0")"

BASE="${LIPSYNC_URL:?Chưa đặt LIPSYNC_URL, VD: export LIPSYNC_URL=http://192.168.1.10:8000}"
SOURCE="$(ls singer.jpg singer.png singer.jpeg singer.webp 2>/dev/null | head -1 || true)"
AUDIO="song-15s.mp3"
[ -n "$SOURCE" ] || { echo "Thiếu ảnh ca sĩ: đặt singer.jpg vào thư mục này"; exit 1; }
[ -f "$AUDIO" ] || { echo "Thiếu nhạc: đặt song-15s.mp3 (15 giây) vào thư mục này"; exit 1; }

JOB_ID=$(curl -s -X POST "$BASE/api/v1/jobs" -H 'Content-Type: application/json' \
  --data @job.json | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
echo "Job: $JOB_ID"
curl -s -X POST "$BASE/api/v1/jobs/$JOB_ID/upload?kind=source" -F "file=@$SOURCE" > /dev/null
curl -s -X POST "$BASE/api/v1/jobs/$JOB_ID/upload?kind=audio" -F "file=@$AUDIO" > /dev/null
curl -s -X POST "$BASE/api/v1/jobs/$JOB_ID/start" > /dev/null

while true; do
  STATUS=$(curl -s "$BASE/api/v1/jobs/$JOB_ID")
  ST=$(echo "$STATUS" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["status"], d["progress"], d["stage"])')
  echo "$ST"
  case "$ST" in
    done*) break ;;
    failed*|cancelled*) echo "$STATUS"; exit 1 ;;
  esac
  sleep 5
done
curl -s -o noel-15s-result.mp4 "$BASE/api/v1/jobs/$JOB_ID/result"
echo "Xong: noel-15s-result.mp4"
ffprobe -v error -show_entries format=duration -of csv=p=0 noel-15s-result.mp4
