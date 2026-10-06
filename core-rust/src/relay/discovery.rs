//! 호스트 디스커버리 핸들러
//!
//! 안드로이드 앱이 공인 IP로 릴레이 서버에 로컬 핫스팟 IP를 등록하면,
//! 동일 공인 IP의 테슬라 브라우저가 로컬 IP를 조회하여 직접 접속 가능.

use axum::{
    extract::{ConnectInfo, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde_json::json;
use std::net::SocketAddr;

use super::{HostRegistration, RegisterHostPayload, RelayState};

pub async fn register_host(
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    State(state): State<RelayState>,
    Json(payload): Json<RegisterHostPayload>,
) -> impl IntoResponse {
    let caller_ip = addr.ip();
    let server_ver = env!("CARGO_PKG_VERSION");
    let app_ver = payload.app_version.clone();
    let version_matched = app_ver.as_deref() == Some(server_ver);
    let reg = HostRegistration {
        local_ip: payload.local_ip.clone(),
        port: payload.port.unwrap_or(8088),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        app_version: app_ver.clone(),
    };
    println!("Registered host from {}: local_ip={}:{}, app_ver={:?}", caller_ip, reg.local_ip, reg.port, reg.app_version);
    let mut lock = state.hosts.write().await;
    lock.insert(caller_ip, reg);
    (StatusCode::OK, Json(json!({
        "status": "ok",
        "caller_ip": caller_ip.to_string(),
        "server_version": server_ver,
        "app_version": app_ver,
        "version_matched": version_matched,
        "play_store_url": "https://play.google.com/store/apps/details?id=io.mmirror",
        "apk_url": "https://mdm.mplat.store:8088/dist/mplatMirror.apk"
    })))
}

pub async fn discover_host(
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    State(state): State<RelayState>,
) -> impl IntoResponse {
    let caller_ip = addr.ip();
    let server_ver = env!("CARGO_PKG_VERSION");
    let lock = state.hosts.read().await;
    if let Some(host) = lock.get(&caller_ip) {
        Json(json!({
            "found": true,
            "local_ip": host.local_ip,
            "port": host.port,
            "app_version": host.app_version,
            "server_version": server_ver,
            "version_matched": host.app_version.as_deref() == Some(server_ver),
            "url": format!("https://{}:{}", host.local_ip, host.port),
        }))
    } else {
        // 일치하는 공인 IP가 없을 경우 최근 5분 이내에 등록된 최신 호스트 fallback 제공
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let fallback = lock.values().filter(|h| now.saturating_sub(h.timestamp) < 300).last();
        if let Some(host) = fallback {
            Json(json!({
                "found": true,
                "local_ip": host.local_ip,
                "port": host.port,
                "app_version": host.app_version,
                "server_version": server_ver,
                "version_matched": host.app_version.as_deref() == Some(server_ver),
                "url": format!("https://{}:{}", host.local_ip, host.port),
                "fallback": true
            }))
        } else {
            Json(json!({
                "found": false,
                "caller_ip": caller_ip.to_string(),
                "server_version": server_ver,
            }))
        }
    }
}
