package com.example.my_3d_camera.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сохраняет фото и видео прямо в галерею (MediaStore), без промежуточных файлов.
 * Суффикс _SBS в имени помогает VR-плеерам распознать формат Side-by-Side.
 */
class MediaSaver(context: Context) {
    private val resolver = context.contentResolver

    fun newFileName(extension: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "3D_${timestamp}_SBS.$extension"
    }

    /** Создаёт видео в галерее в состоянии «ещё пишется». */
    fun createPendingVideo(): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, newFileName("mp4"))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    }

    fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
    }

    fun delete(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось удалить $uri", e)
        }
    }

    fun openForWrite(uri: Uri) = resolver.openFileDescriptor(uri, "rw")

    /** Сохраняет кадр SBS (RGBA, строки сверху вниз) как JPEG. Вызывать не на UI-потоке. */
    fun savePhoto(pixels: ByteBuffer, width: Int, height: Int): Uri? {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(pixels)
        return try {
            savePhoto(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Сохраняет SBS-фото как JPEG в галерею. Вызывать не на UI-потоке. */
    fun savePhoto(bitmap: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, newFileName("jpg"))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            publish(uri)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось сохранить фото", e)
            delete(uri)
            null
        }
    }

    companion object {
        private const val TAG = "MediaSaver"
        const val ALBUM = "3D Camera"
    }
}
