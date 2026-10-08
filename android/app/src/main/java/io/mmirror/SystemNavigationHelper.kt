package io.mmirror

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 시스템 설정 화면 및 외부 앱/마켓 인텐트 디스패처 헬퍼
 */
object SystemNavigationHelper {
    private const val TAG = "SystemNavHelper"

    fun openBluetoothSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (_: Exception) {}
        }
    }

    fun openWriteSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val canWrite = Settings.System.canWrite(activity)
            if (canWrite) {
                Toast.makeText(activity, "✓ 이미 화면 절전(밝기 제어) 권한이 허용되어 있습니다.", Toast.LENGTH_SHORT).show()
            }
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
                return
            } catch (e: Exception) {
                AppLogger.w(TAG, "ACTION_MANAGE_WRITE_SETTINGS with package URI failed: ${e.message}")
            }

            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
                return
            } catch (e: Exception) {
                AppLogger.w(TAG, "ACTION_MANAGE_WRITE_SETTINGS generic failed: ${e.message}")
            }

            // 폴백: 앱 상세 정보 화면 (일반 설정이 아닌 mMirror 전용 설정 화면)
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(activity, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(activity, "이 안드로이드 버전에서는 별도 권한 허용이 필요하지 않습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    fun openAccessibilitySettings(activity: Activity) {
        val disclosure = """
            mplat Mirror는 차량 대화면에서 스마트폰을 원격으로 터치·조작할 수 있도록 안드로이드 '접근성 서비스(AccessibilityService) API'를 사용합니다.

            [사용 목적]
            • 차량 브라우저 화면에서 발생한 터치, 스와이프, 스크롤 제스처를 스마트폰 화면에 실시간 주입
            • 차량 화면 가상 키보드로 입력한 목적지/검색어를 스마트폰의 활성화된 입력창(EditText)에 전달

            [개인정보 보호 및 데이터 미수집 보장]
            • 화면 상의 기존 텍스트, 비밀번호, 금융 정보, 개인 메시지 등을 일절 감시하거나 수집하지 않습니다.
            • 어떠한 사용자 입력 데이터도 외부 서버로 전송되거나 저장되지 않습니다.
            • 모든 동작은 스마트폰 핫스팟의 순수 로컬 Wi-Fi(UDP P2P) 내부에서만 즉시 처리됩니다.

            접근성 권한을 켜지 않아도 '화면 미러링(보기)'은 정상 이용하실 수 있습니다 (터치 조작만 비활성화됨).
        """.trimIndent()

        MaterialAlertDialogBuilder(activity)
            .setTitle("📌 접근성 권한 안내 (필독)")
            .setMessage(disclosure)
            .setPositiveButton("동의하고 설정하기") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    activity.startActivity(intent)
                    Toast.makeText(activity, "[설치된 앱] 또는 [다운로드된 서비스]에서 'mplat Mirror'를 켜주세요.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(activity, "접근성 설정 열기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소 (보기 전용 모드)", null)
            .show()
    }

    fun openHotspotSettings(context: Context) {
        val intent = Intent()
        try {
            intent.action = "android.settings.TETHER_SETTINGS"
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                intent.component = ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$TetherSettingsActivity"
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (_: Exception) {
                    Toast.makeText(context, "핫스팟 설정을 열 수 없습니다. 스마트폰 상단바에서 핫스팟을 켜주세요.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openGooglePlayStore(context: Context) {
        val appPkg = context.packageName
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPkg")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            context.startActivity(marketIntent)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$appPkg")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (_: Exception) {}
        }
    }

    fun openPrivacyPolicy(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mplat-mirror.web.app/privacy.html")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
