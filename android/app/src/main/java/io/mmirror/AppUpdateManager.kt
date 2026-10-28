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
import java.util.concurrent.TimeUnit

/**
 * mMirror 앱 최신 배포 버전 확인 및 업데이트 안내 관리자
 * 
 * - 3중 다중화(Triple-Redundant) 버전 확인: Firebase Hosting CDN -> GitHub Raw CDN -> GitHub Releases API
 * - 캐시 무효화 쿼리(?t=...) 적용으로 통신사 프록시/CDN 엣지 캐시 원천 우회
 * - 10초 독립 타임아웃 및 재시도 보장
 */
object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    const val APK_DOWNLOAD_URL = "https://github.com/Profavor/mplatMirror/releases/latest/download/inyou-mirror.apk"
    private const val CDN_VERSION_URL = "https://inyou-mirror.web.app/version.json"
    private const val GITHUB_RAW_URL = "https://raw.githubusercontent.com/Profavor/mplatMirror/main/version.json"
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/Profavor/mplatMirror/releases/latest"

    data class UpdateInfo(
        val version: String,
        val versionCode: Int = 0,
        val downloadUrl: String = APK_DOWNLOAD_URL,
        val playStoreUrl: String = "",
        val releaseNotes: String = ""
    )

    // 모바일 셀룰러 / 핫스팟 환경에서도 안정적으로 동작하도록 전용 10초 타임아웃 OkHttpClient 구성
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

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
     * 3중 다중화(CDN -> GitHub Raw -> GitHub Releases) 최신 빌드 버전 조회
     */
    fun checkServerVersion(
        scope: CoroutineScope,
        onResult: (updateInfo: UpdateInfo?, errorMsg: String?) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val nowTs = System.currentTimeMillis()
            var lastError: String? = null

            // 1순위: Firebase Hosting CDN version.json (?t= 캐시 버스팅)
            try {
                val req = Request.Builder()
                    .url("$CDN_VERSION_URL?t=$nowTs")
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            val ver = json.optString("version", "").trim().removePrefix("v")
                            if (ver.isNotEmpty()) {
                                Log.i(TAG, "✓ Version fetched from Firebase CDN: v$ver")
                                val info = UpdateInfo(
                                    version = ver,
                                    versionCode = json.optInt("versionCode", 0),
                                    downloadUrl = json.optString("downloadUrl", APK_DOWNLOAD_URL),
                                    playStoreUrl = json.optString("playStoreUrl", ""),
                                    releaseNotes = json.optString("releaseNotes", "")
                                )
                                onResult(info, null)
                                return@launch
                            }
                        }
                    } else {
                        lastError = "CDN 응답 오류 (HTTP ${resp.code})"
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Firebase CDN 연결 실패"
                Log.w(TAG, "Firebase CDN check failed: ${e.message}, falling back to GitHub Raw")
            }

            // 2순위: GitHub Raw CDN version.json (API Rate Limit 없음 & 캐시 버스팅)
            try {
                val req = Request.Builder()
                    .url("$GITHUB_RAW_URL?t=$nowTs")
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            val ver = json.optString("version", "").trim().removePrefix("v")
                            if (ver.isNotEmpty()) {
                                Log.i(TAG, "✓ Version fetched from GitHub Raw: v$ver")
                                val info = UpdateInfo(
                                    version = ver,
                                    versionCode = json.optInt("versionCode", 0),
                                    downloadUrl = json.optString("downloadUrl", APK_DOWNLOAD_URL),
                                    playStoreUrl = json.optString("playStoreUrl", ""),
                                    releaseNotes = json.optString("releaseNotes", "")
                                )
                                onResult(info, null)
                                return@launch
                            }
                        }
                    } else {
                        lastError = "GitHub Raw 응답 오류 (HTTP ${resp.code})"
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "GitHub Raw 연결 실패"
                Log.w(TAG, "GitHub Raw check failed: ${e.message}, falling back to GitHub API")
            }

            // 3순위: GitHub Releases 공식 API 조회
            try {
                val req = Request.Builder()
                    .url(GITHUB_RELEASES_URL)
                    .header("User-Agent", "inYouMirror-Android")
                    .header("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            val tagName = json.optString("tag_name", "").trim().removePrefix("v")
                            if (tagName.isNotEmpty()) {
                                Log.i(TAG, "✓ Version fetched from GitHub API: v$tagName")
                                val info = UpdateInfo(
                                    version = tagName,
                                    releaseNotes = json.optString("body", "").trim()
                                )
                                onResult(info, null)
                                return@launch
                            }
                        }
                    } else {
                        lastError = "GitHub API 응답 오류 (HTTP ${resp.code})"
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "GitHub API 연결 실패"
                Log.w(TAG, "GitHub API check failed: ${e.message}")
            }

            // 모든 확인 경로 실패 시 에러 콜백
            onResult(null, lastError ?: "네트워크 상태를 확인해 주세요.")
        }
    }

    /**
     * 레거시 호환 오버로드
     */
    fun checkServerVersionFromApi(
        scope: CoroutineScope,
        httpClient: OkHttpClient? = null,
        onVersionFetched: (serverVersion: String) -> Unit
    ) {
        checkServerVersion(scope) { info, _ ->
            if (info != null) {
                onVersionFetched(info.version)
            }
        }
    }

    /**
     * 업데이트 알림 다이얼로그 노출
     */
    fun showUpdateDialog(
        activity: Activity,
        updateInfo: UpdateInfo,
        currentVersion: String = BuildConfig.VERSION_NAME
    ) {
        val notesSection = if (updateInfo.releaseNotes.isNotBlank()) {
            "• 주요 개선 및 패치 내역:\n${updateInfo.releaseNotes}\n\n"
        } else {
            "최신 버전에는 0MB 로컬 WebRTC 초저지연 전송 및 안정성 패치가 적용되어 있습니다.\n\n"
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("🚀 최신 버전 업데이트 (v${updateInfo.version})")
            .setMessage(
                "새로운 최신 버전이 출시되었습니다.\n\n" +
                "• 현재 앱 버전: v$currentVersion\n" +
                "• 최신 배포 버전: v${updateInfo.version}\n\n" +
                notesSection +
                "⚠️ [구글 플레이 스토어 설치 기기 안내]\n" +
                "플레이 스토어로 설치하신 경우 아래 [🚀 Google Play]에서 바로 업데이트하실 수 있습니다."
            )
            .setPositiveButton("📥 최신 APK 다운로드 (추천)") { _, _ ->
                downloadDirectApk(activity, updateInfo.version, updateInfo.downloadUrl)
            }
            .setNeutralButton("🚀 Google Play") { _, _ ->
                SystemNavigationHelper.openGooglePlayStore(activity)
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    fun showUpdateDialog(
        activity: Activity,
        serverVersion: String,
        currentVersion: String = BuildConfig.VERSION_NAME
    ) {
        showUpdateDialog(activity, UpdateInfo(version = serverVersion), currentVersion)
    }

    /**
     * 최신 배포 APK 직접 다운로드 브라우저 인텐트 호출
     */
    fun downloadDirectApk(
        context: Context,
        serverVersion: String? = null,
        customUrl: String = APK_DOWNLOAD_URL
    ) {
        try {
            val downloadUri = if (customUrl.isNotBlank()) customUrl else APK_DOWNLOAD_URL
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUri)).apply {
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
