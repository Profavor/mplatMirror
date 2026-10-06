package io.mmirror.webrtc

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.mmirror.AppLogger
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * mMirror 고성능 WebRTC 화면 캡처러.
 * MediaProjection 기반 AUTO_MIRROR VirtualDisplay로 스마트폰 화면을 60 FPS 저지연 캡처합니다.
 */
class PresentationVideoCapturer(
    private val context: Context,
    var mediaProjection: MediaProjection? = null,
    private var densityDpi: Int = 180,
    val initialMode: CaptureMode = CaptureMode.PHONE_MIRROR
) : VideoCapturer, VideoSink {

    companion object {
        private const val TAG = "PresentationCapturer"
        var instance: PresentationVideoCapturer? = null
            private set
    }

    enum class CaptureMode {
        PHONE_MIRROR
    }

    val currentMode: CaptureMode = CaptureMode.PHONE_MIRROR

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var surface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var isDisposed = false
    private var width = 1600
    private var height = 1120

    val displayId: Int
        get() = virtualDisplay?.display?.displayId ?: -1

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, ignoredFramerate: Int) {
        this.width = width
        this.height = height
        val helper = surfaceTextureHelper ?: throw IllegalStateException("surfaceTextureHelper is null")

        helper.setTextureSize(width, height)
        val surf = Surface(helper.surfaceTexture)
        this.surface = surf

        val mp = mediaProjection ?: io.mmirror.MediaProjectionService.instance?.getMediaProjection()
        instance = this

        if (mp != null) {
            try {
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        AppLogger.w(TAG, "⚠️ MediaProjection onStop callback: 시스템에 의해 세션 강제 종료됨")
                    }

                    override fun onCapturedContentResize(width: Int, height: Int) {
                        AppLogger.i(TAG, "📱 MediaProjection onCapturedContentResize: ${width}x${height}")
                        io.mmirror.MediaProjectionService.instance?.scheduleDisplayChangeCheck()
                    }

                    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                        AppLogger.i(TAG, "📱 MediaProjection onCapturedContentVisibilityChanged: isVisible=$isVisible")
                        if (isVisible) {
                            io.mmirror.MediaProjectionService.instance?.scheduleDisplayChangeCheck()
                        }
                    }
                }, Handler(Looper.getMainLooper()))

                val vd = mp.createVirtualDisplay(
                    "mMirror_Tesla_Mirror",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surf,
                    null,
                    null
                )
                virtualDisplay = vd
                AppLogger.i(TAG, "✓ Mirror VirtualDisplay active on WebRTC surface (${width}x${height}, dpi=$densityDpi)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ Failed to create Mirror VirtualDisplay: ${e.message}", e)
            }
        } else {
            AppLogger.w(TAG, "⚠️ MediaProjection is null during startCapture")
        }

        capturerObserver?.onCapturerStarted(true)
        helper.startListening(this)
    }

    override fun stopCapture() {
        val helper = surfaceTextureHelper
        if (helper != null) {
            ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
                helper.stopListening()
                capturerObserver?.onCapturerStopped()
                try {
                    virtualDisplay?.setSurface(null)
                    virtualDisplay?.release()
                } catch (_: Exception) {}
                virtualDisplay = null
                try {
                    surface?.release()
                } catch (_: Exception) {}
                surface = null
                if (instance == this) instance = null
                Log.i(TAG, "PresentationCapturer stopped")
            }
        } else {
            try {
                virtualDisplay?.release()
                virtualDisplay = null
                surface?.release()
                surface = null
            } catch (_: Exception) {}
            if (instance == this) instance = null
        }
    }

    /**
     * 폴드 접힘/펼침 및 화면 회전 시 VirtualDisplay와 SurfaceFlinger 버퍼 큐를 재연결합니다.
     */
    fun updateResolution(newWidth: Int, newHeight: Int, newDensity: Int) {
        this.width = newWidth
        this.height = newHeight
        this.densityDpi = newDensity

        val helper = surfaceTextureHelper ?: return
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
            try {
                helper.setTextureSize(newWidth, newHeight)
                val surf = surface ?: Surface(helper.surfaceTexture).also { surface = it }
                val vd = virtualDisplay
                if (vd != null) {
                    // SurfaceFlinger 파이프라인 단절 방지를 위해 setSurface(null) 없이 안전하게 리사이즈만 수행
                    vd.resize(newWidth, newHeight, newDensity)
                    AppLogger.i(TAG, "✓ VirtualDisplay resized safely: ${newWidth}x${newHeight}, dpi=$newDensity")
                } else {
                    val mp = mediaProjection ?: io.mmirror.MediaProjectionService.instance?.getMediaProjection()
                    if (mp != null) {
                        virtualDisplay = mp.createVirtualDisplay(
                            "mMirror_Tesla_Mirror",
                            newWidth,
                            newHeight,
                            newDensity,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            surf,
                            null,
                            null
                        )
                        AppLogger.i(TAG, "✓ VirtualDisplay recreated: ${newWidth}x${newHeight}, dpi=$newDensity")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ Failed to update capturer resolution: ${e.message}", e)
            }
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, ignoredFramerate: Int) {
        updateResolution(width, height, this.densityDpi)
    }

    override fun dispose() {
        isDisposed = true
    }

    override fun isScreencast(): Boolean = true

    @Volatile var targetFps: Int = 60
    private var lastDeliveredTimestampNs: Long = 0L

    override fun onFrame(frame: VideoFrame) {
        val fps = targetFps
        if (fps in 1..59) {
            val minIntervalNs = (1_000_000_000L / fps) - 2_000_000L
            val now = System.nanoTime()
            if (lastDeliveredTimestampNs > 0L && (now - lastDeliveredTimestampNs) < minIntervalNs) {
                return
            }
            lastDeliveredTimestampNs = now
        } else {
            lastDeliveredTimestampNs = System.nanoTime()
        }
        capturerObserver?.onFrameCaptured(frame)
    }
}
