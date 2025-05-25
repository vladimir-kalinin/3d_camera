package com.example.my_3d_camera.video

import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.example.my_3d_camera.opengl.SBSRenderer
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch

class SBSVideoComposer {
    
    companion object {
        private const val TAG = "SBSVideoComposer"
    }
    
    interface ComposerCallback {
        fun onProgress(progress: Float)
        fun onCompleted(outputFile: File)
        fun onError(error: String)
    }
    
    private val videoProcessor = VideoProcessor()
    private var sbsRenderer: SBSRenderer? = null
    
    // EGL контекст для OpenGL рендеринга
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    
    fun createSBSVideo(
        wideVideoPath: String,
        mainVideoPath: String,
        outputDir: File,
        callback: ComposerCallback
    ) {
        Thread {
            try {
                Log.d(TAG, "Начинаем создание SBS видео")
                Log.d(TAG, "  Широкоугольное: $wideVideoPath")
                Log.d(TAG, "  Основное: $mainVideoPath")
                
                // Получаем информацию о видео
                val wideInfo = videoProcessor.getVideoInfo(wideVideoPath)
                val mainInfo = videoProcessor.getVideoInfo(mainVideoPath)
                
                if (wideInfo == null || mainInfo == null) {
                    callback.onError("Не удалось получить информацию о видео")
                    return@Thread
                }
                
                Log.d(TAG, "Информация о широкоугольном видео: ${wideInfo.width}x${wideInfo.height}, ${wideInfo.frameRate}fps")
                Log.d(TAG, "Информация об основном видео: ${mainInfo.width}x${mainInfo.height}, ${mainInfo.frameRate}fps")
                
                // Определяем параметры выходного видео
                val outputWidth = maxOf(wideInfo.width, mainInfo.width) * 2
                val outputHeight = maxOf(wideInfo.height, mainInfo.height)
                val outputFrameRate = minOf(wideInfo.frameRate, mainInfo.frameRate)
                val outputBitRate = (wideInfo.bitRate + mainInfo.bitRate) * 2 // Увеличиваем битрейт для SBS
                
                Log.d(TAG, "Параметры выходного видео: ${outputWidth}x${outputHeight}, ${outputFrameRate}fps, ${outputBitRate}bps")
                
                // Создаем выходной файл
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val outputFile = File(outputDir, "3D_Video_SBS_$timestamp.mp4")
                
                // Создаем энкодер и muxer
                val encoderResult = videoProcessor.createVideoEncoder(
                    outputFile.absolutePath,
                    outputWidth,
                    outputHeight,
                    outputBitRate,
                    outputFrameRate
                )
                
                if (encoderResult == null) {
                    callback.onError("Не удалось создать видео энкодер")
                    return@Thread
                }
                
                val (encoder, encoderSurface) = encoderResult
                val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                
                try {
                    // Настраиваем EGL и OpenGL
                    setupEGL(encoderSurface, outputWidth, outputHeight)
                    
                    // Создаем SBS рендерер
                    sbsRenderer = SBSRenderer().apply {
                        initialize(outputWidth, outputHeight)
                    }
                    
                    // Запускаем обработку видео
                    processVideos(
                        wideVideoPath,
                        mainVideoPath,
                        encoder,
                        muxer,
                        outputFile,
                        callback
                    )
                    
                } finally {
                    // Очищаем ресурсы
                    sbsRenderer?.release()
                    releaseEGL()
                    encoder.stop()
                    encoder.release()
                    muxer.stop()
                    muxer.release()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка создания SBS видео", e)
                callback.onError("Ошибка создания SBS видео: ${e.message}")
            }
        }.start()
    }
    
    private fun setupEGL(surface: Surface, width: Int, height: Int) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Не удалось получить EGL дисплей")
        }
        
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Не удалось инициализировать EGL")
        }
        
        // Конфигурация EGL
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, configs.size, numConfigs, 0)) {
            throw RuntimeException("Не удалось выбрать EGL конфигурацию")
        }
        
        // Создаем EGL контекст
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Не удалось создать EGL контекст")
        }
        
        // Создаем EGL поверхность
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Не удалось создать EGL поверхность")
        }
        
        // Делаем контекст текущим
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("Не удалось сделать EGL контекст текущим")
        }
        
        // Настраиваем OpenGL
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
    }
    
    private fun processVideos(
        wideVideoPath: String,
        mainVideoPath: String,
        encoder: android.media.MediaCodec,
        muxer: MediaMuxer,
        outputFile: File,
        callback: ComposerCallback
    ) {
        val renderer = sbsRenderer ?: throw RuntimeException("SBS рендерер не инициализирован")
        
        // Получаем поверхности для декодеров
        val leftSurface = Surface(renderer.leftSurfaceTexture)
        val rightSurface = Surface(renderer.rightSurfaceTexture)
        
        // Счетчики для синхронизации
        val leftCompleted = CountDownLatch(1)
        val rightCompleted = CountDownLatch(1)
        var leftProgress = 0f
        var rightProgress = 0f
        
        // Запускаем декодирование левого видео (широкоугольное)
        Thread {
            videoProcessor.extractVideoFrames(
                wideVideoPath,
                leftSurface,
                object : VideoProcessor.ProcessingCallback {
                    override fun onProgress(progress: Float) {
                        leftProgress = progress
                        val totalProgress = (leftProgress + rightProgress) / 4f // 50% от общего прогресса
                        callback.onProgress(totalProgress)
                    }
                    
                    override fun onCompleted() {
                        Log.d(TAG, "Декодирование левого видео завершено")
                        leftCompleted.countDown()
                    }
                    
                    override fun onError(error: String) {
                        Log.e(TAG, "Ошибка декодирования левого видео: $error")
                        callback.onError("Ошибка декодирования левого видео: $error")
                    }
                }
            )
        }.start()
        
        // Запускаем декодирование правого видео (основное)
        Thread {
            videoProcessor.extractVideoFrames(
                mainVideoPath,
                rightSurface,
                object : VideoProcessor.ProcessingCallback {
                    override fun onProgress(progress: Float) {
                        rightProgress = progress
                        val totalProgress = (leftProgress + rightProgress) / 4f // 50% от общего прогресса
                        callback.onProgress(totalProgress)
                    }
                    
                    override fun onCompleted() {
                        Log.d(TAG, "Декодирование правого видео завершено")
                        rightCompleted.countDown()
                    }
                    
                    override fun onError(error: String) {
                        Log.e(TAG, "Ошибка декодирования правого видео: $error")
                        callback.onError("Ошибка декодирования правого видео: $error")
                    }
                }
            )
        }.start()
        
        // Рендерим кадры и кодируем
        var frameCount = 0
        val maxFrames = 1800 // Примерно 60 секунд при 30fps
        
        while (frameCount < maxFrames) {
            // Рендерим SBS кадр
            renderer.renderFrame()
            
            // Отправляем кадр в энкодер
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            
            frameCount++
            
            // Обновляем прогресс рендеринга (50-75% от общего)
            val renderProgress = 0.5f + (frameCount.toFloat() / maxFrames.toFloat()) * 0.25f
            callback.onProgress(renderProgress)
            
            // Небольшая задержка для контроля частоты кадров
            Thread.sleep(33) // ~30fps
        }
        
        // Завершаем кодирование
        encoder.signalEndOfInputStream()
        
        // Запускаем кодирование (75-90% от общего прогресса)
        videoProcessor.encodeVideoFrames(
            encoder,
            muxer,
            object : VideoProcessor.ProcessingCallback {
                override fun onProgress(progress: Float) {
                    val encodeProgress = 0.75f + (progress / 1000f) * 0.15f // Нормализуем прогресс
                    callback.onProgress(encodeProgress)
                }
                
                override fun onCompleted() {
                    Log.d(TAG, "Кодирование завершено")
                }
                
                override fun onError(error: String) {
                    Log.e(TAG, "Ошибка кодирования: $error")
                    callback.onError("Ошибка кодирования: $error")
                }
            }
        )
        
        // Копируем аудиодорожку (90-100% от общего прогресса)
        callback.onProgress(0.9f)
        videoProcessor.copyAudioTrack(wideVideoPath, muxer)
        
        callback.onProgress(1.0f)
        callback.onCompleted(outputFile)
        
        // Очищаем поверхности
        leftSurface.release()
        rightSurface.release()
    }
    
    private fun releaseEGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
} 