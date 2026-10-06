package io.mmirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * mMirror 앱 최신 배포 버전 확인 및 업데이트 안내 관리자
 */
object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    const val APK_DOWNLOAD_URL = "https://mdm.mplat.store:8088/dist/mplatMirror.apk"
    private const val INFO_API_URL = "https://mdm.mplat.store:8088/api/info"

    /**
     * 릴레이 서버로부터 최신 빌드 버전 조회
     */
    fun checkServerVersionFromApi(
        scope: CoroutineScope,
        httpClient: OkHttpClient,
        onVersionFetched: (serverVersion: String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(INFO_API_URL)
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val serverVer = json.optString("version", "")
                        if (serverVer.isNotEmpty()) {
                            onVersionFetched(serverVer)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server version check failed: ${e.message}")
            }
        }
    }

    /**
     * 업데이트 알림 다이얼로그 노출
     */
    fun showUpdateDialog(
        activity: Activity,
        serverVersion: String,
        currentVersion: String = BuildConfig.VERSION_NAME
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("🚀 최신 버전 업데이트 (v$serverVersion)")
            .setMessage(
                "새로운 버전이 감지되었습니다.\n\n" +
                "• 현재 앱 버전: v$currentVersion\n" +
                "• 최신 배포 버전: v$serverVersion\n\n" +
                "⚠️ [플레이스토어 설치 기기 필독]\n" +
                "구글 플레이 스토어로 설치하신 경우, 앱 간 보안 서명 차이로 인해 직접 APK 설치 시 업데이트가 거부될 수 있습니다. 이 경우 기존 앱을 스마트폰에서 '삭제'하신 후 아래 [📥 최신 APK 다운로드]를 받아 설치해 주세요.\n\n" +
                "최신 버전(v$serverVersion)에는 테슬라 오디오 간섭 원천 차단 및 P2P 무중단 연결 패치가 적용되어 있습니다."
            )
            .setPositiveButton("📥 최신 APK 다운로드 (추천)") { _, _ ->
                downloadDirectApk(activity, serverVersion)
            }
            .setNeutralButton("🚀 Google Play 스토어") { _, _ ->
                SystemNavigationHelper.openGooglePlayStore(activity)
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    /**
     * 최신 배포 APK 직접 다운로드 브라우저 인텐트 호출
     */
    fun downloadDirectApk(context: Context, serverVersion: String? = null) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(APK_DOWNLOAD_URL)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val verLabel = if (serverVersion != null) "v$serverVersion " else ""
            Toast.makeText(context, "📥 최신 ${verLabel}APK 다운로드를 시작합니다.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            SystemNavigationHelper.openGooglePlayStore(context)
        }
    }
}
