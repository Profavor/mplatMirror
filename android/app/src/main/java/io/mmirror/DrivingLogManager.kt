package io.mmirror

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TripSummary(
    val todayDistanceKm: Double,
    val durationMin: Int,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val routeDesc: String,
    val isLive: Boolean
)

class DrivingLogManager(private val context: Context) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    var isTracking = false
        private set
    /** 차량(WebRTC 피어)이 실제로 연결되어 있는 동안만 true */
    @Volatile
    var isPeerConnected = false
        private set
    private var tripId: String = ""
    var startTimeMillis: Long = 0L
        private set
    var lastLocation: Location? = null
        private set
    var totalDistanceMeters: Double = 0.0
        private set
    var maxSpeedKmh: Float = 0.0f
        private set
    var speedSumKmh: Double = 0.0
        private set
    var speedCount: Int = 0
        private set

    private val pathPoints = mutableListOf<JSONObject>()

    companion object {
        private const val TAG = "DrivingLogManager"
        @Volatile
        var currentInstance: DrivingLogManager? = null
            private set

        fun getTripSummary(context: Context): TripSummary {
            val live = currentInstance
            if (live != null && live.isTracking) {
                val distKm = live.totalDistanceMeters / 1000.0
                val durSec = (System.currentTimeMillis() - live.startTimeMillis) / 1000L
                val durMin = (durSec / 60).toInt().coerceAtLeast(0)
                val avgSpeed = if (live.speedCount > 0) (live.speedSumKmh / live.speedCount).toFloat() else 0.0f
                return TripSummary(
                    todayDistanceKm = distKm,
                    durationMin = durMin,
                    avgSpeedKmh = avgSpeed,
                    maxSpeedKmh = live.maxSpeedKmh,
                    routeDesc = "실시간 GPS 추적 중",
                    isLive = true
                )
            }

            val prefs = context.getSharedPreferences("triplog_prefs", Context.MODE_PRIVATE)
            val distKm = prefs.getFloat("last_distance_km", 18.4f).toDouble()
            val durSec = prefs.getLong("last_duration_sec", 1620L) // 27분
            val durMin = (durSec / 60).toInt().coerceAtLeast(1)
            val avgSpeed = prefs.getFloat("last_avg_speed_kmh", 42.5f)
            val maxSpeed = prefs.getFloat("last_max_speed_kmh", 88.0f)
            val route = prefs.getString("last_route", "서울 강남 ➔ 경기 성남") ?: "서울 강남 ➔ 경기 성남"

            return TripSummary(
                todayDistanceKm = distKm,
                durationMin = durMin,
                avgSpeedKmh = avgSpeed,
                maxSpeedKmh = maxSpeed,
                routeDesc = route,
                isLive = false
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startTrip() {
        if (isTracking) return

        tripId = UUID.randomUUID().toString()
        startTimeMillis = System.currentTimeMillis()
        totalDistanceMeters = 0.0
        maxSpeedKmh = 0.0f
        speedSumKmh = 0.0
        speedCount = 0
        pathPoints.clear()
        lastLocation = null

        isTracking = true
        currentInstance = this
        try {
            lastLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        } catch (_: Exception) {}
        Log.i(TAG, "Trip prepared (waiting for peer connection): $tripId")
        // GPS 추적은 onPeerConnected()에서 실제 시작
    }

    fun broadcastGps(lat: Double, lng: Double, speed: Float, heading: Float, dist: Double, duration: Long) {
        // 1. 레거시 로컬 웹소켓 서버 전송
        NativeBridge.sendGpsData(lat, lng, speed, heading, dist, duration)

        // 2. 테슬라 WebRTC DataChannel (0x04 PKT_TYPE_GPS) 실시간 전송
        try {
            val gpsJson = JSONObject().apply {
                put("lat", lat)
                put("lng", lng)
                put("speed_kmh", speed)
                put("heading", heading)
                put("trip_distance_meters", dist)
                put("duration_seconds", duration)
            }.toString()
            io.mmirror.webrtc.WebRtcStreamer.instance?.sendGps(gpsJson)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun sendLastKnownLocation() {
        try {
            val lastKnown = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                ?: lastLocation

            if (lastKnown != null) {
                lastLocation = lastKnown
                val speed = if (lastKnown.hasSpeed()) lastKnown.speed * 3.6f else 0.0f
                val bearing = if (lastKnown.hasBearing()) lastKnown.bearing else 0.0f
                Log.i(TAG, "📍 즉시 마지막 위치 전송 (WebRTC + WS): lat=${lastKnown.latitude}, lng=${lastKnown.longitude}")
                broadcastGps(
                    lastKnown.latitude,
                    lastKnown.longitude,
                    speed,
                    bearing,
                    totalDistanceMeters,
                    0L
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendLastKnownLocation failed: ${e.message}")
        }
    }

    /**
     * 차량(WebRTC 피어)이 연결되었을 때 호출 — GPS 추적 시작
     * 차량 미러링 상태일 때만 주행 기록을 남김
     */
    @SuppressLint("MissingPermission")
    fun onPeerConnected() {
        if (isPeerConnected) return
        isPeerConnected = true

        if (!isTracking) {
            // startTrip()이 아직 안 호출된 경우 자동 시작
            startTrip()
        }

        // 연결 시점을 새 trip 시작점으로 리셋
        startTimeMillis = System.currentTimeMillis()
        totalDistanceMeters = 0.0
        maxSpeedKmh = 0.0f
        speedSumKmh = 0.0
        speedCount = 0
        pathPoints.clear()

        Log.i(TAG, "🚗 Peer connected — GPS tracking started: $tripId")

        // 1. 연결 즉시 마지막 측정 위치(현재위치) 브로드캐스트 (정차/실내에서도 즉시 실제 날씨 반영)
        sendLastKnownLocation()

        // 2. 실시간 위치 추적 등록 (GPS 및 기지국/Wi-Fi 네트워크 프로바이더 동시 등록, 정차 중에도 수신)
        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, // 1초 간격
                    0.0f,  // 정차 중에도 수신 가능하도록 0m 설정
                    this
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request GPS updates", e)
        }

        try {
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L, // 2초 간격
                    0.0f,
                    this
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request Network location updates", e)
        }
    }

    /**
     * 차량(WebRTC 피어)이 연결 해제되었을 때 호출 — GPS 추적 중지 및 기록 저장
     */
    fun onPeerDisconnected() {
        if (!isPeerConnected) return
        isPeerConnected = false

        Log.i(TAG, "🚗 Peer disconnected — GPS tracking stopped")

        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing location updates", e)
        }

        saveCurrentTrip()
    }

    fun stopTrip() {
        if (!isTracking) return
        isTracking = false
        isPeerConnected = false
        if (currentInstance == this) {
            currentInstance = null
        }

        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing location updates", e)
        }

        saveCurrentTrip()
        Log.i(TAG, "Trip tracking stopped: $tripId")
    }

    override fun onLocationChanged(location: Location) {
        if (!isTracking || !isPeerConnected) return

        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0.0f
        val heading = if (location.hasBearing()) location.bearing else 0.0f

        val last = lastLocation
        if (last != null) {
            val dist = location.distanceTo(last)
            if (dist > 1.0f && speedKmh > 1.5f) {
                totalDistanceMeters += dist
            }
        }
        lastLocation = location

        if (speedKmh > maxSpeedKmh) {
            maxSpeedKmh = speedKmh
        }
        if (speedKmh > 1.0f) {
            speedSumKmh += speedKmh
            speedCount++
        }

        val durationSec = (System.currentTimeMillis() - startTimeMillis) / 1000L

        // 실시간 GPS 브로드캐스트 (WebRTC DataChannel + WebSocket)
        broadcastGps(
            location.latitude,
            location.longitude,
            speedKmh,
            heading,
            totalDistanceMeters,
            durationSec
        )

        // 궤적 포인트 기록 (1초 단위)
        val point = JSONObject().apply {
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("speed", speedKmh)
            put("time", System.currentTimeMillis())
        }
        pathPoints.add(point)
    }

    private fun saveCurrentTrip() {
        if (pathPoints.isEmpty() && totalDistanceMeters < 50.0) {
            Log.i(TAG, "Trip too short to save (<50m)")
            return
        }

        val endTimeMillis = System.currentTimeMillis()
        val durationSec = (endTimeMillis - startTimeMillis) / 1000L
        val avgSpeedKmh = if (speedCount > 0) (speedSumKmh / speedCount).toFloat() else 0.0f
        val distanceKm = totalDistanceMeters / 1000.0

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

        val tripJson = JSONObject().apply {
            put("id", tripId)
            put("start_time", dateFormat.format(Date(startTimeMillis)))
            put("end_time", dateFormat.format(Date(endTimeMillis)))
            put("distance_km", distanceKm)
            put("duration_sec", durationSec)
            put("avg_speed_kmh", avgSpeedKmh)
            put("max_speed_kmh", maxSpeedKmh)
            put("path", JSONArray(pathPoints))
        }

        NativeBridge.saveTripRecord(tripJson.toString())
        try {
            val prefs = context.getSharedPreferences("triplog_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat("last_distance_km", distanceKm.toFloat())
                .putLong("last_duration_sec", durationSec)
                .putFloat("last_avg_speed_kmh", avgSpeedKmh)
                .putFloat("last_max_speed_kmh", maxSpeedKmh)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist trip prefs", e)
        }
        Log.i(TAG, "Trip record saved: $distanceKm km in $durationSec sec")
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
