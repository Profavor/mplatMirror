package io.mmirror

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * mMirror 스마트폰 모바일 위치 매니저
 *
 * [핵심 원칙]
 * 1. FOREGROUND_SERVICE_LOCATION 권한 일체 미사용 (구글 플레이 FGS Location 심사 거절 리스크 0%)
 * 2. 포그라운드 액티비티(MainActivity) 및 시스템 캐시(LastKnownLocation) 기반 1회 원샷 동기화
 * 3. 0MB 모바일 데이터 원칙: 로컬 Wi-Fi WebRTC DataChannel을 통해 차량 브라우저로만 좌표 전달
 * 4. 배터리 소모 0%: 백그라운드 센서 상시 가동 없이 안전하게 위치 획득
 */
object LocationServiceManager {
    private const val TAG = "LocationServiceManager"

    @Volatile
    private var cachedLocation: Location? = null

    private val executor = Executors.newSingleThreadExecutor()

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * 포그라운드 액티비티(MainActivity)가 활성화되어 있을 때 호출되어 위치를 1회 안전하게 조회하고 캐시
     */
    fun refreshLocationInForeground(context: Context, onLocation: ((Location) -> Unit)? = null) {
        if (!hasLocationPermission(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        // 1. 시스템에 이미 캐시된 위치 즉시 획득
        getLastKnownLocation(context)?.let { loc ->
            cachedLocation = loc
            onLocation?.invoke(loc)
        }

        // 2. Android 11+ 단발성 getCurrentLocation (포그라운드 액티비티 상태에서만 호출)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancellationSignal = CancellationSignal()
                val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    LocationManager.GPS_PROVIDER
                } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    LocationManager.NETWORK_PROVIDER
                } else {
                    LocationManager.PASSIVE_PROVIDER
                }

                lm.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    executor
                ) { loc ->
                    if (loc != null) {
                        cachedLocation = loc
                        AppLogger.i(TAG, "📍 포그라운드 위치 조회 성공: lat=${loc.latitude}, lng=${loc.longitude}")
                        onLocation?.invoke(loc)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "refreshLocationInForeground failed: ${e.message}")
        }
    }

    /**
     * 가장 최근에 확인된 마지막 유효 위치 반환 (캐시 우선 -> 시스템 LastKnownLocation)
     * FGS 센서 구동이 없으므로 FOREGROUND_SERVICE_LOCATION 권한이 전혀 필요 없음
     */
    fun getCachedOrLastKnownLocation(context: Context): Location? {
        cachedLocation?.let { return it }
        return getLastKnownLocation(context)
    }

    /**
     * 시스템 캐시(LastKnownLocation) 조회 (배터리 소모 0, 센서 비활성)
     */
    fun getLastKnownLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        var bestLocation: Location? = null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        for (provider in providers) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null) {
                        if (bestLocation == null || loc.time > bestLocation.time) {
                            bestLocation = loc
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (bestLocation != null) {
            cachedLocation = bestLocation
        }
        return bestLocation
    }

    /**
     * Location 객체를 WebRTC DataChannel 송신용 JSON 문자열로 변환
     */
    fun toJson(location: Location): String {
        return JSONObject().apply {
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy", location.accuracy.toDouble())
            put("speed", location.speed.toDouble())
            put("bearing", location.bearing.toDouble())
            put("timestamp", location.time)
        }.toString()
    }
}
