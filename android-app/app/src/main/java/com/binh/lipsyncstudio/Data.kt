package com.binh.lipsyncstudio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------- Storage
// NOTE (v0.1.0): dự án lưu bằng file JSON (projects.json) thay vì Room.
// Lý do: AGP 9.1 dùng built-in Kotlin 2.4.0 — KSP cho 2.4.0 chưa có trên
// plugin portal, kapt cũng bị chặn bởi built-in Kotlin. Khi KSP 2.4.x có
// mặt, có thể chuyển lại Room mà không đổi API của ProjectStore.
data class Project(
    val jobId: String,
    val title: String,
    val sourceName: String,
    val audioName: String,
    val sourceUri: String,
    val audioUri: String,
    val durationMs: Long,
    val mode: String,
    val engine: String,
    val aspect: String,
    val resolution: String,
    val fps: Int,
    val quality: String,
    val separateVocal: Boolean,
    val sampleOnly: Boolean,
    val backendUrl: String,
    val status: String,
    val progress: Int,
    val stage: String,
    val message: String,
    val localResultPath: String,
    val createdAt: Long
)

class ProjectStore(context: Context) {
    private val file = File(context.filesDir, "projects.json")
    private val _projects = MutableStateFlow(load())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private fun Project.toJson() = JSONObject().apply {
        put("jobId", jobId); put("title", title); put("sourceName", sourceName)
        put("audioName", audioName); put("sourceUri", sourceUri); put("audioUri", audioUri)
        put("durationMs", durationMs); put("mode", mode); put("engine", engine)
        put("aspect", aspect); put("resolution", resolution); put("fps", fps)
        put("quality", quality); put("separateVocal", separateVocal)
        put("sampleOnly", sampleOnly); put("backendUrl", backendUrl)
        put("status", status); put("progress", progress); put("stage", stage)
        put("message", message); put("localResultPath", localResultPath)
        put("createdAt", createdAt)
    }

    private fun JSONObject.toProject() = Project(
        jobId = getString("jobId"), title = getString("title"),
        sourceName = getString("sourceName"), audioName = getString("audioName"),
        sourceUri = getString("sourceUri"), audioUri = getString("audioUri"),
        durationMs = getLong("durationMs"), mode = getString("mode"),
        engine = getString("engine"), aspect = getString("aspect"),
        resolution = getString("resolution"), fps = getInt("fps"),
        quality = getString("quality"), separateVocal = getBoolean("separateVocal"),
        sampleOnly = getBoolean("sampleOnly"), backendUrl = getString("backendUrl"),
        status = getString("status"), progress = getInt("progress"),
        stage = getString("stage"), message = optString("message"),
        localResultPath = optString("localResultPath"),
        createdAt = getLong("createdAt"))

    private fun load(): List<Project> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getJSONObject(it).toProject() }
                .sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(list: List<Project>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
        _projects.value = list.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun upsert(p: Project) {
        val list = _projects.value.filter { it.jobId != p.jobId } + p
        persist(list)
    }

    fun get(jobId: String): Project? = _projects.value.firstOrNull { it.jobId == jobId }

    @Synchronized
    fun updateStatus(jobId: String, status: String, progress: Int, stage: String, message: String) {
        get(jobId)?.let { upsert(it.copy(status = status, progress = progress, stage = stage, message = message)) }
    }

    @Synchronized
    fun markDownloaded(jobId: String, path: String) {
        get(jobId)?.let { upsert(it.copy(localResultPath = path, status = "done", progress = 100, stage = "Hoàn thành")) }
    }

    @Synchronized
    fun rename(jobId: String, title: String) {
        get(jobId)?.let { upsert(it.copy(title = title)) }
    }

    @Synchronized
    fun delete(jobId: String) {
        persist(_projects.value.filter { it.jobId != jobId })
    }

    companion object {
        @Volatile private var INSTANCE: ProjectStore? = null
        fun get(context: Context): ProjectStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProjectStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}

// ---------------------------------------------------------------- API
data class JobCreateRequest(
    val mode: String,
    val engine: String,
    val aspect: String,
    val resolution: String,
    val fps: Int,
    val quality: String,
    val sample_only: Boolean,
    val separate_vocal: Boolean
)

data class JobStatusResponse(
    val id: String,
    val status: String,
    val stage: String = "",
    val progress: Int = 0,
    val message: String = "",
    val engine: String = "",
    val mode: String = "",
    val duration_seconds: Double = 0.0,
    val segments_total: Int = 0,
    val segments_done: Int = 0,
    val has_result: Boolean = false,
    val error: String = ""
)

data class HealthResponse(
    val status: String = "",
    val ffmpeg: Boolean = false,
    val gpu: Map<String, Any>? = null,
    val engines: Map<String, Map<String, Any>>? = null
)

data class CapabilitiesResponse(
    val engines: Map<String, Map<String, Any>>? = null,
    val demucs: Boolean = false,
    val ffmpeg: Boolean = false,
    val max_audio_seconds: Int = 600,
    val note: String = ""
)

interface LipSyncApi {
    @GET("api/v1/health")
    suspend fun health(): HealthResponse

    @GET("api/v1/capabilities")
    suspend fun capabilities(): CapabilitiesResponse

    @POST("api/v1/jobs")
    suspend fun createJob(@Body req: JobCreateRequest): JobStatusResponse

    @Multipart
    @POST("api/v1/jobs/{id}/upload")
    suspend fun upload(
        @Path("id") id: String,
        @Query("kind") kind: String,
        @Part file: MultipartBody.Part
    ): Map<String, Any>

    @POST("api/v1/jobs/{id}/start")
    suspend fun start(@Path("id") id: String): JobStatusResponse

    @GET("api/v1/jobs/{id}")
    suspend fun status(@Path("id") id: String): JobStatusResponse

    @POST("api/v1/jobs/{id}/cancel")
    suspend fun cancel(@Path("id") id: String): JobStatusResponse

    @GET("api/v1/jobs/{id}/result")
    suspend fun result(@Path("id") id: String): ResponseBody
}

object BackendClient {
    fun api(baseUrl: String): LipSyncApi {
        val url = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "Địa chỉ backend phải bắt đầu bằng http:// hoặc https://"
        }
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LipSyncApi::class.java)
    }

    /** Copy a SAF uri to a temp file so Retrofit can upload it as multipart. */
    fun uriToTempFile(context: Context, uri: Uri, name: String): File {
        val out = File(context.cacheDir, "upload-${System.currentTimeMillis()}-$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Không đọc được file: $name")
        return out
    }

    fun uploadPart(file: File): MultipartBody.Part {
        val body = file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
        return MultipartBody.Part.createFormData("file", file.name, body)
    }
}
