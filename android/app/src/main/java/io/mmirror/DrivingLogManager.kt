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

class DrivingLogManager(private val context: Context) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var isTracking = false
    private var tripId: String = ""
    private var startTimeMillis: Long = 0L
    private var lastLocation: Location? = null
    private var totalDistanceMeters: Double = 0.0
    private var maxSpeedKmh: Float = 0.0f
    private var speedSumKmh: Double = 0.0
    private var speedCount: Int = 0

    private val pathPoints = mutableListOf<JSONObject>()

    companion object {
        private const val TAG = "DrivingLogManager"
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
        Log.i(TAG, "Starting trip tracking: $tripId")

        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, // 1초 간격
                    1.0f,  // 1m 최소 변화
                    this
                )
            } else if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    1.0f,
                    this
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location updates", e)
        }
    }

    fun stopTrip() {
        if (!isTracking) return
        isTracking = false

        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing location updates", e)
        }

        saveCurrentTrip()
        Log.i(TAG, "Trip tracking stopped: $tripId")
    }

    override fun onLocationChanged(location: Location) {
        if (!isTracking) return

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

        // 실시간 GPS 브로드캐스트
        NativeBridge.sendGpsData(
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
        Log.i(TAG, "Trip record saved: $distanceKm km in $durationSec sec")
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
