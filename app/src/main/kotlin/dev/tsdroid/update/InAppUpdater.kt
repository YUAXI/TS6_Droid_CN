package dev.tsdroid.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object InAppUpdater {
    private const val TAG = "InAppUpdater"

    enum class DownloadState {
        IDLE, DOWNLOADING, DONE, FAILED
    }

    data class DownloadProgress(
        val state: DownloadState = DownloadState.IDLE,
        val progress: Float = 0f,
        val error: String? = null,
    )

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        fileName: String = "update.apk",
        onProgress: (DownloadProgress) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress(DownloadProgress(DownloadState.DOWNLOADING, 0f))

            val cacheDir = File(context.cacheDir, "updates").also { it.mkdirs() }
            val apkFile = File(cacheDir, fileName)

            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000

            val totalSize = conn.contentLength.toLong().coerceAtLeast(1)
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val progress = (downloaded.toFloat() / totalSize).coerceIn(0f, 1f)
                        onProgress(DownloadProgress(DownloadState.DOWNLOADING, progress))
                    }
                }
            }
            conn.disconnect()

            onProgress(DownloadProgress(DownloadState.DOWNLOADING, 1f))

            installApk(context, apkFile)

            onProgress(DownloadProgress(DownloadState.DONE))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed", e)
            onProgress(DownloadProgress(DownloadState.FAILED, error = e.message ?: "下载失败"))
            false
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun clearCache(context: Context) {
        val cacheDir = File(context.cacheDir, "updates")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }
}
