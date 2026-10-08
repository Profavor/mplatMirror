// mMirror Tesla Browser Player: WebCodecs Engine
(function() {
    'use strict';

    // --- 테슬라 차량 내 실시간 콘솔 로거 (In-Car Console Logger) ---
    const MAX_CAPTURED_LOGS = 600;
    const capturedLogs = [];
    let isConsoleModalOpen = false;
    let autoScrollConsole = true;
    let activeConsoleFilter = 'all'; // 'all', 'sdp', 'wrtc', 'err'
    let consoleSearchQuery = '';
    let lastReportedAppVersion = '';
    let lastOfferMlines = [];
    let ontrackEventCount = 0;

    const origConsole = {
        log: console.log.bind(console),
        info: (console.info || console.log).bind(console),
        warn: (console.warn || console.log).bind(console),
        error: (console.error || console.log).bind(console)
    };

    function formatLogArg(arg) {
        if (arg === null) return 'null';
        if (arg === undefined) return 'undefined';
        if (typeof arg === 'string') return arg;
        if (arg instanceof Error) return arg.stack || arg.message;
        try {
            return JSON.stringify(arg);
        } catch (_) {
            return String(arg);
        }
    }

    function recordConsoleLog(level, args) {
        try {
            const now = new Date();
            const timeStr = String(now.getHours()).padStart(2, '0') + ':' +
                            String(now.getMinutes()).padStart(2, '0') + ':' +
                            String(now.getSeconds()).padStart(2, '0') + '.' +
                            String(now.getMilliseconds()).padStart(3, '0');
            const message = Array.from(args).map(formatLogArg).join(' ');
            const entry = { time: timeStr, level: level, text: message };

            capturedLogs.push(entry);
            if (capturedLogs.length > MAX_CAPTURED_LOGS) {
                capturedLogs.shift();
            }

            const countBadge = document.getElementById('consoleLogCount');
            if (countBadge) {
                countBadge.textContent = `${capturedLogs.length}건`;
            }

            if (isConsoleModalOpen) {
                appendLogEntryToUI(entry);
            }
        } catch (_) {}
    }

    console.log = function(...args) {
        origConsole.log(...args);
        recordConsoleLog('log', args);
    };
    console.info = function(...args) {
        origConsole.info(...args);
        recordConsoleLog('info', args);
    };
    console.warn = function(...args) {
        origConsole.warn(...args);
        recordConsoleLog('warn', args);
    };
    console.error = function(...args) {
        origConsole.error(...args);
        recordConsoleLog('error', args);
    };

    window.addEventListener('error', (event) => {
        console.error('💥 [UNCAUGHT]', event.message, 'at', (event.filename || '') + ':' + (event.lineno || ''));
    });
    window.addEventListener('unhandledrejection', (event) => {
        console.error('💥 [UNHANDLED-PROMISE]', event.reason);
    });

    function logMatchesFilter(entry) {
        if (activeConsoleFilter === 'sdp') {
            const txt = entry.text.toLowerCase();
            if (!txt.includes('sdp') && !txt.includes('m=') && !txt.includes('audio') && !txt.includes('track') && !txt.includes('transceiver')) {
                return false;
            }
        } else if (activeConsoleFilter === 'wrtc') {
            const txt = entry.text.toLowerCase();
            if (!txt.includes('webrtc') && !txt.includes('ice') && !txt.includes('datachannel') && !txt.includes('peer') && !txt.includes('candidate')) {
                return false;
            }
        } else if (activeConsoleFilter === 'err') {
            if (entry.level !== 'warn' && entry.level !== 'error') {
                return false;
            }
        }
        if (consoleSearchQuery) {
            if (!entry.text.toLowerCase().includes(consoleSearchQuery)) {
                return false;
            }
        }
        return true;
    }

    function createLogDomElement(entry) {
        const row = document.createElement('div');
        row.className = 'log-entry';

        const timeSpan = document.createElement('span');
        timeSpan.className = 'log-time';
        timeSpan.textContent = entry.time;

        const levelSpan = document.createElement('span');
        levelSpan.className = `log-level log-level-${entry.level}`;
        levelSpan.textContent = entry.level.toUpperCase();

        const msgSpan = document.createElement('span');
        msgSpan.className = 'log-msg';
        if (entry.text.includes('[SDP-') || entry.text.includes('m=')) {
            msgSpan.classList.add('highlight-sdp');
        } else if (entry.text.includes('[WEBRTC-ONTRACK]') || entry.text.includes('ontrack')) {
            msgSpan.classList.add('highlight-ontrack');
        }
        msgSpan.textContent = entry.text;

        row.appendChild(timeSpan);
        row.appendChild(levelSpan);
        row.appendChild(msgSpan);
        return row;
    }

    function appendLogEntryToUI(entry) {
        const container = document.getElementById('consoleLogContent');
        if (!container) return;
        if (!logMatchesFilter(entry)) return;

        const el = createLogDomElement(entry);
        container.appendChild(el);

        if (autoScrollConsole) {
            container.scrollTop = container.scrollHeight;
        }
    }

    function renderAllLogsToUI() {
        const container = document.getElementById('consoleLogContent');
        if (!container) return;
        container.innerHTML = '';
        const fragment = document.createDocumentFragment();
        for (let i = 0; i < capturedLogs.length; i++) {
            if (logMatchesFilter(capturedLogs[i])) {
                fragment.appendChild(createLogDomElement(capturedLogs[i]));
            }
        }
        container.appendChild(fragment);
        if (autoScrollConsole) {
            container.scrollTop = container.scrollHeight;
        }
    }

    const canvas = document.getElementById('videoCanvas');
    const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
    if (ctx) {
        ctx.imageSmoothingEnabled = true;
        ctx.imageSmoothingQuality = 'medium';
    }
    const statusDot = document.getElementById('statusDot');
    const statusText = document.getElementById('statusText');
    const resolutionInfo = document.getElementById('resolutionInfo');
    const fpsInfo = document.getElementById('fpsInfo');
    const bitrateInfo = document.getElementById('bitrateInfo');
    const latencyInfo = document.getElementById('latencyInfo');
    const disconnectOverlay = document.getElementById('disconnectOverlay');
    const overlayTitle = document.getElementById('overlayTitle');
    const overlayMessage = document.getElementById('overlayMessage');
    const btnFullscreen = document.getElementById('btnFullscreen');

    let ws = null;
    let videoDecoder = null;
    let frameCount = 0;
    let lastFpsTime = performance.now();
    let isConnected = false;
    let videoConfigured = false;

    // --- Self-Healing Watchdog 엔진 상태 ---
    let lastVideoPacketTime = 0;       // 마지막 비디오 패킷 수신 시각 (ms)
    let watchdogInterval = null;       // 워치독 타이머 ID
    let hasEverReceivedVideo = false;  // 최초 비디오 수신 여부
    let watchdogKeyframeRequested = false; // 키프레임 재요청 중복 방지
    let webRtcKeyframeRequested = false;   // WebRTC 키프레임 초기 요청 플래그
    let reconnectInProgress = false;   // 재연결 진행 중 중복 방지

    // 패킷 타입 식별자
    const PKT_TYPE_VIDEO = 0x01; // H.264 NAL Frame
    const PKT_TYPE_AUDIO = 0x02; // Raw PCM Audio (48000Hz, 16bit Stereo)
    const PKT_TYPE_CONFIG = 0x03; // Metadata (Width, Height, FPS, etc.)
    const PKT_TYPE_GPS = 0x04;    // Realtime GPS
    const CURRENT_WEB_VERSION = '1.4.3';

    // 오디오 및 A/V 싱크 제어 상태 변수 (100ms 지터 링 버퍼 엔진)
    let audioCtx = null;
    let audioGainNode = null;
    let audioScriptNode = null;
    let audioDummyOsc = null;
    let audioGainLevel = 1.0;
    let isAudioStreamingActive = (localStorage.getItem('mmirror_audio_mode') === 'web');
    let videoDelayMs = parseInt(localStorage.getItem('mmirror_video_delay') || '0', 10);
    let videoDelayQueue = []; // A/V 싱크 지연 버퍼 큐 [{ bytes, isKeyFrame, arrivalTime }]
    let webrtcAudioDataChannel = null;

    // 100ms 지터 링 버퍼 (Jitter Ring Buffer) 상태
    const PCM_RING_CAPACITY = 96000; // 48kHz 스테레오 2.0초 완충 용량
    const pcmRingBufferL = new Float32Array(PCM_RING_CAPACITY);
    const pcmRingBufferR = new Float32Array(PCM_RING_CAPACITY);
    let pcmRingWritePos = 0;
    let pcmRingReadPos = 0;
    let pcmRingAvailable = 0;
    let isPcmBuffering = true; // 언더런 방지 프리버퍼링 상태 플래그

    // [미디어 세션 및 오디오 격리 방어 엔진]
    // 테슬라 브라우저가 화면 미러링 시작 시 미디어 소스를 '웹'으로 전환하여
    // 차량 인앱 뮤직(Spotify 등)이나 스마트폰 블루투스 음악을 가로채는 현상을 원천 방지합니다.
    function enforceSilentMediaSession() {
        if ('mediaSession' in navigator) {
            try {
                navigator.mediaSession.playbackState = 'none';
                navigator.mediaSession.metadata = null;
            } catch (_) {}
        }
    }

    if ('mediaSession' in navigator) {
        try {
            navigator.mediaSession.playbackState = 'none';
            navigator.mediaSession.metadata = null;
            // playbackState를 'none'으로 영구 고정 (내부 엔진이나 브라우저가 'playing'으로 변경 불가)
            try {
                Object.defineProperty(navigator.mediaSession, 'playbackState', {
                    get: () => 'none',
                    set: () => {},
                    configurable: true,
                    enumerable: true
                });
            } catch (_) {}
            // 미디어 액션 핸들러 무력화 (미디어 제어 카드가 테슬라 OS에 등록되는 것 원천 차단)
            try {
                navigator.mediaSession.setActionHandler = () => {};
            } catch (_) {}
            console.log('🛡️ [AUDIO-SHIELD] MediaSession permanently locked to playbackState="none" (Zero Audio Interference)');
        } catch (_) {}
    }

    function checkAppVersionMismatch(appVersion) {
        if (!appVersion) return;
        lastReportedAppVersion = appVersion;
        const diagApp = document.getElementById('diagAppVer');
        if (diagApp) diagApp.textContent = `v${appVersion}`;

        if (appVersion !== CURRENT_WEB_VERSION) {
            console.warn(`⚠️ [VERSION] Phone app version (v${appVersion}) does not match web player (v${CURRENT_WEB_VERSION})`);
            const badge = document.getElementById('versionMismatchBadge');
            const mismatchText = document.getElementById('mismatchAppVer');
            const overlayWarn = document.getElementById('overlayVersionWarning');
            const overlayVer = document.getElementById('overlayMismatchVer');
            if (badge && mismatchText) {
                mismatchText.textContent = appVersion;
                badge.style.display = 'inline-flex';
            }
            if (overlayWarn && overlayVer) {
                overlayVer.textContent = appVersion;
                overlayWarn.style.display = 'block';
            }
        }
    }

    // --- 화면 꺼짐 방지 (Screen Wake Lock) ---
    let wakeLockSentinel = null;

    async function requestWakeLock() {
        try {
            if ('wakeLock' in navigator) {
                if (wakeLockSentinel && !wakeLockSentinel.released) {
                    return;
                }
                wakeLockSentinel = await navigator.wakeLock.request('screen');
                wakeLockSentinel.addEventListener('release', () => {
                    console.log('💡 Screen Wake Lock 해제됨');
                    wakeLockSentinel = null;
                });
                console.log('💡 Screen Wake Lock 획득 (화면 꺼짐 방지)');
            }
        } catch (err) {
            console.warn('Wake Lock 요청 실패 또는 미지원:', err);
        }
    }

    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') {
            requestWakeLock();
        }
    });

    function activateAntiSleep() {
        requestWakeLock();
    }

    ['click', 'touchstart', 'pointerdown'].forEach(evt => {
        document.addEventListener(evt, activateAntiSleep, { passive: true });
    });

    // 전체화면 토글
    if (btnFullscreen) {
        btnFullscreen.addEventListener('click', () => {
            if (!document.fullscreenElement) {
                document.documentElement.requestFullscreen().catch(err => {
                    console.warn('전체화면 전환 실패:', err);
                });
            } else {
                document.exitFullscreen();
            }
        });
    }

    // 화면 맞춤 모드 (Left / Fill / Cover / Center)
    const btnFitMode = document.getElementById('btnFitMode');
    const FIT_MODES = [
        { id: 'left', label: '🚗 좌측 밀착 (운전석)', title: '핸드폰 화면을 운전석(좌측)에 밀착 배치' },
        { id: 'fill', label: '⚡ 꽉 채움', title: '화면 전체 꽉 채움 (여백 0% 풀스크린)' },
        { id: 'cover', label: '🔍 스마트 줌', title: '비율 유지 꽉 채움 (레터박스 제거)' },
        { id: 'center', label: '📱 중앙 정렬', title: '화면 중앙 정렬 (원본 비율)' }
    ];
    let currentFitIndex = 0;
    const savedFitMode = localStorage.getItem('mmirror_fit_mode') || 'left';
    const foundIndex = FIT_MODES.findIndex(m => m.id === savedFitMode);
    if (foundIndex >= 0) currentFitIndex = foundIndex;

    let lastAppliedLayout = { cw: 0, ch: 0, targetW: 0, targetH: 0, mode: '' };

    function updateCanvasDisplayLayout(force = false) {
        if (!canvas) return;
        const container = canvas.parentElement;
        if (!container) return;

        const cw = container.clientWidth;
        const ch = container.clientHeight;
        const targetW = canvas.width || 1080;
        const targetH = canvas.height || 1920;
        const mode = window.screenFitMode || 'left';

        if (!force && lastAppliedLayout.cw === cw && lastAppliedLayout.ch === ch &&
            lastAppliedLayout.targetW === targetW && lastAppliedLayout.targetH === targetH &&
            lastAppliedLayout.mode === mode) {
            return;
        }

        lastAppliedLayout = { cw, ch, targetW, targetH, mode };

        if (mode === 'fill') {
            canvas.style.width = cw + 'px';
            canvas.style.height = ch + 'px';
            canvas.style.marginLeft = '0';
            canvas.style.marginRight = '0';
        } else if (mode === 'cover') {
            const scale = Math.max(cw / targetW, ch / targetH);
            canvas.style.width = Math.round(targetW * scale) + 'px';
            canvas.style.height = Math.round(targetH * scale) + 'px';
            canvas.style.marginLeft = '0';
            canvas.style.marginRight = '0';
        } else if (mode === 'center') {
            const scale = Math.min(cw / targetW, ch / targetH);
            const w = Math.round(targetW * scale);
            canvas.style.width = w + 'px';
            canvas.style.height = Math.round(targetH * scale) + 'px';
            canvas.style.marginLeft = Math.max(0, Math.round((cw - w) / 2)) + 'px';
            canvas.style.marginRight = 'auto';
        } else {
            // 'left' (기본): 세로 100% 꽉 채우고, 가로는 비율 유지하여 좌측 밀착 (운전석 최적화)
            const scale = ch / targetH;
            canvas.style.height = ch + 'px';
            canvas.style.width = Math.round(targetW * scale) + 'px';
            canvas.style.marginLeft = '0';
            canvas.style.marginRight = 'auto';
        }
        updateMirrorSidePanelLayout(cw, ch, parseInt(canvas.style.width) || targetW, mode);
        updateFloatingZoomPosition(cw, ch, parseInt(canvas.style.width) || targetW, mode);
    }

    // 플로팅 줌 컨트롤러(+/-) 위치를 미러링 화면 바로 우측에 밀착 배치
    function updateFloatingZoomPosition(cw, ch, canvasW, mode) {
        const zoomControls = document.getElementById('floatingZoomControls');
        if (!zoomControls) return;

        if (window.currentViewMode !== 'mirror') {
            zoomControls.style.display = 'none';
            return;
        }

        zoomControls.style.display = 'flex';

        let canvasLeft = 0;
        if (mode === 'center') {
            canvasLeft = Math.max(0, Math.round((cw - canvasW) / 2));
        } else {
            canvasLeft = 0;
        }

        const canvasRight = canvasLeft + canvasW;
        const zoomWidth = 46;
        const margin = 10;

        // 미러링 화면 바로 우측에 붙임:
        // 우측에 60px 이상 여백이 있으면 화면 바로 바깥 우측(canvasRight + 10px)에 밀착
        // 화면이 가로로 꽉 차서 우측 바깥 여백이 없으면 화면 우측 안쪽 가장자리에 밀착
        if (canvasRight + zoomWidth + margin <= cw) {
            zoomControls.style.left = (canvasRight + margin) + 'px';
            zoomControls.style.right = 'auto';
        } else {
            zoomControls.style.left = Math.max(0, canvasRight - zoomWidth - margin) + 'px';
            zoomControls.style.right = 'auto';
        }

        zoomControls.style.bottom = '40px';
    }

    // 미러링 우측 빈 공간 정보 패널 (시계, 실시간 날씨, 퀵 위젯) 레이아웃 동적 계산
    function updateMirrorSidePanelLayout(cw, ch, canvasW, mode) {
        const sidePanel = document.getElementById('mirrorSidePanel');
        const restoreBtn = document.getElementById('btnSidePanelRestore');
        if (!sidePanel) return;

        if (window.currentViewMode !== 'mirror') {
            sidePanel.style.display = 'none';
            if (restoreBtn) restoreBtn.style.display = 'none';
            return;
        }

        const isUserEnabled = localStorage.getItem('mmirror_show_side_widget') !== 'false';

        let remainingW = 0;
        let leftPos = 0;

        if (mode === 'left') {
            remainingW = cw - canvasW;
            leftPos = canvasW;
        } else if (mode === 'center') {
            remainingW = Math.max(0, Math.round((cw - canvasW) / 2));
            leftPos = Math.round((cw + canvasW) / 2);
        } else {
            remainingW = 0;
        }

        // 우측 여유 공간이 220px 이상 충분할 때만 위젯 표시 (가로 전체화면 내비 등으로 꽉 찼을 때는 미표시)
        if (remainingW >= 220) {
            if (isUserEnabled) {
                sidePanel.style.display = 'flex';
                sidePanel.style.left = leftPos + 'px';
                sidePanel.style.width = remainingW + 'px';
                if (restoreBtn) restoreBtn.style.display = 'none';
            } else {
                sidePanel.style.display = 'none';
                if (restoreBtn) restoreBtn.style.display = 'block';
            }
        } else {
            sidePanel.style.display = 'none';
            if (restoreBtn) restoreBtn.style.display = 'none';
        }
    }

    window.addEventListener('resize', () => updateCanvasDisplayLayout(true));

    function applyFitMode(modeObj) {
        window.screenFitMode = modeObj.id;
        if (canvas) canvas.className = 'fit-' + modeObj.id;
        if (btnFitMode) {
            btnFitMode.textContent = modeObj.label;
            btnFitMode.title = modeObj.title;
        }
        localStorage.setItem('mmirror_fit_mode', modeObj.id);
        updateCanvasDisplayLayout(true);
        console.log('화면 채움 모드 적용:', modeObj.id);
    }

    if (btnFitMode) {
        btnFitMode.addEventListener('click', () => {
            currentFitIndex = (currentFitIndex + 1) % FIT_MODES.length;
            applyFitMode(FIT_MODES[currentFitIndex]);
        });
    }
    applyFitMode(FIT_MODES[currentFitIndex]);

    // 상단바 컨트롤 (★ 사용자 요구사항: "설정 버튼 없애고 탑바 고정으로 하자")
    const topBar = document.getElementById('topBar');
    const tripLogModal = document.getElementById('tripLogModal');

    function showUiControls() {
        if (topBar) topBar.classList.remove('ui-hidden');
    }

    function hideUiControls() {
        // 탑바 완전 고정 모드: 숨기지 않음
    }

    function resetUiHideTimer() {
        // 탑바 완전 고정 모드: 자동 숨김 없음
    }

    window.resetUiHideTimer = resetUiHideTimer;
    window.showUiControls = showUiControls;
    window.hideUiControls = hideUiControls;

    // 초기 상태에서 탑바 항상 표시
    showUiControls();

    // 상단바 내부를 조작할 때는 닫힘 타이머 연장
    if (topBar) {
        topBar.addEventListener('click', (e) => {
            e.stopPropagation();
            resetUiHideTimer();
        });
    }

    // 티맵 분할 크롭 모드 (상단 50%만 2배 확대)
    const btnCropToggle = document.getElementById('btnCropToggle');
    window.isCropMode = false;
    window.cropRatio = 0.5; // 기본 상단 50% 분할

    function toggleCropMode() {
        window.isCropMode = !window.isCropMode;
        if (btnCropToggle) {
            btnCropToggle.classList.toggle('active', window.isCropMode);
            btnCropToggle.textContent = window.isCropMode ? '🔍 전체 복원' : '✂️ 내비 확대';
        }
        console.log('티맵 분할 크롭 모드:', window.isCropMode);
    }

    if (btnCropToggle) btnCropToggle.addEventListener('click', toggleCropMode);

    let hasReceivedFirstKeyFrame = false;
    let skippedDeltaFramesBeforeKey = 0;

    function initVideoDecoder() {
        if (location.search.includes('nodec')) {
            console.log('🧪 [TEST-NODEC] ?nodec 모드 활성화: VideoDecoder 초기화 생략');
            return;
        }

        if (!('VideoDecoder' in window)) {
            console.warn('WebCodecs VideoDecoder 미지원 브라우저 (WebRTC P2P 모드 사용)');
            return;
        }

        try {
            if (videoDecoder && videoDecoder.state !== 'closed') {
                videoDecoder.close();
            }
        } catch (_) {}

        videoConfigured = false;
        hasReceivedFirstKeyFrame = false;
        skippedDeltaFramesBeforeKey = 0;
        enforceSilentMediaSession();

        videoDecoder = new VideoDecoder({
            output: renderVideoFrame,
            error: (err) => {
                console.error('⚠️ [DECODER] VideoDecoder 에러 감지 -> 즉각 재초기화 및 키프레임 요청:', err);
                videoConfigured = false;
                hasReceivedFirstKeyFrame = false;
                initVideoDecoder();
                requestKeyframe();
            }
        });
    }

    // 초저지연 프레임 렌더러 (WebCodecs & WebRTC MediaStreamTrackProcessor 공용)
    // HTMLMediaElement(<video>)를 거치지 않고 직접 캔버스에 GPU 가속 60 FPS 드로우
    function renderVideoFrame(videoFrame) {
        hasEverReceivedWebRtcVideo = true;
        lastWebRtcFrameTime = performance.now();

        // 첫 프레임 수신 시 오버레이 숨김 및 상태 업데이트
        if (statusText && (!statusText.textContent.includes('0MB') || statusText.textContent.includes('협상') || statusText.textContent.includes('복구') || statusText.textContent.includes('대기') || statusText.textContent.includes('지연'))) {
            enforceSilentMediaSession();
            statusText.textContent = '0MB 로컬 WebRTC 스트리밍 중';
            if (statusDot) statusDot.className = 'dot connected';
        }
        if (disconnectOverlay && !disconnectOverlay.classList.contains('hidden')) {
            disconnectOverlay.classList.add('hidden');
        }
        if (!hasEverReceivedWebRtcVideo) {
            hasEverReceivedWebRtcVideo = true;
            if (typeof showMirrorView === 'function') {
                showMirrorView();
            }
        }

        // 캔버스 크기 동기화
        const targetW = videoFrame.displayWidth;
        const targetH = window.isCropMode ? Math.round(videoFrame.displayHeight * (window.cropRatio || 0.5)) : videoFrame.displayHeight;

        if (canvas.width !== targetW || canvas.height !== targetH) {
            canvas.width = targetW;
            canvas.height = targetH;
            if (resolutionInfo) resolutionInfo.textContent = `${targetW}x${targetH}` + (window.isCropMode ? ' (티맵 확대)' : '');
            updateCanvasDisplayLayout(true);
        }

        try {
            // 초저지연 프레임 드로우
            if (window.isCropMode) {
                // 스마트폰 화면의 상단 50%(티맵 영역)만 2배 확대해서 캔버스 전체에 꽉 채워 렌더링!
                const srcH = videoFrame.displayHeight * (window.cropRatio || 0.5);
                ctx.drawImage(videoFrame, 0, 0, videoFrame.displayWidth, srcH, 0, 0, canvas.width, canvas.height);
            } else {
                ctx.drawImage(videoFrame, 0, 0, canvas.width, canvas.height);
            }
        } finally {
            videoFrame.close();
        }

        // FPS 계산
        frameCount++;
        const now = performance.now();
        if (now - lastFpsTime >= 1000) {
            const fps = Math.round((frameCount * 1000) / (now - lastFpsTime));
            if (fpsInfo) fpsInfo.textContent = `${fps} FPS`;
            frameCount = 0;
            lastFpsTime = now;
        }
    }

    // --- Self-Healing Watchdog 엔진: 스트림 동결 감지 및 자동 복구 ---
    let isStreamActive = false;         // 폰이 실제로 스트림을 보내고 있는지 여부
    let lastKeyframeRequestTime = 0;    // 키프레임 재요청 쿨타임 (2.5초 제한)
    let watchdogReconnectCount = 0;     // 연속 재연결 횟수 (폭주 방지)

    // WebRTC P2P Self-Healing 전용 상태 변수
    let hasEverReceivedWebRtcVideo = false;
    let lastWebRtcFrameTime = performance.now();
    let lastWebRtcRecoveryTime = 0;
    let webRtcRecoveryCount = 0;
    let disconnectedTimer = null;

    function requestKeyframe() {
        const now = performance.now();
        if (now - lastKeyframeRequestTime < 4500) return; // 4.5초 쿨타임 (Rule #4: 키프레임 폭주 및 대역폭 포화 원천 차단)
        lastKeyframeRequestTime = now;
        videoDelayQueue = []; // 키프레임 재요청 시 지연 큐 즉각 초기화하여 프레임 오염 방지
        console.log('🔑 [KEYFRAME-REQ] 스마트폰에 키프레임(IDR) 동기화 요청 전송');
        if (webrtcDataChannel && webrtcDataChannel.readyState === 'open') {
            try { webrtcDataChannel.send(JSON.stringify({ type: 'request_keyframe' })); } catch (_) {}
            return; // DataChannel이 열려있으면 순수 로컬로만 전송 (Firebase 중복 쓰기 방지)
        }
        sendSignalingMessage({ type: 'request_keyframe' });
        if (ws && ws.readyState === WebSocket.OPEN) {
            try { ws.send(JSON.stringify({ type: 'request_keyframe' })); } catch (_) {}
        }
    }
    const sendKeyframeRequest = requestKeyframe;

    function triggerWebRtcAutoRecovery(reason) {
        const now = performance.now();
        // 복구 쿨다운: 10초 부여하여 재연결 폭풍(storm) 및 30초 무한 반복 원천 차단
        if (now - lastWebRtcRecoveryTime < 10000) {
            console.log('⏳ [WEBRTC-RECOVERY] 복구 쿨다운 진행 중 (스킵):', reason);
            return;
        }
        lastWebRtcRecoveryTime = now;
        webRtcRecoveryCount++;
        console.warn(`🔄 [WEBRTC-RECOVERY] (#${webRtcRecoveryCount}) Self-Healing 무중단 자동 복구 가동: ${reason}`);

        statusText.textContent = `🔄 스트림 자동 복구 중... (#${webRtcRecoveryCount})`;
        statusDot.className = 'dot connecting';

        // 디코더는 완전히 파괴하지 않고 상태 리셋만 시도
        hasReceivedFirstKeyFrame = false;
        try {
            if (videoDecoder && videoDecoder.state !== 'closed') {
                videoDecoder.reset();
            }
        } catch (_) {}

        // 통합 시그널링으로 reconnect 전달
        sendSignalingMessage({ type: 'reconnect', reason: reason });
    }

    function startWatchdog() {
        if (watchdogInterval) clearInterval(watchdogInterval);
        watchdogInterval = setInterval(() => {
            const now = performance.now();

            // [A] WebRTC 로컬 P2P 직결 모드 전용 Self-Healing 워치독
            if (isWebRtcConnected || hasEverReceivedWebRtcVideo) {
                const rtcIdleMs = now - lastWebRtcFrameTime;
                const packetIdleMs = now - lastVideoPacketTime;
                const pongIdleMs = now - lastPongReceivedTime;
                const pc = peerConnection;
                const pcState = pc ? pc.connectionState : 'closed';
                const iceState = pc ? pc.iceConnectionState : 'closed';
                const dcState = webrtcDataChannel ? webrtcDataChannel.readyState : 'closed';

                // 주기적(5초마다) 생존 및 품질 텔레메트리 콘솔 로깅
                if (now - lastHealthLogTime >= 5000) {
                    lastHealthLogTime = now;
                    console.log(`📡 [HEALTH] 0MB 로컬 WebRTC 상태 점검 | 렌더링: ${Math.round(rtcIdleMs)}ms 전, 패킷: ${Math.round(packetIdleMs)}ms 전, PONG: ${Math.round(pongIdleMs)}ms 전 | PC: ${pcState}, ICE: ${iceState}, DC: ${dcState}`);
                }

                // 정상 수신 상태: 프레임 렌더링 및 패킷 수신 중
                if (rtcIdleMs < 3000 && packetIdleMs < 3000) {
                    if (statusText && (statusText.textContent.includes('복구') || statusText.textContent.includes('대기') || statusText.textContent.includes('지연') || statusText.textContent.includes('동기화') || statusText.textContent.includes('단절'))) {
                        statusText.textContent = '0MB 로컬 WebRTC 스트리밍 중';
                        statusDot.className = 'dot connected';
                    }
                    webRtcRecoveryCount = 0;
                    return;
                }

                // 핸드셰이크 진행 중 보호: 연결 중(connecting/checking/new)일 때는 단절로 판정하지 않고 대기
                const isConnecting = pcState === 'connecting' || pcState === 'new' || iceState === 'checking' || iceState === 'new';
                if (isConnecting && (now - lastWebRtcRecoveryTime < 10000)) {
                    return;
                }

                // 상태 1: DataChannel이 정상 OPEN되어 있는 경우
                if (dcState === 'open') {
                    // 패킷 수신이 장시간(12초 이상) 두절되었거나, PONG 응답이 8초 이상 없을 경우 -> 좀비 DataChannel로 판정하고 자동 복구
                    if (packetIdleMs >= 12000 || (pongIdleMs >= 8000 && packetIdleMs >= 6000)) {
                        console.warn(`⚡ [WATCHDOG-WebRTC] 좀비 DataChannel 감지! (패킷: ${Math.round(packetIdleMs)}ms, PONG: ${Math.round(pongIdleMs)}ms 전) -> Self-Healing 재협상 실행`);
                        triggerWebRtcAutoRecovery(`좀비 DataChannel 감지 (패킷 ${Math.round(packetIdleMs/1000)}초 지연)`);
                        return;
                    }

                    // 일시적 패킷 지연(4.5초 이상) 시에는 키프레임 1회 요청 (Rule #4 준수, 디코더 파괴 방지)
                    if (packetIdleMs >= 4500 && (now - lastKeyframeRequestTime > 5000)) {
                        console.log(`🔑 [WATCHDOG-WebRTC] 패킷 지연 감지 (${Math.round(packetIdleMs)}ms) -> 키프레임 갱신 요청`);
                        if (statusText && !statusText.textContent.includes('대기') && !statusText.textContent.includes('복구')) {
                            statusText.textContent = '스마트폰 화면 동기화 중 (키프레임 요청)';
                        }
                        requestKeyframe();
                    }
                    return; // DataChannel이 열려있고 지연이 12초 미만인 동안에는 세션을 유지함
                }

                // 상태 2: DataChannel이 닫혔거나, WebRTC 연결 자체가 실패(failed/closed)한 경우
                // disconnected는 일시적 RF 지터일 수 있으므로 failed 또는 closed 상태에서만 즉각 복구 가동
                const isTransportDead = pcState === 'failed' || iceState === 'failed' || dcState === 'closed';
                if (isTransportDead) {
                    console.warn(`⚡ [WATCHDOG-WebRTC] 전송로 영구 단절 감지! (DC: ${dcState}, PC: ${pcState}, ICE: ${iceState}) -> Self-Healing 재협상 실행`);
                    triggerWebRtcAutoRecovery(`전송로 단절 감지 (DC: ${dcState}, PC: ${pcState})`);
                }
                return;
            }

            // [B] WebSocket 릴레이 폴백 모드 워치독
            // 한번이라도 비디오를 받은 적이 없으면 워치독 비활성
            if (!hasEverReceivedVideo || !isConnected) return;

            const idleMs = now - lastVideoPacketTime;

            // 스트림 활성 상태 판별: 최근 2초 이내에 패킷이 왔으면 활성
            if (idleMs < 2000) {
                isStreamActive = true;
                watchdogReconnectCount = 0; // 정상이면 카운터 리셋
                return; // 정상 수신 중이면 아무것도 안 함
            }

            // 스트림이 한번 멈추면 비활성으로 전환
            // 비활성 상태에서는 키프레임 1번만 요청하고 기다림 (재연결 폭풍 방지)
            if (!isStreamActive) return;

            // 1단계: 4초 이상 무응답 → 키프레임 재요청 (5초에 최대 1번)
            if (idleMs > 4000 && !watchdogKeyframeRequested && (now - lastKeyframeRequestTime > 5000)) {
                console.warn('🔑 [WATCHDOG] 4초 이상 비디오 무응답! 키프레임 재요청...');
                watchdogKeyframeRequested = true;
                lastKeyframeRequestTime = now;
                statusText.textContent = '🔄 스트림 복구 중...';
                statusDot.className = 'dot connecting';
                try {
                    if (ws && ws.readyState === WebSocket.OPEN) {
                        ws.send(JSON.stringify({ type: 'request_keyframe' }));
                    }
                } catch (_) {}
            }

            // 2단계: 10초 이상 무응답 → 웹소켓 강제 재연결 (최대 3회 연속)
            if (idleMs > 10000 && !reconnectInProgress) {
                if (watchdogReconnectCount >= 3) {
                    // 3회 연속 재연결 실패 → 폰이 송출 중단한 것으로 판단, 워치독 중지
                    console.warn('⏸️ [WATCHDOG] 3회 연속 재연결 실패. 폰 송출 중단 판단, 대기 모드 전환.');
                    isStreamActive = false;
                    watchdogReconnectCount = 0;
                    statusText.textContent = '스마트폰 송출 대기 중...';
                    statusDot.className = 'dot connected';
                    return;
                }
                console.warn('⚡ [WATCHDOG] 10초 이상 비디오 무응답! WebSocket 강제 재연결...');
                watchdogReconnectCount++;
                reconnectInProgress = true;
                forceReconnect();
                return;
            }
        }, 1500);
    }

    function forceReconnect() {
        console.log('🔧 [RECOVERY] 스트림 강제 재연결 시작...');
        try {
            if (ws) {
                ws.onclose = null;
                ws.onerror = null;
                ws.close();
            }
        } catch (_) {}
        ws = null;
        isConnected = false;
        videoConfigured = false;
        hasReceivedFirstKeyFrame = false;

        try {
            if (videoDecoder && videoDecoder.state !== 'closed') {
                videoDecoder.close();
            }
        } catch (_) {}
        videoDecoder = null;

        statusDot.className = 'dot disconnected';
        statusText.textContent = '🔄 자동 재연결 중...';

        setTimeout(() => {
            reconnectInProgress = false;
            connectWebSocket();
        }, 3000); // 3초 쿨타임으로 재연결 폭풍 방지
    }

    // 수동 원클릭 비상 복구 함수
    function emergencyRecover() {
        console.log('🆘 [EMERGENCY] 사용자 수동 비상 복구 트리거!');
        hasEverReceivedVideo = false;
        isStreamActive = false;
        watchdogKeyframeRequested = false;
        reconnectInProgress = false;
        watchdogReconnectCount = 0;

        // WebRTC P2P 재동기화 및 SDP 새로고침
        if (webrtcSignalWs && webrtcSignalWs.readyState === WebSocket.OPEN) {
            try {
                webrtcSignalWs.send(JSON.stringify({ type: 'ready' }));
            } catch (_) {}
        } else {
            connectWebRtc();
        }

        forceReconnect();
    }

    // 화면 켜짐/탭 복귀 시 즉시 동기화
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && hasEverReceivedVideo && isStreamActive) {
            const now = performance.now();
            const idleMs = now - lastVideoPacketTime;
            if (idleMs > 3000 && (now - lastKeyframeRequestTime > 5000)) {
                console.log('📱 [WATCHDOG] 화면 복귀 감지! 키프레임 재요청...');
                lastKeyframeRequestTime = now;
                try {
                    if (ws && ws.readyState === WebSocket.OPEN) {
                        ws.send(JSON.stringify({ type: 'request_keyframe' }));
                    }
                } catch (_) {}
            }
        }
    });

    window.addEventListener('pageshow', () => {
        if (hasEverReceivedVideo && isStreamActive) {
            const idleMs = performance.now() - lastVideoPacketTime;
            if (idleMs > 10000) {
                forceReconnect();
            }
        }
    });

    // --- WebRTC 로컬 P2P 연결 엔진 (0MB 모바일 데이터) ---
    let peerConnection = null;
    let webrtcDataChannel = null;
    let webrtcSignalWs = null;
    let isWebRtcConnected = false;
    let activeTrackReader = null;
    let processedPhoneCandidates = new Set();
    let currentOfferUfrag = null;
    let lastHandledOfferTimestamp = 0;
    let publisherOnline = false;
    let publisherOnlineTimestamp = 0;
    let pendingCandidates = [];

    // --- 스트림 종료 및 연결 해제 시 초기 화면 복귀 엔진 ---
    function resetToInitialScreen(reason, force = false) {
        if (!force && isWebRtcConnected && (performance.now() - lastWebRtcFrameTime < 3500)) {
            console.log('🛡️ WebRTC 스트림이 정상 수신 중이므로 resetToInitialScreen 무시:', reason);
            return;
        }
        console.log('🔄 [RESET] 스트리밍 종료/끊김 감지 -> 초기 대기 화면 복구:', reason, force ? '(강제)' : '');
        isWebRtcConnected = false;
        isConnected = false;
        videoConfigured = false;
        hasReceivedFirstKeyFrame = false;
        hasEverReceivedVideo = false;
        hasEverReceivedWebRtcVideo = false;
        webRtcRecoveryCount = 0;
        webRtcKeyframeRequested = false;
        isStreamActive = false;
        watchdogKeyframeRequested = false;
        lastHandledOfferTimestamp = 0;
        currentOfferUfrag = null;
        publisherOnline = false;
        publisherOnlineTimestamp = 0;

        stopWebRtcStats();
        stopPingPong();

        // 0. 기존 WebRTC PeerConnection 완전 정리 (재연결 시 잔류 세션 충돌 방지)
        if (activeTrackReader) {
            try { activeTrackReader.cancel(); } catch (_) {}
            activeTrackReader = null;
        }
        processedPhoneCandidates.clear();

        if (webrtcDataChannel) {
            try {
                webrtcDataChannel.onopen = null;
                webrtcDataChannel.onclose = null;
                webrtcDataChannel.onerror = null;
                webrtcDataChannel.onmessage = null;
                webrtcDataChannel.close();
            } catch (_) {}
            webrtcDataChannel = null;
        }

        if (webrtcAudioDataChannel) {
            try {
                webrtcAudioDataChannel.onopen = null;
                webrtcAudioDataChannel.onclose = null;
                webrtcAudioDataChannel.onerror = null;
                webrtcAudioDataChannel.onmessage = null;
                webrtcAudioDataChannel.close();
            } catch (_) {}
            webrtcAudioDataChannel = null;
        }

        if (peerConnection) {
            try {
                peerConnection.onconnectionstatechange = null;
                peerConnection.oniceconnectionstatechange = null;
                peerConnection.onicecandidate = null;
                peerConnection.ontrack = null;
                peerConnection.ondatachannel = null;
                peerConnection.close();
            } catch (_) {}
            peerConnection = null;
        }
        pendingCandidates = [];

        // 비디오 디코더 완전 정리 (재시작 시 잔류 참조 프레임 및 디코딩 큐 지연 원천 차단)
        if (videoDecoder && videoDecoder.state !== 'closed') {
            try { videoDecoder.close(); } catch (_) {}
            videoDecoder = null;
        }

        // 1. WebSocket 대체 캔버스 잔상 클리어
        if (canvas) {
            try {
                if (ctx) ctx.clearRect(0, 0, canvas.width, canvas.height);
            } catch (_) {}
            canvas.style.display = 'none';
        }

        // 3. 지표 초기화 (0 FPS, -- ms)
        if (fpsInfo) fpsInfo.textContent = '0 FPS';
        if (latencyInfo) latencyInfo.textContent = '-- ms';
        if (resolutionInfo) resolutionInfo.textContent = '-- x --';

        // 4. 상태 표시 업데이트
        if (statusDot) statusDot.className = 'dot disconnected';
        if (statusText) statusText.textContent = reason || '스마트폰 연결 대기 중...';
        const modeBadge = document.getElementById('modeBadge');
        if (modeBadge) modeBadge.style.display = 'none';
        window.isStandaloneMode = undefined;

        // 5. 초기 대기/가이드 화면 오버레이 복원
        if (disconnectOverlay) {
            disconnectOverlay.classList.remove('hidden');
        }
        const overlayTitleText = document.getElementById('overlayTitleText');
        if (overlayTitleText) {
            overlayTitleText.textContent = "mplat Mirror 차량 연결 가이드";
        }
        if (overlayMessage) {
            overlayMessage.innerHTML = (reason ? `<strong>${reason}</strong><br>` : '') + "스마트폰 mplat Mirror 앱에서 파란색 <strong>[미러링 시작]</strong> 버튼을 누르고 <strong>'지금 시작'</strong>을 선택하세요.";
        }
        const overlayFooterText = document.getElementById('overlayFooterText');
        if (overlayFooterText) {
            overlayFooterText.textContent = '스마트폰의 [미러링 시작] 신호를 기다리는 중...';
        }
    }

    let isFirebaseSignaling = false;
    let firebaseDb = null;
    let firebaseRoomRef = null;

    function isFirebaseConfigured() {
        return window.FIREBASE_CONFIG &&
               window.FIREBASE_CONFIG.projectId &&
               window.FIREBASE_CONFIG.projectId.length > 0 &&
               typeof firebase !== 'undefined';
    }

    function applySignalingConfig(msg) {
        if (!msg) return;
        if (msg.width && msg.height && resolutionInfo) {
            resolutionInfo.textContent = `${msg.width}x${msg.height}`;
        }
        if (typeof msg.isStandalone === 'boolean') {
            window.isStandaloneMode = msg.isStandalone;
            console.log('📱 [WEBRTC] Mode updated from signaling config: isStandalone =', msg.isStandalone);
            updateModeBadge(msg.isStandalone);
        }
        if (msg.appVersion) {
            checkAppVersionMismatch(msg.appVersion);
        }
    }

    function initFirebaseSignaling() {
        if (!isFirebaseConfigured()) return false;
        try {
            if (!firebase.apps.length) {
                firebase.initializeApp(window.FIREBASE_CONFIG);
            }
            firebaseDb = firebase.database();
            const urlParams = new URLSearchParams(window.location.search);
            const roomName = urlParams.get('room') || 'default';
            firebaseRoomRef = firebaseDb.ref('rooms/' + roomName);

            console.log('🔥 [FIREBASE] Realtime Database signaling initialized for room:', roomName);
            isFirebaseSignaling = true;
            enforceSilentMediaSession();
            startWatchdog();

            publisherOnline = false;
            publisherOnlineTimestamp = 0;
            lastHandledOfferTimestamp = 0;

            // 1. 퍼블리셔(스마트폰) 온라인 전환 감지 리스너
            firebaseRoomRef.child('publisher_status').on('value', (snapshot) => {
                const val = snapshot.val();
                if (val && val.online) {
                    console.log('🔥 [FIREBASE] Phone publisher is ONLINE!');
                    publisherOnline = true;
                    publisherOnlineTimestamp = val.timestamp || Date.now();
                    lastHandledOfferTimestamp = 0;
                    statusText.textContent = '스마트폰 연결 협상 중...';
                    statusDot.className = 'dot connecting';
                    // 폰에 테슬라 뷰어 준비 완료 즉시 전송
                    firebaseRoomRef.child('viewer_ready').set({
                        ready: true,
                        timestamp: Date.now()
                    });
                } else {
                    console.log('🔥 [FIREBASE] Phone publisher is OFFLINE');
                    publisherOnline = false;
                    resetToInitialScreen('스마트폰 미러링 종료 감지', true);
                }
            });

            // 2. Offer 수신 리스너 (중복 및 이전 세션 잔여 오퍼 필터링)
            firebaseRoomRef.child('offer').on('value', async (snapshot) => {
                const val = snapshot.val();
                if (val && val.sdp && val.type === 'offer') {
                    // 유효성 1: 2분 이상 지난 만료 오퍼 무시
                    if (val.timestamp && Math.abs(Date.now() - val.timestamp) > 120000) {
                        console.log('⏳ [FIREBASE] Ignoring stale offer (age:', Date.now() - val.timestamp, 'ms)');
                        return;
                    }
                    // 유효성 2: 이미 처리한 동일 타임스탬프 오퍼 무시
                    if (val.timestamp && val.timestamp === lastHandledOfferTimestamp) {
                        return;
                    }
                    // 유효성 3: 퍼블리셔가 오프라인이거나 현재 방송 개시보다 이전 잔여 오퍼 무시
                    if (!publisherOnline && publisherOnlineTimestamp > 0 && val.timestamp < publisherOnlineTimestamp) {
                        console.log('⏳ [FIREBASE] Ignoring offer from offline/previous session');
                        return;
                    }
                    lastHandledOfferTimestamp = val.timestamp || Date.now();

                    console.log('🔥 [FIREBASE] Received fresh WebRTC Offer from phone');
                    if (statusText && !isWebRtcConnected) {
                        statusText.textContent = '스마트폰 연결 협상 중...';
                        statusDot.className = 'dot connecting';
                    }
                    if (val.config) {
                        applySignalingConfig(val.config);
                    }
                    await handleWebRtcOffer(val.sdp, val.offerId || String(val.timestamp || ''));
                }
            });

            // 3. 스마트폰 ICE 후보 수신 리스너 (로컬 IP 우선 처리, 중복 방지 및 세션 ufrag 필터링)
            firebaseRoomRef.child('phone_candidates').on('child_added', async (snapshot) => {
                const candidate = snapshot.val();
                if (candidate && candidate.candidate) {
                    // TCP 후보 필터링 (불필요한 핸드셰이크 지연 원천 차단)
                    if (candidate.candidate.includes(' tcp ')) return;
                    if (processedPhoneCandidates.has(candidate.candidate)) return;

                    // 이전 세션 잔여 후보 필터링 (현재 활성 Offer의 ufrag와 불일치 시 무시)
                    if (currentOfferUfrag && candidate.candidate.includes('ufrag ')) {
                        const match = candidate.candidate.match(/ufrag\s+([^\s]+)/);
                        if (match && match[1] !== currentOfferUfrag) {
                            return;
                        }
                    }

                    const isLocalCandidate = candidate.candidate.includes('10.') || 
                                             candidate.candidate.includes('192.168.') || 
                                             candidate.candidate.includes('typ host');
                    if (processedPhoneCandidates.size >= 50 && !isLocalCandidate) return;
                    processedPhoneCandidates.add(candidate.candidate);
                    console.log('🔥 [FIREBASE] Received ICE candidate from phone:', candidate.candidate);
                    await handleRemoteCandidate(candidate);
                }
            });

            // 4. 미러링 종료 리스너 (접속 이전에 남아있던 과거 잔여 메시지 무시)
            firebaseRoomRef.child('stream_stopped').on('value', (snapshot) => {
                const val = snapshot.val();
                if (val && val.stopped === true && val.timestamp && Math.abs(Date.now() - val.timestamp) < 30000) {
                    console.log('🔥 [FIREBASE] Stream stopped');
                    resetToInitialScreen('스마트폰 미러링이 종료되었습니다.', true);
                }
            });

            // 초기 기동: 이전 잔여 후보 및 Answer 정리 후 테슬라 준비 완료 신호 1회 등록
            firebaseRoomRef.child('viewer_candidates').remove();
            firebaseRoomRef.child('answer').remove();
            firebaseRoomRef.child('viewer_ready').set({
                ready: true,
                timestamp: Date.now()
            });

            return true;
        } catch (e) {
            console.warn('⚠️ [FIREBASE] Signaling init failed, falling back to WebSocket:', e);
            isFirebaseSignaling = false;
            return false;
        }
    }

    function sendSignalingMessage(msg) {
        if (isFirebaseSignaling && firebaseRoomRef) {
            try {
                if (msg.type === 'answer') {
                    firebaseRoomRef.child('answer').set({
                        type: 'answer',
                        offerId: msg.offerId || '',
                        sdp: msg.sdp,
                        timestamp: Date.now()
                    });
                    console.log('🔥 [FIREBASE] Sent Answer to phone (offerId:', msg.offerId || 'none', ')');
                } else if (msg.type === 'candidate' && msg.candidate) {
                    const candStr = msg.candidate.candidate || '';
                    if (candStr.includes(' tcp ')) return;
                    const candData = typeof msg.candidate.toJSON === 'function' ? msg.candidate.toJSON() : {
                        candidate: candStr,
                        sdpMid: msg.candidate.sdpMid !== undefined ? msg.candidate.sdpMid : '',
                        sdpMLineIndex: msg.candidate.sdpMLineIndex !== undefined ? msg.candidate.sdpMLineIndex : 0
                    };
                    firebaseRoomRef.child('viewer_candidates').push(candData);
                    console.log('🔥 [FIREBASE] Successfully pushed candidate to Firebase:', candData.candidate);
                } else if (msg.type === 'ready') {
                    firebaseRoomRef.child('viewer_candidates').remove();
                    firebaseRoomRef.child('answer').remove();
                    firebaseRoomRef.child('viewer_ready').set({
                        ready: true,
                        timestamp: Date.now()
                    });
                } else if (msg.type === 'reconnect') {
                    firebaseRoomRef.child('answer').remove();
                    firebaseRoomRef.child('viewer_candidates').remove();
                    const now = Date.now();
                    firebaseRoomRef.child('reconnect_request').set({
                        timestamp: now,
                        reason: msg.reason || 'watchdog'
                    });
                    console.log('🔥 [FIREBASE] Sent Reconnect request to phone');
                } else if (msg.type === 'request_keyframe') {
                    firebaseRoomRef.child('keyframe_request').set({
                        timestamp: Date.now()
                    });
                }
            } catch (err) {
                console.warn('🔥 [FIREBASE] Send signaling error:', err);
            }
            return;
        }

        if (webrtcSignalWs && webrtcSignalWs.readyState === WebSocket.OPEN) {
            try {
                webrtcSignalWs.send(JSON.stringify(msg));
            } catch (err) {
                console.warn('📡 [WEBRTC] Send WS error:', err);
            }
        }
    }

    function connectWebRtc() {
        if (location.search.includes('idle')) {
            console.log('🧪 [TEST] ?idle: WebRTC 연결 시도 차단 (순수 대기 모드)');
            return;
        }

        if (location.search.includes('testpc')) {
            console.log('🧪 [TEST-PC] new RTCPeerConnection() 단독 인스턴스화 테스트');
            if (statusText) statusText.textContent = '🧪 [테스트] new RTCPeerConnection() 생성됨 (시그널링 미연결)';
            try {
                window._testPc = new RTCPeerConnection({
                    iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
                });
                console.log('🧪 [TEST-PC] RTCPeerConnection instance created successfully');
            } catch (err) {
                console.error('🧪 [TEST-PC] Error creating RTCPeerConnection:', err);
            }
            return;
        }

        if (location.search.includes('testdc')) {
            console.log('🧪 [TEST-DC] new RTCPeerConnection() + createDataChannel() 테스트');
            if (statusText) statusText.textContent = '🧪 [테스트] RTCPeerConnection + DataChannel 생성됨 (시그널링 미연결)';
            try {
                window._testPc = new RTCPeerConnection({
                    iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
                });
                window._testDc = window._testPc.createDataChannel('test_dc');
                console.log('🧪 [TEST-DC] RTCPeerConnection + DataChannel created successfully');
            } catch (err) {
                console.error('🧪 [TEST-DC] Error:', err);
            }
            return;
        }

        if (!window.RTCPeerConnection) {
            console.warn('WebRTC not supported on this browser');
            return;
        }

        // Firebase RTDB 시그널링이 설정되어 있으면 우선 초기화
        if (initFirebaseSignaling()) {
            return;
        }

        if (webrtcSignalWs && (webrtcSignalWs.readyState === WebSocket.CONNECTING || webrtcSignalWs.readyState === WebSocket.OPEN)) {
            return;
        }

        const urlParams = new URLSearchParams(window.location.search);
        const roomName = urlParams.get('room') || 'default';
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const signalUrl = `${protocol}//${location.host}/webrtc/signal?role=viewer&room=${encodeURIComponent(roomName)}`;
        console.log('📡 [WEBRTC] Connecting to signaling server:', signalUrl);

        try {
            webrtcSignalWs = new WebSocket(signalUrl);
        } catch (e) {
            console.warn('WebRTC signal socket error:', e);
            return;
        }

        webrtcSignalWs.onopen = () => {
            console.log('📡 [WEBRTC] Signaling open, initializing RTCPeerConnection and requesting fresh stream');
            setupPeerConnection();
            sendSignalingMessage({ type: 'ready' });
        };

        webrtcSignalWs.onmessage = async (event) => {
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === 'offer') {
                    console.log('📡 [WEBRTC] Received Offer from phone');
                    await handleWebRtcOffer(msg.sdp);
                } else if (msg.type === 'candidate' && msg.candidate) {
                    console.log('📡 [WEBRTC] Received ICE candidate');
                    await handleRemoteCandidate(msg.candidate);
                } else if (msg.type === 'config') {
                    applySignalingConfig(msg);
                } else if (msg.type === 'publisher_connected') {
                    console.log('📡 [WEBRTC] Phone publisher connected! Setting up fresh PeerConnection and requesting stream...');
                    setupPeerConnection();
                    sendSignalingMessage({ type: 'ready' });
                } else if (msg.type === 'stream_stopped') {
                    console.log('📡 [WEBRTC] Received stream_stopped from signaling:', msg.reason);
                    if (!isWebRtcConnected) {
                        resetToInitialScreen('스마트폰 미러링이 종료되었습니다.');
                    }
                }
            } catch (e) {
                console.warn('WebRTC signal message parse error:', e);
            }
        };

        webrtcSignalWs.onclose = () => {
            console.log('📡 [WEBRTC] Signaling closed. Reconnecting in 3s...');
            // 로컬 P2P 비디오 스트림이 이미 연결되어 흐르고 있다면 화면을 리셋하지 않고 백그라운드 재연결만 수행
            if (!isWebRtcConnected) {
                statusText.textContent = '스마트폰 신호 대기 중...';
            }
            setTimeout(connectWebRtc, 3000);
        };
    }

    let webrtcRfcId = null;
    let webrtcRfcFrames = 0;
    let webrtcLastRfcTime = performance.now();
    let webrtcStatsInterval = null;
    let pingInterval = null;
    let lastPingTime = performance.now();
    let lastPongReceivedTime = performance.now();
    let lastHealthLogTime = 0;

    function startPingPong() {
        if (pingInterval) clearInterval(pingInterval);
        pingInterval = setInterval(() => {
            if (webrtcDataChannel && webrtcDataChannel.readyState === 'open') {
                try {
                    lastPingTime = performance.now();
                    webrtcDataChannel.send(JSON.stringify({ type: 'ping', t: lastPingTime }));
                } catch (_) {}
            }
        }, 1200);
    }

    function stopPingPong() {
        if (pingInterval) {
            clearInterval(pingInterval);
            pingInterval = null;
        }
    }

    function startWebRtcStats(remoteVideo) {
        // 1. requestVideoFrameCallback (HTMLMediaElement 사용 시 실제 화면 렌더링 FPS 실시간 정밀 측정)
        if (remoteVideo && 'requestVideoFrameCallback' in remoteVideo) {
            const onFrame = (now) => {
                hasEverReceivedWebRtcVideo = true;
                lastWebRtcFrameTime = now;
                if (statusText.textContent.includes('복구') || statusText.textContent.includes('대기') || statusText.textContent.includes('지연')) {
                    statusText.textContent = '0MB 로컬 WebRTC 스트리밍 중';
                    statusDot.className = 'dot connected';
                }
                webrtcRfcFrames++;
                const elapsed = now - webrtcLastRfcTime;
                if (elapsed >= 1000) {
                    const fps = Math.round((webrtcRfcFrames * 1000) / elapsed);
                    if (fpsInfo) fpsInfo.textContent = `${fps} FPS`;
                    webrtcRfcFrames = 0;
                    webrtcLastRfcTime = now;
                }
                if (remoteVideo.videoWidth && remoteVideo.videoHeight && resolutionInfo) {
                    resolutionInfo.textContent = `${remoteVideo.videoWidth}x${remoteVideo.videoHeight}`;
                }
                webrtcRfcId = remoteVideo.requestVideoFrameCallback(onFrame);
            };
            if (!webrtcRfcId) {
                webrtcRfcId = remoteVideo.requestVideoFrameCallback(onFrame);
            }
        }

        // 2. RTCPeerConnection getStats 폴링 (FPS, 실시간 비트레이트, RTT, 패킷 손실 계측 및 ABR 텔레메트리 피드백)
        if (webrtcStatsInterval) clearInterval(webrtcStatsInterval);
        let prevFramesDecoded = 0;
        let prevStatsTime = performance.now();
        let prevBytesReceived = 0;
        let prevPacketsReceived = 0;
        let prevPacketsLost = 0;
        let lastNetStatsReportTime = 0;
        let latestRttMs = 0;
        let latestFps = 60;
        let latestBitrateMbps = 0;
        let latestLossRate = 0;
        let latestJitterMs = 0;

        webrtcStatsInterval = setInterval(async () => {
            if (!peerConnection || peerConnection.connectionState !== 'connected') return;
            try {
                const stats = await peerConnection.getStats();
                const now = performance.now();
                stats.forEach(report => {
                    if (report.type === 'inbound-rtp' && report.kind === 'video') {
                        if (report.frameWidth && report.frameHeight && resolutionInfo) {
                            resolutionInfo.textContent = `${report.frameWidth}x${report.frameHeight}`;
                        }
                        if (report.framesDecoded !== undefined && report.framesDecoded > prevFramesDecoded) {
                            hasEverReceivedWebRtcVideo = true;
                            lastWebRtcFrameTime = now;
                            if (statusText.textContent.includes('복구') || statusText.textContent.includes('대기') || statusText.textContent.includes('지연')) {
                                statusText.textContent = '0MB 로컬 WebRTC 스트리밍 중';
                                statusDot.className = 'dot connected';
                            }
                        }
                        if (report.framesDecoded !== undefined) {
                            prevFramesDecoded = report.framesDecoded;
                        }
                        if (!remoteVideo || !('requestVideoFrameCallback' in remoteVideo)) {
                            if (report.framesPerSecond !== undefined) {
                                latestFps = Math.round(report.framesPerSecond);
                                if (fpsInfo) fpsInfo.textContent = `${latestFps} FPS`;
                            } else if (report.framesDecoded !== undefined) {
                                const elapsed = (now - prevStatsTime) / 1000;
                                if (elapsed >= 1) {
                                    const diff = report.framesDecoded - prevFramesDecoded;
                                    const fps = Math.round(diff / elapsed);
                                    if (fps >= 0) {
                                        latestFps = fps;
                                        if (fpsInfo) fpsInfo.textContent = `${fps} FPS`;
                                    }
                                }
                            }
                        }

                        // 실시간 수신 비트레이트(Mbps) 정밀 계산
                        if (report.bytesReceived !== undefined) {
                            if (prevBytesReceived > 0) {
                                const bytesDiff = report.bytesReceived - prevBytesReceived;
                                const elapsedSec = (now - prevStatsTime) / 1000;
                                if (elapsedSec > 0 && bytesDiff >= 0) {
                                    latestBitrateMbps = (bytesDiff * 8) / (elapsedSec * 1_000_000);
                                    if (bitrateInfo) {
                                        bitrateInfo.textContent = `${latestBitrateMbps.toFixed(1)} Mbps`;
                                    }
                                }
                            }
                            prevBytesReceived = report.bytesReceived;
                        }

                        // 패킷 손실률 및 지터 계측
                        if (report.packetsReceived !== undefined) {
                            if (prevPacketsReceived > 0) {
                                const pktsDiff = report.packetsReceived - prevPacketsReceived;
                                const lossDiff = (report.packetsLost || 0) - prevPacketsLost;
                                if (pktsDiff > 0 && lossDiff >= 0) {
                                    latestLossRate = lossDiff / (pktsDiff + lossDiff);
                                } else {
                                    latestLossRate = 0;
                                }
                            }
                            prevPacketsReceived = report.packetsReceived;
                            prevPacketsLost = report.packetsLost || 0;
                        }
                        if (report.jitter !== undefined) {
                            latestJitterMs = Math.round(report.jitter * 1000);
                        }
                    }

                    if (report.type === 'candidate-pair' && report.state === 'succeeded') {
                        if (report.currentRoundTripTime !== undefined) {
                            latestRttMs = Math.round(report.currentRoundTripTime * 1000);
                            if (latencyInfo && (!latencyInfo.textContent || latencyInfo.textContent.includes('--') || latencyInfo.textContent === '0 ms')) {
                                latencyInfo.textContent = `${latestRttMs} ms`;
                            }
                        }
                    }
                });

                prevStatsTime = now;

                // 1.5초마다 스마트폰 ABR 제어기로 실시간 네트워크 텔레메트리 전송
                if (now - lastNetStatsReportTime >= 1500) {
                    lastNetStatsReportTime = now;
                    if (webrtcDataChannel && webrtcDataChannel.readyState === 'open') {
                        try {
                            webrtcDataChannel.send(JSON.stringify({
                                type: 'net_stats',
                                rtt: latestRttMs,
                                loss: Number(latestLossRate.toFixed(4)),
                                jitter: latestJitterMs,
                                fps: latestFps,
                                bitrate: Number(latestBitrateMbps.toFixed(2))
                            }));
                        } catch (_) {}
                    }
                }
            } catch (_) {}
        }, 1000);
    }

    function stopWebRtcStats() {
        if (webrtcStatsInterval) {
            clearInterval(webrtcStatsInterval);
            webrtcStatsInterval = null;
        }
        if (bitrateInfo) bitrateInfo.textContent = '-- Mbps';
        webrtcRfcId = null;
        webrtcRfcFrames = 0;
    }

    function setupPeerConnection() {
        if (peerConnection) {
            try {
                peerConnection.onconnectionstatechange = null;
                peerConnection.oniceconnectionstatechange = null;
                peerConnection.onicecandidate = null;
                peerConnection.ontrack = null;
                peerConnection.ondatachannel = null;
                peerConnection.close();
            } catch (_) {}
            peerConnection = null;
        }
        if (webrtcDataChannel) {
            try {
                webrtcDataChannel.onopen = null;
                webrtcDataChannel.onclose = null;
                webrtcDataChannel.onerror = null;
                webrtcDataChannel.onmessage = null;
                webrtcDataChannel.close();
            } catch (_) {}
            webrtcDataChannel = null;
        }
        if (webrtcAudioDataChannel) {
            try {
                webrtcAudioDataChannel.onopen = null;
                webrtcAudioDataChannel.onclose = null;
                webrtcAudioDataChannel.onerror = null;
                webrtcAudioDataChannel.onmessage = null;
                webrtcAudioDataChannel.close();
            } catch (_) {}
            webrtcAudioDataChannel = null;
        }
        if (activeTrackReader) {
            try { activeTrackReader.cancel(); } catch (_) {}
            activeTrackReader = null;
        }
        processedPhoneCandidates.clear();
        stopWebRtcStats();
        stopPingPong();
        isWebRtcConnected = false;
        pendingCandidates = [];

        const rtcConfig = {
            iceServers: [
                { urls: 'stun:stun.l.google.com:19302' },
                { urls: 'stun:stun1.l.google.com:19302' }
            ],
            sdpSemantics: 'unified-plan',
            bundlePolicy: 'max-bundle',
            rtcpMuxPolicy: 'require'
        };

        peerConnection = new RTCPeerConnection(rtcConfig);
        enforceSilentMediaSession();

        peerConnection.onicecandidate = (event) => {
            if (location.search.includes('noice')) {
                console.log('🧪 [TEST] ?noice: ICE candidate 전송 차단 (폰과 UDP 패킷 교환 안 함)');
                return;
            }
            if (event.candidate) {
                console.log('📡 [WEBRTC] Sending local ICE candidate to phone:', event.candidate.candidate);
                const candData = typeof event.candidate.toJSON === 'function' ? event.candidate.toJSON() : {
                    candidate: event.candidate.candidate || '',
                    sdpMid: event.candidate.sdpMid !== undefined ? event.candidate.sdpMid : '',
                    sdpMLineIndex: event.candidate.sdpMLineIndex !== undefined ? event.candidate.sdpMLineIndex : 0
                };
                sendSignalingMessage({
                    type: 'candidate',
                    candidate: candData
                });
            }
        };

        peerConnection.onconnectionstatechange = () => {
            const state = peerConnection.connectionState;
            console.log('⚡ [WEBRTC] Connection state:', state);
            const diagRtc = document.getElementById('diagRtcState');
            if (diagRtc) diagRtc.textContent = state;
            try {
                if (state === 'connected') {
                    if (disconnectedTimer) {
                        clearTimeout(disconnectedTimer);
                        disconnectedTimer = null;
                    }
                    enforceSilentMediaSession();
                    console.log('🎉 [WEBRTC] Direct P2P Connected to Phone! 0MB Local Stream Active!');
                    isWebRtcConnected = true;
                    isConnected = true;
                    hasEverReceivedWebRtcVideo = true;
                    lastWebRtcFrameTime = performance.now();
                    webRtcRecoveryCount = 0;
                    webRtcKeyframeRequested = false;
                    if (statusDot) statusDot.className = 'dot connected';
                    if (statusText) statusText.textContent = '0MB 로컬 WebRTC 스트리밍 중';
                    if (disconnectOverlay) disconnectOverlay.classList.add('hidden');
                    if (typeof showMirrorView === 'function') showMirrorView();
                    startWebRtcStats(null);
                } else if (state === 'disconnected') {
                    console.warn('⚡ [WEBRTC] Connection disconnected (transient), awaiting natural ICE recovery for 7.5s...');
                    if (statusText) statusText.textContent = '⚠️ 일시 연결 지연 (신호 회복 대기 중...)';
                    if (statusDot) statusDot.className = 'dot connecting';
                    if (disconnectedTimer) clearTimeout(disconnectedTimer);
                    disconnectedTimer = setTimeout(() => {
                        if (peerConnection && (peerConnection.connectionState === 'disconnected' || peerConnection.connectionState === 'failed')) {
                            console.warn('🔄 [WEBRTC] Disconnected 상태 7.5s 지속 감지 -> 즉각 Self-Healing 재협상 실행');
                            triggerWebRtcAutoRecovery('WebRTC 연결 단절 타임아웃');
                        }
                    }, 7500);
                } else if (state === 'failed') {
                    if (disconnectedTimer) {
                        clearTimeout(disconnectedTimer);
                        disconnectedTimer = null;
                    }
                    console.warn('⚡ [WEBRTC] Connection failed -> 즉각 Self-Healing 자동 복구 실행');
                    triggerWebRtcAutoRecovery('WebRTC 연결 실패 자동 복구');
                } else if (state === 'closed') {
                    if (disconnectedTimer) {
                        clearTimeout(disconnectedTimer);
                        disconnectedTimer = null;
                    }
                    console.log('🔌 [WEBRTC] Connection closed');
                }
            } catch (err) {
                console.error('⚠️ [WEBRTC] Error in onconnectionstatechange handler:', err);
                if (state === 'connected') {
                    if (statusDot) statusDot.className = 'dot connected';
                    if (statusText) statusText.textContent = '0MB 로컬 WebRTC 스트리밍 중';
                    if (disconnectOverlay) disconnectOverlay.classList.add('hidden');
                    if (typeof showMirrorView === 'function') showMirrorView();
                }
            }
        };

        peerConnection.oniceconnectionstatechange = () => {
            const iceState = peerConnection.iceConnectionState;
            console.log('⚡ [WEBRTC] ICE connection state:', iceState);
            const diagRtc = document.getElementById('diagRtcState');
            if (diagRtc && peerConnection.connectionState !== 'connected') {
                diagRtc.textContent = `${iceState} (ICE)`;
            }
            try {
                if (iceState === 'connected' || iceState === 'completed') {
                    isWebRtcConnected = true;
                    isConnected = true;
                    if (statusDot) statusDot.className = 'dot connected';
                    if (statusText) statusText.textContent = '0MB 로컬 WebRTC 스트리밍 중';
                    if (disconnectOverlay) disconnectOverlay.classList.add('hidden');
                    if (typeof showMirrorView === 'function') showMirrorView();
                    webRtcRecoveryCount = 0;
                } else if (iceState === 'failed') {
                    console.warn('⚡ [WEBRTC] ICE failed -> 즉각 Self-Healing 자동 복구 실행');
                    triggerWebRtcAutoRecovery('ICE 연결 실패');
                }
            } catch (err) {
                console.error('⚠️ [WEBRTC] Error in oniceconnectionstatechange handler:', err);
            }
        };

        peerConnection.ontrack = (event) => {
            ontrackEventCount++;
            console.warn(`🚨 [WEBRTC-ONTRACK] ontrack triggered! kind=${event.track ? event.track.kind : 'unknown'}, id=${event.track ? event.track.id : 'unknown'}`);
            const diagOntrack = document.getElementById('diagOntrackCount');
            if (diagOntrack) {
                diagOntrack.textContent = `${ontrackEventCount}회 (미디어 수신됨!)`;
                diagOntrack.style.color = '#f87171';
            }
            // 비디오 화면은 DataChannel을 통해 WebCodecs -> <canvas>로만 렌더링됩니다.
            // 미디어 트랙을 즉시 정지시켜 테슬라 미디어 소스 전환 원천 방지.
            try {
                if (event.track) {
                    event.track.enabled = false;
                    event.track.stop();
                }
            } catch (_) {}
        };

        peerConnection.ondatachannel = (event) => {
            console.log('💬 [WEBRTC] DataChannel received from phone:', event.channel.label);
            if (event.channel.label === 'audio') {
                setupAudioDataChannel(event.channel);
            } else {
                setupDataChannel(event.channel);
            }
        };
    }

    function showTestNotification(title, desc) {
        let el = document.getElementById('testNotificationBanner');
        if (!el) {
            el = document.createElement('div');
            el.id = 'testNotificationBanner';
            el.style.cssText = 'position:fixed; bottom:30px; left:50%; transform:translateX(-50%); background:#0f172a; border:2px solid #38bdf8; color:white; padding:16px 24px; border-radius:12px; font-size:16px; font-weight:bold; z-index:999999; box-shadow:0 10px 30px rgba(0,0,0,0.8); text-align:center; max-width:90vw;';
            document.body.appendChild(el);
        }
        el.innerHTML = `<div>🧪 ${title}</div><div style="font-size:13px; font-weight:normal; color:#94a3b8; margin-top:6px;">${desc}</div>`;
    }

    async function handleWebRtcOffer(sdp, offerId = '') {
        if (location.search.includes('noanswer')) {
            console.log('🧪 [TEST] ?noanswer: Answer 전송 차단 (Offer 수신 완료, P2P 미성립 상태 유지)');
            return;
        }
        const ufragMatch = sdp.match(/a=ice-ufrag:([^\r\n]+)/);
        currentOfferUfrag = ufragMatch ? ufragMatch[1].trim() : null;
        console.log(`🔄 WebRTC resetting PeerConnection for fresh incoming offer (offerId: ${offerId || 'none'}, ufrag: ${currentOfferUfrag})`);

        // [작업 지시서 1] 수신한 Offer SDP의 m= 줄 전체와 a=sendrecv/sendonly/recvonly/inactive 줄 로깅
        const sdpLines = sdp.split(/\r\n|\n/);
        const mLines = sdpLines.filter(l => l.startsWith('m='));
        const dirLines = sdpLines.filter(l => /^(a=sendrecv|a=sendonly|a=recvonly|a=inactive)/.test(l));
        lastOfferMlines = mLines;
        const diagMlinesSummary = document.getElementById('diagMlinesSummary');
        if (diagMlinesSummary) {
            diagMlinesSummary.textContent = mLines.length > 0 ? mLines.join(' | ') : '없음 (순수 데이터)';
        }
        console.log('📡 [SDP-OFFER-AUDIT] m= lines from phone:', mLines);
        console.log('📡 [SDP-OFFER-AUDIT] direction lines from phone:', dirLines);

        // 새 세션 오퍼 수신 시 디코더 및 키프레임 상태 초기화 (참조 프레임 꼬임 및 디코더 큐 밀림 방지)
        hasReceivedFirstKeyFrame = false;
        videoConfigured = false;
        skippedDeltaFramesBeforeKey = 0;
        if (videoDecoder && videoDecoder.state !== 'closed') {
            try { videoDecoder.close(); } catch (_) {}
            videoDecoder = null;
        }
        initVideoDecoder();

        setupPeerConnection();
        processedPhoneCandidates.clear();

        // [작업 지시서 2] 수신 쪽 방어: setRemoteDescription 전에 SDP의 audio/video m-section 포트를 0으로 거부
        const sanitizedSdp = sdpLines.map(line => {
            if (line.startsWith('m=audio ')) {
                console.warn('🛡️ [SDP-DEFENSE] Rejecting incoming audio m-section (port 0):', line);
                return line.replace(/^m=audio \d+/, 'm=audio 0');
            }
            if (line.startsWith('m=video ')) {
                console.warn('🛡️ [SDP-DEFENSE] Rejecting incoming video m-section (port 0, using DataChannel):', line);
                return line.replace(/^m=video \d+/, 'm=video 0');
            }
            return line;
        }).join('\r\n');

        // SDP 오퍼를 표준 규격 그대로 원본 적용 (BUNDLE mid 및 ICE candidate 페어링 100% 보장)
        const isNowms = location.search.includes('nowms');
        const isCleanSdp = location.search.includes('cleansdp');
        let sdpForRemote = sanitizedSdp;
        if (isNowms || isCleanSdp) {
            sdpForRemote = sdpForRemote.replace(/a=msid-semantic:[^\r\n]+\r?\n/g, '').replace(/a=msid:[^\r\n]+\r?\n/g, '');
            console.log('🧪 [TEST] Offer에서 a=msid-semantic / a=msid 제거');
        }
        if (isCleanSdp) {
            sdpForRemote = sdpForRemote.replace(/a=group:BUNDLE[^\r\n]+\r?\n/g, '');
            console.log('🧪 [TEST] ?cleansdp: Offer에서 a=group:BUNDLE 제거');
        }
        await peerConnection.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: sdpForRemote }));

        if (location.search.includes('stopat=srd')) {
            console.log('🧪 [TEST] ?stopat=srd: setRemoteDescription 완료 후 중단 (Answer 미생성)');
            if (statusText) statusText.textContent = '🧪 [테스트] setRemoteDescription 완료 (Answer 생성 안 함)';
            showTestNotification(
                '[테스트 완료] setRemoteDescription 완료',
                'Answer는 생성하지 않고 중단되었습니다.<br>블루투스 노래 상태를 확인해 주세요.'
            );
            return;
        }

        // [작업 지시서 2] setRemoteDescription 후 getTransceivers()에서 audio/video 트랜시버를 direction='inactive' + stop() 처리
        try {
            if (typeof peerConnection.getTransceivers === 'function') {
                const transceivers = peerConnection.getTransceivers();
                transceivers.forEach(tc => {
                    const kind = (tc.receiver && tc.receiver.track && tc.receiver.track.kind) || 'unknown';
                    console.warn(`🛑 [SDP-DEFENSE] Inactivating and stopping transceiver: kind=${kind}, mid=${tc.mid}`);
                    try {
                        tc.direction = 'inactive';
                        if (tc.stop) tc.stop();
                    } catch (e) {
                        console.warn('Transceiver stop warning:', e);
                    }
                });
            }
        } catch (e) {
            console.warn('getTransceivers warning:', e);
        }

        // remoteDescription 설정 완료 후 대기 중이던 ICE 후보들 중 현재 세션 일치 후보만 일괄 주입
        while (pendingCandidates.length > 0) {
            const cand = pendingCandidates.shift();
            try {
                const candStr = cand.candidate || '';
                if (currentOfferUfrag && candStr.includes('ufrag ')) {
                    const match = candStr.match(/ufrag\s+([^\s]+)/);
                    if (match && match[1] !== currentOfferUfrag) {
                        console.log(`⏳ [WEBRTC] Stale buffered candidate discarded (${match[1]} !== ${currentOfferUfrag})`);
                        continue;
                    }
                }
                await peerConnection.addIceCandidate(new RTCIceCandidate(cand));
                console.log('📡 [WEBRTC] Processed buffered candidate');
            } catch (e) {
                console.warn('Failed to add buffered candidate:', e);
            }
        }

        // Firebase RTDB에 이미 존재하는 후보들을 비동기로 즉시 추가 (createAnswer 전송을 차단하지 않음)
        if (firebaseRoomRef) {
            firebaseRoomRef.child('phone_candidates').once('value').then((candSnapshot) => {
                if (candSnapshot && candSnapshot.exists()) {
                    candSnapshot.forEach((child) => {
                        const cand = child.val();
                        if (cand && cand.candidate) {
                            handleRemoteCandidate(cand);
                        }
                    });
                }
            }).catch((err) => {
                console.warn('⚠️ Error querying snapshot phone_candidates:', err);
            });
        }

        if (location.search.includes('stopat=before_ca')) {
            console.log('🧪 [TEST] ?stopat=before_ca: createAnswer 직전 중단');
            if (statusText) statusText.textContent = '🧪 [테스트] createAnswer 직전 중단';
            return;
        }

        // [미디어 트랙 수신 차단] DataChannel 전용 오퍼에 대해 오디오/비디오 트랜시버 수신 완전 차단
        enforceSilentMediaSession();
        const isLegacyAns = location.search.includes('legacyans');
        const answerOptions = isLegacyAns ? {
            offerToReceiveAudio: false,
            offerToReceiveVideo: false
        } : {};
        console.log('🧪 [ANSWER] createAnswer options:', answerOptions);
        const answer = await peerConnection.createAnswer(answerOptions);

        if (location.search.includes('stopat=ca_only')) {
            console.log('🧪 [TEST] ?stopat=ca_only: createAnswer 완료 후 중단 (setLocalDescription 미호출)');
            if (statusText) statusText.textContent = '🧪 [테스트] createAnswer 완료 (setLocalDescription 미호출)';
            showTestNotification(
                '[테스트 완료] createAnswer 생성 완료',
                'setLocalDescription은 호출하지 않고 중단되었습니다.<br>블루투스 노래 상태를 확인해 주세요.'
            );
            return;
        }

        let sdpForLocal = answer.sdp;
        if (isNowms || isCleanSdp) {
            sdpForLocal = sdpForLocal.replace(/a=msid-semantic:[^\r\n]+\r?\n/g, '').replace(/a=msid:[^\r\n]+\r?\n/g, '');
            console.log('🧪 [TEST] Answer에서 a=msid-semantic / a=msid 제거');
        }
        if (isCleanSdp) {
            sdpForLocal = sdpForLocal.replace(/a=group:BUNDLE[^\r\n]+\r?\n/g, '');
            console.log('🧪 [TEST] ?cleansdp: Answer에서 a=group:BUNDLE 제거');
        }
        await peerConnection.setLocalDescription(new RTCSessionDescription({ type: 'answer', sdp: sdpForLocal }));
        enforceSilentMediaSession();

        if (location.search.includes('stopat=ans') || location.search.includes('stopat=sld')) {
            const isNoice = location.search.includes('noice');
            const tag = (isCleanSdp ? 'cleansdp' : isNowms ? 'nowms' : '기본') + (isNoice ? '+noice' : '');
            console.log(`🧪 [TEST] ?stopat=sld: setLocalDescription 완료 후 중단 (${tag})`);
            if (statusText) statusText.textContent = `🧪 [테스트] setLocalDescription 완료 (${tag})`;
            showTestNotification(
                `[테스트 완료] setLocalDescription 호출됨 (${tag})`,
                `화면 미러링은 의도적으로 중단된 상태입니다.<br>👉 <strong>스마트폰 블루투스 노래가 계속 나오나요? 아니면 웹 소리로 바뀌었나요?</strong>`
            );
            return;
        }

        console.log('📡 [WEBRTC] Sending Answer to phone (offerId:', offerId || 'none', ')');
        sendSignalingMessage({
            type: 'answer',
            offerId: offerId,
            sdp: answer.sdp,
            timestamp: Date.now()
        });
    }

    async function handleRemoteCandidate(candidate) {
        if (!peerConnection) return;
        const candObj = (typeof candidate === 'string') ? { candidate: candidate, sdpMid: '0', sdpMLineIndex: 0 } : candidate;
        const candStr = candObj.candidate || '';

        // 세션 불일치 후보 필터링 (과거 세션 잔류 후보 차단)
        if (currentOfferUfrag && candStr.includes('ufrag ')) {
            const match = candStr.match(/ufrag\s+([^\s]+)/);
            if (match && match[1] !== currentOfferUfrag) {
                console.log(`⏳ [WEBRTC] Stale candidate ignored (${match[1]} !== ${currentOfferUfrag})`);
                return;
            }
        }

        if (peerConnection.remoteDescription && peerConnection.remoteDescription.type) {
            try {
                await peerConnection.addIceCandidate(new RTCIceCandidate(candObj));
            } catch (e) {
                console.warn('Failed to add remote candidate:', e);
            }
        } else {
            console.log('⏳ [WEBRTC] Buffering remote candidate until remoteDescription is set');
            pendingCandidates.push(candObj);
        }
    }

    function setupDataChannel(channel) {
        webrtcDataChannel = channel;
        try {
            webrtcDataChannel.binaryType = 'arraybuffer';
        } catch (_) {}
        let isChannelOpenHandled = false;
        const handleChannelOpen = () => {
            if (isChannelOpenHandled) return;
            isChannelOpenHandled = true;
            enforceSilentMediaSession();
            console.log('💬 [WEBRTC] DataChannel OPEN! 0ms Touch, Control, and Video ready.');
            isWebRtcConnected = true;
            isConnected = true;
            lastWebRtcFrameTime = performance.now();
            if (!location.search.includes('nodec')) {
                initVideoDecoder();
            }
            if (canvas && !location.search.includes('noview')) {
                canvas.style.display = 'block';
                canvas.className = 'fit-' + (window.screenFitMode || 'left');
            }
            if (statusText) {
                statusText.textContent = location.search.includes('nodec')
                    ? '🧪 [테스트] WebRTC 연결됨 (디코더 OFF: ?nodec)'
                    : '0MB 로컬 WebRTC 스트리밍 중';
            }
            if (statusDot) statusDot.className = 'dot connected';
            if (disconnectOverlay) disconnectOverlay.classList.add('hidden');
            if (typeof showMirrorView === 'function' && !location.search.includes('noview')) {
                showMirrorView();
            }
            startPingPong();
            startWatchdog();
            try {
                webrtcDataChannel.send(JSON.stringify({ type: 'request_keyframe' }));
                webrtcDataChannel.send(JSON.stringify({ type: 'get_touch_status' }));
                // 안드로이드 앱에서 DataChannel 연결 즉시 app_list 및 필수 도크 아이콘을 자동 푸시하므로 중복 요청 제거
                const savedAudioMode = localStorage.getItem('mmirror_audio_mode') || 'bluetooth';
                if (savedAudioMode === 'web') {
                    webrtcDataChannel.send(JSON.stringify({ type: 'set_audio_mode', enabled: true }));
                } else {
                    webrtcDataChannel.send(JSON.stringify({ type: 'get_audio_mode' }));
                }
            } catch (_) {}
        };
        webrtcDataChannel.onopen = handleChannelOpen;
        if (webrtcDataChannel.readyState === 'open') {
            handleChannelOpen();
        }
        webrtcDataChannel.onclose = () => {
            isChannelOpenHandled = false;
            console.warn('💬 [WEBRTC] DataChannel CLOSED -> 즉각 Self-Healing 자동 복구 가동');
            stopPingPong();
            if (isWebRtcConnected || hasEverReceivedWebRtcVideo) {
                statusText.textContent = '🔄 데이터 채널 복구 중...';
                statusDot.className = 'dot connecting';
                triggerWebRtcAutoRecovery('DataChannel 닫힘 감지');
            }
        };
        webrtcDataChannel.onerror = (err) => {
            console.warn('💬 [WEBRTC] DataChannel ERROR (non-fatal, monitoring):', err);
        };
        webrtcDataChannel.onmessage = (event) => {
            if (event.data instanceof ArrayBuffer) {
                const view = new DataView(event.data);
                const packetType = view.getUint8(0);
                const payload = event.data.slice(1);
                if (packetType === PKT_TYPE_VIDEO) {
                    handleVideoPacket(payload);
                } else if (packetType === PKT_TYPE_AUDIO) {
                    playPcmAudio(payload);
                } else if (packetType === PKT_TYPE_CONFIG) {
                    handleConfigPacket(payload);
                } else if (packetType === PKT_TYPE_GPS) {
                    handleGpsPacket(payload);
                }
            } else if (typeof event.data === 'string') {
                try {
                    const data = JSON.parse(event.data);
                    if (data.type === 'audio_mode_status' && typeof data.enabled === 'boolean') {
                        updateAudioModeUI(data.enabled);
                        return;
                    }
                    if (data.type === 'stream_stopped') {
                        console.log('💬 [WEBRTC] Received stream_stopped via DataChannel');
                        resetToInitialScreen('스마트폰 미러링이 종료되었습니다.');
                        return;
                    }
                    if (data.type === 'screen_power_changed' && typeof data.on === 'boolean') {
                        isPhoneScreenOn = data.on;
                        updateScreenPowerUI();
                    } else if (data.type === 'pong' && typeof data.t === 'number') {
                        lastPongReceivedTime = performance.now();
                        const rtt = Math.round(performance.now() - data.t);
                        if (latencyInfo) latencyInfo.textContent = `${rtt} ms`;
                    } else if (data.type === 'toast' && data.message) {
                        showTeslaToast(data.message);
                    } else if (data.type === 'config') {
                        if (data.width && data.height && resolutionInfo) {
                            resolutionInfo.textContent = `${data.width}x${data.height}`;
                        }
                        if (typeof data.isStandalone === 'boolean') {
                            window.isStandaloneMode = data.isStandalone;
                            updateModeBadge(data.isStandalone);
                        }
                        if (data.appVersion) {
                            checkAppVersionMismatch(data.appVersion);
                        }
                    } else if (data.type === 'abr_status') {
                        console.log('⚡ [WEBRTC] Received abr_status:', data);
                        if (bitrateInfo) {
                            bitrateInfo.title = `적응형 ABR: ${data.quality || ''} (${data.bitrate_kbps} kbps, ${data.fps} FPS)`;
                            if (data.quality === 'ECO') {
                                bitrateInfo.style.color = '#e67e22';
                            } else if (data.quality === 'HD') {
                                bitrateInfo.style.color = '#2ecc71';
                            } else {
                                bitrateInfo.style.color = '';
                            }
                        }
                    } else if (data.type === 'touch_status' || data.type === 'shizuku_status') {
                        console.log('⚡ [WEBRTC] Received touch_status:', data);
                        if (typeof data.isStandalone === 'boolean') {
                            window.isStandaloneMode = data.isStandalone;
                            updateModeBadge(data.isStandalone);
                        }
                        updateTouchControlUI(data);
                    } else if (data.type === 'app_list' && Array.isArray(data.apps)) {
                        const touchOk = !!(data.touchControl ?? data.shizuku);
                        const touchRun = !!(data.touchRunning ?? data.shizukuRunning);
                        console.log('📱 [WEBRTC] Received app list from phone:', data.apps.length, 'apps. Touch:', touchOk);
                        if (typeof data.isStandalone === 'boolean') {
                            window.isStandaloneMode = data.isStandalone;
                            updateModeBadge(data.isStandalone);
                        }
                        updateTouchControlUI({
                            granted: touchOk,
                            running: touchRun,
                            installed: touchOk,
                            virtualDisplayId: data.virtualDisplayId
                        });
                        handleAppList(data.apps, touchOk, data.virtualDisplayId);
                    } else if (data.type === 'app_icons' && data.icons) {
                        const iconCount = Object.keys(data.icons).length;
                        console.log(`🖼️ [WEBRTC] Received priority app icons (${iconCount} apps)`);
                        handleAppIcons(data.icons);
                    } else if (data.type === 'gps') {
                        if (window.mMirrorGps && window.mMirrorGps.onGpsUpdate) {
                            window.mMirrorGps.onGpsUpdate(data.payload);
                        }
                        if (typeof updateDashboardGps === 'function') {
                            updateDashboardGps(data.payload);
                        }
                    }
                } catch (_) {}
            }
        };
    }

    function setupAudioDataChannel(channel) {
        webrtcAudioDataChannel = channel;
        webrtcAudioDataChannel.binaryType = 'arraybuffer';
        webrtcAudioDataChannel.onopen = () => {
            console.log('🔊 [WEBRTC] Dedicated Audio DataChannel OPEN! (Unordered, 0-retransmit UDP)');
        };
        webrtcAudioDataChannel.onclose = () => {
            console.log('🔊 [WEBRTC] Dedicated Audio DataChannel CLOSED');
            webrtcAudioDataChannel = null;
        };
        webrtcAudioDataChannel.onerror = (err) => {
            console.warn('🔊 [WEBRTC] Dedicated Audio DataChannel ERROR:', err);
        };
        webrtcAudioDataChannel.onmessage = (event) => {
            if (event.data instanceof ArrayBuffer) {
                const view = new DataView(event.data);
                const firstByte = view.getUint8(0);
                if (firstByte === PKT_TYPE_AUDIO) {
                    playPcmAudio(event.data.slice(1));
                } else {
                    playPcmAudio(event.data);
                }
            }
        };
    }

    function showTeslaToast(message) {
        let toast = document.getElementById('teslaToast');
        if (!toast) {
            toast = document.createElement('div');
            toast.id = 'teslaToast';
            toast.style.position = 'fixed';
            toast.style.top = '65px';
            toast.style.left = '50%';
            toast.style.transform = 'translateX(-50%)';
            toast.style.backgroundColor = 'rgba(25, 25, 30, 0.95)';
            toast.style.color = '#fff';
            toast.style.padding = '12px 24px';
            toast.style.borderRadius = '10px';
            toast.style.boxShadow = '0 6px 20px rgba(0,0,0,0.7)';
            toast.style.fontSize = '15px';
            toast.style.fontWeight = '500';
            toast.style.zIndex = '99999';
            toast.style.border = '1px solid rgba(255, 255, 255, 0.25)';
            toast.style.backdropFilter = 'blur(8px)';
            toast.style.transition = 'opacity 0.3s ease, transform 0.3s ease';
            toast.style.pointerEvents = 'none';
            document.body.appendChild(toast);
        }
        toast.textContent = message;
        toast.style.opacity = '1';
        toast.style.transform = 'translateX(-50%) translateY(0)';
        setTimeout(() => {
            toast.style.opacity = '0';
            toast.style.transform = 'translateX(-50%) translateY(-10px)';
        }, 4000);
    }

    // --- WebSocket 연결 및 스트림 수신 (로컬 핫스팟 직접 접속 시에만 사용) ---
    function connectWebSocket() {
        if (location.search.includes('idle')) {
            console.log('🧪 [TEST] ?idle: WebSocket 연결 시도 차단');
            return;
        }
        // Firebase Hosting(mplat-mirror.web.app) 및 공인 중계 도메인은 WebRTC P2P 직결 표준만 사용 (/ws 제외)
        const host = location.hostname.toLowerCase();
        if (host.includes('web.app') || host.includes('firebaseapp.com') || host.includes('mplat.store')) {
            console.log('🌐 WebRTC P2P 직결 모드 동작 (공인 도메인 환경: 레거시 /ws 웹소켓 비활성화)');
            return;
        }

        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${location.host}/ws`;

        console.log('Connecting to Local WebSocket:', wsUrl);
        if (!isWebRtcConnected) {
            statusText.textContent = '로컬 연결 시도 중...';
        }

        ws = new WebSocket(wsUrl);
        ws.binaryType = 'arraybuffer';

        ws.onopen = () => {
            console.log('WebSocket 연결 완료');
            isConnected = true;
            reconnectInProgress = false;
            if (!isWebRtcConnected) {
                statusDot.className = 'dot connected';
                statusText.textContent = '스마트폰 송출 대기 중...';
                const overlayTitleText = document.getElementById('overlayTitleText');
                if (overlayTitleText) overlayTitleText.textContent = "스마트폰에서 '미러링 시작'을 눌러주세요";
                if (overlayMessage) overlayMessage.innerHTML = "차량 화면과 스마트폰이 정상 연결되었습니다.<br>스마트폰 화면의 <strong>mplat Mirror 앱</strong>에서 <strong>[미러링 시작]</strong> 버튼을 누르면 즉시 화면이 송출됩니다.";
                disconnectOverlay.classList.remove('hidden');
            }
            initVideoDecoder();
            startWatchdog();
        };

        ws.onmessage = (event) => {
            const data = event.data;
            if (!(data instanceof ArrayBuffer)) return;

            const view = new DataView(data);
            const packetType = view.getUint8(0);
            const payload = data.slice(1);

            if (packetType === PKT_TYPE_VIDEO) {
                handleVideoPacket(payload);
            } else if (packetType === PKT_TYPE_AUDIO) {
                playPcmAudio(payload);
            } else if (packetType === PKT_TYPE_CONFIG) {
                handleConfigPacket(payload);
            } else if (packetType === PKT_TYPE_GPS) {
                handleGpsPacket(payload);
            }
        };

        function handleGpsPacket(payload) {
            try {
                const dec = new TextDecoder();
                const gpsData = JSON.parse(dec.decode(payload));
                if (window.mMirrorGps && window.mMirrorGps.onGpsUpdate) {
                    window.mMirrorGps.onGpsUpdate(gpsData);
                }
                if (typeof updateDashboardGps === 'function') {
                    updateDashboardGps(gpsData);
                }
            } catch (e) {
                console.warn('GPS packet parse error:', e);
            }
        }

        ws.onclose = () => {
            console.warn('WebSocket 연결 종료. 3초 후 재연결...');
            // WebRTC와 독립 격리: WebRTC P2P 세션이나 peerConnection을 일체 파괴하지 않음!
            if (!window.RTCPeerConnection && !isWebRtcConnected) {
                statusDot.className = 'dot disconnected';
                statusText.textContent = '스마트폰 연결 대기 중...';
            }
            setTimeout(connectWebSocket, 3000);
        };

        ws.onerror = (err) => {
            console.error('WebSocket 에러:', err);
            try { ws.close(); } catch (_) {}
        };
    }

    let dcVideoBytes = 0;
    let lastDcBitrateTime = performance.now();

    // H.264 NAL 패킷 실제 WebCodecs 하드웨어 디코더 전달
    function feedVideoDecoder(bytes, isKeyFrame) {
        if (!videoDecoder) {
            initVideoDecoder();
            if (!videoDecoder) return;
        }

        if (!videoConfigured) {
            // 초기 H.264 디코더 구성 (Baseline / Main Profile 호환)
            try {
                videoDecoder.configure({
                    codec: 'avc1.42002A', // Baseline Profile Level 4.2
                    optimizeForLatency: true
                });
                videoConfigured = true;
                console.log('VideoDecoder 설정 완료 (avc1.42002A)');
            } catch (e) {
                console.error('VideoDecoder configure 실패:', e);
                return;
            }
        }

        // WebCodecs 규격: configure 후 첫 번째 프레임은 반드시 키프레임(key)이어야 함
        if (!hasReceivedFirstKeyFrame) {
            if (!isKeyFrame) {
                skippedDeltaFramesBeforeKey++;
                if (skippedDeltaFramesBeforeKey % 15 === 0) {
                    console.warn(`⏳ [DECODER] 첫 키프레임 대기 중 (${skippedDeltaFramesBeforeKey} 프레임 스킵됨) -> 키프레임 재요청`);
                    requestKeyframe();
                }
                return; // 다음 키프레임 도착 시까지 대기
            }
            hasReceivedFirstKeyFrame = true;
            skippedDeltaFramesBeforeKey = 0;
            console.log('🎉 [DECODER] 첫 키프레임(IDR) 수신 완료 -> 디코딩 시작');
        }

        // WebCodecs H.264 참조 프레임 연속성 보장:
        // 중간 P-프레임 단독 드롭 절대 금지 (화면 지직거림/깨짐 원천 차단)
        if (videoDecoder.decodeQueueSize > 60) {
            console.warn('⚠️ [DECODER] 디코더 큐 극단적 과적체(>60) 감지 -> 디코더 초기화 및 새 키프레임 대기');
            hasReceivedFirstKeyFrame = false;
            videoConfigured = false;
            try { videoDecoder.reset(); } catch (_) {}
            initVideoDecoder();
            requestKeyframe();
            return;
        } else if (videoDecoder.decodeQueueSize > 20) {
            requestKeyframe();
        }

        if (videoDecoder.state === 'configured') {
            try {
                const chunk = new EncodedVideoChunk({
                    type: isKeyFrame ? 'key' : 'delta',
                    timestamp: performance.now() * 1000,
                    data: bytes
                });
                videoDecoder.decode(chunk);
            } catch (err) {
                console.warn('디코딩 청크 전송 실패:', err);
                hasReceivedFirstKeyFrame = false;
                requestKeyframe();
                if (videoDecoder.state === 'closed') {
                    videoConfigured = false;
                    initVideoDecoder();
                }
            }
        } else if (videoDecoder.state === 'closed') {
            videoConfigured = false;
            hasReceivedFirstKeyFrame = false;
            initVideoDecoder();
            requestKeyframe();
        }
    }

    // A/V 싱크 비디오 지연 버퍼 드레인 엔진 (웹 브라우저 오디오 시스템 버퍼 지연 보정)
    function drainVideoDelayQueue() {
        const now = performance.now();
        // 큐 과적체(>80 프레임, 약 1.3초) 발생 시 오래된 프레임 정리 (지연 누적 방지)
        if (videoDelayQueue.length > 80) {
            while (videoDelayQueue.length > 40) {
                videoDelayQueue.shift();
            }
        }
        while (videoDelayQueue.length > 0) {
            const item = videoDelayQueue[0];
            if (now - item.arrivalTime >= videoDelayMs) {
                videoDelayQueue.shift();
                feedVideoDecoder(item.bytes, item.isKeyFrame);
            } else {
                break;
            }
        }
    }

    // A/V 싱크 버퍼 즉각 플러시 (0ms 또는 모드 변경/터치 인터랙션 시)
    function flushVideoDelayQueue() {
        while (videoDelayQueue.length > 0) {
            const item = videoDelayQueue.shift();
            feedVideoDecoder(item.bytes, item.isKeyFrame);
        }
    }
    window.flushVideoDelayQueue = flushVideoDelayQueue;

    // 60FPS 애니메이션 루프를 통한 부드러운 딜레이 큐 드레인 보장
    function animateVideoDelayDrain() {
        if (videoDelayMs > 0 && videoDelayQueue.length > 0 && isAudioStreamingActive) {
            drainVideoDelayQueue();
        }
        requestAnimationFrame(animateVideoDelayDrain);
    }
    requestAnimationFrame(animateVideoDelayDrain);

    // NAL 패킷 처리 (초저지연 워치독 및 A/V 싱크 연동)
    function handleVideoPacket(payload) {
        // [원인 분리 테스트 스위치 1: 가설 B(WebCodecs 디코더) 격리 검증]
        // ?nodec 접속 시 WebRTC 연결 및 DataChannel 수신은 정상 유지하되 WebCodecs 비디오 디코딩만 건너뜀
        if (location.search.includes('nodec')) {
            lastVideoPacketTime = performance.now();
            hasEverReceivedVideo = true;
            hasEverReceivedWebRtcVideo = true;
            if (statusText && !statusText.textContent.includes('?nodec')) {
                statusText.textContent = '🧪 [테스트] WebRTC 수신 중 (디코더 OFF: ?nodec)';
            }
            return;
        }

        // 워치독: 비디오 패킷 수신 시각 갱신
        lastVideoPacketTime = performance.now();
        hasEverReceivedVideo = true;
        hasEverReceivedWebRtcVideo = true;
        watchdogKeyframeRequested = false;

        const bytes = new Uint8Array(payload);
        if (bytes.length < 5) return;

        // 비트레이트 실시간 측정
        dcVideoBytes += bytes.length;
        const now = performance.now();
        if (now - lastDcBitrateTime >= 1000) {
            const elapsed = (now - lastDcBitrateTime) / 1000;
            const mbps = (dcVideoBytes * 8) / (elapsed * 1000 * 1000);
            if (bitrateInfo) bitrateInfo.textContent = `${mbps.toFixed(1)} Mbps`;
            dcVideoBytes = 0;
            lastDcBitrateTime = now;
        }

        // 캔버스 가시화 & 미러링 뷰 전환 (가설 C 검증 스위치 ?noview)
        if (!location.search.includes('noview')) {
            if (canvas && canvas.style.display !== 'block') {
                canvas.style.display = 'block';
                canvas.className = 'fit-' + (window.screenFitMode || 'left');
            }
        }

        // NAL 유닛 타입 다중 스캔 (AUD/SEI 등 접두 NAL이 있어도 IDR(5) 또는 SPS(7) 검출)
        let isKeyFrame = false;
        const scanLimit = Math.min(bytes.length - 4, 256);
        for (let i = 0; i < scanLimit; i++) {
            if (bytes[i] === 0 && bytes[i + 1] === 0) {
                let nType = -1;
                if (bytes[i + 2] === 1) {
                    nType = bytes[i + 3] & 0x1F;
                } else if (bytes[i + 2] === 0 && i + 3 < scanLimit && bytes[i + 3] === 1) {
                    nType = bytes[i + 4] & 0x1F;
                }
                if (nType === 5 || nType === 7) {
                    isKeyFrame = true;
                    break;
                }
            }
        }

        // A/V 싱크 비디오 지연 보정: 웹 브라우저 사운드 스트리밍 중이고 지연시간이 지정되어 있을 때
        if (videoDelayMs > 0 && isAudioStreamingActive) {
            videoDelayQueue.push({
                bytes: bytes,
                isKeyFrame: isKeyFrame,
                arrivalTime: performance.now()
            });
            drainVideoDelayQueue();
        } else {
            if (videoDelayQueue.length > 0) {
                flushVideoDelayQueue();
            }
            feedVideoDecoder(bytes, isKeyFrame);
        }
    }

    let lastConfigWidth = 0;
    let lastConfigHeight = 0;

    function handleConfigPacket(payload) {
        try {
            const dec = new TextDecoder();
            const config = JSON.parse(dec.decode(payload));
            console.log('서버 설정 수신:', config);
            if (config.width && config.height) {
                resolutionInfo.textContent = `${config.width}x${config.height}`;

                // 폴드 열림/닫힘 및 가로/세로 회전 시 비디오 디코더 안전하게 재초기화
                if (lastConfigWidth !== 0 && (lastConfigWidth !== config.width || lastConfigHeight !== config.height)) {
                    console.log(`🔄 해상도 변경 감지 (${lastConfigWidth}x${lastConfigHeight} -> ${config.width}x${config.height}): VideoDecoder 재설정`);
                    videoConfigured = false;
                    hasReceivedFirstKeyFrame = false;
                    initVideoDecoder();
                }
                lastConfigWidth = config.width;
                lastConfigHeight = config.height;
            }
        } catch (e) {
            console.warn('설정 패킷 파싱 오류:', e);
        }
    }

    // --- CarPlay 스타일 App Palette Dock 제어 ---
    let currentDisplayMode = 'standalone'; // 테슬라 가상 디스플레이 표준
    const btnDisplayMode = document.getElementById('btnDisplayMode');
    if (btnDisplayMode) {
        btnDisplayMode.style.display = 'none'; // 미러링 단일 모드로 단순화
    }
    const appDock = document.getElementById('appDock');
    const btnToggleDock = document.getElementById('btnToggleDock');
    const dockToggleBtn = document.getElementById('dockToggleBtn');

    // 스마트폰 화면 초절전(밝기 0% 암전) 토글 제어
    let isPhoneScreenOn = true;
    const btnScreenPower = document.getElementById('btnScreenPower');
    const dockBtnScreenPower = document.getElementById('dockBtnScreenPower');
    const dockScreenPowerEmoji = document.getElementById('dockScreenPowerEmoji');
    const dockScreenPowerText = document.getElementById('dockScreenPowerText');

    function updateScreenPowerUI() {
        if (btnScreenPower) {
            if (isPhoneScreenOn) {
                btnScreenPower.textContent = '💡 폰 화면 절전';
                btnScreenPower.title = '스마트폰 화면 최저 밝기 암전 (초절전/발열 방지)';
                btnScreenPower.style.borderColor = '#888';
                btnScreenPower.style.color = '#ccc';
            } else {
                btnScreenPower.textContent = '💡 폰 밝기 복원';
                btnScreenPower.title = '스마트폰 화면 밝기 복원';
                btnScreenPower.style.borderColor = '#f39c12';
                btnScreenPower.style.color = '#f39c12';
            }
        }
        if (dockBtnScreenPower) {
            if (isPhoneScreenOn) {
                if (dockScreenPowerEmoji) dockScreenPowerEmoji.textContent = '🌙';
                if (dockScreenPowerText) dockScreenPowerText.textContent = '절전';
                dockBtnScreenPower.title = '스마트폰 화면 최저 밝기 암전 (초절전/발열 방지)';
                dockBtnScreenPower.classList.remove('dimmed');
            } else {
                if (dockScreenPowerEmoji) dockScreenPowerEmoji.textContent = '☀️';
                if (dockScreenPowerText) dockScreenPowerText.textContent = '복원';
                dockBtnScreenPower.title = '스마트폰 화면 밝기 복원';
                dockBtnScreenPower.classList.add('dimmed');
            }
        }
    }

    function toggleScreenPower(e) {
        if (e) {
            e.stopPropagation();
            if (e.cancelable) e.preventDefault();
        }
        isPhoneScreenOn = !isPhoneScreenOn;
        updateScreenPowerUI();
        console.log('💡 스마트폰 화면 절전 변경:', isPhoneScreenOn ? '복원' : '절전(암전)');
        window.mMirror.sendControl({
            type: 'set_screen_power',
            on: isPhoneScreenOn
        });
        showTeslaToast(isPhoneScreenOn ? '☀️ 스마트폰 화면 밝기 복원' : '🌙 스마트폰 화면 초절전(암전) 적용');
    }

    if (btnScreenPower) btnScreenPower.addEventListener('click', toggleScreenPower);
    if (dockBtnScreenPower) {
        let lastPowerTime = 0;
        const triggerPower = (e) => {
            e.stopPropagation();
            if (e.cancelable) e.preventDefault();
            const now = Date.now();
            if (now - lastPowerTime < 350) return;
            lastPowerTime = now;
            toggleScreenPower(e);
            dockBtnScreenPower.style.transform = 'scale(0.9)';
            setTimeout(() => { dockBtnScreenPower.style.transform = ''; }, 150);
        };
        dockBtnScreenPower.addEventListener('touchend', triggerPower, { passive: false });
        dockBtnScreenPower.addEventListener('click', triggerPower);
    }

    function toggleAppDock() {
        if (!appDock) return;
        appDock.classList.toggle('collapsed');
    }

    if (btnToggleDock) btnToggleDock.addEventListener('click', toggleAppDock);
    if (dockToggleBtn) dockToggleBtn.addEventListener('click', toggleAppDock);

    // ==========================================================================
    // 독립 가상 디스플레이 앱 팔레트 커스텀 관리 & 원격 내비게이션 키
    // ==========================================================================
    // ==========================================================================
    // 테슬라 맞춤형 독립 가상 디스플레이 앱 팔레트 (CarPlay Dock) 엔진
    // ==========================================================================
    const DEFAULT_DOCK_APPS = [
        { name: '티맵', package: 'com.skt.tmap.ku', emoji: '🚗' },
        { name: '카카오내비', package: 'com.locnall.KimGiSa', emoji: '🧭' },
        { name: '네이버지도', package: 'com.nhn.android.nmap', emoji: '🗺️' },
        { name: '유튜브', package: 'com.google.android.youtube', emoji: '▶️' },
        { name: 'YT뮤직', package: 'com.google.android.apps.youtube.music', emoji: '🎵' },
        { name: '크롬', package: 'com.android.chrome', emoji: '🌐' }
    ];

    let dockApps = [];
    let installedPhoneApps = [];
    let appIconCache = {};
    let currentTouchStatus = null;

    function loadAppIconCache() {
        try {
            const savedIcons = localStorage.getItem('mmirror_app_icons_v1');
            if (savedIcons) {
                const parsed = JSON.parse(savedIcons);
                if (parsed && typeof parsed === 'object') {
                    appIconCache = parsed;
                    console.log(`🖼️ [CACHE] Loaded ${Object.keys(appIconCache).length} cached app icons from localStorage`);
                }
            }
            const savedApps = localStorage.getItem('mmirror_installed_apps_v1');
            if (savedApps) {
                const parsedApps = JSON.parse(savedApps);
                if (Array.isArray(parsedApps) && parsedApps.length > 0) {
                    installedPhoneApps = parsedApps;
                    installedPhoneApps.forEach(app => {
                        if (appIconCache[app.package]) {
                            app.icon = appIconCache[app.package];
                        }
                    });
                    console.log(`📱 [CACHE] Loaded ${installedPhoneApps.length} installed apps from localStorage`);
                }
            }
        } catch (e) {
            console.warn('Failed to load app icon cache from localStorage:', e);
        }
    }

    function saveAppIconCache() {
        try {
            if (appIconCache && Object.keys(appIconCache).length > 0) {
                localStorage.setItem('mmirror_app_icons_v1', JSON.stringify(appIconCache));
            }
            if (installedPhoneApps && installedPhoneApps.length > 0) {
                localStorage.setItem('mmirror_installed_apps_v1', JSON.stringify(installedPhoneApps));
            }
        } catch (e) {
            console.warn('Failed to save app icon cache to localStorage:', e);
        }
    }

    function loadDockApps() {
        try {
            const saved = localStorage.getItem('mmirror_dock_apps');
            if (saved) {
                const parsed = JSON.parse(saved);
                if (Array.isArray(parsed) && parsed.length > 0) {
                    // 미동작 주행이력 앱은 독바 목록에서 필터링하여 제거
                    dockApps = parsed.filter(a => a.package !== 'builtin:triplog');
                    dockApps.forEach(d => {
                        if (appIconCache[d.package]) {
                            d.icon = appIconCache[d.package];
                        }
                    });
                    saveDockApps();
                    return;
                }
            }
        } catch (_) {}
        dockApps = [...DEFAULT_DOCK_APPS];
        dockApps.forEach(d => {
            if (appIconCache[d.package]) {
                d.icon = appIconCache[d.package];
            }
        });
        saveDockApps();
    }

    function saveDockApps() {
        try {
            localStorage.setItem('mmirror_dock_apps', JSON.stringify(dockApps));
        } catch (_) {}
    }

    function guessEmoji(name) {
        if (!name) return '📱';
        const n = name.toLowerCase();
        if (n.includes('주행') || n.includes('일지') || n.includes('이력') || n.includes('기록') || n.includes('trip') || n.includes('log')) return '📊';
        if (n.includes('티맵') || n.includes('내비') || n.includes('지도') || n.includes('navi') || n.includes('map')) return '🚗';
        if (n.includes('유튜브') || n.includes('youtube') || n.includes('video') || n.includes('영상')) return '▶️';
        if (n.includes('뮤직') || n.includes('music') || n.includes('멜론') || n.includes('음악') || n.includes('벅스') || n.includes('지니') || n.includes('spotify') || n.includes('스포티파이')) return '🎵';
        if (n.includes('넷플릭스') || n.includes('netflix') || n.includes('영화') || n.includes('티빙') || n.includes('웨이브') || n.includes('디즈니') || n.includes('쿠팡플레이')) return '🍿';
        if (n.includes('크롬') || n.includes('chrome') || n.includes('브라우저') || n.includes('인터넷')) return '🌐';
        if (n.includes('카카오톡') || n.includes('메시지') || n.includes('talk') || n.includes('chat')) return '💬';
        if (n.includes('카메라') || n.includes('camera') || n.includes('사진') || n.includes('photo')) return '📷';
        if (n.includes('게임') || n.includes('game')) return '🎮';
        return '📱';
    }

    function renderDock() {
        const container = document.getElementById('dockAppsContainer');
        if (!container) return;
        container.innerHTML = '';

        dockApps.forEach(app => {
            const btn = document.createElement('button');
            btn.className = 'dock-item-btn';
            btn.setAttribute('data-pkg', app.package);
            btn.title = (app.package === 'builtin:triplog' || app.isBuiltin)
                ? `${app.name} (차량 주행일지 & GPS 지도)`
                : `${app.name} 가상화면 단독 실행`;

            // 실제 앱 아이콘 또는 캐시에서 가져오기
            const iconData = app.icon || appIconCache[app.package];
            let iconEl;
            if (iconData) {
                iconEl = document.createElement('img');
                iconEl.className = 'dock-icon';
                iconEl.src = 'data:image/png;base64,' + iconData;
                iconEl.alt = app.name;
                iconEl.width = 36;
                iconEl.height = 36;
                iconEl.draggable = false;
            } else {
                iconEl = document.createElement('span');
                iconEl.className = 'dock-emoji';
                iconEl.textContent = app.emoji || guessEmoji(app.name);
            }

            const textSpan = document.createElement('span');
            textSpan.className = 'dock-text';
            textSpan.textContent = app.name;

            btn.appendChild(iconEl);
            btn.appendChild(textSpan);

            let dockTouchMoved = false;
            btn.addEventListener('touchstart', () => { dockTouchMoved = false; }, { passive: true });
            btn.addEventListener('touchmove', () => { dockTouchMoved = true; }, { passive: true });
            btn.addEventListener('touchend', (e) => {
                if (!dockTouchMoved) {
                    e.preventDefault();
                    e.stopPropagation();
                    launchDockApp(app);
                    btn.style.transform = 'scale(0.9)';
                    setTimeout(() => { btn.style.transform = ''; }, 150);
                }
            }, { passive: false });

            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                launchDockApp(app);
                btn.style.transform = 'scale(0.9)';
                setTimeout(() => { btn.style.transform = ''; }, 150);
            });

            container.appendChild(btn);
        });

        updateAppInstallState();
        if (typeof renderDashAppsGrid === 'function') {
            renderDashAppsGrid();
        }
    }

    function launchDockApp(app) {
        // 내장 주행이력 앱 실행
        if (app.package === 'builtin:triplog' || app.isBuiltin) {
            console.log('🚗 도크에서 내장 주행이력 앱 실행');
            if (window.openTripLogModal) {
                window.openTripLogModal();
            } else {
                const modal = document.getElementById('tripLogModal');
                if (modal) {
                    modal.classList.remove('hidden');
                    modal.style.setProperty('display', 'flex', 'important');
                }
            }
            return;
        }

        console.log(`🚀 앱 런칭 요청: ${app.name} (${app.package})`);
        showTeslaToast(`🚀 ${app.name} 실행 중...`);
        window.mMirror.sendControl({
            type: 'launch_app',
            package: app.package
        });
        // 앱 실행 시 스마트폰 화면 1:1 미러링 뷰로 부드럽게 자동 전환
        if (typeof showMirrorView === 'function') {
            showMirrorView();
        }
    }

    function updateAppInstallState() {
        if (!installedPhoneApps || installedPhoneApps.length === 0) return;
        const installedPkgMap = new Map();
        installedPhoneApps.forEach(a => installedPkgMap.set(a.package, a.installed !== false));

        document.querySelectorAll('.dock-item-btn[data-pkg]').forEach(btn => {
            const pkg = btn.getAttribute('data-pkg');
            if (pkg && (pkg.startsWith('builtin:') || pkg === 'builtin:triplog')) {
                btn.style.opacity = '1.0';
                return;
            }
            if (installedPkgMap.has(pkg)) {
                const isInst = installedPkgMap.get(pkg);
                btn.style.opacity = isInst ? '1.0' : '0.35';
                if (!isInst) {
                    btn.title = `${btn.querySelector('.dock-text')?.textContent || pkg} (스마트폰에 앱 미설치)`;
                }
            }
        });
    }

    function updateModeBadge(mode) {
        const badge = document.getElementById('modeBadge');
        if (badge) {
            badge.style.display = 'none';
        }
    }

    function updateTouchControlUI(status) {
        currentTouchStatus = status;
    }

    function handleAppList(apps, touchEnabled, vdId) {
        installedPhoneApps = apps;
        // TMAP 패키지 자동 매칭 (원스토어 com.skt.skaf.l001mtm091 vs 플레이스토어 com.skt.tmap.ku)
        const tmapApp = apps.find(a => a.package === 'com.skt.tmap.ku' || a.package === 'com.skt.skaf.l001mtm091' || a.package.toLowerCase().includes('tmap'));
        if (tmapApp) {
            dockApps.forEach(d => {
                if (d.name === '티맵' || d.package.includes('tmap') || d.package === 'com.skt.skaf.l001mtm091') {
                    d.package = tmapApp.package;
                }
            });
            saveDockApps();
        }
        // Restore any cached icons to newly received apps
        installedPhoneApps.forEach(app => {
            if (appIconCache[app.package]) {
                app.icon = appIconCache[app.package];
            }
        });
        updateAppInstallState();
        saveAppIconCache();
        renderDock();
        if (appManagerModal && !appManagerModal.classList.contains('hidden')) {
            renderAvailableApps();
        }
    }

    let iconSaveTimer = null;
    let renderDockTimer = null;

    function handleAppIcons(icons) {
        if (!icons) return;
        let updated = false;
        // Cache icons
        for (const [pkg, icon] of Object.entries(icons)) {
            if (icon && appIconCache[pkg] !== icon) {
                appIconCache[pkg] = icon;
                updated = true;
            }
        }
        if (!updated) return;

        // Update installedPhoneApps with icons
        installedPhoneApps.forEach(app => {
            if (icons[app.package]) {
                app.icon = icons[app.package];
            }
        });
        // Update dock apps that have matching packages
        dockApps.forEach(app => {
            if (icons[app.package]) {
                app.icon = icons[app.package];
            }
        });

        // 지연 디바운스로 DOM 재구성 및 로컬 스토리지 부하 방지
        clearTimeout(iconSaveTimer);
        iconSaveTimer = setTimeout(() => {
            saveAppIconCache();
        }, 300);

        clearTimeout(renderDockTimer);
        renderDockTimer = setTimeout(() => {
            renderDock();
            if (appManagerModal && !appManagerModal.classList.contains('hidden')) {
                renderAppManager();
            }
        }, 100);
    }

    // --- 앱 관리 모달 (App Manager Modal) ---
    const appManagerModal = document.getElementById('appManagerModal');
    const btnOpenAppManager = document.getElementById('btnOpenAppManager');
    const btnCloseAppManager = document.getElementById('btnCloseAppManager');
    const btnDoneAppManager = document.getElementById('btnDoneAppManager');
    const btnResetDefaultApps = document.getElementById('btnResetDefaultApps');
    const appSearchInput = document.getElementById('appSearchInput');
    const managerCurrentApps = document.getElementById('managerCurrentApps');
    const managerAvailableApps = document.getElementById('managerAvailableApps');
    const dockAppCount = document.getElementById('dockAppCount');
    const btnAddCustomApp = document.getElementById('btnAddCustomApp');
    const customAppName = document.getElementById('customAppName');
    const customAppPkg = document.getElementById('customAppPkg');
    const customAppEmoji = document.getElementById('customAppEmoji');

    function openAppManager() {
        if (!appManagerModal) return;
        renderAppManager();
        appManagerModal.classList.remove('hidden');
        appManagerModal.style.setProperty('display', 'flex', 'important');
        window.mMirror.sendControl({ type: 'get_apps' });
    }

    function closeAppManager() {
        if (appManagerModal) {
            appManagerModal.classList.add('hidden');
            appManagerModal.style.setProperty('display', 'none', 'important');
        }
    }

    function renderAppManager() {
        if (dockAppCount) dockAppCount.textContent = dockApps.length;

        // 1. 현재 팔레트에 등록된 앱
        if (managerCurrentApps) {
            managerCurrentApps.innerHTML = '';
            if (dockApps.length === 0) {
                managerCurrentApps.innerHTML = '<span style="color:#8e9aa8; font-size:12px; padding:6px;">등록된 앱이 없습니다.</span>';
            } else {
                dockApps.forEach((app, idx) => {
                    const item = document.createElement('div');
                    item.className = 'manager-app-item';
                    const iconData = app.icon || appIconCache[app.package];
                    const iconHtml = iconData
                        ? `<img class="dock-icon" src="data:image/png;base64,${iconData}" width="28" height="28" draggable="false">`
                        : `<span class="app-emoji">${app.emoji || guessEmoji(app.name)}</span>`;
                    item.innerHTML = `
                        ${iconHtml}
                        <span class="app-name">${app.name}</span>
                        <button class="btn-remove-app" title="팔레트에서 제거">✕ 삭제</button>
                    `;
                    const removeBtn = item.querySelector('.btn-remove-app');
                    const handleRemove = (e) => {
                        e.stopPropagation();
                        e.preventDefault();
                        removeAppFromDock(idx);
                    };
                    removeBtn.addEventListener('click', handleRemove);
                    removeBtn.addEventListener('touchend', handleRemove);
                    managerCurrentApps.appendChild(item);
                });
            }
        }

        // 2. 스마트폰 설치 앱 중 미등록 앱 목록
        renderAvailableApps();
    }

    function renderAvailableApps() {
        if (!managerAvailableApps) return;
        managerAvailableApps.innerHTML = '';

        const registeredPkgSet = new Set(dockApps.map(a => a.package));
        const query = (appSearchInput?.value || '').trim().toLowerCase();

        const available = installedPhoneApps.filter(app => {
            if (registeredPkgSet.has(app.package)) return false;
            if (!query) return true;
            return (app.name && app.name.toLowerCase().includes(query)) ||
                   (app.package && app.package.toLowerCase().includes(query));
        });

        if (available.length === 0) {
            managerAvailableApps.innerHTML = '<span style="color:#8e9aa8; font-size:12px; padding:6px;">추가 가능한 앱이 없거나 검색 결과가 없습니다.</span>';
            return;
        }

        available.forEach(app => {
            const item = document.createElement('div');
            item.className = 'manager-app-item';
            const iconData = app.icon || appIconCache[app.package];
            const iconHtml = iconData
                ? `<img class="dock-icon" src="data:image/png;base64,${iconData}" width="28" height="28" draggable="false">`
                : `<span class="app-emoji">${guessEmoji(app.name)}</span>`;
            item.innerHTML = `
                ${iconHtml}
                <span class="app-name">${app.name}</span>
                <button class="btn-add-app" title="팔레트에 추가">➕ 추가</button>
            `;
            const addBtn = item.querySelector('.btn-add-app');
            const handleAdd = (e) => {
                e.stopPropagation();
                e.preventDefault();
                addAppToDock({
                    name: app.name,
                    package: app.package,
                    icon: app.icon || appIconCache[app.package] || '',
                    emoji: guessEmoji(app.name)
                });
            };
            addBtn.addEventListener('click', handleAdd);
            addBtn.addEventListener('touchend', handleAdd);
            managerAvailableApps.appendChild(item);
        });
    }

    function addAppToDock(app) {
        if (dockApps.some(a => a.package === app.package)) {
            showTeslaToast('이미 팔레트에 등록된 앱입니다.');
            return;
        }
        dockApps.push(app);
        saveDockApps();
        renderDock();
        renderAppManager();
        showTeslaToast(`✓ [${app.name}] 팔레트에 추가됨`);
    }

    function removeAppFromDock(index) {
        if (index >= 0 && index < dockApps.length) {
            const removed = dockApps.splice(index, 1)[0];
            saveDockApps();
            renderDock();
            renderAppManager();
            showTeslaToast(`✕ [${removed.name}] 팔레트에서 삭제됨`);
        }
    }

    // 모달 배경 및 닫기 버튼 이벤트 바인딩 (터치스크린 즉시 반응)
    if (appManagerModal) {
        const handleBackdrop = (e) => {
            if (e.target === appManagerModal) {
                e.stopPropagation();
                e.preventDefault();
                closeAppManager();
            }
        };
        appManagerModal.addEventListener('click', handleBackdrop);
        appManagerModal.addEventListener('touchend', handleBackdrop);

        const card = appManagerModal.querySelector('.app-manager-card');
        if (card) {
            ['click', 'touchstart', 'touchend', 'mousedown', 'mouseup'].forEach(evt => {
                card.addEventListener(evt, (e) => {
                    e.stopPropagation();
                });
            });
        }
    }

    if (appSearchInput) {
        appSearchInput.addEventListener('input', () => {
            renderAvailableApps();
        });
        ['touchstart', 'touchend', 'click', 'keydown', 'keyup'].forEach(evt => {
            appSearchInput.addEventListener(evt, (e) => {
                e.stopPropagation();
            });
        });
    }

    const bindModalBtn = (btn, action) => {
        if (!btn) return;
        const fn = (e) => {
            e.stopPropagation();
            e.preventDefault();
            action(e);
        };
        btn.addEventListener('click', fn);
        btn.addEventListener('touchend', fn);
    };

    bindModalBtn(btnOpenAppManager, openAppManager);
    bindModalBtn(btnCloseAppManager, closeAppManager);
    bindModalBtn(btnDoneAppManager, closeAppManager);

    if (btnResetDefaultApps) {
        const handleReset = (e) => {
            e.stopPropagation();
            e.preventDefault();
            if (confirm('팔레트 앱 목록을 초기 기본값으로 되돌리시겠습니까?')) {
                dockApps = [...DEFAULT_DOCK_APPS];
                saveDockApps();
                renderDock();
                renderAppManager();
                showTeslaToast('✓ 기본 앱 목록으로 복원되었습니다.');
            }
        };
        btnResetDefaultApps.addEventListener('click', handleReset);
        btnResetDefaultApps.addEventListener('touchend', handleReset);
    }

    if (btnAddCustomApp) {
        const handleAddCustom = (e) => {
            e.stopPropagation();
            e.preventDefault();
            const name = (customAppName?.value || '').trim();
            const pkg = (customAppPkg?.value || '').trim();
            const emoji = (customAppEmoji?.value || '').trim() || guessEmoji(name);

            if (!name || !pkg) {
                showTeslaToast('⚠️ 앱 이름과 패키지명을 모두 입력해 주세요.');
                return;
            }

            addAppToDock({
                name,
                package: pkg,
                emoji,
                icon: ''
            });

            if (customAppName) customAppName.value = '';
            if (customAppPkg) customAppPkg.value = '';
            if (customAppEmoji) customAppEmoji.value = '';
        };
        btnAddCustomApp.addEventListener('click', handleAddCustom);
        btnAddCustomApp.addEventListener('touchend', handleAddCustom);
    }

    // ==========================================================================
    // 테슬라 전용 홈 대시보드 엔진 (시계, 실시간 GPS 날씨, 주행 통계, 앱 런처)
    // ==========================================================================
    window.currentViewMode = 'dashboard'; // 'dashboard' | 'mirror'

    function showDashboardView() {
        window.currentViewMode = 'dashboard';
        const dash = document.getElementById('homeDashboard');
        if (dash) dash.classList.remove('hidden');
        const sidePanel = document.getElementById('mirrorSidePanel');
        if (sidePanel) sidePanel.style.display = 'none';
        const restoreBtn = document.getElementById('btnSidePanelRestore');
        if (restoreBtn) restoreBtn.style.display = 'none';
        const zoomControls = document.getElementById('floatingZoomControls');
        if (zoomControls) zoomControls.style.display = 'none';
        updateModeBadge('dashboard');
        document.getElementById('btnDockHome')?.classList.add('active');
        document.getElementById('btnDockMirror')?.classList.remove('active');
    }

    function showMirrorView() {
        if (location.search.includes('nomv')) {
            console.log('🧪 [TEST] ?nomv: 미러 뷰 화면 전환 차단 (대시보드 유지)');
            return;
        }
        window.currentViewMode = 'mirror';
        const dash = document.getElementById('homeDashboard');
        if (dash) dash.classList.add('hidden');
        updateModeBadge('mirror');
        document.getElementById('btnDockHome')?.classList.remove('active');
        document.getElementById('btnDockMirror')?.classList.add('active');
        updateCanvasDisplayLayout(true);
    }

    // 1. 대형 디지털 시계 & 캘린더
    function updateDashClock() {
        const now = new Date();
        const hours24 = now.getHours();
        const hours12 = (hours24 % 12) || 12;
        const ampm = hours24 >= 12 ? 'PM' : 'AM';
        const minutes = String(now.getMinutes()).padStart(2, '0');
        const seconds = String(now.getSeconds()).padStart(2, '0');

        const elTime = document.getElementById('dashClockTime');
        const elSec = document.getElementById('dashClockSec');
        const elAmPm = document.getElementById('dashClockAmPm');
        const elDate = document.getElementById('dashClockDate');

        const timeStr = `${String(hours12).padStart(2, '0')}:${minutes}`;
        if (elTime) elTime.textContent = timeStr;
        if (elSec) elSec.textContent = seconds;
        if (elAmPm) elAmPm.textContent = ampm;

        const days = ['일요일', '월요일', '화요일', '수요일', '목요일', '금요일', '토요일'];
        const year = now.getFullYear();
        const month = now.getMonth() + 1;
        const date = now.getDate();
        const dayName = days[now.getDay()];
        const dateStr = `${year}년 ${month}월 ${date}일 ${dayName}`;
        if (elDate) elDate.textContent = dateStr;

        // 미러링 우측 빈 공간 시계 위젯 동기화
        const sideTime = document.getElementById('sideClockTime');
        const sideSec = document.getElementById('sideClockSec');
        const sideAmPm = document.getElementById('sideClockAmPm');
        const sideDate = document.getElementById('sideClockDate');

        if (sideTime) sideTime.textContent = timeStr;
        if (sideSec) sideSec.textContent = seconds;
        if (sideAmPm) sideAmPm.textContent = ampm;
        if (sideDate) sideDate.textContent = dateStr;
    }

    // 2. 실시간 GPS 기반 날씨 엔진 (Open-Meteo & Nominatim)
    let lastWeatherFetchTime = 0;
    let lastWeatherLat = 0;
    let lastWeatherLng = 0;

    function getWeatherDescription(code, isDay = 1) {
        switch (code) {
            case 0: return { icon: isDay ? '☀️' : '🌙', desc: '맑음' };
            case 1: return { icon: isDay ? '🌤️' : '🌤️', desc: '대체로 맑음' };
            case 2: return { icon: '⛅', desc: '구름 조금' };
            case 3: return { icon: '☁️', desc: '흐림' };
            case 45: case 48: return { icon: '🌫️', desc: '안개' };
            case 51: case 53: case 55: return { icon: '🌦️', desc: '이슬비' };
            case 56: case 57: return { icon: '🌧️', desc: '빙우' };
            case 61: case 63: return { icon: '🌧️', desc: '비' };
            case 65: return { icon: '🌧️', desc: '강한 비' };
            case 66: case 67: return { icon: '🌨️', desc: '진눈깨비' };
            case 71: case 73: return { icon: '🌨️', desc: '눈' };
            case 75: case 77: return { icon: '❄️', desc: '강한 눈' };
            case 80: case 81: case 82: return { icon: '🌦️', desc: '소나기' };
            case 85: case 86: return { icon: '🌨️', desc: '눈 소나기' };
            case 95: return { icon: '⛈️', desc: '뇌우' };
            case 96: case 99: return { icon: '⛈️', desc: '뇌우/우박' };
            default: return { icon: '🌤️', desc: '맑음/구름' };
        }
    }

    function getFallbackKoreanLocation(lat, lng) {
        // 화성시 동탄신도시 (동탄1/동탄2)
        if (lat >= 37.14 && lat <= 37.24 && lng >= 127.04 && lng <= 127.17) return '화성시 동탄';
        // 화성시 전역
        if (lat >= 37.05 && lat <= 37.33 && lng >= 126.65 && lng <= 127.20) return '화성시';
        // 오산시
        if (lat >= 37.12 && lat <= 37.19 && lng >= 127.02 && lng <= 127.10) return '오산시';
        // 평택시
        if (lat >= 36.92 && lat <= 37.12 && lng >= 126.85 && lng <= 127.18) return '평택시';
        // 수원시
        if (lat >= 37.24 && lat <= 37.33 && lng >= 126.96 && lng <= 127.08) return '수원시';
        // 용인시
        if (lat >= 37.15 && lat <= 37.38 && lng >= 127.05 && lng <= 127.35) return '용인시';
        // 성남시 분당구
        if (lat >= 37.33 && lat <= 37.43 && lng >= 127.08 && lng <= 127.18) return '성남시 분당구';
        // 서울특별시
        if (lat >= 37.42 && lat <= 37.70 && lng >= 126.76 && lng <= 127.18) return '서울특별시';
        // 인천광역시
        if (lat >= 37.40 && lat <= 37.60 && lng >= 126.60 && lng <= 126.78) return '인천광역시';
        // 고양시
        if (lat >= 37.62 && lat <= 37.72 && lng >= 126.73 && lng <= 126.93) return '고양시';
        // 안산시
        if (lat >= 37.25 && lat <= 37.36 && lng >= 126.75 && lng <= 126.90) return '안산시';
        // 안양시
        if (lat >= 37.36 && lat <= 37.43 && lng >= 126.89 && lng <= 126.98) return '안양시';
        // 부천시
        if (lat >= 37.47 && lat <= 37.54 && lng >= 126.75 && lng <= 126.83) return '부천시';
        // 부산광역시
        if (lat >= 35.05 && lat <= 35.35 && lng >= 128.95 && lng <= 129.25) return '부산광역시';
        // 대구광역시
        if (lat >= 35.75 && lat <= 35.95 && lng >= 128.50 && lng <= 128.70) return '대구광역시';
        // 대전광역시
        if (lat >= 36.25 && lat <= 36.45 && lng >= 127.30 && lng <= 127.45) return '대전광역시';
        // 광주광역시
        if (lat >= 35.10 && lat <= 35.25 && lng >= 126.75 && lng <= 126.95) return '광주광역시';
        // 울산광역시
        if (lat >= 35.45 && lat <= 35.65 && lng >= 129.20 && lng <= 129.45) return '울산광역시';
        // 제주특별자치도
        if (lat >= 33.20 && lat <= 33.55 && lng >= 126.15 && lng <= 126.95) return '제주특별자치도';
        // 강릉시
        if (lat >= 37.70 && lat <= 37.85 && lng >= 128.80 && lng <= 129.00) return '강릉시';
        // 춘천시
        if (lat >= 37.80 && lat <= 37.95 && lng >= 127.65 && lng <= 127.85) return '춘천시';
        // 청주시
        if (lat >= 36.55 && lat <= 36.70 && lng >= 127.40 && lng <= 127.55) return '청주시';
        // 전주시
        if (lat >= 35.75 && lat <= 35.90 && lng >= 127.05 && lng <= 127.20) return '전주시';
        return null;
    }

    async function fetchLocationName(lat, lng) {
        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 3500);
            const res = await fetch(`https://nominatim.openstreetmap.org/reverse?lat=${lat}&lon=${lng}&format=json&accept-language=ko`, { signal: controller.signal });
            clearTimeout(timeoutId);
            if (res.ok) {
                const data = await res.json();
                if (data && data.address) {
                    const city = data.address.city || data.address.town || data.address.county || data.address.province || '';
                    const district = data.address.suburb || data.address.quarter || data.address.borough || data.address.neighbourhood || data.address.village || '';
                    
                    let locStr = '';
                    if (district.includes('동탄') || (data.display_name && data.display_name.includes('동탄'))) {
                        locStr = (city.includes('화성') ? city : '화성시') + ' 동탄';
                    } else {
                        locStr = [city, district].filter(Boolean).join(' ');
                    }
                    if (locStr) return locStr;
                }
            }
        } catch (_) {}
        return getFallbackKoreanLocation(lat, lng) || '대한민국';
    }

    async function fetchWeather(lat, lng) {
        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 4000);
            const url = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lng}&current=temperature_2m,relative_humidity_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto`;
            const res = await fetch(url, { signal: controller.signal });
            clearTimeout(timeoutId);
            if (!res.ok) return;
            const data = await res.json();
            const current = data.current;
            const daily = data.daily;
            const wInfo = getWeatherDescription(current.weather_code, current.is_day);
            const temp = Math.round(current.temperature_2m);
            const minTemp = (daily && daily.temperature_2m_min) ? Math.round(daily.temperature_2m_min[0]) : '--';
            const maxTemp = (daily && daily.temperature_2m_max) ? Math.round(daily.temperature_2m_max[0]) : '--';

            const locName = await fetchLocationName(lat, lng);

            const elIcon = document.getElementById('dashWeatherIcon');
            const elTemp = document.getElementById('dashWeatherTemp');
            const elDesc = document.getElementById('dashWeatherDesc');
            const elLoc = document.getElementById('dashWeatherLocation');
            const elRange = document.getElementById('dashWeatherRange');

            if (elIcon) elIcon.textContent = wInfo.icon;
            if (elTemp) elTemp.textContent = `${temp}`;
            if (elDesc) elDesc.textContent = wInfo.desc;
            if (elLoc) elLoc.textContent = `📍 ${locName}`;
            if (elRange) elRange.textContent = `최저 ${minTemp}° / 최고 ${maxTemp}°`;

            // 미러링 우측 빈 공간 날씨 위젯 동기화
            const sideIcon = document.getElementById('sideWeatherIcon');
            const sideTemp = document.getElementById('sideWeatherTemp');
            const sideDesc = document.getElementById('sideWeatherDesc');
            const sideLoc = document.getElementById('sideWeatherLocation');
            const sideRange = document.getElementById('sideWeatherRange');

            if (sideIcon) sideIcon.textContent = wInfo.icon;
            if (sideTemp) sideTemp.textContent = `${temp}`;
            if (sideDesc) sideDesc.textContent = wInfo.desc;
            if (sideLoc) sideLoc.textContent = `📍 ${locName}`;
            if (sideRange) sideRange.textContent = `최저 ${minTemp}° / 최고 ${maxTemp}°`;

            lastWeatherFetchTime = Date.now();
            lastWeatherLat = lat;
            lastWeatherLng = lng;

            localStorage.setItem('mmirror_weather_cache', JSON.stringify({
                temp, minTemp, maxTemp, icon: wInfo.icon, desc: wInfo.desc, locName,
                lat, lng, timestamp: Date.now()
            }));
        } catch (e) {
            console.warn('Weather fetch error:', e);
        }
    }

    function initWeatherFromCache() {
        try {
            const raw = localStorage.getItem('mmirror_weather_cache');
            if (raw) {
                const c = JSON.parse(raw);
                if (c && c.temp !== undefined && c.locName && !c.locName.includes('서울')) {
                    const elIcon = document.getElementById('dashWeatherIcon');
                    const elTemp = document.getElementById('dashWeatherTemp');
                    const elDesc = document.getElementById('dashWeatherDesc');
                    const elLoc = document.getElementById('dashWeatherLocation');
                    const elRange = document.getElementById('dashWeatherRange');

                    if (elIcon && c.icon) elIcon.textContent = c.icon;
                    if (elTemp && c.temp !== undefined) elTemp.textContent = `${c.temp}`;
                    if (elDesc && c.desc) elDesc.textContent = c.desc;
                    if (elLoc && c.locName) elLoc.textContent = `📍 ${c.locName}`;
                    if (elRange && c.minTemp !== undefined) elRange.textContent = `최저 ${c.minTemp}° / 최고 ${c.maxTemp}°`;

                    const sideIcon = document.getElementById('sideWeatherIcon');
                    const sideTemp = document.getElementById('sideWeatherTemp');
                    const sideDesc = document.getElementById('sideWeatherDesc');
                    const sideLoc = document.getElementById('sideWeatherLocation');
                    const sideRange = document.getElementById('sideWeatherRange');

                    if (sideIcon && c.icon) sideIcon.textContent = c.icon;
                    if (sideTemp && c.temp !== undefined) sideTemp.textContent = `${c.temp}`;
                    if (sideDesc && c.desc) sideDesc.textContent = c.desc;
                    if (sideLoc && c.locName) sideLoc.textContent = `📍 ${c.locName}`;
                    if (sideRange && c.minTemp !== undefined) sideRange.textContent = `최저 ${c.minTemp}° / 최고 ${c.maxTemp}°`;

                    if (c.lat && c.lng) {
                        lastWeatherLat = c.lat;
                        lastWeatherLng = c.lng;
                    }
                    if (c.timestamp) {
                        lastWeatherFetchTime = c.timestamp;
                    }
                }
            }
        } catch (_) {}

        // 브라우저 지오로케이션(HTML5 Geolocation) 지원 시 우선 조회
        if (navigator.geolocation && (!lastWeatherLat || lastWeatherLat === 37.5665)) {
            try {
                navigator.geolocation.getCurrentPosition(
                    pos => {
                        console.log('📍 브라우저 GPS 위치 감지:', pos.coords.latitude, pos.coords.longitude);
                        fetchWeather(pos.coords.latitude, pos.coords.longitude);
                    },
                    _ => {},
                    { timeout: 3000, enableHighAccuracy: true, maximumAge: 60000 }
                );
            } catch (_) {}
        }

        // 최초 기동 시 캐시/GPS가 아직 없더라도 서울 기준 기본 날씨 자동 로드
        if (!lastWeatherFetchTime) {
            setTimeout(() => {
                if (!lastWeatherFetchTime) {
                    fetchWeather(37.5665, 126.9780);
                }
            }, 3000);
        }
    }

    // GPS 패킷 수신 시 호출되는 대시보드 갱신 함수
    function updateDashboardGps(gpsData) {
        if (!gpsData) return;

        // 1. 오늘의 주행 통계 HUD
        const elDist = document.getElementById('dashTripDist');
        if (elDist && typeof gpsData.trip_distance_meters === 'number') {
            elDist.textContent = (gpsData.trip_distance_meters / 1000).toFixed(1);
        }

        const elTime = document.getElementById('dashTripTime');
        if (elTime && typeof gpsData.duration_seconds === 'number') {
            const totalSec = gpsData.duration_seconds;
            const m = Math.floor(totalSec / 60);
            const s = totalSec % 60;
            elTime.textContent = `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
        }

        const elSpeed = document.getElementById('dashTripSpeed');
        if (elSpeed && typeof gpsData.speed_kmh === 'number') {
            elSpeed.textContent = Math.round(gpsData.speed_kmh);
        }

        // 2. 실시간 GPS 기반 날씨 갱신 (실시간 좌표 수신 시 동탄 등 실제 위치로 즉시 반영)
        if (gpsData.lat && gpsData.lng && gpsData.lat > 0 && gpsData.lng > 0) {
            const now = Date.now();
            const distMoved = Math.hypot(gpsData.lat - lastWeatherLat, gpsData.lng - lastWeatherLng);
            const isFromDefaultSeoul = (Math.abs(lastWeatherLat - 37.5665) < 0.01 && Math.abs(gpsData.lat - 37.5665) > 0.03);
            if (isFromDefaultSeoul || now - lastWeatherFetchTime > 20 * 60 * 1000 || distMoved > 0.02) {
                console.log('📍 GPS 수신에 따른 날씨 위치 즉시 갱신:', gpsData.lat, gpsData.lng);
                fetchWeather(gpsData.lat, gpsData.lng);
            }
        }
    }

    // 3. 대시보드 앱 그리드 동적 렌더링
    function renderDashAppsGrid() {
        const grid = document.getElementById('dashAppsGrid');
        if (!grid) return;
        grid.innerHTML = '';

        dockApps.forEach(app => {
            const tile = document.createElement('div');
            tile.className = 'dash-app-tile';
            tile.setAttribute('data-pkg', app.package);

            const iconData = app.icon || appIconCache[app.package];
            let iconHtml;
            if (iconData) {
                iconHtml = `<img src="data:image/png;base64,${iconData}" class="tile-icon" alt="${app.name}" draggable="false">`;
            } else {
                iconHtml = `<span class="tile-emoji">${app.emoji || guessEmoji(app.name)}</span>`;
            }

            tile.innerHTML = `
                ${iconHtml}
                <span class="tile-name">${app.name}</span>
            `;

            let tileTouchMoved = false;
            tile.addEventListener('touchstart', () => { tileTouchMoved = false; }, { passive: true });
            tile.addEventListener('touchmove', () => { tileTouchMoved = true; }, { passive: true });
            tile.addEventListener('touchend', (e) => {
                if (!tileTouchMoved) {
                    e.preventDefault();
                    e.stopPropagation();
                    launchDockApp(app);
                    tile.style.transform = 'scale(0.92)';
                    setTimeout(() => { tile.style.transform = ''; }, 150);
                }
            }, { passive: false });

            tile.addEventListener('click', (e) => {
                e.stopPropagation();
                launchDockApp(app);
                tile.style.transform = 'scale(0.92)';
                setTimeout(() => { tile.style.transform = ''; }, 150);
            });

            grid.appendChild(tile);
        });
    }

    // 4. 네비게이션 및 대시보드 인터랙션 버튼 바인딩
    const btnDockHome = document.getElementById('btnDockHome');
    if (btnDockHome) {
        const handleDockHome = (e) => {
            e.stopPropagation();
            if (e.cancelable) e.preventDefault();
            console.log('⌂ 홈 대시보드 뷰 전환');
            showDashboardView();
            btnDockHome.style.transform = 'scale(0.9)';
            setTimeout(() => { btnDockHome.style.transform = ''; }, 150);
        };
        btnDockHome.addEventListener('touchend', handleDockHome, { passive: false });
        btnDockHome.addEventListener('click', handleDockHome);
    }

    const btnDockMirror = document.getElementById('btnDockMirror');
    if (btnDockMirror) {
        const handleDockMirror = (e) => {
            e.stopPropagation();
            if (e.cancelable) e.preventDefault();
            console.log('📱 1:1 스마트폰 화면 미러링 뷰 전환');
            showMirrorView();
            if (canvas) canvas.style.display = 'block';
            try {
                window.mMirror.sendControl({ type: 'request_keyframe' });
            } catch (_) {}
            btnDockMirror.style.transform = 'scale(0.9)';
            setTimeout(() => { btnDockMirror.style.transform = ''; }, 150);
        };
        btnDockMirror.addEventListener('touchend', handleDockMirror, { passive: false });
        btnDockMirror.addEventListener('click', handleDockMirror);
    }

    const btnDashEnterMirror = document.getElementById('btnDashEnterMirror');
    if (btnDashEnterMirror) {
        btnDashEnterMirror.addEventListener('click', (e) => {
            e.stopPropagation();
            showMirrorView();
            if (canvas) canvas.style.display = 'block';
            try {
                window.mMirror.sendControl({ type: 'request_keyframe' });
            } catch (_) {}
        });
    }

    const btnDashTripLog = document.getElementById('btnDashTripLog');
    if (btnDashTripLog) {
        btnDashTripLog.addEventListener('click', (e) => {
            e.stopPropagation();
            if (window.openTripLogModal) {
                window.openTripLogModal();
            } else {
                const modal = document.getElementById('tripLogModal');
                if (modal) {
                    modal.classList.remove('hidden');
                    modal.style.setProperty('display', 'flex', 'important');
                }
            }
        });
    }

    // 팔레트 초기 로드 및 렌더링 (로컬 캐시 즉시 복원)
    loadAppIconCache();
    loadDockApps();
    renderDock();

    // 대시보드 & 시계 & 날씨 초기 기동
    updateDashClock();
    setInterval(updateDashClock, 1000);
    initWeatherFromCache();
    showDashboardView();

    // 전역 소켓/데이터채널 송신 래퍼 (touch.js 에서 활용)
    window.mMirror = {
        sendControl: function(msgObj) {
            if (webrtcDataChannel && webrtcDataChannel.readyState === 'open') {
                try {
                    webrtcDataChannel.send(JSON.stringify(msgObj));
                    return;
                } catch (_) {}
            }
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify(msgObj));
            }
        },
        getCanvas: function() {
            return canvas;
        },
        getVideo: function() {
            return null;
        },
        getDisplayMode: function() {
            return currentDisplayMode;
        },
        emergencyRecover: emergencyRecover
    };

    // 상태 표시 클릭 시 수동 비상 복구 (statusDot, statusText, 복구 버튼)
    [statusDot, statusText].forEach(el => {
        if (el) el.addEventListener('click', emergencyRecover);
    });
    const btnRecoverStream = document.getElementById('btnRecoverStream');
    if (btnRecoverStream) {
        btnRecoverStream.addEventListener('click', (e) => {
            e.stopPropagation();
            emergencyRecover();
        });
    }

    // --- 테슬라 키보드 텍스트 직접 입력 모달 연동 ---
    const textInputModal = document.getElementById('textInputModal');
    const dockBtnTextInput = document.getElementById('dockBtnTextInput');
    const btnCloseTextInput = document.getElementById('btnCloseTextInput');
    const textInputForm = document.getElementById('textInputForm');
    const phoneTextInput = document.getElementById('phoneTextInput');

    function openTextInputModal() {
        if (!textInputModal) return;
        textInputModal.classList.remove('hidden');
        textInputModal.style.setProperty('display', 'flex', 'important');
        if (phoneTextInput) {
            phoneTextInput.value = '';
            setTimeout(() => phoneTextInput.focus(), 100);
        }
    }

    function closeTextInputModal() {
        if (!textInputModal) return;
        textInputModal.classList.add('hidden');
        textInputModal.style.setProperty('display', 'none', 'important');
        if (phoneTextInput) phoneTextInput.blur();
    }

    if (dockBtnTextInput) {
        dockBtnTextInput.addEventListener('click', (e) => {
            e.stopPropagation();
            openTextInputModal();
        });
    }

    if (btnCloseTextInput) {
        btnCloseTextInput.addEventListener('click', (e) => {
            e.stopPropagation();
            closeTextInputModal();
        });
    }

    if (textInputForm && phoneTextInput) {
        textInputForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const text = phoneTextInput.value.trim();
            if (text) {
                window.mMirror.sendControl({ type: 'type_text', text: text });
                showTeslaToast(`✓ "${text}" 스마트폰 전송 완료`);
                closeTextInputModal();
            }
        });
    }

    // --- 차량 실시간 콘솔 로그 & 진단 모달 연동 ---
    const consoleLogModal = document.getElementById('consoleLogModal');
    const btnConsoleLog = document.getElementById('btnConsoleLog');
    const dockBtnConsoleLog = document.getElementById('dockBtnConsoleLog');
    const btnCloseConsoleModal = document.getElementById('btnCloseConsoleModal');
    const btnConsoleClear = document.getElementById('btnConsoleClear');
    const btnConsoleCopy = document.getElementById('btnConsoleCopy');
    const btnScrollToBottom = document.getElementById('btnScrollToBottom');
    const chkAutoScroll = document.getElementById('chkAutoScroll');
    const consoleSearchInput = document.getElementById('consoleSearchInput');

    const filterBtns = {
        all: document.getElementById('btnConsoleFilterAll'),
        sdp: document.getElementById('btnConsoleFilterSdp'),
        wrtc: document.getElementById('btnConsoleFilterWrtc'),
        err: document.getElementById('btnConsoleFilterErr')
    };

    function updateDiagSummary() {
        const diagApp = document.getElementById('diagAppVer');
        if (diagApp && lastReportedAppVersion) {
            diagApp.textContent = `v${lastReportedAppVersion}`;
        }
        const diagRtc = document.getElementById('diagRtcState');
        if (diagRtc && peerConnection) {
            diagRtc.textContent = `${peerConnection.connectionState || 'unknown'} (ICE: ${peerConnection.iceConnectionState || 'unknown'})`;
        }
        const diagMlines = document.getElementById('diagMlinesSummary');
        if (diagMlines && lastOfferMlines && lastOfferMlines.length > 0) {
            diagMlines.textContent = lastOfferMlines.join(' | ');
        }
        const diagOntrack = document.getElementById('diagOntrackCount');
        if (diagOntrack) {
            if (ontrackEventCount > 0) {
                diagOntrack.textContent = `${ontrackEventCount}회 (미디어 수신됨!)`;
                diagOntrack.style.color = '#f87171';
            } else {
                diagOntrack.textContent = '0회 (정상)';
                diagOntrack.style.color = '#10b981';
            }
        }
    }

    function openConsoleModal() {
        if (!consoleLogModal) return;
        isConsoleModalOpen = true;
        consoleLogModal.classList.remove('hidden');
        consoleLogModal.style.setProperty('display', 'flex', 'important');
        updateDiagSummary();
        renderAllLogsToUI();
    }

    function closeConsoleModal() {
        if (!consoleLogModal) return;
        isConsoleModalOpen = false;
        consoleLogModal.classList.add('hidden');
        consoleLogModal.style.setProperty('display', 'none', 'important');
    }

    if (btnConsoleLog) {
        btnConsoleLog.addEventListener('click', (e) => {
            e.stopPropagation();
            openConsoleModal();
        });
    }

    if (dockBtnConsoleLog) {
        dockBtnConsoleLog.addEventListener('click', (e) => {
            e.stopPropagation();
            openConsoleModal();
        });
    }

    if (btnCloseConsoleModal) {
        btnCloseConsoleModal.addEventListener('click', (e) => {
            e.stopPropagation();
            closeConsoleModal();
        });
    }

    if (btnConsoleClear) {
        btnConsoleClear.addEventListener('click', (e) => {
            e.stopPropagation();
            capturedLogs.length = 0;
            renderAllLogsToUI();
            const countBadge = document.getElementById('consoleLogCount');
            if (countBadge) countBadge.textContent = '0건';
            showTeslaToast('✓ 콘솔 로그가 초기화되었습니다');
        });
    }

    function fallbackCopyText(text) {
        try {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.focus();
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
            showTeslaToast('✓ 콘솔 로그 복사 완료');
        } catch (_) {
            showTeslaToast('클립보드 복사 실패');
        }
    }

    if (btnConsoleCopy) {
        btnConsoleCopy.addEventListener('click', (e) => {
            e.stopPropagation();
            const textToCopy = capturedLogs
                .filter(logMatchesFilter)
                .map(l => `[${l.time}] [${l.level.toUpperCase()}] ${l.text}`)
                .join('\n');
            if (!textToCopy) {
                showTeslaToast('복사할 로그가 없습니다');
                return;
            }
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(textToCopy).then(() => {
                    showTeslaToast('✓ 콘솔 로그 전체 복사 완료');
                }).catch(() => {
                    fallbackCopyText(textToCopy);
                });
            } else {
                fallbackCopyText(textToCopy);
            }
        });
    }

    if (chkAutoScroll) {
        chkAutoScroll.addEventListener('change', () => {
            autoScrollConsole = chkAutoScroll.checked;
        });
    }

    if (btnScrollToBottom) {
        btnScrollToBottom.addEventListener('click', () => {
            const container = document.getElementById('consoleLogContent');
            if (container) {
                container.scrollTop = container.scrollHeight;
            }
        });
    }

    if (consoleSearchInput) {
        consoleSearchInput.addEventListener('input', () => {
            consoleSearchQuery = consoleSearchInput.value.trim().toLowerCase();
            renderAllLogsToUI();
        });
    }

    Object.keys(filterBtns).forEach(key => {
        const btn = filterBtns[key];
        if (btn) {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                activeConsoleFilter = key;
                Object.values(filterBtns).forEach(b => b && b.classList.remove('active'));
                btn.classList.add('active');
                renderAllLogsToUI();
            });
        }
    });

    if (consoleLogModal) {
        consoleLogModal.addEventListener('click', (e) => {
            if (e.target === consoleLogModal) {
                closeConsoleModal();
            }
        });
    }

    // --- Web Audio 시스템 & PCM 48kHz 스테레오 250ms 적응형 엘라스틱 지터 버퍼 엔진 ---
    // 클록 드리프트(스마트폰 ↔ 테슬라 사운드카드 오차) 자동 보정 및 무손실 스트리밍
    function initAudioContext() {
        if (!audioCtx) {
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            if (AudioContextClass) {
                audioCtx = new AudioContextClass({ sampleRate: 48000 });
                audioGainNode = audioCtx.createGain();
                audioGainNode.gain.value = audioGainLevel;
                audioGainNode.connect(audioCtx.destination);

                // 2048 샘플 크기 (~42.6ms @ 48kHz) 연속 오디오 프로세서
                try {
                    audioScriptNode = audioCtx.createScriptProcessor(2048, 1, 2);
                    audioScriptNode.onaudioprocess = handleAudioProcess;

                    // Chromium 오디오 그래프 절전 방지용 더미 오실레이터
                    audioDummyOsc = audioCtx.createOscillator();
                    const dummyGain = audioCtx.createGain();
                    dummyGain.gain.value = 0.0;
                    audioDummyOsc.connect(dummyGain);
                    dummyGain.connect(audioScriptNode);
                    audioDummyOsc.start();

                    audioScriptNode.connect(audioGainNode);
                    console.log('🔊 [AUDIO] Web Audio 250ms 엘라스틱 지터 버퍼 엔진 초기화 완료 (sampleRate=' + audioCtx.sampleRate + ')');
                } catch (e) {
                    console.error('🔊 [AUDIO] ScriptProcessor 초기화 실패:', e);
                }
            }
        }
        if (audioCtx && audioCtx.state === 'suspended') {
            audioCtx.resume().then(() => {
                console.log('🔊 [AUDIO] AudioContext resume 성공 (state: running)');
                hideAudioUnlockBanner();
            }).catch(() => {});
        } else if (audioCtx && audioCtx.state === 'running') {
            hideAudioUnlockBanner();
        }
    }

    function showAudioUnlockBanner() {
        if (!isAudioStreamingActive) return;
        let banner = document.getElementById('audioUnlockBanner');
        if (!banner) {
            banner = document.createElement('div');
            banner.id = 'audioUnlockBanner';
            banner.style.position = 'fixed';
            banner.style.top = '14px';
            banner.style.left = '50%';
            banner.style.transform = 'translateX(-50%)';
            banner.style.background = 'linear-gradient(135deg, #1e3a8a, #2563eb)';
            banner.style.color = '#ffffff';
            banner.style.padding = '10px 22px';
            banner.style.borderRadius = '30px';
            banner.style.boxShadow = '0 8px 25px rgba(0,0,0,0.6), 0 0 15px rgba(59,130,246,0.5)';
            banner.style.fontSize = '14px';
            banner.style.fontWeight = 'bold';
            banner.style.zIndex = '999999';
            banner.style.cursor = 'pointer';
            banner.style.display = 'flex';
            banner.style.alignItems = 'center';
            banner.style.gap = '8px';
            banner.style.border = '1px solid rgba(255,255,255,0.3)';
            banner.innerHTML = '<span>🔊 [소리 켜기] 화면을 터치하면 오디오가 즉시 재생됩니다</span>';
            banner.addEventListener('click', (e) => {
                e.stopPropagation();
                ensureAudioContextUnlocked();
            });
            document.body.appendChild(banner);
        }
        banner.style.display = 'flex';
    }

    function hideAudioUnlockBanner() {
        const banner = document.getElementById('audioUnlockBanner');
        if (banner) {
            banner.style.display = 'none';
        }
    }

    function ensureAudioContextUnlocked() {
        if (!audioCtx) {
            initAudioContext();
        }
        if (audioCtx && audioCtx.state === 'suspended') {
            audioCtx.resume().then(() => {
                console.log('🔊 [AUDIO] AudioContext resumed via user gesture (state: running)');
                hideAudioUnlockBanner();
            }).catch((err) => {
                console.warn('🔊 [AUDIO] AudioContext resume failed:', err);
            });
        } else if (audioCtx && audioCtx.state === 'running') {
            hideAudioUnlockBanner();
        }
    }
    window.ensureAudioContextUnlocked = ensureAudioContextUnlocked;

    // 캡처 단계(capture: true)에서 모든 사용자 터치/클릭 제스처를 선점하여 AudioContext 즉각 언락
    ['click', 'touchstart', 'touchend', 'pointerdown', 'mousedown', 'keydown'].forEach(evt => {
        window.addEventListener(evt, () => {
            ensureAudioContextUnlocked();
        }, { capture: true, passive: true });
    });

    function handleAudioProcess(e) {
        const outL = e.outputBuffer.getChannelData(0);
        const outR = e.outputBuffer.getChannelData(1);
        const frames = outL.length; // 2048

        if (!isAudioStreamingActive) {
            outL.fill(0);
            outR.fill(0);
            return;
        }

        const ctxSampleRate = (audioCtx ? audioCtx.sampleRate : 48000);
        // 기준 워터마크: 타겟 250ms, 언더런 탈출 최소 프리버퍼 180ms
        const targetPrebuffer = Math.floor(ctxSampleRate * 0.18); // 180ms 프리버퍼
        const minBufferThreshold = Math.floor(ctxSampleRate * 0.15); // 150ms 미만: 미세 감속 (버퍼 충전)
        const highBufferThreshold = Math.floor(ctxSampleRate * 0.35); // 350ms 초과: 미세 가속 (버퍼 방출)
        const maxBufferLimit = Math.floor(ctxSampleRate * 0.50); // 500ms 상한 (Bufferbloat 방지)

        // 초기 시작 또는 언더런 후 재버퍼링: 180ms 완충될 때까지 대기
        if (isPcmBuffering) {
            if (pcmRingAvailable >= targetPrebuffer) {
                isPcmBuffering = false;
            } else {
                outL.fill(0);
                outR.fill(0);
                return;
            }
        }

        // 클록 드리프트 적응형 재생 속도 보정 (Elastic Rate Matching)
        // 버퍼가 부족하면 1.5% 천천히 재생하여 버퍼를 자연 보충하고, 버퍼가 많으면 1.5% 빠르게 재생하여 250ms 수렴
        let rate = 1.0;
        if (pcmRingAvailable < minBufferThreshold) {
            rate = 0.985; // 1.5% 감속 (청각상 피치 변화 감지 불가)
        } else if (pcmRingAvailable > highBufferThreshold) {
            rate = 1.015; // 1.5% 가속
        }

        const needed = Math.round(frames * rate);

        // 버퍼 고갈(Underflow) 발생 시: 남은 샘플을 부드럽게 페이드아웃 출력 후 재버퍼링 돌입
        if (pcmRingAvailable < needed) {
            const avail = Math.min(pcmRingAvailable, frames);
            for (let i = 0; i < avail; i++) {
                // 끝부분 32샘플 페이드아웃으로 팝/클릭 노이즈 완벽 제거
                const fade = (avail > 32 && i >= avail - 32) ? (avail - i) / 32.0 : 1.0;
                outL[i] = pcmRingBufferL[pcmRingReadPos] * fade;
                outR[i] = pcmRingBufferR[pcmRingReadPos] * fade;
                pcmRingReadPos = (pcmRingReadPos + 1) % PCM_RING_CAPACITY;
            }
            for (let i = avail; i < frames; i++) {
                outL[i] = 0;
                outR[i] = 0;
            }
            pcmRingAvailable = 0;
            isPcmBuffering = true;
            return;
        }

        // 정상 연속 스트리밍: rate에 따른 고음질 선형 보간 출력
        if (rate === 1.0) {
            for (let i = 0; i < frames; i++) {
                outL[i] = pcmRingBufferL[pcmRingReadPos];
                outR[i] = pcmRingBufferR[pcmRingReadPos];
                pcmRingReadPos = (pcmRingReadPos + 1) % PCM_RING_CAPACITY;
            }
            pcmRingAvailable -= frames;
        } else {
            for (let i = 0; i < frames; i++) {
                const pos = i * rate;
                const idx0 = Math.floor(pos);
                const frac = pos - idx0;
                const r0 = (pcmRingReadPos + idx0) % PCM_RING_CAPACITY;
                const r1 = (r0 + 1) % PCM_RING_CAPACITY;
                outL[i] = pcmRingBufferL[r0] * (1.0 - frac) + pcmRingBufferL[r1] * frac;
                outR[i] = pcmRingBufferR[r0] * (1.0 - frac) + pcmRingBufferR[r1] * frac;
            }
            pcmRingReadPos = (pcmRingReadPos + needed) % PCM_RING_CAPACITY;
            pcmRingAvailable -= needed;
        }

        // 지연 누적(Bufferbloat) 자동 억제: 500ms 초과 시 타겟 250ms로 부드럽게 조정
        if (pcmRingAvailable > maxBufferLimit) {
            const targetBufferSamples = Math.floor(ctxSampleRate * 0.25);
            const dropSamples = pcmRingAvailable - targetBufferSamples;
            pcmRingReadPos = (pcmRingReadPos + dropSamples) % PCM_RING_CAPACITY;
            pcmRingAvailable = targetBufferSamples;
        }
    }

    function playPcmAudio(arrayBuffer) {
        if (!isAudioStreamingActive) return;
        if (!audioCtx || !audioScriptNode) {
            initAudioContext();
            if (!audioCtx) return;
        }
        if (audioCtx.state === 'suspended') {
            showAudioUnlockBanner();
            audioCtx.resume().then(() => {
                hideAudioUnlockBanner();
            }).catch(() => {});
        } else if (audioCtx.state === 'running') {
            hideAudioUnlockBanner();
        }

        // 16-bit PCM Stereo (Interleaved L/R, 48000Hz)
        const pcm16 = new Int16Array(arrayBuffer);
        const inNumSamples = Math.floor(pcm16.length / 2);
        if (inNumSamples <= 0) return;

        const ctxSampleRate = audioCtx.sampleRate || 48000;

        if (ctxSampleRate === 48000) {
            // 48kHz 1:1 직결 입력
            for (let i = 0; i < inNumSamples; i++) {
                pcmRingBufferL[pcmRingWritePos] = pcm16[i * 2] / 32768.0;
                pcmRingBufferR[pcmRingWritePos] = pcm16[i * 2 + 1] / 32768.0;
                pcmRingWritePos = (pcmRingWritePos + 1) % PCM_RING_CAPACITY;
            }
            pcmRingAvailable += inNumSamples;
        } else {
            // 사운드카드 샘플 레이트가 44.1kHz 등 상이한 경우 선형 리샘플링 (피치/속도 왜곡 방지)
            const ratio = 48000.0 / ctxSampleRate;
            const outNumSamples = Math.floor(inNumSamples / ratio);
            for (let i = 0; i < outNumSamples; i++) {
                const srcIdx = i * ratio;
                const idx0 = Math.floor(srcIdx);
                const idx1 = Math.min(idx0 + 1, inNumSamples - 1);
                const frac = srcIdx - idx0;

                const l0 = pcm16[idx0 * 2] / 32768.0;
                const l1 = pcm16[idx1 * 2] / 32768.0;
                const r0 = pcm16[idx0 * 2 + 1] / 32768.0;
                const r1 = pcm16[idx1 * 2 + 1] / 32768.0;

                pcmRingBufferL[pcmRingWritePos] = l0 + (l1 - l0) * frac;
                pcmRingBufferR[pcmRingWritePos] = r0 + (r1 - r0) * frac;
                pcmRingWritePos = (pcmRingWritePos + 1) % PCM_RING_CAPACITY;
            }
            pcmRingAvailable += outNumSamples;
        }

        // 지연 누적(Bufferbloat) 자동 억제: 500ms 한계 초과 시 타겟 250ms로 점진 스킵
        const maxBufferLimit = Math.floor(ctxSampleRate * 0.50);
        if (pcmRingAvailable > maxBufferLimit) {
            const targetBufferSamples = Math.floor(ctxSampleRate * 0.25);
            const dropSamples = pcmRingAvailable - targetBufferSamples;
            pcmRingReadPos = (pcmRingReadPos + dropSamples) % PCM_RING_CAPACITY;
            pcmRingAvailable = targetBufferSamples;
        }
    }

    function flushAudioBuffer() {
        pcmRingWritePos = 0;
        pcmRingReadPos = 0;
        pcmRingAvailable = 0;
        isPcmBuffering = true;
    }

    function updateAudioModeUI(enabled) {
        isAudioStreamingActive = enabled;
        const btnBt = document.getElementById('btnAudioModeBt');
        const btnWeb = document.getElementById('btnAudioModeWeb');
        const secDelay = document.getElementById('sectionVideoDelay');
        const secGain = document.getElementById('sectionAudioGain');
        const topBarAudio = document.getElementById('btnTopBarAudio');
        const dockAudioEmoji = document.getElementById('dockAudioEmoji');
        const tipEl = document.getElementById('audioModeTip');

        if (enabled) {
            if (btnBt) btnBt.classList.remove('active');
            if (btnWeb) btnWeb.classList.add('active');
            if (secDelay) secDelay.style.display = 'flex';
            if (secGain) secGain.style.display = 'flex';
            if (topBarAudio) {
                topBarAudio.textContent = `🌐 웹소리 +${videoDelayMs}ms`;
                topBarAudio.classList.add('web-active');
            }
            if (dockAudioEmoji) dockAudioEmoji.textContent = '🌐';
            if (tipEl) {
                tipEl.innerHTML = '🌐 <strong>웹 브라우저 송출 중</strong>: 차량 브라우저로 소리가 직접 스트리밍됩니다. (A/V 싱크 지연 조절로 립싱크를 맞출 수 있습니다)';
            }
            flushAudioBuffer();
            initAudioContext();
        } else {
            if (btnBt) btnBt.classList.add('active');
            if (btnWeb) btnWeb.classList.remove('active');
            if (secDelay) secDelay.style.display = 'none';
            if (secGain) secGain.style.display = 'none';
            if (topBarAudio) {
                topBarAudio.textContent = '🔊 BT 직결 0ms';
                topBarAudio.classList.remove('web-active');
            }
            if (dockAudioEmoji) dockAudioEmoji.textContent = '🔊';
            if (tipEl) {
                tipEl.innerHTML = '💡 <strong>안내</strong>: 차량 화면 하단 미디어 소스를 <strong>[블루투스]</strong>로 선택하시면 스마트폰의 티맵 안내와 음악이 차량 스피커로 최고 음질로 즉시 출력됩니다.';
            }
            flushAudioBuffer();
            flushVideoDelayQueue();
        }
    }

    function setAudioMode(mode) {
        const enabled = (mode === 'web');
        isAudioStreamingActive = enabled;
        try {
            localStorage.setItem('mmirror_audio_mode', mode);
        } catch (_) {}
        if (enabled) {
            const savedDelay = parseInt(localStorage.getItem('mmirror_video_delay') || '0', 10);
            setVideoDelay(savedDelay >= 0 ? savedDelay : 0);
        } else {
            setVideoDelay(0);
        }
        updateAudioModeUI(enabled);

        // 스마트폰에 DataChannel로 변경 신호 전송
        const msg = JSON.stringify({ type: 'set_audio_mode', enabled: enabled });
        if (webrtcDataChannel && webrtcDataChannel.readyState === 'open') {
            try { webrtcDataChannel.send(msg); } catch (_) {}
        }
        console.log(`🔊 [AUDIO-MODE] Set audio mode to: ${mode} (enabled=${enabled})`);
    }

    function setVideoDelay(ms) {
        videoDelayMs = ms;
        localStorage.setItem('mmirror_video_delay', String(ms));
        const badge = document.getElementById('txtCurrentDelay');
        if (badge) badge.textContent = `${ms}ms`;
        const slider = document.getElementById('sliderVideoDelay');
        if (slider) slider.value = ms;
        const topBarAudio = document.getElementById('btnTopBarAudio');
        if (topBarAudio && isAudioStreamingActive) {
            topBarAudio.textContent = `🌐 웹소리 +${ms}ms`;
        }

        // 프리셋 버튼 활성화 상태 갱신
        document.querySelectorAll('.delay-preset-btn').forEach(btn => {
            const delayVal = parseInt(btn.dataset.delay, 10);
            if (delayVal === ms) {
                btn.classList.add('active');
            } else {
                btn.classList.remove('active');
            }
        });

        if (ms === 0) {
            flushVideoDelayQueue();
        }
        console.log(`⏱️ [A/V SYNC] Video delay set to: ${ms}ms`);
    }

    function setAudioGain(gain) {
        audioGainLevel = gain;
        if (audioGainNode) {
            audioGainNode.gain.value = gain;
        }
        document.querySelectorAll('.gain-btn').forEach(btn => {
            const val = parseFloat(btn.dataset.gain);
            if (val === gain) {
                btn.classList.add('active');
            } else {
                btn.classList.remove('active');
            }
        });
        console.log(`🔊 [AUDIO-GAIN] Digital audio gain set to: ${gain}x`);
    }

    function setupAudioModalListeners() {
        const modal = document.getElementById('audioSettingsModal');
        const btnOpenDock = document.getElementById('btnDockAudio');
        const btnOpenTop = document.getElementById('btnTopBarAudio');
        const btnClose = document.getElementById('btnCloseAudioModal');

        function openModal() {
            if (modal) {
                modal.classList.remove('hidden');
                modal.style.setProperty('display', 'flex', 'important');
                initAudioContext();
            }
        }
        function closeModal() {
            if (modal) {
                modal.classList.add('hidden');
                modal.style.display = 'none';
            }
        }

        if (btnOpenDock) btnOpenDock.addEventListener('click', openModal);
        if (btnOpenTop) btnOpenTop.addEventListener('click', openModal);
        if (btnClose) btnClose.addEventListener('click', closeModal);
        if (modal) {
            modal.addEventListener('click', (e) => {
                if (e.target === modal) closeModal();
            });
        }

        // 모드 전환 버튼
        const btnBt = document.getElementById('btnAudioModeBt');
        const btnWeb = document.getElementById('btnAudioModeWeb');
        if (btnBt) btnBt.addEventListener('click', () => setAudioMode('bluetooth'));
        if (btnWeb) btnWeb.addEventListener('click', () => setAudioMode('web'));

        // 딜레이 프리셋 버튼들
        document.querySelectorAll('.delay-preset-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const val = parseInt(btn.dataset.delay, 10);
                setVideoDelay(val);
            });
        });

        // 딜레이 슬라이더
        const slider = document.getElementById('sliderVideoDelay');
        if (slider) {
            slider.addEventListener('input', (e) => {
                setVideoDelay(parseInt(e.target.value, 10));
            });
        }

        // 볼륨 게인 버튼들
        document.querySelectorAll('.gain-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                setAudioGain(parseFloat(btn.dataset.gain));
            });
        });

        // 수동 언락 및 테스트 비프음
        const btnUnlock = document.getElementById('btnManualUnlockAudio');
        if (btnUnlock) {
            btnUnlock.addEventListener('click', () => {
                initAudioContext();
                if (audioCtx) {
                    try {
                        const osc = audioCtx.createOscillator();
                        const g = audioCtx.createGain();
                        osc.type = 'sine';
                        osc.frequency.setValueAtTime(440, audioCtx.currentTime);
                        g.gain.setValueAtTime(0.2, audioCtx.currentTime);
                        g.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.3);
                        osc.connect(g);
                        g.connect(audioGainNode || audioCtx.destination);
                        osc.start();
                        osc.stop(audioCtx.currentTime + 0.3);
                        showTeslaToast('🔊 브라우저 사운드가 정상 작동 중입니다 (테스트 비프음 재생됨)');
                    } catch (_) {}
                }
            });
        }

        // 초기 UI 상태 적용 (localStorage 저장된 모드 복원)
        const savedAudioMode = localStorage.getItem('mmirror_audio_mode') || 'bluetooth';
        updateAudioModeUI(savedAudioMode === 'web');
        if (savedAudioMode === 'web') {
            const savedDelay = parseInt(localStorage.getItem('mmirror_video_delay') || '0', 10);
            setVideoDelay(savedDelay >= 0 ? savedDelay : 0);
        }
    }
    setupAudioModalListeners();

    function setupMirrorSidePanelListeners() {
        const btnToggle = document.getElementById('btnToggleSidePanel');
        const btnRestore = document.getElementById('btnSidePanelRestore');

        if (btnToggle) {
            btnToggle.addEventListener('click', (e) => {
                e.stopPropagation();
                localStorage.setItem('mmirror_show_side_widget', 'false');
                updateCanvasDisplayLayout(true);
            });
        }

        if (btnRestore) {
            btnRestore.addEventListener('click', (e) => {
                e.stopPropagation();
                localStorage.setItem('mmirror_show_side_widget', 'true');
                updateCanvasDisplayLayout(true);
            });
        }

        initAdCarousel();
    }

    function initAdCarousel() {
        const track = document.getElementById('sideAdTrack');
        const container = document.getElementById('sideAdCarousel');
        const dots = document.querySelectorAll('#sideAdDots .ad-dot');
        const btnPrev = document.getElementById('btnPrevAd');
        const btnNext = document.getElementById('btnNextAd');
        if (!track) return;

        const totalSlides = track.children.length;
        if (totalSlides <= 1) return;

        let currentIndex = 0;
        let autoSlideTimer = null;
        let isPaused = false;

        function goToSlide(index) {
            currentIndex = (index + totalSlides) % totalSlides;
            track.style.transform = `translateX(-${currentIndex * 100}%)`;
            dots.forEach((dot, idx) => {
                if (idx === currentIndex) dot.classList.add('active');
                else dot.classList.remove('active');
            });
        }

        function nextSlide() {
            goToSlide(currentIndex + 1);
        }

        function prevSlide() {
            goToSlide(currentIndex - 1);
        }

        function startAutoSlide() {
            stopAutoSlide();
            autoSlideTimer = setInterval(() => {
                if (!isPaused && window.currentViewMode === 'mirror') {
                    nextSlide();
                }
            }, 5500);
        }

        function stopAutoSlide() {
            if (autoSlideTimer) {
                clearInterval(autoSlideTimer);
                autoSlideTimer = null;
            }
        }

        if (btnNext) {
            btnNext.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                nextSlide();
                startAutoSlide();
            });
        }

        if (btnPrev) {
            btnPrev.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                prevSlide();
                startAutoSlide();
            });
        }

        dots.forEach((dot) => {
            dot.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const idx = parseInt(dot.dataset.index, 10);
                if (!isNaN(idx)) {
                    goToSlide(idx);
                    startAutoSlide();
                }
            });
        });

        if (container) {
            container.addEventListener('mouseenter', () => { isPaused = true; });
            container.addEventListener('mouseleave', () => { isPaused = false; });
            container.addEventListener('touchstart', () => { isPaused = true; }, { passive: true });
            container.addEventListener('touchend', () => {
                setTimeout(() => { isPaused = false; }, 3000);
            }, { passive: true });
        }

        startAutoSlide();
    }
    setupMirrorSidePanelListeners();

    // [작업 지시서 4] 가설 3 검증용 실험 지원 (URL 쿼리 기반)
    // ?test=ws : WebRTC 미사용, 순수 WebSocket(/ws) 모드만 사용
    // ?test=touch : 사용자 첫 터치/클릭 이후에만 connectWebRtc() 호출
    const testModeParams = new URLSearchParams(window.location.search);
    const testMode = (testModeParams.get('test') || testModeParams.get('mode') || '').toLowerCase();

    // 활성 진단 모드 배지 갱신 (테슬라 화면 표시)
    const activeModeEl = document.getElementById('activeModeText');
    if (activeModeEl) {
        if (window.location.search) {
            activeModeEl.textContent = `현재: ${window.location.search}`;
            activeModeEl.style.background = '#d97706';
        } else {
            activeModeEl.textContent = '현재: 일반 정식 미러링';
            activeModeEl.style.background = '#0284c7';
        }
    }

    requestWakeLock();
    startWatchdog();

    if (testMode === 'ws') {
        console.log('🧪 [EXP-A] 테스트 모드 A: RTCPeerConnection 미사용, 순수 WebSocket(/ws) 모드 활성화');
        connectWebSocket();
    } else if (testMode === 'touch' || testMode === 'gesture') {
        console.log('🧪 [EXP-B] 테스트 모드 B: 사용자 터치 대기 후 WebRTC 연결');
        let touchWebRtcStarted = false;
        const startOnTouch = () => {
            if (touchWebRtcStarted) return;
            touchWebRtcStarted = true;
            console.log('🧪 [EXP-B] 사용자 터치 감지됨 -> WebRTC 연결 시작');
            connectWebRtc();
            connectWebSocket();
            ['click', 'touchstart', 'pointerdown'].forEach(evt => document.removeEventListener(evt, startOnTouch));
        };
        ['click', 'touchstart', 'pointerdown'].forEach(evt => document.addEventListener(evt, startOnTouch, { passive: true, once: true }));
    } else {
        connectWebRtc();
        connectWebSocket();
    }
})();
