package io.mmirror

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * mMirror 초절전 암전(Screen Dimmer) 통합 관리자
 * 
 * 테슬라 웹 플레이어(가상화면 독바의 절전 버튼) 및 스마트폰 UI에서 호출 시:
 * 1. Settings.System.SCREEN_BRIGHTNESS를 직접 1로 조절 (시스템 설정 권한 보유 시 타 앱 실행 중에도 즉시 작동)
 * 2. AccessibilityService의 TYPE_ACCESSIBILITY_OVERLAY 활용 (접근성 활성화 시)
 * 3. MainActivity Window Brightness 동기화
 */
object ScreenDimmerManager {
    private const val TAG = "ScreenDimmerManager"

    @Volatile
    var isDimmed: Boolean = false
        private set

    private var appContext: Context? = null
    private var savedBrightness: Int = -1
    private var savedBrightnessMode: Int = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    private var systemOverlayView: android.view.View? = null

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun toggleDim() {
        setDimmed(!isDimmed)
    }

    fun setDimmed(dimmed: Boolean) {
        if (isDimmed == dimmed) return
        isDimmed = dimmed
        AppLogger.i(TAG, if (dimmed) "🌙 스마트폰 초절전 암전(OLED Black/최저밝기) 모드 활성화" else "☀️ 스마트폰 화면 밝기 복원")

        val ctx = appContext
        if (ctx != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(ctx))) {
            try {
                if (dimmed) {
                    if (savedBrightness == -1) {
                        val current = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                        savedBrightness = if (current < 30) 128 else current
                        savedBrightnessMode = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                    }
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 1)
                    AppLogger.i(TAG, "🌙 [DIMMER] 하드웨어 백라이트 밝기 최저치(1) 직접 적용 성공 (복원용 이전 밝기: $savedBrightness)")
                } else {
                    val restoreVal = if (savedBrightness < 30) 128 else savedBrightness
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, restoreVal)
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, savedBrightnessMode)
                    AppLogger.i(TAG, "☀️ [DIMMER] 하드웨어 백라이트 밝기 복원 성공: $restoreVal")
                    savedBrightness = -1
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "하드웨어 밝기 제어 실패: ${e.message}")
            }
        }

        Handler(Looper.getMainLooper()).post {
            // 1. Accessibility Service 시스템 전역 오버레이 (접근성 활성화 시)
            val touchService = TouchControlService.instance
            if (touchService != null) {
                touchService.setScreenDimmed(dimmed)
            }

            // 1-1. '다른 앱 위에 표시' TYPE_APPLICATION_OVERLAY 처리 (접근성이 꺼져 있어도 다른 앱 위에 표시 권한으로 타 앱 실행 중 백라이트 0.001f 강제 적용)
            val wm = ctx?.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            if (wm != null) {
                if (dimmed && touchService == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(ctx)) {
                    if (systemOverlayView == null) {
                        try {
                            val overlay = android.view.View(ctx).apply {
                                setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0))
                            }
                            val params = android.view.WindowManager.LayoutParams(
                                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                                android.graphics.PixelFormat.TRANSLUCENT
                            ).apply {
                                screenBrightness = 0.001f
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                                }
                            }
                            wm.addView(overlay, params)
                            systemOverlayView = overlay
                            AppLogger.i(TAG, "✓ TYPE_APPLICATION_OVERLAY 시스템 오버레이 활성화 (타 앱 실행 중 백라이트 0.001f 강제 적용)")
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Failed to add system overlay view: ${e.message}")
                        }
                    }
                } else if (!dimmed) {
                    systemOverlayView?.let { view ->
                        try {
                            val p = view.layoutParams as? android.view.WindowManager.LayoutParams
                            if (p != null) {
                                p.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                                wm.updateViewLayout(view, p)
                            }
                        } catch (_: Exception) {}
                        try {
                            wm.removeView(view)
                            AppLogger.i(TAG, "✓ TYPE_APPLICATION_OVERLAY 시스템 오버레이 제거 (화면 밝기 정상 복구)")
                        } catch (_: Exception) {}
                        systemOverlayView = null
                    }
                }
            }

            // 2. MainActivity Window Brightness 및 UI 버튼 동기화
            MainActivity.instance?.setWindowDimmed(dimmed)

            // 3. 등록된 리스너(WebRtcStreamer 등)에 상태 통보
            val currentListeners = synchronized(listeners) { listeners.toList() }
            currentListeners.forEach {
                try {
                    it.invoke(dimmed)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Listener error: ${e.message}")
                }
            }
        }
    }

    fun toggle() {
        setDimmed(!isDimmed)
    }
}
