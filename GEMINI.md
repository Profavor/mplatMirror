# mMirror Agent Rules & Core Project Principles

## 🚨 최우선 철학: 0MB 모바일 데이터 로컬 미러링 (Zero-Data In-Car Mirroring)

이 프로젝트(mMirror)의 가장 중요한 핵심 목표는 **"스마트폰의 LTE/5G 모바일 데이터를 단 1MB도 소모하지 않는 초저지연 테슬라 미러링"**을 구현하는 것입니다.

모든 에이전트는 향후 코드 작성, 아키텍처 설계, 디버깅 및 기능 추가 시 아래 원칙을 **절대적인 최우선 규칙**으로 준수해야 합니다.

---

### 1. 데이터 소모 제로 (0MB) 원칙
- **외부 릴레이 업로드 절대 금지 (기본값):**
  - 스마트폰의 비디오(H.264), 오디오(PCM), 터치 데이터를 외부 인터넷 서버(VPS, mdm.mplat.store 등)로 전송하면 **시간당 2.7GB 이상의 모바일 데이터가 소모**됩니다.
  - 외부 릴레이(`/publish`, 릴레이 서버 경유)는 비상용 또는 외부 원격 테스트용으로만 존재해야 하며, **통상적인 차량 미러링 시에는 반드시 비활성화(OFF)** 되어야 합니다.
- **순수 로컬 통신 (Local Wi-Fi Hotspot only):**
  - 모든 비디오/오디오/터치 스트림은 스마트폰 핫스팟이 형성한 **로컬 Wi-Fi 서브넷 내부(10.x.x.x 또는 192.168.x.x)에서만 순환**해야 합니다.
  - 테슬라 브라우저는 Firebase Hosting(`https://mplat-mirror.web.app`)에서 웹 플레이어를 로드하고, 로컬 핫스팟 P2P로 직접 통신합니다. (레거시 9999 포트는 폐기됨)

---

### 2. 테슬라 브라우저 로컬 접속 및 SSL/도메인 처리
- **테슬라 브라우저의 제약:**
  - 테슬라 브라우저는 사설 IP(`10.x.x.x`, `192.168.x.x`) 직접 접속을 PNA(Private Network Access) 보안 정책으로 차단하며, 자체 서명 인증서 접속 시 미디어 재생을 막습니다.
- **WebRTC 로컬 P2P 직결 표준 (0MB 모바일 데이터 & VPN 불필요):**
  - 테슬라 브라우저는 공식 Firebase Hosting 도메인(**`https://mplat-mirror.web.app`**)에서 공인 Let's Encrypt / Google SSL 인증서로 웹 플레이어를 로드합니다 (Secure Context 충족, 녹색 자물쇠).
  - 스마트폰과 테슬라 간 시그널링(SDP/ICE)은 **Firebase Realtime Database 전용**으로 교환되며, 실제 비디오/오디오/터치 스트림은 **핫스팟 로컬 Wi-Fi 서브넷(UDP 10.x.x.x)으로 직접 P2P 연결**됩니다.
  - 이 방식은 안드로이드 14~16의 eBPF 테더링 오프로드 제약을 완전히 우회하며, **VPN 없이도 0MB 모바일 데이터 및 초저지연(<50ms) 60 FPS 미러링**을 완벽히 달성합니다.
- **공식 다운로드 및 배포 (GitHub Releases & Firebase CDN):**
  - 최신 APK 및 AAB는 **GitHub Releases** (`https://github.com/Profavor/mplatMirror/releases/latest/download/mplatMirror.apk`) 및 Firebase Hosting CDN(`https://mplat-mirror.web.app/mplatMirror.apk`)을 통해 표준 HTTPS 443 포트로 안전하고 빠르게 배포됩니다. (레거시 8088 포트는 보안/방화벽 제약으로 완전 폐기됨)
- **로컬 IP 직접 접속 방식 (태블릿/PC):**
  - 핫스팟 게이트웨이 IP(`http://<핫스팟IP>:8282`)로 일반 태블릿/PC 브라우저가 직접 접속하는 방식도 완벽히 지원됩니다.

---

