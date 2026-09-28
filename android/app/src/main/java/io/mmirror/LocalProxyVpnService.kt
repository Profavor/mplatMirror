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

        try {
            val builder = Builder()
                .setSession("mplat Tesla Proxy")
                .setMtu(1500)
                .setBlocking(false)

            // 가상 인터페이스 주소 직접 바인딩 (100.99.9.9, 7.7.7.7, 3.3.3.3, 10.254.1.1, 10.254.1.2)
            // 안드로이드 커널이 해당 목적지 패킷을 호스트 로컬 수신으로 인식하여 테더링 방화벽 드롭 없이 즉시 처리하도록 /32 바인딩
            try { builder.addAddress("100.99.9.9", 32) } catch (e: Throwable) { Log.w(TAG, "addAddress 100.99.9.9 failed: ${e.message}") }
            try { builder.addAddress("7.7.7.7", 32) } catch (e: Throwable) { Log.w(TAG, "addAddress 7.7.7.7 failed: ${e.message}") }
            try { builder.addAddress("3.3.3.3", 32) } catch (e: Throwable) { Log.w(TAG, "addAddress 3.3.3.3 failed: ${e.message}") }
            try { builder.addAddress("10.254.1.1", 32) } catch (e: Throwable) { Log.w(TAG, "addAddress 10.254.1.1 failed: ${e.message}") }
            try { builder.addAddress("10.254.1.2", 24) } catch (e: Throwable) { Log.w(TAG, "addAddress 10.254.1.2 failed: ${e.message}") }

            // 테슬라 브라우저가 사용하는 가상 프록시 IP 대역을 로컬 VPN 터널로 유입
            // teslamirror.net -> 100.99.9.9
            try { builder.addRoute("100.99.9.0", 24) } catch (e: Throwable) { Log.w(TAG, "addRoute 100.99.9.0 failed: ${e.message}") }
            // td9.cc -> 7.7.7.7
            try { builder.addRoute("7.7.7.0", 24) } catch (e: Throwable) { Log.w(TAG, "addRoute 7.7.7.0 failed: ${e.message}") }
            // 보조 가상 대역
            try { builder.addRoute("3.3.3.0", 24) } catch (e: Throwable) { Log.w(TAG, "addRoute 3.3.3.0 failed: ${e.message}") }
            try { builder.addRoute("10.254.1.0", 24) } catch (e: Throwable) { Log.w(TAG, "addRoute 10.254.1.0 failed: ${e.message}") }

            // 앱 자체(미러링 소켓)는 VPN 루프에 빠지지 않도록 우회
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to disallow application package: ${e.message}")
            }
            try {
                builder.allowBypass()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to allow bypass: ${e.message}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val pfd = builder.establish()
            if (pfd != null) {
                vpnInterface = pfd
                val rawFd = pfd.fd
                Log.i(TAG, "VPN TUN established successfully with fd: $rawFd")

                val targetPort = NativeBridge.getServerPort().takeIf { it > 0 } ?: 8080
                val ok = NativeBridge.startTunProxy(rawFd, targetPort)
                Log.i(TAG, "NativeBridge startTunProxy returned: $ok with targetPort: $targetPort")

                isRunning = true
            } else {
                Log.e(TAG, "Builder.establish() returned null. VPN permission might be missing.")
                stopSelf()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Exception while establishing LocalProxyVpnService", e)
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
