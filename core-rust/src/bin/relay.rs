use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    http::{header, StatusCode},
    response::{Html, IntoResponse, Response},
    routing::get,
    Json, Router,
};
use futures_util::{SinkExt, StreamExt};
use serde_json::json;
use std::{net::SocketAddr, path::PathBuf, sync::Arc};
use tokio::sync::{broadcast, RwLock};
use tower_http::cors::CorsLayer;
use tracing::info;

use axum::extract::ConnectInfo;
use std::collections::HashMap;
use std::net::IpAddr;

const INDEX_HTML: &str = include_str!("../../../web/index.html");
const STYLE_CSS: &str = include_str!("../../../web/style.css");
const LEAFLET_CSS: &str = include_str!("../../../web/leaflet.css");
const PLAYER_JS: &str = include_str!("../../../web/player.js");
const TOUCH_JS: &str = include_str!("../../../web/touch.js");
const TRIPLOG_JS: &str = include_str!("../../../web/triplog.js");
const LEAFLET_JS: &str = include_str!("../../../web/leaflet.js");
const LOGO_PNG: &[u8] = include_bytes!("../../../web/logo.png");
const DOWNLOAD_HTML: &str = include_str!("../../../dist/index.html");
const TESLA_HTML: &str = include_str!("../../../dist/tesla.html");

#[derive(Clone, serde::Serialize, serde::Deserialize)]
struct HostRegistration {
    local_ip: String,
    port: u16,
    timestamp: u64,
}

#[derive(serde::Deserialize)]
struct RegisterHostPayload {
    local_ip: String,
    port: Option<u16>,
}

#[derive(Clone)]
struct RelayState {
    broadcast_tx: broadcast::Sender<Arc<Vec<u8>>>,
    control_tx: broadcast::Sender<String>,
    current_config: Arc<RwLock<Option<Arc<Vec<u8>>>>>,
    last_keyframe: Arc<RwLock<Option<Arc<Vec<u8>>>>>,
    hosts: Arc<RwLock<HashMap<IpAddr, HostRegistration>>>,
    apk_path: PathBuf,
}

async fn serve_mirror() -> impl IntoResponse {
    Html(INDEX_HTML)
}

async fn serve_download() -> impl IntoResponse {
    Html(DOWNLOAD_HTML)
}

async fn serve_tesla() -> impl IntoResponse {
    Html(TESLA_HTML)
}

async fn get_trips() -> impl IntoResponse {
    Json(json!([]))
}

async fn serve_css() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "text/css; charset=utf-8".parse().unwrap());
    (StatusCode::OK, headers, STYLE_CSS)
}

async fn serve_player() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    (StatusCode::OK, headers, PLAYER_JS)
}

async fn serve_touch() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    (StatusCode::OK, headers, TOUCH_JS)
}

async fn serve_triplog() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    (StatusCode::OK, headers, TRIPLOG_JS)
}

async fn serve_leaflet_css() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "text/css; charset=utf-8".parse().unwrap());
    (StatusCode::OK, headers, LEAFLET_CSS)
}

async fn serve_leaflet_js() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    (StatusCode::OK, headers, LEAFLET_JS)
}

async fn serve_logo() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "image/png".parse().unwrap());
    (StatusCode::OK, headers, LOGO_PNG)
}

async fn serve_apk(State(state): State<RelayState>) -> Response {
    match tokio::fs::read(&state.apk_path).await {
        Ok(bytes) => {
            let mut headers = axum::http::HeaderMap::new();
            headers.insert(header::CONTENT_TYPE, "application/vnd.android.package-archive".parse().unwrap());
            headers.insert(
                header::CONTENT_DISPOSITION,
                "attachment; filename=\"mplatMirror.apk\"".parse().unwrap(),
            );
            (StatusCode::OK, headers, bytes).into_response()
        }
        Err(_) => (StatusCode::NOT_FOUND, "APK not found").into_response(),
    }
}

