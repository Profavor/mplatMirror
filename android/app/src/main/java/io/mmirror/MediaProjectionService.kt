package io.mmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private var isStreaming = false
    private var encodingThread: Thread? = null

    private var wakeLockManager: MirrorWakeLockManager? = null
    private var drivingLogManager: DrivingLogManager? = null

    private var spsPpsBuffer: ByteArray? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320
    private var rawScreenWidth = 1080
    private var rawScreenHeight = 2400

    private var relayWebSocket: WebSocket? = null
    @Volatile
    private var isRelayConnected = false
    private var webRtcStreamer: io.mmirror.webrtc.WebRtcStreamer? = null
    private val okHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()
    private var isStandaloneModeRequested = false
    private var isAutoMirrorUnsupported = false
    private var resolutionPreset: ResolutionPreset = ResolutionPreset.DEFAULT
    private var carExitRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioCaptureService: AudioCaptureService? = null
    @Volatile
    private var isAudioStreamingEnabled = false

    fun setAudioStreamingEnabled(enabled: Boolean) {
        val prefs = getSharedPreferences("mmirror_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("pref_audio_stream_enabled", enabled).apply()
        if (isAudioStreamingEnabled == enabled) {
            if (enabled && audioCaptureService == null) {
                startAudioCapture()
            }
            webRtcStreamer?.sendAudioModeStatus()
            return
        }
        isAudioStreamingEnabled = enabled
        AppLogger.i(TAG, "🔊 Audio streaming mode changed: enabled=$enabled")
        if (enabled) {
            startAudioCapture()
        } else {
            stopAudioCapture()
        }
        webRtcStreamer?.sendAudioModeStatus()
    }

    fun isAudioStreamingEnabled(): Boolean = isAudioStreamingEnabled

    fun sendWebRtcAudio(data: ByteArray, length: Int) {
        if (!isAudioStreamingEnabled) return
        webRtcStreamer?.sendAudio(data, length)
    }

    private fun startAudioCapture() {
        val mp = mediaProjection ?: run {
            AppLogger.w(TAG, "⚠️ Cannot start audio capture: mediaProjection is null")
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AppLogger.w(TAG, "⚠️ RECORD_AUDIO 권한이 없어 오디오 스트리밍을 시작할 수 없습니다.")
            return
        }
        try {
            if (audioCaptureService == null) {
                audioCaptureService = AudioCaptureService(mp)
            }
            audioCaptureService?.start()
            AppLogger.i(TAG, "🔊 Audio capture started successfully")
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Failed to start audio capture: ${e.message}", e)
        }
    }

    private fun stopAudioCapture() {
        try {
            audioCaptureService?.stop()
            audioCaptureService = null
            AppLogger.i(TAG, "🔊 Audio capture stopped")
        } catch (e: Exception) {
            AppLogger.w(TAG, "⚠️ Error stopping audio capture: ${e.message}")
        }
    }

    private fun handleBluetoothDisconnectedOnExit() {
        carExitRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (isAudioStreamingEnabled) {
                AppLogger.i(TAG, "🔊 [차량 하차 감지 무시] 웹 사운드 스트리밍 모드 활성화 중이므로 블루투스 해제 자동 종료 건너뜀")
                return@Runnable
            }
            val isWebRtcConnected = webRtcStreamer?.isPeerConnected() == true
            if (!isWebRtcConnected && isStreaming) {
                AppLogger.i(TAG, "🚗 [차량 하차 감지] 테슬라 브라우저 연결 단절 확인 -> 스마트폰 배터리 보호를 위해 미러링 자동 종료")
                android.widget.Toast.makeText(
                    applicationContext,
                    "🚗 차량 하차가 감지되어 미러링을 자동 종료했습니다 (배터리 보호)",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                stopMirroring()
                stopSelf()
            }
        }
        carExitRunnable = r
        AppLogger.w(TAG, "🚗 차량 블루투스 해제 감지 -> 90초간 테슬라 브라우저 연결 유지 여부 감시")
        mainHandler.postDelayed(r, 90_000L)
    }

    private fun sendRelayVideo(data: ByteArray, isKeyFrame: Boolean) {
        if (!enableRemoteRelay) return
        val ws = relayWebSocket ?: return
        if (!isRelayConnected) return
        // 큐가 48KB 넘으면 키프레임 외에는 드롭하여 지연시간 40ms 미만 유지
        if (!isKeyFrame && ws.queueSize() > 48 * 1024L) return

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
                    // 스트리밍 중이면 자동 재연결
                    if (isStreaming) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (isStreaming && !isRelayConnected) {
                                Log.i(TAG, "🔄 Relay auto-reconnect after onClosed...")
                                connectRelayWebSocket()
                            }
                        }, 2000)
                    }
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
                "keyframe", "request_keyframe" -> {
                    Log.i(TAG, "🔑 Sync frame requested by viewer/relay, generating IDR...")
                    requestSyncFrame()
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
        const val ACTION_SET_STANDALONE = "io.mmirror.action.SET_STANDALONE"
        const val ACTION_TOGGLE_DIM = "io.mmirror.action.TOGGLE_DIM"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PORT = "port"
        const val EXTRA_STANDALONE = "is_standalone"
        const val EXTRA_RESOLUTION_PRESET = "extra_resolution_preset"
        const val EXTRA_AUTO_MIRROR_UNSUPPORTED = "extra_auto_mirror_unsupported"

        var isRunning = false
            private set

        @Volatile
        var isStandalone: Boolean = false
            private set

        var instance: MediaProjectionService? = null
            private set

        var virtualDisplayId: Int = -1
            private set

        @Volatile
        var enableRemoteRelay: Boolean = false // 0MB 모바일 데이터 원칙: 외부 릴레이 완전 차단 (기본값 false)
    }

    fun getMediaProjection(): MediaProjection? = mediaProjection

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
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
        ScreenDimmerManager.init(applicationContext)
        createNotificationChannel()
    }

    fun toggleOrientation() {
        val temp = screenWidth
        screenWidth = screenHeight
        screenHeight = temp

        val tempRaw = rawScreenWidth
        rawScreenWidth = rawScreenHeight
        rawScreenHeight = tempRaw

        Log.i(TAG, "Toggling orientation to: ${screenWidth}x${screenHeight} (raw: ${rawScreenWidth}x${rawScreenHeight})")
        TouchControlService.instance?.updateDimensions(rawScreenWidth, rawScreenHeight)
        NativeBridge.updateConfig(screenWidth, screenHeight, 0, 60)
        sendRelayConfig()
        restartVideoEncoder()
    }

    private fun isWifiApEnabled(): Boolean = NetworkUtils.isWifiApEnabled(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isWifiApEnabled()) {
                    AppLogger.w(TAG, "⛔ 모바일 핫스팟이 비활성화되어 있어 미러링 시작을 차단합니다 (0MB 원칙 준수)")
                    stopSelf()
                    return START_NOT_STICKY
                }
                AppLogger.clear()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val port = intent.getIntExtra(EXTRA_PORT, 8282)
                isStandaloneModeRequested = intent.getBooleanExtra(EXTRA_STANDALONE, true)
                isStandalone = isStandaloneModeRequested
                isAutoMirrorUnsupported = intent.getBooleanExtra(EXTRA_AUTO_MIRROR_UNSUPPORTED, true)
                val presetId = intent.getStringExtra(EXTRA_RESOLUTION_PRESET)
                resolutionPreset = ResolutionPreset.fromId(presetId)
                val effectiveData = resultData ?: Intent()

                try {
                    startForegroundServiceWithNotification()
                    AppLogger.i(TAG, "🚀 미러링 서비스 시작 요청 (port=$port, standalone=$isStandaloneModeRequested, preset=$presetId)")
                    startMirroring(resultCode, effectiveData, port)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "❌ Failed to start mirroring service", e)
                    stopMirroring()
                    stopSelf()
                }
            }
            ACTION_SET_STANDALONE -> {
                val isStandaloneMode = intent.getBooleanExtra(EXTRA_STANDALONE, true)
                Log.i(TAG, "ACTION_SET_STANDALONE received: $isStandaloneMode")
                isStandaloneModeRequested = isStandaloneMode
                isStandalone = isStandaloneMode
                webRtcStreamer?.setStandaloneMode(isStandaloneMode)
                startForegroundServiceWithNotification()
            }
            ACTION_TOGGLE_DIM -> {
                ScreenDimmerManager.toggleDim()
                startForegroundServiceWithNotification()
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
                description = "테슬라 차량으로 0MB 화면을 스트리밍 중입니다 (오디오는 차량 블루투스 직결)."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceWithNotification() {
        val title = "mplat Mirror 테슬라 미러링 중 (0MB P2P)"
        val desc = "⚠️ 폰 전원(화면 끄기) 버튼 금지 · 테슬라의 [절전] 버튼을 이용하세요"

        val stopIntent = Intent(this, MediaProjectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 101, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isDimmed = ScreenDimmerManager.isDimmed
        val dimText = if (isDimmed) "☀️ 절전 해제" else "🌙 초절전 암전"
        val dimIntent = Intent(this, MediaProjectionService::class.java).apply {
            action = ACTION_TOGGLE_DIM
        }
        val dimPendingIntent = PendingIntent.getService(
            this, 102, dimIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(desc)
            .setStyle(NotificationCompat.BigTextStyle().bigText("테슬라 브라우저로 0MB 로컬 P2P 화면 송출 중입니다.\n⚠️ 스마트폰의 물리 전원(화면 끄기) 버튼을 누르면 안드로이드 보안 정책으로 미러링이 즉시 종료됩니다. 화면을 어둡게 하려면 테슬라 화면의 [절전] 버튼이나 아래 [암전] 버튼을 이용하세요."))
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "🛑 미러링 종료", stopPendingIntent)
            .addAction(android.R.drawable.ic_lock_power_off, dimText, dimPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
    }

    private fun startMirroring(resultCode: Int, resultData: Intent, port: Int) {
        try {
            val (w, h, density) = computeScreenDimensions()
            screenWidth = w
            screenHeight = h
            screenDensity = density

            Log.i(TAG, "Streaming resolution: ${screenWidth}x${screenHeight}, density: $screenDensity (raw: ${rawScreenWidth}x${rawScreenHeight}, preset: ${resolutionPreset.id})")
            TouchControlService.instance?.updateDimensions(rawScreenWidth, rawScreenHeight, screenWidth, screenHeight)

            // 0. 전원 및 WakeLock 관리자 가동 및 디스플레이 변경 리스너 등록
            wakeLockManager = MirrorWakeLockManager(
                context = this,
                onScreenOff = { requestSyncFrame() },
                onScreenOn = {
                    scheduleDisplayChangeCheck()
                    requestSyncFrame()
                },
                onUserPresent = {
                    scheduleDisplayChangeCheck()
                    requestSyncFrame()
                },
                onHotspotDisabled = {
                    stopMirroring()
                    stopSelf()
                },
                onBluetoothDisconnected = {
                    handleBluetoothDisconnectedOnExit()
                }
            ).apply { acquireLocks() }
            registerDisplayListener()

            // 1. Rust HTTP & WebSocket 서버 시작 (로컬/대체용)
            NativeBridge.startServer(port)
            NativeBridge.updateConfig(screenWidth, screenHeight, 0, 60)

            // 1-1. MediaProjection 사전 생성 및 Callback 등록 (Android 14+ 필수: createVirtualDisplay 전 등록 필수)
            if (mediaProjection == null) {
                try {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            AppLogger.w(TAG, "⚠️ MediaProjection이 중지되었습니다 (물리 전원 버튼 또는 시스템 정책)")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(
                                    applicationContext,
                                    "⚠️ 화면 송출이 중단되었습니다.\n(물리 전원 버튼을 누르면 안드로이드 보안 정책으로 중단됩니다. 절전 시 테슬라 화면의 [절전] 버튼을 이용하세요)",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            stopMirroring()
                            stopSelf()
                        }

                        override fun onCapturedContentResize(width: Int, height: Int) {
                            Log.i(TAG, "MediaProjection onCapturedContentResize: ${width}x${height}")
                            scheduleDisplayChangeCheck()
                        }

                        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                            Log.i(TAG, "MediaProjection onCapturedContentVisibilityChanged: isVisible=$isVisible")
                            if (isVisible) {
                                scheduleDisplayChangeCheck()
                            }
                        }
                    }, Handler(Looper.getMainLooper()))
                    Log.i(TAG, "✓ MediaProjection created and callback registered in MediaProjectionService")
                } catch (e: Exception) {
                    Log.w(TAG, "MediaProjection init in service warning: ${e.message}")
                }
            }

            if (mediaProjection == null) {
                AppLogger.e(TAG, "❌ MediaProjection is null!")
                stopMirroring()
                return
            }

            // 1-2. MediaCodec 하드웨어 인코더 시작 (WebRTC DataChannel 및 Rust 웹소켓 공용 H.264 소스)
            setupVideoEncoder()

            // 2. WebRTC 로컬 P2P 스트리머 시작 (테슬라 브라우저 0MB 초저지연 표준 - 순수 DataChannel 모드)
            try {
                val streamer = io.mmirror.webrtc.WebRtcStreamer(
                    context = applicationContext,
                    resultData = null, // 단일 MediaProjection 보장 (Android 14+ 1회용 토큰 재사용 차단)
                    mediaProjection = null, // MediaCodec 단일 인코더 사용 -> DataChannel로 프레임 직접 전송
                    width = screenWidth,
                    height = screenHeight,
                    density = screenDensity,
                    fps = 60,
                    isStandalone = isStandaloneModeRequested,
                    isAutoMirrorUnsupported = isAutoMirrorUnsupported
                )
                val prefs = getSharedPreferences("mmirror_prefs", Context.MODE_PRIVATE)
                streamer.isAbrEnabled = prefs.getBoolean("pref_adaptive_bitrate", true)
                streamer.start()
                webRtcStreamer = streamer
                AppLogger.i(TAG, "✓ WebRtcStreamer started successfully in DataChannel mode (virtualDisplayId=$virtualDisplayId, isStandalone=$isStandaloneModeRequested, abr=${streamer.isAbrEnabled})")
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ WebRtcStreamer initialization error: ${e.message}", e)
            }

            if (enableRemoteRelay) {
                connectRelayWebSocket()
            }

            // 3. 오디오: 차량 블루투스(A2DP) 고음질 직결 + 웹 브라우저 오디오 하이브리드 지원
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                try {
                    // [작업 지시서 3] AudioManager 모드가 MODE_NORMAL로 유지되는지, 블루투스 SCO가 시작되지 않는지 확인
                    @Suppress("DEPRECATION")
                    if (audioManager.isBluetoothScoOn) {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            audioManager.clearCommunicationDevice()
                        } catch (_: Exception) {}
                    }
                    if (audioManager.mode != AudioManager.MODE_NORMAL) {
                        audioManager.mode = AudioManager.MODE_NORMAL
                    }
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val currVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    @Suppress("DEPRECATION")
                    Log.i(TAG, "🔊 Audio Volume (STREAM_MUSIC): $currVol / $maxVol, Mode: ${audioManager.mode} (MODE_NORMAL=${AudioManager.MODE_NORMAL}), SCO: ${audioManager.isBluetoothScoOn}")
                    if (currVol <= 0 && maxVol > 0) {
                        val targetVol = (maxVol * 0.85).toInt().coerceAtLeast(1)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                        Log.i(TAG, "✓ Unmuted Bluetooth STREAM_MUSIC volume to $targetVol / $maxVol")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Bluetooth volume adjust warning: ${e.message}")
                }
            }

            // 3. 오디오 전송 모드 확인 및 오디오 캡처 서비스 초기화
            val prefs = getSharedPreferences("mmirror_prefs", Context.MODE_PRIVATE)
            isAudioStreamingEnabled = prefs.getBoolean("pref_audio_stream_enabled", false)
            if (isAudioStreamingEnabled) {
                AppLogger.i(TAG, "🔊 웹 브라우저 직접 사운드 송출 모드 활성화됨 -> AudioCaptureService 기동")
                startAudioCapture()
            } else {
                AppLogger.i(TAG, "✓ Vehicle Bluetooth audio priority: all audio routes via phone Bluetooth A2DP directly (Audio capture disabled)")
            }

            // 4. GPS 주행일지 추적 시작
            val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                drivingLogManager = DrivingLogManager(this)
                drivingLogManager?.startTrip()
            } else {
                Log.i(TAG, "위치 권한이 없어 GPS 추적을 건너뜁니다.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ Error starting mirroring: ${e.message}", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(applicationContext, "미러링 시작 오류: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            stopMirroring()
        }
    }

    @Volatile var isAdaptiveBitrateEnabled: Boolean = true
        private set

    fun setAdaptiveBitrateEnabled(enabled: Boolean) {
        isAdaptiveBitrateEnabled = enabled
        webRtcStreamer?.setAdaptiveBitrateEnabled(enabled)
        if (!enabled) {
            updateMediaCodecBitrate(3_200_000)
        }
        Log.i(TAG, "⚡ Adaptive Bitrate set to: $enabled")
    }

    fun updateMediaCodecBitrate(bitrateBps: Int) {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps)
            }
            mediaCodec?.setParameters(params)
            Log.i(TAG, "Dynamic MediaCodec bitrate updated to ${bitrateBps / 1000} kbps")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update MediaCodec bitrate: ${e.message}")
        }
    }

    fun updateStayAwake() {
        wakeLockManager?.updateStayAwake()
    }

    @Volatile
    private var lastSyncFrameTime = 0L

    @Volatile
    private var lastIFrameBuffer: ByteArray? = null

    @Volatile
    private var lastFrameProducedTime = 0L

    fun requestKeyFrame(immediateCached: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSyncFrameTime < 1000L) return // 1초 쿨타임으로 폭주 방지
        if (immediateCached) {
            val lastIFrame = lastIFrameBuffer
            if (lastIFrame != null && (now - lastFrameProducedTime > 1000L)) {
                webRtcStreamer?.sendVideoPacket(lastIFrame, true)
                Log.d(TAG, "🔑 Cached I-frame sent on cold start (${lastIFrame.size} bytes)")
            }
        }
        requestSyncFrame()
    }

    private fun requestSyncFrame() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSyncFrameTime < 1000L) return // 1000ms 쿨타임으로 폭주 방지
        lastSyncFrameTime = now
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
            Log.d(TAG, "🔑 On-demand sync frame (I-frame) requested to MediaCodec")
        } catch (e: Exception) {
            Log.w(TAG, "Sync frame request failed", e)
        }
    }

    private fun setupVideoEncoder() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_200_000) // 3.2 Mbps (720p 60 FPS 최적 화질)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60) // 60 FPS 부드러운 초고속 송출
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1초마다 I-프레임 (참조 프레임 단절 방지 및 초고속 자가치유)
                // Baseline Profile: B-프레임 100% 제거 -> 인코더/디코더 버퍼링 0ms
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                // CBR(고정 비트레이트) 모드 우선 적용 -> 빠른 화면 전환 시 급격한 대역폭 버스트 및 Wi-Fi 지연 누적 원천 차단
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                } catch (_: Exception) {
                    try {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    } catch (_: Exception) {}
                }
                // 정적 화면 시 프레임 단절 방지: 100ms(10 FPS)마다 이전 프레임 자동 반복 송출
                try {
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                } catch (_: Exception) {}
                // Android R+ 초저지연 실시간 모드
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        setInteger(MediaFormat.KEY_LATENCY, 0) // 퀄컴/엑시노스 0-레이턴시 모드
                        setInteger(MediaFormat.KEY_PRIORITY, 0) // 실시간 우선순위
                    } catch (_: Exception) {}
                }
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
            var lastClientCheckTime = 0L
            var cachedHasLocalClients = false
            lastFrameProducedTime = android.os.SystemClock.elapsedRealtime()

            requestSyncFrame()
            scheduleDisplayChangeCheck()

            while (isStreaming) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastClientCheckTime > 1500L) {
                    lastClientCheckTime = nowMs
                    cachedHasLocalClients = try { NativeBridge.hasConnectedClients() } catch (_: Throwable) { false }
                }

                val outputBufferIndex = try {
                    mediaCodec?.dequeueOutputBuffer(bufferInfo, 10_000) ?: -1
                } catch (e: Exception) {
                    if (isStreaming) Log.w(TAG, "Error dequeuing output buffer", e)
                    -1
                }

                if (outputBufferIndex >= 0) {
                    lastFrameProducedTime = android.os.SystemClock.elapsedRealtime()
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
                            if (cachedHasLocalClients) {
                                NativeBridge.sendVideoFrame(byteArray, 0, bufferInfo.size)
                            }
                            sendRelayVideo(byteArray, false)
                            // WebRTC DataChannel에는 SPS/PPS 단독 패킷을 보내지 않음 (다음 IDR 프레임과 병합되어 100% 온전하게 전송)
                        } else if (isKeyFrame && spsPpsBuffer != null) {
                            // 키프레임(IDR) 앞단에 SPS/PPS를 항상 병합하여 전송 및 상시 캐시
                            val combined = ByteArray(spsPpsBuffer!!.size + byteArray.size)
                            System.arraycopy(spsPpsBuffer!!, 0, combined, 0, spsPpsBuffer!!.size)
                            System.arraycopy(byteArray, 0, combined, spsPpsBuffer!!.size, byteArray.size)
                            lastIFrameBuffer = combined
                            if (cachedHasLocalClients) {
                                NativeBridge.sendVideoFrame(combined, 0, combined.size)
                            }
                            sendRelayVideo(combined, true)
                            webRtcStreamer?.sendVideoPacket(combined, true)
                        } else {
                            if (isKeyFrame) {
                                lastIFrameBuffer = byteArray.clone()
                            }
                            if (cachedHasLocalClients) {
                                NativeBridge.sendVideoFrame(byteArray, 0, bufferInfo.size)
                            }
                            sendRelayVideo(byteArray, false)
                            webRtcStreamer?.sendVideoPacket(byteArray, isKeyFrame)
                        }
                    }
                    try {
                        mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                    } catch (_: Exception) {}
                } else {
                    // 정적 화면 동결 방지 하트비트: 2.5초 이상 화면 변화가 없을 경우 캐시된 I-프레임을 리프레시 송출하여 테슬라 캔버스 정전 차단
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastFrameProducedTime > 2500L) {
                        lastFrameProducedTime = now
                        val lastIFrame = lastIFrameBuffer
                        if (lastIFrame != null && isStreaming) {
                            webRtcStreamer?.sendVideoPacket(lastIFrame, true)
                        } else if (isStreaming) {
                            requestSyncFrame()
                        }
                    }
                }
            }
            Log.i(TAG, "H.264 Encoder loop stopped")
        }, "mMirror-VideoEncoderThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private var displayListener: DisplayManager.DisplayListener? = null
    private val displayChangeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val displayChangeRunnable = Runnable {
        checkAndApplyDisplayChanges()
    }

    fun scheduleDisplayChangeCheck() {
        displayChangeHandler.removeCallbacks(displayChangeRunnable)
        displayChangeHandler.postDelayed(displayChangeRunnable, 200L) // 1차 빠른 반영
        displayChangeHandler.postDelayed(displayChangeRunnable, 500L) // 2차 힌지 전환 완료 시점
        displayChangeHandler.postDelayed(displayChangeRunnable, 900L) // 3차 안정화
    }

    private fun computeScreenDimensions(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var rawWidth: Int
        var rawHeight: Int
        var density: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = wm.currentWindowMetrics
            val bounds = windowMetrics.bounds
            rawWidth = bounds.width()
            rawHeight = bounds.height()
            density = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            rawWidth = metrics.widthPixels
            rawHeight = metrics.heightPixels
            density = metrics.densityDpi
        }

        if (rawWidth <= 0 || rawHeight <= 0) {
            rawWidth = 1080
            rawHeight = 1920
        }

        rawScreenWidth = rawWidth
        rawScreenHeight = rawHeight

        return resolutionPreset.computeEffectiveDimensions(this, rawWidth, rawHeight, density)
    }

    fun computeScreenDimensionsForStream(): Triple<Int, Int, Int> {
        return computeScreenDimensions()
    }

    private fun registerDisplayListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                AppLogger.i(TAG, "📱 외부 디스플레이 연결됨 (displayId=$displayId)")
                scheduleDisplayChangeCheck()
            }
            override fun onDisplayRemoved(displayId: Int) {
                AppLogger.i(TAG, "📱 디스플레이 제거됨 (displayId=$displayId)")
                scheduleDisplayChangeCheck()
            }
            override fun onDisplayChanged(displayId: Int) {
                scheduleDisplayChangeCheck()
            }
        }
        dm.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    private fun unregisterDisplayListener() {
        displayChangeHandler.removeCallbacks(displayChangeRunnable)
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
        AppLogger.i(TAG, "📱 onConfigurationChanged 감지 (폴드 접힘/펼침/화면회전) -> 해상도 자동 동기화")
        scheduleDisplayChangeCheck()
    }

    @Synchronized
    private fun checkAndApplyDisplayChanges() {
        if (!isStreaming) return

        val (newW, newH, newDensity) = computeScreenDimensions()
        val diffW = kotlin.math.abs(newW - screenWidth)
        val diffH = kotlin.math.abs(newH - screenHeight)
        if (diffW < 32 && diffH < 32 && newDensity == screenDensity) {
            return
        }

        AppLogger.i(TAG, "🔄 폴드/화면 전환 감지: ${screenWidth}x${screenHeight} -> ${newW}x${newH} (밀도: $newDensity, raw: ${rawScreenWidth}x${rawScreenHeight})")
        screenWidth = newW
        screenHeight = newH
        screenDensity = newDensity
        TouchControlService.instance?.updateDimensions(rawScreenWidth, rawScreenHeight, screenWidth, screenHeight)

        NativeBridge.updateConfig(screenWidth, screenHeight, 0, 60)
        sendRelayConfig()

        webRtcStreamer?.changeResolution(screenWidth, screenHeight, screenDensity, 60)
        if (mediaCodec != null) {
            restartVideoEncoder()
        }
    }

    private fun restartVideoEncoder() {
        try {
            isStreaming = false
            encodingThread?.interrupt()
            encodingThread = null

            // 1. 기존 가상 디스플레이에서 서피스를 먼저 안전하게 분리하여 크래시 방지
            try {
                virtualDisplay?.setSurface(null)
            } catch (e: Exception) {
                Log.w(TAG, "Surface detach warning: ${e.message}")
            }

            // 2. 이전 비디오 코덱 안전하게 종료
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (_: Exception) {}
            mediaCodec = null

            // 3. 해상도 변경에 따른 새 SPS/PPS 생성 대기 위해 버퍼 초기화
            spsPpsBuffer = null
            lastIFrameBuffer = null

            // 4. 새로운 해상도 포맷으로 H.264 하드웨어 인코더 생성
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                } catch (_: Exception) {}
                try {
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        setInteger(MediaFormat.KEY_LATENCY, 0)
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                    } catch (_: Exception) {}
                }
            }

            val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = newCodec.createInputSurface()
            mediaCodec = newCodec

            // 5. 가상 디스플레이 크기 재설정 및 새 서피스 연결
            if (virtualDisplay != null) {
                virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)
                virtualDisplay?.setSurface(surface)
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

            newCodec.start()
            isStreaming = true
            startEncodingLoop()
            requestSyncFrame()

            Log.i(TAG, "✅ 폴드 화면 전환 완료: ${screenWidth}x${screenHeight} 실시간 재설정됨")
        } catch (e: Exception) {
            Log.e(TAG, "비디오 인코더 재설정 실패: ${e.message}", e)
            isStreaming = true
        }
    }

    private fun stopMirroring() {
        isStreaming = false
        isRunning = false
        isStandalone = false
        carExitRunnable?.let { mainHandler.removeCallbacks(it) }
        carExitRunnable = null

        try {
            if (ScreenDimmerManager.isDimmed) {
                ScreenDimmerManager.setDimmed(false)
            }
        } catch (_: Exception) {}


        unregisterDisplayListener()

        wakeLockManager?.releaseLocks()
        wakeLockManager = null

        try {
            relayWebSocket?.close(1000, "User stopped mirroring")
        } catch (_: Exception) {}
        relayWebSocket = null
        isRelayConnected = false

        webRtcStreamer?.stop()
        webRtcStreamer = null

        stopAudioCapture()

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

            spsPpsBuffer = null
            lastIFrameBuffer = null
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
