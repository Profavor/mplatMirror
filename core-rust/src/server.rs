use std::net::SocketAddr;
use std::sync::{Arc, Mutex, RwLock};
use tokio::sync::broadcast;
use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        Json, Request, State,
    },
    http::{header, HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::get,
    Router,
};
use tower_http::cors::CorsLayer;
use futures_util::{SinkExt, StreamExt};
use tracing::{info, warn};

use crate::protocol::{ControlMessage, DeviceConfig, GpsData, TripRecord, PKT_TYPE_AUDIO, PKT_TYPE_VIDEO};
use crate::web_assets;

pub type ControlCallback = Arc<dyn Fn(ControlMessage) + Send + Sync>;

#[derive(Clone)]
pub struct AppState {
    pub broadcast_tx: broadcast::Sender<Arc<Vec<u8>>>,
    pub current_config: Arc<RwLock<Option<DeviceConfig>>>,
    pub control_callback: Arc<Mutex<Option<ControlCallback>>>,
    pub trips: Arc<RwLock<Vec<TripRecord>>>,
}

#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_write(
        prio: std::os::raw::c_int,
        tag: *const std::os::raw::c_char,
        text: *const std::os::raw::c_char,
    ) -> std::os::raw::c_int;
}

static RUST_LOGS: Mutex<Vec<String>> = Mutex::new(Vec::new());

pub fn log_android(prio: i32, msg: &str) {
    if let Ok(mut logs) = RUST_LOGS.lock() {
        if logs.len() > 300 {
            logs.drain(0..50);
        }
        let prio_str = match prio {
            3 => "DEBUG",
            4 => "INFO",
            5 => "WARN",
            6 => "ERROR",
            _ => "LOG",
        };
        logs.push(format!("[{}] {}", prio_str, msg));
    }

    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        if let (Ok(tag_c), Ok(msg_c)) = (CString::new("mMirrorRust"), CString::new(msg)) {
            unsafe {
                __android_log_write(prio, tag_c.as_ptr(), msg_c.as_ptr());
            }
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        eprintln!("[mMirrorRust] (level {}) {}", prio, msg);
    }
}

pub fn get_rust_logs() -> String {
    if let Ok(logs) = RUST_LOGS.lock() {
        logs.join("\n")
    } else {
        String::new()
    }
}

pub struct MirrorServer {
    preferred_port: u16,
    bound_port: Arc<RwLock<u16>>,
    state: AppState,
    shutdown_tx: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
}

impl MirrorServer {
    pub fn new(port: u16) -> Self {
        let (broadcast_tx, _) = broadcast::channel(128);

        Self {
            preferred_port: port,
            bound_port: Arc::new(RwLock::new(0)),
            state: AppState {
                broadcast_tx,
                current_config: Arc::new(RwLock::new(None)),
                control_callback: Arc::new(Mutex::new(None)),
                trips: Arc::new(RwLock::new(Vec::new())),
            },
            shutdown_tx: Mutex::new(None),
        }
    }

    pub fn bound_port(&self) -> u16 {
        *self.bound_port.read().unwrap()
    }

    pub fn set_control_callback<F>(&self, callback: F)
    where
        F: Fn(ControlMessage) + Send + Sync + 'static,
    {
        let cb = Arc::new(callback);
        let mut lock = self.state.control_callback.lock().unwrap();
        *lock = Some(cb);
    }

    pub fn update_config(&self, config: DeviceConfig) {
        let bytes = Arc::new(config.to_bytes());
        let _ = self.state.broadcast_tx.send(bytes);
        if let Ok(mut lock) = self.state.current_config.write() {
            *lock = Some(config);
        }
    }

    pub fn has_clients(&self) -> bool {
        self.state.broadcast_tx.receiver_count() > 0
    }

    pub fn send_video(&self, nal_data: &[u8]) {
        if !self.has_clients() {
            return;
        }
        let mut packet = Vec::with_capacity(1 + nal_data.len());
        packet.push(PKT_TYPE_VIDEO);
        packet.extend_from_slice(nal_data);
        let _ = self.state.broadcast_tx.send(Arc::new(packet));
    }

    pub fn send_audio(&self, pcm_data: &[u8]) {
        if self.state.broadcast_tx.receiver_count() == 0 {
            return;
        }
        let mut packet = Vec::with_capacity(1 + pcm_data.len());
        packet.push(PKT_TYPE_AUDIO);
        packet.extend_from_slice(pcm_data);
        let _ = self.state.broadcast_tx.send(Arc::new(packet));
    }

    pub fn send_gps(&self, gps: GpsData) {
        if self.state.broadcast_tx.receiver_count() == 0 {
            return;
        }
        let bytes = Arc::new(gps.to_bytes());
        let _ = self.state.broadcast_tx.send(bytes);
    }

