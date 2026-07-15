package dev.tsdroid.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object CustomBackgroundManager {
    private const val DIR_NAME = "custom_backgrounds"
    private const val ACTIVE_BG_FILE = "active_background.jpg"

    private fun getDir(context: Context): File =
        File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    fun getActiveBackground(context: Context): File? {
        val file = File(getDir(context), ACTIVE_BG_FILE)
        return if (file.exists() && file.length() > 0) file else null
    }

    fun saveBackground(context: Context, uri: Uri): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return false
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) return false

            val scaled = scaleBitmap(bitmap, 1920, 1080)
            val file = File(getDir(context), ACTIVE_BG_FILE)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            scaled.recycle()
            bitmap.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun cropAndSave(context: Context, bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        return try {
            val width = (right - left).coerceAtLeast(64)
            val height = (bottom - top).coerceAtLeast(64)
            val cropped = Bitmap.createBitmap(bitmap, left.coerceAtLeast(0), top.coerceAtLeast(0), width, height)
            val scaled = scaleBitmap(cropped, 1920, 1080)

            val file = File(getDir(context), ACTIVE_BG_FILE)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            scaled.recycle()
            cropped.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteBackground(context: Context) {
        val file = File(getDir(context), ACTIVE_BG_FILE)
        if (file.exists()) file.delete()
    }

    fun hasCustomBackground(context: Context): Boolean = getActiveBackground(context) != null

    fun loadBitmap(context: Context): Bitmap? {
        val file = getActiveBackground(context) ?: return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun scaleBitmap(bitmap: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxW && h <= maxH) return bitmap.copy(Bitmap.Config.ARGB_8888, false)

        val ratio = minOf(maxW.toFloat() / w, maxH.toFloat() / h)
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
