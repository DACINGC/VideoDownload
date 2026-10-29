package com.vdl.videodownload.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vdl.videodownload.core.MediaFormat
import com.vdl.videodownload.core.MediaKind
import com.vdl.videodownload.core.Platform
import com.vdl.videodownload.core.VideoInfo
import com.vdl.videodownload.core.formatBytes
import com.vdl.videodownload.core.formatDuration
import com.vdl.videodownload.ui.AppViewModel
import com.vdl.videodownload.ui.HomeUiState
import com.vdl.videodownload.ui.components.InfoPill
import com.vdl.videodownload.ui.components.PlatformBadge
import com.vdl.videodownload.ui.components.SectionCard

@Composable
fun HomeScreen(
    vm: AppViewModel,
    ui: HomeUiState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp)
    ) {
        Text(
            text = "视频下载助手",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "复制分享链接，自动提取并保存到相册",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = ui.input,
                    onValueChange = vm::onInputChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("粘贴抖音 / B站 / 快手 / 小红书等分享链接") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    enabled = !ui.parsing
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val platform = ui.detectedPlatform
                    if (platform != null) {
                        PlatformBadge(platform)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = ui.parsedUrl?.let { hostOf(it) } ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Text(
                            text = "支持短链自动跳转与原画提取",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (ui.input.isNotBlank()) {
                        TextButton(onClick = vm::clearInput) { Text("清空") }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.parse() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        enabled = ui.input.isNotBlank() && !ui.parsing
                    ) {
                        if (ui.parsing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在解析")
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("解析视频")
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.paste(readClipboardText(context)) },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("粘贴")
                    }
                }
            }
        }

        if (ui.error != null) {
            Spacer(Modifier.height(12.dp))
            ErrorCard(message = ui.error, onRetry = { vm.parse() })
        }

        val info = ui.result
        if (info != null) {
            Spacer(Modifier.height(16.dp))
            ResultCard(
                info = info,
                selectedFormatId = ui.selectedFormatId,
                onSelectFormat = vm::selectFormat,
                onDownload = vm::download,
                onCopyLink = {
                    val url = vm.copyDirectLink()
                    if (url != null) {
                        clipboard.setText(AnnotatedString(url))
                    }
                }
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionCard(title = "使用说明") {
            TipLine("1", "在抖音 / B站 / 快手等 App 里点击「分享 → 复制链接」")
            TipLine("2", "回到本应用，链接会自动填入并解析")
            TipLine("3", "选择清晰度后点击下载，文件保存到相册 Movies/VideoDownload")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "支持：抖音无水印 · 哔哩哔哩 MP4 · 快手 · 小红书 · 微博 · 直链 / HLS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TipLine(index: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val summary = message.substringBefore("\n\n解析过程:").trim()
    val trace = message.substringAfter("\n\n解析过程:", "").trim()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "解析失败",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = summary.ifBlank { "解析失败，请稍后重试" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (trace.isNotBlank()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起解析详情" else "查看解析详情", style = MaterialTheme.typography.labelSmall)
                }
                if (expanded) {
                    Text(
                        text = trace,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun ResultCard(
    info: VideoInfo,
    selectedFormatId: String?,
    onSelectFormat: (String) -> Unit,
    onDownload: () -> Unit,
    onCopyLink: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = info.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0x66000000))
                            )
                        )
                )
                PlatformBadge(
                    platform = info.platform,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                )
                if (info.durationMs != null && info.durationMs > 0) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                        InfoPill(text = formatDuration(info.durationMs))
                    }
                }
            }

            Column(Modifier.padding(14.dp)) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!info.author.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Link,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = info.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))

                Text(
                    text = "选择清晰度（${info.formats.size}）",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))

                info.formats.forEach { format ->
                    FormatRow(
                        format = format,
                        selected = format.id == (selectedFormatId ?: info.bestFormat?.id),
                        onClick = { onSelectFormat(format.id) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("下载到相册")
                }
                TextButton(onClick = onCopyLink, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制视频直链")
                }
            }
        }
    }
}

@Composable
private fun FormatRow(format: MediaFormat, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(
                text = format.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val extra = buildList {
                if (format.sizeBytes != null && format.sizeBytes > 0) add(formatBytes(format.sizeBytes))
                if (format.kind == MediaKind.HLS) add("HLS 分片合并")
                if (format.audioUrl != null) add("含独立音轨")
            }
            if (extra.isNotEmpty()) {
                Text(
                    text = extra.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (format.kind == MediaKind.HLS) {
            InfoPill(text = "HLS")
        }
    }
}

private fun readClipboardText(context: Context): String? = try {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = manager.primaryClip
    if (clip == null || clip.itemCount == 0) null
    else clip.getItemAt(0).coerceToText(context)?.toString()
} catch (_: Exception) {
    null
}

private fun hostOf(url: String): String = url
    .substringAfter("://", url)
    .substringBefore('/')
    .removePrefix("www.")
