package com.vdl.videodownload.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vdl.videodownload.core.Platform
import com.vdl.videodownload.core.formatBytes
import com.vdl.videodownload.download.DownloadItem
import com.vdl.videodownload.download.DownloadState
import com.vdl.videodownload.download.MediaSaver
import com.vdl.videodownload.ui.AppViewModel
import com.vdl.videodownload.ui.components.EmptyState
import com.vdl.videodownload.ui.components.PlatformBadge

@Composable
fun DownloadsScreen(
    vm: AppViewModel,
    items: List<DownloadItem>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeCount = items.count { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "下载列表",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${items.size} 个任务 · $activeCount 个进行中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (items.any { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }) {
                TextButton(onClick = { vm.downloads.pauseAll() }) { Text("全部暂停") }
            } else if (items.any { it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED }) {
                TextButton(onClick = { vm.downloads.resumeAll() }) { Text("全部继续") }
            }
        }

        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.CloudDownload,
                title = "还没有下载任务",
                subtitle = "回到首页粘贴视频链接，解析后即可下载到相册",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    DownloadCard(
                        item = item,
                        onPause = { vm.downloads.pause(item.id) },
                        onResume = { vm.downloads.resume(item.id) },
                        onCancel = { vm.downloads.cancel(item.id) },
                        onRetry = { vm.downloads.retry(item.id) },
                        onRemove = { vm.downloads.remove(item.id, deleteFile = false) },
                        onDeleteFile = { vm.downloads.deleteDownloadedFile(item.id) },
                        onOpen = {
                            val intent = MediaSaver.openIntent(
                                context, item.savedUri, item.savedPath, MediaSaver.mimeFor(item.ext)
                            )
                            if (intent == null) {
                                Toast.makeText(context, "文件不存在或已被删除", Toast.LENGTH_SHORT).show()
                            } else {
                                try {
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "没有可以打开该视频的应用", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onShare = {
                            val intent = MediaSaver.shareIntent(
                                context, item.savedUri, item.savedPath, MediaSaver.mimeFor(item.ext), item.title
                            )
                            if (intent == null) {
                                Toast.makeText(context, "文件不存在或已被删除", Toast.LENGTH_SHORT).show()
                            } else {
                                try {
                                    context.startActivity(Intent.createChooser(intent, "分享视频"))
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "没有可以分享的应用", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onCopySource = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("video url", item.sourceUrl)
                            )
                            Toast.makeText(context, "已复制原始链接", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFile: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopySource: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.coverUrl != null) {
                        AsyncImage(
                            model = item.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Filled.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlatformBadge(Platform.fromId(item.platformId))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = item.qualityLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = statusText(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(item)
                    )
                }

                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "移除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (item.state == DownloadState.RUNNING ||
                item.state == DownloadState.QUEUED ||
                item.state == DownloadState.PAUSED
            ) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = if (item.state == DownloadState.PAUSED) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${(item.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.state == DownloadState.RUNNING && item.speedBps > 0) {
                        Text(
                            text = "${formatBytes(item.speedBps)}/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (item.error != null && item.state == DownloadState.FAILED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = item.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (item.state) {
                    DownloadState.RUNNING -> {
                        OutlinedButton(onClick = onPause, contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Text("暂停")
                        }
                        TextButton(onClick = onCancel) { Text("取消") }
                    }

                    DownloadState.QUEUED -> {
                        TextButton(onClick = onCancel) { Text("取消") }
                    }

                    DownloadState.PAUSED -> {
                        Button(onClick = onResume, contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Text("继续")
                        }
                        TextButton(onClick = onCancel) { Text("取消") }
                    }

                    DownloadState.FAILED -> {
                        Button(onClick = onRetry, contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Text("重试")
                        }
                        TextButton(onClick = onRemove) { Text("移除") }
                    }

                    DownloadState.CANCELED -> {
                        Button(onClick = onRetry, contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Text("重新下载")
                        }
                        TextButton(onClick = onRemove) { Text("移除") }
                    }

                    DownloadState.COMPLETED -> {
                        Button(
                            onClick = onOpen,
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            colors = ButtonDefaults.buttonColors()
                        ) { Text("打开") }
                        OutlinedButton(onClick = onShare, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text("分享")
                        }
                        TextButton(onClick = onDeleteFile) { Text("删除文件") }
                        TextButton(onClick = onCopySource) { Text("复制链接") }
                    }
                }
            }
        }
    }
}

private fun statusText(item: DownloadItem): String = when (item.state) {
    DownloadState.QUEUED -> "排队等待中"
    DownloadState.RUNNING -> "下载中"
    DownloadState.PAUSED -> "已暂停（可断点续传）"
    DownloadState.COMPLETED -> "已保存到相册"
    DownloadState.FAILED -> "下载失败"
    DownloadState.CANCELED -> "已取消"
}

@Composable
private fun statusColor(item: DownloadItem): Color = when (item.state) {
    DownloadState.COMPLETED -> Color(0xFF2E7D32)
    DownloadState.FAILED -> MaterialTheme.colorScheme.error
    DownloadState.PAUSED, DownloadState.CANCELED -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}
