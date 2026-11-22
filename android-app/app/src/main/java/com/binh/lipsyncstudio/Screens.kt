package com.binh.lipsyncstudio

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.io.File

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun statusLabel(status: String): String = when (status) {
    "awaiting_upload" -> "Chờ tải file"
    "queued" -> "Đã xếp hàng"
    "analyzing" -> "Đang phân tích nhạc"
    "separating" -> "Đang tách giọng hát"
    "preparing" -> "Đang chuẩn bị"
    "lipsync" -> "Đang xử lý lip sync"
    "muxing" -> "Đang ghép video"
    "done" -> "Hoàn thành"
    "failed" -> "Thất bại"
    "cancelled" -> "Đã hủy"
    else -> status
}

@Composable
fun ChipRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        options.forEach { opt ->
            val sel = opt == selected
            if (sel) Button(onClick = { onSelect(opt) }, modifier = Modifier.height(36.dp)) { Text(opt, fontSize = 13.sp) }
            else OutlinedButton(onClick = { onSelect(opt) }, modifier = Modifier.height(36.dp)) { Text(opt, fontSize = 13.sp) }
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
fun CreateScreen(
    draft: Draft,
    onDraftChange: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onCreate: () -> Unit,
    activeProject: Project?,
    onCancel: (String) -> Unit,
    busy: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))) {
            Column(Modifier.padding(14.dp)) {
                Text("1. Ảnh / video ca sĩ", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickImage) { Text("Chọn ảnh") }
                    OutlinedButton(onClick = onPickVideo) { Text("Chọn video") }
                }
                if (draft.sourceName.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Đã chọn: ${draft.sourceName}", color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    draft.sourceUri?.let { uri ->
                        if (draft.sourceIsVideo) {
                            VideoPreview(uri = uri, modifier = Modifier.fillMaxWidth().height(200.dp))
                        } else {
                            AsyncImage(model = uri, contentDescription = "Ảnh ca sĩ",
                                modifier = Modifier.fillMaxWidth().height(200.dp))
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))) {
            Column(Modifier.padding(14.dp)) {
                Text("2. Bài hát", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onPickAudio) { Text("Chọn bài hát MP3 / WAV") }
                if (draft.audioName.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Đã chọn: ${draft.audioName}", color = Color.White, fontSize = 13.sp)
                    Text("Thời lượng: ${formatDuration(draft.audioDurationMs)}", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))) {
            Column(Modifier.padding(14.dp)) {
                Text("3. Cài đặt video", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(10.dp))
                ChipRow("Chế độ tạo", listOf("video", "image"), draft.mode) {
                    draft.mode = it; onDraftChange()
                }
                Text("video = nhép miệng trên video có sẵn (khuyên dùng). image = tạo từ 1 ảnh.", color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                ChipRow("Tỉ lệ", listOf("16:9", "9:16", "1:1"), draft.aspect) {
                    draft.aspect = it; onDraftChange()
                }
                ChipRow("Độ phân giải", listOf("480p", "720p", "1080p"), draft.resolution) {
                    draft.resolution = it; onDraftChange()
                }
                ChipRow("FPS", listOf("24", "25", "30"), draft.fps.toString()) {
                    draft.fps = it.toInt(); onDraftChange()
                }
                ChipRow("Chất lượng", listOf("fast", "balanced", "high"), draft.quality) {
                    draft.quality = it; onDraftChange()
                }
                ChipRow("Engine AI", listOf("musetalk", "latentsync", "test"), draft.engine) {
                    draft.engine = it; onDraftChange()
                }
                if (draft.engine == "test") {
                    Text("Engine 'test' chỉ kiểm tra pipeline bằng FFmpeg — KHÔNG phải AI lip sync, video ra sẽ không nhép miệng.",
                        color = Color(0xFFFFB74D), fontSize = 12.sp)
                } else {
                    Text("MuseTalk / LatentSync cần máy chủ GPU đã cài weights. Nếu máy chủ chưa cài, job sẽ báo lỗi rõ ràng chứ không trả video giả.",
                        color = Color.LightGray, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tách vocal trước khi nhép (giúp môi khớp giọng hơn)", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = draft.separateVocal, onCheckedChange = { draft.separateVocal = it; onDraftChange() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chỉ xử lý mẫu 15 giây đầu để kiểm tra", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = draft.sampleOnly, onCheckedChange = { draft.sampleOnly = it; onDraftChange() })
                }
            }
        }

        Button(
            onClick = onCreate,
            enabled = !busy && draft.sourceUri != null && draft.audioUri != null,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("TẠO VIDEO", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

        activeProject?.let { p ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF263238))) {
                Column(Modifier.padding(14.dp)) {
                    Text("Tiến trình: ${statusLabel(p.status)}", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(p.stage, color = Color.White, fontSize = 13.sp)
                    if (p.message.isNotEmpty()) Text(p.message, color = Color(0xFFFFB74D), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { p.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${p.progress}%", color = Color.White, fontSize = 12.sp)
                    if (p.status in listOf("queued", "analyzing", "separating", "preparing", "lipsync", "muxing")) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { onCancel(p.jobId) }) { Text("Hủy công việc") }
                    }
                }
            }
        }
        if (busy && activeProject == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text("Đang gửi file lên máy chủ...")
            }
        }
    }
}

