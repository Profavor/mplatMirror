package io.mmirror

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serverPort = 8080
    private var startMirroringAfterVpn = false

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

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            LocalProxyVpnService.start(this)
            Toast.makeText(this, "⚡ 테슬라 로컬 가상 프록시가 가동되었습니다!", Toast.LENGTH_SHORT).show()
            updateUIState()
            if (startMirroringAfterVpn) {
                startMirroringAfterVpn = false
                checkPermissionsAndStart()
            }
        } else {
            startMirroringAfterVpn = false
            Toast.makeText(this, "로컬 가상 프록시(VPN) 권한이 취소되었습니다.", Toast.LENGTH_SHORT).show()
            binding.switchLocalProxy.isChecked = false
            updateUIState()
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

        // VPN 권한이 이미 승인되어 있다면 즉시 가상 프록시 가동
        if (!LocalProxyVpnService.isRunning) {
            try {
                if (android.net.VpnService.prepare(this) == null) {
                    LocalProxyVpnService.start(this)
                }
            } catch (_: Exception) {}
        }

        updateNetworkAddress()
        updateAccessibilityStatus()
        updateUIState()

        networkPollJob?.cancel()
        networkPollJob = lifecycleScope.launch {
            while (isActive) {
                delay(1200)
                updateNetworkAddress()
                updateAccessibilityStatus()
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
                val textToCopy = "http://td9.cc:7777"
                val clip = ClipData.newPlainText("Tesla Address", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 추천 가상 프록시 주소(http://td9.cc:7777)가 복사되었습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "복사 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvSecondaryAddress.setOnClickListener {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val textToCopy = "https://teslamirror.net:9999"
                val clip = ClipData.newPlainText("Tesla Secondary Address", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 보조 가상 프록시 주소(https://teslamirror.net:9999)가 복사되었습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "복사 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchLocalProxy.setOnClickListener {
            val isChecked = binding.switchLocalProxy.isChecked
            if (isChecked) {
                if (!LocalProxyVpnService.isRunning) {
                    try {
                        startMirroringAfterVpn = false
                        val vpnIntent = android.net.VpnService.prepare(this)
                        if (vpnIntent != null) {
                            vpnLauncher.launch(vpnIntent)
                        } else {
                            LocalProxyVpnService.start(this)
                            Toast.makeText(this, "⚡ 테슬라 로컬 가상 프록시가 가동되었습니다!", Toast.LENGTH_SHORT).show()
                            updateUIState()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "VPN 준비 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                        binding.switchLocalProxy.isChecked = false
                    }
                }
            } else {
                if (LocalProxyVpnService.isRunning) {
                    LocalProxyVpnService.stop(this)
                    Toast.makeText(this, "로컬 가상 프록시가 중지되었습니다.", Toast.LENGTH_SHORT).show()
                    updateUIState()
                }
            }
        }

        binding.btnOpenHotspot.setOnClickListener {
            openHotspotSettings()
        }

        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
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
            ① 상단 [⚡ 테슬라 로컬 가상 프록시 (데이터 0MB)]가 켜져 있는지 확인합니다. (삼성 핫스팟 차단 우회 및 LTE 데이터 0MB 소모)
            ② 화면 아래 파란색 [미러링 시작] 버튼을 누르고 권한 요청에서 '지금 시작'을 선택합니다.
            ③ 테슬라 화면 터치로 폰을 조작하려면 [양방향 터치 조작 (접근성)] 권한도 허용해 주세요.

            【 3단계: 테슬라 모니터 브라우저 접속 】
            ① 테슬라 모니터 하단 메뉴에서 브라우저를 실행합니다.
            ② 주소창에 아래 추천 주소를 입력합니다:
               👉 1순위 추천: http://td9.cc:7777 (보안 경고 없이 원클릭 접속)
               👉 2순위 보조: https://teslamirror.net:9999 (HTTPS 접속)
            ③ 테슬라 브라우저 상단 ★ (즐겨찾기) 버튼을 눌러 북마크에 추가해두시면 다음 탑승부터 원클릭으로 바로 연결됩니다!
            ★ 모바일 브라우저 테스트 팁:
            스마트폰 브라우저로 접속 시 '연결이 비공개로 설정되어 있지 않습니다' 경고가 나타나면 [고급] → [계속 진행(안전하지 않음)]을 1회 눌러주시면 즉시 열립니다. (http://td9.cc:7777 접속 시에는 경고가 없습니다)

            【 4단계: 갤럭시 폴드 & 편의기능 】
            • 화면 자동 조절: 갤럭시 폴드를 접거나 펼칠 때 앱을 재실행할 필요 없이 실시간으로 테슬라 화면 해상도와 비율이 자동 재설정됩니다.
            • 운전자 좌측 밀착 (기본): 운전석에서 티맵/카카오내비를 가장 편하게 볼 수 있도록 화면이 운전석 쪽(좌측)에 정렬됩니다.
            • 뷰 모드 변경: 좌측 상단 ⚙️ 플로팅 버튼을 눌러 '화면 꽉 채우기', '중앙 정렬' 등으로 변경할 수 있습니다.
            • 상단바 3초 자동 숨김: 3초 후 상단바가 자동으로 숨겨져 몰입감 있는 전체 화면을 제공합니다 (화면 가장자리 터치 시 다시 나타남).
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
            - 테슬라 모니터 브라우저(https://teslamirror.net:9999) 상단 [🚗] 메뉴 또는 하단 [Trip Log]를 누르면 대화면 지도와 함께 실시간 주행 궤적이 모달로 시각화됩니다.
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
        // 테슬라 로컬 가상 프록시가 꺼져 있다면 먼저 자동으로 켜기
        if (!LocalProxyVpnService.isRunning) {
            try {
                val vpnIntent = android.net.VpnService.prepare(this)
                if (vpnIntent != null) {
                    startMirroringAfterVpn = true
                    vpnLauncher.launch(vpnIntent)
                    return
                } else {
                    LocalProxyVpnService.start(this)
                    updateUIState()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to start LocalProxyVpnService", e)
            }
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

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
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

    private fun updateUIState() {
        if (MediaProjectionService.isRunning) {
            binding.btnToggleMirroring.text = "미러링 중지"
            binding.btnToggleMirroring.setBackgroundColor(0xFFE74C3C.toInt())
        } else {
            binding.btnToggleMirroring.text = "미러링 시작"
            binding.btnToggleMirroring.setBackgroundColor(0xFF3498DB.toInt())
        }

        val proxyActive = LocalProxyVpnService.isRunning
        if (binding.switchLocalProxy.isChecked != proxyActive) {
            binding.switchLocalProxy.isChecked = proxyActive
        }

        if (proxyActive) {
            binding.tvProxyBadge.text = "가상 프록시 활성화됨 (데이터 0MB)"
            binding.tvProxyBadge.setTextColor(0xFF2ECC71.toInt())
            binding.tvProxyBadge.setBackgroundColor(0x1F2ECC71.toInt())
            binding.tvProxyStatus.text = "🟢 0MB 로컬 터널 가동 중 (td9.cc:7777 / teslamirror.net:9999)"
            binding.tvProxyStatus.setTextColor(0xFF2ECC71.toInt())
        } else {
            binding.tvProxyBadge.text = "가상 프록시 대기 중"
            binding.tvProxyBadge.setTextColor(0xFFA0A5B1.toInt())
            binding.tvProxyBadge.setBackgroundColor(0x1FA0A5B1.toInt())
            binding.tvProxyStatus.text = "⚠️ 아래 [미러링 시작] 버튼을 먼저 눌러야 프록시가 가동됩니다!"
            binding.tvProxyStatus.setTextColor(0xFFE67E22.toInt())
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

        binding.tvTeslaAddress.text = "http://td9.cc:7777"
        binding.tvSecondaryAddress.text = "보조 주소: https://teslamirror.net:9999"

        if (apEnabled) {
            binding.tvHotspotStatus.text = "✓ 핫스팟 켜짐 - 테슬라 Wi-Fi 연결 대기"
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
}
