package io.mmirror

import android.util.Log

object NativeBridge {
    private const val TAG = "NativeBridge"

    init {
        try {
            System.loadLibrary("mmirror_core")
            init()
            Log.i(TAG, "mmirror_core native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load mmirror_core native library", e)
        }
    }

    interface TouchListener {
        fun onTouch(action: String, id: Int, x: Float, y: Float)
        fun onKey(key: String)
        fun onCommand(cmd: String)
    }

    private var touchListener: TouchListener? = null

    fun setTouchListener(listener: TouchListener?) {
        this.touchListener = listener
    }

    // --- Native Methods implemented in Rust ---
    private external fun init()
    external fun startServer(port: Int): Int
    external fun getServerPort(): Int
    external fun stopServer()
    external fun startTunProxy(fd: Int, targetPort: Int): Boolean
    external fun stopTunProxy()
    external fun getNativeLogs(): String

    fun isServerRunning(): Boolean = getServerPort() > 0
    external fun sendVideoFrame(data: ByteArray, offset: Int, length: Int)
    external fun sendAudioData(data: ByteArray, offset: Int, length: Int)
    external fun updateConfig(width: Int, height: Int, rotation: Int, fps: Int)
    external fun sendGpsData(lat: Double, lng: Double, speed: Float, heading: Float, distance: Double, duration: Long)
    external fun saveTripRecord(tripJson: String)

    // --- Called from Rust via JNI ---
    @JvmStatic
    fun onTouchEvent(action: String, id: Int, x: Float, y: Float) {
        touchListener?.onTouch(action, id, x, y)
    }

    @JvmStatic
    fun onKeyEvent(key: String) {
        touchListener?.onKey(key)
    }

    @JvmStatic
    fun onCommandEvent(cmd: String) {
        touchListener?.onCommand(cmd)
    }
}
