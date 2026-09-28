// mMirror Tesla Touch & Gesture Controller
(function() {
    'use strict';

    const canvas = document.getElementById('videoCanvas');
    const btnNavBack = document.getElementById('btnNavBack');
    const btnNavHome = document.getElementById('btnNavHome');
    const btnNavRecents = document.getElementById('btnNavRecents');
    const btnNavSplit = document.getElementById('btnNavSplit');
    const btnRotate = document.getElementById('btnRotate');

    let isMouseDown = false;

    // 터치/마우스 좌표를 캔버스 기준 0.0 ~ 1.0 정규화 좌표로 변환
    function getNormalizedCoords(clientX, clientY) {
        const rect = canvas.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) return null;

        // 캔버스 내 상대 위치 계산
        const x = clientX - rect.left;
        const y = clientY - rect.top;

        // 0.0 ~ 1.0 범위로 클램핑
        const normX = Math.max(0.0, Math.min(1.0, x / rect.width));
        let normY = Math.max(0.0, Math.min(1.0, y / rect.height));

        // 티맵 분할 크롭 모드 활성화 시 터치 좌표를 상단 50% 영역으로 자동 스케일링
        if (window.isCropMode) {
            const cropRatio = window.cropRatio || 0.5;
            normY = normY * cropRatio;
        }

        return { x: normX, y: normY };
    }

    // --- 터치 이벤트 핸들러 (테슬라 디스플레이 전용) ---
    canvas.addEventListener('touchstart', (e) => {
        e.preventDefault();
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = getNormalizedCoords(touch.clientX, touch.clientY);
            if (coords) {
                window.mMirror.sendControl({
                    type: 'touch',
                    action: 'down',
                    id: touch.identifier,
                    x: coords.x,
                    y: coords.y
                });
            }
        }
    }, { passive: false });

    canvas.addEventListener('touchmove', (e) => {
        e.preventDefault();
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = getNormalizedCoords(touch.clientX, touch.clientY);
            if (coords) {
                window.mMirror.sendControl({
                    type: 'touch',
                    action: 'move',
                    id: touch.identifier,
                    x: coords.x,
                    y: coords.y
                });
            }
        }
    }, { passive: false });

    canvas.addEventListener('touchend', (e) => {
        e.preventDefault();
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            window.mMirror.sendControl({
                type: 'touch',
                action: 'up',
                id: touch.identifier
            });
        }
    }, { passive: false });

    canvas.addEventListener('touchcancel', (e) => {
        e.preventDefault();
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            window.mMirror.sendControl({
                type: 'touch',
                action: 'up',
                id: touch.identifier
            });
        }
    }, { passive: false });

    // --- 마우스 이벤트 폴백 (PC 브라우저 테스트 지원) ---
    canvas.addEventListener('mousedown', (e) => {
        isMouseDown = true;
        const coords = getNormalizedCoords(e.clientX, e.clientY);
        if (coords) {
            window.mMirror.sendControl({
                type: 'touch',
                action: 'down',
                id: 0,
                x: coords.x,
                y: coords.y
            });
        }
    });

    window.addEventListener('mousemove', (e) => {
        if (!isMouseDown) return;
        const coords = getNormalizedCoords(e.clientX, e.clientY);
        if (coords) {
            window.mMirror.sendControl({
                type: 'touch',
                action: 'move',
                id: 0,
                x: coords.x,
                y: coords.y
            });
        }
    });

    window.addEventListener('mouseup', () => {
        if (isMouseDown) {
            isMouseDown = false;
            window.mMirror.sendControl({
                type: 'touch',
                action: 'up',
                id: 0
            });
        }
    });

    // --- 가상 내비게이션 바 버튼 제어 ---
    btnNavBack.addEventListener('click', (e) => {
        e.stopPropagation();
        window.mMirror.sendControl({ type: 'key', key: 'BACK' });
    });

    btnNavHome.addEventListener('click', (e) => {
        e.stopPropagation();
        window.mMirror.sendControl({ type: 'key', key: 'HOME' });
    });

    btnNavRecents.addEventListener('click', (e) => {
        e.stopPropagation();
        window.mMirror.sendControl({ type: 'key', key: 'RECENTS' });
    });

    btnNavSplit.addEventListener('click', (e) => {
        e.stopPropagation();
        window.mMirror.sendControl({ type: 'key', key: 'SPLIT_SCREEN' });
    });

    btnRotate.addEventListener('click', (e) => {
        e.stopPropagation();
        window.mMirror.sendControl({ type: 'command', cmd: 'ROTATE' });
    });

    // 키보드 이벤트 (PC 연결 또는 테슬라 물리 키보드 연결 시)
    window.addEventListener('keydown', (e) => {
        if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

        if (e.key === 'Escape' || e.key === 'BrowserBack') {
            window.mMirror.sendControl({ type: 'key', key: 'BACK' });
        } else if (e.key === 'Home') {
            window.mMirror.sendControl({ type: 'key', key: 'HOME' });
        }
    });
})();
