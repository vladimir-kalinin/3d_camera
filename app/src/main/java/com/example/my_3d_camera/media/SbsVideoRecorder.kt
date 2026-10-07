package com.example.my_3d_camera.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Пишет готовый кадр SBS (из GL-рендерера) и звук с микрофона в один MP4.
 * Файл сразу создаётся в галерее, копировать его потом не нужно.
 *
 * Используются MediaCodec + MediaMuxer, а не MediaRecorder: MediaRecorder не позволяет
 * задать цветовое пространство, и для кадров от ~6.3 Мп (3072×2048 уже считается «4K»)
 * Android помечает видео как BT.2020 — плееры растягивают охват и цвета перенасыщаются.
 * Кадры из GL — это BT.709/sRGB, поэтому задаём BT.709 явно.
 */
class SbsVideoRecorder(private val saver: MediaSaver) {

    private var session: Session? = null
    private var uri: Uri? = null
    private var fd: ParcelFileDescriptor? = null

    /** Готовит и запускает запись. Возвращает поверхность, в которую нужно рисовать кадры. */
    fun start(size: Size, frameRate: Int, withAudio: Boolean): Surface {
        val target = saver.createPendingVideo() ?: error("couldn't create a file in the gallery")
        uri = target

        try {
            val descriptor = saver.openForWrite(target) ?: error("couldn't open the file for writing")
            fd = descriptor
            val s = Session(descriptor.fileDescriptor, size, frameRate, withAudio)
            session = s
            Log.i(TAG, "Запись начата: ${size.width}x${size.height}@$frameRate, " +
                    "${bitRateFor(size, frameRate) / 1_000_000} Мбит/с, звук=${s.hasAudio}")
            return s.inputSurface
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
        val s = session ?: return null
        val ok = s.finish()
        if (!ok) Log.e(TAG, "Запись пустая или повреждена")
        val result = uri
        cleanup(keepFile = ok)
        return if (ok) result else null
    }

    private fun cleanup(keepFile: Boolean) {
        session?.release()
        session = null
        fd?.close()
        fd = null
        uri?.let { if (keepFile) saver.publish(it) else saver.delete(it) }
        uri = null
    }

    /** Одна запись: видеоэнкодер, аудиоэнкодер с микрофоном и общий MP4. */
    private class Session(fd: FileDescriptor, size: Size, frameRate: Int, withAudio: Boolean) {
        private val muxer = SharedMuxer(fd)
        private val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        lateinit var inputSurface: Surface
            private set
        private var audio: AudioPipeline? = null
        val hasAudio: Boolean get() = audio != null
        private var videoThread: Thread? = null
        @Volatile private var videoFrames = 0

        init {
            try {
                video.configure(videoFormat(size, frameRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = video.createInputSurface()
                audio = if (withAudio) AudioPipeline.create(muxer) else null
                muxer.expectedTracks = if (audio != null) 2 else 1
                video.start()
                videoThread = Thread({ drainVideo() }, "SbsVideoEncoder").apply { start() }
                audio?.start()
            } catch (e: Exception) {
                release()
                throw e
            }
        }

        private fun videoFormat(size: Size, frameRate: Int) =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRateFor(size, frameRate))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }

        private fun drainVideo() {
            val info = MediaCodec.BufferInfo()
            var track = -1
            try {
                while (true) {
                    val index = video.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = muxer.addTrack(video.outputFormat)
                        index >= 0 -> {
                            val buffer = video.getOutputBuffer(index)
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (buffer != null && !isConfig && info.size > 0 && track >= 0) {
                                muxer.write(track, buffer, info)
                                videoFrames++
                            }
                            video.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка видеоэнкодера", e)
            }
        }

        /** Дописывает оставшиеся кадры и закрывает файл. true — если файл годный. */
        fun finish(): Boolean {
            try {
                video.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось завершить видеопоток", e)
            }
            audio?.stop()
            videoThread?.join(FINISH_TIMEOUT_MS)
            return muxer.finish() && videoFrames > 0
        }

        fun release() {
            audio?.release()
            runCatching { video.stop() }
            video.release()
            if (::inputSurface.isInitialized) inputSurface.release()
            muxer.release()
        }
    }

    /** Микрофон → AAC. Работает на своём потоке в синхронном режиме кодека. */
    private class AudioPipeline private constructor(
        private val muxer: SharedMuxer,
        private val record: AudioRecord,
        private val encoder: MediaCodec,
    ) {
        /** Момент остановки (мкс, System.nanoTime), до которого нужно дописать звук. */
        @Volatile private var stopAtUs = Long.MAX_VALUE
        private val thread = Thread({ loop() }, "SbsAudioEncoder")

        fun start() {
            record.startRecording()
            encoder.start()
            thread.start()
        }

        /**
         * Звук приходит из микрофона с задержкой ~0.25 с, поэтому после остановки
         * дочитываем буфер, пока метки времени не дойдут до момента остановки.
         */
        fun stop() {
            stopAtUs = System.nanoTime() / 1000
            thread.join(FINISH_TIMEOUT_MS)
        }

        fun release() {
            runCatching { record.stop() }
            record.release()
            runCatching { encoder.stop() }
            encoder.release()
        }

        private fun loop() {
            val pcm = ByteArray(READ_BYTES)
            val info = MediaCodec.BufferInfo()
            val timestamp = AudioTimestamp()
            var track = -1
            var framesRead = 0L
            // Время (System.nanoTime) первого сэмпла — та же шкала, что у кадров видео
            var startNs = -1L
            var eosQueued = false
            try {
                while (true) {
                    if (!eosQueued) {
                        val index = encoder.dequeueInputBuffer(DRAIN_TIMEOUT_US)
                        if (index >= 0) {
                            val input = encoder.getInputBuffer(index)!!
                            val stopAt = stopAtUs
                            val caughtUp = startNs >= 0 && ptsUs(startNs, framesRead) >= stopAt
                            if (caughtUp || System.nanoTime() / 1000 - MAX_TAIL_US > stopAt) {
                                encoder.queueInputBuffer(index, 0, 0, ptsUs(startNs, framesRead), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosQueued = true
                            } else {
                                val read = record.read(pcm, 0, minOf(pcm.size, input.capacity()))
                                if (read > 0) {
                                    if (startNs < 0) {
                                        startNs = if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                            timestamp.nanoTime - timestamp.framePosition * 1_000_000_000L / SAMPLE_RATE
                                        } else {
                                            System.nanoTime() - (read / BYTES_PER_FRAME) * 1_000_000_000L / SAMPLE_RATE
                                        }
                                    }
                                    input.clear()
                                    input.put(pcm, 0, read)
                                    encoder.queueInputBuffer(index, 0, read, ptsUs(startNs, framesRead), 0)
                                    framesRead += read / BYTES_PER_FRAME
                                } else {
                                    encoder.queueInputBuffer(index, 0, 0, ptsUs(startNs, framesRead), 0)
                                }
                            }
                        }
                    }
                    // Забираем всё, что кодек уже выдал
                    while (true) {
                        val index = encoder.dequeueOutputBuffer(info, if (eosQueued) DRAIN_TIMEOUT_US else 0)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(encoder.outputFormat)
                            continue
                        }
                        if (index < 0) break
                        val buffer = encoder.getOutputBuffer(index)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && !isConfig && info.size > 0 && track >= 0) muxer.write(track, buffer, info)
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка аудиоэнкодера", e)
            }
        }

        private fun ptsUs(startNs: Long, frames: Long): Long =
            (maxOf(startNs, 0L) + frames * 1_000_000_000L / SAMPLE_RATE) / 1000

        companion object {
            private const val SAMPLE_RATE = 48_000
            private const val CHANNELS = 2
            private const val BYTES_PER_FRAME = 2 * CHANNELS
            /** ~20 мс звука за одно чтение. */
            private const val READ_BYTES = SAMPLE_RATE / 50 * BYTES_PER_FRAME
            /** Дольше этого хвост звука после остановки не ждём. */
            private const val MAX_TAIL_US = 1_000_000L

            /** null — если микрофон недоступен; тогда пишем видео без звука. */
            @SuppressLint("MissingPermission") // разрешение проверяется в MainActivity
            fun create(muxer: SharedMuxer): AudioPipeline? {
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val record = try {
                    AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, READ_BYTES) * 4)
                } catch (e: Exception) {
                    Log.e(TAG, "Микрофон недоступен", e)
                    return null
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Микрофон не инициализировался")
                    record.release()
                    return null
                }
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, READ_BYTES)
                }
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                return try {
                    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    AudioPipeline(muxer, record, encoder)
                } catch (e: Exception) {
                    Log.e(TAG, "Аудиоэнкодер не настроился", e)
                    encoder.release()
                    record.release()
                    null
                }
            }
        }
    }

    /**
     * MediaMuxer, в который пишут два потока. Стартует, когда добавлены все дорожки;
     * пришедшие раньше сэмплы копируются и ждут старта.
     */
    private class SharedMuxer(fd: FileDescriptor) {
        private class Pending(val track: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

        private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val pending = mutableListOf<Pending>()
        private var tracks = 0
        private var started = false
        private var released = false
        @Volatile var expectedTracks = 1

        @Synchronized
        fun addTrack(format: MediaFormat): Int {
            val track = muxer.addTrack(format)
            if (++tracks == expectedTracks) {
                muxer.start()
                started = true
                for (p in pending) muxer.writeSampleData(p.track, p.data, p.info)
                pending.clear()
            }
            return track
        }

        @Synchronized
        fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            if (started) {
                muxer.writeSampleData(track, buffer, info)
            } else {
                val copy = ByteBuffer.allocateDirect(info.size).put(buffer).apply { flip() }
                val copyInfo = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                pending += Pending(track, copy, copyInfo)
            }
        }

        /** Закрывает файл. true — если в него что-то записано. */
        @Synchronized
        fun finish(): Boolean {
            if (!started || released) return false
            return try {
                muxer.stop()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось закрыть MP4", e)
                false
            } finally {
                started = false
            }
        }

        @Synchronized
        fun release() {
            if (released) return
            released = true
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    companion object {
        private const val TAG = "SbsVideoRecorder"
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val FINISH_TIMEOUT_MS = 3000L

        /** ~0.2 бита на пиксель: для 3072x2048@30 это около 38 Мбит/с. */
        private fun bitRateFor(size: Size, frameRate: Int): Int =
            (size.width.toLong() * size.height * frameRate / 5).coerceIn(8_000_000, 80_000_000).toInt()
    }
}
