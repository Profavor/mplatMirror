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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable

import android.provider.Settings
import android.widget.Toast

class TouchControlService : AccessibilityService(), NativeBridge.TouchListener {

    private val activeTouchPoints = mutableMapOf<Int, PointF>()
    @Volatile
    private var screenWidth: Int = 1080
    @Volatile
    private var screenHeight: Int = 2400

    companion object {
        private const val TAG = "TouchControlService"
        var instance: TouchControlService? = null
            private set

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expectedComponentName = "${context.packageName}/${TouchControlService::class.java.name}"
            val enabledServicesSetting = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedComponentName, ignoreCase = true) ||
                    componentName.contains(context.packageName, ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ScreenDimmerManager.init(applicationContext)
        updateScreenDimensions()
        NativeBridge.setTouchListener(this)
        AppLogger.i(TAG, "✓ TouchControlService 연결됨 (${screenWidth}x${screenHeight})")
    }

    private var dimOverlayView: View? = null

    override fun onDestroy() {
        super.onDestroy()
        dimOverlayView?.let { view ->
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (_: Exception) {}
            dimOverlayView = null
        }
        NativeBridge.setTouchListener(null)
        if (instance == this) {
            instance = null
        }
        AppLogger.i(TAG, "TouchControlService 종료됨")
    }

