use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        ConnectInfo, Query, Request, State,
    },
    http::{header, StatusCode},
    middleware::{self, Next},
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
pub struct RoomChannels {
    pub to_publisher: broadcast::Sender<String>,
    pub to_viewer: broadcast::Sender<String>,
    pub last_offer: Arc<RwLock<Option<String>>>,
}

#[derive(Clone)]
struct RelayState {
    broadcast_tx: broadcast::Sender<Arc<Vec<u8>>>,
    control_tx: broadcast::Sender<String>,
    current_config: Arc<RwLock<Option<Arc<Vec<u8>>>>>,
    last_keyframe: Arc<RwLock<Option<Arc<Vec<u8>>>>>,
    hosts: Arc<RwLock<HashMap<IpAddr, HostRegistration>>>,
    webrtc_rooms: Arc<RwLock<HashMap<String, RoomChannels>>>,
    apk_path: PathBuf,
}

async fn serve_mirror() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate".parse().unwrap());
    (StatusCode::OK, headers, Html(INDEX_HTML))
}

async fn serve_download() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate".parse().unwrap());
    (StatusCode::OK, headers, Html(DOWNLOAD_HTML))
}

async fn serve_tesla() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate".parse().unwrap());
    (StatusCode::OK, headers, Html(INDEX_HTML))
}

async fn get_trips() -> impl IntoResponse {
    Json(json!([]))
}

async fn serve_css() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "text/css; charset=utf-8".parse().unwrap());
    headers.insert(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate".parse().unwrap());
    (StatusCode::OK, headers, STYLE_CSS)
}

async fn serve_player() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    headers.insert(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate".parse().unwrap());
    (StatusCode::OK, headers, PLAYER_JS)
}

async fn serve_touch() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    headers.insert(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate".parse().unwrap());
    (StatusCode::OK, headers, TOUCH_JS)
}

async fn serve_triplog() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    headers.insert(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate".parse().unwrap());
    (StatusCode::OK, headers, TRIPLOG_JS)
}

async fn serve_leaflet_css() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "text/css; charset=utf-8".parse().unwrap());
    headers.insert(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate".parse().unwrap());
    (StatusCode::OK, headers, LEAFLET_CSS)
}

