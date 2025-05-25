package com.example.my_3d_camera.video

import android.media.*
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class VideoProcessor {
    
    companion object {
        private const val TAG = "VideoProcessor"
        private const val TIMEOUT_USEC = 10000L
    }
    
    data class VideoInfo(
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val duration: Long,
        val bitRate: Int
    )
    
    interface ProcessingCallback {
        fun onProgress(progress: Float)
        fun onCompleted()
        fun onError(error: String)
    }
    
    fun getVideoInfo(videoPath: String): VideoInfo? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoPath)
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                
                if (mime?.startsWith("video/") == true) {
                    val width = format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    val frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE)
                    val duration = format.getLong(MediaFormat.KEY_DURATION)
                    val bitRate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        format.getInteger(MediaFormat.KEY_BIT_RATE)
                    } else {
                        1000000 // default 1Mbps
                    }
                    
                    return VideoInfo(width, height, frameRate, duration, bitRate)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения информации о видео", e)
        } finally {
            extractor.release()
        }
        
        return null
    }
    
    fun extractVideoFrames(
        videoPath: String,
        outputSurface: Surface,
        callback: ProcessingCallback
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        
        try {
            extractor.setDataSource(videoPath)
            
            // Находим видеодорожку
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                
                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            
            if (videoTrackIndex == -1 || videoFormat == null) {
                callback.onError("Видеодорожка не найдена")
                return
            }
            
            extractor.selectTrack(videoTrackIndex)
            
            // Создаем декодер
            val mime = videoFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(videoFormat, outputSurface, null, 0)
            decoder.start()
            
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEOS = false
            var outputEOS = false
            val duration = videoFormat.getLong(MediaFormat.KEY_DURATION)
            
            while (!outputEOS) {
                // Подаем данные на вход декодера
                if (!inputEOS) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                            
                            // Обновляем прогресс
                            val progress = presentationTimeUs.toFloat() / duration.toFloat()
                            callback.onProgress(progress)
                        }
                    }
                }
                
                // Получаем декодированные кадры
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Нет доступных выходных буферов
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Формат вывода изменен: ${decoder.outputFormat}")
                    }
                    outputBufferIndex >= 0 -> {
                        // Кадр готов для рендеринга
                        decoder.releaseOutputBuffer(outputBufferIndex, true)
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEOS = true
                        }
                    }
                }
            }
            
            callback.onCompleted()
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка извлечения кадров", e)
            callback.onError("Ошибка извлечения кадров: ${e.message}")
        } finally {
            decoder?.stop()
            decoder?.release()
            extractor.release()
        }
    }
    
    fun createVideoEncoder(
        outputPath: String,
        width: Int,
        height: Int,
        bitRate: Int,
        frameRate: Int
    ): Pair<MediaCodec, Surface>? {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            val inputSurface = encoder.createInputSurface()
            encoder.start()
            
            return Pair(encoder, inputSurface)
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания энкодера", e)
            return null
        }
    }
    
    fun encodeVideoFrames(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        callback: ProcessingCallback
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var videoTrackIndex = -1
        var muxerStarted = false
        var frameCount = 0
        
        try {
            while (true) {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                
                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Нет доступных выходных буферов
                        continue
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            throw RuntimeException("Формат изменился дважды")
                        }
                        
                        val newFormat = encoder.outputFormat
                        videoTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer запущен с форматом: $newFormat")
                    }
                    outputBufferIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outputBufferIndex)
                            ?: throw RuntimeException("Получен null буфер")
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        
                        if (bufferInfo.size != 0) {
                            if (!muxerStarted) {
                                throw RuntimeException("Muxer не запущен")
                            }
                            
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            frameCount++
                            
                            if (frameCount % 30 == 0) { // Обновляем прогресс каждые 30 кадров
                                callback.onProgress(frameCount.toFloat())
                            }
                        }
                        
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "Кодирование завершено")
                            break
                        }
                    }
                }
            }
            
            callback.onCompleted()
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка кодирования", e)
            callback.onError("Ошибка кодирования: ${e.message}")
        }
    }
    
    fun copyAudioTrack(inputPath: String, muxer: MediaMuxer): Int? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputPath)
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                
                if (mime?.startsWith("audio/") == true) {
                    extractor.selectTrack(i)
                    val audioTrackIndex = muxer.addTrack(format)
                    
                    val buffer = ByteBuffer.allocate(1024 * 1024)
                    val bufferInfo = MediaCodec.BufferInfo()
                    
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = extractor.sampleTime
                        bufferInfo.flags = extractor.sampleFlags
                        
                        muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                        extractor.advance()
                    }
                    
                    return audioTrackIndex
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка копирования аудиодорожки", e)
        } finally {
            extractor.release()
        }
        
        return null
    }
} 