    /**
     * 스마트폰 물리 화면을 시스템 전역에서 100% OLED 순수 암전(Black) 및 최저 하드웨어 밝기(0.001f)로 전환합니다.
     * AccessibilityService 권한을 활용하여 앱뿐만 아니라 타 앱 실행 중이거나 가상화면 모드에서도 작동합니다.
     */
    fun setScreenDimmed(dimmed: Boolean) {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                if (dimmed) {
                    if (dimOverlayView != null) return@post

                    // 폰 화면 1:1 복제 미러링(단말 화면 미러링) 중일 때:
                    // 완전 블랙 오버레이를 올리면 MediaProjection이 블랙 오버레이를 캡처하여 테슬라 화면도 검게 꺼져버립니다.
                    // 따라서 폰 미러링 중일 때는 투명 오버레이에 FLAG_NOT_TOUCHABLE과 하드웨어 백라이트 최저치(0.001f)를 적용하여
                    // 물리 화면은 최저 밝기로 어둡게 하면서, 티맵 등 실행 중인 앱의 화면은 테슬라로 정상 송출합니다.
                    val isPhoneMirrorMode = MediaProjectionService.isRunning && !MediaProjectionService.isStandalone
                    if (isPhoneMirrorMode) {
                        AppLogger.i(TAG, "🌙 폰 미러링 모드(복제): 투명 오버레이를 통해 물리 백라이트 최저치(0.001f) 적용 (테슬라 화면 송출 100% 유지)")
                        val overlay = View(this).apply {
                            setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0))
                        }
                        val params = WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT
                        ).apply {
                            screenBrightness = 0.001f // 최저 하드웨어 백라이트 밝기
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            }
                        }
                        wm.addView(overlay, params)
                        dimOverlayView = overlay
                        AppLogger.i(TAG, "✓ 폰 미러링 모드(복제) 최저 밝기(0.001f) 투명 오버레이 활성화 완료")
                        return@post
                    }

                    AppLogger.i(TAG, "🌙 스마트폰 전신 초절전 암전(OLED Black Overlay) 생성")

                    val overlay = FrameLayout(this).apply {
                        setBackgroundColor(0xFF000000.toInt()) // 100% OLED 완전 암전 (화소 OFF)

                        val guideText = TextView(context).apply {
                            text = "🌙 mMirror 초절전 암전 모드\n(스마트폰 화면을 터치하면 화면이 복원됩니다)"
                            setTextColor(0x99FFFFFF.toInt())
                            textSize = 15f
                            gravity = Gravity.CENTER
                            setPadding(48, 24, 48, 24)
                            background = GradientDrawable().apply {
                                setColor(0x33FFFFFF.toInt())
                                cornerRadius = 32f
                            }
                        }
                        val lp = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                        addView(guideText, lp)

                        // 3초 후 안내 텍스트 페이드아웃 -> 완전한 100% 암전 유지
                        guideText.animate()
                            .alpha(0f)
                            .setDuration(1000)
                            .setStartDelay(2500)
                            .start()

                        // 스마트폰 화면을 탭하면 즉시 암전 해제 및 정상 화면 복원
                        setOnClickListener {
                            AppLogger.i(TAG, "스마트폰 화면 터치 감지 -> 초절전 암전 해제")
                            ScreenDimmerManager.setDimmed(false)
                        }
                    }

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        screenBrightness = 0.001f // 최저 하드웨어 백라이트 밝기
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }

                    wm.addView(overlay, params)
                    dimOverlayView = overlay
                    AppLogger.i(TAG, "✓ TYPE_ACCESSIBILITY_OVERLAY 암전 뷰 활성화 성공")
                } else {
                    dimOverlayView?.let { view ->
                        try {
                            val p = view.layoutParams as? WindowManager.LayoutParams
                            if (p != null) {
                                p.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                                wm.updateViewLayout(view, p)
                            }
                        } catch (_: Exception) {}
                        try {
                            wm.removeView(view)
                        } catch (_: Exception) {}
                        dimOverlayView = null
                        AppLogger.i(TAG, "✓ TYPE_ACCESSIBILITY_OVERLAY 암전 뷰 제거 완료 (화면 복원)")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to toggle screen dim overlay: ${e.message}", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility event processing if needed
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "⚠️ AccessibilityService interrupted")
    }

    fun getPhysicalScreenBounds(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                Pair(dm.widthPixels, dm.heightPixels)
            }
        } catch (_: Exception) {
            val dm = resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private var physicalWidth = 1080
    private var physicalHeight = 1920
    private var streamWidth = 1080
    private var streamHeight = 1920

    private fun updateScreenDimensions() {
        val (w, h) = getPhysicalScreenBounds()
        if (w > 0 && h > 0) {
            physicalWidth = w
            physicalHeight = h
            screenWidth = w
            screenHeight = h
        }
    }

    fun updateDimensions(physW: Int, physH: Int, streamW: Int = physW, streamH: Int = physH) {
        if (physW > 0 && physH > 0) {
            physicalWidth = physW
            physicalHeight = physH
            screenWidth = physW
            screenHeight = physH
        }
        if (streamW > 0 && streamH > 0) {
            streamWidth = streamW
            streamHeight = streamH
        }
        Log.i(TAG, "Touch dimensions updated: physical=${physicalWidth}x${physicalHeight}, stream=${streamWidth}x${streamHeight}")
    }

    private data class TouchSession(
        val startX: Float,
        val startY: Float,
        var lastX: Float,
        var lastY: Float,
        val startTime: Long,
        var isMoved: Boolean = false,
        var lastDispatchedX: Float = startX,
        var lastDispatchedY: Float = startY,
        var lastDispatchTime: Long = startTime,
        var dragDispatchedCount: Int = 0
    )

    private val activeTouchSessions = mutableMapOf<Int, TouchSession>()
    @Volatile
    private var isDispatchingDrag = false

    private fun dispatchDragStep(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        if (isDispatchingDrag) return
        try {
            isDispatchingDrag = true
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(20L, 60L))
            val builder = GestureDescription.Builder().apply {
                addStroke(stroke)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setDisplayId(android.view.Display.DEFAULT_DISPLAY)
                }
            }
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    isDispatchingDrag = false
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    isDispatchingDrag = false
                }
            }, null)
        } catch (_: Throwable) {
            isDispatchingDrag = false
        }
    }

    // --- NativeBridge.TouchListener 구현 ---

    override fun onTouch(action: String, id: Int, x: Float, y: Float) {
        // 실시간 물리 화면 해상도 동적 갱신 (폴드 열림/닫힘 및 화면 회전 100% 동기화)
        val (realW, realH) = getPhysicalScreenBounds()
        if (realW > 0 && realH > 0) {
            physicalWidth = realW
            physicalHeight = realH
            screenWidth = realW
            screenHeight = realH
        }

        // 스트림 해상도와 물리 해상도의 화면 비율(Aspect Ratio) 분석 및 필러박스/레터박스 보정
        val physW = physicalWidth.toFloat()
        val physH = physicalHeight.toFloat()
        val stW = (if (streamWidth > 0) streamWidth else physicalWidth).toFloat()
        val stH = (if (streamHeight > 0) streamHeight else physicalHeight).toFloat()

        val physAspect = physW / physH
        val streamAspect = stW / stH

        val mirroredW: Float
        val mirroredH: Float
        val offsetX: Float
        val offsetY: Float

        if (kotlin.math.abs(physAspect - streamAspect) < 0.01f) {
            // 비율이 동일한 경우 (예: 폰 비율 유지 스케일링 모드 또는 1:1 복제)
            mirroredW = stW
            mirroredH = stH
            offsetX = 0f
            offsetY = 0f
        } else if (physAspect < streamAspect) {
            // 폰이 더 좁고 길쭉함 (예: 세로형 폰을 1600x1120 등 와이드 가상화면에 송출)
            // 좌우에 필러박스(검은 여백) 형성, 상하 높이는 100% 꽉 참
            mirroredH = stH
            mirroredW = mirroredH * physAspect
            offsetX = (stW - mirroredW) / 2f
            offsetY = 0f
        } else {
            // 폰이 더 넓음 (예: 가로형 폴드를 세로/정사각형 가상화면에 송출)
            // 상하에 레터박스 형성, 좌우 폭은 100% 꽉 참
            mirroredW = stW
            mirroredH = mirroredW / physAspect
            offsetX = 0f
            offsetY = (stH - mirroredH) / 2f
        }

        // 테슬라 브라우저의 스트림 픽셀 좌표
        val streamPixelX = x * stW
        val streamPixelY = y * stH

        // 필러박스/레터박스 내부의 폰 렌더링 영역으로 역정규화 (0.0 ~ 1.0)
        val phoneNormX = ((streamPixelX - offsetX) / mirroredW).coerceIn(0f, 1f)
        val phoneNormY = ((streamPixelY - offsetY) / mirroredH).coerceIn(0f, 1f)

        // 스마트폰 물리 화면 픽셀 좌표
        val pixelX = phoneNormX * physW
        val pixelY = phoneNormY * physH

        // 터치 슬롭: 화면 최소 치수의 1.5% 또는 15~35px (초고감도 제스처 인식 및 진동 필터링 균형)
        val touchSlop = (minOf(physW, physH) * 0.015f).coerceIn(15f, 35f)

        when (action) {
            "down" -> {
                activeTouchSessions[id] = TouchSession(
                    startX = pixelX,
                    startY = pixelY,
                    lastX = pixelX,
                    lastY = pixelY,
                    startTime = System.currentTimeMillis(),
                    isMoved = false,
                    lastDispatchedX = pixelX,
                    lastDispatchedY = pixelY,
                    lastDispatchTime = System.currentTimeMillis(),
                    dragDispatchedCount = 0
                )
            }
            "move" -> {
                val session = activeTouchSessions[id] ?: return
                session.lastX = pixelX
                session.lastY = pixelY
                val distFromStart = kotlin.math.hypot(pixelX - session.startX, pixelY - session.startY)
                if (distFromStart > touchSlop) {
                    session.isMoved = true
                }

                if (session.isMoved) {
                    val now = System.currentTimeMillis()
                    val stepDist = kotlin.math.hypot(pixelX - session.lastDispatchedX, pixelY - session.lastDispatchedY)
                    // 드래그 중 30ms 간격 또는 12px 이상 이동 시 즉시 실시간 드래그 스텝 주입!
                    if (!isDispatchingDrag && stepDist >= 12f && (now - session.lastDispatchTime >= 30L)) {
                        dispatchDragStep(session.lastDispatchedX, session.lastDispatchedY, pixelX, pixelY, 30L)
                        session.lastDispatchedX = pixelX
                        session.lastDispatchedY = pixelY
                        session.lastDispatchTime = now
                        session.dragDispatchedCount++
                    }
                }
            }
            "up" -> {
                val session = activeTouchSessions.remove(id)
                isDispatchingDrag = false
                if (session != null) {
                    val duration = System.currentTimeMillis() - session.startTime
                    val distFromStart = kotlin.math.hypot(pixelX - session.startX, pixelY - session.startY)

                    if (!session.isMoved && distFromStart <= touchSlop) {
                        if (duration >= 600) {
                            // 롱 프레스 (Long Press) 주입
                            dispatchLongPress(session.startX, session.startY)
                        } else {
                            // 단발 탭 (Tap) 주입
                            dispatchTap(session.startX, session.startY)
                        }
                    } else if (session.dragDispatchedCount > 0) {
                        // 이미 실시간으로 드래그가 주입된 경우:
                        val elapsedSinceLast = System.currentTimeMillis() - session.lastDispatchTime
                        val finalDist = kotlin.math.hypot(pixelX - session.lastDispatchedX, pixelY - session.lastDispatchedY)
                        // 손가락을 빠르게 튕겼을(Fling) 때만 잔여 관성 스와이프 짧게 주입
                        if (elapsedSinceLast < 100L && finalDist > 20f) {
                            dispatchSwipe(session.lastDispatchedX, session.lastDispatchedY, pixelX, pixelY, 60L)
                        }
                    } else {
                        // 스와이프 / 드래그 / 스크롤 제스처 주입 (완결된 스트로크로 네이티브 관성 스크롤 발동)
                        val swipeDuration = duration.coerceIn(50L, 250L)
                        dispatchSwipe(session.startX, session.startY, pixelX, pixelY, swipeDuration)
                    }
                }
            }
            "cancel" -> {
                activeTouchSessions.remove(id)
                isDispatchingDrag = false
            }
        }
    }

    // 핀치 줌 (두 손가락 제스처) 주입
    fun onPinchZoom(direction: String, x: Float, y: Float) {
        val (realW, realH) = getPhysicalScreenBounds()
        if (realW > 0 && realH > 0) {
            screenWidth = realW
            screenHeight = realH
        }

        val centerX = (x * screenWidth).coerceIn(0f, screenWidth.toFloat())
        val centerY = (y * screenHeight).coerceIn(0f, screenHeight.toFloat())
        val span = (minOf(screenWidth, screenHeight) * 0.25f).coerceAtLeast(150f)

        val (startSpan, endSpan) = if (direction.equals("in", ignoreCase = true)) {
            // 확대 (줌 인): 안에서 밖으로 벌림
            Pair(span * 0.4f, span * 1.2f)
        } else {
            // 축소 (줌 아웃): 밖에서 안으로 모음
            Pair(span * 1.2f, span * 0.4f)
        }

        try {
            val path1 = Path().apply {
                moveTo(centerX - startSpan, centerY - startSpan)
                lineTo(centerX - endSpan, centerY - endSpan)
            }
            val path2 = Path().apply {
                moveTo(centerX + startSpan, centerY + startSpan)
                lineTo(centerX + endSpan, centerY + endSpan)
            }

            val stroke1 = GestureDescription.StrokeDescription(path1, 0, 100)
            val stroke2 = GestureDescription.StrokeDescription(path2, 0, 100)

            val builder = GestureDescription.Builder().apply {
                addStroke(stroke1)
                addStroke(stroke2)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setDisplayId(android.view.Display.DEFAULT_DISPLAY)
                }
            }
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "✓ 핀치줌($direction) 완료: ($centerX, $centerY)")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "⚠️ 핀치줌($direction) 취소됨")
                }
            }, null)
        } catch (t: Throwable) {
            Log.w(TAG, "onPinchZoom failed: ${t.message}")
        }
    }

    /**
     * 테슬라 대화면에서 입력된 텍스트를 현재 활성화된 포커스 입력창(EditText)에 즉시 주입
     */
    fun injectText(text: String): Boolean {
        try {
            val root = rootInActiveWindow ?: run {
                Log.w(TAG, "injectText failed: rootInActiveWindow is null")
                return false
            }
            // 1순위: 포커스된 입력 노드 검색
            var targetNode = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)

            // 2순위: FOCUS_INPUT이 없으면 트리 내 editable인 노드 탐색
            if (targetNode == null) {
                targetNode = findEditableNode(root)
            }

            if (targetNode != null) {
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                val success = targetNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.i(TAG, "✓ Injected text via ACTION_SET_TEXT to node '${targetNode.className}': success=$success, text='$text'")
                targetNode.recycle()
                return success
            } else {
                Log.w(TAG, "No editable/focused input node found in active window for text: $text")
            }
        } catch (e: Exception) {
            Log.w(TAG, "injectText error: ${e.message}")
        }
        return false
    }

    private fun findEditableNode(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
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

    // 단발 탭 주입 (접근성 서비스 dispatchGesture)
    private fun dispatchTap(x: Float, y: Float) {
        try {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y + 1f) // 1px 미세 선분으로 PathMeasure 길이 확보 (일부 ROM 0길이 cancel 방지)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 30)
            val builder = GestureDescription.Builder().apply {
                addStroke(stroke)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setDisplayId(android.view.Display.DEFAULT_DISPLAY)
                }
            }
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "✓ 탭 주입 성공: (${x.toInt()}, ${y.toInt()}) [디스플레이: ${screenWidth}x${screenHeight}]")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "⚠️ 탭 주입 취소됨: (${x.toInt()}, ${y.toInt()})")
                }
            }, null)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "dispatchTap failed: ${t.message}", t)
        }
    }

    // 롱 프레스 주입
    private fun dispatchLongPress(x: Float, y: Float) {
        try {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y + 1f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 700)
            val builder = GestureDescription.Builder().apply {
                addStroke(stroke)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setDisplayId(android.view.Display.DEFAULT_DISPLAY)
                }
            }
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "✓ 롱프레스 주입 성공: (${x.toInt()}, ${y.toInt()})")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "⚠️ 롱프레스 주입 취소됨")
                }
            }, null)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "dispatchLongPress failed: ${t.message}", t)
        }
    }

    // 스와이프/드래그 주입 (접근성 서비스 dispatchGesture)
    private fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50L, 500L))
            val builder = GestureDescription.Builder().apply {
                addStroke(stroke)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setDisplayId(android.view.Display.DEFAULT_DISPLAY)
                }
            }
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "✓ 스와이프 주입 성공: (${startX.toInt()}, ${startY.toInt()}) -> (${endX.toInt()}, ${endY.toInt()}) [${durationMs}ms]")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "⚠️ 스와이프 주입 취소됨")
                }
            }, null)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "dispatchSwipe failed: ${t.message}", t)
        }
    }
}
