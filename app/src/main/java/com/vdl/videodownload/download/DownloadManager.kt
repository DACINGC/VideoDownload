package com.vdl.videodownload.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vdl.videodownload.core.MediaFormat
import com.vdl.videodownload.core.VideoInfo
import com.vdl.videodownload.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载队列与调度中心（进程内单例，由 Application 持有）。
 *
 * 职责：
 *  - 维护任务列表（StateFlow，供界面与通知观察）；
 *  - 按最大并发数调度 [DownloadRunner]；
 *  - 保存/恢复任务记录，处理暂停、继续、取消、删除。
 */
class DownloadManager(
    private val context: Context,
    private val settings: SettingsStore
) {

    private val store = DownloadStore(context)
    private val _items = MutableStateFlow(sortItems(store.load()))
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelReasons = ConcurrentHashMap<String, DownloadState>()
    private val dispatchMutex = Mutex()
    private val client = DownloadRunner.downloadClient()

    private var lastPersistAt = 0L
    private var lastWifiWarningAt = 0L

    init {
        // 清理上次进程遗留的、已不再需要的临时分片，避免占用缓存空间
        scope.launch { cleanupStaleParts() }
    }

    private fun cleanupStaleParts() {
        runCatching {
            val dir = java.io.File(context.cacheDir, "downloads")
            if (!dir.isDirectory) return@runCatching
            val keep = _items.value
                .filter {
                    it.state == DownloadState.QUEUED ||
                        it.state == DownloadState.RUNNING ||
                        it.state == DownloadState.PAUSED
                }
                .map { "${it.id}.part" }
                .toSet()
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".part") && file.name !in keep && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        }
    }

    val activeCount: Int
        get() = _items.value.count { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }

    fun currentRunningTitle(): String? =
        _items.value.firstOrNull { it.state == DownloadState.RUNNING }?.title

    fun overallProgress(): Int {
        val active = _items.value.filter { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }
        if (active.isEmpty()) return 0
        val sum = active.sumOf { it.progress.toDouble() }
        return ((sum / active.size) * 100).toInt().coerceIn(0, 100)
    }

    // ------------------------------------------------------------------
    // 入队
    // ------------------------------------------------------------------

    fun enqueue(info: VideoInfo, format: MediaFormat): DownloadItem {
        val item = DownloadItem.from(info, format)
        enqueueItem(item)
        return item
    }

    fun enqueueItem(item: DownloadItem) {
        _items.update { current -> sortItems(listOf(item) + current.filterNot { it.id == item.id }) }
        persist()
        if (settings.current.wifiOnly && !isOnWifi()) {
            update(item.id) {
                it.copy(
                    state = DownloadState.FAILED,
                    error = "已开启「仅 Wi-Fi 下载」，请连接 Wi-Fi 后点击重试",
                    errorIsPermanent = true
                )
            }
            persist()
            emit("已开启仅 Wi-Fi 下载，请连接 Wi-Fi 后重试")
            return
        }
        emit("已加入下载队列：${item.title.take(24)}")
        DownloadService.ensureStarted(context)
        dispatch()
    }

    // ------------------------------------------------------------------
    // 调度
    // ------------------------------------------------------------------

    fun dispatch() {
        scope.launch {
            dispatchMutex.withLock {
                if (settings.current.wifiOnly && !isOnWifi()) {
                    val blocked = _items.value.filter { it.state == DownloadState.QUEUED }
                    if (blocked.isNotEmpty()) {
                        blocked.forEach { item ->
                            update(item.id) {
                                it.copy(
                                    state = DownloadState.FAILED,
                                    error = "已开启「仅 Wi-Fi 下载」，请连接 Wi-Fi 后点击重试",
                                    errorIsPermanent = true
                                )
                            }
                        }
                        persist()
                        val now = System.currentTimeMillis()
                        if (now - lastWifiWarningAt > 10_000) {
                            lastWifiWarningAt = now
                            emit("已开启仅 Wi-Fi 下载，请连接 Wi-Fi 后重试")
                        }
                    }
                    return@withLock
                }

                val max = settings.current.maxConcurrent.coerceIn(1, 4)
                var slots = max - jobs.size
                if (slots <= 0) return@withLock

                val queued = _items.value.filter { it.state == DownloadState.QUEUED }
                for (item in queued) {
                    if (slots <= 0) break
                    if (jobs.containsKey(item.id)) continue
                    start(item)
                    slots--
                }

                if (jobs.isEmpty() && _items.value.none { it.state == DownloadState.QUEUED }) {
                    onIdle()
                }
            }
        }
    }

    private fun start(item: DownloadItem) {
        update(item.id) {
            it.copy(
                state = DownloadState.RUNNING,
                error = null,
                downloadedBytes = 0,
                speedBps = 0,
                progressFraction = 0f,
                errorIsPermanent = false
            )
        }

        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val latest = _items.value.firstOrNull { it.id == item.id } ?: item
            try {
                val saved = DownloadRunner(
                    context = context,
                    client = client,
                    item = latest,
                    subDir = settings.current.saveSubDir.ifBlank { "VideoDownload" },
                    onProgress = { progress ->
                        update(item.id) { current ->
                            current.copy(
                                downloadedBytes = progress.downloaded,
                                totalBytes = if (progress.total > 0) progress.total else current.totalBytes,
                                progressFraction = progress.fraction,
                                speedBps = progress.speedBps
                            )
                        }
                        maybePersist()
                    }
                ).run()

                update(item.id) { current ->
                    current.copy(
                        state = DownloadState.COMPLETED,
                        finishedAt = System.currentTimeMillis(),
                        savedUri = saved.uri,
                        savedPath = saved.path,
                        ext = saved.ext,
                        downloadedBytes = if (current.downloadedBytes > 0) current.downloadedBytes else current.totalBytes,
                        progressFraction = 1f,
                        speedBps = 0,
                        error = null
                    )
                }
                persist()
                val finished = _items.value.firstOrNull { it.id == item.id }
                if (finished != null) NotificationHelper.notifyCompleted(context, finished)
            } catch (cancelled: CancellationException) {
                val reason = cancelReasons.remove(item.id) ?: DownloadState.PAUSED
                update(item.id) { it.copy(state = reason, speedBps = 0) }
                if (reason == DownloadState.CANCELED) deletePartFile(item.id)
                persist()
                throw cancelled
            } catch (t: Throwable) {
                update(item.id) {
                    it.copy(
                        state = DownloadState.FAILED,
                        error = t.message ?: "下载失败",
                        speedBps = 0,
                        errorIsPermanent = true,
                        finishedAt = System.currentTimeMillis()
                    )
                }
                persist()
                val failed = _items.value.firstOrNull { it.id == item.id }
                if (failed != null) NotificationHelper.notifyFailed(context, failed, t.message)
            } finally {
                jobs.remove(item.id)
                dispatch()
            }
        }
        // 先登记再启动，避免任务瞬间完成时 jobs 中残留已完成任务
        jobs[item.id] = job
        job.start()
    }

    private fun onIdle() {
        persist()
        NotificationHelper.cancelProgress(context)
        DownloadService.stop(context)
    }

    // ------------------------------------------------------------------
    // 用户操作
    // ------------------------------------------------------------------

    fun pause(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.state != DownloadState.RUNNING && item.state != DownloadState.QUEUED) return
        cancelReasons[id] = DownloadState.PAUSED
        jobs[id]?.cancel()
        if (jobs[id] == null) {
            update(id) { it.copy(state = DownloadState.PAUSED, speedBps = 0) }
            persist()
        }
    }

    fun resume(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.state == DownloadState.RUNNING || item.state == DownloadState.QUEUED) return
        cancelReasons.remove(id)
        update(id) { it.copy(state = DownloadState.QUEUED, error = null, speedBps = 0) }
        persist()
        DownloadService.ensureStarted(context)
        dispatch()
    }

    fun cancel(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        cancelReasons[id] = DownloadState.CANCELED
        jobs[id]?.cancel()
        if (jobs[id] == null) {
            update(id) { it.copy(state = DownloadState.CANCELED, speedBps = 0) }
            deletePartFile(id)
            persist()
        }
    }

    fun retry(id: String) {
        update(id) {
            it.copy(
                state = DownloadState.QUEUED,
                error = null,
                downloadedBytes = 0,
                progressFraction = 0f,
                speedBps = 0,
                finishedAt = null
            )
        }
        cancelReasons.remove(id)
        deletePartFile(id)
        persist()
        DownloadService.ensureStarted(context)
        dispatch()
    }

    /** 从列表移除记录（可选同时删除已下载文件）。 */
    fun remove(id: String, deleteFile: Boolean = false) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        cancelReasons[id] = DownloadState.CANCELED
        jobs[id]?.cancel()
        deletePartFile(id)
        if (deleteFile && item.state == DownloadState.COMPLETED) {
            MediaSaver.delete(context, item.savedUri, item.savedPath)
        }
        _items.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun deleteDownloadedFile(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        val removed = MediaSaver.delete(context, item.savedUri, item.savedPath)
        update(id) {
            it.copy(
                savedUri = null,
                savedPath = null,
                error = if (removed) null else "删除失败，文件可能已被移动",
                errorIsPermanent = !removed
            )
        }
        persist()
    }

    fun clearFinished() {
        _items.update { list -> list.filterNot { it.state.isFinished } }
        persist()
    }

    fun pauseAll() {
        _items.value.filter { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }
            .forEach { pause(it.id) }
    }

    fun resumeAll() {
        _items.value.filter { it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED }
            .forEach { resume(it.id) }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        _items.update { list -> sortItems(list.map { if (it.id == id) transform(it) else it }) }
    }

    private fun maybePersist() {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt > 5_000) {
            lastPersistAt = now
            persist()
        }
    }

    private fun persist() {
        store.save(_items.value)
    }

    private fun emit(message: String) {
        _events.tryEmit(message)
    }

    private fun deletePartFile(id: String) {
        runCatching {
            java.io.File(context.cacheDir, "downloads/$id.part").delete()
        }
    }

    private fun isOnWifi(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            true
        }
    }

    companion object {
        private fun sortItems(list: List<DownloadItem>): List<DownloadItem> = list.sortedWith(
            compareBy(
                { when (it.state) {
                    DownloadState.RUNNING -> 0
                    DownloadState.QUEUED -> 1
                    DownloadState.PAUSED -> 2
                    DownloadState.FAILED -> 3
                    DownloadState.CANCELED -> 5
                    DownloadState.COMPLETED -> 4
                } },
                { -it.createdAt }
            )
        )
    }
}
