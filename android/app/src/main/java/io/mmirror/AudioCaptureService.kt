package io.mmirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build

class AudioCaptureService(private val mediaProjection: MediaProjection) {

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null

    companion object {
        private const val TAG = "AudioCaptureService"
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AppLogger.w(TAG, "Audio playback capture requires Android 10 (API 29) or higher.")
            return
        }

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val internalBufferSize = (minBufferSize * 4).coerceAtLeast(32768)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(internalBufferSize)
                .build()

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread({
                // 20ms 표준 청크: 48,000Hz * 0.02s = 960 스테레오 샘플 = 3,840 바이트
                val chunkSamples = 960
                val chunkBytes = chunkSamples * 2 * 2 // 2채널 * 16비트(2바이트) = 3840바이트
                val chunkBuffer = ByteArray(chunkBytes)
                AppLogger.i(TAG, "🔊 Audio capture loop started (48kHz Stereo 16-bit PCM, 20ms/$chunkBytes bytes per packet)")

                while (isRecording) {
                    val bytesRead = audioRecord?.read(chunkBuffer, 0, chunkBytes, AudioRecord.READ_BLOCKING) ?: -1
                    if (bytesRead > 0) {
                        MediaProjectionService.instance?.sendWebRtcAudio(chunkBuffer, bytesRead)
                    } else if (bytesRead < 0) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                AppLogger.i(TAG, "🔊 Audio capture loop ended")
            }, "mMirror-AudioRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start audio playback capture: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        if (!isRecording && audioRecord == null) return
        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null
        AppLogger.i(TAG, "🔊 Audio capture stopped")
    }

    fun isCapturing(): Boolean = isRecording
}
