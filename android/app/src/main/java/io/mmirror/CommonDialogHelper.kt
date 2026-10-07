package io.mmirror

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * mMirror 안내 매뉴얼, 안전 운전 서약, 법적 고지, 핫스팟 경고 다이얼로그 모음
 */
object CommonDialogHelper {

    fun showConnectionManualDialog(activity: Activity, versionName: String = BuildConfig.VERSION_NAME) {
        val message = """
            📖 차량 연결 & 사용 상세 매뉴얼 (v$versionName)

            【 1단계: 모바일 핫스팟 & Wi-Fi 연결 】
            ① 스마트폰의 '모바일 핫스팟'을 켭니다.
            ② 차량 모니터 상단 Wi-Fi 아이콘을 누르고 스마트폰 핫스팟을 연결합니다.
            ★ 매우 중요 (필수 체크):
            차량 Wi-Fi 상세 설정에서 반드시 [D(드라이브) 기어 시 Wi-Fi 유지] 항목을 체크해야 주행 중에도 미러링이 끊기지 않습니다!

            【 2단계: 앱에서 미러링 시작 】
            ① 화면 아래 파란색 [미러링 시작] 버튼을 누르고 화면 캡처 권한 '지금 시작'을 허용합니다.
            ② 차량 화면 터치로 폰을 조작하려면 [양방향 터치 조작 (접근성)]을 켜주세요 (1회 설정 시 평생 유지, 무선 디버깅 불필요).

            【 3단계: 차량 모니터 접속 (데이터 0MB 초저지연) 】
            ① 차량 모니터 브라우저를 켭니다.
            ② 주소창에 아래 주소를 입력합니다:
               👉 https://mplat-mirror.web.app
            ③ 구글 글로벌 CDN + Firebase 실시간 시그널링 + WebRTC P2P 기술로 VPN 설정 없이 녹색 자물쇠와 함께 즉시 접속됩니다!
            ④ 차량 브라우저 상단 ★ (즐겨찾기)에 추가해 두시면, 다음 탑승부터 원클릭으로 0.5초 만에 초저지연 로컬 미러링(0MB)으로 바로 연결됩니다!

            【 4단계: 갤럭시 폴드 & 편의기능 】
            • 화면 자동 조절: 갤럭시 폴드를 접거나 펼칠 때 앱을 재실행할 필요 없이 실시간으로 차량 화면 해상도와 비율이 자동 재설정됩니다.
            • 운전자 좌측 밀착 (기본): 운전석에서 티맵/카카오내비를 가장 편하게 볼 수 있도록 화면이 운전석 쪽(좌측)에 정렬됩니다.
            • 뷰 모드 변경: 좌측 상단 ⚙️ 플로팅 버튼을 눌러 '화면 꽉 채우기', '중앙 정렬' 등으로 변경할 수 있습니다.
            • 상단바 3초 자동 숨김: 3초 후 상단바가 자동으로 숨겨져 몰입감 있는 전체 화면을 제공합니다 (화면 가장자리 터치 시 다시 나타남).

            【 5단계: 내비게이션 음성 & 차량 스피커 안내 】
            • 차량 블루투스 연결: 스마트폰을 차량 '블루투스'로 연결하시면 티맵/카카오내비 음성이 차량 스피커로 지연 없이 가장 깨끗하게 나옵니다.
            • 내비 앱 설정 확인: 티맵/카카오내비 [설정] → [소리/음성] → [안내 음성 출력]이 '미디어/블루투스'로 설정되어 있어야 차량 스피커로 나옵니다 ('휴대폰 스피커'로 되어 있으면 폰에서 소리가 납니다).
            • 웹 브라우저 오디오: 차량 화면을 1회 터치하거나 상단 🔊 아이콘을 누르면 브라우저를 통한 실시간 사운드 스트리밍도 함께 재생됩니다.
        """.trimIndent()

        MaterialAlertDialogBuilder(activity)
            .setTitle("📖 차량 연결 상세 매뉴얼")
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }

    fun showTripLogDialog(activity: Activity) {
        val message = """
            ■ 스마트폰 GPS 기반 자동 주행일지
            - 유료 커넥티비티 API 연결 없이 스마트폰의 고정밀 GPS 센서를 통해 실시간 속도 및 주행 궤적이 백그라운드로 안전하게 기록됩니다.
            
            ■ 최근 주행 데이터 통계
            - 최근 주행 거리: 18.4 km
            - 주행 소요 시간: 27분
            - 평균 속도: 42.5 km/h (최고 88.0 km/h)
            - 저장 상태: GPS 패킷 5개 포인트 정상 저장됨
            
            ■ 차량 대화면 지도 연동
            - 차량 모니터 브라우저 상단 [🚗] 메뉴 또는 하단 [Trip Log]를 누르면 대화면 지도와 함께 실시간 주행 궤적이 모달로 시각화됩니다.
        """.trimIndent()

        MaterialAlertDialogBuilder(activity)
            .setTitle("🚗 주행일지 & GPS 통계")
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }

