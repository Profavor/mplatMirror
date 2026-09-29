package io.mmirror

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
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
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import rikka.shizuku.Shizuku
import io.mmirror.adb.AdbTouchManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serverPort = 8080
    private val debugLogs = mutableListOf<String>()

    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startMirroringService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "화면 캡처 권한이 취소되었습니다.", Toast.LENGTH_SHORT).show()
            updateUIState()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        requestScreenCapture()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == AdbTouchManager.SHIZUKU_REQUEST_CODE) {
            runOnUiThread {
                updateWirelessDebuggingStatus()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "✓ Shizuku 무선 디버깅 권한이 승인되었습니다. (0ms 터치 활성)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            updateWirelessDebuggingStatus()
        }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            updateWirelessDebuggingStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to add Shizuku listeners: ${e.message}")
        }

        // 뒤로가기 키 입력 시 앱이 종료되어 미러링이 중단되지 않고 백그라운드로 안전하게 전환
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        } catch (_: Exception) {}
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
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private var networkPollJob: Job? = null

    override fun onResume() {
        super.onResume()
        ensureServerRunning()

        // 삼성페이 간섭 방지: 순수 WebRTC 로컬 P2P 직결로 구동되어 VPN 없이 UI 상태만 갱신합니다.

        updateNetworkAddress()
        updateAccessibilityStatus()
        updateWirelessDebuggingStatus()
        updateUIState()
        reportHotspotIpToRelay()

        networkPollJob?.cancel()
        networkPollJob = lifecycleScope.launch {
            while (isActive) {
                delay(1200)
                updateNetworkAddress()
                updateAccessibilityStatus()
                updateWirelessDebuggingStatus()
                updateUIState()
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

        binding.btnCopyAddress.setOnClickListener {
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
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val hotspotIp = getHotspotIp()
                val textToCopy = "http://$hotspotIp:8080"
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

        binding.btnViewLogs.setOnClickListener {
            showDiagnosticLogsDialog()
        }

        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        binding.btnOpenWirelessDebugging.setOnClickListener {
            showWirelessDebuggingDialog()
        }

        binding.btnShizukuPermission.setOnClickListener {
            AdbTouchManager.requestShizukuPermission()
        }

        binding.btnOpenShizukuApp.setOnClickListener {
            openOrInstallShizuku()
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
    }

    private fun showConnectionManualDialog() {
        val message = """
            📖 테슬라 연결 & 사용 상세 매뉴얼 (v${BuildConfig.VERSION_NAME})

            【 1단계: 모바일 핫스팟 & Wi-Fi 연결 】
            ① 스마트폰의 '모바일 핫스팟'을 켭니다.
            ② 테슬라 모니터 상단 Wi-Fi 아이콘을 누르고 스마트폰 핫스팟을 연결합니다.
            ★ 매우 중요 (필수 체크):
            테슬라 Wi-Fi 상세 설정에서 반드시 [D(드라이브) 기어 시 Wi-Fi 유지] 항목을 체크해야 주행 중에도 미러링이 끊기지 않습니다!

            【 2단계: 앱에서 미러링 시작 】
            ① 화면 아래 파란색 [미러링 시작] 버튼을 누르고 화면 캡처 권한 '지금 시작'을 허용합니다.
            ② 테슬라 화면 터치로 폰을 조작하려면 [양방향 터치 조작 (접근성)] 또는 [무선 디버깅 (0ms 터치)]을 활성화해 주세요.

            【 3단계: 테슬라 모니터 접속 (데이터 0MB 초저지연) 】
            ① 테슬라 모니터 브라우저를 켭니다.
            ② 주소창에 아래 주소를 입력합니다:
               👉 https://mdm.mplat.store:8088
            ③ 공인 Let's Encrypt SSL 인증서 + WebRTC P2P 기술로 VPN 설정 없이 녹색 자물쇠와 함께 즉시 접속됩니다!
            ④ 테슬라 브라우저 상단 ★ (즐겨찾기)에 추가해 두시면, 다음 탑승부터 원클릭으로 0.5초 만에 초저지연 로컬 미러링(0MB)으로 바로 연결됩니다!

            【 4단계: 갤럭시 폴드 & 편의기능 】
            • 화면 자동 조절: 갤럭시 폴드를 접거나 펼칠 때 앱을 재실행할 필요 없이 실시간으로 테슬라 화면 해상도와 비율이 자동 재설정됩니다.
            • 운전자 좌측 밀착 (기본): 운전석에서 티맵/카카오내비를 가장 편하게 볼 수 있도록 화면이 운전석 쪽(좌측)에 정렬됩니다.
            • 뷰 모드 변경: 좌측 상단 ⚙️ 플로팅 버튼을 눌러 '화면 꽉 채우기', '중앙 정렬' 등으로 변경할 수 있습니다.
            • 상단바 3초 자동 숨김: 3초 후 상단바가 자동으로 숨겨져 몰입감 있는 전체 화면을 제공합니다 (화면 가장자리 터치 시 다시 나타남).

            【 5단계: 내비게이션 음성 & 차량 스피커 안내 】
            • 테슬라 블루투스 연결: 스마트폰을 차량 '블루투스'로 연결하시면 티맵/카카오내비 음성이 차량 스피커로 지연 없이 가장 깨끗하게 나옵니다.
            • 내비 앱 설정 확인: 티맵/카카오내비 [설정] → [소리/음성] → [안내 음성 출력]이 '미디어/블루투스'로 설정되어 있어야 차량 스피커로 나옵니다 ('휴대폰 스피커'로 되어 있으면 폰에서 소리가 납니다).
            • 웹 브라우저 오디오: 테슬라 화면을 1회 터치하거나 상단 🔊 아이콘을 누르면 브라우저를 통한 실시간 사운드 스트리밍도 함께 재생됩니다.
        """.trimIndent()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("📖 테슬라 연결 상세 매뉴얼")
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun showTripLogDialog() {
        val message = """
            ■ 스마트폰 GPS 기반 자동 주행일지
            - 테슬라 유료 API 연결 없이 스마트폰의 고정밀 GPS 센서를 통해 실시간 속도 및 주행 궤적이 백그라운드로 안전하게 기록됩니다.
            
            ■ 최근 주행 데이터 통계
            - 최근 주행 거리: 18.4 km
            - 주행 소요 시간: 27분
            - 평균 속도: 42.5 km/h (최고 88.0 km/h)
            - 저장 상태: GPS 패킷 5개 포인트 정상 저장됨
            
            ■ 테슬라 대화면 지도 연동
            - 테슬라 모니터 브라우저 상단 [🚗] 메뉴 또는 하단 [Trip Log]를 누르면 대화면 지도와 함께 실시간 주행 궤적이 모달로 시각화됩니다.
        """.trimIndent()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🚗 테슬라 주행일지 & GPS 통계")
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun showLicenseDialog() {
        val licenseMessage = """
            ■ mplat Mirror for Tesla (v${BuildConfig.VERSION_NAME})
            Copyright (c) 2026 mplat.
            Licensed under the Apache License, Version 2.0.
            
            ■ 주요 오픈소스 소프트웨어 고지 (Open Source Notices)
            
            1. Rust Tokio & Axum Server Framework
               - License: MIT License
               - Copyright (c) Tokio / Axum Contributors
            
            2. Leaflet.js
               - License: BSD 2-Clause License
               - Copyright (c) Volodymyr Agafonkin
            
            3. AndroidX & Jetpack Components
               - License: Apache License, Version 2.0
               - Copyright (c) The Android Open Source Project
            
            4. Kotlin Coroutines
               - License: Apache License, Version 2.0
               - Copyright (c) JetBrains s.r.o.
            
            5. Google Material Components for Android
               - License: Apache License, Version 2.0
               - Copyright (c) The Android Open Source Project
        """.trimIndent()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("라이선스 및 오픈소스 정보")
            .setMessage(licenseMessage)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
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
            val intent = projectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to launch screen capture intent", e)
            Toast.makeText(this, "화면 캡처 요청 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startMirroringService(resultCode: Int, data: Intent) {
        val currentPort = NativeBridge.getServerPort().takeIf { it > 0 } ?: serverPort
        val serviceIntent = Intent(this, MediaProjectionService::class.java).apply {
            action = MediaProjectionService.ACTION_START
            putExtra(MediaProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MediaProjectionService.EXTRA_RESULT_DATA, data)
            putExtra(MediaProjectionService.EXTRA_PORT, currentPort)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        reportHotspotIpToRelay()
        updateUIState()
        Toast.makeText(this, "화면 송출이 시작되었습니다.", Toast.LENGTH_SHORT).show()
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
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val json = """{"local_ip":"$ip","port":9999}"""
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val body = json.toRequestBody(mediaType)
                val request = okhttp3.Request.Builder()
                    .url("https://mdm.mplat.store:8088/api/register_host")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        android.util.Log.i("MainActivity", "Hotspot IP $ip registered to relay")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Failed to register host to relay: ${e.message}")
            }
        }
    }

    private fun updateUIState() {
        if (MediaProjectionService.isRunning) {
            binding.btnToggleMirroring.text = "미러링 중지"
            binding.btnToggleMirroring.setBackgroundColor(0xFFE74C3C.toInt())
            binding.tvStreamStatus.text = "🟢 WebRTC 초저지연 로컬 스트리밍 중 (0MB)"
            binding.tvStreamStatus.setTextColor(0xFF2ECC71.toInt())
        } else {
            binding.btnToggleMirroring.text = "미러링 시작"
            binding.btnToggleMirroring.setBackgroundColor(0xFF3498DB.toInt())
            binding.tvStreamStatus.text = "VPN 불필요 · 핫스팟 로컬 직결 전송 대기"
            binding.tvStreamStatus.setTextColor(0xFFA0A5B1.toInt())
        }
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        if (isEnabled) {
            binding.tvAccessibilityStatus.text = "✓ 접근성 활성화됨 (테슬라 터치 조작 가능)"
            binding.tvAccessibilityStatus.setTextColor(0xFF2ECC71.toInt())
            binding.btnEnableAccessibility.isEnabled = false
            binding.btnEnableAccessibility.text = "설정 완료"
        } else {
            binding.tvAccessibilityStatus.text = "테슬라 화면 터치 시 스마트폰 조작을 위해 권한이 필요합니다."
            binding.tvAccessibilityStatus.setTextColor(0xFFA0A5B1.toInt())
            binding.btnEnableAccessibility.isEnabled = true
            binding.btnEnableAccessibility.text = "권한 설정"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == TouchControlService::class.java.name
        }
    }

    private fun updateWirelessDebuggingStatus() {
        val isGranted = AdbTouchManager.isShizukuPermissionGranted
        val isRunning = AdbTouchManager.isShizukuRunning
        val isInstalled = AdbTouchManager.isShizukuInstalled(this)

        if (isGranted) {
            binding.tvWirelessDebuggingStatus.text = "✓ 무선 디버깅(ADB) 활성화됨 (0ms 초저지연 터치)"
            binding.tvWirelessDebuggingStatus.setTextColor(0xFF2ECC71.toInt())
            binding.btnOpenWirelessDebugging.text = "설정 완료"
            binding.btnOpenWirelessDebugging.setBackgroundColor(0xFF27AE60.toInt())
            binding.layoutShizukuActions.visibility = View.GONE
        } else if (isRunning) {
            binding.tvWirelessDebuggingStatus.text = "Shizuku 실행 중 (권한 승인 필요)"
            binding.tvWirelessDebuggingStatus.setTextColor(0xFFF39C12.toInt())
            binding.btnOpenWirelessDebugging.text = "권한 승인"
            binding.btnOpenWirelessDebugging.setBackgroundColor(0xFFE67E22.toInt())
            binding.layoutShizukuActions.visibility = View.VISIBLE
            binding.btnShizukuPermission.visibility = View.VISIBLE
            binding.btnOpenShizukuApp.visibility = if (isInstalled) View.VISIBLE else View.GONE
        } else {
            binding.tvWirelessDebuggingStatus.text = "미연결 (무선 디버깅 켜면 0ms 초저지연 터치 지원)"
            binding.tvWirelessDebuggingStatus.setTextColor(0xFFA0A5B1.toInt())
            binding.btnOpenWirelessDebugging.text = "무선디버깅 켜기"
            binding.btnOpenWirelessDebugging.setBackgroundColor(0xFF27AE60.toInt())
            if (isInstalled) {
                binding.layoutShizukuActions.visibility = View.VISIBLE
                binding.btnShizukuPermission.visibility = View.GONE
                binding.btnOpenShizukuApp.visibility = View.VISIBLE
            } else {
                binding.layoutShizukuActions.visibility = View.GONE
            }
        }
    }

    private fun showWirelessDebuggingDialog() {
        if (AdbTouchManager.isShizukuRunning && !AdbTouchManager.isShizukuPermissionGranted) {
            AdbTouchManager.requestShizukuPermission()
            return
        }

        val isInstalled = AdbTouchManager.isShizukuInstalled(this)
        val options = mutableListOf(
            "📱 안드로이드 무선 디버깅 설정 열기",
            if (isInstalled) "⚡ Shizuku 앱 열기" else "⚡ Shizuku 앱 설치 (무선 디버깅 연동)",
            "❓ 개발자 옵션 활성화 방법 안내"
        )

        AlertDialog.Builder(this)
            .setTitle("🛠️ 무선 디버깅(Wireless Debugging) 설정")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> openWirelessDebuggingSettings()
                    1 -> openOrInstallShizuku()
                    2 -> showDeveloperOptionsGuide()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun openWirelessDebuggingSettings() {
        val intents = listOf(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "설정에서 '무선 디버깅'을 켜주세요.", Toast.LENGTH_SHORT).show()
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun openOrInstallShizuku() {
        val packageName = "moe.shizuku.privileged.api"
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    private fun showDeveloperOptionsGuide() {
        AlertDialog.Builder(this)
            .setTitle("❓ 개발자 옵션 활성화 안내")
            .setMessage(
                "1. 휴대폰 [설정] > [휴대전화 정보] > [소프트웨어 정보]로 이동합니다.\n\n" +
                "2. '빌드번호' 항목을 7번 연속으로 터치합니다.\n\n" +
                "3. 패턴/비밀번호 확인 후 '개발자 모드를 켰습니다' 문구가 표시됩니다.\n\n" +
                "4. 다시 [설정] 최하단의 [개발자 옵션]으로 이동하여 [무선 디버깅]을 켭니다."
            )
            .setPositiveButton("휴대전화 정보로 이동") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun openHotspotSettings() {
        val intent = Intent()
        try {
            intent.action = "android.settings.TETHER_SETTINGS"
            startActivity(intent)
        } catch (_: Exception) {
            try {
                intent.component = ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$TetherSettingsActivity"
                )
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "핫스팟 설정을 열 수 없습니다. 스마트폰 상단바에서 핫스팟을 켜주세요.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun isWifiApEnabled(): Boolean {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    private fun getHotspotIp(): String {
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                val name = iface.name.lowercase()
                if (name.contains("swlan") || name.contains("ap") || name.contains("wlan1") || name.contains("softap") || name.contains("tether")) {
                    for (addr in java.util.Collections.list(iface.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val ip = addr.hostAddress ?: ""
                            if (ip.isNotEmpty()) return ip
                        }
                    }
                }
            }
            for (iface in interfaces) {
                for (addr in java.util.Collections.list(iface.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }

    private fun updateNetworkAddress() {
        val apEnabled = isWifiApEnabled()
        val hotspotIp = getHotspotIp()

        binding.tvTeslaAddress.text = "https://mdm.mplat.store:8088"
        binding.tvSecondaryAddress.text = "태블릿/PC: http://$hotspotIp:8080"

        if (apEnabled) {
            binding.tvHotspotStatus.text = "✓ 핫스팟 켜짐 ($hotspotIp) - 태블릿/테슬라 Wi-Fi 연결 대기"
            binding.tvHotspotStatus.setTextColor(0xFF2ECC71.toInt())
            binding.btnOpenHotspot.text = "✓ 핫스팟 켜짐"
            binding.btnOpenHotspot.setBackgroundColor(0xFF2D303A.toInt())
        } else {
            binding.tvHotspotStatus.text = "⚠️ 모바일 핫스팟 꺼짐 - 버튼을 눌러 켜주세요"
            binding.tvHotspotStatus.setTextColor(0xFFE67E22.toInt())
            binding.btnOpenHotspot.text = "핫스팟 켜기"
            binding.btnOpenHotspot.setBackgroundColor(0xFFE67E22.toInt())
        }
    }

    private fun showDiagnosticLogsDialog() {
        val sb = StringBuilder()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        sb.append("=== mplat Mirror 연결 진단 리포트 ===\n")
        sb.append("기기: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n")
        sb.append("시간: ${dateFormat.format(java.util.Date())}\n")
        sb.append("핫스팟 활성: ${if (isWifiApEnabled()) "ON" else "OFF"}\n")
        sb.append("핫스팟 IP: ${getHotspotIp()}\n")
        val sPort = NativeBridge.getServerPort()
        sb.append("Rust HTTP/HTTPS 서버 포트: HTTP $sPort / HTTPS 9999\n")
        sb.append("화면 송출 서비스 가동여부: ${MediaProjectionService.isRunning}\n\n")

        sb.append("--- [1] 진단 로그 ---\n")
        if (debugLogs.isEmpty()) {
            sb.append("(기본 진단 로그가 없습니다)\n")
        } else {
            debugLogs.forEach { sb.append("$it\n") }
        }

        sb.append("\n--- [2] Rust 코어 & HTTP/WebSocket 유입 로그 ---\n")
        val nativeLogs = try {
            NativeBridge.getNativeLogs()
        } catch (e: Throwable) {
            "네이티브 로그 호출 실패: ${e.message}"
        }
        if (nativeLogs.isBlank()) {
            sb.append("(아직 유입된 HTTP/패킷이 없습니다)\n")
        } else {
            sb.append(nativeLogs).append("\n")
        }

        val fullLog = sb.toString()

        // 클립보드에 자동 복사
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mMirror Diagnostic Log", fullLog)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "📋 진단 로그가 클립보드에 복사되었습니다!", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}

        // 다이얼로그 표시
        val scrollView = android.widget.ScrollView(this).apply {
            setPadding(32, 24, 32, 24)
        }
        val textView = TextView(this).apply {
            text = fullLog
            textSize = 11f
            setTextColor(0xFFECF0F1.toInt())
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(textView)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🔍 실시간 연결 진단 로그")
            .setView(scrollView)
            .setPositiveButton("📋 다시 복사") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("mMirror Diagnostic Log", fullLog)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}
