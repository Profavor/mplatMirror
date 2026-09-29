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

            // 가상 인터페이스 주소 직접 바인딩 (100.99.9.9, 7.7.7.7, 3.3.3.3, 10.254.1.1, 10.254.1.2)
            // 안드로이드 커널이 해당 목적지 패킷을 호스트 로컬 수신으로 인식하여 테더링 방화벽 드롭 없이 즉시 처리하도록 /32 바인딩
            try { builder.addAddress("100.99.9.9", 32) } catch (e: Throwable) { sendDebugLog("⚠ addAddress 100.99.9.9 실패: ${e.message}") }
            try { builder.addAddress("7.7.7.7", 32) } catch (e: Throwable) { sendDebugLog("⚠ addAddress 7.7.7.7 실패: ${e.message}") }
            try { builder.addAddress("3.3.3.3", 32) } catch (e: Throwable) { sendDebugLog("⚠ addAddress 3.3.3.3 실패: ${e.message}") }
            try { builder.addAddress("10.254.1.1", 32) } catch (e: Throwable) { sendDebugLog("⚠ addAddress 10.254.1.1 실패: ${e.message}") }
            try { builder.addAddress("10.254.1.2", 24) } catch (e: Throwable) { sendDebugLog("⚠ addAddress 10.254.1.2 실패: ${e.message}") }

            sendDebugLog("2/5 addAddress 완료, addRoute 설정 중")

            // 테슬라 브라우저가 사용하는 가상 프록시 IP 대역을 로컬 VPN 터널로 유입
            // teslamirror.net -> 100.99.9.9
            try { builder.addRoute("100.99.9.0", 24) } catch (e: Throwable) { sendDebugLog("⚠ addRoute 100.99.9.0 실패: ${e.message}") }
            // td9.cc -> 7.7.7.7
            try { builder.addRoute("7.7.7.0", 24) } catch (e: Throwable) { sendDebugLog("⚠ addRoute 7.7.7.0 실패: ${e.message}") }
            // 보조 가상 대역
            try { builder.addRoute("3.3.3.0", 24) } catch (e: Throwable) { sendDebugLog("⚠ addRoute 3.3.3.0 실패: ${e.message}") }
            try { builder.addRoute("10.254.1.0", 24) } catch (e: Throwable) { sendDebugLog("⚠ addRoute 10.254.1.0 실패: ${e.message}") }

            // 앱 자체(미러링 소켓)는 VPN 루프에 빠지지 않도록 우회
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Throwable) {
                sendDebugLog("⚠ addDisallowedApplication 실패: ${e.message}")
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

    private fun stopVpn() {
        try {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("⚡ 테슬라 로컬 가상 프록시 (데이터 0MB)")
            .setContentText("접속 주소: http://td9.cc:7777 (또는 https://teslamirror.net:9999)")

            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