### 3. 초저지연 (Sub-50ms, 60 FPS) 인코딩 표준
- **해상도 및 프레임:** 기본 60 FPS 지원, 3.0 Mbps CBR
- **하드웨어 인코더 설정 (Qualcomm / Exynos):**
  - `KEY_PROFILE = AVCProfileBaseline` (B-프레임 제거로 인코더/디코더 버퍼 지연 0ms)
  - `KEY_LEVEL = AVCLevel41`
  - `KEY_LATENCY = 0` (Android R+: 칩셋 내부 룩어헤드 큐 비활성화)
  - `KEY_PRIORITY = 0` (실시간 우선순위)
- **전송 큐 제한:** 소켓 버퍼 48KB 초과 시 델타 프레임 드롭 (지연 누적 원천 차단)

---

### 4. Self-Healing 자동복구 (Watchdog)
- 테슬라 브라우저 측(`player.js`) 및 안드로이드 측(`MediaProjectionService.kt`) 모두 스트림 동결 감지 및 자동 재연결(Watchdog) 로직을 갖추되:
  - 불필요한 키프레임 폭주(`request_keyframe`)로 대역폭이 포화되지 않도록 최소 4~5초 쿨타임을 둘 것.
  - 연결 재시도 폭풍(storm)을 방지하고 정상 대기 모드로 안전하게 전환할 것.

---

### 5. 핵심 기기별 동작 특성 및 테슬라 연결 표준 (절대 망각 금지!)
- **일반 태블릿/스마트폰/PC:**
  - 핫스팟 로컬 IP(`http://<핫스팟IP>:8282`)로 접속 시 딜레이 0초, 60 FPS로 완벽하게 동작함.
- **테슬라 차량 브라우저:**
  - 테슬라는 공식 Firebase Hosting 도메인(**`https://mplat-mirror.web.app`**)으로 접속하여 WebRTC 로컬 P2P 직결로 화면/오디오를 수신함.
  - VPN, TUN 가상 프록시, 복잡한 네트워크 조작 없이 100% 순수 로컬 Wi-Fi UDP로 동작하여 모바일 데이터 소모가 0MB이며, 삼성페이 등 타 앱에 전혀 간섭하지 않음.

---

### 6. 답변 시 최신 GitHub 다운로드 링크 항시 제공 원칙 (사용자 필수 지침)
- 스마트폰 및 PC에서 즉시 최신 빌드를 내려받아 설치할 수 있도록, **모든 답변 시 항상 GitHub 최신 릴리즈 다운로드 링크를 안내**할 것:
  - **APK**: `https://github.com/Profavor/mplatMirror/releases/latest/download/mplatMirror.apk`
  - **AAB**: `https://github.com/Profavor/mplatMirror/releases/latest/download/mplatMirror.aab`
  - (8088 포트는 방화벽 차단 이슈로 폐기되었으므로 사용 금지)

---

### 7. 웹 플레이어 & APK/AAB 버전 항시 100% 동기화 원칙 (사용자 필수 지침)
- 앱 버전(예: `v1.3.1`) 업데이트 시, 테슬라/웹 브라우저가 접속하는 **웹 플레이어 버전 및 GitHub 릴리즈도 반드시 동일하게 동기화**할 것.
- **동기화 대상 파일 목록:**
  1. `android/app/build.gradle.kts` (`versionCode`, `versionName`)
  2. `web/index.html` (`.app-version-badge`, `.badge-version`, footer 법적 고지 버전 텍스트, 스크립트 캐시 쿼리)
  3. `dist/index.html` 및 `index.html`
  4. `web/download.html`, `dist/download.html`, `download.html` (다운로드 페이지 버전 및 변경 내역)
  5. `core-rust/Cargo.toml` (`version`)
  6. `firebase.json` (`redirects` 대상 버전 태그)
  7. GitHub 릴리즈 발행 (`gh release create <tag> ...`) 및 Firebase Hosting 배포 (`npx firebase-tools deploy --only hosting`)
- 사용자가 테슬라 화면(`https://mplat-mirror.web.app`) 또는 다운로드 페이지(`https://mplat-mirror.web.app/download`)를 열었을 때 안내되는 버전 배지와 스마트폰에 설치된 앱 버전이 1자리도 틀림없이 완벽히 일치해야 함.
