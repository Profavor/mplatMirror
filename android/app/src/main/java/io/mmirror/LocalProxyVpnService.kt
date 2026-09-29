package io.mmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class LocalProxyVpnService : VpnService() {

    companion object {
        private const val TAG = "LocalProxyVpnService"
        const val ACTION_START = "io.mmirror.action.START_VPN"
        const val ACTION_STOP = "io.mmirror.action.STOP_VPN"
        private const val CHANNEL_ID = "mmirror_vpn_channel"
        private const val NOTIFICATION_ID = 2002

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, LocalProxyVpnService::class.java).apply {
                action = ACTION_START
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to startService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocalProxyVpnService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stopService: ${e.message}", e)
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectivityManager: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stopping LocalProxyVpnService...")
                stopVpn()
                stopSelf()
            }
            ACTION_START, null -> {
                Log.i(TAG, "Starting LocalProxyVpnService...")
                startVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun sendDebugLog(msg: String) {
        Log.i(TAG, "[DEBUG] $msg")
        val intent = Intent("io.mmirror.VPN_DEBUG_LOG").apply {
            putExtra("log", msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun startVpn() {
        if (isRunning) {
            Log.i(TAG, "LocalProxyVpnService is already running")
            return
        }

        try {
            createNotificationChannel()
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground non-fatal error: ${e.message}")
        }

        sendDebugLog("1/5 VPN 빌더 생성 시작")

        try {
            val builder = Builder()
                .setSession("mplat Tesla Proxy")
                .setMtu(1500)
                .setBlocking(false)

            // 1. 가상 인터페이스 주소 직접 바인딩 (122.40.252.50, 100.99.9.9, 7.7.7.7, 100.99.9.2)
            // 핫스팟 클라이언트(테슬라)가 접속할 타겟 IP를 기기 로컬 인터페이스 주소로 직접 등록하여 외부 셀룰러 유출 방지
            try { builder.addAddress("122.40.252.50", 32) } catch (e: Throwable) { sendDebugLog("⚠ addAddress 122.40.252.50 실패: ${e.message}") }
            try { builder.addAddress("100.99.9.9", 32) } catch (e: Throwable) { sendDebugLog("⚠ addAddress 100.99.9.9 실패: ${e.message}") }
            try { builder.addAddress("7.7.7.7", 32) } catch (e: Throwable) { sendDebugLog("⚠ addAddress 7.7.7.7 실패: ${e.message}") }
            try { builder.addAddress("100.99.9.2", 24) } catch (e: Throwable) { sendDebugLog("⚠ addAddress 100.99.9.2 실패: ${e.message}") }

            sendDebugLog("2/5 addAddress 완료, 가상 프록시 IP만 1:1 정밀 라우팅 (/32) 설정 중")

            // 오직 테슬라 가상 프록시 대상 IP(/32)만 1:1 정밀 인터셉트 (일반 인터넷 및 핫스팟 트래픽 100% 정상 보장)
            try { builder.addRoute("122.40.252.50", 32) } catch (e: Throwable) { sendDebugLog("⚠ addRoute 122.40.252.50 실패: ${e.message}") }
            try { builder.addRoute("100.99.9.9", 32) } catch (e: Throwable) { sendDebugLog("⚠ addRoute 100.99.9.9 실패: ${e.message}") }
            try { builder.addRoute("7.7.7.7", 32) } catch (e: Throwable) { sendDebugLog("⚠ addRoute 7.7.7.7 실패: ${e.message}") }

            // Android 13+ (API 33+) 사설망(핫스팟/로컬 Wi-Fi) 라우팅 완전 배제 보장
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("10.0.0.0"), 8))
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("172.16.0.0"), 12))
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("192.168.0.0"), 16))
                } catch (e: Throwable) {
                    Log.w(TAG, "excludeRoute not supported: ${e.message}")
                }
            }

            // 앱 자체(미러링 소켓) 및 삼성페이 앱들은 VPN 간섭 방지를 위해 우회
            val bypassPackages = listOf(
                packageName,
                "com.samsung.android.spay",
                "com.samsung.android.spayfw",
                "com.samsung.android.rajaampat",
                "com.samsung.android.samsungpay.gear",
                "com.samsung.android.authfw"
            )
            for (pkg in bypassPackages) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: Throwable) {
                    // 패키지가 설치되지 않았거나 시스템 패키지인 경우 무시
                }
            }
            try {
                builder.allowBypass()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to allow bypass: ${e.message}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            sendDebugLog("3/5 builder.establish() 호출 중...")

            val pfd = builder.establish()
            if (pfd != null) {
                vpnInterface = pfd
                val rawFd = pfd.fd
                sendDebugLog("4/5 VPN TUN 생성 성공 fd=$rawFd, 서버포트 확인 중...")

                // 물리 셀룰러/Wi-Fi 네트워크를 underlying network로 즉시 바인딩하여 핫스팟 인터넷 공유 유지
                updateUnderlyingNetworks()
                registerNetworkCallback()

                val targetPort = NativeBridge.getServerPort().takeIf { it > 0 } ?: 8080
                sendDebugLog("4/5 targetPort=$targetPort, TUN 프록시 시작 중...")

                val ok = NativeBridge.startTunProxy(rawFd, targetPort)
                sendDebugLog("5/5 startTunProxy 결과=$ok (targetPort=$targetPort)")

                isRunning = true
            } else {
                sendDebugLog("❌ builder.establish() 반환값 null! VPN 권한 없음?")
                stopSelf()
            }
        } catch (e: Throwable) {
            sendDebugLog("❌ VPN 시작 예외: ${e.message}")
            stopSelf()
        }
    }

    private fun updateUnderlyingNetworks() {
        try {
            val cm = connectivityManager ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).also { connectivityManager = it }
            val physicalNetworks = cm.allNetworks.filter { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            }.toTypedArray()

            if (physicalNetworks.isNotEmpty()) {
                setUnderlyingNetworks(physicalNetworks)
                sendDebugLog("✓ 물리 네트워크(${physicalNetworks.size}개)를 기본 인터넷 업스트림으로 설정 완료")
            } else if (cm.activeNetwork != null) {
                setUnderlyingNetworks(arrayOf(cm.activeNetwork))
                sendDebugLog("✓ activeNetwork를 기본 인터넷 업스트림으로 설정 완료")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to update underlying networks: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = connectivityManager ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).also { connectivityManager = it }
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    updateUnderlyingNetworks()
                }
                override fun onLost(network: android.net.Network) {
                    updateUnderlyingNetworks()
                }
                override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: android.net.NetworkCapabilities) {
                    updateUnderlyingNetworks()
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
        } catch (_: Throwable) {}
        networkCallback = null
    }

    private fun stopVpn() {
        try {
            unregisterNetworkCallback()
            NativeBridge.stopTunProxy()
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing VPN interface: ${e.message}")
        } finally {
            isRunning = false
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "LocalProxyVpnService destroyed")
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "테슬라 가상 프록시",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "테슬라 화면 미러링 로컬 가상 프록시가 실행 중입니다."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopVpnIntent = Intent(this, LocalProxyVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopVpnPendingIntent = PendingIntent.getService(
            this, 1, stopVpnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("⚡ 테슬라 로컬 가상 프록시 (데이터 0MB)")
            .setContentText("접속: https://mdm.mplat.store:9999 (보조: https://teslamirror.net:9999)")
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "VPN 끄기 (삼성페이)", stopVpnPendingIntent)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