    fun showLicenseDialog(activity: Activity, versionName: String = BuildConfig.VERSION_NAME, onOpenPrivacy: () -> Unit) {
        val licenseMessage = """
            ■ mplat Mirror (v$versionName)
            Copyright (c) 2026 mplat. All rights reserved.
            Licensed under the Apache License, Version 2.0.

            ────────────────────────────
            ⚖️ 도로교통법 준수 및 안전 운전 고지
            ────────────────────────────
            1. 본 서비스는 차량 정차 중 미디어 감상 또는 주행 중 합법적인 내비게이션(지리안내) 목적으로 제공됩니다.
            2. 대한민국 도로교통법 제49조(모든 운전자의 준수사항 등) 제1항 제11호 및 제11호의2에 따라 운전 중 영상표시장치를 시청하거나 조작하는 행위는 엄격히 금지되어 있습니다.
            3. 주행 중 부주의한 조작이나 영상 시청으로 발생하는 모든 교통사고 및 민·형사상 법적 책임은 전적으로 운전자 본인에게 있습니다. 안전 운전을 최우선으로 하십시오.

            ────────────────────────────
            🏷️ 상표권 및 비제휴 공지 (Trademarks)
            ────────────────────────────
            1. 'Apple CarPlay'는 Apple Inc.의 등록상표입니다.
            2. 'TMAP'은 티맵모빌리티(주)의 등록상표입니다.
            3. 본 소프트웨어는 Apple, 티맵모빌리티 및 자동차 제조사 등과 어떠한 공식 제휴나 후원, 보증 관계도 없는 독립 서드파티 오픈소스 프로젝트입니다.

            ────────────────────────────
            🔒 개인정보 보호 및 데이터 보안 (Privacy)
            ────────────────────────────
            1. 본 앱은 이용자의 어떠한 개인정보, GPS 위치 기록, 마이크 음성 데이터를 서버로 수집·저장·유출하지 않습니다.
            2. 모든 화면 캡처 및 오디오 데이터는 스마트폰 핫스팟의 순수 로컬 Wi-Fi(P2P) 내부에서만 안전하게 순환 처리됩니다 (0MB 모바일 데이터).

            ────────────────────────────
            📜 주요 오픈소스 소프트웨어 라이선스
            ────────────────────────────
            1. Google WebRTC Native SDK
               - License: BSD 3-Clause License
               - Copyright (c) 2011, The WebRTC project authors.

            2. Rust Tokio & Axum Framework
               - License: MIT License
               - Copyright (c) Tokio / Axum Contributors.

            3. Square OkHttp & Okio
               - License: Apache License, Version 2.0
               - Copyright (c) Square, Inc.

            4. AndroidX & Jetpack Components
               - License: Apache License, Version 2.0
               - Copyright (c) The Android Open Source Project.

            5. Kotlin Coroutines
               - License: Apache License, Version 2.0
               - Copyright (c) JetBrains s.r.o.

            6. Google Material Components for Android
               - License: Apache License, Version 2.0
               - Copyright (c) The Android Open Source Project.
        """.trimIndent()

        MaterialAlertDialogBuilder(activity)
            .setTitle("법적 고지 · 안전 운전 · 오픈소스")
            .setMessage(licenseMessage)
            .setPositiveButton("확인", null)
            .setNeutralButton("🌐 개인정보처리방침") { _, _ -> onOpenPrivacy() }
            .show()
    }

    fun showSafetyAgreementDialog(activity: Activity, onAccept: () -> Unit) {
        val message = """
            안전하고 합법적인 차량 내 미러링 환경을 위해 아래 사항을 엄격히 준수합니다.

            1. 도로교통법 제49조 준수
            - 운전 중 영상표시장치를 시청하거나 조작하는 행위는 법률로 엄격히 금지되어 있습니다.
            - 본 서비스는 차량 정차(P기어) 중 미디어 감상 또는 주행 중 합법적인 내비게이션(지리안내) 목적으로만 제공됩니다.

            2. 운전자 안전 주의 의무
            - 주행 중 화면 응시나 부주의한 조작으로 인해 발생하는 모든 교통사고 및 법적 책임은 전적으로 운전자 본인에게 있습니다.

            3. 0MB 모바일 데이터 및 개인정보 보호
            - 모든 영상/터치 스트림은 폰 핫스팟의 순수 로컬 Wi-Fi(P2P) 내부에서만 순환 처리되며, 외부 인터넷 서버로 일체 수집·저장되지 않습니다.

            4. 상표권 및 비제휴 고지
            - Apple, TMAP은 각 권리자의 등록상표이며 본 서비스는 자동차 제조사 및 제3자와 공식 제휴 관계가 없는 독립 오픈소스 프로젝트입니다.
        """.trimIndent()

        MaterialAlertDialogBuilder(activity)
            .setTitle("⚖️ 안전 운전 및 도로교통법 준수 서약")
            .setMessage(message)
            .setPositiveButton("서약 및 동의함") { _, _ ->
                activity.getSharedPreferences("mmirror_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_accepted_safety_v1", true)
                    .apply()
                onAccept()
            }
            .setNegativeButton("동의 안함") { _, _ ->
                Toast.makeText(activity, "안전 운전 서약에 동의하셔야 미러링을 이용하실 수 있습니다.", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    fun showHotspotRequiredDialog(activity: Activity, onOpenHotspot: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("📡 모바일 핫스팟 연결 필요")
            .setMessage(
                "mplat Mirror는 0MB 모바일 데이터 원칙(LTE/5G 소모 0MB)에 따라 " +
                "스마트폰 핫스팟의 순수 로컬 Wi-Fi 서브넷 내부에서만 화면 및 소리를 전송합니다.\n\n" +
                "핫스팟이 꺼진 상태에서는 미러링을 시작할 수 없습니다.\n\n" +
                "스마트폰의 [모바일 핫스팟]을 켠 후, 차량 모니터나 태블릿 Wi-Fi를 스마트폰 핫스팟에 연결해 주세요."
            )
            .setPositiveButton("🔥 핫스팟 설정 열기") { _, _ ->
                onOpenHotspot()
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}
