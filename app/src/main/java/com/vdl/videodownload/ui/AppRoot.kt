package com.vdl.videodownload.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdl.videodownload.download.DownloadState
import com.vdl.videodownload.ui.screens.DownloadsScreen
import com.vdl.videodownload.ui.screens.HomeScreen
import com.vdl.videodownload.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun AppRoot(viewModel: AppViewModel) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val items by viewModel.downloadItems.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        launch {
            viewModel.messages.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }
        launch {
            viewModel.downloadEvents.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    val activeCount = items.count {
        it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { viewModel.selectTab(0) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { viewModel.selectTab(1) },
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    label = { Text(if (activeCount > 0) "下载 ($activeCount)" else "下载") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { viewModel.selectTab(2) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (tab) {
                0 -> HomeScreen(vm = viewModel, ui = ui)
                1 -> DownloadsScreen(vm = viewModel, items = items)
                else -> SettingsScreen(vm = viewModel, settings = settings)
            }
        }
    }
}
