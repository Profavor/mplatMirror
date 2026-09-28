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

    // --- NativeBridge.TouchListener 구현 ---

    override fun onTouch(action: String, id: Int, x: Float, y: Float) {
        val pixelX = x * screenWidth
        val pixelY = y * screenHeight

        if (action == "down") {
            ensureScreenInteractable()
        }

        when (action) {
            "down" -> {
                activeTouchPoints[id] = PointF(pixelX, pixelY)
                // 즉시 탭 반응을 위한 짧은 제스처 디스패치
                dispatchTap(pixelX, pixelY)
            }
            "move" -> {
                val prev = activeTouchPoints[id]
                if (prev != null) {
                    dispatchSwipe(prev.x, prev.y, pixelX, pixelY, 60)
                    activeTouchPoints[id] = PointF(pixelX, pixelY)
                }
            }
            "up" -> {
                activeTouchPoints.remove(id)
            }
        }
    }

    override fun onKey(key: String) {
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
    }

    override fun onCommand(cmd: String) {
        Log.i(TAG, "Command received: $cmd")
        when (cmd.uppercase()) {
            "ROTATE" -> MediaProjectionService.instance?.toggleOrientation()
            else -> Log.w(TAG, "Unhandled command: $cmd")
        }
    }

    // 단발 탭 주입
    private fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)

        val vDisplayId = MediaProjectionService.virtualDisplayId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vDisplayId > 0) {
            try {
                builder.setDisplayId(vDisplayId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to setDisplayId on GestureDescription: ${e.message}")
            }
        }
        dispatchGesture(builder.build(), null, null)
    }

    // 스와이프/드래그 주입
    private fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)

        val vDisplayId = MediaProjectionService.virtualDisplayId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vDisplayId > 0) {
            try {
                builder.setDisplayId(vDisplayId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to setDisplayId on GestureDescription: ${e.message}")
            }
        }
        dispatchGesture(builder.build(), null, null)
    }

    private fun ensureScreenInteractable() {
        try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km?.isKeyguardLocked == true) {
                // 전원 버튼으로 화면이 잠겼을 때 키가드 해제 시도
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure screen interactable", e)
        }
    }
}