@Composable
fun VideoPreview(uri: Uri, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    AndroidView(factory = {
        PlayerView(it).apply {
            player = ExoPlayer.Builder(it).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = false
            }
            useController = true
        }
    }, modifier = modifier, onRelease = { (it.player as? ExoPlayer)?.release() })
}

@Composable
fun PlayerScreen(videoPath: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedButton(onClick = onBack) { Text("← Quay lại") }
        Spacer(Modifier.height(12.dp))
        VideoPreview(uri = Uri.fromFile(File(videoPath)), modifier = Modifier.fillMaxWidth().height(360.dp))
        Spacer(Modifier.height(12.dp))
        Text("Video: $videoPath", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
fun MyVideosScreen(
    projects: List<Project>,
    onPlay: (Project) -> Unit,
    onShare: (Project) -> Unit,
    onRename: (Project, String) -> Unit,
    onDelete: (Project) -> Unit,
    onResume: (Project) -> Unit
) {
    val ctx = LocalContext.current
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var renameText by remember { mutableStateOf("") }
    if (projects.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Chưa có video nào. Vào tab 'Tạo mới' để làm video đầu tiên.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(projects, key = { it.jobId }) { p ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.title, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("${p.audioName} • ${formatDuration(p.durationMs)} • ${p.engine} • ${p.aspect} ${p.resolution}",
                        color = Color.LightGray, fontSize = 12.sp)
                    Text("Trạng thái: ${statusLabel(p.status)} — ${p.progress}%", color = Color.White, fontSize = 13.sp)
                    if (p.message.isNotEmpty()) Text(p.message, color = Color(0xFFFFB74D), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (p.status == "done" && p.localResultPath.isNotEmpty()) {
                            Button(onClick = { onPlay(p) }) { Text("Phát") }
                            OutlinedButton(onClick = { onShare(p) }) { Text("Chia sẻ") }
                        }
                        if (p.status == "failed" || p.status == "cancelled") {
                            Button(onClick = { onResume(p) }) { Text("Tiếp tục") }
                        }
                        OutlinedButton(onClick = { renameTarget = p; renameText = p.title }) { Text("Đổi tên") }
                        OutlinedButton(onClick = { onDelete(p) }) { Text("Xóa") }
                    }
                }
            }
        }
    }
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Đổi tên video") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(target, renameText.ifBlank { target.title })
                    renameTarget = null
                }) { Text("Lưu") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Hủy") } }
        )
    }
}

@Composable
fun SettingsScreen(
    backendUrl: String,
    onBackendUrlChange: (String) -> Unit,
    onCheckConnection: () -> Unit,
    health: String,
    onClearTemp: () -> Unit,
    storageInfo: String
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cài đặt", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Địa chỉ backend (máy chủ GPU)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        OutlinedTextField(value = backendUrl, onValueChange = onBackendUrlChange,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("http://192.168.1.10:8000") })
        Text("Mẹo: chạy backend trên máy tính cùng mạng Wi-Fi thì điền IP LAN của máy đó. Dùng giả lập Android Studio thì để http://10.0.2.2:8000.",
            fontSize = 12.sp, color = Color.Gray)
        Button(onClick = onCheckConnection) { Text("Kiểm tra kết nối máy chủ") }
        if (health.isNotEmpty()) Text(health, fontSize = 13.sp)
        Text("Phiên bản app: 0.1.0 (giai đoạn 1–2)", fontSize = 13.sp)
        Text("Dung lượng video đã tải: $storageInfo", fontSize = 13.sp)
        OutlinedButton(onClick = onClearTemp) { Text("Xóa dữ liệu tạm (cache upload)") }
        Text("Lưu ý: MuseTalk/LatentSync chạy trên máy chủ GPU, không chạy trên điện thoại. App chỉ gửi file và nhận video về.",
            fontSize = 12.sp, color = Color.Gray)
    }
}

fun shareVideo(ctx: android.content.Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, "Chia sẻ video"))
}
