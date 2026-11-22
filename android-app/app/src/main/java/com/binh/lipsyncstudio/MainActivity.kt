package com.binh.lipsyncstudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var draft by mutableStateOf(Draft())
    private var draftVersion by mutableStateOf(0)

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        draft = draft.copy(sourceUri = uri, sourceName = vm.fileName(uri), sourceIsVideo = false, mode = "image")
        draftVersion++
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        draft = draft.copy(sourceUri = uri, sourceName = vm.fileName(uri), sourceIsVideo = true, mode = "video")
        draftVersion++
    }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        draft = draft.copy(audioUri = uri, audioName = vm.fileName(uri), audioDurationMs = vm.audioDurationMs(uri))
        draftVersion++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    @Composable
    fun App() {
        val projects by vm.projects.collectAsState()
        val backendUrl by vm.backendUrl.collectAsState()
        val busy by vm.busy.collectAsState()
        val notice by vm.notice.collectAsState()
        val health by vm.health.collectAsState()
        var tab by remember { mutableStateOf(0) }
        var playing by remember { mutableStateOf<Project?>(null) }
        val snackbarHost = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val active = projects.firstOrNull {
            it.status in listOf("awaiting_upload", "queued", "analyzing", "separating", "preparing", "lipsync", "muxing")
        }
        // draftVersion forces recomposition when the mutable Draft changes
        @Suppress("UNUSED_EXPRESSION")
        draftVersion

        if (notice.isNotEmpty()) {
            scope.launch { snackbarHost.showSnackbar(notice); vm.say("") }
        }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                TabButton("Tạo mới", tab == 0) { tab = 0; playing = null }
                TabButton("Video của tôi", tab == 1) { tab = 1; playing = null }
                TabButton("Cài đặt", tab == 2) { tab = 2; playing = null }
            }
            when {
                playing != null -> PlayerScreen(videoPath = playing!!.localResultPath) { playing = null }
                tab == 0 -> CreateScreen(
                    draft = draft,
                    onDraftChange = { draftVersion++ },
                    onPickImage = { pickImage.launch("image/*") },
                    onPickVideo = { pickVideo.launch("video/*") },
                    onPickAudio = { pickAudio.launch("audio/*") },
                    onCreate = {
                        vm.createVideo(draft)
                        draft = Draft()
                        draftVersion++
                    },
                    activeProject = active,
                    onCancel = { vm.cancelJob(it) },
                    busy = busy
                )
                tab == 1 -> MyVideosScreen(
                    projects = projects,
                    onPlay = { playing = it },
                    onShare = { shareVideo(this@MainActivity, it.localResultPath) },
                    onRename = { p, t -> vm.renameJob(p.jobId, t) },
                    onDelete = { vm.deleteJob(it.jobId) },
                    onResume = { vm.resumeJob(it.jobId) }
                )
                else -> SettingsScreen(
                    backendUrl = backendUrl,
                    onBackendUrlChange = { vm.setBackendUrl(it) },
                    onCheckConnection = { vm.checkConnection() },
                    health = health,
                    onClearTemp = {
                        cacheDir.listFiles()?.forEach { it.delete() }
                        Toast.makeText(this@MainActivity, "Đã xóa cache", Toast.LENGTH_SHORT).show()
                    },
                    storageInfo = storageUsed()
                )
            }
            SnackbarHost(hostState = snackbarHost)
        }
    }

    @Composable
    private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
        if (selected) Button(onClick = onClick, modifier = Modifier.padding(4.dp)) { Text(label) }
        else OutlinedButton(onClick = onClick, modifier = Modifier.padding(4.dp)) { Text(label) }
    }

    private fun storageUsed(): String {
        val dir = File(getExternalFilesDir(null), "videos")
        var bytes = 0L
        dir.listFiles()?.forEach { bytes += it.length() }
        return if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}
