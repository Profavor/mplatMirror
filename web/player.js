// mMirror Tesla Browser Player: WebCodecs + Web Audio API Engine
(function() {
    'use strict';

    const canvas = document.getElementById('videoCanvas');
    const ctx = canvas.getContext('2d');
    const statusDot = document.getElementById('statusDot');
    const statusText = document.getElementById('statusText');
    const resolutionInfo = document.getElementById('resolutionInfo');
    const fpsInfo = document.getElementById('fpsInfo');
    const latencyInfo = document.getElementById('latencyInfo');
    const disconnectOverlay = document.getElementById('disconnectOverlay');
    const overlayTitle = document.getElementById('overlayTitle');
    const overlayMessage = document.getElementById('overlayMessage');
    const audioUnlockOverlay = document.getElementById('audioUnlockOverlay');
    const btnUnlockAudio = document.getElementById('btnUnlockAudio');
    const btnAudioToggle = document.getElementById('btnAudioToggle');
    const btnFullscreen = document.getElementById('btnFullscreen');

    let ws = null;
    let videoDecoder = null;
    let audioCtx = null;
    let audioNextPlayTime = 0;
    let isAudioMuted = false;
    let frameCount = 0;
    let lastFpsTime = performance.now();
    let isConnected = false;
    let videoConfigured = false;

    // 패킷 타입 식별자
    const PKT_TYPE_VIDEO = 0x01; // H.264 NAL Frame
    const PKT_TYPE_AUDIO = 0x02; // Raw PCM Audio (48000Hz, 16bit Stereo)
    const PKT_TYPE_CONFIG = 0x03; // Metadata (Width, Height, FPS, etc.)
    const PKT_TYPE_GPS = 0x04; // 실시간 GPS 주행 데이터

    // --- Web Audio 시스템 초기화 ---
    function initAudio() {
        if (!audioCtx) {
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            audioCtx = new AudioContextClass({ sampleRate: 48000 });
        }
        if (audioCtx.state === 'suspended') {
            audioCtx.resume().then(() => {
                audioUnlockOverlay.classList.add('hidden');
            }).catch(() => {
                audioUnlockOverlay.classList.remove('hidden');
            });
        } else {
            audioUnlockOverlay.classList.add('hidden');
        }
    }

    btnUnlockAudio.addEventListener('click', () => {
        initAudio();
    });

    document.body.addEventListener('click', () => {
        if (audioCtx && audioCtx.state === 'suspended') {
            audioCtx.resume();
        }
    }, { once: true });

    btnAudioToggle.addEventListener('click', () => {
        isAudioMuted = !isAudioMuted;
        btnAudioToggle.textContent = isAudioMuted ? '🔇' : '🔊';
        if (!isAudioMuted && audioCtx && audioCtx.state === 'suspended') {
            audioCtx.resume();
        }
    });

    // 전체화면 토글
    btnFullscreen.addEventListener('click', () => {
        if (!document.fullscreenElement) {
            document.documentElement.requestFullscreen().catch(err => {
                console.warn('전체화면 전환 실패:', err);
            });
        } else {
            document.exitFullscreen();
        }
    });

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

    function applyFitMode(modeObj) {
        window.screenFitMode = modeObj.id;
        canvas.className = 'fit-' + modeObj.id;
        if (btnFitMode) {
            btnFitMode.textContent = modeObj.label;
            btnFitMode.title = modeObj.title;
        }
        localStorage.setItem('mmirror_fit_mode', modeObj.id);
        console.log('화면 채움 모드 적용:', modeObj.id);
    }

    if (btnFitMode) {
        btnFitMode.addEventListener('click', () => {
            currentFitIndex = (currentFitIndex + 1) % FIT_MODES.length;
            applyFitMode(FIT_MODES[currentFitIndex]);
        });
    }
    applyFitMode(FIT_MODES[currentFitIndex]);

    // 몰입 모드 & UI 자동 숨김 (Immersive Auto-Hide)
    const topBar = document.getElementById('topBar');
    const floatingNavbar = document.getElementById('floatingNavbar');
    const btnImmersiveToggle = document.getElementById('btnImmersiveToggle');
    const btnShowUiFab = document.getElementById('btnShowUiFab');
    const tripLogModal = document.getElementById('tripLogModal');

    window.isImmersiveMode = true; // 기본적으로 3초 후 UI 자동 숨김
    let uiHideTimer = null;

    function showUiControls() {
        if (topBar) topBar.classList.remove('ui-hidden');
        if (floatingNavbar) floatingNavbar.classList.remove('ui-hidden');
        if (btnShowUiFab) btnShowUiFab.classList.add('hidden');
        resetUiHideTimer();
    }

    function hideUiControls() {
        if (!window.isImmersiveMode) return;
        if (tripLogModal && !tripLogModal.classList.contains('hidden')) return;
        if (disconnectOverlay && !disconnectOverlay.classList.contains('hidden')) return;

        if (topBar) topBar.classList.add('ui-hidden');
        if (floatingNavbar) floatingNavbar.classList.add('ui-hidden');
        if (btnShowUiFab) btnShowUiFab.classList.remove('hidden');
    }

    function resetUiHideTimer() {
        if (uiHideTimer) clearTimeout(uiHideTimer);
        if (!window.isImmersiveMode) return;
        uiHideTimer = setTimeout(() => {
            hideUiControls();
        }, 3200);
    }

    window.resetUiHideTimer = resetUiHideTimer;
    window.showUiControls = showUiControls;

    if (btnImmersiveToggle) {
        btnImmersiveToggle.addEventListener('click', () => {
            window.isImmersiveMode = !window.isImmersiveMode;
            btnImmersiveToggle.classList.toggle('active', !window.isImmersiveMode);
            btnImmersiveToggle.textContent = window.isImmersiveMode ? '👁️' : '🔒';
            if (window.isImmersiveMode) {
                resetUiHideTimer();
            } else {
                if (uiHideTimer) clearTimeout(uiHideTimer);
                showUiControls();
            }
        });
    }

    if (btnShowUiFab) {
        btnShowUiFab.addEventListener('click', (e) => {
            e.stopPropagation();
            showUiControls();
        });
    }

    // 마우스 움직임 감지 시 UI 리셋
    window.addEventListener('mousemove', () => {
        if (topBar && topBar.classList.contains('ui-hidden')) {
            showUiControls();
        } else {
            resetUiHideTimer();
        }
    }, { passive: true });

    // 티맵 분할 크롭 모드 (상단 50%만 2배 확대)
    const btnCropToggle = document.getElementById('btnCropToggle');
    const btnNavCrop = document.getElementById('btnNavCrop');
    window.isCropMode = false;
    window.cropRatio = 0.5; // 기본 상단 50% 분할

    function toggleCropMode() {
        window.isCropMode = !window.isCropMode;
        if (btnCropToggle) {
            btnCropToggle.classList.toggle('active', window.isCropMode);
            btnCropToggle.textContent = window.isCropMode ? '🔍 전체 복원' : '✂️ 내비 확대';
        }
        if (btnNavCrop) {
            btnNavCrop.classList.toggle('active', window.isCropMode);
        }
        console.log('티맵 분할 크롭 모드:', window.isCropMode);
    }

    if (btnCropToggle) btnCropToggle.addEventListener('click', toggleCropMode);
    if (btnNavCrop) btnNavCrop.addEventListener('click', toggleCropMode);

    let hasReceivedFirstKeyFrame = false;

    // --- WebCodecs 비디오 디코더 초기화 ---
    function initVideoDecoder() {
        if (!('VideoDecoder' in window)) {
            console.error('WebCodecs VideoDecoder를 지원하지 않는 브라우저입니다.');
            statusText.textContent = '⚠️ WebCodecs 미지원 브라우저 (MCU3/크롬 필요)';
            statusDot.className = 'dot disconnected';
            return;
        }

        try {
            if (videoDecoder && videoDecoder.state !== 'closed') {
                videoDecoder.close();
            }
        } catch (_) {}

        videoConfigured = false;
        hasReceivedFirstKeyFrame = false;

        videoDecoder = new VideoDecoder({
            output: (videoFrame) => {
                // 첫 프레임 수신 시 오버레이 숨김 및 상태 업데이트
                statusText.textContent = '미러링 정상 송출 중';
                statusDot.className = 'dot connected';
                disconnectOverlay.classList.add('hidden');

                // 캔버스 크기 동기화
                const targetW = videoFrame.displayWidth;
                const targetH = window.isCropMode ? Math.round(videoFrame.displayHeight * (window.cropRatio || 0.5)) : videoFrame.displayHeight;

                if (canvas.width !== targetW || canvas.height !== targetH) {
                    canvas.width = targetW;
                    canvas.height = targetH;
                    resolutionInfo.textContent = `${targetW}x${targetH}` + (window.isCropMode ? ' (티맵 확대)' : '');
                }

                // 초저지연 프레임 드로우
                if (window.isCropMode) {
                    // 스마트폰 화면의 상단 50%(티맵 영역)만 2배 확대해서 캔버스 전체에 꽉 채워 렌더링!
                    const srcH = videoFrame.displayHeight * (window.cropRatio || 0.5);
                    ctx.drawImage(videoFrame, 0, 0, videoFrame.displayWidth, srcH, 0, 0, canvas.width, canvas.height);
                } else {
                    ctx.drawImage(videoFrame, 0, 0, canvas.width, canvas.height);
                }
                videoFrame.close();

                // FPS 계산
                frameCount++;
                const now = performance.now();
                if (now - lastFpsTime >= 1000) {
                    const fps = Math.round((frameCount * 1000) / (now - lastFpsTime));
                    fpsInfo.textContent = `${fps} FPS`;
                    frameCount = 0;
                    lastFpsTime = now;
                }
            },
            error: (err) => {
                console.error('VideoDecoder 오류:', err);
                videoConfigured = false;
                hasReceivedFirstKeyFrame = false;
                setTimeout(initVideoDecoder, 500);
            }
        });
    }

    // --- 오디오 PCM 재생 처리 (저지연 지터 버퍼링) ---
    function playPcmAudio(arrayBuffer) {
        if (isAudioMuted || !audioCtx || audioCtx.state !== 'running') return;

        // 16-bit PCM, 2ch (Interleaved L/R)
        const pcm16 = new Int16Array(arrayBuffer);
        const numChannels = 2;
        const numSamples = pcm16.length / numChannels;

        const audioBuffer = audioCtx.createBuffer(numChannels, numSamples, 48000);
        const channelLeft = audioBuffer.getChannelData(0);
        const channelRight = audioBuffer.getChannelData(1);

        for (let i = 0; i < numSamples; i++) {
            channelLeft[i] = pcm16[i * 2] / 32768.0;
            channelRight[i] = pcm16[i * 2 + 1] / 32768.0;
        }

        const source = audioCtx.createBufferSource();
        source.buffer = audioBuffer;
        source.connect(audioCtx.destination);

        const currentTime = audioCtx.currentTime;
        // 딜레이가 너무 누적되었을 경우 리셋 (최대 0.08초 지연 허용)
        if (audioNextPlayTime < currentTime || audioNextPlayTime > currentTime + 0.15) {
            audioNextPlayTime = currentTime + 0.02;
        }

        source.start(audioNextPlayTime);
        audioNextPlayTime += audioBuffer.duration;
    }

    // --- WebSocket 연결 및 스트림 수신 ---
    function connectWebSocket() {
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${location.host}/ws`;

        console.log('Connecting to WebSocket:', wsUrl);
        statusText.textContent = '연결 시도 중...';
        statusDot.className = 'dot disconnected';

        ws = new WebSocket(wsUrl);
        ws.binaryType = 'arraybuffer';

        ws.onopen = () => {
            console.log('WebSocket 연결 완료');
            isConnected = true;
            statusDot.className = 'dot connected';
            statusText.textContent = '스마트폰 송출 대기 중...';
            if (overlayTitle) overlayTitle.textContent = "스마트폰에서 '미러링 시작'을 눌러주세요";
            if (overlayMessage) overlayMessage.innerHTML = "테슬라 화면과 스마트폰이 정상 연결되었습니다.<br>스마트폰 화면의 <strong>mplat Mirror 앱</strong>에서 <strong>[미러링 시작]</strong> 버튼을 누르면 즉시 화면이 송출됩니다.";
            disconnectOverlay.classList.remove('hidden');
            initAudio();
            initVideoDecoder();
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
            } catch (e) {
                console.warn('GPS packet parse error:', e);
            }
        }

        ws.onclose = () => {
            console.warn('WebSocket 연결 종료. 2초 후 재연결 시도...');
            isConnected = false;
            videoConfigured = false;
            hasReceivedFirstKeyFrame = false;
            statusDot.className = 'dot disconnected';
            statusText.textContent = '스마트폰 연결 끊김';
            if (overlayTitle) overlayTitle.textContent = "스마트폰 연결 대기 중";
            if (overlayMessage) overlayMessage.innerHTML = "스마트폰 핫스팟에 연결되어 있는지 확인하고,<br>스마트폰의 <strong>mplat Mirror 앱</strong>을 실행해주세요.";
            disconnectOverlay.classList.remove('hidden');
            setTimeout(connectWebSocket, 2000);
        };

        ws.onerror = (err) => {
            console.error('WebSocket 에러:', err);
            ws.close();
        };
    }

    // NAL 패킷 처리
    function handleVideoPacket(payload) {
        if (!videoDecoder) return;

        const bytes = new Uint8Array(payload);
        if (bytes.length < 5) return;

        // NAL 유닛 타입 확인 (비트마스크: 0x1F)
        let nalType = 0;
        let nalOffset = 0;
        if (bytes[0] === 0 && bytes[1] === 0 && bytes[2] === 0 && bytes[3] === 1) {
            nalType = bytes[4] & 0x1F;
            nalOffset = 4;
        } else if (bytes[0] === 0 && bytes[1] === 0 && bytes[2] === 1) {
            nalType = bytes[3] & 0x1F;
            nalOffset = 3;
        }

        // SPS (7), PPS (8) 또는 IDR (5)
        const isKeyFrame = (nalType === 5 || nalType === 7);

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
                return; // 다음 1초 내 키프레임 도착 시까지 대기
            }
            hasReceivedFirstKeyFrame = true;
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
                if (videoDecoder.state === 'closed') {
                    videoConfigured = false;
                    hasReceivedFirstKeyFrame = false;
                    initVideoDecoder();
                }
            }
        } else if (videoDecoder.state === 'closed') {
            videoConfigured = false;
            hasReceivedFirstKeyFrame = false;
            initVideoDecoder();
        }
    }

    function handleConfigPacket(payload) {
        try {
            const dec = new TextDecoder();
            const config = JSON.parse(dec.decode(payload));
            console.log('서버 설정 수신:', config);
            if (config.width && config.height) {
                resolutionInfo.textContent = `${config.width}x${config.height}`;
            }
        } catch (e) {
            console.warn('설정 패킷 파싱 오류:', e);
        }
    }

    // 전역 소켓 송신 래퍼 (touch.js 에서 활용)
    window.mMirror = {
        sendControl: function(msgObj) {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify(msgObj));
            }
        },
        getCanvas: function() {
            return canvas;
        }
    };

    // 앱 시작 시 소켓 연결
    connectWebSocket();
})();
