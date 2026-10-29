package com.vdl.videodownload.download

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vdl.videodownload.VideoDownloadApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 下载前台服务：保证应用退到后台后仍能继续下载，并在通知栏展示进度。
 * 真正的下载调度在 [DownloadManager]，本服务只负责存活与通知。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: DownloadManager
    private var startedForeground = false

    override fun onCreate() {
        super.onCreate()
        manager = (application as VideoDownloadApp).container.downloads
        NotificationHelper.createChannels(this)
        startForegroundCompat()
        scope.launch { observeProgress() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startedForeground) startForegroundCompat()
        manager.dispatch()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = NotificationHelper.buildProgress(
            context = this,
            activeCount = manager.activeCount,
            currentTitle = manager.currentRunningTitle(),
            progress = manager.overallProgress(),
            downloadedLabel = "",
            indeterminate = manager.activeCount == 0
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.PROGRESS_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationHelper.PROGRESS_NOTIFICATION_ID, notification)
            }
            startedForeground = true
        } catch (t: Throwable) {
            // 前台服务被系统拒绝（例如 Android 12+ 后台启动限制）时，退化为普通服务
            startedForeground = false
        }
    }

    private suspend fun observeProgress() {
        manager.items.collectLatest { items ->
            val active = items.filter { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }
            if (active.isEmpty()) {
                NotificationHelper.cancelProgress(this@DownloadService)
                stopSelf()
                return@collectLatest
            }
            val running = active.firstOrNull { it.state == DownloadState.RUNNING }
            val label = running?.let { item ->
                "${formatBytesShort(item.downloadedBytes)} / ${formatBytesShort(item.totalBytes)}"
            }.orEmpty()
            val notification = NotificationHelper.buildProgress(
                context = this@DownloadService,
                activeCount = active.size,
                currentTitle = running?.title?.take(20),
                progress = manager.overallProgress(),
                downloadedLabel = label,
                indeterminate = running == null || (running.totalBytes <= 0 && running.progressFraction <= 0f)
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NotificationHelper.PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun formatBytesShort(value: Long): String = when {
        value <= 0 -> "--"
        value < 1024 * 1024 -> "${value / 1024}KB"
        else -> String.format("%.1fMB", value / 1024.0 / 1024.0)
    }

    companion object {
        fun ensureStarted(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                runCatching { context.startService(intent) }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
        }
    }
}
