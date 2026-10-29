package com.vdl.videodownload.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.vdl.videodownload.ui.theme.VideoDownloadTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果不影响主流程 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VideoDownloadTheme {
                AppRoot(viewModel)
            }
        }

        requestRuntimePermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val autoParse = viewModel.settings.value.autoClipboard
        viewModel.onClipboardContent(readClipboard(), autoParse)
    }

    private fun readClipboard(): String? {
        return try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(this)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                viewModel.handleSharedText(text)
            }
            Intent.ACTION_VIEW -> {
                val data = intent.dataString
                viewModel.handleSharedText(data)
            }
            else -> {
                val openTab = intent.getIntExtra(EXTRA_OPEN_TAB, -1)
                if (openTab >= 0) viewModel.selectTab(openTab)
            }
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                wanted += Manifest.permission.POST_NOTIFICATIONS
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        if (wanted.isNotEmpty()) {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "extra_open_tab"
        const val TAB_HOME = 0
        const val TAB_DOWNLOADS = 1
        const val TAB_SETTINGS = 2
    }
}
