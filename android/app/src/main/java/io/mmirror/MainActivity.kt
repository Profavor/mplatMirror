package io.mmirror

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.mmirror.databinding.ActivityMainBinding
import android.app.ActivityOptions
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.DialogInterface
import android.widget.LinearLayout
import android.net.Uri
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        var instance: MainActivity? = null
            private set
    }

    private lateinit var binding: ActivityMainBinding
    private var serverPort = 8282
    private var hasShownUpdateDialogThisSession = false
    private var latestServerVersion: String? = null

    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            AppLogger.i(TAG, "✓ 사용자 화면 캡처 권한 승인됨 (RESULT_OK)")
            startMirroringService(result.resultCode, result.data!!)
        } else {
            AppLogger.e(TAG, "❌ 화면 캡처 권한 거부 또는 취소됨 (code=${result.resultCode})")
            Toast.makeText(this, "화면 캡처 권한이 취소되었습니다.", Toast.LENGTH_SHORT).show()
            updateUIState()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val denied = perms.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            AppLogger.w(TAG, "⚠️ 런타임 권한 거부됨: $denied")
        } else {
            AppLogger.i(TAG, "✓ 모든 런타임 권한 승인됨")
        }
        requestScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 전원 버튼으로 화면이 꺼지거나 잠겼을 때도 앱이 유지되도록 설정
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        checkBatteryOptimization()
        setupListeners()
        ensureServerRunning()

        ScreenDimmerManager.init(applicationContext)
        ScreenDimmerManager.addListener(dimmerListener)

        // 뒤로가기 키 입력 시 앱이 종료되어 미러링이 중단되지 않고 백그라운드로 안전하게 전환
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    private val dimmerListener: (Boolean) -> Unit = { dimmed ->
        setWindowDimmed(dimmed)
    }

    override fun onDestroy() {
        ScreenDimmerManager.removeListener(dimmerListener)
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun ensureServerRunning() {
        try {
            val port = NativeBridge.startServer(serverPort)
            if (port > 0) {
                if (port != serverPort) {
                    serverPort = port
                }
                android.util.Log.i("MainActivity", "mMirror HTTP & WebSocket server is running on port $serverPort")
            } else {
                android.util.Log.e("MainActivity", "Failed to start Rust server on any port")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error starting Rust server", e)
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val prefs = getSharedPreferences("mmirror_prefs", Context.MODE_PRIVATE)
                val alreadyPrompted = prefs.getBoolean("battery_opt_prompted", false)
                if (!alreadyPrompted) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("백그라운드 미러링 절전 예외 안내")
                        .setMessage("주행 중 스마트폰 화면 꺼짐이나 절전 모드로 인해 미러링이 중단되는 현상을 방지하기 위해 배터리 최적화 예외(제한 없음) 등록을 권장합니다.")
                        .setPositiveButton("설정하기") { _, _ ->
                            prefs.edit().putBoolean("battery_opt_prompted", true).apply()
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:$packageName")
                                }
                                startActivity(intent)
                            } catch (_: Exception) {}
                        }
                        .setNegativeButton("나중에") { _, _ ->
                            prefs.edit().putBoolean("battery_opt_prompted", true).apply()
                        }
                        .show()
                }
            }
        }
    }

    private var networkPollJob: Job? = null

    override fun onResume() {
        super.onResume()
        ensureServerRunning()

        // 삼성페이 간섭 방지: 순수 WebRTC 로컬 P2P 직결로 구동되어 VPN 없이 UI 상태만 갱신합니다.

        updateNetworkAddress()
        updateBluetoothStatus()
        updateAccessibilityStatus()
        updateWriteSettingsStatus()
        updateUIState()
        binding.layoutFoldContinuity.visibility = if (isGalaxyZDevice()) View.VISIBLE else View.GONE
        reportHotspotIpToRelay()
        checkServerVersionFromApi()

        networkPollJob?.cancel()
        networkPollJob = lifecycleScope.launch {
            while (isActive) {
                delay(1200)
                updateNetworkAddress()
                updateBluetoothStatus()
                updateAccessibilityStatus()
                updateWriteSettingsStatus()
                updateUIState()

                if (MediaProjectionService.isRunning && !isWifiApEnabled()) {
                    AppLogger.w(TAG, "⚠️ 모바일 핫스팟이 꺼져 미러링을 자동 중단합니다 (0MB 데이터 보호)")
                    stopMirroringService()
                    Toast.makeText(this@MainActivity, "모바일 핫스팟이 꺼져 미러링을 안전하게 중단했습니다.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        networkPollJob?.cancel()
    }

    private fun setupListeners() {
        val ver = BuildConfig.VERSION_NAME
        binding.tvAppVersionBadge.text = "v$ver"
        binding.tvVersionInfo.text = "mplat Mirror v$ver · Apache License 2.0"

        binding.btnDownloadUpdate.setOnClickListener {
            openDownloadUrl()
        }
        binding.cardUpdateNotice.setOnClickListener {
            latestServerVersion?.let { showUpdateDialog(it) } ?: openDownloadUrl()
        }
        binding.tvAppVersionBadge.setOnClickListener {
            latestServerVersion?.let { showUpdateDialog(it) }
        }

        binding.btnCopyAddress.setOnClickListener {
            if (!isWifiApEnabled()) {
                showHotspotRequiredDialog()
                return@setOnClickListener
            }
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val textToCopy = binding.tvTeslaAddress.text.toString()
                val clip = ClipData.newPlainText("Tesla Address", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 주소($textToCopy)가 복사되었습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "복사 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvSecondaryAddress.setOnClickListener {
            if (!isWifiApEnabled()) {
                showHotspotRequiredDialog()
                return@setOnClickListener
            }
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val hotspotIp = getHotspotIp()
                val textToCopy = "http://$hotspotIp:8282"
                val clip = ClipData.newPlainText("Tablet Address", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 태블릿/PC 주소($textToCopy)가 복사되었습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "복사 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnOpenHotspot.setOnClickListener {
            openHotspotSettings()
        }

        binding.btnOpenBluetooth.setOnClickListener {
            openBluetoothSettings()
        }

        binding.btnViewLogs.setOnClickListener {
            showDiagnosticLogsDialog()
        }

        binding.btnDimScreen.setOnClickListener {
            ScreenDimmerManager.toggle()
        }

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.btnOpenWriteSettings.setOnClickListener {
            openWriteSettings()
        }

        binding.btnToggleMirroring.setOnClickListener {
            if (MediaProjectionService.isRunning) {
                stopMirroringService()
            } else {
                checkPermissionsAndStart()
            }
        }

        binding.btnViewLicenses.setOnClickListener {
            showLicenseDialog()
        }

        binding.btnOpenTripLog.setOnClickListener {
            showTripLogDialog()
        }

        binding.cardConnectionGuide.setOnClickListener {
            showConnectionManualDialog()
        }

        binding.btnViewDetailedGuide.setOnClickListener {
            showConnectionManualDialog()
        }

        setupResolutionSelector()
        setupSmartLockSettings()
    }

    private fun setupSmartLockSettings() {
        binding.btnOpenSmartLock.setOnClickListener {
            openSmartLockSettings()
        }

        val prefs = getSharedPreferences("mmirror_prefs", Context.MODE_PRIVATE)
        val stayAwakeSaved = prefs.getBoolean("stay_awake", true)
        binding.switchStayAwake.isChecked = stayAwakeSaved
        updateWindowKeepScreenOn(stayAwakeSaved)
        binding.switchStayAwake.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("stay_awake", isChecked).apply()
            updateWindowKeepScreenOn(isChecked)
            if (MediaProjectionService.isRunning) {
                MediaProjectionService.instance?.updateStayAwake()
            }
            Toast.makeText(this, if (isChecked) "🔋 미러링 중 화면 꺼짐 방지(Stay Awake) 켜짐" else "화면 꺼짐 방지 꺼짐", Toast.LENGTH_SHORT).show()
        }

        val autoDismissSaved = prefs.getBoolean("auto_dismiss_keyguard", true)
        binding.switchAutoDismissKeyguard.isChecked = autoDismissSaved
        binding.switchAutoDismissKeyguard.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_dismiss_keyguard", isChecked).apply()
            if (MediaProjectionService.isRunning) {
                MediaProjectionService.instance?.updateStayAwake()
            }
            Toast.makeText(this, if (isChecked) "🔓 미러링 중 화면 켜짐 유지 켜짐" else "화면 켜짐 유지 꺼짐", Toast.LENGTH_SHORT).show()
        }

        val abrSaved = prefs.getBoolean("pref_adaptive_bitrate", true)
        binding.switchAdaptiveBitrate.isChecked = abrSaved
        binding.switchAdaptiveBitrate.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_adaptive_bitrate", isChecked).apply()
            if (MediaProjectionService.isRunning) {
                MediaProjectionService.instance?.setAdaptiveBitrateEnabled(isChecked)
            }
            Toast.makeText(
                this,
                if (isChecked) "⚡ 적응형 스트리밍 자동 최적화(ABR) 켜짐" else "적응형 스트리밍 꺼짐 (고정 대역폭)",
                Toast.LENGTH_SHORT
            ).show()
        }

        val audioStreamSaved = prefs.getBoolean("pref_audio_stream_enabled", false)
        binding.switchAudioStreaming.isChecked = audioStreamSaved
        binding.switchAudioStreaming.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_audio_stream_enabled", isChecked).apply()
            if (MediaProjectionService.isRunning) {
                MediaProjectionService.instance?.setAudioStreamingEnabled(isChecked)
            }
            Toast.makeText(
                this,
                if (isChecked) "🌐 웹 브라우저 사운드 송출 켜짐 (테슬라 브라우저로 소리 전송)" else "🚗 차량 블루투스 직결 모드 (0ms 무손실 전송)",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (isGalaxyZDevice()) {
            binding.layoutFoldContinuity.visibility = View.VISIBLE
            binding.btnOpenFoldContinuity.setOnClickListener {
                openCoverScreenContinuitySettings()
            }
        } else {
            binding.layoutFoldContinuity.visibility = View.GONE
        }
    }

    private fun updateWindowKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun openSmartLockSettings() = FoldableDeviceHelper.openSmartLockSettings(this)
    fun isGalaxyZDevice(): Boolean = FoldableDeviceHelper.isGalaxyZDevice(this)
    fun openCoverScreenContinuitySettings() = FoldableDeviceHelper.openCoverScreenContinuitySettings(this)
    private fun checkAndPromptFoldContinuity() = FoldableDeviceHelper.checkAndPromptFoldContinuity(this)

    private fun updateResolutionUI() {
        binding.tvCurrentResolutionBadge.text = "720p HD"
        binding.tvCurrentResolutionDesc.text = "📱 스마트폰 720p 고화질 최적화 (여백 0% · 60 FPS)\n스마트폰 원본 비율 100% 유지 · 화면 끊김/지직거림 완벽 방지"
    }

    private fun setupResolutionSelector() {
        updateResolutionUI()
        binding.cardResolutionPreset.setOnClickListener {
            Toast.makeText(this, "스마트폰 원본 비율 유지 720p 고화질(60 FPS) 모드로 최적화되어 있습니다.", Toast.LENGTH_SHORT).show()
        }
    }


    private fun showConnectionManualDialog() = CommonDialogHelper.showConnectionManualDialog(this)
    private fun showTripLogDialog() = CommonDialogHelper.showTripLogDialog(this)
    private fun showLicenseDialog() = CommonDialogHelper.showLicenseDialog(this) { openPrivacyPolicy() }
    private fun openPrivacyPolicy() = SystemNavigationHelper.openPrivacyPolicy(this)

    private fun checkPermissionsAndStart() {
        if (!isWifiApEnabled()) {
            showHotspotRequiredDialog()
            return
        }

        val prefs = getSharedPreferences("mmirror_prefs", Context.MODE_PRIVATE)
        val hasAcceptedSafety = prefs.getBoolean("has_accepted_safety_v1", false)
        if (!hasAcceptedSafety) {
            showSafetyAgreementDialog {
                checkPermissionsAndStart()
            }
            return
        }

        proceedPermissionsAndStart()
    }

    private fun showSafetyAgreementDialog(onAccept: () -> Unit) =
        CommonDialogHelper.showSafetyAgreementDialog(this, onAccept)

    private fun proceedPermissionsAndStart() {
        if (!isWifiApEnabled()) {
            showHotspotRequiredDialog()
            return
        }
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (One UI 6/7): "앱 1개 선택" 선택지를 원천 차단하고 기본 전체 화면으로 고정
                val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                projectionManager.createScreenCaptureIntent(config)
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            screenCaptureLauncher.launch(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to launch screen capture intent", e)
            Toast.makeText(this, "화면 캡처 요청 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startMirroringService(resultCode: Int, data: Intent) {
        if (!isWifiApEnabled()) {
            Toast.makeText(this, "모바일 핫스팟이 꺼져 있어 미러링을 시작할 수 없습니다.", Toast.LENGTH_LONG).show()
            showHotspotRequiredDialog()
            return
        }
        val currentPort = NativeBridge.getServerPort().takeIf { it > 0 } ?: serverPort
        val isStandalone = binding.switchVirtualDisplay.isChecked
        val serviceIntent = Intent(this, MediaProjectionService::class.java).apply {
            action = MediaProjectionService.ACTION_START
            putExtra(MediaProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MediaProjectionService.EXTRA_RESULT_DATA, data)
            putExtra(MediaProjectionService.EXTRA_PORT, currentPort)
            putExtra(MediaProjectionService.EXTRA_STANDALONE, isStandalone)
            putExtra(MediaProjectionService.EXTRA_RESOLUTION_PRESET, ResolutionPreset.DEFAULT.id)
            putExtra(MediaProjectionService.EXTRA_AUTO_MIRROR_UNSUPPORTED, binding.switchAutoMirror.isChecked)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        reportHotspotIpToRelay()
        updateUIState()
        if (isGalaxyZDevice()) {
            checkAndPromptFoldContinuity()
        }
        val modeStr = if (isStandalone) "독립 가상화면(720p HD)" else "단말 화면 미러링(720p HD)"
        Toast.makeText(this, "화면 송출 시작: $modeStr", Toast.LENGTH_SHORT).show()
    }

    private fun stopMirroringService() {
        val serviceIntent = Intent(this, MediaProjectionService::class.java).apply {
            action = MediaProjectionService.ACTION_STOP
        }
        startService(serviceIntent)
        updateUIState()
        Toast.makeText(this, "미러링이 중지되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun reportHotspotIpToRelay() {
        val ip = getHotspotIp()
        val currentVer = BuildConfig.VERSION_NAME
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val currentPort = NativeBridge.getServerPort().takeIf { it > 0 } ?: serverPort
                val json = org.json.JSONObject().apply {
                    put("local_ip", ip)
                    put("port", currentPort)
                    put("app_version", currentVer)
                }.toString()
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val body = json.toRequestBody(mediaType)
                val request = okhttp3.Request.Builder()
                    .url("https://mdm.mplat.store:8088/api/register_host")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val respBody = response.body?.string()
                        if (respBody != null) {
                            val respJson = org.json.JSONObject(respBody)
                            val serverVer = respJson.optString("server_version", "")
                            if (serverVer.isNotEmpty()) {
                                checkAppVersionWithServer(serverVer)
                            }
                        }
                        android.util.Log.i("MainActivity", "Hotspot IP $ip registered to relay")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Failed to register host to relay: ${e.message}")
            }
        }
    }

    private fun checkServerVersionFromApi() {
        AppUpdateManager.checkServerVersionFromApi(lifecycleScope, httpClient) { serverVer ->
            checkAppVersionWithServer(serverVer)
        }
    }

    private fun checkAppVersionWithServer(serverVersion: String) {
        val currentVersion = BuildConfig.VERSION_NAME
        latestServerVersion = serverVersion
        val isMismatch = serverVersion.isNotEmpty() && serverVersion != currentVersion

        runOnUiThread {
            if (isMismatch) {
                binding.cardUpdateNotice.visibility = View.VISIBLE
                binding.tvUpdateNoticeTitle.text = "최신 버전(v$serverVersion) 업데이트 안내"
                binding.tvUpdateNoticeDesc.text = "현재 버전: v$currentVersion · 릴레이 서버(v$serverVersion)와 버전이 다릅니다. 안정적인 연결을 위해 업데이트하세요."
                binding.tvAppVersionBadge.text = "v$currentVersion ⚠️ v$serverVersion"
                binding.tvAppVersionBadge.setTextColor(0xFFF59E0B.toInt())

                if (!hasShownUpdateDialogThisSession) {
                    hasShownUpdateDialogThisSession = true
                    showUpdateDialog(serverVersion)
                }
            } else {
                binding.cardUpdateNotice.visibility = View.GONE
                binding.tvAppVersionBadge.text = "v$currentVersion"
                binding.tvAppVersionBadge.setTextColor(0xFF3498DB.toInt())
            }
        }
    }

    private fun showUpdateDialog(serverVersion: String) {
        AppUpdateManager.showUpdateDialog(this, serverVersion)
    }

    private fun downloadDirectApk() {
        AppUpdateManager.downloadDirectApk(this, latestServerVersion)
    }

    private fun openGooglePlayStore() {
        SystemNavigationHelper.openGooglePlayStore(this)
    }

    private fun openDownloadUrl() {
        latestServerVersion?.let { showUpdateDialog(it) } ?: downloadDirectApk()
    }

    private fun updateUIState() {
        if (MediaProjectionService.isRunning) {
            binding.btnToggleMirroring.text = "🛑 미러링 중지"
            binding.btnToggleMirroring.setBackgroundColor(0xFFE74C3C.toInt())
            binding.tvStreamStatus.text = "🟢 테슬라 브라우저로 실시간 60FPS 전송 중"
            binding.tvStreamStatus.setTextColor(0xFF2ECC71.toInt())
            binding.btnDimScreen.visibility = View.VISIBLE
            binding.btnDimScreen.text = if (ScreenDimmerManager.isDimmed) "☀️ 스마트폰 화면 켜기 (밝기 복원)" else "🌙 스마트폰 화면 끄기 (초절전 암전)"
            binding.btnDimScreen.setBackgroundColor(if (ScreenDimmerManager.isDimmed) 0xFF27AE60.toInt() else 0xFF2D303A.toInt())
        } else {
            binding.btnDimScreen.visibility = View.GONE
            if (ScreenDimmerManager.isDimmed) {
                ScreenDimmerManager.setDimmed(false)
            }
            binding.btnToggleMirroring.text = "🚗 테슬라 미러링 시작"
            binding.btnToggleMirroring.setBackgroundColor(0xFF27AE60.toInt())
            binding.tvStreamStatus.text = "✓ 원클릭 준비 완료 · 테슬라 브라우저로 60FPS 직결"
            binding.tvStreamStatus.setTextColor(0xFF2ECC71.toInt())
        }
    }

    fun setWindowDimmed(dimmed: Boolean) {
        runOnUiThread {
            try {
                val lp = window.attributes
                lp.screenBrightness = if (dimmed) 0.001f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            } catch (_: Exception) {}

            binding.btnDimScreen.text = if (dimmed) "☀️ 스마트폰 화면 켜기 (밝기 복원)" else "🌙 스마트폰 화면 끄기 (초절전 암전)"
            binding.btnDimScreen.setBackgroundColor(if (dimmed) 0xFF27AE60.toInt() else 0xFF2D303A.toInt())
        }
    }

    private fun openBluetoothSettings() = SystemNavigationHelper.openBluetoothSettings(this)

    private fun getBluetoothAudioStatus(): Pair<Boolean, String?> = DiagnosticReportManager.getBluetoothAudioStatus(this)

    private fun updateBluetoothStatus() {
        val (isConnected, deviceName) = getBluetoothAudioStatus()

        if (isConnected) {
            binding.tvBluetoothStatus.text = "✓ 연결됨: $deviceName (차량 A2DP 직결 출력 중)"
            binding.tvBluetoothStatus.setTextColor(0xFF2ECC71.toInt())
            binding.btnOpenBluetooth.text = "설정 확인"
            binding.btnOpenBluetooth.setBackgroundColor(0xFF27AE60.toInt())

            binding.tvSmartLockStatus.text = "✓ $deviceName 연결됨 (Smart Lock 등록 시 잠금 자동 해제)"
            binding.tvSmartLockStatus.setTextColor(0xFF2ECC71.toInt())
        } else {
            binding.tvBluetoothStatus.text = "미연결 (차량 블루투스를 연결하여 원음으로 감상하세요)"
            binding.tvBluetoothStatus.setTextColor(0xFFE67E22.toInt())
            binding.btnOpenBluetooth.text = "블루투스 연결"
            binding.btnOpenBluetooth.setBackgroundColor(0xFF2980B9.toInt())

            binding.tvSmartLockStatus.text = "차량 BT 미연결 (테슬라 BT를 Smart Lock 기기로 등록 권장)"
            binding.tvSmartLockStatus.setTextColor(0xFFA0A5B1.toInt())
        }
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = TouchControlService.isAccessibilityServiceEnabled(this)
        if (isEnabled) {
            binding.tvAccessibilityStatus.text = "✓ 접근성 켜짐 (테슬라 화면 터치 100% 가능)"
            binding.tvAccessibilityStatus.setTextColor(0xFF2ECC71.toInt())
            binding.btnOpenAccessibilitySettings.text = "설정 완료"
            binding.btnOpenAccessibilitySettings.setBackgroundColor(0xFF27AE60.toInt())
        } else {
            binding.tvAccessibilityStatus.text = "미연결 (터치 조작을 원하시면 접근성을 켜주세요)"
            binding.tvAccessibilityStatus.setTextColor(0xFFF39C12.toInt())
            binding.btnOpenAccessibilitySettings.text = "접근성 켜기"
            binding.btnOpenAccessibilitySettings.setBackgroundColor(0xFF3498DB.toInt())
        }
    }

    private fun updateWriteSettingsStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val canWrite = Settings.System.canWrite(this)

            if (canWrite) {
                binding.tvWriteSettingsStatus.text = "✓ 절전 권한 활성 (티맵 실행 중 테슬라 절전 100% 가능)"
                binding.tvWriteSettingsStatus.setTextColor(0xFF2ECC71.toInt())
                binding.btnOpenWriteSettings.text = "설정 완료"
                binding.btnOpenWriteSettings.setBackgroundColor(0xFF27AE60.toInt())
            } else {
                binding.tvWriteSettingsStatus.text = "미허용 (티맵 실행 중 절전을 위해 권한 필요)"
                binding.tvWriteSettingsStatus.setTextColor(0xFFF39C12.toInt())
                binding.btnOpenWriteSettings.text = "권한 허용"
                binding.btnOpenWriteSettings.setBackgroundColor(0xFF9B59B6.toInt())
            }
        } else {
            binding.tvWriteSettingsStatus.text = "✓ 절전 권한 지원 (안드로이드 5 이하 기본 지원)"
            binding.tvWriteSettingsStatus.setTextColor(0xFF2ECC71.toInt())
            binding.btnOpenWriteSettings.text = "설정 완료"
            binding.btnOpenWriteSettings.setBackgroundColor(0xFF27AE60.toInt())
        }
    }

    private fun openWriteSettings() = SystemNavigationHelper.openWriteSettings(this)
    private fun openAccessibilitySettings() = SystemNavigationHelper.openAccessibilitySettings(this)
    private fun openHotspotSettings() = SystemNavigationHelper.openHotspotSettings(this)
    private fun showHotspotRequiredDialog() = CommonDialogHelper.showHotspotRequiredDialog(this) { openHotspotSettings() }

    private fun isWifiApEnabled(): Boolean = NetworkUtils.isWifiApEnabled(this)

    private fun getHotspotIp(): String = NetworkUtils.getHotspotIp()

    private fun updateNetworkAddress() {
        val apEnabled = isWifiApEnabled()
        val hotspotIp = if (apEnabled) getHotspotIp() else NetworkUtils.DEFAULT_HOTSPOT_IP

        binding.tvTeslaAddress.text = "https://mplat-mirror.web.app"

        if (apEnabled) {
            binding.tvSecondaryAddress.text = "태블릿/PC: http://$hotspotIp:8282"
            binding.tvHotspotStatus.text = "✓ 핫스팟 켜짐 ($hotspotIp) - 태블릿/테슬라 Wi-Fi 연결 대기"
            binding.tvHotspotStatus.setTextColor(0xFF2ECC71.toInt())
            binding.btnOpenHotspot.text = "✓ 핫스팟 켜짐"
            binding.btnOpenHotspot.setBackgroundColor(0xFF2D303A.toInt())
        } else {
            binding.tvSecondaryAddress.text = "태블릿/PC: 핫스팟을 켜면 로컬 IP(8282)가 표시됩니다"
            binding.tvHotspotStatus.text = "⚠️ 모바일 핫스팟 꺼짐 - 미러링을 위해 켜주세요"
            binding.tvHotspotStatus.setTextColor(0xFFE67E22.toInt())
            binding.btnOpenHotspot.text = "핫스팟 켜기"
            binding.btnOpenHotspot.setBackgroundColor(0xFFE67E22.toInt())
        }
    }

    private fun showDiagnosticLogsDialog() {
        DiagnosticReportManager.showDiagnosticLogsDialog(this)
    }
}
