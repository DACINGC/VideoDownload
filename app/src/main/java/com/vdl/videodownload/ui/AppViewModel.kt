package com.vdl.videodownload.ui

import android.app.Application
import com.vdl.videodownload.VideoDownloadApp
import com.vdl.videodownload.core.LinkParser
import com.vdl.videodownload.core.Platform
import com.vdl.videodownload.core.VideoInfo
import com.vdl.videodownload.data.AppSettings
import com.vdl.videodownload.download.DownloadItem
import com.vdl.videodownload.extractor.RemoteResolverExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

data class HomeUiState(
    val input: String = "",
    val parsing: Boolean = false,
    val result: VideoInfo? = null,
    val selectedFormatId: String? = null,
    val error: String? = null,
    val detectedPlatform: Platform? = null,
    val parsedUrl: String? = null
) {
    val selectedFormat get() = result?.formats?.firstOrNull { it.id == selectedFormatId }
        ?: result?.bestFormat
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as VideoDownloadApp).container

    val settings: StateFlow<AppSettings> = container.settings.settings
    val downloadItems: StateFlow<List<DownloadItem>> = container.downloads.items
    val downloadEvents: SharedFlow<String> = container.downloads.events

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    private val _tab = MutableStateFlow(0)
    val tab: StateFlow<Int> = _tab.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _remoteTest = MutableStateFlow<String?>(null)
    val remoteTest: StateFlow<String?> = _remoteTest.asStateFlow()

    private var parseJob: Job? = null
    private var lastClipboardUrl: String? = null

    val downloads get() = container.downloads

    fun selectTab(index: Int) {
        _tab.value = index.coerceIn(0, 2)
    }

    fun onInputChanged(text: String) {
        _ui.update {
            it.copy(
                input = text,
                error = null,
                detectedPlatform = LinkParser.firstUrl(text)?.let(LinkParser::detectPlatform)
            )
        }
    }

    fun paste(text: String?) {
        if (text.isNullOrBlank()) {
            emit("剪贴板为空")
            return
        }
        _ui.update { it.copy(input = text.trim(), error = null) }
        emit("已粘贴剪贴板内容")
    }

    /** 应用回到前台时检查剪贴板，自动解析新出现的链接。 */
    fun onClipboardContent(text: String?, autoParse: Boolean) {
        if (text.isNullOrBlank()) return
        val url = LinkParser.pickBestUrl(text) ?: return
        if (url == lastClipboardUrl) return
        lastClipboardUrl = url

        if (!autoParse) {
            _ui.update {
                if (it.input.isBlank()) it.copy(input = text.trim()) else it
            }
            return
        }
        emit("检测到剪贴板链接，自动解析")
        _ui.update { it.copy(input = text.trim()) }
        parse(url)
    }

    /** 处理从其它应用分享进来的文本或链接。 */
    fun handleSharedText(text: String?) {
        if (text.isNullOrBlank()) return
        val url = LinkParser.pickBestUrl(text) ?: return
        lastClipboardUrl = url
        _ui.update { it.copy(input = text.trim()) }
        selectTab(0)
        parse(url)
    }

    fun parse(text: String? = null) {
        val raw = (text ?: _ui.value.input).trim()
        if (raw.isBlank()) {
            _ui.update { it.copy(error = "请先粘贴视频链接") }
            return
        }
        val url = LinkParser.pickBestUrl(raw)
            ?: raw.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) {
            _ui.update { it.copy(error = "没有在文本中识别到链接，请检查后重试") }
            return
        }

        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    parsing = true,
                    error = null,
                    result = null,
                    selectedFormatId = null,
                    detectedPlatform = LinkParser.detectPlatform(url),
                    parsedUrl = url
                )
            }
            try {
                val info = container.engine.extract(url, container.settings.toExtractSettings())
                _ui.update {
                    it.copy(
                        parsing = false,
                        result = info,
                        selectedFormatId = info.bestFormat?.id,
                        detectedPlatform = info.platform
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        parsing = false,
                        error = t.message ?: "解析失败，请稍后重试"
                    )
                }
            }
        }
    }

    fun selectFormat(formatId: String) {
        _ui.update { it.copy(selectedFormatId = formatId) }
    }

    fun download() {
        val state = _ui.value
        val info = state.result ?: return
        val format = state.selectedFormat ?: run {
            emit("没有可用的下载地址")
            return
        }
        container.downloads.enqueue(info, format)
        selectTab(1)
    }

    fun copyDirectLink(): String? {
        val state = _ui.value
        return state.selectedFormat?.url
    }

    fun clear() {
        parseJob?.cancel()
        _ui.value = HomeUiState()
    }

    fun clearInput() {
        _ui.update { it.copy(input = "") }
    }

    fun testRemoteEndpoint() {
        viewModelScope.launch {
            _remoteTest.value = "测试中…"
            _remoteTest.value = RemoteResolverExtractor.testEndpoint(
                container.web,
                container.settings.toExtractSettings()
            )
        }
    }

    fun clearRemoteTest() {
        _remoteTest.value = null
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        container.settings.update(transform)
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }
}
