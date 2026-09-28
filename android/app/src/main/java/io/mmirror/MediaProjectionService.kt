package io.mmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString

class MediaProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var audioCaptureService: AudioCaptureService? = null
    private var isStreaming = false
    private var encodingThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var drivingLogManager: DrivingLogManager? = null

    private var spsPpsBuffer: ByteArray? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320

    private var relayWebSocket: WebSocket? = null
    @Volatile
    private var isRelayConnected = false
    private val okHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    fun sendRelayAudio(data: ByteArray, length: Int) {
        if (!enableRemoteRelay) return
        val ws = relayWebSocket ?: return
        if (!isRelayConnected) return
        if (ws.queueSize() > 128 * 1024L) return

        val packet = ByteArray(1 + length)
        packet[0] = 0x02 // PKT_TYPE_AUDIO
        System.arraycopy(data, 0, packet, 1, length)
        ws.send(packet.toByteString())
    }

    private fun sendRelayVideo(data: ByteArray, isKeyFrame: Boolean) {
        if (!enableRemoteRelay) return
        val ws = relayWebSocket ?: return
        if (!isRelayConnected) return
        // 큐가 256KB 넘으면 키프레임 외에는 드롭하여 지연시간 50ms 미만 유지
        if (!isKeyFrame && ws.queueSize() > 256 * 1024L) return

        val packet = ByteArray(1 + data.size)
        packet[0] = 0x01 // PKT_TYPE_VIDEO
        System.arraycopy(data, 0, packet, 1, data.size)
        ws.send(packet.toByteString())
    }

    private fun connectRelayWebSocket() {
        if (!enableRemoteRelay || !isStreaming) return
        try {
            val request = Request.Builder()
                .url("wss://mdm.mplat.store:8088/publish")
                .build()

            relayWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "🟢 Connected to mplat Relay Server (wss://mdm.mplat.store:8088/publish)")
                    isRelayConnected = true
                    sendRelayConfig()
                    spsPpsBuffer?.let { sps ->
                        val pkt = ByteArray(1 + sps.size)
                        pkt[0] = 0x01
                        System.arraycopy(sps, 0, pkt, 1, sps.size)
                        webSocket.send(pkt.toByteString())
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleRelayControlMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Relay WebSocket error: ${t.message}")
                    isRelayConnected = false
                    if (isStreaming) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (isStreaming && !isRelayConnected) {
                                connectRelayWebSocket()
                            }
                        }, 2000)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Relay WebSocket closed: $reason")
                    isRelayConnected = false
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to relay: ${e.message}", e)
        }
    }

    private fun sendRelayConfig() {
        try {
            val json = org.json.JSONObject().apply {
                put("width", screenWidth)
                put("height", screenHeight)
                put("rotation", 0)
                put("fps", 60)
            }.toString()
            val bytes = json.toByteArray(Charsets.UTF_8)
            val pkt = ByteArray(1 + bytes.size)
            pkt[0] = 0x03 // PKT_TYPE_CONFIG
            System.arraycopy(bytes, 0, pkt, 1, bytes.size)
            relayWebSocket?.send(pkt.toByteString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send relay config: ${e.message}")
        }
    }

    private fun handleRelayControlMessage(text: String) {
        try {
            val json = org.json.JSONObject(text)
            val type = json.optString("type")
            when (type) {
                "touch" -> {
                    val action = json.optString("action", "down")
                    val id = json.optInt("id", 0)
                    val x = json.optDouble("x", 0.0).toFloat()
                    val y = json.optDouble("y", 0.0).toFloat()
                    TouchControlService.instance?.onTouch(action, id, x, y)
                }
                "key" -> {
                    val key = json.optString("key", "")
                    TouchControlService.instance?.onKey(key)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse control message: $text", e)
        }
    }

    companion object {
        private const val TAG = "MediaProjectionService"
        private const val NOTIFICATION_CHANNEL_ID = "mmirror_stream_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "io.mmirror.action.START"
        const val ACTION_STOP = "io.mmirror.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PORT = "port"

        var isRunning = false
            private set

        var instance: MediaProjectionService? = null
            private set

        var virtualDisplayId: Int = -1
            private set

        @Volatile
        var enableRemoteRelay: Boolean = false
    }

    /**
     * 가상 디스플레이에 앱을 실행합니다.
     * 테슬라에 표시될 독립적인 가상 화면에서 앱이 동작합니다.
     * 사용자는 폰 메인 화면을 자유롭게 사용할 수 있습니다.
     */
    fun launchAppOnVirtualDisplay(intent: Intent): Boolean {
        val displayId = virtualDisplayId
        if (displayId < 0) {
            Log.e(TAG, "Virtual display not created yet (displayId=$displayId)")
            return false
        }
        return try {
            val options = android.app.ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                options.launchDisplayId = displayId
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            applicationContext.startActivity(intent, options.toBundle())
            Log.i(TAG, "App launched on virtual display successfully (displayId=$displayId)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: OS blocked launching third-party app on virtual display $displayId without desktop mode permission: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app on virtual display: ${e.message}", e)
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    fun toggleOrientation() {
        val temp = screenWidth
        screenWidth = screenHeight
        screenHeight = temp

        Log.i(TAG, "Toggling orientation to: ${screenWidth}x${screenHeight}")
        virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)
        NativeBridge.updateConfig(screenWidth, screenHeight, 0, 60)
        TouchControlService.instance?.updateDimensions(screenWidth, screenHeight)
        requestSyncFrame()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val port = intent.getIntExtra(EXTRA_PORT, 8080)

                if (resultData != null) {
                    try {
                        startForegroundServiceWithNotification()
                        startMirroring(resultCode, resultData, port)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start mirroring service", e)
                        stopMirroring()
                        stopSelf()
                    }
                } else {
                    Log.e(TAG, "EXTRA_RESULT_DATA is null!")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopMirroring()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 사용자가 최근 앱에서 스와이프하여 앱을 제거해도 미러링 서비스 유지
        Log.i(TAG, "onTaskRemoved: 앱이 최근 목록에서 제거됨 — 미러링 서비스 계속 유지")
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "mMirror Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "테슬라 차량으로 화면 및 오디오를 스트리밍 중입니다."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("mMirror 테슬라 미러링 중")
            .setContentText("테슬라 브라우저로 화면 및 오디오를 송출하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            try {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start FGS with microphone type, fallback to mediaProjection only", e)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
    }

    private fun startMirroring(resultCode: Int, resultData: Intent, port: Int) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null!")
                stopMirroring()
                return
            }

            // Android 14 필수 요구사항: createVirtualDisplay 전에 반드시 MediaProjection.Callback 등록
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection이 시스템에 의해 중지되었습니다.")
                    stopMirroring()
                }
            }, null)

            // 해상도 계산 (폴드 열림/닫힘 및 화면 비율 자동 적응)
            val (w, h, density) = computeScreenDimensions()
            screenWidth = w
            screenHeight = h
            screenDensity = density

            Log.i(TAG, "Streaming resolution: ${screenWidth}x${screenHeight}, density: $screenDensity")
            TouchControlService.instance?.updateDimensions(screenWidth, screenHeight)

            // 0. CPU WakeLock 획득 및 화면/폴드 변경 리스너 등록
            acquireWakeLock()
            registerScreenStateReceiver()
            registerDisplayListener()

            // 1. Rust HTTP & WebSocket 서버 시작 (로컬용)
            NativeBridge.startServer(port)
            NativeBridge.updateConfig(screenWidth, screenHeight, 0, 60)

            // 2. 비디오 하드웨어 인코더 설정
            setupVideoEncoder()

            // 2-1. 외부 릴레이 (기본 비활성화: 모바일 데이터 0MB & 로컬 초저지연 보장)
            if (enableRemoteRelay) {
                connectRelayWebSocket()
            } else {
                Log.i(TAG, "⚡ 로컬 가상 프록시 모드: 외부 릴레이 업로드 비활성화 (데이터 소모 0MB)")
            }

            // 3. 오디오 캡처 서비스 시작 (권한 있는 경우만 안전하게 시작)
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                mediaProjection?.let { mp ->
                    audioCaptureService = AudioCaptureService(mp)
                    audioCaptureService?.start()
                }
            } else {
                Log.i(TAG, "RECORD_AUDIO 권한이 없어 오디오 스트리밍을 건너뜁니다.")
            }

            // 4. GPS 주행일지 추적 시작 (권한 있는 경우만 안전하게 시작)
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                drivingLogManager = DrivingLogManager(this)
                drivingLogManager?.startTrip()
            } else {
                Log.i(TAG, "ACCESS_FINE_LOCATION 권한이 없어 GPS 추적을 건너뜁니다.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting mirroring", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(applicationContext, "미러링 시작 오류: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            stopMirroring()
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "mMirror:VirtualDisplayWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24시간 안전 타임아웃
            }
            Log.i(TAG, "CPU WakeLock acquired successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun registerScreenStateReceiver() {
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.i(TAG, "스마트폰 물리 전원 버튼으로 화면 꺼짐 감지 -> 가상 디스플레이 스트림 유지")
                        requestSyncFrame()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.i(TAG, "스마트폰 화면 켜짐 감지")
                        requestSyncFrame()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    private fun requestSyncFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Sync frame request failed", e)
        }
    }

    private fun setupVideoEncoder() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000) // 4 Mbps (테슬라 브라우저 권장 대역폭)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30) // 30 FPS 안정적 송출
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1초마다 I-프레임
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                } catch (_: Exception) {}
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = mediaCodec?.createInputSurface()

            // 가상 디스플레이 생성 (표준 AUTO_MIRROR로 고화질 초저지연 송출 보장)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "mMirror-VirtualDisplay",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
            virtualDisplayId = virtualDisplay?.display?.displayId ?: -1
            Log.i(TAG, "Virtual display created successfully with AUTO_MIRROR mode (displayId=$virtualDisplayId)")

            mediaCodec?.start()
            isStreaming = true
            startEncodingLoop()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup video encoder", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(applicationContext, "인코더 시작 실패: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            stopMirroring()
        }
    }

    private fun startEncodingLoop() {
        encodingThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            Log.i(TAG, "H.264 Encoder loop started (${screenWidth}x${screenHeight})")

            while (isStreaming) {
                val outputBufferIndex = try {
                    mediaCodec?.dequeueOutputBuffer(bufferInfo, 10_000) ?: -1
                } catch (e: Exception) {
                    if (isStreaming) Log.w(TAG, "Error dequeuing output buffer", e)
                    -1
                }

                if (outputBufferIndex >= 0) {
                    val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val byteArray = ByteArray(bufferInfo.size)
                        outputBuffer.get(byteArray)

                        val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isCodecConfig) {
                            spsPpsBuffer = byteArray.clone()
                            NativeBridge.sendVideoFrame(byteArray, 0, bufferInfo.size)
                            sendRelayVideo(byteArray, false)
                        } else if (isKeyFrame && spsPpsBuffer != null) {
                            // 키프레임(IDR) 앞단에 SPS/PPS를 항상 병합하여 전송
                            val combined = ByteArray(spsPpsBuffer!!.size + byteArray.size)
                            System.arraycopy(spsPpsBuffer!!, 0, combined, 0, spsPpsBuffer!!.size)
                            System.arraycopy(byteArray, 0, combined, spsPpsBuffer!!.size, byteArray.size)
                            NativeBridge.sendVideoFrame(combined, 0, combined.size)
                            sendRelayVideo(combined, true)
                        } else {
                            NativeBridge.sendVideoFrame(byteArray, 0, bufferInfo.size)
                            sendRelayVideo(byteArray, false)
                        }
                    }
                    try {
                        mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                    } catch (_: Exception) {}
                }
            }
            Log.i(TAG, "H.264 Encoder loop stopped")
        }, "mMirror-VideoEncoderThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private var displayListener: DisplayManager.DisplayListener? = null

    private fun computeScreenDimensions(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        var w = (metrics.widthPixels / 16) * 16
        var h = (metrics.heightPixels / 16) * 16
        val maxDim = 1280
        if (w > maxDim || h > maxDim) {
            val scale = maxDim.toFloat() / maxOf(w, h)
            w = ((w * scale).toInt() / 16) * 16
            h = ((h * scale).toInt() / 16) * 16
        }
        return Triple(maxOf(320, w), maxOf(320, h), metrics.densityDpi)
    }

    private fun registerDisplayListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                    checkAndApplyDisplayChanges()
                }
            }
        }
        dm.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    private fun unregisterDisplayListener() {
        try {
            displayListener?.let {
                val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dm.unregisterDisplayListener(it)
            }
        } catch (_: Exception) {}
        displayListener = null
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "onConfigurationChanged 감지 (폴드 열림/닫힘/화면회전) -> 해상도 자동 동기화")
        checkAndApplyDisplayChanges()
    }

    @Synchronized
    private fun checkAndApplyDisplayChanges() {
        if (!isStreaming || mediaProjection == null) return

        val (newW, newH, newDensity) = computeScreenDimensions()
        if (newW == screenWidth && newH == screenHeight) {
            return
        }

        Log.i(TAG, "🔄 폴드/화면 전환 감지: ${screenWidth}x${screenHeight} -> ${newW}x${newH} (밀도: $newDensity)")
        screenWidth = newW
        screenHeight = newH
        screenDensity = newDensity

        TouchControlService.instance?.updateDimensions(screenWidth, screenHeight)
        NativeBridge.updateConfig(screenWidth, screenHeight, 0, 60)
        sendRelayConfig()

        restartVideoEncoder()
    }

    private fun restartVideoEncoder() {
        try {
            isStreaming = false
            encodingThread?.interrupt()
            encodingThread = null

            try {
                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (_: Exception) {}

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                } catch (_: Exception) {}
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = mediaCodec?.createInputSurface()

            if (virtualDisplay != null && surface != null) {
                virtualDisplay?.setSurface(surface)
                virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)
            } else {
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "mMirror-VirtualDisplay",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
            }
            virtualDisplayId = virtualDisplay?.display?.displayId ?: -1

            mediaCodec?.start()
            isStreaming = true
            startEncodingLoop()
            requestSyncFrame()

            Log.i(TAG, "✅ 폴드 화면 전환 완료: ${screenWidth}x${screenHeight} 실시간 재설정됨")
        } catch (e: Exception) {
            Log.e(TAG, "비디오 인코더 재설정 실패: ${e.message}", e)
        }
    }

    private fun stopMirroring() {
        isStreaming = false
        isRunning = false

        try {
            if (screenStateReceiver != null) {
                unregisterReceiver(screenStateReceiver)
                screenStateReceiver = null
            }
        } catch (_: Exception) {}

        unregisterDisplayListener()

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {}

        try {
            relayWebSocket?.close(1000, "User stopped mirroring")
        } catch (_: Exception) {}
        relayWebSocket = null
        isRelayConnected = false

        audioCaptureService?.stop()
        audioCaptureService = null

        encodingThread?.interrupt()
        encodingThread = null

        try {
            virtualDisplayId = -1
            virtualDisplay?.release()
            virtualDisplay = null

            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null

            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up projection", e)
        }

        // GPS 주행일지 추적 종료 및 기록 저장
        drivingLogManager?.stopTrip()
        drivingLogManager = null

        // 웹서버는 테슬라 브라우저 연결(ERR_CONNECTION_REFUSED 방지)을 위해 상시 유지
        Log.i(TAG, "mMirror screen capture stopped (Web server maintained)")
    }

    override fun onDestroy() {
        stopMirroring()
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }
}
