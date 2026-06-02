package dev.tsdroid.bridge

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.net.URLEncoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AvatarCache(private val cacheDir: File) {

    companion object {
        private const val TAG = "AvatarCache"
        private const val MAX_RETRIES = 5
    }

    private val memoryCache = ConcurrentHashMap<String, ImageBitmap>()
    private val loading = ConcurrentHashMap<String, Boolean>()
    private val failedAttempts = ConcurrentHashMap<String, Int>()
    private val avatarsDir = File(cacheDir, "avatars").also { it.mkdirs() }

    fun getAvatar(uid: String): ImageBitmap? = memoryCache[uid]

    fun hasNoAvatar(uid: String): Boolean = (failedAttempts[uid] ?: 0) >= MAX_RETRIES

    /** Clear memory cache for specific UIDs so next loadAvatar forces re-download */
    fun clearMemoryCache(vararg uids: String) {
        for (uid in uids) {
            memoryCache.remove(uid)
            loading.remove(uid)
            failedAttempts.remove(uid)
        }
        Log.d(TAG, "Cleared memory cache for ${uids.size} UID(s)")
    }

    /** Clear ALL memory caches so next loadAvatar calls re-download */
    fun clearAllMemoryCache() {
        memoryCache.clear()
        loading.clear()
        failedAttempts.clear()
        Log.d(TAG, "Cleared ALL memory caches")
    }

    /**
     * Convert a TS3 base64 UID to the avatar file path used by the server.
     *
     * The server stores avatars at `/avatar_{encoded}` where each byte of the
     * decoded UID is encoded as two characters in [a-p] (nibble + 'a').
     */
    fun uidToAvatarPath(base64Uid: String): String {
        val bytes = Base64.decode(base64Uid, Base64.DEFAULT)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val unsigned = b.toInt() and 0xFF
            sb.append(('a' + (unsigned shr 4)))
            sb.append(('a' + (unsigned and 0x0F)))
        }
        return "/avatar_$sb"
    }

    suspend fun loadAvatar(uid: String, tsClient: TsClient) {
        if (uid.isEmpty()) return
        if (memoryCache.containsKey(uid)) return
        if (hasNoAvatar(uid)) return
        if (loading.putIfAbsent(uid, true) != null) return

        try {
            // UID contains / and = which are invalid in filenames — URL-encode for disk cache
            val safeFileName = URLEncoder.encode(uid, "UTF-8")
            val diskFile = File(avatarsDir, safeFileName)
            var bytes: ByteArray? = null
            var fromDisk = false

            // Try disk cache first
            if (diskFile.exists() && diskFile.length() > 0) {
                bytes = diskFile.readBytes()
                fromDisk = true
                Log.d(TAG, "Read avatar from disk for $uid (${bytes.size} bytes)")
            }

            // Try decoding if we have disk data
            if (fromDisk && bytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    memoryCache[uid] = bitmap.asImageBitmap()
                    Log.d(TAG, "Loaded avatar from disk cache for $uid")
                    return
                }
                Log.w(TAG, "Corrupt avatar on disk for $uid, deleting and re-downloading")
                diskFile.delete()
                bytes = null
            }

            // Download from server using UID-based path
            val path = uidToAvatarPath(uid)
            Log.d(TAG, "Downloading avatar for $uid at path $path")
            bytes = tsClient.downloadFile(0L, path)

            if (bytes == null || bytes.isEmpty()) {
                Log.w(TAG, "Download returned empty/null for $uid (attempt ${(failedAttempts[uid] ?: 0) + 1}/$MAX_RETRIES)")
                failedAttempts.merge(uid, 1, Integer::sum)
                return
            }

            Log.d(TAG, "Downloaded avatar for $uid: ${bytes.size} bytes")

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                diskFile.writeBytes(bytes)
                memoryCache[uid] = bitmap.asImageBitmap()
                Log.i(TAG, "Avatar loaded for $uid")
            } else {
                Log.w(TAG, "Failed to decode downloaded avatar for $uid (${bytes.size} bytes)")
                failedAttempts.merge(uid, 1, Integer::sum)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load avatar for $uid", e)
            failedAttempts.merge(uid, 1, Integer::sum)
        } finally {
            loading.remove(uid)
        }
    }
}