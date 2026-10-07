package io.mmirror.webrtc

import android.os.Handler
import android.os.Looper
import io.mmirror.AppLogger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Firebase Realtime Database REST/SSE 기반 서버리스 WebRTC 시그널링 관리자
 * 
 * - 추가 무거운 SDK 없이 순정 OkHttpClient로 실시간 SSE(Server-Sent Events) 스트림 처리
 * - 테슬라 웹 플레이어와 P2P SDP Offer/Answer/ICE Candidates 초경량 교환 (~5KB)
 * - 0MB 모바일 데이터 원칙 준수 (실제 60FPS 스트림은 핫스팟 로컬 UDP로 직결)
 */
class FirebaseSignalingManager(
    private val databaseUrl: String = "https://mplat-33044-default-rtdb.asia-southeast1.firebasedatabase.app",
    private val room: String = "default",
    private val client: OkHttpClient,
    private val onMessage: (String) -> Unit
) {
    companion object {
        private const val TAG = "FirebaseSignaling"
    }

    private val dbBaseUrl: String
        get() = "${databaseUrl.trimEnd('/')}/rooms/$room"

    private var sseCall: Call? = null
    @Volatile private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile private var currentOfferTimestamp = 0L
    @Volatile private var currentOfferId = ""
    @Volatile private var lastProcessedAnswerTimestamp = 0L
    @Volatile private var lastProcessedViewerReadyTimestamp = 0L
    @Volatile private var lastProcessedReconnectTimestamp = 0L

    // SSE 스트리밍 전용 OkHttpClient: 45초 readTimeout 설정 (Firebase 30초 keepalive 무응답 시 자동 재연결)
    private val sseClient by lazy {
        client.newBuilder()
            .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun start() {
        if (databaseUrl.isBlank() || isRunning) return
        isRunning = true
        android.util.Log.i(TAG, "🔥 Starting Firebase Realtime Database signaling for room '$room' ($databaseUrl)")
        cleanupRoomForNewSession()
        startSseStream()
    }

    fun stop() {
        isRunning = false
        currentOfferTimestamp = 0L
        currentOfferId = ""
        lastProcessedAnswerTimestamp = 0L
        handler.removeCallbacksAndMessages(null)
        try {
            val stopJson = JSONObject().apply {
                put("publisher_status", JSONObject().put("online", false).put("timestamp", System.currentTimeMillis()))
                put("offer", JSONObject.NULL)
                put("answer", JSONObject.NULL)
                put("phone_candidates", JSONObject.NULL)
                put("viewer_candidates", JSONObject.NULL)
                put("stream_stopped", JSONObject().put("stopped", true).put("timestamp", System.currentTimeMillis()))
            }
            val putPub = Request.Builder()
                .url("$dbBaseUrl.json")
                .patch(stopJson.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(putPub).enqueue(EmptyCallback)
        } catch (_: Exception) {}
        try {
            sseCall?.cancel()
        } catch (_: Exception) {}
        sseCall = null
    }

    private fun startSseStream() {
        if (!isRunning || databaseUrl.isBlank()) return
        val url = "$dbBaseUrl.json"
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "text/event-stream")
            .build()

        sseCall = sseClient.newCall(request)
        sseCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (!isRunning || call.isCanceled()) return
                android.util.Log.d(TAG, "Firebase SSE connection idle/closed: ${e.message}")
                handler.postDelayed({
                    if (isRunning) startSseStream()
                }, 1000)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!isRunning || call.isCanceled()) {
                    response.close()
                    return
                }

                if (!response.isSuccessful) {
                    android.util.Log.d(TAG, "Firebase SSE response HTTP ${response.code}. Retrying in 1s...")
                    response.close()
                    handler.postDelayed({
                        if (isRunning) startSseStream()
                    }, 1000)
                    return
                }

                val body = response.body
                if (body == null) {
                    response.close()
                    handler.postDelayed({
                        if (isRunning) startSseStream()
                    }, 1000)
                    return
                }

                try {
                    val reader = BufferedReader(InputStreamReader(body.byteStream()))
                    var line: String? = null

                    while (isRunning && !call.isCanceled() && reader.readLine().also { line = it } != null) {
                        val currentLine = line?.trim() ?: continue
                        if (currentLine.startsWith("data:")) {
                            val data = currentLine.removePrefix("data:").trim()
                            if (data.isNotEmpty() && data != "null") {
                                handleSseData(data)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning && !call.isCanceled()) {
                        android.util.Log.d(TAG, "Firebase SSE read finished: ${e.message}")
                        handler.postDelayed({
                            if (isRunning) startSseStream()
                        }, 1000)
                    }
                } finally {
                    try {
                        response.close()
                    } catch (_: Exception) {}
                }
            }
        })
    }

    fun cleanupRoomForNewSession() {
        if (!isRunning || databaseUrl.isBlank()) return
        currentOfferTimestamp = 0L
        lastProcessedAnswerTimestamp = 0L
        try {
            val cleanupJson = JSONObject().apply {
                put("publisher_status", JSONObject().put("online", true).put("timestamp", System.currentTimeMillis()))
                put("offer", JSONObject.NULL)
                put("answer", JSONObject.NULL)
                put("phone_candidates", JSONObject.NULL)
                put("viewer_candidates", JSONObject.NULL)
                put("reconnect_request", JSONObject.NULL)
                put("stream_stopped", JSONObject.NULL)
            }
            val req = Request.Builder()
                .url("$dbBaseUrl.json")
                .patch(cleanupJson.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).enqueue(EmptyCallback)

            android.util.Log.i(TAG, "🧹 Cleaned up room session & set publisher_status online (Atomic PATCH)")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "cleanupRoomForNewSession notice: ${e.message}")
        }
    }

    private fun handleSseData(dataStr: String) {
        try {
            val json = JSONObject(dataStr)
            val path = json.optString("path", "/")
            val data = json.opt("data")

            if (path == "/" && data is JSONObject) {
                // 루트 스냅샷 파싱: 뷰어 대기 신호 수용 (기발행 Answer 및 후보는 현재 유효한 Offer 발행 이후 것만 수용)
                val answerObj = data.optJSONObject("answer")
                if (answerObj != null && (currentOfferId.isNotEmpty() || currentOfferTimestamp > 0L)) {
                    val ansOfferId = answerObj.optString("offerId", "")
                    if (ansOfferId.isNotEmpty() && currentOfferId.isNotEmpty() && ansOfferId != currentOfferId) {
                        android.util.Log.d(TAG, "⏳ Ignoring answer for mismatched offerId in snapshot ($ansOfferId != $currentOfferId)")
                    } else {
                        val ts = answerObj.optLong("timestamp", 0L)
                        if (ts == 0L || ts != lastProcessedAnswerTimestamp) {
                            if (ts > 0L) lastProcessedAnswerTimestamp = ts
                            android.util.Log.i(TAG, "📥 Fresh answer detected in root snapshot (offerId: $ansOfferId, ts=$ts)")
                            onMessage(answerObj.toString())
                        }
                    }
                }

                val viewerCands = data.optJSONObject("viewer_candidates")
                if (viewerCands != null && (currentOfferId.isNotEmpty() || currentOfferTimestamp > 0L)) {
                    val keys = viewerCands.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val c = viewerCands.optJSONObject(k)
                        if (c != null && c.has("candidate")) {
                            onMessage(JSONObject().put("type", "candidate").put("candidate", c).toString())
                        }
                    }
                }

                var reconnectHandled = false
                if (data.has("reconnect_request")) {
                    val rr = data.optJSONObject("reconnect_request")
                    val ts = rr?.optLong("timestamp") ?: data.optLong("reconnect_request", 0L)
                    val age = Math.abs(System.currentTimeMillis() - ts)
                    if (ts > 0L && ts != lastProcessedReconnectTimestamp && age < 60_000L) {
                        lastProcessedReconnectTimestamp = ts
                        reconnectHandled = true
                        android.util.Log.i(TAG, "🔄 Fresh reconnect_request detected in room snapshot (age: ${age}ms, ts=$ts)")
                        onMessage(JSONObject().put("type", "reconnect").put("timestamp", ts).toString())
                    }
                }

                if (!reconnectHandled && data.has("viewer_ready")) {
                    val vr = data.optJSONObject("viewer_ready")
                    val isReady = vr?.optBoolean("ready", false) ?: (data.opt("viewer_ready") == true)
                    val ts = vr?.optLong("timestamp") ?: 0L
                    val age = Math.abs(System.currentTimeMillis() - ts)
                    if (isReady && (ts == 0L || age < 120_000L)) {
                        lastProcessedViewerReadyTimestamp = ts
                        android.util.Log.i(TAG, "📥 Fresh viewer_ready detected in room snapshot (age: ${age}ms)")
                        onMessage(JSONObject().put("type", "ready").put("timestamp", ts).toString())
                    }
                }
            } else if (path.startsWith("/answer") && data is JSONObject) {
                val ansOfferId = data.optString("offerId", "")
                if (ansOfferId.isNotEmpty() && currentOfferId.isNotEmpty() && ansOfferId != currentOfferId) {
                    android.util.Log.d(TAG, "⏳ Ignoring stale answer via SSE (mismatched offerId: $ansOfferId != $currentOfferId)")
                    return
                }
                val ts = data.optLong("timestamp", 0L)
                if (ts > 0L && ts == lastProcessedAnswerTimestamp) return
                if (ts > 0L) lastProcessedAnswerTimestamp = ts

                android.util.Log.i(TAG, "📥 Realtime answer received via SSE (offerId: $ansOfferId, ts=$ts)")
                onMessage(data.toString())
            } else if (path.startsWith("/viewer_candidates") && data is JSONObject) {
                if (data.has("candidate")) {
                    onMessage(JSONObject().put("type", "candidate").put("candidate", data).toString())
                } else {
                    val keys = data.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val c = data.optJSONObject(k)
                        if (c != null && c.has("candidate")) {
                            onMessage(JSONObject().put("type", "candidate").put("candidate", c).toString())
                        }
                    }
                }
            } else if (path.startsWith("/viewer_ready")) {
                var isReady = true
                var ts = 0L
                if (data is JSONObject) {
                    isReady = data.optBoolean("ready", true)
                    ts = data.optLong("timestamp", 0L)
                } else if (data is Boolean) {
                    isReady = data
                } else if (data is Number) {
                    ts = data.toLong()
                }

                if (ts > 0L && ts == lastProcessedViewerReadyTimestamp) return
                if (ts > 0L) lastProcessedViewerReadyTimestamp = ts

                if (isReady) {
                    android.util.Log.i(TAG, "📥 Realtime viewer_ready received via SSE: path=$path, ts=$ts")
                    onMessage(JSONObject().put("type", "ready").put("timestamp", ts).toString())
                }
            } else if (path.startsWith("/reconnect_request")) {
                var ts = 0L
                if (data is JSONObject) {
                    ts = data.optLong("timestamp", 0L)
                } else if (data is Number) {
                    ts = data.toLong()
                }

                if (ts > 0L && ts == lastProcessedReconnectTimestamp) return
                if (ts > 0L) lastProcessedReconnectTimestamp = ts

                android.util.Log.i(TAG, "🔄 Realtime reconnect_request received via SSE: path=$path, ts=$ts")
                onMessage(JSONObject().put("type", "reconnect").put("timestamp", ts).toString())
            } else if (path.startsWith("/keyframe_request")) {
                onMessage(JSONObject().put("type", "request_keyframe").toString())
            }
        } catch (e: Exception) {
            android.util.Log.d(TAG, "Firebase SSE parse notice: ${e.message}")
        }
    }

    fun send(message: String) {
        if (!isRunning || databaseUrl.isBlank()) return
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            when (type) {
                "offer" -> {
                    val offerTs = json.optLong("timestamp", System.currentTimeMillis())
                    currentOfferTimestamp = if (offerTs > 0L) offerTs else System.currentTimeMillis()
                    val offerId = json.optString("offerId", "").ifEmpty { currentOfferTimestamp.toString() }
                    currentOfferId = offerId
                    lastProcessedAnswerTimestamp = 0L
                    val patchJson = JSONObject().apply {
                        put("offer", JSONObject(message))
                        put("answer", JSONObject.NULL)
                        put("reconnect_request", JSONObject.NULL)
                    }
                    val req = Request.Builder()
                        .url("$dbBaseUrl.json")
                        .patch(patchJson.toString().toRequestBody(jsonMediaType))
                        .build()
                    client.newCall(req).enqueue(EmptyCallback)
                    android.util.Log.i(TAG, "🧹 Published new Offer (Atomic PATCH, offerId=$currentOfferId, offerTs=$currentOfferTimestamp)")
                }
                "candidate" -> {
                    val candidateObj = json.optJSONObject("candidate") ?: return
                    val candStr = candidateObj.optString("candidate", "")
                    if (candStr.contains(" tcp ")) {
                        return
                    }
                    val req = Request.Builder()
                        .url("$dbBaseUrl/phone_candidates.json")
                        .post(candidateObj.toString().toRequestBody(jsonMediaType))
                        .build()
                    client.newCall(req).enqueue(EmptyCallback)
                }
                "config" -> {
                    val req = Request.Builder()
                        .url("$dbBaseUrl/config.json")
                        .put(message.toRequestBody(jsonMediaType))
                        .build()
                    client.newCall(req).enqueue(EmptyCallback)
                }
                "stream_stopped" -> {
                    val stopObj = JSONObject().put("stopped", true).put("timestamp", System.currentTimeMillis())
                    val req = Request.Builder()
                        .url("$dbBaseUrl/stream_stopped.json")
                        .put(stopObj.toString().toRequestBody(jsonMediaType))
                        .build()
                    client.newCall(req).enqueue(EmptyCallback)

                    val delCandidates = Request.Builder().url("$dbBaseUrl/phone_candidates.json").delete().build()
                    val delViewerCandidates = Request.Builder().url("$dbBaseUrl/viewer_candidates.json").delete().build()
                    val delOffer = Request.Builder().url("$dbBaseUrl/offer.json").delete().build()
                    client.newCall(delCandidates).enqueue(EmptyCallback)
                    client.newCall(delViewerCandidates).enqueue(EmptyCallback)
                    client.newCall(delOffer).enqueue(EmptyCallback)
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to send Firebase signaling message: ${e.message}")
        }
    }

    fun clearSessionForNewOffer() {
        currentOfferTimestamp = 0L
        currentOfferId = ""
        lastProcessedAnswerTimestamp = 0L
        try {
            val patchJson = JSONObject().apply {
                put("phone_candidates", JSONObject.NULL)
                put("viewer_candidates", JSONObject.NULL)
                put("answer", JSONObject.NULL)
                put("reconnect_request", JSONObject.NULL)
            }
            val req = Request.Builder()
                .url("$dbBaseUrl.json")
                .patch(patchJson.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).enqueue(EmptyCallback)
            android.util.Log.i(TAG, "🧹 Pre-cleared session room before creating new PeerConnection")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to clear session: ${e.message}")
        }
    }

    private object EmptyCallback : Callback {
        override fun onFailure(call: Call, e: java.io.IOException) {}
        override fun onResponse(call: Call, response: Response) { response.close() }
    }
}
