// mMirror Tesla Driving Log & Map Controller
(function() {
    'use strict';

    const btnTripLog = document.getElementById('btnTripLog');
    const tripLogModal = document.getElementById('tripLogModal');
    const btnCloseModal = document.getElementById('btnCloseTripModal');
    const btnBottomClose = document.getElementById('btnBottomCloseTripModal');
    const hudSpeed = document.getElementById('hudSpeed');
    const hudDistance = document.getElementById('hudDistance');
    const hudDuration = document.getElementById('hudDuration');
    const tripListContainer = document.getElementById('tripList');

    let currentLat = 37.5665;
    let currentLng = 126.9780;
    let map = null;
    let liveMarker = null;
    let livePolyline = null;
    let historyPolyline = null;
    let startMarker = null;
    let endMarker = null;
    let livePathCoords = [];
    let isModalOpen = false;

    const btnHeaderTripLog = document.getElementById('btnHeaderTripLog');

    // 모달 닫기 공통 핸들러
    function closeModal() {
        isModalOpen = false;
        if (tripLogModal) {
            tripLogModal.classList.add('hidden');
            tripLogModal.style.setProperty('display', 'none', 'important');
        }
    }

    function openModal() {
        isModalOpen = true;
        if (tripLogModal) {
            tripLogModal.classList.remove('hidden');
            tripLogModal.style.setProperty('display', 'flex', 'important');
        }
        setTimeout(() => {
            initMapIfNeeded();
            if (map) map.invalidateSize();
        }, 50);
        setTimeout(() => {
            if (map) map.invalidateSize();
        }, 200);
        setTimeout(() => {
            if (map) map.invalidateSize();
        }, 500);
        loadTripHistory();
    }

    function toggleModal() {
        if (isModalOpen) {
            closeModal();
        } else {
            openModal();
        }
    }

    // 전역 인터페이스 노출 (도크 앱 팔레트 및 외부 호출용)
    window.openTripLogModal = openModal;
    window.closeTripLogModal = closeModal;
    window.toggleTripLogModal = toggleModal;

    // 모달 토글 버튼 바인딩
    ['click', 'pointerdown'].forEach(evt => {
        if (btnTripLog) {
            btnTripLog.addEventListener(evt, (e) => {
                e.stopPropagation();
                e.preventDefault();
                toggleModal();
            });
        }
        if (btnHeaderTripLog) {
            btnHeaderTripLog.addEventListener(evt, (e) => {
                e.stopPropagation();
                e.preventDefault();
                toggleModal();
            });
        }
    });

    // 닫기 버튼 멀티 이벤트 바인딩 (터치스크린 즉시 반응 보장)
    ['click', 'pointerdown', 'touchend'].forEach(evt => {
        if (btnCloseModal) {
            btnCloseModal.addEventListener(evt, (e) => {
                e.stopPropagation();
                e.preventDefault();
                closeModal();
            }, { passive: false });
        }
        if (btnBottomClose) {
            btnBottomClose.addEventListener(evt, (e) => {
                e.stopPropagation();
                e.preventDefault();
                closeModal();
            }, { passive: false });
        }
    });

    // 모달 바깥 배경 터치 시 닫기
    if (tripLogModal) {
        ['click', 'pointerdown'].forEach(evt => {
            tripLogModal.addEventListener(evt, (e) => {
                if (e.target === tripLogModal) {
                    e.stopPropagation();
                    e.preventDefault();
                    closeModal();
                }
            });
        });
    }

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && isModalOpen) {
            closeModal();
        }
    });


    // Leaflet 기반 경량 고화질 지도 초기화 (OpenStreetMap 타일)
    function initMapIfNeeded() {
        if (map) {
            setTimeout(() => map.invalidateSize(), 150);
            return;
        }

        const mapContainer = document.getElementById('mapContainer');
        if (!mapContainer || typeof L === 'undefined') {
            console.warn('Leaflet library not loaded yet or container missing');
            if (mapContainer && typeof L === 'undefined') {
                mapContainer.innerHTML = '<div style="color:#aaa;display:flex;align-items:center;justify-content:center;height:100%;">지도를 불러오는 중...</div>';
            }
            return;
        }

        map = L.map('mapContainer', {
            center: [currentLat, currentLng],
            zoom: 16,
            zoomControl: false,
            attributionControl: false
        });

        // OpenStreetMap 고화질 타일 레이어 (오프라인 0MB 프록시 대응)
        const tileLayer = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            subdomains: ['a', 'b', 'c']
        });
        tileLayer.on('tileerror', () => {
            // 오프라인 상태에서도 에러 없이 레이더 HUD 그리드 위에 궤적과 마커 정상 표시
        });
        tileLayer.addTo(map);


        // 줌 컨트롤 우측 하단 배치
        L.control.zoom({ position: 'bottomright' }).addTo(map);

        // 실시간 차량 마커 생성
        const carIcon = L.divIcon({
            className: 'car-live-marker',
            html: '<div class="marker-pulse"></div><div class="marker-core">🚘</div>',
            iconSize: [36, 36],
            iconAnchor: [18, 18]
        });

        liveMarker = L.marker([currentLat, currentLng], { icon: carIcon }).addTo(map);

        // 실시간 궤적 라인
        livePolyline = L.polyline([], {
            color: '#3498db',
            weight: 5,
            opacity: 0.85,
            lineJoin: 'round'
        }).addTo(map);
    }

    // GPS 패킷 실시간 수신 처리
    window.mMirrorGps = {
        onGpsUpdate: function(data) {
            if (!data) return;

            // 1. HUD 대시보드 갱신
            if (hudSpeed) {
                hudSpeed.textContent = `${Math.round(data.speed_kmh || 0)}`;
            }
            if (hudDistance) {
                const km = ((data.trip_distance_meters || 0) / 1000).toFixed(1);
                hudDistance.textContent = `${km} km`;
            }
            if (hudDuration) {
                const totalSec = data.duration_seconds || 0;
                const m = Math.floor(totalSec / 60);
                const s = totalSec % 60;
                hudDuration.textContent = `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
            }

            // 2. 지도 위치 갱신
            currentLat = data.lat;
            currentLng = data.lng;

            if (map && liveMarker) {
                const newLatLng = [data.lat, data.lng];
                liveMarker.setLatLng(newLatLng);

                if (data.speed_kmh > 1.0) {
                    livePathCoords.push(newLatLng);
                    livePolyline.setLatLngs(livePathCoords);
                    map.panTo(newLatLng, { animate: true, duration: 0.8 });
                }
            }
        }
    };

    // 주행일지 목록 조회
    async function loadTripHistory() {
        if (!tripListContainer) return;
        tripListContainer.innerHTML = '<div class="loading-state">주행 기록 불러오는 중...</div>';

        try {
            const res = await fetch('/api/trips');
            if (!res.ok) throw new Error('Failed to fetch trips');
            const trips = await res.json();

            if (!trips || trips.length === 0) {
                tripListContainer.innerHTML = '<div class="empty-state">아직 저장된 주행 기록이 없습니다.<br>주행을 시작하면 자동으로 기록됩니다.</div>';
                return;
            }

            tripListContainer.innerHTML = '';
            trips.forEach((trip) => {
                const card = document.createElement('div');
                card.className = 'trip-card';
                card.innerHTML = `
                    <div class="trip-card-header">
                        <span class="trip-date">${formatDateTime(trip.start_time)}</span>
                        <span class="trip-distance">${trip.distance_km.toFixed(1)} km</span>
                    </div>
                    <div class="trip-card-details">
                        <span>⏱ ${formatDuration(trip.duration_sec)}</span>
                        <span>🚀 평균 ${Math.round(trip.avg_speed_kmh)} km/h</span>
                        <span>⚡ 최고 ${Math.round(trip.max_speed_kmh)} km/h</span>
                    </div>
                `;
                card.addEventListener('click', () => {
                    displayTripHistoryOnMap(trip);
                });
                tripListContainer.appendChild(card);
            });
        } catch (e) {
            tripListContainer.innerHTML = '<div class="error-state">주행일지를 불러올 수 없습니다.</div>';
            console.error('Error loading trips:', e);
        }
    }

    // 선택된 과거 주행일지 지도에 궤적 그리기
    function displayTripHistoryOnMap(trip) {
        if (!map || !trip.path || trip.path.length === 0) return;

        // 기존 히스토리 레이어 정리
        if (historyPolyline) map.removeLayer(historyPolyline);
        if (startMarker) map.removeLayer(startMarker);
        if (endMarker) map.removeLayer(endMarker);

        const latLngs = trip.path.map(p => [p.lat, p.lng]);

        // 출발 마커 (초록)
        startMarker = L.circleMarker(latLngs[0], {
            radius: 8,
            fillColor: '#2ecc71',
            color: '#fff',
            weight: 2,
            opacity: 1,
            fillOpacity: 0.9
        }).bindPopup('출발 지점').addTo(map);

        // 도착 마커 (빨강)
        endMarker = L.circleMarker(latLngs[latLngs.length - 1], {
            radius: 8,
            fillColor: '#e74c3c',
            color: '#fff',
            weight: 2,
            opacity: 1,
            fillOpacity: 0.9
        }).bindPopup('도착 지점').addTo(map);

        // 주행 궤적 선
        historyPolyline = L.polyline(latLngs, {
            color: '#e67e22',
            weight: 6,
            opacity: 0.9,
            lineJoin: 'round'
        }).addTo(map);

        map.fitBounds(historyPolyline.getBounds(), { padding: [40, 40] });
    }

    function formatDateTime(isoString) {
        if (!isoString) return '--';
        const d = new Date(isoString);
        return `${d.getMonth() + 1}월 ${d.getDate()}일 ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
    }

    function formatDuration(sec) {
        if (!sec) return '0분';
        const m = Math.floor(sec / 60);
        if (m < 60) return `${m}분`;
        const h = Math.floor(m / 60);
        return `${h}시간 ${m % 60}분`;
    }
})();
