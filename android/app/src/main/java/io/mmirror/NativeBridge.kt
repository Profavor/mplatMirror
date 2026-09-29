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
    @JvmStatic
    private external fun init()
    @JvmStatic
    external fun startServer(port: Int): Int
    @JvmStatic
    external fun getServerPort(): Int
    @JvmStatic
    external fun stopServer()
    @JvmStatic
    external fun startTunProxy(fd: Int, targetPort: Int): Boolean
    @JvmStatic
    external fun stopTunProxy()
    @JvmStatic
    external fun getNativeLogs(): String

    fun isServerRunning(): Boolean = getServerPort() > 0
    @JvmStatic
    external fun sendVideoFrame(data: ByteArray, offset: Int, length: Int)
    @JvmStatic
    external fun sendAudioData(data: ByteArray, offset: Int, length: Int)
    @JvmStatic
    external fun updateConfig(width: Int, height: Int, rotation: Int, fps: Int)
    @JvmStatic
    external fun sendGpsData(lat: Double, lng: Double, speed: Float, heading: Float, distance: Double, duration: Long)
    @JvmStatic
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
