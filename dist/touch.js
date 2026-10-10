/**
 * mMirror Tesla Precision Touch Engine (해상도 동적 보정 및 저지연 직결)
 * 16인치 1600x1120 / 15.4인치 1280x960 등 변경된 해상도 및 비디오 레터박스를 자동 계산하여
 * 테슬라 터치스크린 좌표를 1:1 오차 0px로 스마트폰/가상화면에 주입합니다.
 */
(function () {
    'use strict';

    const canvas = document.getElementById('videoCanvas');
    const viewportContainer = document.getElementById('viewportContainer') || canvas;

    let isMouseDown = false;

    // 터치/마우스 좌표를 현재 송출 해상도(캔버스 크기)를 고려하여 정확히 0.0 ~ 1.0 정규화 좌표로 변환
    function getNormalizedCoords(clientX, clientY) {
        const activeEl = canvas;
        if (!activeEl) return null;
        const rect = activeEl.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) return null;

        // 실제 송출 영상의 고유 해상도 (예: 1648x1920, 1920x1080 등)
        const streamWidth = activeEl.videoWidth || activeEl.width || 1600;
        const streamHeight = activeEl.videoHeight || activeEl.height || 1120;
        if (streamWidth <= 0 || streamHeight <= 0) return null;

        const isCanvas = (activeEl === canvas);
        const isFitFill = activeEl.classList.contains('fit-fill');
        const isCenterFit = activeEl.classList.contains('fit-center');

        // [A] 캔버스 엘리먼트: CSS(width auto / 100%, fit-fill 등)로 렌더링된 DOM 엘리먼트 rect 자체가 실제 비디오 영역임
        if (isCanvas) {
            const relX = clientX - rect.left;
            const relY = clientY - rect.top;
            if (relX < 0 || relX > rect.width || relY < 0 || relY > rect.height) {
                return null; // 캔버스 외곽(우측/좌우 검은 여백) 터치 무시
            }
            let normY = Math.max(0.0, Math.min(1.0, relY / rect.height));
            if (window.isCropMode) {
                normY = normY * (window.cropRatio || 0.5);
            }
            return {
                x: Math.max(0.0, Math.min(1.0, relX / rect.width)),
                y: normY
            };
        }

        // [B] video 태그 엘리먼트:
        if (isFitFill) {
            const relX = clientX - rect.left;
            const relY = clientY - rect.top;
            if (relX < 0 || relX > rect.width || relY < 0 || relY > rect.height) return null;
            return {
                x: Math.max(0.0, Math.min(1.0, relX / rect.width)),
                y: Math.max(0.0, Math.min(1.0, relY / rect.height))
            };
        }

        const streamAspect = streamWidth / streamHeight;
        const containerAspect = rect.width / rect.height;

        let renderedWidth = rect.width;
        let renderedHeight = rect.height;
        let offsetX = 0;
        let offsetY = 0;

        if (containerAspect > streamAspect) {
            // 컨테이너가 더 넓음 -> 좌우 필러박스 (검은 여백)
            renderedHeight = rect.height;
            renderedWidth = rect.height * streamAspect;
            offsetX = isCenterFit ? (rect.width - renderedWidth) / 2 : 0;
            offsetY = 0;
        } else {
            // 컨테이너가 더 높음 -> 상하 레터박스
            renderedWidth = rect.width;
            renderedHeight = rect.width / streamAspect;
            offsetX = 0;
            offsetY = isCenterFit ? (rect.height - renderedHeight) / 2 : 0;
        }

        const relX = clientX - rect.left - offsetX;
        const relY = clientY - rect.top - offsetY;

        if (relX < 0 || relX > renderedWidth || relY < 0 || relY > renderedHeight) {
            return null; // 비디오 영역 밖의 레터박스 여백 클릭 무시
        }

        let normY = Math.max(0.0, Math.min(1.0, relY / renderedHeight));
        if (window.isCropMode) {
            normY = normY * (window.cropRatio || 0.5);
        }

        return {
            x: Math.max(0.0, Math.min(1.0, relX / renderedWidth)),
            y: normY
        };
    }

    function getMode() {
        return window.isStandaloneMode === false ? 'mirror' : 'standalone';
    }

    function sendControl(payload) {
        if (window.mMirror && window.mMirror.sendControl) {
            window.mMirror.sendControl(payload);
        }
    }

    const lastTouchCoords = new Map();

    function getAllActivePointers(touchList) {
        const pointers = [];
        for (let i = 0; i < touchList.length; i++) {
            const t = touchList[i];
            const c = getNormalizedCoords(t.clientX, t.clientY) || lastTouchCoords.get(t.identifier);
            if (c) {
                pointers.push({
                    id: t.identifier,
                    x: c.x,
                    y: c.y
                });
            }
        }
        return pointers;
    }

    // --- 멀티터치 핀치 줌 (Pinch-to-Zoom) 엔진 상태 관리 ---
    let isPinching = false;
    let pinchStartDist = 0;
    let lastPinchDist = 0;
    let lastPinchTime = 0;
    let pinchTouchStartTime = 0;
    let pinchCenter = { x: 0.5, y: 0.5 };

    function isDashboardEvent(e) {
        const dash = document.getElementById('homeDashboard');
        if (dash && !dash.classList.contains('hidden') && e.target && e.target.closest('#homeDashboard')) {
            return true;
        }
        const side = document.getElementById('mirrorSidePanel');
        if (side && side.style.display !== 'none' && e.target && e.target.closest('#mirrorSidePanel')) {
            return true;
        }
        const restoreBtn = document.getElementById('btnSidePanelRestore');
        if (restoreBtn && restoreBtn.style.display !== 'none' && e.target && e.target.closest('#btnSidePanelRestore')) {
            return true;
        }
        return false;
    }

    // --- 터치 이벤트 핸들러 (테슬라 디스플레이 전용 - 멀티터치 핀치 줌 & 1:1 직결 터치) ---
    viewportContainer.addEventListener('touchstart', (e) => {
        if (typeof window.ensureAudioContextUnlocked === 'function') {
            window.ensureAudioContextUnlocked();
        }
        if (isDashboardEvent(e)) return;
        e.preventDefault();
        if (typeof window.flushVideoDelayQueue === 'function') {
            window.flushVideoDelayQueue();
        }
        if (window.resetUiHideTimer) window.resetUiHideTimer();
        if (e.changedTouches[0] && e.changedTouches[0].clientY < 40) {
            if (window.showUiControls) window.showUiControls();
        }

        // 두 손가락 이상 터치 감지 시: 핀치 줌(Pinch-to-Zoom) 모드로 즉시 전환
        if (e.touches.length >= 2) {
            isPinching = true;
            const t0 = e.touches[0];
            const t1 = e.touches[1];
            pinchStartDist = Math.hypot(t0.clientX - t1.clientX, t0.clientY - t1.clientY);
            lastPinchDist = pinchStartDist;
            lastPinchTime = Date.now();
            pinchTouchStartTime = Date.now();

            const c0 = getNormalizedCoords(t0.clientX, t0.clientY);
            const c1 = getNormalizedCoords(t1.clientX, t1.clientY);
            if (c0 && c1) {
                pinchCenter = { x: (c0.x + c1.x) / 2, y: (c0.y + c1.y) / 2 };
            }

            // 기존 단일 손가락 터치 세션 취소 (원치 않는 1손가락 탭/드래그 오동작 원천 차단)
            for (let i = 0; i < e.touches.length; i++) {
                sendControl({
                    type: 'touch',
                    action: 'cancel',
                    id: e.touches[i].identifier,
                    mode: getMode()
                });
            }
            return;
        }

        if (isPinching) return;

        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = getNormalizedCoords(touch.clientX, touch.clientY);
            if (coords) {
                lastTouchCoords.set(touch.identifier, coords);
            }
        }

        const allPointers = getAllActivePointers(e.touches);
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = getNormalizedCoords(touch.clientX, touch.clientY) || lastTouchCoords.get(touch.identifier);
            if (coords) {
                sendControl({
                    type: 'touch',
                    action: 'down',
                    id: touch.identifier,
                    x: coords.x,
                    y: coords.y,
                    pointers: allPointers,
                    mode: getMode()
                });
            }
        }
    }, { passive: false });

    viewportContainer.addEventListener('touchmove', (e) => {
        if (isDashboardEvent(e)) return;
        e.preventDefault();
        if (window.resetUiHideTimer) window.resetUiHideTimer();

        // 두 손가락 핀치 제스처 이동 처리
        if (e.touches.length >= 2) {
            const t0 = e.touches[0];
            const t1 = e.touches[1];
            const currDist = Math.hypot(t0.clientX - t1.clientX, t0.clientY - t1.clientY);
            const c0 = getNormalizedCoords(t0.clientX, t0.clientY);
            const c1 = getNormalizedCoords(t1.clientX, t1.clientY);
            if (c0 && c1) {
                pinchCenter = { x: (c0.x + c1.x) / 2, y: (c0.y + c1.y) / 2 };
            }

            if (!isPinching) {
                isPinching = true;
                pinchStartDist = currDist;
                lastPinchDist = currDist;
                lastPinchTime = Date.now();
                pinchTouchStartTime = Date.now();
                return;
            }

            const now = Date.now();
            const delta = currDist - lastPinchDist;

            // 14px 이상 거리 변화 및 110ms 쿨타임으로 안드로이드 접근성 제스처(100ms) 완결과 동기화
            if (Math.abs(delta) >= 14 && (now - lastPinchTime >= 110)) {
                const direction = delta > 0 ? 'in' : 'out';
                sendControl({
                    type: 'pinch_zoom',
                    direction: direction,
                    x: pinchCenter.x,
                    y: pinchCenter.y,
                    delta: delta,
                    mode: getMode()
                });
                lastPinchDist = currDist;
                lastPinchTime = now;
            }
            return; // 핀치 중 1손가락 스와이프 전송 방지!
        }

        if (isPinching) return;

        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = getNormalizedCoords(touch.clientX, touch.clientY);
            if (coords) {
                lastTouchCoords.set(touch.identifier, coords);
            }
        }

        const allPointers = getAllActivePointers(e.touches);
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = getNormalizedCoords(touch.clientX, touch.clientY) || lastTouchCoords.get(touch.identifier);
            if (coords) {
                sendControl({
                    type: 'touch',
                    action: 'move',
                    id: touch.identifier,
                    x: coords.x,
                    y: coords.y,
                    pointers: allPointers,
                    mode: getMode()
                });
            }
        }
    }, { passive: false });

    viewportContainer.addEventListener('touchend', (e) => {
        if (isDashboardEvent(e)) return;
        e.preventDefault();

        // 핀치 모드 종료 처리
        if (isPinching) {
            if (e.touches.length < 2) {
                const elapsed = Date.now() - pinchTouchStartTime;
                const distChange = Math.abs(lastPinchDist - pinchStartDist);
                isPinching = false;
                lastTouchCoords.clear();

                // 두 손가락 탭(Two-finger tap): 움직임 없이 280ms 내에 떼면 축소(Zoom Out) 처리!
                if (elapsed < 280 && distChange < 18) {
                    sendControl({
                        type: 'pinch_zoom',
                        direction: 'out',
                        x: pinchCenter.x,
                        y: pinchCenter.y,
                        mode: getMode()
                    });
                }
                return;
            }
            return;
        }

        const allPointers = getAllActivePointers(e.touches);
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = getNormalizedCoords(touch.clientX, touch.clientY) || lastTouchCoords.get(touch.identifier) || { x: 0.5, y: 0.5 };
            lastTouchCoords.delete(touch.identifier);
            sendControl({
                type: 'touch',
                action: 'up',
                id: touch.identifier,
                x: coords.x,
                y: coords.y,
                pointers: allPointers,
                mode: getMode()
            });
        }
    }, { passive: false });

    viewportContainer.addEventListener('touchcancel', (e) => {
        if (isDashboardEvent(e)) return;
        e.preventDefault();
        if (isPinching) {
            isPinching = false;
            lastTouchCoords.clear();
            return;
        }
        const allPointers = getAllActivePointers(e.touches);
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = getNormalizedCoords(touch.clientX, touch.clientY) || lastTouchCoords.get(touch.identifier) || { x: 0.5, y: 0.5 };
            lastTouchCoords.delete(touch.identifier);
            sendControl({
                type: 'touch',
                action: 'cancel',
                id: touch.identifier,
                x: coords.x,
                y: coords.y,
                pointers: allPointers,
                mode: getMode()
            });
        }
    }, { passive: false });

    // --- 마우스 휠 이벤트 (PC 브라우저 테스트 확대/축소 지원) ---
    viewportContainer.addEventListener('wheel', (e) => {
        if (isDashboardEvent(e)) return;
        e.preventDefault();
        const coords = getNormalizedCoords(e.clientX, e.clientY) || { x: 0.5, y: 0.5 };
        const direction = e.deltaY < 0 ? 'in' : 'out';
        sendControl({
            type: 'pinch_zoom',
            direction: direction,
            x: coords.x,
            y: coords.y,
            mode: getMode()
        });
    }, { passive: false });

    // --- 마우스 이벤트 폴백 (PC 브라우저 테스트 지원) ---
    viewportContainer.addEventListener('mousedown', (e) => {
        if (typeof window.ensureAudioContextUnlocked === 'function') {
            window.ensureAudioContextUnlocked();
        }
        if (isDashboardEvent(e)) return;
        isMouseDown = true;
        if (typeof window.flushVideoDelayQueue === 'function') {
            window.flushVideoDelayQueue();
        }
        const coords = getNormalizedCoords(e.clientX, e.clientY);
        if (coords) {
            sendControl({
                type: 'touch',
                action: 'down',
                id: 0,
                x: coords.x,
                y: coords.y,
                pointers: [{ id: 0, x: coords.x, y: coords.y }],
                mode: getMode()
            });
        }
    });

    window.addEventListener('mousemove', (e) => {
        if (!isMouseDown) return;
        const coords = getNormalizedCoords(e.clientX, e.clientY);
        if (coords) {
            sendControl({
                type: 'touch',
                action: 'move',
                id: 0,
                x: coords.x,
                y: coords.y,
                pointers: [{ id: 0, x: coords.x, y: coords.y }],
                mode: getMode()
            });
        }
    });

    window.addEventListener('mouseup', (e) => {
        if (isMouseDown) {
            isMouseDown = false;
            const coords = getNormalizedCoords(e.clientX, e.clientY) || { x: 0.5, y: 0.5 };
            sendControl({
                type: 'touch',
                action: 'up',
                id: 0,
                x: coords.x,
                y: coords.y,
                pointers: [],
                mode: getMode()
            });
        }
    });

    // 독 바 / 가상 내비게이션 키 (BACK, HOME, RECENTS)
    document.querySelectorAll('.dock-key-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            const key = btn.getAttribute('data-key');
            if (key) {
                sendControl({ type: 'key', key: key, mode: getMode() });
            }
        });
    });
})();
