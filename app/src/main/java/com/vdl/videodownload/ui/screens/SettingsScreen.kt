package com.vdl.videodownload.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdl.videodownload.BuildConfig
import com.vdl.videodownload.data.AppSettings
import com.vdl.videodownload.ui.AppViewModel
import com.vdl.videodownload.ui.components.SectionCard
import com.vdl.videodownload.ui.components.SettingRow
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val remoteTest by vm.remoteTest.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        SectionCard(title = "下载") {
            SettingRow(
                title = "自动读取剪贴板",
                subtitle = "打开应用时自动解析新复制的链接",
                trailing = {
                    Switch(
                        checked = settings.autoClipboard,
                        onCheckedChange = { value -> vm.updateSettings { it.copy(autoClipboard = value) } }
                    )
                }
            )
            Spacer(Modifier.height(12.dp))
            SettingRow(
                title = "仅 Wi-Fi 下载",
                subtitle = "移动网络下不自动开始下载",
                trailing = {
                    Switch(
                        checked = settings.wifiOnly,
                        onCheckedChange = { value -> vm.updateSettings { it.copy(wifiOnly = value) } }
                    )
                }
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "同时下载数量：${settings.maxConcurrent}",
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = settings.maxConcurrent.toFloat(),
                onValueChange = { value ->
                    vm.updateSettings { it.copy(maxConcurrent = value.roundToInt().coerceIn(1, 4)) }
                },
                valueRange = 1f..4f,
                steps = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "保存位置：相册 Movies/${settings.saveSubDir}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(title = "平台账号（可选）") {
            Text(
                text = "填入登录后的 Cookie 可以解锁更高清晰度，例如 B 站 1080P 需要 SESSDATA。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = settings.bilibiliCookie,
                onValueChange = { value -> vm.updateSettings { it.copy(bilibiliCookie = value) } },
                label = { Text("哔哩哔哩 Cookie") },
                placeholder = { Text("SESSDATA=xxxx; bili_jct=xxxx") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = settings.douyinCookie,
                onValueChange = { value -> vm.updateSettings { it.copy(douyinCookie = value) } },
                label = { Text("抖音 Cookie（可选）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(10.dp)
            )
        }

        SectionCard(title = "高级") {
            OutlinedTextField(
                value = settings.customUserAgent,
                onValueChange = { value -> vm.updateSettings { it.copy(customUserAgent = value) } },
                label = { Text("自定义 User-Agent") },
                placeholder = { Text("留空使用平台默认 UA") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3,
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(Modifier.height(16.dp))
            SettingRow(
                title = "远程解析兜底",
                subtitle = "内置解析失败时，调用自建接口（如 cobalt）",
                trailing = {
                    Switch(
                        checked = settings.remoteEnabled,
                        onCheckedChange = { value -> vm.updateSettings { it.copy(remoteEnabled = value) } }
                    )
                }
            )

            if (settings.remoteEnabled) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.remoteType == "cobalt",
                        onClick = { vm.updateSettings { it.copy(remoteType = "cobalt") } },
                        label = { Text("cobalt") }
                    )
                    FilterChip(
                        selected = settings.remoteType == "generic",
                        onClick = { vm.updateSettings { it.copy(remoteType = "generic") } },
                        label = { Text("通用 JSON (GET)") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.remoteBase,
                    onValueChange = { value -> vm.updateSettings { it.copy(remoteBase = value) } },
                    label = { Text("接口地址") },
                    placeholder = { Text("https://cobalt.example.com/") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.remoteToken,
                    onValueChange = { value -> vm.updateSettings { it.copy(remoteToken = value) } },
                    label = { Text("访问令牌（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { vm.testRemoteEndpoint() },
                        enabled = settings.remoteBase.isNotBlank()
                    ) { Text("测试连接") }
                    if (remoteTest != null) {
                        TextButton(onClick = { vm.clearRemoteTest() }) { Text("清除") }
                    }
                }
                if (remoteTest != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = remoteTest.orEmpty(),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

        SectionCard(title = "关于") {
            AboutRow("版本", BuildConfig.VERSION_NAME)
            AboutRow("保存目录", "相册 / Movies / ${settings.saveSubDir}")
            AboutRow("支持的平台", "抖音 · 哔哩哔哩 · 快手 · 小红书 · 微博 · 直链 / HLS")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "免责声明：本工具仅用于个人学习与备份，请勿用于侵犯版权的用途；下载内容请遵守各平台用户协议。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
