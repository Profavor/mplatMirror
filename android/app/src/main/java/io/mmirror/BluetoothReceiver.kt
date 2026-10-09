package io.mmirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

class BluetoothReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BluetoothReceiver"
        private const val CHANNEL_ID = "mmirror_bluetooth_channel"
        private const val NOTIF_ID = 2001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val devName = try { device?.name } catch (_: SecurityException) { null } ?: "차량 블루투스"
                Log.i(TAG, "🚗 Bluetooth device connected: $devName")
                val isHotspotOn = NetworkUtils.isWifiApEnabled(context)
                if (!isHotspotOn) {
                    showHotspotTurnOnNotification(context, devName)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val devName = try { device?.name } catch (_: SecurityException) { null } ?: "차량 블루투스"
                Log.i(TAG, "🚗 Bluetooth device disconnected: $devName")
                dismissNotification(context)
            }
        }
    }

    private fun showHotspotTurnOnNotification(context: Context, devName: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "차량 블루투스 연동 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "차량 블루투스 연결 시 모바일 핫스팟 켜기 안내"
            }
            nm.createNotificationChannel(channel)
        }

        val hotspotIntent = Intent().apply {
            action = "android.settings.TETHER_SETTINGS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            hotspotIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚗 $devName 연결됨")
            .setContentText("차량 미러링을 위해 모바일 핫스팟을 켜주세요.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(R.mipmap.ic_launcher, "핫스팟 설정 열기", pendingIntent)
            .build()

        nm.notify(NOTIF_ID, notif)
    }

    private fun dismissNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(NOTIF_ID)
    }
}
