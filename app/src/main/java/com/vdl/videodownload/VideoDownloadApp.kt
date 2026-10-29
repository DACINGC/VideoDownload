package com.vdl.videodownload

import android.app.Application
import android.content.Context
import com.vdl.videodownload.data.SettingsStore
import com.vdl.videodownload.download.DownloadManager
import com.vdl.videodownload.extractor.ExtractorEngine
import com.vdl.videodownload.net.WebClient

class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val web = WebClient()
    val engine = ExtractorEngine(web)
    val downloads = DownloadManager(context, settings)
}

class VideoDownloadApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: VideoDownloadApp
            private set
    }
}
