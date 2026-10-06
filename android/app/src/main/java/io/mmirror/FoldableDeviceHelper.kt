package io.mmirror

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 삼성 갤럭시 Z 폴드/플립 및 폴더블 기기 전용 설정 및 연속성 관리 도우미
 */
object FoldableDeviceHelper {
    private const val TAG = "FoldableDeviceHelper"

    /**
     * 현재 기기가 삼성 갤럭시 Z 폴드/플립 시리즈인지 판별
     */
    fun isGalaxyZDevice(context: Context): Boolean {
        val namesToCheck = mutableListOf(
            Build.MODEL,
            Build.PRODUCT,
            Build.DEVICE
        )

        // 1. Settings.Global / Secure의 제품명 / 기기명 / 블루투스명
        try {
            Settings.Global.getString(context.contentResolver, "device_name")?.let { namesToCheck.add(it) }
            Settings.Global.getString(context.contentResolver, "synced_device_name")?.let { namesToCheck.add(it) }
            Settings.Secure.getString(context.contentResolver, "bluetooth_name")?.let { namesToCheck.add(it) }
        } catch (_: Exception) {}

        // 2. 삼성 시스템 마케팅 제품명 프로퍼티 (ro.product.marketname 등)
        try {
            val sysPropClass = Class.forName("android.os.SystemProperties")
            val getMethod = sysPropClass.getMethod("get", String::class.java)
            listOf(
                "ro.product.marketname",
                "ro.product.vendor.marketname",
                "ro.product.name",
                "ro.config.marketing_name"
            ).forEach { prop ->
                (getMethod.invoke(null, prop) as? String)?.let {
                    if (it.isNotBlank()) namesToCheck.add(it)
                }
            }
        } catch (_: Exception) {}

        // 3. 제품명/모델명에 "갤럭시 Z" 또는 "Galaxy Z" 포함 여부 검사 (Fold, Flip 포함)
        for (name in namesToCheck) {
            val trimmed = name.trim()
            if (trimmed.contains("Galaxy Z", ignoreCase = true) ||
                trimmed.contains("갤럭시 Z", ignoreCase = true) ||
                trimmed.contains("갤럭시Z", ignoreCase = true) ||
                trimmed.contains("Galaxy-Z", ignoreCase = true) ||
                trimmed.contains("Galaxy_Z", ignoreCase = true) ||
                trimmed.contains("GalaxyZ", ignoreCase = true) ||
                trimmed.contains("Z Fold", ignoreCase = true) ||
                trimmed.contains("Z Flip", ignoreCase = true)) {
                return true
            }
        }

        // 4. 삼성 공식 갤럭시 Z 시리즈 모델 번호 (SM-F: Z 폴드 및 Z 플립 전체 공통)
        val upperModel = Build.MODEL.uppercase()
        if (upperModel.startsWith("SM-F") || upperModel.contains("-F") || upperModel.startsWith("SM-W")) {
            return true
        }

        return false
    }

    /**
     * 삼성 커버 화면 앱 연속성 설정 화면 열기
     */
    fun openCoverScreenContinuitySettings(context: Context) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$CoverScreenAppContinuitySettingsActivity")),
            Intent("com.samsung.settings.COVER_SCREEN_CONTINUITY"),
            Intent(Settings.ACTION_DISPLAY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                Log.i(TAG, "✓ Opened Cover Screen Continuity settings via $intent")
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(context, "설정 > 디스플레이 > 커버 화면에서 앱 계속 사용에서 mMirror를 활성화하세요.", Toast.LENGTH_LONG).show()
    }

    /**
     * 폴더블 기기 첫 진입 시 커버 화면 연속성 권장 안내 다이얼로그
     */
    fun checkAndPromptFoldContinuity(activity: Activity) {
        val prefs = activity.getSharedPreferences("mmirror_prefs", Context.MODE_PRIVATE)
        val alreadyPrompted = prefs.getBoolean("fold_continuity_guided_v142", false)
        if (!alreadyPrompted) {
            AlertDialog.Builder(activity)
                .setTitle("📱 갤럭시 Z 미러링 안내")
                .setMessage("폰을 접었을 때도 미러링이 중단되지 않고 커버 화면으로 계속 이어지도록 하려면 '커버 화면에서 앱 계속 사용' 설정에서 mMirror를 켜주세요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    prefs.edit().putBoolean("fold_continuity_guided_v142", true).apply()
                    openCoverScreenContinuitySettings(activity)
                }
                .setNegativeButton("나중에") { _, _ ->
                    prefs.edit().putBoolean("fold_continuity_guided_v142", true).apply()
                }
                .show()
        }
    }

    /**
     * Smart Lock (Extend Unlock / 신뢰할 수 있는 기기) 설정 열기
     */
    fun openSmartLockSettings(context: Context) {
        val intents = listOf(
            Intent("android.settings.LOCK_SCREEN_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$LockscreenMenuActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$LockscreenSettingsActivity")),
            Intent().setComponent(ComponentName("com.google.android.gms", "com.google.android.gms.auth.trustagent.GoogleTrustAgentPersonalTrustSettingsActivity")),
            Intent().setComponent(ComponentName("com.google.android.gms", "com.google.android.gms.auth.trustagent.TrustAgentSettingsActivity")),
            Intent("android.settings.TRUST_AGENT_SETTINGS"),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                Log.i(TAG, "✓ Opened Smart Lock settings via $intent")
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(context, "설정 > 잠금화면 및 AOD > 잠금 해제 유지에서 테슬라 BT를 등록할 수 있습니다.", Toast.LENGTH_LONG).show()
    }
}