    pub fn add_trip(&self, trip: TripRecord) {
        if let Ok(mut lock) = self.state.trips.write() {
            lock.insert(0, trip);
        }
    }

    pub fn start(&self) -> Result<u16, String> {
        let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel();
        if let Ok(mut lock) = self.shutdown_tx.lock() {
            *lock = Some(shutdown_tx);
        }

        let app_state = self.state.clone();

        // 후보 포트 목록 (우선 지정 포트 -> 8282 -> 8284 -> 8888 -> 8080 -> 7070 -> 9090)
        let candidates = vec![self.preferred_port, 8282, 8284, 8888, 8080, 7070, 9090];
        let (std_listener, actual_port) = bind_with_fallback(&candidates)?;

        if let Ok(mut lock) = self.bound_port.write() {
            *lock = actual_port;
        }

        let listener = tokio::net::TcpListener::from_std(std_listener)
            .map_err(|e| format!("Failed to convert std listener to tokio: {}", e))?;

        log_android(4, &format!("mMirror HTTP & WebSocket Server successfully bound to 0.0.0.0:{}", actual_port));

        let app = Router::new()
            .route("/", get(serve_index))
            .route("/status", get(serve_status))
            .route("/style.css", get(web_assets::serve_css))
            .route("/player.js", get(web_assets::serve_player))
            .route("/touch.js", get(web_assets::serve_touch))
            .route("/triplog.js", get(web_assets::serve_triplog))
            .route("/firebase-config.js", get(web_assets::serve_firebase_config))
            .route("/leaflet.css", get(web_assets::serve_leaflet_css))
            .route("/leaflet.js", get(web_assets::serve_leaflet_js))
            .route("/logo.png", get(web_assets::serve_logo))
            .route("/api/info", get(serve_info))
            .route("/api/logs", get(serve_logs))
            .route("/api/trips", get(get_trips).post(post_trip))
            .route("/ws", get(ws_handler))
            .layer(middleware::from_fn(log_request_middleware))
            .layer(CorsLayer::permissive())
            .with_state(app_state);

        let app_main = app.clone();
        tokio::spawn(async move {
            log_android(4, &format!("mMirror axum serving on http://0.0.0.0:{}", actual_port));

            axum::serve(listener, app_main)
                .with_graceful_shutdown(async move {
                    let _ = shutdown_rx.await;
                    log_android(4, "mMirror Server shutting down");
                })
                .await
                .unwrap_or_else(|e| {
                    log_android(6, &format!("Server error: {}", e));
                });
        });

        // 8282 단일 표준 포트만 바인딩 (불필요한 레거시 포트 7777, 7678, 9999 등 완전 차단)
        log_android(4, &format!("✓ mMirror 단일 로컬 HTTP 포트(8282)만 바인딩 (불필요 포트 완전 폐쇄): http://0.0.0.0:{}", actual_port));

        Ok(actual_port)
    }

    pub fn stop(&self) {
        if let Ok(mut lock) = self.shutdown_tx.lock() {
            if let Some(tx) = lock.take() {
                let _ = tx.send(());
            }
        }
        if let Ok(mut lock) = self.bound_port.write() {
            *lock = 0;
        }
    }
}

fn bind_with_fallback(ports: &[u16]) -> Result<(std::net::TcpListener, u16), String> {
    let mut tried = Vec::new();
    let mut last_err = String::new();

    for &port in ports {
        if port == 0 || tried.contains(&port) {
            continue;
        }
        tried.push(port);

        let addr: SocketAddr = ([0, 0, 0, 0], port).into();
        match create_socket_listener(&addr) {
            Ok(listener) => {
                log_android(4, &format!("Successfully bound TCP port {}", port));
                return Ok((listener, port));
            }
            Err(e) => {
                log_android(5, &format!("Port {} unavailable ({}). Trying next candidate...", port, e));
                last_err = e;
            }
        }
    }

    Err(format!("Could not bind to any candidate port: {}", last_err))
}

fn create_socket_listener(addr: &SocketAddr) -> Result<std::net::TcpListener, String> {
    use socket2::{Domain, Protocol, Socket, Type};

    let domain = if addr.is_ipv6() { Domain::IPV6 } else { Domain::IPV4 };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))
        .map_err(|e| format!("Socket::new error: {}", e))?;

    socket.set_reuse_address(true)
        .map_err(|e| format!("set_reuse_address error: {}", e))?;

    socket.set_nonblocking(true)
        .map_err(|e| format!("set_nonblocking error: {}", e))?;

    socket.bind(&((*addr).into()))
        .map_err(|e| format!("bind error: {}", e))?;

    socket.listen(1024)
        .map_err(|e| format!("listen error: {}", e))?;

    Ok(socket.into())
}

