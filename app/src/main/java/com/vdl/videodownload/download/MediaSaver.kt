package com.vdl.videodownload.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把下载好的临时文件写入相册目录：
 *  - Android 10+ 使用 MediaStore（无需存储权限）；
 *  - Android 9 及以下写入 Movies/VideoDownload 并触发媒体扫描。
 */
object MediaSaver {

    data class Saved(
        val uri: String?,
        val path: String?,
        val displayName: String,
        val ext: String
    )

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "video/mp4"
    }

    fun isAudio(ext: String): Boolean = ext.lowercase() in setOf("m4a", "mp3", "aac", "opus")

    fun save(
        context: Context,
        source: File,
        baseName: String,
        ext: String,
        subDir: String
    ): Saved {
        val mime = mimeFor(ext)
        val displayName = "$baseName.$ext"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, source, displayName, mime, ext, subDir)
        } else {
            saveLegacy(context, source, displayName, mime, ext, subDir)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        source: File,
        displayName: String,
        mime: String,
        ext: String,
        subDir: String
    ): Saved {
        val resolver = context.contentResolver
        val audio = isAudio(ext)
        val collection: Uri = if (audio) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val relativePath = if (audio) {
            "${Environment.DIRECTORY_MUSIC}/$subDir"
        } else {
            "${Environment.DIRECTORY_MOVIES}/$subDir"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法在相册中创建文件（存储空间不足？）")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
                output.flush()
            } ?: throw IllegalStateException("无法写入媒体文件")

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return Saved(uri.toString(), null, displayName, ext)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        source: File,
        displayName: String,
        mime: String,
        ext: String,
        subDir: String
    ): Saved {
        val baseDir = if (isAudio(ext)) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }
        val targetDir = File(baseDir, subDir)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("无法创建目录 ${targetDir.absolutePath}")
        }

        var target = File(targetDir, displayName)
        var index = 1
        while (target.exists()) {
            val name = displayName.substringBeforeLast('.')
            target = File(targetDir, "$name($index).$ext")
            index++
        }

        source.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return Saved(null, target.absolutePath, target.name, ext)
    }

    /** 删除已保存到相册的文件（用户在下载列表里点“删除”时调用）。 */
    fun delete(context: Context, uriString: String?, path: String?): Boolean {
        var ok = false
        if (!uriString.isNullOrBlank()) {
            ok = runCatching {
                context.contentResolver.delete(Uri.parse(uriString), null, null) > 0
            }.getOrDefault(false)
        }
        if (!ok && !path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists()) ok = file.delete()
        }
        return ok
    }

    /** 打开已下载的视频。 */
    fun openIntent(context: Context, uriString: String?, path: String?, mime: String): android.content.Intent? {
        val uri = resolveUri(context, uriString, path) ?: return null
        return android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 分享已下载的视频。 */
    fun shareIntent(
        context: Context,
        uriString: String?,
        path: String?,
        mime: String,
        title: String
    ): android.content.Intent? {
        val uri = resolveUri(context, uriString, path) ?: return null
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun resolveUri(context: Context, uriString: String?, path: String?): Uri? = when {
        !uriString.isNullOrBlank() -> Uri.parse(uriString)
        !path.isNullOrBlank() -> {
            val file = File(path)
            if (!file.exists()) null
            else runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            }.getOrNull()
        }
        else -> null
    }
}
