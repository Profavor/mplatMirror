package io.mmirror

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager as PlayAppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * mMirror 구글 플레이 스토어 전용 앱 업데이트 관리자
 *
 * 1. Google Play In-App Update API 공식 연동
 *    - 구글 플레이 스토어 기준 최신 버전 감지 (UpdateAvailability.UPDATE_AVAILABLE)
 *    - 구글 플레이 순정 인앱 즉시 업데이트(IMMEDIATE) 흐름 구동
 * 2. 구글 플레이 마켓 직결 (market://details?id=io.mmirror)
 *    - 플레이 스토어 앱 상세 페이지로 즉시 이동하여 [업데이트] 유도
 * 3. 3중 다중화 보조 버전 확인 (Firebase CDN -> GitHub Raw -> GitHub Releases)
 *    - 구글 플레이 배포 상태 동기화 및 릴리즈 노트 제공
 *    - 모든 업데이트 동작은 100% 구글 플레이 스토어로만 연결
 */
object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    const val REQUEST_CODE_PLAY_UPDATE = 9001
    const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=io.mmirror"

    private const val CDN_VERSION_URL = "https://inyou-mirror.web.app/version.json"
    private const val GITHUB_RAW_URL = "https://raw.githubusercontent.com/Profavor/mplatMirror/main/version.json"
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/Profavor/mplatMirror/releases/latest"

    data class UpdateInfo(
        val version: String,
        val versionCode: Int = 0,
        val playStoreUrl: String = PLAY_STORE_URL,
        val releaseNotes: String = ""
    )

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
     * 1순위: Google Play In-App Update API로 구글 플레이 스토어 기준 최신 버전 확인
     */
    fun checkPlayStoreInAppUpdate(
        activity: Activity,
        onResult: (isAvailable: Boolean, appUpdateInfo: AppUpdateInfo?, playAppUpdateManager: PlayAppUpdateManager?) -> Unit
    ) {
        try {
            val playManager = AppUpdateManagerFactory.create(activity)
            val appUpdateInfoTask = playManager.appUpdateInfo
            appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
                val isAvailable = appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                val canImmediate = appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                Log.i(TAG, "Play Store In-App Update check: availability=${appUpdateInfo.updateAvailability()}, canImmediate=$canImmediate, verCode=${appUpdateInfo.availableVersionCode()}")
                onResult(isAvailable, appUpdateInfo, playManager)
            }.addOnFailureListener { e ->
                Log.w(TAG, "Play Store In-App Update check failed: ${e.message}")
                onResult(false, null, playManager)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PlayAppUpdateManager: ${e.message}")
            onResult(false, null, null)
        }
    }

    /**
     * 구글 플레이 순정 인앱 즉시 업데이트(IMMEDIATE) 실행
     * (실패 시 구글 플레이 스토어 앱으로 바로 연결)
     */
    fun launchPlayStoreImmediateUpdate(
        activity: Activity,
        playManager: PlayAppUpdateManager,
        appUpdateInfo: AppUpdateInfo
    ): Boolean {
        return try {
            val options = AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
            playManager.startUpdateFlowForResult(appUpdateInfo, activity, options, REQUEST_CODE_PLAY_UPDATE)
            Log.i(TAG, "✓ Google Play immediate in-app update flow started successfully")
            true
        } catch (e: Exception) {
            Log.w(TAG, "startUpdateFlowForResult failed: ${e.message}, falling back to Play Store app")
            openGooglePlayStore(activity)
            false
        }
    }

    /**
     * onResume에서 이미 진행 중이던 인앱 업데이트 복구
     */
    fun resumeInProgressUpdateIfAny(activity: Activity) {
        try {
            val playManager = AppUpdateManagerFactory.create(activity)
            playManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    val options = AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    playManager.startUpdateFlowForResult(appUpdateInfo, activity, options, REQUEST_CODE_PLAY_UPDATE)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 3중 다중화(CDN -> GitHub Raw -> GitHub Releases) 구글 플레이 배포 버전 조회
     */
    fun checkServerVersion(
        scope: CoroutineScope,
        onResult: (updateInfo: UpdateInfo?, errorMsg: String?) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val nowTs = System.currentTimeMillis()
            var lastError: String? = null

            // 1순위: Firebase Hosting CDN version.json
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
                                    playStoreUrl = json.optString("playStoreUrl", PLAY_STORE_URL),
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
            }

            // 2순위: GitHub Raw CDN version.json
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
                                    playStoreUrl = json.optString("playStoreUrl", PLAY_STORE_URL),
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
            }

            // 3순위: GitHub Releases 공식 API
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
            }

            onResult(null, lastError ?: "네트워크 상태를 확인해 주세요.")
        }
    }

    /**
     * 구글 플레이 스토어 전용 업데이트 다이얼로그 노출
     */
    fun showPlayStoreUpdateDialog(
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
            .setTitle("🚀 Google Play 최신 버전 업데이트 (v${updateInfo.version})")
            .setMessage(
                "Google Play 스토어에 새로운 최신 버전이 출시되었습니다.\n\n" +
                "• 현재 앱 버전: v$currentVersion\n" +
                "• 최신 배포 버전: v${updateInfo.version}\n\n" +
                notesSection +
                "Google Play 스토어에서 바로 최신 버전으로 업데이트하세요."
            )
            .setPositiveButton("🚀 Google Play 스토어에서 업데이트") { _, _ ->
                openGooglePlayStore(activity)
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    /**
     * 레거시 호환 오버로드
     */
    fun showUpdateDialog(
        activity: Activity,
        updateInfo: UpdateInfo,
        currentVersion: String = BuildConfig.VERSION_NAME
    ) {
        showPlayStoreUpdateDialog(activity, updateInfo, currentVersion)
    }

    fun showUpdateDialog(
        activity: Activity,
        serverVersion: String,
        currentVersion: String = BuildConfig.VERSION_NAME
    ) {
        showPlayStoreUpdateDialog(activity, UpdateInfo(version = serverVersion), currentVersion)
    }

    /**
     * 구글 플레이 스토어 앱 직접 실행 (무조건 구글 플레이로만 업데이트)
     */
    fun openGooglePlayStore(context: Context) {
        SystemNavigationHelper.openGooglePlayStore(context)
    }
}