async fn serve_info() -> impl IntoResponse {
    Json(json!({
        "app": "mplat Mirror Relay",
        "version": "1.2.0",
        "domain": "mdm.mplat.store:8088",
        "status": "online"
    }))
}

async fn register_host(
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    State(state): State<RelayState>,
    Json(payload): Json<RegisterHostPayload>,
) -> impl IntoResponse {
    let caller_ip = addr.ip();
    let reg = HostRegistration {
        local_ip: payload.local_ip.clone(),
        port: payload.port.unwrap_or(9999),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
    };
    info!("Registered host from {}: local_ip={}:{}", caller_ip, reg.local_ip, reg.port);
    let mut lock = state.hosts.write().await;
    lock.insert(caller_ip, reg);
    (StatusCode::OK, Json(json!({"status": "ok", "caller_ip": caller_ip.to_string()})))
}

async fn discover_host(
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    State(state): State<RelayState>,
) -> impl IntoResponse {
    let caller_ip = addr.ip();
    let lock = state.hosts.read().await;
    if let Some(host) = lock.get(&caller_ip) {
        Json(json!({
            "found": true,
            "local_ip": host.local_ip,
            "port": host.port,
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
                "url": format!("https://{}:{}", host.local_ip, host.port),
                "fallback": true
            }))
        } else {
            Json(json!({
                "found": false,
                "caller_ip": caller_ip.to_string(),
            }))
        }
    }
}

// 테슬라 브라우저 (시청자) WebSocket 핸들러
async fn ws_handler(ws: WebSocketUpgrade, State(state): State<RelayState>) -> Response {
    ws.on_upgrade(|socket| handle_tesla_viewer(socket, state))
}

async fn handle_tesla_viewer(socket: WebSocket, state: RelayState) {
    info!("Tesla browser viewer connected");
    let (mut sender, mut receiver) = socket.split();

    // 초기 해상도 설정이 캐시되어 있으면 즉시 전달
    if let Some(config_bytes) = state.current_config.read().await.clone() {
        let _ = sender.send(Message::Binary((*config_bytes).clone())).await;
    }

    // 최신 키프레임(SPS/PPS + IDR)이 캐시되어 있으면 즉시 전달하여 1ms 즉시 렌더링
    if let Some(keyframe_bytes) = state.last_keyframe.read().await.clone() {
        let _ = sender.send(Message::Binary((*keyframe_bytes).clone())).await;
    }

    let mut broadcast_rx = state.broadcast_tx.subscribe();
    let control_tx = state.control_tx.clone();

    // 폰 화면 스트림 -> 테슬라 화면
    let send_task = tokio::spawn(async move {
        while let Ok(packet) = broadcast_rx.recv().await {
            if let Err(_) = sender.send(Message::Binary((*packet).clone())).await {
                break;
            }
        }
    });

    // 테슬라 터치 이벤트 -> 폰으로 전달
    let recv_task = tokio::spawn(async move {
        while let Some(Ok(msg)) = receiver.next().await {
            if let Message::Text(text) = msg {
                let _ = control_tx.send(text);
            }
        }
    });

    tokio::select! {
        _ = send_task => {},
        _ = recv_task => {},
    }
    info!("Tesla browser viewer disconnected");
}

// 안드로이드 폰 (화면 송출자) WebSocket 핸들러
async fn publish_handler(ws: WebSocketUpgrade, State(state): State<RelayState>) -> Response {
    ws.on_upgrade(|socket| handle_phone_publisher(socket, state))
}

