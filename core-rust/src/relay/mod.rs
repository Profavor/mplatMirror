//! 릴레이 서버 모듈 — WebRTC 시그널링, 호스트 디스커버리, 레거시 WebSocket 릴레이
//!
//! VPS(mdm.mplat.store:8088)에서 구동되는 공인 HTTPS 릴레이 서버의 비즈니스 로직.
//! 실제 비디오/오디오 스트림은 WebRTC P2P로 로컬 Wi-Fi 직결 (0MB 모바일 데이터).

pub mod signaling;
pub mod discovery;
pub mod handlers;

use std::collections::HashMap;
use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::{broadcast, RwLock};

/// 릴레이 서버 공유 상태
#[derive(Clone)]
pub struct RelayState {
    /// 레거시 WebSocket 릴레이: 폰→뷰어 바이너리 브로드캐스트
    pub broadcast_tx: broadcast::Sender<Arc<Vec<u8>>>,
    /// 레거시 WebSocket 릴레이: 뷰어→폰 제어 메시지
    pub control_tx: broadcast::Sender<String>,
    /// 캐시된 DeviceConfig (해상도 정보)
    pub current_config: Arc<RwLock<Option<Arc<Vec<u8>>>>>,
    /// 캐시된 최신 IDR 키프레임 (즉시 렌더링용)
    pub last_keyframe: Arc<RwLock<Option<Arc<Vec<u8>>>>>,
    /// 공인 IP → 로컬 핫스팟 IP 매핑 (호스트 디스커버리)
    pub hosts: Arc<RwLock<HashMap<IpAddr, HostRegistration>>>,
    /// WebRTC 시그널링 룸 (SDP/ICE 교환)
    pub webrtc_rooms: Arc<RwLock<HashMap<String, RoomChannels>>>,
    /// APK 파일 경로
    pub apk_path: PathBuf,
}

/// 호스트 등록 정보 (안드로이드 앱 → 릴레이 서버)
#[derive(Clone, serde::Serialize, serde::Deserialize)]
pub struct HostRegistration {
    pub local_ip: String,
    pub port: u16,
    pub timestamp: u64,
    #[serde(default)]
    pub app_version: Option<String>,
}

/// 호스트 등록 요청 페이로드
#[derive(serde::Deserialize)]
pub struct RegisterHostPayload {
    pub local_ip: String,
    pub port: Option<u16>,
    pub app_version: Option<String>,
}

/// WebRTC 시그널링 룸 채널
#[derive(Clone)]
pub struct RoomChannels {
    pub to_publisher: broadcast::Sender<String>,
    pub to_viewer: broadcast::Sender<String>,
    pub cached_offer: Arc<RwLock<Option<String>>>,
    pub cached_candidates: Arc<RwLock<Vec<String>>>,
    pub cached_config: Arc<RwLock<Option<String>>>,
}

/// WebRTC 시그널링 쿼리 파라미터
#[derive(serde::Deserialize)]
pub struct WebrtcSignalQuery {
    pub role: Option<String>,
    pub room: Option<String>,
}