async fn log_request_middleware(
    req: Request,
    next: Next,
) -> Response {
    let method = req.method().to_string();
    let uri = req.uri().to_string();
    let host = req.headers().get("host").and_then(|h| h.to_str().ok()).unwrap_or("-").to_string();
    log_android(4, &format!("🌐 [HTTP REQUEST] {} {} (Host: {})", method, uri, host));
    let res = next.run(req).await;
    log_android(4, &format!("📤 [HTTP RESPONSE] {} {} -> Status {}", method, uri, res.status().as_u16()));
    res
}

// ─── 로컬 서버 전용 핸들러 ───

async fn serve_index() -> impl IntoResponse {
    web_assets::serve_text(web_assets::INDEX_HTML, "text/html; charset=utf-8", web_assets::CACHE_NO_CACHE)
}

async fn serve_status() -> impl IntoResponse {
    let json = format!(
        r#"{{"status":"ok","version":"{}","device":"mplat-mirror"}}"#,
        env!("CARGO_PKG_VERSION")
    );
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/json".parse().unwrap());
    headers.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*".parse().unwrap());
    (StatusCode::OK, headers, json)
}

#[derive(serde::Serialize)]
struct AppInfo {
    name: &'static str,
    version: &'static str,
    license: &'static str,
    copyright: &'static str,
}

async fn serve_info() -> impl IntoResponse {
    let info = AppInfo {
        name: "mplat Mirror",
        version: env!("CARGO_PKG_VERSION"),
        license: "Apache-2.0",
        copyright: "Copyright (c) 2026 mplat. All rights reserved.",
    };
    (StatusCode::OK, Json(info))
}

async fn serve_logs() -> impl IntoResponse {
    let logs = get_rust_logs();
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "text/plain; charset=utf-8".parse().unwrap());
    headers.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*".parse().unwrap());
    (StatusCode::OK, headers, logs)
}

async fn get_trips(State(state): State<AppState>) -> impl IntoResponse {
    let list = {
        state.trips.read().unwrap().clone()
    };
    (StatusCode::OK, Json(list))
}

async fn post_trip(
    State(state): State<AppState>,
    Json(record): Json<TripRecord>,
) -> impl IntoResponse {
    if let Ok(mut lock) = state.trips.write() {
        lock.insert(0, record);
    }
    StatusCode::CREATED
}

// ─── WebSocket 핸들러 ───

async fn ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
) -> Response {
    ws.on_upgrade(|socket| handle_socket(socket, state))
}

async fn handle_socket(socket: WebSocket, state: AppState) {
    info!("New Tesla Browser WebSocket connection established");
    let (mut sender, mut receiver) = socket.split();

    // 초기 해상도 정보 전송 (await 전에 락 해제)
    let initial_config_bytes = {
        state.current_config
            .read()
            .ok()
            .and_then(|guard| guard.as_ref().map(|c| c.to_bytes()))
    };

    if let Some(bytes) = initial_config_bytes {
        if let Err(e) = sender.send(Message::Binary(bytes)).await {
            warn!("Failed to send initial config to client: {}", e);
            return;
        }
    }

    let mut broadcast_rx = state.broadcast_tx.subscribe();

    // 송신 태스크 (비디오/오디오 스트림 -> 테슬라 브라우저)
    let send_task = tokio::spawn(async move {
        while let Ok(packet) = broadcast_rx.recv().await {
            if let Err(e) = sender.send(Message::Binary((*packet).clone())).await {
                warn!("WebSocket send error: {}", e);
                break;
            }
        }
    });

    // 수신 태스크 (테슬라 브라우저 터치/키 이벤트 -> 안드로이드 제어 콜백)
    let recv_task = tokio::spawn(async move {
        while let Some(Ok(msg)) = receiver.next().await {
            match msg {
                Message::Text(text) => {
                    if let Ok(control_msg) = serde_json::from_str::<ControlMessage>(&text) {
                        let cb_opt = {
                            if let Ok(lock) = state.control_callback.lock() {
                                lock.clone()
                            } else {
                                None
                            }
                        };
                        if let Some(cb) = cb_opt {
                            cb(control_msg);
                        }
                    } else {
                        warn!("Received invalid JSON control message: {}", text);
                    }
                }
                Message::Close(_) => {
                    info!("Client initiated WebSocket close");
                    break;
                }
                _ => {}
            }
        }
    });

    // 둘 중 하나가 끝나면 소켓 정리
    tokio::select! {
        _ = send_task => {},
        _ = recv_task => {},
    }

    info!("Tesla Browser WebSocket connection closed");
}