async fn handle_phone_publisher(socket: WebSocket, state: RelayState) {
    info!("Phone publisher connected to relay");
    let (mut sender, mut receiver) = socket.split();

    let mut control_rx = state.control_tx.subscribe();

    // 테슬라의 터치 명령을 폰으로 전송
    let send_task = tokio::spawn(async move {
        while let Ok(touch_event) = control_rx.recv().await {
            if let Err(_) = sender.send(Message::Text(touch_event)).await {
                break;
            }
        }
    });

    // 폰의 비디오/오디오/설정 스트림을 테슬라 뷰어들에게 브로드캐스트
    let broadcast_tx = state.broadcast_tx.clone();
    let current_config = state.current_config.clone();
    let last_keyframe = state.last_keyframe.clone();

    let recv_task = tokio::spawn(async move {
        while let Some(Ok(msg)) = receiver.next().await {
            match msg {
                Message::Binary(bin) => {
                    if !bin.is_empty() {
                        let packet_type = bin[0];
                        if packet_type == 0x03 {
                            let mut lock = current_config.write().await;
                            *lock = Some(Arc::new(bin.clone()));
                        } else if packet_type == 0x01 && bin.len() > 5 {
                            // H.264 NAL type 7(SPS) or 5(IDR Keyframe)
                            let is_keyframe = bin[1..].windows(4).any(|w| {
                                w == [0, 0, 0, 1] && ((w[3] & 0x1F) == 7 || (w[3] & 0x1F) == 5)
                            });
                            if is_keyframe {
                                let mut lock = last_keyframe.write().await;
                                *lock = Some(Arc::new(bin.clone()));
                            }
                        }
                        let _ = broadcast_tx.send(Arc::new(bin));
                    }
                }
                _ => {}
            }
        }
    });

    tokio::select! {
        _ = send_task => {},
        _ = recv_task => {},
    }
    info!("Phone publisher disconnected from relay");
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt::init();

    let (broadcast_tx, _) = broadcast::channel(512);
    let (control_tx, _) = broadcast::channel(64);

    let state = RelayState {
        broadcast_tx,
        control_tx,
        current_config: Arc::new(RwLock::new(None)),
        last_keyframe: Arc::new(RwLock::new(None)),
        hosts: Arc::new(RwLock::new(HashMap::new())),
        apk_path: PathBuf::from("/home/profavor/mMirror/dist/mplatMirror.apk"),
    };

    let app = Router::new()
        .route("/", get(serve_download))
        .route("/mirror", get(serve_mirror))
        .route("/tesla", get(serve_tesla))
        .route("/tesla.html", get(serve_tesla))
        .route("/download", get(serve_download))
        .route("/mplatMirror.apk", get(serve_apk))
        .route("/api/register_host", axum::routing::post(register_host))
        .route("/api/discover", get(discover_host))
        .route("/style.css", get(serve_css))
        .route("/player.js", get(serve_player))
        .route("/touch.js", get(serve_touch))
        .route("/triplog.js", get(serve_triplog))
        .route("/leaflet.css", get(serve_leaflet_css))
        .route("/leaflet.js", get(serve_leaflet_js))
        .route("/logo.png", get(serve_logo))
        .route("/app_logo.png", get(serve_logo))
        .route("/api/info", get(serve_info))
        .route("/api/trips", get(get_trips))
        .route("/ws", get(ws_handler))
        .route("/publish", get(publish_handler))
        .layer(CorsLayer::permissive())
        .with_state(state);

    let cert_path = "/home/profavor/.letsencrypt/live/mdm.mplat.store/fullchain.pem";
    let key_path = "/home/profavor/.letsencrypt/live/mdm.mplat.store/privkey.pem";

    let tls_config = axum_server::tls_rustls::RustlsConfig::from_pem_file(cert_path, key_path)
        .await
        .map_err(|e| format!("Failed to load certs: {}", e))?;

    let addr: SocketAddr = ([0, 0, 0, 0], 8088).into();
    println!("🚀 mplat Mirror Official Relay Server running on https://0.0.0.0:8088 (mdm.mplat.store)");

    axum_server::bind_rustls(addr, tls_config)
        .serve(app.into_make_service_with_connect_info::<SocketAddr>())
        .await?;

    Ok(())
}
