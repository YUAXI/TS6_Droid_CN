package dev.tsdroid.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val apkSize: Long,
)

data class CheckResult(
    val update: UpdateInfo?,
    val error: String?,
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val REPO = "YUAXI/TS6_Droid_CN"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    suspend fun checkForUpdate(currentVersionName: String): CheckResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "TS6-Droid/2.0")

            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext CheckResult(null, "服务器返回 ${conn.responseCode}")
            }

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val obj = JSONObject(json)
            val tagName = obj.optString("tag_name", "")
            val versionName = tagName.removePrefix("v").removeSuffix("-Han")
            val body = obj.optString("body", "")

            // Compare versions properly — strip -Han suffix first
            if (!isNewerVersion(currentVersionName, versionName)) {
                return@withContext CheckResult(null, null) // no update, no error
            }

            val assets = obj.getJSONArray("assets")
            val downloadUrl: String
            val apkSize: Long
            if (assets.length() > 0) {
                val apkAsset = assets.getJSONObject(0)
                downloadUrl = apkAsset.optString("browser_download_url", "")
                apkSize = apkAsset.optLong("size", 0L)
            } else {
                // No APK uploaded yet — provide fallback to releases page
                downloadUrl = "https://github.com/$REPO/releases/tag/$tagName"
                apkSize = 0L
            }

            CheckResult(
                UpdateInfo(
                    tagName = tagName,
                    versionName = versionName,
                    changelog = body,
                    downloadUrl = downloadUrl,
                    apkSize = apkSize,
                ),
                error = null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            CheckResult(null, e.message ?: "网络请求失败")
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentClean = current.removeSuffix("-Han")
        val latestClean = latest.removeSuffix("-Han")

        val currentParts = currentClean.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latestClean.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun openDownload(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL", e)
        }
    }
}