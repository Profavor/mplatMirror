package io.mmirror

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * mMirror 전원 및 화면 켜짐 유지(WakeLock) 통합 관리자
 * 
 * 1. CPU Partial WakeLock (백그라운드 가상 디스플레이 인코딩 보호)
 * 2. Screen Bright WakeLock (미러링 중 스마트폰 화면 켜짐 유지)
 * 3. Wi-Fi High-Perf Lock (화면 꺼짐 시 핫스팟 스로틀링 방지)
 * 4. KeyguardLock (미러링 중 화면 잠금 방지)
 * 5. Screen State, Hotspot & Bluetooth BroadcastReceiver
 */
class MirrorWakeLockManager(
    private val context: Context,
    private val onScreenOff: () -> Unit,
    private val onScreenOn: () -> Unit,
    private val onUserPresent: () -> Unit,
    private val onHotspotDisabled: (() -> Unit)? = null,
    private val onBluetoothDisconnected: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "MirrorWakeLockManager"
        private const val WAKELOCK_TIMEOUT = 24 * 60 * 60 * 1000L // 24시간 안전 타임아웃
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Suppress("DEPRECATION")
    private var keyguardLock: KeyguardManager.KeyguardLock? = null
    private var screenStateReceiver: BroadcastReceiver? = null

    fun acquireLocks() {
        acquireCpuWakeLock()
        acquireScreenWakeLock()
        acquireWifiLock()
        registerScreenStateReceiver()
    }

    fun releaseLocks() {
        unregisterScreenStateReceiver()
        releaseCpuWakeLock()
        releaseScreenWakeLock()
        releaseWifiLock()
    }

    private fun acquireCpuWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "mMirror:VirtualDisplayWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT)
            }
            Log.i(TAG, "CPU WakeLock acquired successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire CPU WakeLock: ${e.message}")
        }
    }

    private fun releaseCpuWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "mMirror:WifiLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Wi-Fi High-Perf Lock acquired (prevents hotspot throttling on screen off)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WifiLock: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        wifiLock = null
    }

    fun acquireScreenWakeLock() {
        if (screenWakeLock?.isHeld == true) return
        val prefs = context.getSharedPreferences("mmirror_prefs", Context.MODE_PRIVATE)
        val stayAwake = prefs.getBoolean("stay_awake", true)
        if (!stayAwake) return

        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "mMirror:ScreenWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT)
            }
            AppLogger.i(TAG, "💡 미러링 화면 켜짐 유지(Screen WakeLock) 활성화 완료")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Screen WakeLock 획득 실패: ${e.message}")
        }

        if (prefs.getBoolean("auto_dismiss_keyguard", true)) {
            try {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                @Suppress("DEPRECATION")
                keyguardLock = km.newKeyguardLock("mMirror:KeyguardLock").apply {
                    disableKeyguard()
                }
                AppLogger.i(TAG, "🔓 KeyguardLock 비활성화 완료 (미러링 중 잠금 해제 유지)")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to disable keyguard: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    fun releaseScreenWakeLock() {
        try {
            screenWakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        screenWakeLock = null

        try {
            keyguardLock?.reenableKeyguard()
        } catch (_: Exception) {}
        keyguardLock = null
    }

    fun updateStayAwake() {
        releaseScreenWakeLock()
        acquireScreenWakeLock()
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        AppLogger.w(TAG, "📱 스마트폰 화면 꺼짐(Screen OFF) 감지 -> 디스플레이 절전 전환")
                        onScreenOff()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        AppLogger.i(TAG, "📱 스마트폰 화면 켜짐(Screen ON) 감지 -> 디스플레이 상태 재점검")
                        onScreenOn()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        AppLogger.i(TAG, "🔓 스마트폰 잠금 해제(User Present) 감지 -> 미러링 화면 즉시 재동기화")
                        onUserPresent()
                    }
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                        if (isInitialStickyBroadcast) return
                        val state = intent.getIntExtra("wifi_state", -1)
                        if (state == 11 || state == 10 || state == 14) {
                            AppLogger.w(TAG, "⛔ 모바일 핫스팟 꺼짐 감지 (wifi_state=$state) -> 0MB 데이터 보호를 위해 미러링 자동 중단")
                            onHotspotDisabled?.invoke()
                        }
                    }
                    android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        AppLogger.w(TAG, "🚗 차량 블루투스 연결 해제 감지 (하차 여부 감시 시작)")
                        onBluetoothDisconnected?.invoke()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            screenStateReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screenStateReceiver: ${e.message}")
        }
    }

    private fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
            screenStateReceiver = null
        }
    }
}
