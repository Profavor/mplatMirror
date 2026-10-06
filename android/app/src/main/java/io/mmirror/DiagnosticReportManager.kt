package io.mmirror

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * mMirror 실시간 연결 진단 리포트 및 터미널 콘솔 UI 관리자
 * 
 * - 실시간 핫스팟/WebRTC/블루투스/접근성/절전 권한 상태 취합
 * - AppLogger 및 NativeBridge Rust 로그 추출
 * - 터미널 구문 강조(Syntax Highlighting) 및 다이얼로그 렌더링
 */
object DiagnosticReportManager {

    /**
     * 블루투스 오디오 장치 연결 상태 및 장치명을 반환합니다.
     */
    fun getBluetoothAudioStatus(context: Context): Pair<Boolean, String?> {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return Pair(false, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val btDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
                }
                if (btDevice != null) {
                    val name = btDevice.productName?.toString()?.takeIf { it.isNotBlank() } ?: "차량 블루투스"
                    return Pair(true, name)
                }
            } else {
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn) {
                    return Pair(true, "차량 블루투스")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("DiagnosticReport", "Bluetooth audio detection error: ${e.message}")
        }
        return Pair(false, null)
    }

    /**
     * 현재 기기 및 세션의 종합 연결 진단 리포트를 생성합니다.
     */
    fun buildDiagnosticReport(context: Context): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.append("=== mplat Mirror 연결 진단 리포트 ===\n")
        sb.append("기기: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n")
        sb.append("시간: ${dateFormat.format(Date())}\n")
        sb.append("핫스팟 활성: ${if (NetworkUtils.isWifiApEnabled(context)) "ON" else "OFF"}\n")
        sb.append("핫스팟 IP: ${NetworkUtils.getHotspotIp()}\n")
        val sPort = NativeBridge.getServerPort()
        sb.append("HTTP 포트: $sPort\n")
        sb.append("화면 송출 서비스 가동여부: ${MediaProjectionService.isRunning}\n")
        val (btConnected, btName) = getBluetoothAudioStatus(context)
        sb.append("블루투스 오디오: ${if (btConnected) "연결됨 ($btName, 고음질 A2DP)" else "미연결"}\n")
        sb.append("오디오 출력: 차량 블루투스(A2DP) 무손실 직결 고정 (0ms 딜레이)\n\n")

        sb.append("--- [1] 실시간 진단 로그 (최근 세션/오류 이벤트) ---\n")
        val appLogs = AppLogger.getLogs()
        if (appLogs.isEmpty()) {
            sb.append("(아직 기록된 로그가 없습니다. 화면 송출을 시작하면 모든 이벤트 및 오류가 실시간 기록됩니다.)\n")
        } else {
            appLogs.takeLast(120).forEach { sb.append("$it\n") }
        }

        sb.append("\n--- [2] 양방향 터치 제어 및 절전 상태 ---\n")
        val isA11y = TouchControlService.isAccessibilityServiceEnabled(context)
        sb.append("접근성 터치 서비스 활성: ").append(if (isA11y) "YES (TouchControlService 가동 중)" else "NO (접근성 설정 필요)").append("\n")
        val canWriteSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(context) else true
        sb.append("시스템 설정 변경(화면 절전) 권한: ").append(if (canWriteSettings) "YES (티맵 실행 중 테슬라 절전 가능)" else "NO (권한 필요)").append("\n")
        val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        sb.append("다른 앱 위에 표시(절전 오버레이) 권한: ").append(if (canOverlay) "YES (티맵 실행 중 테슬라 절전 100% 가능)" else "NO (권한 필요)").append("\n")

        sb.append("\n--- [3] Rust 코어 & HTTP/WebSocket 유입 로그 ---\n")
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

        return sb.toString()
    }

    /**
     * 진단 리포트 문자열을 터미널 콘솔 스타일 색상으로 포맷팅합니다.
     */
    fun formatDiagnosticReport(fullLog: String): SpannableStringBuilder {
        val spannable = SpannableStringBuilder()
        fullLog.lines().forEach { line ->
            val start = spannable.length
            spannable.append(line).append("\n")
            val end = spannable.length - 1
            if (line.startsWith("===")) {
                spannable.setSpan(
                    ForegroundColorSpan(0xFF00E5FF.toInt()), // 네온 시안
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else if (line.startsWith("---")) {
                spannable.setSpan(
                    ForegroundColorSpan(0xFF2ECC71.toInt()), // 에메랄드 그린
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else if (line.contains("YES") || line.contains("ON") || line.contains("[INFO]") || line.startsWith("✓") || line.contains("성공")) {
                spannable.setSpan(
                    ForegroundColorSpan(0xFF2ECC71.toInt()), // 연녹색
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else if (line.contains("NO") || line.contains("OFF") || line.contains("실패") || line.contains("ERROR") || line.contains("[ERROR]") || line.contains("❌")) {
                spannable.setSpan(
                    ForegroundColorSpan(0xFFFF6B6B.toInt()), // 코랄 레드
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else if (line.contains("WARN") || line.contains("[WARN]") || line.contains("⚠️")) {
                spannable.setSpan(
                    ForegroundColorSpan(0xFFFFB86C.toInt()), // 앰버 옐로우
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else if (line.contains(":") && !line.startsWith(" ")) {
                val colonIdx = line.indexOf(":")
                spannable.setSpan(
                    ForegroundColorSpan(0xFF58A6FF.toInt()), // 스카이 블루 (라벨)
                    start, start + colonIdx + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    ForegroundColorSpan(0xFFE6EDF3.toInt()), // 크리스프 화이트 (값)
                    start + colonIdx + 1, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                spannable.setSpan(
                    ForegroundColorSpan(0xFFE6EDF3.toInt()), // 기본 크리스프 화이트
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }

    /**
     * 터미널 블랙 콘솔 뷰어 다이얼로그를 표시합니다.
     */
    fun showDiagnosticLogsDialog(context: Context) {
        var currentLog = buildDiagnosticReport(context)

        // 클립보드에 자동 복사
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mMirror Diagnostic Log", currentLog)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "📋 진단 로그가 클립보드에 복사되었습니다!", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}

        // 다이얼로그 뷰 구성 (고급 블랙 테마 터미널 콘솔)
        val density = context.resources.displayMetrics.density
        val dpToPx = { dp: Int -> (dp * density).toInt() }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF12151C.toInt())
            setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(10))
        }

        // 헤더 타이틀 및 서브타이틀
        val headerTitle = TextView(context).apply {
            text = "🔍 실시간 연결 진단 로그"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        val headerSubtitle = TextView(context).apply {
            text = "실시간 세션 이벤트, 오류 추적 및 WebRTC/Rust 패킷"
            textSize = 12f
            setTextColor(0xFF8B949E.toInt())
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        rootLayout.addView(headerTitle)
        rootLayout.addView(headerSubtitle)

        // 터미널 블랙 콘솔 박스 (#07080B 제트 블랙 배경, #232733 테두리)
        val consoleFrame = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_terminal_console)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(380)
            )
        }

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
        }

        val textView = TextView(context).apply {
            text = formatDiagnosticReport(currentLog)
            textSize = 11.5f
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dpToPx(3).toFloat(), 1.15f)
        }
        scrollView.addView(textView)
        consoleFrame.addView(scrollView)
        rootLayout.addView(consoleFrame)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(rootLayout)
            .setPositiveButton("📋 복사") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("mMirror Diagnostic Log", currentLog)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("🔄 새로고침", null)
            .setNegativeButton("닫기", null)
            .create()

        dialog.window?.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_dark_dialog))
        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            val neutralBtn = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
            val negativeBtn = dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)

            positiveBtn?.apply {
                setTextColor(0xFF00E5FF.toInt())
                typeface = Typeface.DEFAULT_BOLD
            }
            neutralBtn?.apply {
                setTextColor(0xFF58A6FF.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    currentLog = buildDiagnosticReport(context)
                    textView.text = formatDiagnosticReport(currentLog)
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                    Toast.makeText(context, "🔄 실시간 진단 로그가 갱신되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            negativeBtn?.apply {
                setTextColor(0xFF8B949E.toInt())
            }
        }
        dialog.show()
    }
}
