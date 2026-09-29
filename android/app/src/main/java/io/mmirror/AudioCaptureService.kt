package io.mmirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log

class AudioCaptureService(private val mediaProjection: MediaProjection) {

    private var audioRecord: AudioRecord? = null
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
            Log.w(TAG, "Audio playback capture requires Android 10 (API 29) or higher.")
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
            val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread({
                val buffer = ByteArray(bufferSize)
                Log.i(TAG, "Audio capture loop started")

                while (isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        NativeBridge.sendAudioData(buffer, 0, bytesRead)
                        MediaProjectionService.instance?.sendRelayAudio(buffer, bytesRead)
                    } else {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {}
                    }
                }
                Log.i(TAG, "Audio capture loop ended")
            }, "mMirror-AudioRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio playback capture", e)
            stop()
        }
    }

    fun stop() {
        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
        Log.i(TAG, "Audio capture stopped")
    }
}
