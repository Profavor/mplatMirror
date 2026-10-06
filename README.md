# mplat Mirror for Tesla 🚗📱

[![Version](https://img.shields.io/badge/version-v1.2.2-2ecc71.svg)](https://github.com/Profavor/mplatMirror)
[![License](https://img.shields.io/badge/license-Apache%202.0-2ecc71.svg)](LICENSE)
[![Data](https://img.shields.io/badge/Mobile%20Data-0MB%20Local%20P2P-brightgreen.svg)](https://github.com/Profavor/mplatMirror)
[![Android](https://img.shields.io/badge/Android-10.0%2B-brightgreen.svg)](https://developer.android.com)
[![Rust](https://img.shields.io/badge/Rust-Core%20Engine-DEA584.svg)](core-rust)

**테슬라 차량 내비게이션 브라우저(Tesla In-Car Chromium Browser)**를 통해 안드로이드 스마트폰의 화면을 **모바일 데이터 0MB 소모 및 50ms 미만 초저지연**으로 미러링하고, 테슬라 터치스크린으로 폰을 양방향 조작하며 차량 스피커로 고음질 블루투스 원음을 출력하는 고성능 로컬 P2P 미러링 솔루션입니다.

---

## 🌟 주요 특징

1. **⚡ 데이터 소모 0MB WebRTC 로컬 P2P 직결 (Zero-Data In-Car Mirroring)**:
   - 스마트폰 LTE/5G 모바일 데이터를 **단 1MB도 소모하지 않는** 순수 로컬 Wi-Fi P2P 스트리밍 표준을 구현합니다.
   - 테슬라 브라우저는 공식 Firebase Hosting 주소인 **`https://mplat-mirror.web.app`**에서 웹 플레이어를 로드하여 녹색 자물쇠(Secure Context)를 충족합니다.
   - 시그널링(SDP/ICE)은 **Firebase Realtime Database 전용**으로 가볍게 교환되며, 실제 비디오/오디오/터치 스트림은 **핫스팟 로컬 Wi-Fi 서브넷(UDP 10.x.x.x)으로 100% 직접 P2P 연결**됩니다.
   - **VPN이나 TUN 가상 프록시가 전혀 필요 없어**, 삼성페이·금융 앱에 간섭하지 않으며 안드로이드 14~16 eBPF 테더링 오프로드 제약을 완벽히 우회합니다.
   - **포트 9999 완전 폐기**: 과거 테스트용 레거시 포트(9999 등)는 완전히 제거 및 차단되었습니다.
   - **포트 8088 임시 서빙**: 스마트폰에서 최신 APK를 다운로드하기 위한 임시 서버(`https://mdm.mplat.store:8088`)로만 사용됩니다.

2. **🎧 차량 블루투스(A2DP) 무손실 0딜레이 직결 오디오**:
   - 테슬라 브라우저가 오디오 장치를 점유하거나 음질이 열화되는 문제를 방지하기 위해, 모든 사운드(티맵 안내음, 멜론, 유튜브 뮤직 등)는 스마트폰에서 차량 블루투스 스피커로 **직접 A2DP 원음 출력**됩니다.

3. **🚀 720p 60 FPS 초저지연 하드웨어 인코딩 & WebCodecs 리플로우 제로**:
   - 짧은 축 720p 기준 스마트폰 원본 화면비(Aspect Ratio)를 100% 보존하여 왜곡이나 불필요한 여백 없이 꽉 찬 화면을 제공합니다.
   - MediaCodec H.264 `AVCProfileBaseline` 설정으로 B-프레임을 100% 제거하고 3.5 Mbps CBR 및 60 FPS로 지연시간을 50ms 미만으로 극소화합니다.
   - 렌더 루프 내 DOM 리플로우를 제거하여 60 FPS 무결점 렌더링을 유지합니다.

4. **🚀 WebCodecs + HTML5 Canvas 초저지연 렌더링 (주행 D모드 차단 우회)**:
   - 테슬라 내장 브라우저가 주행(D) 상태에서 HTML5 `<video>`를 강제 일시 정지시키는 제약을 우회하기 위해, **WebCodecs API (`VideoDecoder`)**와 `<canvas>` 하드웨어 가속 렌더러를 탑재하여 주행 중에도 무중단 재생됩니다.

5. **✂️ 티맵 분할 크롭 2배 확대 & 보험사 안전점수 100% 적립**:
   - 스마트폰 화면 분할 상태에서 상단 티맵 영역만 정밀 크롭하여 테슬라 화면에 2배로 채우는 **[✂️ 내비 확대]** 모드를 제공합니다.
   - 스마트폰 포그라운드에서 티맵이 정상 구동되므로 **티맵 운전 안전점수 및 자동차 보험 할인 혜택이 정상 적립**됩니다.

6. **🚗 차량 하차 시 자동 미러링 종료 & 상단 알림 빠른 제어**:
   - 차량 시동을 끄거나 하차하여 블루투스 연결이 해제되면 45초 후 백그라운드 미러링이 자동으로 안전하게 종료되어 배터리를 보호합니다.
   - 스마트폰 상단 포그라운드 알림창에서 `[🛑 미러링 종료]` 및 `[🌙 초절전 암전]`을 즉시 원터치로 제어할 수 있습니다.

7. **🚗 로컬 GPS 주행일지(Trip Log) & 오프라인 레이더 HUD**:
   - 테슬라 유료 Fleet API 불필요! 스마트폰 내장 고정밀 GPS로 주행거리, 소요시간, 평균/최고 속도를 로컬에 안전하게 자동 기록합니다.
   - Leaflet JS/CSS를 Rust 코어에 완전 임베딩하여 오프라인 0MB 환경에서도 **텍티컬 다크 레이더 HUD 그리드** 위에 이동 궤적을 실시간 시각화합니다.

---

## 🏗 시스템 & 네트워크 아키텍처

| 구분 | 주소 / 포트 | 역할 설명 |
|---|---|---|
| **차량 브라우저 접속** | **`https://mplat-mirror.web.app`** | Firebase Hosting 공식 웹 플레이어 (테슬라 브라우저 즐겨찾기) |
| **WebRTC 시그널링** | **Firebase Realtime Database** | `mplat-33044` 전용 SDP/ICE 신호 교환 (릴레이 불필요) |
| **화면/오디오 스트림** | **로컬 Wi-Fi P2P (UDP)** | 스마트폰 핫스팟 서브넷 직결 (**모바일 데이터 0MB 소모**) |
| **임시 APK 배포 서버** | **`https://mdm.mplat.store:8088`** | 최신 릴리즈 APK(`dist/mplatMirror.apk`) 다운로드 전용 |
| **레거시 포트 (9999 등)** | **폐기 (Closed)** | 사용하지 않으며 서버 바인딩 완전 차단 |

```mermaid
flowchart TD
    subgraph Tesla["🚗 테슬라 차량 (MCU3 내장 브라우저 & 오디오)"]
        TB["테슬라 브라우저 (https://mplat-mirror.web.app)"]
        CANVAS["Canvas 2D / WebCodecs (60 FPS 비디오)"]
        SPEAKER["차량 스피커 (Bluetooth A2DP 무손실)"]
        TOUCH["Touch/Gesture Controller"]
        TB --> CANVAS
        TB --> TOUCH
    end

    subgraph Firebase["🔥 Google Firebase"]
        HOSTING["Firebase Hosting (https://mplat-mirror.web.app)"]
        RTDB["Firebase Realtime Database (SDP/ICE 시그널링)"]
    end

    subgraph Smartphone["📱 안드로이드 스마트폰 (mplat Mirror 앱)"]
        MP["MediaProjection (화면 캡처 720p 60FPS)"]
        A2DP["차량 블루투스 오디오 직결"]
        WEBRTC["WebRtcStreamer"]
        MP --> WEBRTC
        A2DP === "0딜레이 무손실 Bluetooth A2DP" ===> SPEAKER
    end

    subgraph TempServer["📦 임시 배포 서버 (mdm.mplat.store:8088)"]
        APK["최신 APK 다운로드 (/dist/mplatMirror.apk)"]
    end

    TB <== "1. 웹 플레이어 로드" ==> HOSTING
    TB <== "2. 시그널링 교환 (SDP/ICE)" ==> RTDB
    WEBRTC <== "2. 시그널링 교환 (SDP/ICE)" ==> RTDB
    WEBRTC <=== "3. 로컬 핫스팟 Wi-Fi P2P 직결 (UDP, 모바일 데이터 0MB, 지연 <50ms)" ===> TB
    Smartphone -. "최신 APK 다운로드" .-> APK
```

---

## 🚀 빠른 시작 가이드 (Quick Start)

### 1단계: 스마트폰 핫스팟 및 앱 실행
1. 스마트폰 상단바에서 **[모바일 핫스팟]**을 켭니다.
2. 차량 블루투스에 스마트폰을 연결합니다.
3. `mplat Mirror` 앱을 실행하고 하단의 **[미러링 시작]**을 누릅니다.

### 2단계: 테슬라 Wi-Fi 연결
1. 테슬라 차량 Wi-Fi 설정에서 스마트폰 핫스팟을 연결합니다.
2. 연결 옵션에서 **[드라이브 중 연결 유지 (Remain connected in Drive)]**를 반드시 체크합니다.

### 3단계: 테슬라 브라우저 접속
1. 테슬라 화면의 내장 웹 브라우저를 엽니다.
2. 주소창에 아래 공식 주소를 입력하고 **즐겨찾기(북마크)**에 등록합니다:
   ```
   https://mplat-mirror.web.app
   ```
   *(Firebase Hosting의 공식 SSL 인증서로 녹색 자물쇠와 함께 즉시 열립니다)*
3. 즉시 50ms 미만 초저지연 미러링 화면이 재생됩니다!
4. 상단 메뉴의 **[✂️ 내비 확대]**를 누르면 티맵이 테슬라 화면에 2배로 시원하게 확대됩니다.

---

## 📦 최신 APK 다운로드

- **공식 임시 다운로드 링크 (포트 8088)**: [https://mdm.mplat.store:8088/dist/mplatMirror.apk](https://mdm.mplat.store:8088/dist/mplatMirror.apk)

---

## 🏷️ 버전 관리 (Version)

- **현재 버전**: `v1.1.9` (코드 119)
- **주요 릴리즈 이력**:
  - `v1.1.9`:
    - **미러링 연결 대기 멈춤 버그 완벽 해결**: 시그널링 시 스마트폰과 차량 간 단말 시계 오차(Clock Skew)로 인해 `viewer_ready` 및 `answer`가 만료된 것으로 오인되어 삭제되던 치명적 결함 수정 (`Math.abs` 오차 허용 및 실시간 SSE 이벤트 즉시 처리)
    - **방송 시작 시 능동적 초기 SDP Offer 즉시 생성**: 테슬라 브라우저가 이미 켜져 있거나 나중에 켜져도 대기 지연 없이 Offer를 즉각 수신하도록 선제적 오퍼 발행 로직 탑재
    - **8088 웹 플레이어 및 앱 버전 100% 동기화 (v1.1.9)**
  - `v1.1.8`:
    - **무의미한 60 FPS JNI 및 Rust 힙 메모리 복사(3~6MB/s) 완전 차단**: WebRTC 미러링 시 로컬 8282 클라이언트 검사로 불필요한 JNI 메모리 복사 원천 제거 (스마트폰 CPU 발열 및 배터리 소모 대폭 절감)
    - **하드웨어 인코더 CBR 모드 강제 & ABR 하드웨어 즉각 연동**: 빠른 화면 전환 시 급격한 10~15 Mbps 대역폭 버스트 및 Wi-Fi 버퍼블로트 원천 차단, 네트워크 혼잡 시 MediaCodec 칩셋 비트레이트 즉시 동적 조절
    - **테슬라 핀치 줌 제스처 완벽 동기화**: 제스처 스트로크(160ms ➡️ 100ms) 및 브라우저 쿨타임(110ms) 정합으로 안드로이드 OS 제스처 취소 현상 제거
    - **테슬라 대화면 텍스트/목적지 직접 입력 지원**: 접근성 `ACTION_SET_TEXT` 연동 및 웹 플레이어 키보드 입력 모달(`[⌨️ 입력]`) 탑재
    - **8088 웹 플레이어 및 앱 버전 100% 동기화 (v1.1.8)**
  - `v1.1.7`:
    - **Firebase 전용 시그널링 완전 일원화**: 불필요한 WebSocket 3초 재시도 루프 제거 및 배터리/네트워크 절감
    - **WebCodecs GPU 텍스처 메모리 누수 방지 (Backpressure Guard)**: 비디오 프레임 드로우 시 `videoFrame.close()` 100% 보장으로 테슬라 브라우저 장시간 미러링 안정성 대폭 향상
    - **초정밀 터치 반응성 튜닝**: 터치 슬롭(15~35px), 탭 스트로크(30ms), 스와이프 시간(50~250ms) 단축으로 즉각적인 관성 스크롤 및 지도 드래그 체감 개선
    - **레거시 Shizuku 잔재 완전 제거**: 기본 탑재된 네이티브 접근성 서비스(`TouchControlService`) 기반 터치 제어로 통합 및 UI/로그 정리
    - **앱 버전 동적 바인딩 및 웹 플레이어 100% 버전 일치**
  - `v1.1.6`:
    - 브라우저 접속 주소 `https://mplat-mirror.web.app` (Firebase Hosting) 표준화
    - 시그널링을 Firebase Realtime Database 전용으로 일원화
    - 불필요한 레거시 포트 9999 완전 폐기 및 포트 8088 APK 다운로드 전용화
    - WebCodecs 60 FPS 렌더 루프 레이아웃 리플로우(DOM 재계산) 완전 제거
    - 차량 하차 시(블루투스 해제 시 45초 카운트다운) 미러링 자동 종료 기능 추가
    - 포그라운드 상단 알림창 빠른 제어 액션 버튼([미러링 종료], [초절전 암전]) 탑재
    - 물리 전원 버튼 오조작 방지 안내 토스트 추가
    - WebRTC 로컬 UDP P2P 직결 표준 도입 (0MB 모바일 데이터 완벽 달성, VPN 불필요)
    - 스마트폰 비율 유지 720p 60 FPS 고화질 하드웨어 인코딩 고정
    - 차량 블루투스(A2DP) 고음질 무손실 오디오 직결
    - Rust 코어 엔진(서버/시그널링/에셋) 모듈화 및 보일러플레이트 축소
    - 8088 웹 플레이어 및 앱 버전 100% 동기화
  - `v1.0.0`:
    - 정식 브랜딩 적용 (`mplat Mirror`)
    - WebCodecs 초저지연 하드웨어 렌더링 및 양방향 터치 인젝션 지원

---

## 📄 라이선스 (License)

본 프로젝트는 **[Apache License 2.0](LICENSE)** 하에 배포됩니다.

```
Copyright 2026 mplat

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### 3rd-Party 오픈소스 고지
- **Rust Tokio & Axum**: MIT License
- **Leaflet.js**: BSD 2-Clause License
- **AndroidX & Jetpack**: Apache License 2.0
- **Kotlin Coroutines**: Apache License 2.0
- **Google Material Components**: Apache License 2.0
- **Google WebRTC (libjingle)**: BSD 3-Clause License
