package io.mmirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class TouchControlService : AccessibilityService(), NativeBridge.TouchListener {

    private val activeTouchPoints = mutableMapOf<Int, PointF>()
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    companion object {
        private const val TAG = "TouchControlService"
        var instance: TouchControlService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        updateScreenDimensions()
        NativeBridge.setTouchListener(this)
        Log.i(TAG, "TouchControlService connected and registered with NativeBridge")
    }

    override fun onDestroy() {
        super.onDestroy()
        NativeBridge.setTouchListener(null)
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "TouchControlService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 이벤트 청취는 필요 시 처리
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    private fun updateScreenDimensions() {
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
    }

    fun updateDimensions(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        Log.i(TAG, "Touch dimensions updated to: ${screenWidth}x${screenHeight}")
    }

    private data class TouchSession(
        val startX: Float,
        val startY: Float,
        var lastX: Float,
        var lastY: Float,
        val startTime: Long,
        var isMoved: Boolean = false
    )

    private val activeTouchSessions = mutableMapOf<Int, TouchSession>()

    // --- NativeBridge.TouchListener 구현 ---

    override fun onTouch(action: String, id: Int, x: Float, y: Float) {
        val pixelX = (x * screenWidth).coerceIn(0f, screenWidth.toFloat())
        val pixelY = (y * screenHeight).coerceIn(0f, screenHeight.toFloat())

        when (action) {
            "down" -> {
                activeTouchSessions[id] = TouchSession(
                    startX = pixelX,
                    startY = pixelY,
                    lastX = pixelX,
                    lastY = pixelY,
                    startTime = System.currentTimeMillis()
                )
            }
            "move" -> {
                val session = activeTouchSessions[id] ?: return
                val dist = kotlin.math.hypot(pixelX - session.lastX, pixelY - session.lastY)
                if (dist > 8f) {
                    session.isMoved = true
                    dispatchSwipe(session.lastX, session.lastY, pixelX, pixelY, 60)
                    session.lastX = pixelX
                    session.lastY = pixelY
                }
            }
            "up" -> {
                val session = activeTouchSessions.remove(id)
                if (session != null) {
                    val duration = System.currentTimeMillis() - session.startTime
                    if (!session.isMoved && duration < 500) {
                        // 이동이 거의 없는 단발 터치 -> 정확한 탭 주입
                        dispatchTap(session.startX, session.startY)
                    } else if (session.isMoved) {
                        // 스와이프 마무리
                        val dist = kotlin.math.hypot(pixelX - session.lastX, pixelY - session.lastY)
                        if (dist > 4f) {
                            dispatchSwipe(session.lastX, session.lastY, pixelX, pixelY, 40)
                        }
                    }
                }
            }
        }
    }

    override fun onKey(key: String) {
        try {
            when (key.uppercase()) {
                "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "SPLIT_SCREEN" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    }
                }
                "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "LOCK" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    }
                }
                else -> Log.w(TAG, "Unhandled key action: $key")
            }
        } catch (e: Exception) {
            Log.w(TAG, "onKey error: ${e.message}")
        }
    }

    override fun onCommand(cmd: String) {
        Log.i(TAG, "Command received: $cmd")
        try {
            when (cmd.uppercase()) {
                "ROTATE" -> MediaProjectionService.instance?.toggleOrientation()
                else -> Log.w(TAG, "Unhandled command: $cmd")
            }
        } catch (e: Exception) {
            Log.w(TAG, "onCommand error: ${e.message}")
        }
    }

    // 단발 탭 주입 (무선 디버깅 ADB 우선, 접근성 서비스 폴백)
    private fun dispatchTap(x: Float, y: Float) {
        if (io.mmirror.adb.AdbTouchManager.isShizukuAvailable) {
            io.mmirror.adb.AdbTouchManager.injectTap(x, y)
            return
        }
        try {
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val builder = GestureDescription.Builder().apply {
                addStroke(stroke)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setDisplayId(android.view.Display.DEFAULT_DISPLAY)
                }
            }
            dispatchGesture(builder.build(), null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchTap failed: ${t.message}")
        }
    }

    // 스와이프/드래그 주입 (무선 디버깅 ADB 우선, 접근성 서비스 폴백)
    private fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        if (io.mmirror.adb.AdbTouchManager.isShizukuAvailable) {
            io.mmirror.adb.AdbTouchManager.injectSwipe(startX, startY, endX, endY, durationMs)
            return
        }
        try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(20L))
            val builder = GestureDescription.Builder().apply {
                addStroke(stroke)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setDisplayId(android.view.Display.DEFAULT_DISPLAY)
                }
            }
            dispatchGesture(builder.build(), null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchSwipe failed: ${t.message}")
        }
    }
}
