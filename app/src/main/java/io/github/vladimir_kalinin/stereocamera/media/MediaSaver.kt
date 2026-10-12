package io.github.vladimir_kalinin.stereocamera.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
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
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
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
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
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

    /** Последнее фото или видео приложения в галерее (для кнопки-превью). Вызывать не на UI-потоке. */
    fun latestMedia(): Uri? {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        val args = arrayOf("$RELATIVE_PATH/")
        val columns = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
        return try {
            resolver.query(uri, columns, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                if (c.moveToFirst()) typedUri(c.getLong(0), c.getInt(1)) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось найти последний снимок", e)
            null
        }
    }

    /**
     * Раньше фото лежали в Pictures/3D Camera, а видео — в Movies/3D Camera, и галерея показывала
     * их как два альбома. Переносит снимки в общую папку. Без разрешения на чтение хранилища
     * MediaStore отдаёт только файлы этой сборки, чужие не затрагиваются. Вызывать не на UI-потоке.
     */
    fun moveToCommonFolder() {
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val oldPaths = arrayOf("${Environment.DIRECTORY_PICTURES}/$ALBUM/", "${Environment.DIRECTORY_MOVIES}/$ALBUM/")
        val columns = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH) }
        try {
            resolver.query(files, columns, "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)", oldPaths, null)?.use { c ->
                while (c.moveToNext()) {
                    // Через общий URI files перенос разрешён только в Download/Documents
                    val uri = typedUri(c.getLong(0), c.getInt(1))
                    try {
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось перенести $uri", e)
                    }
                }
                if (c.count > 0) Log.i(TAG, "Перенесено в $RELATIVE_PATH: ${c.count}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось перенести снимки в общую папку", e)
        }
    }

    private fun typedUri(id: Long, mediaType: Int): Uri {
        val base = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        return Uri.withAppendedPath(base, id.toString())
    }

    /** Миниатюра снимка или видео. Вызывать не на UI-потоке. */
    fun loadThumbnail(uri: Uri, size: Int): Bitmap? = try {
        resolver.loadThumbnail(uri, Size(size, size), null)
    } catch (e: Exception) {
        Log.e(TAG, "Не удалось загрузить миниатюру $uri", e)
        null
    }

    companion object {
        private const val TAG = "MediaSaver"
        const val ALBUM = "3D Camera"
        /** Фото и видео в одной папке — в галерее это один альбом. */
        private val RELATIVE_PATH = "${Environment.DIRECTORY_DCIM}/$ALBUM"
    }
}
