package com.binh.lipsyncstudio

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Draft(
    var sourceUri: Uri? = null,
    var sourceName: String = "",
    var sourceIsVideo: Boolean = false,
    var audioUri: Uri? = null,
    var audioName: String = "",
    var audioDurationMs: Long = 0L,
    var mode: String = "video",
    var engine: String = "musetalk",
    var aspect: String = "16:9",
    var resolution: String = "720p",
    var fps: Int = 25,
    var quality: String = "balanced",
    var separateVocal: Boolean = false,
    var sampleOnly: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore.get(app)
    private val prefs = app.getSharedPreferences("lipsync", Context.MODE_PRIVATE)

    val projects: StateFlow<List<Project>> = store.projects

    private val _backendUrl = MutableStateFlow(
        prefs.getString("backend_url", "http://10.0.2.2:8000") ?: "http://10.0.2.2:8000")
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice.asStateFlow()

    private val _health = MutableStateFlow("")
    val health: StateFlow<String> = _health.asStateFlow()

    fun setBackendUrl(url: String) {
        _backendUrl.value = url.trim()
        prefs.edit().putString("backend_url", _backendUrl.value).apply()
    }

    fun say(msg: String) { _notice.value = msg }

    fun fileName(uri: Uri): String {
        val ctx = getApplication<Application>()
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx) ?: "file"
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    fun audioDurationMs(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(getApplication(), uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    fun checkConnection() {
        viewModelScope.launch {
            _health.value = "Đang kiểm tra..."
            try {
                val h = withContext(Dispatchers.IO) { BackendClient.api(_backendUrl.value).health() }
                val caps = withContext(Dispatchers.IO) { BackendClient.api(_backendUrl.value).capabilities() }
                val engines = caps.engines?.entries?.joinToString(", ") { (k, v) ->
                    "$k: " + if (v["available"] == true) "sẵn sàng" else "chưa sẵn sàng"
                } ?: ""
                _health.value = "Kết nối OK — FFmpeg: ${if (h.ffmpeg) "có" else "không"}. Engine: $engines"
            } catch (e: Exception) {
                _health.value = "Không kết nối được: ${e.message}"
            }
        }
    }

    /** Create job -> upload both files -> start -> poll in foreground.
     *  A WorkManager worker is also enqueued so the job keeps being tracked
     *  if the app goes to background. */
    fun createVideo(draft: Draft) {
        val ctx = getApplication<Application>()
        val sourceUri = draft.sourceUri ?: return say("Chưa chọn ảnh/video")
        val audioUri = draft.audioUri ?: return say("Chưa chọn bài hát")
        viewModelScope.launch {
            _busy.value = true
            try {
                val api = BackendClient.api(_backendUrl.value)
                val created = withContext(Dispatchers.IO) {
                    api.createJob(JobCreateRequest(
                        mode = draft.mode, engine = draft.engine, aspect = draft.aspect,
                        resolution = draft.resolution, fps = draft.fps,
                        quality = draft.quality, sample_only = draft.sampleOnly,
                        separate_vocal = draft.separateVocal))
                }
                val project = Project(
                    jobId = created.id,
                    title = "${draft.audioName} — ${draft.sourceName}",
                    sourceName = draft.sourceName, audioName = draft.audioName,
                    sourceUri = sourceUri.toString(), audioUri = audioUri.toString(),
                    durationMs = draft.audioDurationMs, mode = draft.mode,
                    engine = draft.engine, aspect = draft.aspect,
                    resolution = draft.resolution, fps = draft.fps,
                    quality = draft.quality, separateVocal = draft.separateVocal,
                    sampleOnly = draft.sampleOnly, backendUrl = _backendUrl.value,
                    status = created.status, progress = 0, stage = "Đang tải file lên",
                    message = "", localResultPath = "", createdAt = System.currentTimeMillis())
                store.upsert(project)

                withContext(Dispatchers.IO) {
                    val srcFile = BackendClient.uriToTempFile(ctx, sourceUri, draft.sourceName)
                    api.upload(created.id, "source", BackendClient.uploadPart(srcFile))
                    srcFile.delete()
                    val audFile = BackendClient.uriToTempFile(ctx, audioUri, draft.audioName)
                    api.upload(created.id, "audio", BackendClient.uploadPart(audFile))
                    audFile.delete()
                    api.start(created.id)
                }
                say("Đã gửi job ${created.id} — đang xử lý trên máy chủ")
                enqueuePollWorker(created.id, _backendUrl.value)
                pollUntilDone(created.id)
            } catch (e: Exception) {
                say("Lỗi tạo video: ${e.message}")
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun pollUntilDone(jobId: String) {
        val api = BackendClient.api(_backendUrl.value)
        while (true) {
            val st = try {
                withContext(Dispatchers.IO) { api.status(jobId) }
            } catch (e: Exception) {
                say("Mất kết nối khi theo dõi job: ${e.message}")
                return
            }
            store.updateStatus(jobId, st.status, st.progress, st.stage,
                st.message.ifBlank { st.error })
            when (st.status) {
                "done" -> {
                    downloadResult(jobId)
                    return
                }
                "failed" -> {
                    say("Job thất bại: ${st.error.ifBlank { st.message }}")
                    return
                }
                "cancelled" -> {
                    say("Job đã hủy")
                    return
                }
            }
            delay(3000)
        }
    }

    suspend fun downloadResult(jobId: String) {
        val project = store.get(jobId) ?: return
        try {
            val api = BackendClient.api(project.backendUrl)
            val body = withContext(Dispatchers.IO) { api.result(jobId) }
            val dir = File(getApplication<Application>().getExternalFilesDir(null), "videos")
            dir.mkdirs()
            val out = File(dir, "lipsync-$jobId.mp4")
            withContext(Dispatchers.IO) {
                body.byteStream().use { input -> out.outputStream().use { output -> input.copyTo(output) } }
            }
            store.markDownloaded(jobId, out.absolutePath)
            say("Đã tải video xong: ${out.name}")
        } catch (e: Exception) {
            say("Tải kết quả thất bại: ${e.message}")
        }
    }

    fun cancelJob(jobId: String) {
        viewModelScope.launch {
            try {
                val project = store.get(jobId) ?: return@launch
                withContext(Dispatchers.IO) { BackendClient.api(project.backendUrl).cancel(jobId) }
                store.updateStatus(jobId, "cancelled", 0, "Đã hủy", "Người dùng đã hủy")
            } catch (e: Exception) {
                say("Hủy thất bại: ${e.message}")
            }
        }
    }

    /** Continue a failed job: server re-runs only the unfinished segments. */
    fun resumeJob(jobId: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val project = store.get(jobId) ?: return@launch
                withContext(Dispatchers.IO) { BackendClient.api(project.backendUrl).start(jobId) }
                enqueuePollWorker(jobId, project.backendUrl)
                pollUntilDone(jobId)
            } catch (e: Exception) {
                say("Tiếp tục job thất bại: ${e.message}")
            } finally {
                _busy.value = false
            }
        }
    }

    fun renameJob(jobId: String, title: String) {
        viewModelScope.launch { store.rename(jobId, title) }
    }

    fun deleteJob(jobId: String) {
        viewModelScope.launch {
            val p = store.get(jobId)
            p?.localResultPath?.let { File(it).delete() }
            store.delete(jobId)
        }
    }

    private fun enqueuePollWorker(jobId: String, backendUrl: String) {
        val req = OneTimeWorkRequestBuilder<JobPollWorker>()
            .setInputData(Data.Builder()
                .putString("jobId", jobId)
                .putString("backendUrl", backendUrl)
                .build())
            .build()
        WorkManager.getInstance(getApplication()).enqueue(req)
    }
}

/** Background worker: polls the job and downloads the result even if the
 *  user leaves the app. Updates the same project row the UI observes. */
class JobPollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString("jobId") ?: return Result.failure()
        val backendUrl = inputData.getString("backendUrl") ?: return Result.failure()
        val store = ProjectStore.get(applicationContext)
        val api = BackendClient.api(backendUrl)
        try {
            while (true) {
                val st = api.status(jobId)
                store.updateStatus(jobId, st.status, st.progress, st.stage,
                    st.message.ifBlank { st.error })
                when (st.status) {
                    "done" -> {
                        val body = api.result(jobId)
                        val dir = File(applicationContext.getExternalFilesDir(null), "videos")
                        dir.mkdirs()
                        val out = File(dir, "lipsync-$jobId.mp4")
                        body.byteStream().use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        store.markDownloaded(jobId, out.absolutePath)
                        return Result.success()
                    }
                    "failed", "cancelled" -> return Result.success()
                }
                delay(5000)
            }
        } catch (_: Exception) {
            return Result.retry()
        }
    }
}
