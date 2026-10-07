package com.example.my_3d_camera.media

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * Пишет готовый кадр SBS (из GL-рендерера) и звук с микрофона в один MP4.
 * Файл сразу создаётся в галерее, копировать его потом не нужно.
 */
class SbsVideoRecorder(private val context: Context, private val saver: MediaSaver) {

    private var recorder: MediaRecorder? = null
    private var uri: Uri? = null
    private var fd: ParcelFileDescriptor? = null

    /** Готовит и запускает запись. Возвращает поверхность, в которую нужно рисовать кадры. */
    fun start(size: Size, frameRate: Int, withAudio: Boolean): Surface {
        val target = saver.createPendingVideo() ?: error("couldn't create a file in the gallery")
        uri = target

        try {
            val descriptor = saver.openForWrite(target) ?: error("couldn't open the file for writing")
            fd = descriptor
            val mr = MediaRecorder(context)
            recorder = mr
            if (withAudio) mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(descriptor.fileDescriptor)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setVideoSize(size.width, size.height)
            mr.setVideoFrameRate(frameRate)
            mr.setVideoEncodingBitRate(bitRateFor(size, frameRate))
            if (withAudio) {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioChannels(2)
                mr.setAudioSamplingRate(48_000)
                mr.setAudioEncodingBitRate(192_000)
            }
            mr.prepare()
            mr.start()
            Log.i(TAG, "Запись начата: ${size.width}x${size.height}@$frameRate, ${bitRateFor(size, frameRate) / 1_000_000} Мбит/с")
            return mr.surface
        } catch (e: Exception) {
            cleanup(keepFile = false)
            throw e
        }
    }

    /**
     * Останавливает запись. Перед вызовом рендерер должен перестать рисовать в поверхность.
     * Возвращает Uri видео или null, если записать ничего не удалось.
     */
    fun stop(): Uri? {
        val mr = recorder ?: return null
        val ok = try {
            mr.stop()
            true
        } catch (e: RuntimeException) {
            // stop() бросает исключение, если не было записано ни одного кадра
            Log.e(TAG, "Запись пустая или повреждена", e)
            false
        }
        val result = uri
        cleanup(keepFile = ok)
        return if (ok) result else null
    }

    private fun cleanup(keepFile: Boolean) {
        recorder?.release()
        recorder = null
        fd?.close()
        fd = null
        uri?.let { if (keepFile) saver.publish(it) else saver.delete(it) }
        uri = null
    }

    companion object {
        private const val TAG = "SbsVideoRecorder"

        /** ~0.2 бита на пиксель: для 3072x2048@30 это около 38 Мбит/с. */
        private fun bitRateFor(size: Size, frameRate: Int): Int =
            (size.width.toLong() * size.height * frameRate / 5).coerceIn(8_000_000, 80_000_000).toInt()
    }
}
