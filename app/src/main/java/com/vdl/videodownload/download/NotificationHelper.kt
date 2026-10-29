package com.vdl.videodownload.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vdl.videodownload.R
import com.vdl.videodownload.core.formatBytes
import com.vdl.videodownload.ui.MainActivity

object NotificationHelper {

    const val CHANNEL_PROGRESS = "download_progress"
    const val CHANNEL_DONE = "download_done"
    const val PROGRESS_NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            "下载进度",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示正在下载的视频进度"
            setShowBadge(false)
        }

        val done = NotificationChannel(
            CHANNEL_DONE,
            "下载完成",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "视频下载完成或失败时提醒"
        }

        manager.createNotificationChannel(progress)
        manager.createNotificationChannel(done)
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_DOWNLOADS)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildProgress(
        context: Context,
        activeCount: Int,
        currentTitle: String?,
        progress: Int,
        downloadedLabel: String,
        indeterminate: Boolean
    ): Notification {
        val text = when {
            activeCount <= 0 -> "准备中…"
            currentTitle.isNullOrBlank() -> "正在下载 $activeCount 个视频"
            else -> "$currentTitle · $downloadedLabel"
        }
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (activeCount > 1) "正在下载 $activeCount 个视频" else "正在下载视频")
            .setContentText(text)
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()
    }

    fun notifyCompleted(context: Context, item: DownloadItem) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText(item.title.take(40))
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(item.id.hashCode(), notification)
    }

    fun notifyFailed(context: Context, item: DownloadItem, message: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("下载失败")
            .setContentText("${item.title.take(30)}：${message ?: "未知错误"}")
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(item.id.hashCode() + 7, notification)
    }

    fun cancelProgress(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(PROGRESS_NOTIFICATION_ID)
    }
}
