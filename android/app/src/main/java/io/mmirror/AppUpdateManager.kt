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
    const val APK_DOWNLOAD_URL = "https://github.com/Profavor/mplatMirror/releases/latest/download/inyou-mirror.apk"
    private const val CDN_VERSION_URL = "https://inyou-mirror.web.app/version.json"
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/Profavor/mplatMirror/releases/latest"

    /**
     * 시맨틱 버전 비교 (remote가 local보다 높은지 검사)
     */
    fun isNewerVersion(remoteVer: String, localVer: String): Boolean {
        return try {
            val rParts = remoteVer.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val lParts = localVer.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(rParts.size, lParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            false
        } catch (_: Exception) {
            remoteVer.trim().removePrefix("v") != localVer.trim().removePrefix("v")
        }
    }

    /**
     * Firebase CDN 및 GitHub API로부터 최신 빌드 버전 조회 (HTTPS 443 표준)
     */
    fun checkServerVersionFromApi(
        scope: CoroutineScope,
        httpClient: OkHttpClient,
        onVersionFetched: (serverVersion: String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            // 1순위: Firebase Hosting CDN version.json 조회 (가장 빠름 & 무제한 대역폭)
            try {
                val request = Request.Builder()
                    .url(CDN_VERSION_URL)
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val serverVer = json.optString("version", "").trim().removePrefix("v")
                        if (serverVer.isNotEmpty()) {
                            Log.i(TAG, "✓ Version fetched from Firebase CDN: v$serverVer")
                            onVersionFetched(serverVer)
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Firebase CDN version check failed: ${e.message}, falling back to GitHub API")
            }

            // 2순위: GitHub Releases 공식 API 조회
            try {
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_URL)
                    .header("User-Agent", "inYouMirror-Android")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val tagName = json.optString("tag_name", "").trim().removePrefix("v")
                        if (tagName.isNotEmpty()) {
                            Log.i(TAG, "✓ Version fetched from GitHub API: v$tagName")
                            onVersionFetched(tagName)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "GitHub API version check failed: ${e.message}")
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
                "새로운 최신 버전이 출시되었습니다.\n\n" +
                "• 현재 앱 버전: v$currentVersion\n" +
                "• 최신 배포 버전: v$serverVersion\n\n" +
                "최신 버전에는 초기 검은 화면 방지, 0MB 로컬 WebRTC 초저지연 전송, 정밀 터치 엔진 개선 및 안정성 패치가 적용되어 있습니다.\n\n" +
                "⚠️ [구글 플레이 스토어 설치 기기 안내]\n" +
                "플레이 스토어로 설치하신 경우 아래 [🚀 Google Play]에서 바로 업데이트하실 수 있습니다."
            )
            .setPositiveButton("📥 최신 APK 다운로드 (추천)") { _, _ ->
                downloadDirectApk(activity, serverVersion)
            }
            .setNeutralButton("🚀 Google Play") { _, _ ->
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
