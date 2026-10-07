package io.mmirror.webrtc

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import io.mmirror.AppLogger
import io.mmirror.MediaProjectionService
import io.mmirror.NetworkUtils
import io.mmirror.ScreenDimmerManager
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer

class WebRtcStreamer(
    private val context: Context,
    private val resultData: Intent? = null,
    private var width: Int = 1600,
    private var height: Int = 1120,
    private var density: Int = 180,
    private val fps: Int = 60,
    private val signalingUrl: String = "wss://mdm.mplat.store:8088/webrtc/signal?role=publisher&room=default",
    var isStandalone: Boolean = false,
    private var mediaProjection: MediaProjection? = null,
    val isAutoMirrorUnsupported: Boolean = false
) {
    companion object {
        private const val TAG = "WebRtcStreamer"
        val cachedAppIcons = java.util.concurrent.ConcurrentHashMap<String, String>()
        var instance: WebRtcStreamer? = null
            private set
    }

    private val initialWidth: Int = width
    private val initialHeight: Int = height

    var activeAutoMirrorMode: Boolean = false
        private set

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var videoCapturer: VideoCapturer? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var presentationCapturer: PresentationVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private val okHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()
    private var signalingWs: WebSocket? = null
    private var firebaseSignaling: FirebaseSignalingManager? = null
    var firebaseDatabaseUrl: String = "https://mplat-33044-default-rtdb.asia-southeast1.firebasedatabase.app"
        get() {
            if (field.isNotBlank()) return field
            return try {
                val prefs = context.getSharedPreferences("mmirror_prefs", Context.MODE_PRIVATE)
                prefs.getString("firebase_database_url", "https://mplat-33044-default-rtdb.asia-southeast1.firebasedatabase.app") ?: "https://mplat-33044-default-rtdb.asia-southeast1.firebasedatabase.app"
            } catch (_: Exception) { "https://mplat-33044-default-rtdb.asia-southeast1.firebasedatabase.app" }
        }

    private fun sendSignaling(message: String) {
        try {
            signalingWs?.send(message)
            firebaseSignaling?.send(message)
        } catch (_: Exception) {}
    }

    @Volatile private var isRunning = false
    @Volatile private var isSignalingConnected = false

    // --- 적응형 스트리밍 (ABR: Adaptive Bitrate & FPS) 상태 ---
    @Volatile var isAbrEnabled: Boolean = true
    @Volatile private var currentMinBitrateBps: Int = 1_000_000 // 1.0 Mbps
    @Volatile private var currentMaxBitrateBps: Int = 3_200_000 // 3.2 Mbps (60 FPS 초저지연 + 40KB 이하 IDR 보장)
    @Volatile private var currentMaxFramerate: Int = 60
    private var abrGoodConditionStreak: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val dimmerListener: (Boolean) -> Unit = { dimmed ->
        try {
            val notify = JSONObject().apply {
                put("type", "screen_power_changed")
                put("on", !dimmed)
            }
            dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(notify.toString().toByteArray(Charsets.UTF_8)), false))
        } catch (_: Exception) {}
    }

    fun isPeerConnected(): Boolean {
        return peerConnection?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        instance = this
        ScreenDimmerManager.addListener(dimmerListener)
        AppLogger.i(TAG, "Starting WebRtcStreamer (${width}x${height} @ ${fps}fps, isStandalone=$isStandalone)...")

        try {
            initPeerConnectionFactory()
            initVideoCapturer()
            connectSignaling()
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Failed to start WebRtcStreamer: ${e.message}", e)
            stop()
        }
    }

    private fun initPeerConnectionFactory() {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val egl = EglBase.create()
        eglBase = egl

        val encoderFactory = DefaultVideoEncoderFactory(
            egl.eglBaseContext,
            true, /* enableIntelVp8Encoder */
            false /* enableH264HighProfile -> Baseline Profile for 0ms B-frame delay */
        )
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)

        val options = PeerConnectionFactory.Options().apply {
            disableNetworkMonitor = true
            networkIgnoreMask = 0
        }

        val adm = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setEnableVolumeLogger(false)
            .createAudioDeviceModule().apply {
                setSpeakerMute(true)
                setMicrophoneMute(true)
            }

        // [작업 지시서 3] AudioManager 모드가 MODE_NORMAL로 유지되는지 확인 및 SCO 비활성화
        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (audioManager != null) {
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    audioManager.clearCommunicationDevice()
                } catch (_: Exception) {}
            }
            if (audioManager.mode != android.media.AudioManager.MODE_NORMAL) {
                audioManager.mode = android.media.AudioManager.MODE_NORMAL
            }
            @Suppress("DEPRECATION")
            AppLogger.i(TAG, "🔊 [AUDIO-CHECK] AudioManager mode: ${audioManager.mode} (MODE_NORMAL=${android.media.AudioManager.MODE_NORMAL}), SCO: ${audioManager.isBluetoothScoOn}")
        }

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun initVideoCapturer() {
        // 순수 DataChannel H.264 전송 모드 (Android 14+ 단일 MediaProjection 충돌 방지 및 브라우저 오디오 간섭 0%)
        Log.i(TAG, "✓ Operating in pure DataChannel H.264 stream mode (Zero audio interference, single hardware MediaCodec)")
    }

    private fun connectSignaling() {
        if (!isRunning) return

        // 1. Firebase Realtime Database 시그널링 (우선 표준)
        val dbUrl = firebaseDatabaseUrl
        if (dbUrl.isNotBlank()) {
            if (firebaseSignaling == null) {
                firebaseSignaling = FirebaseSignalingManager(dbUrl, "default", okHttpClient) { msg ->
                    handleSignalingMessage(msg)
                }
                firebaseSignaling?.start()
                isSignalingConnected = true
                sendConfig()
                AppLogger.i(TAG, "🔥 Firebase Realtime Database 전용 시그널링 활성화 (레거시 WebSocket 루프 차단)")

                // ★ 스마트폰 방송 시작 시 뷰어 대기 여부와 상관없이 즉시 초기 SDP Offer 생성 및 Firebase 등록
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isRunning && !isPeerConnected()) {
                        AppLogger.i(TAG, "🚀 Proactively generating initial SDP Offer on start...")
                        createPeerConnectionAndOffer()
                    }
                }, 200L)
            }
            return
        }

        // 2. WebSocket 시그널링 (Firebase 미설정 시의 오프라인 로컬 폴백)
        Log.i(TAG, "Connecting to WebRTC Signaling: $signalingUrl")

        val request = Request.Builder()
            .url(signalingUrl)
            .build()

        signalingWs = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLogger.i(TAG, "🟢 WebRTC Signaling WebSocket Connected")
                isSignalingConnected = true
                sendConfig()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignalingMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLogger.e(TAG, "❌ Signaling WebSocket failure: ${t.message} (HTTP ${response?.code})", t)
                isSignalingConnected = false
                if (isRunning && firebaseDatabaseUrl.isBlank()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isRunning && !isSignalingConnected && firebaseDatabaseUrl.isBlank()) {
                            connectSignaling()
                        }
                    }, 5000)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.w(TAG, "⚠️ Signaling WebSocket closed: code=$code, reason=$reason")
                isSignalingConnected = false
                if (isRunning && firebaseDatabaseUrl.isBlank()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isRunning && !isSignalingConnected && firebaseDatabaseUrl.isBlank()) {
                            connectSignaling()
                        }
                    }, 5000)
                }
            }
        })
    }

    private fun sendConfig() {
        try {
            val json = JSONObject().apply {
                put("type", "config")
                put("width", width)
                put("height", height)
                put("fps", fps)
                put("isStandalone", false)
                put("appVersion", io.mmirror.BuildConfig.VERSION_NAME)
            }
            val str = json.toString()
            sendSignaling(str)

            // DataChannel이 열려있으면 0ms 직접 전달
            val dc = dataChannel
            if (dc != null && dc.state() == DataChannel.State.OPEN) {
                val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(str.toByteArray(Charsets.UTF_8)), false)
                dc.send(buffer)
            }
        } catch (_: Exception) {}
    }

    private fun getHotspotIp(): String = NetworkUtils.getHotspotIp()

    private fun getCandidateTargetIps(hotspotIp: String): Set<String> = NetworkUtils.getCandidateTargetIps(hotspotIp)

    private fun mungeSdpForLowLatency(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val hotspotIp = getHotspotIp()

        val result = mutableListOf<String>()
        for (line in lines) {
            var l = line
            // [작업 지시서 3] audio/video m-section이 있을 경우 포트를 0으로 거부하여 DataChannel만 활성화
            if (l.startsWith("m=audio ")) {
                l = l.replaceFirst(Regex("^m=audio \\d+"), "m=audio 0")
            } else if (l.startsWith("m=video ")) {
                l = l.replaceFirst(Regex("^m=video \\d+"), "m=video 0")
            }
            if (l.startsWith("c=IN IP4") && !l.contains("0.0.0.0") && hotspotIp.isNotEmpty()) {
                l = "c=IN IP4 $hotspotIp"
            }
            result.add(l)
        }
        return result.joinToString("\r\n")
    }

    private fun configureVideoSenderParameters() {
        try {
            peerConnection?.senders?.forEach { sender ->
                if (sender.track()?.kind() == "video") {
                    val params = sender.parameters
                    for (enc in params.encodings) {
                        enc.minBitrateBps = currentMinBitrateBps
                        enc.maxBitrateBps = currentMaxBitrateBps
                        enc.maxFramerate = currentMaxFramerate
                    }
                    sender.parameters = params
                    Log.i(TAG, "✓ Configured RtpSender: ${currentMinBitrateBps / 1000}~${currentMaxBitrateBps / 1000} kbps, $currentMaxFramerate FPS")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure video sender parameters: ${e.message}")
        }
    }

    /**
     * 실시간 적응형 비트레이트 및 FPS 즉각 적용
     */
    fun applyBitrateAndFramerate(bitrateBps: Int, fps: Int, notifyClient: Boolean = true) {
        currentMaxBitrateBps = bitrateBps.coerceIn(1_000_000, 3_500_000)
        currentMaxFramerate = fps.coerceIn(30, 60)

        try {
            peerConnection?.senders?.forEach { sender ->
                if (sender.track()?.kind() == "video") {
                    val params = sender.parameters
                    for (enc in params.encodings) {
                        enc.minBitrateBps = currentMinBitrateBps
                        enc.maxBitrateBps = currentMaxBitrateBps
                        enc.maxFramerate = currentMaxFramerate
                    }
                    sender.parameters = params
                }
            }
            PresentationVideoCapturer.instance?.targetFps = currentMaxFramerate
            Log.i(TAG, "⚡ ABR Applied: ${currentMaxBitrateBps / 1000} kbps, $currentMaxFramerate FPS")
            io.mmirror.MediaProjectionService.instance?.updateMediaCodecBitrate(currentMaxBitrateBps)

            if (notifyClient) {
                sendAbrStatus()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply ABR parameters: ${e.message}")
        }
    }

    fun setAdaptiveBitrateEnabled(enabled: Boolean) {
        this.isAbrEnabled = enabled
        Log.i(TAG, "⚡ ABR Enabled state set to: $enabled")
        if (!enabled) {
            applyBitrateAndFramerate(3_200_000, 60)
        } else {
            sendAbrStatus()
        }
    }

    private fun sendAbrStatus() {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        try {
            val quality = when {
                currentMaxBitrateBps >= 4_000_000 -> "HD"
                currentMaxBitrateBps >= 2_500_000 -> "BALANCED"
                else -> "ECO"
            }
            val resp = JSONObject().apply {
                put("type", "abr_status")
                put("bitrate_kbps", currentMaxBitrateBps / 1000)
                put("fps", currentMaxFramerate)
                put("quality", quality)
                put("abr_enabled", isAbrEnabled)
            }
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(resp.toString().toByteArray(Charsets.UTF_8)), false))
        } catch (_: Exception) {}
    }

    /**
     * 테슬라 브라우저에서 전송된 실시간 네트워크 텔레메트리 기반 적응형 조절 알고리즘
     */
    fun handleNetworkStats(rtt: Double, loss: Double, jitter: Double, @Suppress("UNUSED_PARAMETER") clientFps: Double = 0.0, @Suppress("UNUSED_PARAMETER") clientBitrate: Double = 0.0) {
        if (!isAbrEnabled) return

        // 1. 심각한 혼잡 상태: RTT 160ms 초과, 패킷 손실 5% 초과, 또는 지터 40ms 초과
        if (rtt > 160.0 || loss > 0.05 || jitter > 40.0) {
            abrGoodConditionStreak = 0
            val newBitrate = (currentMaxBitrateBps * 0.70).toInt().coerceAtLeast(1_200_000)
            val newFps = 30
            if (newBitrate != currentMaxBitrateBps || newFps != currentMaxFramerate) {
                Log.w(TAG, "🚨 [ABR] Severe congestion (RTT=${rtt.toInt()}ms, loss=${(loss * 100).toInt()}%, jitter=${jitter.toInt()}ms) -> Stepping down to ${newBitrate / 1000} kbps, $newFps FPS")
                applyBitrateAndFramerate(newBitrate, newFps)
            }
            return
        }

        // 2. 경미한 혼잡 상태: RTT 90ms 초과, 패킷 손실 2% 초과, 또는 지터 25ms 초과
        if (rtt > 90.0 || loss > 0.02 || jitter > 25.0) {
            abrGoodConditionStreak = 0
            val newBitrate = (currentMaxBitrateBps * 0.85).toInt().coerceAtLeast(1_800_000)
            val newFps = if (currentMaxFramerate > 45) 45 else currentMaxFramerate
            if (newBitrate != currentMaxBitrateBps || newFps != currentMaxFramerate) {
                Log.i(TAG, "⚠️ [ABR] Moderate congestion (RTT=${rtt.toInt()}ms, loss=${(loss * 100).toInt()}%) -> Stepping down to ${newBitrate / 1000} kbps, $newFps FPS")
                applyBitrateAndFramerate(newBitrate, newFps)
            }
            return
        }

        // 3. 안정 및 양호 상태: RTT < 50ms, 손실률 < 0.5%, 지터 < 15ms
        if (rtt < 50.0 && loss < 0.005 && jitter < 15.0) {
            abrGoodConditionStreak++
            // 2회 연속 안정(약 3초 유지) 시 단계적 상향 (+350 kbps)
            if (abrGoodConditionStreak >= 2) {
                abrGoodConditionStreak = 0
                val newBitrate = (currentMaxBitrateBps + 350_000).coerceAtMost(3_500_000)
                val newFps = if (newBitrate >= 3_200_000) 60 else 45
                if (newBitrate != currentMaxBitrateBps || newFps != currentMaxFramerate) {
                    Log.i(TAG, "🟢 [ABR] Network clean & stable -> Ramping up to ${newBitrate / 1000} kbps, $newFps FPS")
                    applyBitrateAndFramerate(newBitrate, newFps)
                }
            }
        } else {
            abrGoodConditionStreak = 0
        }
    }

    private var lastOfferTimestamp = 0L
    @Volatile private var currentOfferId: String = ""
    @Volatile private var hasSynthesizedHotspotCandidate = false
    @Volatile private var hasNativeHotspotCandidate = false
    @Volatile private var lastRemoteAnswerUfrag: String? = null
    private var pendingReconnectRunnable: Runnable? = null
    private var proactiveReconnectRunnable: Runnable? = null

    private fun createPeerConnectionAndOffer(force: Boolean = false) {
        val existingPc = peerConnection
        if (!force && existingPc != null && (existingPc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED ||
                                   existingPc.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED)) {
            AppLogger.i(TAG, "⚡ PeerConnection already connected! Skipping redundant offer creation and requesting keyframe.")
            io.mmirror.MediaProjectionService.instance?.requestKeyFrame()
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastOfferTimestamp < 1500) {
            Log.i(TAG, "Debouncing rapid Offer request (${now - lastOfferTimestamp}ms)")
            return
        }
        lastOfferTimestamp = now
        currentOfferId = java.util.UUID.randomUUID().toString()
        hasSynthesizedHotspotCandidate = false
        hasNativeHotspotCandidate = false
        lastRemoteAnswerUfrag = null

        val pcf = peerConnectionFactory ?: return

        synchronized(pendingRemoteCandidates) {
            pendingRemoteCandidates.clear()
        }

        peerConnection?.close()
        firebaseSignaling?.clearSessionForNewOffer()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }

        val pcObserver = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (candidate.sdp.contains(" tcp ")) {
                    Log.d(TAG, "⏩ Skipping TCP ICE candidate: ${candidate.sdp}")
                    return
                }
                Log.i(TAG, "📡 Generated local ICE candidate: ${candidate.sdp}")
                val json = JSONObject().apply {
                    put("type", "candidate")
                    put("candidate", JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                }
                sendSignaling(json.toString())

                // ★ 테슬라 핫스팟 P2P 직결 핵심: 핫스팟 로컬 IP(10.x.x.x 또는 192.168.x.x) 후보 단 1회만 정밀 합성 전송
                val hotspotIp = getHotspotIp()
                if (candidate.sdp.contains(hotspotIp)) {
                    hasNativeHotspotCandidate = true
                } else if (!hasNativeHotspotCandidate && !hasSynthesizedHotspotCandidate) {
                    val parts = candidate.sdp.split(" ")
                    if (parts.size >= 8 && candidate.sdp.contains(" udp ") && (candidate.sdp.contains("typ host") || candidate.sdp.contains("typ srflx"))) {
                        hasSynthesizedHotspotCandidate = true
                        val synthSdp = candidate.sdp
                            .replace(" ${parts[4]} ", " $hotspotIp ")
                            .replace("typ srflx", "typ host")
                        val synthJson = JSONObject().apply {
                            put("type", "candidate")
                            put("candidate", JSONObject().apply {
                                put("candidate", synthSdp)
                                put("sdpMid", candidate.sdpMid)
                                put("sdpMLineIndex", candidate.sdpMLineIndex)
                            })
                        }
                        sendSignaling(synthJson.toString())
                        Log.i(TAG, "📡 Synthesized Hotspot ICE candidate for Tesla (single, UDP): $synthSdp")
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                AppLogger.i(TAG, "⚡ WebRTC Connection State: $newState")
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    AppLogger.i(TAG, "🎉 [WEBRTC] Direct P2P Connected to Tesla! 0MB Local Streaming Active!")
                    proactiveReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingReconnectRunnable?.let { mainHandler.removeCallbacks(it); pendingReconnectRunnable = null }
                    // 차량 연결됨 → GPS 주행 기록 시작
                    io.mmirror.DrivingLogManager.currentInstance?.onPeerConnected()
                    io.mmirror.MediaProjectionService.instance?.requestKeyFrame(immediateCached = true)
                } else if (newState == PeerConnection.PeerConnectionState.DISCONNECTED) {
                    AppLogger.w(TAG, "🔌 WebRTC peer disconnected (일시 지터 감지 - 8초 자연 복구 대기): $newState")
                    proactiveReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                    val r = Runnable {
                        val currState = peerConnection?.connectionState()
                        if (isRunning && currState != PeerConnection.PeerConnectionState.CONNECTED) {
                            AppLogger.i(TAG, "⚡ [SELF-HEALING] Connection lost for 8s (state=$currState) -> Phone proactively generating fresh Offer!")
                            io.mmirror.DrivingLogManager.currentInstance?.onPeerDisconnected()
                            createPeerConnectionAndOffer(force = true)
                        }
                    }
                    proactiveReconnectRunnable = r
                    mainHandler.postDelayed(r, 8000L)
                } else if (newState == PeerConnection.PeerConnectionState.FAILED) {
                    AppLogger.w(TAG, "🔌 WebRTC peer failed: $newState")
                    io.mmirror.DrivingLogManager.currentInstance?.onPeerDisconnected()
                    proactiveReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                    val r = Runnable {
                        if (isRunning && peerConnection?.connectionState() == PeerConnection.PeerConnectionState.FAILED) {
                            AppLogger.i(TAG, "⚡ [SELF-HEALING] Connection failed -> Phone generating fresh Offer!")
                            createPeerConnectionAndOffer(force = true)
                        }
                    }
                    proactiveReconnectRunnable = r
                    mainHandler.postDelayed(r, 3000L)
                } else if (newState == PeerConnection.PeerConnectionState.CLOSED) {
                    proactiveReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingReconnectRunnable?.let { mainHandler.removeCallbacks(it); pendingReconnectRunnable = null }
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                AppLogger.d(TAG, "📡 WebRTC Signaling State: $state")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                AppLogger.i(TAG, "🧊 ICE Connection State: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    AppLogger.i(TAG, "✓ [WEBRTC] ICE P2P 직결 바인딩 성공!")
                    proactiveReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingReconnectRunnable?.let { mainHandler.removeCallbacks(it); pendingReconnectRunnable = null }
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    AppLogger.w(TAG, "⚠️ [WEBRTC] ICE 일시 연결 지연 (8s 자연 복구 대기)")
                    proactiveReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                    val r = Runnable {
                        val currIce = peerConnection?.iceConnectionState()
                        if (isRunning && currIce != PeerConnection.IceConnectionState.CONNECTED && currIce != PeerConnection.IceConnectionState.COMPLETED) {
                            AppLogger.i(TAG, "⚡ [SELF-HEALING] ICE state=$currIce for 8s -> Phone proactively generating fresh Offer!")
                            createPeerConnectionAndOffer(force = true)
                        }
                    }
                    proactiveReconnectRunnable = r
                    mainHandler.postDelayed(r, 8000L)
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    AppLogger.w(TAG, "⚠️ [WEBRTC] ICE 연결 실패 (3s 후 자가치유 Offer 가동)")
                    proactiveReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                    val r = Runnable {
                        val currIce = peerConnection?.iceConnectionState()
                        if (isRunning && currIce == PeerConnection.IceConnectionState.FAILED) {
                            AppLogger.i(TAG, "⚡ [SELF-HEALING] ICE failed -> Phone proactively generating fresh Offer!")
                            createPeerConnectionAndOffer(force = true)
                        }
                    }
                    proactiveReconnectRunnable = r
                    mainHandler.postDelayed(r, 3000L)
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                AppLogger.d(TAG, "🧊 ICE Receiving: $receiving")
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                AppLogger.d(TAG, "📡 ICE Gathering: $state")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel) {
                setupDataChannel(dc)
            }
            override fun onRenegotiationNeeded() {
                AppLogger.d(TAG, "🔄 WebRTC onRenegotiationNeeded")
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        val pc = pcf.createPeerConnection(rtcConfig, pcObserver)
        if (pc == null) {
            AppLogger.e(TAG, "❌ PeerConnectionFactory.createPeerConnection() returned NULL!")
            return
        }
        peerConnection = pc

        // [작업 지시서 3] DataChannel 전용: addTrack/addTransceiver/오디오소스를 만들지 않음
        val dcInit = DataChannel.Init().apply {
            ordered = true
        }
        val dc = pc.createDataChannel("control", dcInit)
        setupDataChannel(dc)

        // WebRTC 표준 MediaConstraints (레거시 옵션 제거)
        val sdpConstraints = MediaConstraints()

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                // [작업 지시서 1] 송출단에서 생성된 Offer SDP m-section 전체 로깅
                val rawMlines = desc.description.lines().filter { it.startsWith("m=") }
                AppLogger.i(TAG, "📡 [SDP-OFFER-RAW] Generated m-lines: $rawMlines")

                val mungedSdp = mungeSdpForLowLatency(desc.description)
                val mungedDesc = SessionDescription(desc.type, mungedSdp)

                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        AppLogger.i(TAG, "✓ LocalDescription 설정 완료 (DataChannel 전용 SDP), Offer 전송 중...")
                        val json = JSONObject().apply {
                            put("type", "offer")
                            put("offerId", currentOfferId)
                            put("sdp", mungedSdp)
                            put("timestamp", System.currentTimeMillis())
                            put("config", JSONObject().apply {
                                put("width", width)
                                put("height", height)
                                put("fps", fps)
                                put("isStandalone", isStandalone)
                                put("appVersion", io.mmirror.BuildConfig.VERSION_NAME)
                            })
                        }
                        sendSignaling(json.toString())
                    }
                    override fun onCreateFailure(err: String?) { AppLogger.e(TAG, "❌ setLocalDescription createFailure: $err") }
                    override fun onSetFailure(err: String?) { AppLogger.e(TAG, "❌ setLocalDescription setFailure: $err") }
                }, mungedDesc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { AppLogger.e(TAG, "❌ createOffer failure: $err") }
            override fun onSetFailure(err: String?) { AppLogger.e(TAG, "❌ createOffer setFailure: $err") }
        }, sdpConstraints)
    }

    private fun setupDataChannel(dc: DataChannel) {
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.i(TAG, "💬 DataChannel State changed: ${dc.state()}")
                if (dc.state() == DataChannel.State.OPEN) {
                    isDroppingGop = false
                    sendConfig()
                    sendTouchStatus()
                    sendAppList()
                    sendAudioModeStatus()
                    MediaProjectionService.instance?.requestKeyFrame()
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                handleControlMessage(text)
            }
        })
    }

    fun sendTouchStatus() {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        try {
            val isA11yGranted = io.mmirror.TouchControlService.isAccessibilityServiceEnabled(context)
            val resp = JSONObject().apply {
                put("type", "touch_status")
                put("installed", true)
                put("running", isA11yGranted)
                put("granted", isA11yGranted)
                put("uid", 1000)
                put("version", "v${io.mmirror.BuildConfig.VERSION_NAME}")
                put("statusText", if (isA11yGranted) "접근성 터치 활성" else "접근성 권한 필요")
                put("virtualDisplayId", -1)
                put("isStandalone", false)
            }
            val payload = resp.toString().toByteArray(Charsets.UTF_8)
            val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(payload), false)
            dc.send(buffer)
            Log.i(TAG, "Sent touch status: granted=$isA11yGranted")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send touch status: ${e.message}")
        }
    }

    private fun drawableToBase64(drawable: android.graphics.drawable.Drawable, sizePx: Int = 72): String {
        val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 70, baos)
        bitmap.recycle()
        return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    fun sendAppList() {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        Thread {
            try {
                val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val installedList = pm.queryIntentActivities(launcherIntent, 0)

            // 우선 표시할 주요 앱 패키지 (정렬 우선순위)
            val priorityPkgs = listOf(
                "com.skt.tmap.ku", "com.skt.skaf.l001mtm091",
                "com.locnall.KimGiSa", "com.nhn.android.nmap",
                "com.google.android.youtube", "com.netflix.mediaclient",
                "com.google.android.apps.youtube.music",
                "com.iloen.melon", "com.spotify.music",
                "com.android.chrome",
                "com.coupang.mobile.play", "net.cj.cjenm.tving",
                "kr.co.captv.pooqV2", "com.disney.disneyplus"
            )

            data class AppEntry(val name: String, val pkg: String, val resolveInfo: android.content.pm.ResolveInfo, val priority: Int)

            val appEntries = mutableListOf<AppEntry>()
            val seenPkgs = mutableSetOf<String>()

            for (resolveInfo in installedList) {
                val pkg = resolveInfo.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue
                if (seenPkgs.contains(pkg)) continue
                seenPkgs.add(pkg)

                val label = resolveInfo.loadLabel(pm).toString()
                val priority = priorityPkgs.indexOf(pkg).let { if (it >= 0) it else 999 }
                appEntries.add(AppEntry(label, pkg, resolveInfo, priority))
            }

            // 정렬: 우선 앱 먼저, 나머지는 한글/영어 이름순
            appEntries.sortWith(compareBy<AppEntry> { it.priority }.thenBy { it.name })

            // 1차: 아이콘 없이 앱 목록 먼저 전송 (즉시 UI 렌더링, 0ms 지연)
            val jsonArray = org.json.JSONArray()
            for (entry in appEntries) {
                val item = JSONObject().apply {
                    put("name", entry.name)
                    put("package", entry.pkg)
                    put("installed", true)
                }
                jsonArray.put(item)
            }

            val isA11yGranted = io.mmirror.TouchControlService.isAccessibilityServiceEnabled(context)
            val resp = JSONObject().apply {
                put("type", "app_list")
                put("apps", jsonArray)
                put("touchControl", isA11yGranted)
                put("touchRunning", isA11yGranted)
                put("virtualDisplayId", -1)
                put("isStandalone", false)
            }
            val payload = resp.toString().toByteArray(Charsets.UTF_8)
            dc.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(payload), false))
            Log.i(TAG, "Sent app_list to viewer (${jsonArray.length()} apps)")

            // 2차: 아이콘을 백그라운드에서 지연 추출하여 배치(batch)로 전송 (메모리 캐시 재활용)
            val batchSize = 12
            for (i in appEntries.indices step batchSize) {
                val dcNow = dataChannel
                if (dcNow == null || dcNow.state() != DataChannel.State.OPEN) break

                val end = minOf(i + batchSize, appEntries.size)
                val batch = appEntries.subList(i, end)
                val iconsObj = JSONObject()
                for (entry in batch) {
                    val iconStr = cachedAppIcons.getOrPut(entry.pkg) {
                        try {
                            val icon = entry.resolveInfo.loadIcon(pm)
                            drawableToBase64(icon)
                        } catch (_: Throwable) { "" }
                    }
                    if (iconStr.isNotBlank()) {
                        iconsObj.put(entry.pkg, iconStr)
                    }
                }
                if (iconsObj.length() > 0) {
                    val iconResp = JSONObject().apply {
                        put("type", "app_icons")
                        put("icons", iconsObj)
                    }
                    val iconPayload = iconResp.toString().toByteArray(Charsets.UTF_8)
                    dcNow.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(iconPayload), false))
                    Thread.sleep(20) // 캐시된 아이콘은 빠른 전송 허용 (20ms)
                }
            }

            } catch (e: Exception) {
                Log.w(TAG, "Failed to send app list: ${e.message}")
            }
        }.start()
    }

    private fun handleControlMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            when (type) {
                "get_touch_status", "get_shizuku_status" -> {
                    sendTouchStatus()
                }
                "request_keyframe" -> {
                    Log.i(TAG, "🔑 Received request_keyframe from DataChannel -> triggering sync frame refresh")
                    isDroppingGop = false
                    io.mmirror.MediaProjectionService.instance?.requestKeyFrame()
                    io.mmirror.MediaProjectionService.instance?.scheduleDisplayChangeCheck()
                }
                "touch" -> {
                    val action = json.optString("action")
                    val id = json.optInt("id", 0)
                    val x = json.optDouble("x", 0.0).toFloat()
                    val y = json.optDouble("y", 0.0).toFloat()
                    io.mmirror.TouchControlService.instance?.onTouch(action, id, x, y)
                }
                "pinch_zoom" -> {
                    val direction = json.optString("direction", "in")
                    val x = json.optDouble("x", 0.5).toFloat()
                    val y = json.optDouble("y", 0.5).toFloat()
                    io.mmirror.TouchControlService.instance?.onPinchZoom(direction, x, y)
                }
                "key" -> {
                    val key = json.optString("key")
                    io.mmirror.TouchControlService.instance?.onKey(key)
                }
                "type_text", "inject_text" -> {
                    val textToInject = json.optString("text")
                    if (textToInject.isNotEmpty()) {
                        val ok = io.mmirror.TouchControlService.instance?.injectText(textToInject) ?: false
                        if (ok) {
                            sendToast("✓ 텍스트가 스마트폰에 입력되었습니다: $textToInject")
                        } else {
                            sendToast("⚠️ 입력창(EditText)을 찾지 못했습니다. 폰의 검색창을 먼저 터치해 주세요.")
                        }
                    }
                }
                "launch_app" -> {
                    val pkg = json.optString("package")
                    try {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            context.startActivity(launchIntent)
                            Log.i(TAG, "✓ Launched $pkg via standard Intent")
                        } else {
                            sendToast("⚠️ 앱 실행 실패: 설치 여부를 확인해 주세요.")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to launch $pkg: ${e.message}")
                        sendToast("⚠️ 앱 실행 실패: ${e.message}")
                    }
                }
                "get_apps" -> {
                    sendAppList()
                }
                "set_screen_power" -> {
                    val powerOn = json.optBoolean("on", true)
                    Log.i(TAG, "💡 Received set_screen_power: on=$powerOn")
                    ScreenDimmerManager.setDimmed(!powerOn)
                    val resp = JSONObject().apply {
                        put("type", "toast")
                        put("message", if (powerOn) "☀️ 스마트폰 화면 밝기가 정상 복원되었습니다." else "🌙 스마트폰 화면이 최저 밝기(초절전 암전)로 전환되었습니다.")
                    }
                    dataChannel?.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(resp.toString().toByteArray(Charsets.UTF_8)), false))
                }
                "ping" -> {
                    val t = json.optDouble("t", 0.0)
                    val pong = JSONObject().apply {
                        put("type", "pong")
                        put("t", t)
                    }
                    dataChannel?.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(pong.toString().toByteArray(Charsets.UTF_8)), false))
                }
                "net_stats" -> {
                    val rtt = json.optDouble("rtt", 0.0)
                    val loss = json.optDouble("loss", 0.0)
                    val jitter = json.optDouble("jitter", 0.0)
                    val clientFps = json.optDouble("fps", 60.0)
                    val clientBitrate = json.optDouble("bitrate", 0.0)
                    handleNetworkStats(rtt, loss, jitter, clientFps, clientBitrate)
                }
                "get_abr_status" -> {
                    sendAbrStatus()
                }
                "set_abr" -> {
                    val enable = json.optBoolean("enabled", true)
                    setAdaptiveBitrateEnabled(enable)
                }
                "set_audio_mode" -> {
                    val enabled = json.optBoolean("enabled", false)
                    AppLogger.i(TAG, "🔊 Received set_audio_mode: enabled=$enabled")
                    io.mmirror.MediaProjectionService.instance?.setAudioStreamingEnabled(enabled)
                }
                "get_audio_mode" -> {
                    sendAudioModeStatus()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling control message: ${e.message}")
        }
    }

    fun setStandaloneMode(standalone: Boolean) {
        Log.i(TAG, "setStandaloneMode called: standalone=$standalone")
        this.isStandalone = standalone
    }

    private fun notifyModeChanged(standalone: Boolean) {
        try {
            val json = JSONObject().apply {
                put("type", "config")
                put("width", width)
                put("height", height)
                put("fps", fps)
                put("isStandalone", standalone)
            }
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            dataChannel?.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), false))
            sendSignaling(json.toString())
        } catch (_: Exception) {}
    }

    private fun sendToast(message: String) {
        try {
            val json = JSONObject().apply {
                put("type", "toast")
                put("message", message)
            }
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            dataChannel?.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), false))
        } catch (_: Exception) {}
    }

    private fun handleSignalingMessage(text: String) {
        mainHandler.post {
            handleSignalingMessageInternal(text)
        }
    }

    private fun handleSignalingMessageInternal(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            when (type) {
                "ready" -> {
                    val existingPc = peerConnection
                    val isConnected = existingPc != null && (
                        existingPc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED ||
                        existingPc.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED
                    )
                    if (isConnected) {
                        AppLogger.i(TAG, "⚡ 뷰어 ready 수신: 이미 P2P 연결 상태입니다. 키프레임 전송으로 화면 즉시 갱신.")
                        MediaProjectionService.instance?.requestKeyFrame()
                    } else {
                        val now = System.currentTimeMillis()
                        val state = existingPc?.signalingState()
                        if (state == PeerConnection.SignalingState.HAVE_LOCAL_OFFER && (now - lastOfferTimestamp < 2500L)) {
                            AppLogger.i(TAG, "⏳ 뷰어 ready 수신: 이미 Offer 발행 후 핸드셰이크 진행 중 (${now - lastOfferTimestamp}ms 전). 대기.")
                        } else {
                            AppLogger.i(TAG, "📥 테슬라 뷰어 ready 수신 -> 새 SDP Offer 생성")
                            createPeerConnectionAndOffer(force = false)
                        }
                    }
                }
                "reconnect" -> {
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastOfferTimestamp
                    if (elapsed < 1200L) {
                        AppLogger.i(TAG, "Debouncing rapid reconnect (${elapsed}ms) - scheduling delayed retry in ${1200L - elapsed}ms")
                        pendingReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                        val r = Runnable {
                            val pc = peerConnection
                            if (pc != null && (pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED ||
                                              pc.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED)) {
                                AppLogger.i(TAG, "⚡ Connection already established during cooldown! Cancelling delayed reconnect.")
                                return@Runnable
                            }
                            AppLogger.i(TAG, "Executing debounced reconnect after cooldown")
                            createPeerConnectionAndOffer(force = true)
                        }
                        pendingReconnectRunnable = r
                        mainHandler.postDelayed(r, 1200L - elapsed)
                    } else {
                        pendingReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                        AppLogger.i(TAG, "🔄 테슬라 뷰어 reconnect 요청 수신 -> 세션 재설정 및 새 Offer 생성")
                        createPeerConnectionAndOffer(force = true)
                    }
                }
                "answer" -> {
                    val sdp = json.optString("sdp")
                    val ansOfferId = json.optString("offerId")
                    if (ansOfferId.isNotEmpty() && currentOfferId.isNotEmpty() && ansOfferId != currentOfferId) {
                        AppLogger.w(TAG, "⏳ Stale answer ignored in WebRtcStreamer (offerId: $ansOfferId != $currentOfferId)")
                        return
                    }
                    val pc = peerConnection ?: return
                    val state = pc.signalingState()
                    if (state != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                        Log.d(TAG, "중복 또는 이미 처리된 SDP Answer 무시 (현재 상태: $state)")
                        return
                    }
                    val ufragMatch = Regex("a=ice-ufrag:([^\\r\\n]+)").find(sdp)
                    lastRemoteAnswerUfrag = ufragMatch?.groupValues?.get(1)?.trim()
                    AppLogger.i(TAG, "📥 테슬라 뷰어로부터 SDP Answer 수신 (offerId: $ansOfferId, ufrag: $lastRemoteAnswerUfrag), RemoteDescription 설정 중...")
                    val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                    pc.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            AppLogger.i(TAG, "🎉 [WEBRTC] RemoteDescription(Answer) 설정 완료! P2P 핸드셰이크 성공.")
                            configureVideoSenderParameters()
                            synchronized(pendingRemoteCandidates) {
                                for (c in pendingRemoteCandidates) {
                                    try {
                                        val candUfragMatch = Regex("ufrag\\s+([^\\s]+)").find(c.sdp)
                                        val candUfrag = candUfragMatch?.groupValues?.get(1)?.trim()
                                        if (candUfrag == null || lastRemoteAnswerUfrag == null || candUfrag == lastRemoteAnswerUfrag) {
                                            pc.addIceCandidate(c)
                                        } else {
                                            AppLogger.d(TAG, "Dropping buffered candidate with mismatched ufrag ($candUfrag != $lastRemoteAnswerUfrag)")
                                        }
                                    } catch (e: Exception) {
                                        AppLogger.w(TAG, "Failed to add buffered candidate: ${e.message}")
                                    }
                                }
                                pendingRemoteCandidates.clear()
                            }
                        }
                        override fun onCreateFailure(err: String?) { AppLogger.e(TAG, "❌ RemoteDescription createFailure: $err") }
                        override fun onSetFailure(err: String?) { AppLogger.e(TAG, "❌ RemoteDescription setFailure: $err") }
                    }, desc)
                }
                "candidate" -> {
                    val candidateObj = json.optJSONObject("candidate")
                    if (candidateObj != null) {
                        val sdp = candidateObj.optString("candidate")
                        val sdpMid = candidateObj.optString("sdpMid")
                        val sdpMLineIndex = candidateObj.optInt("sdpMLineIndex", 0)

                        val expectedUfrag = lastRemoteAnswerUfrag
                        if (sdp.isNotEmpty()) {
                            val candUfragMatch = Regex("ufrag\\s+([^\\s]+)").find(sdp)
                            val candUfrag = candUfragMatch?.groupValues?.get(1)?.trim()
                            if (candUfrag != null && expectedUfrag != null && candUfrag != expectedUfrag) {
                                AppLogger.d(TAG, "⏳ Ignoring stale remote candidate (ufrag: $candUfrag != $expectedUfrag)")
                                return
                            }
                        }

                        val cand = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                        val pc = peerConnection
                        if (pc != null && pc.remoteDescription != null) {
                            try {
                                pc.addIceCandidate(cand)
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "⚠️ ICE candidate add warning: ${e.message}")
                            }
                        } else {
                            synchronized(pendingRemoteCandidates) {
                                pendingRemoteCandidates.add(cand)
                            }
                            AppLogger.d(TAG, "⏳ Buffered viewer candidate until remoteDescription is set")
                        }

                        // 2. ★ 크롬 mDNS(.local) 마스킹 해제: 테슬라 브라우저가 보낸 .local 후보를 핫스팟 클라이언트 IP로 치환 주입!
                        if (sdp.contains(".local")) {
                            val hotspotIp = getHotspotIp()
                            val candidateIps = getCandidateTargetIps(hotspotIp)
                            var injectedCount = 0
                            for (teslaIp in candidateIps) {
                                val unmaskedSdp = sdp.replace(Regex("[a-zA-Z0-9-]+\\.local"), teslaIp)
                                val unmaskedCand = IceCandidate(sdpMid, sdpMLineIndex, unmaskedSdp)
                                if (pc != null && pc.remoteDescription != null) {
                                    try {
                                        pc.addIceCandidate(unmaskedCand)
                                        injectedCount++
                                    } catch (_: Exception) {}
                                } else {
                                    synchronized(pendingRemoteCandidates) {
                                        pendingRemoteCandidates.add(unmaskedCand)
                                    }
                                    injectedCount++
                                }
                            }
                            AppLogger.i(TAG, "📡 Tesla mDNS 후보 치환 주입 완료: 총 ${injectedCount}개 서브넷 IP 주입 완료 (핫스팟: $hotspotIp)")
                        }
                        AppLogger.d(TAG, "Added remote ICE candidate from viewer")
                    }
                }
                "request_keyframe" -> {
                    AppLogger.i(TAG, "🔑 시그널링 request_keyframe 수신 -> 키프레임 강제 생성")
                    io.mmirror.MediaProjectionService.instance?.requestKeyFrame()
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "⚠️ handleSignalingMessage error: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        if (instance == this) instance = null
        Log.i(TAG, "Stopping WebRtcStreamer...")

        try {
            ScreenDimmerManager.removeListener(dimmerListener)
            ScreenDimmerManager.setDimmed(false)
        } catch (_: Exception) {}

        try {
            sendSignaling("""{"type":"stream_stopped"}""")
        } catch (_: Exception) {}

        try {
            firebaseSignaling?.stop()
        } catch (_: Exception) {}
        firebaseSignaling = null
        pendingReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingReconnectRunnable = null
        proactiveReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        proactiveReconnectRunnable = null
        lastOfferTimestamp = 0L
        currentOfferId = ""
        lastRemoteAnswerUfrag = null
        hasSynthesizedHotspotCandidate = false
        hasNativeHotspotCandidate = false
        synchronized(pendingRemoteCandidates) {
            pendingRemoteCandidates.clear()
        }

        try {
            val stopMsg = ByteBuffer.wrap("""{"type":"stream_stopped"}""".toByteArray(Charsets.UTF_8))
            dataChannel?.send(DataChannel.Buffer(stopMsg, false))
        } catch (_: Exception) {}

        try {
            signalingWs?.close(1000, "Normal stop")
        } catch (_: Exception) {}
        signalingWs = null

        try {
            dataChannel?.close()
        } catch (_: Exception) {}
        dataChannel = null

        try {
            peerConnection?.close()
        } catch (_: Exception) {}
        peerConnection = null

        try {
            presentationCapturer?.stopCapture()
            presentationCapturer?.dispose()
        } catch (_: Exception) {}
        presentationCapturer = null

        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
        } catch (_: Exception) {}
        screenCapturer = null

        try {
            surfaceTextureHelper?.dispose()
        } catch (_: Exception) {}
        surfaceTextureHelper = null

        try {
            videoSource?.dispose()
        } catch (_: Exception) {}
        videoSource = null

        try {
            peerConnectionFactory?.dispose()
        } catch (_: Exception) {}
        peerConnectionFactory = null

        try {
            eglBase?.release()
        } catch (_: Exception) {}
        eglBase = null

        Log.i(TAG, "WebRtcStreamer stopped completely")
    }

    fun getMediaProjection(): MediaProjection? = mediaProjection ?: screenCapturer?.mediaProjection ?: presentationCapturer?.mediaProjection

    fun getVirtualDisplayId(): Int = -1

    fun changeResolution(newWidth: Int, newHeight: Int, newDensity: Int = density, newFps: Int = 60, @Suppress("UNUSED_PARAMETER") force: Boolean = false) {
        try {
            width = newWidth
            height = newHeight
            density = newDensity
            surfaceTextureHelper?.let { helper ->
                ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
                    helper.setTextureSize(newWidth, newHeight)
                }
            }
            videoSource?.adaptOutputFormat(newWidth, newHeight, newFps)
            presentationCapturer?.updateResolution(newWidth, newHeight, newDensity)
            screenCapturer?.changeCaptureFormat(newWidth, newHeight, newFps)
            sendConfig()
            Log.i(TAG, "✓ WebRTC capture resolution changed cleanly to ${newWidth}x${newHeight} (dpi=$newDensity)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to change WebRTC capture resolution: ${e.message}", e)
        }
    }

    @Volatile
    private var isDroppingGop = false
    @Volatile
    private var gopDropStartTime = 0L

    fun sendVideoPacket(data: ByteArray, isKeyFrame: Boolean) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        val buffered = dc.bufferedAmount()
        val now = android.os.SystemClock.elapsedRealtime()

        // 1. 소켓 버퍼가 512KB 이상 적체된 경우 네트워크 혼잡으로 판단하여 GOP 드롭 모드 진입
        if (buffered > 512 * 1024L) {
            if (!isDroppingGop) {
                isDroppingGop = true
                gopDropStartTime = now
                Log.w(TAG, "⚠️ WebRTC DataChannel 버퍼 과적체 (${buffered / 1024}KB) -> GOP 드롭 모드 진입")
            }
        }

        if (isDroppingGop) {
            val dropDuration = now - gopDropStartTime
            if (buffered < 128 * 1024L || dropDuration > 1200L) {
                // 버퍼가 128KB 이하로 원활하게 배출되었거나 1200ms 경과 시 GOP 드롭 해제 및 신규 키프레임 요청
                isDroppingGop = false
                Log.i(TAG, "✓ WebRTC DataChannel 버퍼 해소 (${buffered / 1024}KB) -> 정상 전송 재개 및 키프레임 갱신")
                io.mmirror.MediaProjectionService.instance?.requestKeyFrame()
            } else if (!isKeyFrame) {
                // 버퍼 해소 전까지 델타 프레임만 드롭 (신규 키프레임은 통과 허용)
                return
            }
        }

        try {
            val packet = ByteArray(1 + data.size)
            packet[0] = 0x01 // PKT_TYPE_VIDEO
            System.arraycopy(data, 0, packet, 1, data.size)
            val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(packet), true)
            dc.send(buffer)
        } catch (e: Exception) {
            AppLogger.w(TAG, "⚠️ sendVideoPacket 실패: ${e.message}")
        }
    }

    fun sendAudio(data: ByteArray, length: Int) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        if (dc.bufferedAmount() > 64 * 1024L) return // DataChannel 큐 과적체 시 프레임 드롭하여 버퍼 팽창 방지
        try {
            val packet = ByteArray(1 + length)
            packet[0] = 0x02 // PKT_TYPE_AUDIO
            System.arraycopy(data, 0, packet, 1, length)
            val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(packet), true)
            dc.send(buffer)
        } catch (e: Exception) {
            AppLogger.w(TAG, "⚠️ sendAudio 실패: ${e.message}")
        }
    }

    fun sendAudioModeStatus() {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        try {
            val enabled = io.mmirror.MediaProjectionService.instance?.isAudioStreamingEnabled() ?: false
            val resp = JSONObject().apply {
                put("type", "audio_mode_status")
                put("enabled", enabled)
            }
            val payload = resp.toString().toByteArray(Charsets.UTF_8)
            val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(payload), false)
            dc.send(buffer)
            AppLogger.i(TAG, "🔊 Sent audio mode status: enabled=$enabled")
        } catch (_: Exception) {}
    }

    fun sendGps(json: String) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        try {
            val payload = json.toByteArray(Charsets.UTF_8)
            val packet = ByteArray(1 + payload.size)
            packet[0] = 0x04 // PKT_TYPE_GPS
            System.arraycopy(payload, 0, packet, 1, payload.size)
            val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(packet), true)
            dc.send(buffer)
        } catch (_: Exception) {}
    }
}