async fn serve_leaflet_js() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    headers.insert(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate".parse().unwrap());
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
    println!("👀 [RELAY] Tesla/Tablet viewer connected to /ws");
    let (mut sender, mut receiver) = socket.split();

    // 초기 해상도 설정이 캐시되어 있으면 즉시 전달
    if let Some(config_bytes) = state.current_config.read().await.clone() {
        println!("📤 [RELAY] Sending cached config to viewer (len={})", config_bytes.len());
        let _ = sender.send(Message::Binary((*config_bytes).clone())).await;
    }

    // 최신 키프레임(SPS/PPS + IDR)이 캐시되어 있으면 즉시 전달하여 1ms 즉시 렌더링
    if let Some(keyframe_bytes) = state.last_keyframe.read().await.clone() {
        println!("🔑 [RELAY] Sending cached keyframe to viewer (len={})", keyframe_bytes.len());
        let _ = sender.send(Message::Binary((*keyframe_bytes).clone())).await;
    }

    // 캐시된 키프레임이 없을 때만 폰에 키프레임 요청 (불필요한 IDR 폭주 방지)
    if state.last_keyframe.read().await.is_none() {
        let _ = state.control_tx.send("{\"type\":\"request_keyframe\"}".to_string());
    }

    let mut broadcast_rx = state.broadcast_tx.subscribe();
    let send_control_tx = state.control_tx.clone();
    let recv_control_tx = state.control_tx.clone();

    // 폰 화면 스트림 -> 테슬라 화면 (초저지연: 뒤처질 경우 낡은 프레임 즉시 드롭하고 실시간으로 스냅)
    let send_task = tokio::spawn(async move {
        loop {
            match broadcast_rx.recv().await {
                Ok(packet) => {
                    if let Err(_) = sender.send(Message::Binary((*packet).clone())).await {
                        break;
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                    println!("⚡ [RELAY] Viewer lagged, skipped {} stale frames to preserve 0ms latency!", skipped);
                    let _ = send_control_tx.send("{\"type\":\"request_keyframe\"}".to_string());
                }
                Err(_) => break,
            }
        }
    });

    // 테슬라 터치 이벤트 -> 폰으로 전달
    let recv_task = tokio::spawn(async move {
        while let Some(Ok(msg)) = receiver.next().await {
            if let Message::Text(text) = msg {
                let _ = recv_control_tx.send(text);
            }
        }
    });

    tokio::select! {
        _ = send_task => {},
        _ = recv_task => {},
    }
    println!("👋 [RELAY] Tesla/Tablet viewer disconnected from /ws");
}

// 안드로이드 폰 (화면 송출자) WebSocket 핸들러
async fn publish_handler(ws: WebSocketUpgrade, State(state): State<RelayState>) -> Response {
    ws.on_upgrade(|socket| handle_phone_publisher(socket, state))
}

async fn handle_phone_publisher(socket: WebSocket, state: RelayState) {
    println!("📱 [RELAY] Phone publisher connected to /publish!");
    let (mut sender, mut receiver) = socket.split();

    let mut control_rx = state.control_tx.subscribe();

    // 테슬라의 터치 및 키프레임 요청을 폰으로 전송
    let send_task = tokio::spawn(async move {
        while let Ok(msg) = control_rx.recv().await {
            if let Err(_) = sender.send(Message::Text(msg)).await {
                break;
            }
        }
    });

    // 폰의 비디오/오디오/설정 스트림을 테슬라 뷰어들에게 브로드캐스트
    let broadcast_tx = state.broadcast_tx.clone();
    let current_config = state.current_config.clone();
    let last_keyframe = state.last_keyframe.clone();

    let recv_task = tokio::spawn(async move {
        let mut frame_count = 0u64;
        while let Some(Ok(msg)) = receiver.next().await {
            match msg {
                Message::Binary(bin) => {
                    if !bin.is_empty() {
                        let packet_type = bin[0];
                        if packet_type == 0x03 {
                            println!("⚙️ [RELAY] Received DeviceConfig from phone (len={})", bin.len());
                            let mut lock = current_config.write().await;
                            *lock = Some(Arc::new(bin.clone()));
                        } else if packet_type == 0x01 && bin.len() > 5 {
                            // H.264 NAL type 7(SPS) or 5(IDR Keyframe)
                            let is_keyframe = bin[1..].windows(5).any(|w| {
                                w[0..4] == [0, 0, 0, 1] && ((w[4] & 0x1F) == 7 || (w[4] & 0x1F) == 5)
                            }) || bin[1..].windows(4).any(|w| {
                                w[0..3] == [0, 0, 1] && ((w[3] & 0x1F) == 7 || (w[3] & 0x1F) == 5)
                            });
                            if is_keyframe {
                                println!("🔑 [RELAY] Cached IDR Keyframe from phone (len={})", bin.len());
                                let mut lock = last_keyframe.write().await;
                                *lock = Some(Arc::new(bin.clone()));
                            }
                            frame_count += 1;
                            if frame_count % 300 == 1 {
                                println!("🎬 [RELAY] Relayed {} video frames from phone to viewers", frame_count);
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
    println!("📱 [RELAY] Phone publisher disconnected from /publish");
}

#[derive(serde::Deserialize)]
struct WebrtcSignalQuery {
    role: Option<String>,
    room: Option<String>,
}

async fn webrtc_signal_handler(
    ws: WebSocketUpgrade,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    Query(query): Query<WebrtcSignalQuery>,
    State(state): State<RelayState>,
) -> Response {
    let role = query.role.unwrap_or_else(|| "viewer".to_string());
    let room = query.room.unwrap_or_else(|| "default".to_string());
    ws.on_upgrade(move |socket| handle_webrtc_signal(socket, state, role, room))
}

async fn handle_webrtc_signal(
    socket: WebSocket,
    state: RelayState,
    role: String,
    room: String,
) {
    println!("📡 [WEBRTC SIGNAL] Connected: role={} room={}", role, room);

    let (to_publisher, to_viewer, last_offer) = {
        let mut rooms = state.webrtc_rooms.write().await;
        let entry = rooms.entry(room.clone()).or_insert_with(|| {
            let (to_pub, _) = broadcast::channel(128);
            let (to_view, _) = broadcast::channel(128);
            RoomChannels {
                to_publisher: to_pub,
                to_viewer: to_view,
                last_offer: Arc::new(RwLock::new(None)),
            }
        });
        (entry.to_publisher.clone(), entry.to_viewer.clone(), entry.last_offer.clone())
    };

    let (mut sender, mut receiver) = socket.split();

    if role == "publisher" {
        let mut rx = to_publisher.subscribe();
        let send_task = tokio::spawn(async move {
            while let Ok(msg) = rx.recv().await {
                if let Err(_) = sender.send(Message::Text(msg)).await {
                    break;
                }
            }
        });

        let forward_tx = to_viewer.clone();
        let last_offer_clone = last_offer.clone();
        let recv_task = tokio::spawn(async move {
            while let Some(Ok(msg)) = receiver.next().await {
                if let Message::Text(text) = msg {
                    if text.contains("\"type\":\"offer\"") || text.contains("\"offer\"") {
                        println!("📥 [SIGNAL] Cached SDP Offer from publisher (len={})", text.len());
                        let mut lock = last_offer_clone.write().await;
                        *lock = Some(text.clone());
                    } else if text.contains("\"type\":\"candidate\"") {
                        println!("📡 [SIGNAL] Forwarding Candidate from publisher");
                    }
                    let _ = forward_tx.send(text);
                }
            }
        });

        tokio::select! {
            _ = send_task => {},
            _ = recv_task => {},
        }
    } else {
        // 시청자(테슬라 브라우저) 접속 시: 스마트폰 퍼블리셔에 뷰어 준비 완료 알림 -> 퍼블리셔가 즉시 최신 Offer 생성 전송
        println!("📢 [SIGNAL] Viewer connected, requesting fresh SDP Offer from publisher");
        let _ = to_publisher.send("{\"type\":\"ready\"}".to_string());

        let mut rx = to_viewer.subscribe();
        let send_task = tokio::spawn(async move {
            while let Ok(msg) = rx.recv().await {
                if let Err(_) = sender.send(Message::Text(msg)).await {
                    break;
                }
            }
        });

        let forward_tx = to_publisher.clone();
        let recv_task = tokio::spawn(async move {
            while let Some(Ok(msg)) = receiver.next().await {
                if let Message::Text(text) = msg {
                    if text.contains("\"type\":\"answer\"") {
                        println!("📥 [SIGNAL] Forwarding SDP Answer from viewer (len={})", text.len());
                    } else if text.contains("\"type\":\"candidate\"") {
                        println!("📡 [SIGNAL] Forwarding Candidate from viewer");
                    }
                    let _ = forward_tx.send(text);
                }
            }
        });

        tokio::select! {
            _ = send_task => {},
            _ = recv_task => {},
        }
    }

    println!("📡 [WEBRTC SIGNAL] Disconnected: role={} room={}", role, room);
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt::init();

    let (broadcast_tx, _) = broadcast::channel(16);
    let (control_tx, _) = broadcast::channel(64);

    let state = RelayState {
        broadcast_tx,
        control_tx,
        current_config: Arc::new(RwLock::new(None)),
        last_keyframe: Arc::new(RwLock::new(None)),
        hosts: Arc::new(RwLock::new(HashMap::new())),
        webrtc_rooms: Arc::new(RwLock::new(HashMap::new())),
        apk_path: PathBuf::from("/home/profavor/mMirror/dist/mplatMirror.apk"),
    };

async fn log_request_middleware(
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    req: Request,
    next: Next,
) -> Response {
    let method = req.method().to_string();
    let uri = req.uri().to_string();
    println!("⚡ [RELAY REQUEST] {} {} from {}", method, uri, addr);
    next.run(req).await
}

    let base_app = Router::new()
        .route("/", get(serve_mirror))
        .route("/mirror", get(serve_mirror))
        .route("/tesla", get(serve_mirror))
        .route("/tesla.html", get(serve_mirror))
        .route("/download", get(serve_download))
        .route("/mplatMirror.apk", get(serve_apk))
        .route("/dist/mplatMirror.apk", get(serve_apk))
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
        .route("/webrtc/signal", get(webrtc_signal_handler))
        .layer(middleware::from_fn(log_request_middleware))
        .layer(CorsLayer::permissive())
        .with_state(state);

    let (cert_path, key_path) = if std::path::Path::new("/home/profavor/.letsencrypt/live/mplat.store/fullchain.pem").exists() {
        ("/home/profavor/.letsencrypt/live/mplat.store/fullchain.pem", "/home/profavor/.letsencrypt/live/mplat.store/privkey.pem")
    } else {
        ("/home/profavor/.letsencrypt/live/mdm.mplat.store/fullchain.pem", "/home/profavor/.letsencrypt/live/mdm.mplat.store/privkey.pem")
    };

    let tls_config = axum_server::tls_rustls::RustlsConfig::from_pem_file(cert_path, key_path)
        .await
        .map_err(|e| format!("Failed to load certs: {}", e))?;

    let app_8088 = base_app.clone();
    let tls_8088 = tls_config.clone();
    let addr_8088: SocketAddr = ([0, 0, 0, 0], 8088).into();
    tokio::spawn(async move {
        println!("🚀 mplat Mirror Web Player & Relay Server running on https://0.0.0.0:8088 (mdm.mplat.store:8088)");
        if let Err(e) = axum_server::bind_rustls(addr_8088, tls_8088)
            .serve(app_8088.into_make_service_with_connect_info::<SocketAddr>())
            .await
        {
            eprintln!("Port 8088 server error: {}", e);
        }
    });

    let app_9999 = base_app;
    let addr_9999: SocketAddr = ([0, 0, 0, 0], 9999).into();
    println!("🚀 mplat Mirror Official Port 9999 HTTPS Server running on https://0.0.0.0:9999 (mdm.mplat.store:9999)");

    axum_server::bind_rustls(addr_9999, tls_config)
        .serve(app_9999.into_make_service_with_connect_info::<SocketAddr>())
        .await?;

    Ok(())
}
