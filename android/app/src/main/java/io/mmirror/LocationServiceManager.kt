package io.mmirror

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * mMirror 스마트폰 모바일 실시간 GPS 위치 매니저
 *
 * 1. 0MB 모바일 데이터 원칙 준수: 로컬 WebRTC DataChannel을 통해 테슬라 차량 브라우저로만 좌표 전달
 * 2. 포그라운드 서비스(MediaProjectionService) 연동: 화면 꺼짐/티맵 실행 중에도 백그라운드에서 실시간 위치 갱신
 * 3. 배터리 소모 0% 최적화:
 *    - 티맵/카카오내비 실행 시 하드웨어 센서 추가 가동 없이 공짜로 수신하는 PASSIVE_PROVIDER 활용
 *    - 미러링 시작 시 캐시된 최근 위치(LastKnownLocation) 즉각(0ms) 송출
 *    - 10분 주기 또는 1km 이상 이동 시에만 스마트 갱신
 */
object LocationServiceManager {
    private const val TAG = "LocationServiceManager"

    // 갱신 주기: 10분, 최소 이동 거리: 1,000m (배터리 및 날씨 API 호출 최적화)
    private const val MIN_TIME_MS = 10 * 60 * 1000L
    private const val MIN_DISTANCE_M = 1000f

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var isListening = false
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastBroadcastLocation: Location? = null

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
     * 가장 최근에 확인된 마지막 유효 위치 즉시 반환 (0ms 지연, 배터리 소모 0)
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
            } catch (e: SecurityException) {
                AppLogger.w(TAG, "SecurityException while accessing $provider: ${e.message}")
            } catch (_: Exception) {}
        }
        return bestLocation
    }

    /**
     * 단발성 현재 위치 요청 (단말 부팅 직후 등으로 캐시가 없을 때 1회 최신화)
     */
    fun requestCurrentLocation(context: Context, onLocation: (Location) -> Unit) {
        if (!hasLocationPermission(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

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
                        lastBroadcastLocation = loc
                        onLocation(loc)
                    }
                }
            } else {
                // Android 10 이하 fallback
                val singleListener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        lastBroadcastLocation = loc
                        onLocation(loc)
                        try {
                            lm.removeUpdates(this)
                        } catch (_: Exception) {}
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    LocationManager.NETWORK_PROVIDER
                } else {
                    LocationManager.GPS_PROVIDER
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, singleListener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "SecurityException during requestCurrentLocation: ${e.message}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "requestCurrentLocation failed: ${e.message}")
        }
    }

    /**
     * 미러링 서비스 구동 중 위치 리스너 시작 (배터리 0% 패시브 + 저주기 갱신)
     */
    @Synchronized
    fun startLocationUpdates(context: Context, onLocationUpdate: (Location) -> Unit) {
        if (isListening) return
        if (!hasLocationPermission(context)) {
            AppLogger.i(TAG, "📍 위치 권한이 없어 GPS 동기화를 건너뜁니다.")
            return
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm

        // 1. 기존 캐시된 위치가 있으면 즉시 콜백 (초기 대시보드 0ms 반영)
        getLastKnownLocation(context)?.let { cached ->
            lastBroadcastLocation = cached
            AppLogger.i(TAG, "📍 [GPS] 캐시된 최근 위치 즉시 반영: lat=${cached.latitude}, lng=${cached.longitude}")
            onLocationUpdate(cached)
        } ?: run {
            // 캐시가 없으면 1회 단발성 조회
            requestCurrentLocation(context, onLocationUpdate)
        }

        // 2. 상시 패시브 리스너 등록 (티맵/카카오내비가 위치를 잡을 때 배터리 0%로 가로채기)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val prev = lastBroadcastLocation
                val dist = if (prev != null) location.distanceTo(prev) else Float.MAX_VALUE
                val timeDiff = if (prev != null) Math.abs(location.time - prev.time) else Long.MAX_VALUE

                // 1km 이상 이동했거나 10분 이상 경과한 경우에만 웹으로 브로드캐스트
                if (prev == null || dist >= MIN_DISTANCE_M || timeDiff >= MIN_TIME_MS) {
                    lastBroadcastLocation = location
                    AppLogger.i(TAG, "📍 [GPS] 유의미한 위치 변화 감지: lat=${location.latitude}, lng=${location.longitude}, dist=${dist.toInt()}m")
                    onLocationUpdate(location)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            // PASSIVE_PROVIDER는 타 앱 GPS 사용 시 이벤트 전달 (배터리 소모 없음)
            lm.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper()
            )

            // 네트워크/기지국 기반 저전력 위치 업데이트
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper()
                )
            }

            locationListener = listener
            isListening = true
            AppLogger.i(TAG, "✓ 위치 감지 리스너 등록 완료 (PASSIVE + NETWORK, 배터리 초절전 모드)")
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "위치 리스너 권한 오류: ${e.message}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "위치 리스너 등록 실패: ${e.message}")
        }
    }

    /**
     * 미러링 종료 시 위치 리스너 해제 (배터리 보호)
     */
    @Synchronized
    fun stopLocationUpdates() {
        try {
            locationListener?.let {
                locationManager?.removeUpdates(it)
            }
        } catch (_: Exception) {}
        locationListener = null
        locationManager = null
        isListening = false
        AppLogger.i(TAG, "🛑 위치 감지 리스너 해제 완료")
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
