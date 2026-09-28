use std::net::SocketAddr;
use std::sync::{Arc, Mutex, RwLock};
use tokio::sync::broadcast;
use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        Json, State,
    },
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Router,
};
use tower_http::cors::CorsLayer;
use futures_util::{SinkExt, StreamExt};
use tracing::{info, warn};

use crate::protocol::{ControlMessage, DeviceConfig, GpsData, TripRecord, PathPoint, PKT_TYPE_AUDIO, PKT_TYPE_VIDEO};
use crate::web_assets::{INDEX_HTML, PLAYER_JS, STYLE_CSS, TOUCH_JS, TRIPLOG_JS, LEAFLET_CSS, LEAFLET_JS, LOGO_PNG};


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

pub fn log_android(prio: i32, msg: &str) {
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

pub struct MirrorServer {
    preferred_port: u16,
    bound_port: Arc<RwLock<u16>>,
    state: AppState,
    shutdown_tx: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
    tls_handles: Arc<Mutex<Vec<axum_server::Handle<std::net::SocketAddr>>>>,
}

impl MirrorServer {
    pub fn new(port: u16) -> Self {
        let (broadcast_tx, _) = broadcast::channel(128);

        // 샘플 주행일지 초기화 (테슬라 브라우저 지도 렌더링 확인용)
        let sample_trips = vec![
            TripRecord {
                id: "trip-001".to_string(),
                start_time: "2026-09-28T09:15:00".to_string(),
                end_time: "2026-09-28T09:42:00".to_string(),
                distance_km: 18.4,
                duration_sec: 1620,
                avg_speed_kmh: 42.5,
                max_speed_kmh: 88.0,
                path: vec![
                    PathPoint { lat: 37.5665, lng: 126.9780, speed: 35.0, time: 100 },
                    PathPoint { lat: 37.5620, lng: 126.9820, speed: 45.0, time: 200 },
                    PathPoint { lat: 37.5550, lng: 126.9910, speed: 65.0, time: 300 },
                    PathPoint { lat: 37.5410, lng: 127.0050, speed: 82.0, time: 400 },
                    PathPoint { lat: 37.5280, lng: 127.0280, speed: 40.0, time: 500 },
                ],
            }
        ];

        Self {
            preferred_port: port,
            bound_port: Arc::new(RwLock::new(0)),
            state: AppState {
                broadcast_tx,
                current_config: Arc::new(RwLock::new(None)),
                control_callback: Arc::new(Mutex::new(None)),
                trips: Arc::new(RwLock::new(sample_trips)),
            },
            shutdown_tx: Mutex::new(None),
            tls_handles: Arc::new(Mutex::new(Vec::new())),
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

    pub fn send_video(&self, nal_data: &[u8]) {
        if self.state.broadcast_tx.receiver_count() == 0 {
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

        // 후보 포트 목록 (우선 지정 포트 -> 8080 -> 8082 -> 8888 -> 8081 -> 7070 -> 9090)
        let candidates = vec![self.preferred_port, 8080, 8082, 8888, 8081, 7070, 9090];
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
            .route("/style.css", get(serve_css))
            .route("/player.js", get(serve_player))
            .route("/touch.js", get(serve_touch))
            .route("/triplog.js", get(serve_triplog))
            .route("/leaflet.css", get(serve_leaflet_css))
            .route("/leaflet.js", get(serve_leaflet_js))
            .route("/logo.png", get(serve_logo))
            .route("/api/info", get(serve_info))
            .route("/api/trips", get(get_trips).post(post_trip))
            .route("/ws", get(ws_handler))
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

        // 테슬라디스플레이(7777)/보조(7678) 포트 다중 바인딩 (일반 HTTP)
        let extra_ports = vec![7777, 7678];
        for extra_port in extra_ports {
            if actual_port != extra_port {
                if let Ok(sec_std) = create_socket_listener(&([0, 0, 0, 0], extra_port).into()) {
                    if let Ok(sec_listener) = tokio::net::TcpListener::from_std(sec_std) {
                        let app_sec = app.clone();
                        tokio::spawn(async move {
                            log_android(4, &format!("mMirror axum multi-port server running on http://0.0.0.0:{}", extra_port));
                            let _ = axum::serve(sec_listener, app_sec).await;
                        });
                    }
                }
            }
        }

        // 테슬라 브라우저 HTTPS(TLS) 전용 서버 가동 (포트: 9999 [테슬라미러 호환], 8443, 7679)
        let app_https = app.clone();
        let tls_handles = self.tls_handles.clone();
        tokio::spawn(async move {
            let subject_alt_names = vec![
                "teslamirror.net".to_string(),
                "*.teslamirror.net".to_string(),
                "td9.cc".to_string(),
                "*.td9.cc".to_string(),
                "td7.cc".to_string(),
                "*.td7.cc".to_string(),
                "100.99.9.9".to_string(),
                "7.7.7.7".to_string(),
                "3.3.3.3".to_string(),
                "10.254.1.1".to_string(),
                "192.168.43.1".to_string(),
                "127.0.0.1".to_string(),
                "localhost".to_string(),
                "tesla.local".to_string(),
            ];
            match rcgen::generate_simple_self_signed(subject_alt_names) {
                Ok(certified_key) => {
                    let cert_pem = certified_key.cert.pem();
                    let key_pem = certified_key.signing_key.serialize_pem();
                    match axum_server::tls_rustls::RustlsConfig::from_pem(
                        cert_pem.as_bytes().to_vec(),
                        key_pem.as_bytes().to_vec(),
                    ).await {
                        Ok(tls_config) => {
                            let https_ports = vec![9999, 8443, 7679];
                            for https_port in https_ports {
                                let addr: SocketAddr = ([0, 0, 0, 0], https_port).into();
                                let handle = axum_server::Handle::new();
                                if let Ok(mut lock) = tls_handles.lock() {
                                    lock.push(handle.clone());
                                }
                                let app_inst = app_https.clone();
                                let config_inst = tls_config.clone();
                                tokio::spawn(async move {
                                    log_android(4, &format!("mMirror axum HTTPS server running on https://0.0.0.0:{}", https_port));
                                    let _ = axum_server::bind_rustls(addr, config_inst)
                                        .handle(handle)
                                        .serve(app_inst.into_make_service())
                                        .await;
                                });
                            }
                        }
                        Err(e) => {
                            log_android(6, &format!("Failed to create RustlsConfig: {}", e));
                        }
                    }
                }
                Err(e) => {
                    log_android(6, &format!("Failed to generate self-signed cert: {}", e));
                }
            }
        });

        Ok(actual_port)
    }

    pub fn stop(&self) {
        if let Ok(mut lock) = self.shutdown_tx.lock() {
            if let Some(tx) = lock.take() {
                let _ = tx.send(());
            }
        }
        if let Ok(mut handles) = self.tls_handles.lock() {
            for handle in handles.drain(..) {
                handle.shutdown();
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

// HTTP Static Handler
async fn serve_index() -> impl IntoResponse {
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "text/html; charset=utf-8".parse().unwrap());
    headers.insert(header::CACHE_CONTROL, "no-cache".parse().unwrap());
    (StatusCode::OK, headers, INDEX_HTML)
}

async fn serve_status() -> impl IntoResponse {
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/json".parse().unwrap());
    headers.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*".parse().unwrap());
    (StatusCode::OK, headers, "{\"status\":\"ok\",\"version\":\"1.2.0\",\"device\":\"mplat-mirror\"}")
}

async fn serve_css() -> impl IntoResponse {
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "text/css; charset=utf-8".parse().unwrap());
    (StatusCode::OK, headers, STYLE_CSS)
}

async fn serve_player() -> impl IntoResponse {
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    (StatusCode::OK, headers, PLAYER_JS)
}

async fn serve_touch() -> impl IntoResponse {
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    (StatusCode::OK, headers, TOUCH_JS)
}

async fn serve_triplog() -> impl IntoResponse {
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    (StatusCode::OK, headers, TRIPLOG_JS)
}

async fn serve_leaflet_css() -> impl IntoResponse {
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "text/css; charset=utf-8".parse().unwrap());
    headers.insert(header::CACHE_CONTROL, "public, max-age=86400".parse().unwrap());
    (StatusCode::OK, headers, LEAFLET_CSS)
}

async fn serve_leaflet_js() -> impl IntoResponse {
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/javascript; charset=utf-8".parse().unwrap());
    headers.insert(header::CACHE_CONTROL, "public, max-age=86400".parse().unwrap());
    (StatusCode::OK, headers, LEAFLET_JS)
}

async fn serve_logo() -> impl IntoResponse {

    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "image/png".parse().unwrap());
    headers.insert(header::CACHE_CONTROL, "public, max-age=86400".parse().unwrap());
    (StatusCode::OK, headers, LOGO_PNG)
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
        version: "1.0.0",
        license: "Apache-2.0",
        copyright: "Copyright (c) 2026 mplat. All rights reserved.",
    };
    (StatusCode::OK, Json(info))
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

// WebSocket 핸들러
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
