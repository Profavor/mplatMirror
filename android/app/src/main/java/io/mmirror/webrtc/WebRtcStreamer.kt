package io.mmirror.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import io.mmirror.TouchControlService
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*

class WebRtcStreamer(
    private val context: Context,
    private val resultData: Intent,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 60,
    private val signalingUrl: String = "wss://mdm.mplat.store:8088/webrtc/signal?role=publisher&room=default"
) {
    companion object {
        private const val TAG = "WebRtcStreamer"
    }

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private val okHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()
    private var signalingWs: WebSocket? = null
    @Volatile private var isRunning = false
    @Volatile private var isSignalingConnected = false

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starting WebRtcStreamer (${width}x${height} @ ${fps}fps)...")

        try {
            initPeerConnectionFactory()
            initVideoCapturer()
            connectSignaling()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start WebRtcStreamer: ${e.message}", e)
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
            true  /* enableH264HighProfile */
        )
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun initVideoCapturer() {
        val pcf = peerConnectionFactory ?: return
        val egl = eglBase ?: return

        screenCapturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "ScreenCapturerAndroid onStop callback triggered")
            }
        })

        surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCaptureHelper", egl.eglBaseContext)
        val vSource = pcf.createVideoSource(screenCapturer!!.isScreencast)
        videoSource = vSource

        screenCapturer!!.initialize(surfaceTextureHelper, context, vSource.capturerObserver)
        screenCapturer!!.startCapture(width, height, fps)

        val vTrack = pcf.createVideoTrack("video_track_0", vSource)
        vTrack.setEnabled(true)
        videoTrack = vTrack
        Log.i(TAG, "VideoTrack created and capture started (${width}x${height})")
    }

    private fun connectSignaling() {
        if (!isRunning) return
        Log.i(TAG, "Connecting to WebRTC Signaling: $signalingUrl")

        val request = Request.Builder()
            .url(signalingUrl)
            .build()

        signalingWs = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "🟢 WebRTC Signaling WebSocket Connected")
                isSignalingConnected = true
                sendConfig()
                createPeerConnectionAndOffer()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignalingMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Signaling WebSocket failure: ${t.message}")
                isSignalingConnected = false
                if (isRunning) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isRunning && !isSignalingConnected) {
                            connectSignaling()
                        }
                    }, 3000)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Signaling WebSocket closed: $reason")
                isSignalingConnected = false
                if (isRunning) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isRunning && !isSignalingConnected) {
                            connectSignaling()
                        }
                    }, 3000)
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
            }
            signalingWs?.send(json.toString())
        } catch (_: Exception) {}
    }

    private fun createPeerConnectionAndOffer() {
        val pcf = peerConnectionFactory ?: return
        val vTrack = videoTrack ?: return

        peerConnection?.close()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pcObserver = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.i(TAG, "📡 Generated local ICE candidate: ${candidate.sdp}")
                val json = JSONObject().apply {
                    put("type", "candidate")
                    put("candidate", JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                }
                signalingWs?.send(json.toString())
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "⚡ WebRTC Connection State: $newState")
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    Log.i(TAG, "🎉 [WEBRTC] Direct P2P Connected to Tesla! 0MB Local Streaming Active!")
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel) {
                setupDataChannel(dc)
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        val pc = pcf.createPeerConnection(rtcConfig, pcObserver) ?: return
        peerConnection = pc

        pc.addTrack(vTrack, listOf("stream_mmirror"))

        val dcInit = DataChannel.Init().apply {
            ordered = true
        }
        val dc = pc.createDataChannel("control", dcInit)
        setupDataChannel(dc)

        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                Log.i(TAG, "SDP Offer created successfully")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.i(TAG, "LocalDescription set, sending Offer to signaling...")
                        val json = JSONObject().apply {
                            put("type", "offer")
                            put("sdp", desc.description)
                        }
                        signalingWs?.send(json.toString())
                    }
                    override fun onCreateFailure(err: String?) { Log.e(TAG, "setLocalDescription createFailure: $err") }
                    override fun onSetFailure(err: String?) { Log.e(TAG, "setLocalDescription setFailure: $err") }
                }, desc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { Log.e(TAG, "createOffer failure: $err") }
            override fun onSetFailure(err: String?) { Log.e(TAG, "createOffer setFailure: $err") }
        }, sdpConstraints)
    }

    private fun setupDataChannel(dc: DataChannel) {
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.i(TAG, "💬 DataChannel State changed: ${dc.state()}")
                if (dc.state() == DataChannel.State.OPEN) {
                    sendAppList()
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

    fun sendAppList() {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        try {
            val pm = context.packageManager
            val knownApps = listOf(
                Pair("티맵", "com.skt.tmap.ku"),
                Pair("카카오내비", "com.locnall.KimGiSa"),
                Pair("네이버지도", "com.nhn.android.nmap"),
                Pair("유튜브", "com.google.android.youtube"),
                Pair("넷플릭스", "com.netflix.mediaclient"),
                Pair("YT 뮤직", "com.google.android.apps.youtube.music"),
                Pair("멜론", "com.iloen.melon"),
                Pair("스포티파이", "com.spotify.music"),
                Pair("크롬", "com.android.chrome"),
                Pair("쿠팡플레이", "com.coupang.mobile.play"),
                Pair("티빙", "net.cj.cjenm.tving"),
                Pair("웨이브", "kr.co.captv.pooqV2")
            )
            val jsonArray = org.json.JSONArray()
            for ((name, pkg) in knownApps) {
                val isInstalled = try {
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (_: Exception) {
                    false
                }
                val item = JSONObject().apply {
                    put("name", name)
                    put("package", pkg)
                    put("installed", isInstalled)
                }
                jsonArray.put(item)
            }
            val resp = JSONObject().apply {
                put("type", "app_list")
                put("apps", jsonArray)
                put("shizuku", io.mmirror.adb.AdbTouchManager.isShizukuAvailable)
                put("virtualDisplayId", getVirtualDisplayId())
            }
            val payload = resp.toString().toByteArray(Charsets.UTF_8)
            val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(payload), false)
            dc.send(buffer)
            Log.i(TAG, "Sent app_list to viewer (${jsonArray.length()} apps, vd=${getVirtualDisplayId()})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send app list: ${e.message}")
        }
    }

    private fun handleControlMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            when (type) {
                "touch" -> {
                    val action = json.optString("action")
                    val id = json.optInt("id", 0)
                    val x = json.optDouble("x", 0.0).toFloat()
                    val y = json.optDouble("y", 0.0).toFloat()
                    val mode = json.optString("mode", "standalone")
                    val targetDisplayId = if (mode == "mirror") -1 else getVirtualDisplayId()

                    if (io.mmirror.adb.AdbTouchManager.isShizukuAvailable) {
                        io.mmirror.adb.AdbTouchManager.onTouch(action, id, x, y, width, height, targetDisplayId)
                    } else {
                        TouchControlService.instance?.onTouch(action, id, x, y)
                    }
                }
                "key" -> {
                    val key = json.optString("key")
                    val mode = json.optString("mode", "standalone")
                    val targetDisplayId = if (mode == "mirror") -1 else getVirtualDisplayId()
                    val keyCode = when (key.uppercase()) {
                        "BACK" -> 4
                        "HOME" -> 3
                        "RECENTS" -> 187
                        "VOLUME_UP" -> 24
                        "VOLUME_DOWN" -> 25
                        else -> 0
                    }
                    if (keyCode != 0 && io.mmirror.adb.AdbTouchManager.isShizukuAvailable) {
                        io.mmirror.adb.AdbTouchManager.injectKey(keyCode, targetDisplayId)
                    } else {
                        TouchControlService.instance?.onKey(key)
                    }
                }
                "launch_app" -> {
                    val pkg = json.optString("package")
                    val mode = json.optString("mode", "standalone")
                    val targetDisplayId = if (mode == "mirror") -1 else getVirtualDisplayId()
                    Log.i(TAG, "Launching $pkg on display $targetDisplayId (mode=$mode)")
                    if (io.mmirror.adb.AdbTouchManager.isShizukuAvailable) {
                        io.mmirror.adb.AdbTouchManager.launchAppOnDisplay(context, pkg, targetDisplayId)
                    } else {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                        }
                    }
                }
                "get_apps" -> {
                    sendAppList()
                }
                "command" -> {
                    val cmd = json.optString("cmd")
                    TouchControlService.instance?.onCommand(cmd)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling control message: ${e.message}")
        }
    }

    private fun handleSignalingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            when (type) {
                "ready" -> {
                    Log.i(TAG, "Viewer notified ready, generating new SDP Offer")
                    createPeerConnectionAndOffer()
                }
                "answer" -> {
                    val sdp = json.optString("sdp")
                    Log.i(TAG, "Received SDP Answer from viewer")
                    val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.i(TAG, "RemoteDescription set successfully! P2P negotiation complete.")
                        }
                        override fun onCreateFailure(err: String?) { Log.e(TAG, "RemoteDescription createFailure: $err") }
                        override fun onSetFailure(err: String?) { Log.e(TAG, "RemoteDescription setFailure: $err") }
                    }, desc)
                }
                "candidate" -> {
                    val candidateObj = json.optJSONObject("candidate")
                    if (candidateObj != null) {
                        val sdp = candidateObj.optString("candidate")
                        val sdpMid = candidateObj.optString("sdpMid")
                        val sdpMLineIndex = candidateObj.optInt("sdpMLineIndex", 0)
                        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                        peerConnection?.addIceCandidate(iceCandidate)
                        Log.i(TAG, "Added remote ICE candidate from viewer")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleSignalingMessage error: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        Log.i(TAG, "Stopping WebRtcStreamer...")

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

    fun getMediaProjection(): MediaProjection? = screenCapturer?.mediaProjection

    fun getVirtualDisplayId(): Int {
        return try {
            val field = ScreenCapturerAndroid::class.java.getDeclaredField("virtualDisplay")
            field.isAccessible = true
            val vd = field.get(screenCapturer) as? android.hardware.display.VirtualDisplay
            vd?.display?.displayId ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    fun changeResolution(newWidth: Int, newHeight: Int, newFps: Int = 60) {
        try {
            screenCapturer?.changeCaptureFormat(newWidth, newHeight, newFps)
            Log.i(TAG, "WebRTC capture resolution changed to ${newWidth}x${newHeight}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to change WebRTC capture resolution: ${e.message}")
        }
    }

    fun sendAudio(data: ByteArray, length: Int) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        if (dc.bufferedAmount() > 128 * 1024L) return // 버퍼 폭주 방지

        try {
            val packet = ByteArray(1 + length)
            packet[0] = 0x02 // PKT_TYPE_AUDIO
            System.arraycopy(data, 0, packet, 1, length)
            val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(packet), true)
            dc.send(buffer)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send audio via DataChannel: ${e.message}")
        }
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
