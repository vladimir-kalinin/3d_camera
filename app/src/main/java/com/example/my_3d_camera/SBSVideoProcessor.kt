package com.example.my_3d_camera

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.my_3d_camera.video.SBSVideoComposer
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SBSVideoProcessor(private val context: Context) {
    
    companion object {
        private const val TAG = "SBSVideoProcessor"
    }
    
    interface SBSProcessingCallback {
        fun onProcessingStarted()
        fun onProcessingProgress(progress: Int)
        fun onProcessingCompleted(outputFile: File)
        fun onProcessingFailed(error: String)
    }
    
    private val sbsComposer = SBSVideoComposer()
    
    /**
     * Создает реальное SBS видео используя MediaCodec и OpenGL ES
     */
    fun createSBSVideo(
        wideVideoPath: String,
        mainVideoPath: String,
        callback: SBSProcessingCallback
    ) {
        Log.d(TAG, "Начинаем создание SBS видео:")
        Log.d(TAG, "  Широкоугольное: $wideVideoPath")
        Log.d(TAG, "  Основное: $mainVideoPath")
        
        callback.onProcessingStarted()
        
        val wideFile = File(wideVideoPath)
        val mainFile = File(mainVideoPath)
        
        if (!wideFile.exists() || !mainFile.exists()) {
            callback.onProcessingFailed("Один из видеофайлов не найден")
            return
        }
        
        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (outputDir == null) {
            callback.onProcessingFailed("Не удалось получить папку для сохранения")
            return
        }
        
        // Создаем папку если не существует
        outputDir.mkdirs()
        
        sbsComposer.createSBSVideo(
            wideVideoPath,
            mainVideoPath,
            outputDir,
            object : SBSVideoComposer.ComposerCallback {
                override fun onProgress(progress: Float) {
                    val progressPercent = (progress * 100).toInt()
                    callback.onProcessingProgress(progressPercent)
                }
                
                override fun onCompleted(outputFile: File) {
                    Log.d(TAG, "SBS видео создано: ${outputFile.absolutePath}")
                    Log.d(TAG, "Размер файла: ${outputFile.length()} байт")
                    
                    // Добавляем видео в галерею
                    addVideoToGallery(outputFile)
                    callback.onProcessingCompleted(outputFile)
                }
                
                override fun onError(error: String) {
                    Log.e(TAG, "Ошибка создания SBS видео: $error")
                    callback.onProcessingFailed(error)
                }
            }
        )
    }
    
    /**
     * Добавляет видеофайл в галерею Android
     */
    private fun addVideoToGallery(videoFile: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    videoFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                
                Log.d(TAG, "SBS видео добавлено в галерею: $uri")
            } else {
                Log.e(TAG, "Не удалось создать URI для галереи")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка добавления SBS видео в галерею", e)
        }
    }
    
    /**
     * Находит все парные видео в папке Movies
     */
    fun findVideoPairs(): List<VideoPair> {
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val videoFiles = moviesDir?.listFiles { file ->
            file.name.endsWith(".mp4") && (file.name.contains("Wide") || file.name.contains("Main"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
        
        if (videoFiles.isEmpty()) {
            return emptyList()
        }
        
        // Группируем видео по timestamp
        val videoGroups = mutableMapOf<String, MutableList<File>>()
        videoFiles.forEach { file ->
            val timestamp = extractTimestamp(file.name)
            if (timestamp != null) {
                videoGroups.getOrPut(timestamp) { mutableListOf() }.add(file)
            }
        }
        
        // Находим пары видео (Wide + Main)
        return videoGroups.values.mapNotNull { group ->
            if (group.size == 2 && 
                group.any { it.name.contains("Wide") } && 
                group.any { it.name.contains("Main") }) {
                
                val wide = group.first { it.name.contains("Wide") }
                val main = group.first { it.name.contains("Main") }
                VideoPair(wide, main, extractTimestamp(wide.name) ?: "")
            } else {
                null
            }
        }.sortedByDescending { it.timestamp }
    }
    
    /**
     * Извлекает timestamp из имени файла
     */
    private fun extractTimestamp(filename: String): String? {
        val regex = """(\d{8}_\d{6})""".toRegex()
        return regex.find(filename)?.value
    }
    
    /**
     * Класс для представления пары видео
     */
    data class VideoPair(
        val wideVideo: File,
        val mainVideo: File,
        val timestamp: String
    ) {
        fun getDisplayName(): String {
            return "SBS видео: $timestamp"
        }
        
        fun isValid(): Boolean {
            return wideVideo.exists() && mainVideo.exists()
        }
    }
} 