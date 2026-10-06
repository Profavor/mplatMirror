//! mplat Mirror 릴레이 서버 (VPS 바이너리)
//!
//! mdm.mplat.store:8088 에서 구동.
//! 역할: (1) 최신 APK 배포 및 웹 플레이어 서빙
//!       (2) WebRTC SDP/ICE 시그널링 중계 (실제 스트림은 로컬 P2P)
//!       (3) 호스트 디스커버리 (공인 IP → 로컬 핫스팟 IP 매핑)
//!       (4) 레거시 WebSocket 릴레이 (원격 디버깅 전용)

use axum::{
    extract::{ConnectInfo, Request, State},
    http::{header, StatusCode},
    middleware::{self, Next},
    response::{Html, IntoResponse, Response},
    routing::get,
    Json, Router,
};
use serde_json::json;
use std::{collections::HashMap, net::SocketAddr, path::PathBuf, sync::Arc};
use tokio::sync::{broadcast, RwLock};
use tower_http::cors::CorsLayer;

use mmirror_core::relay::{self, RelayState};
use mmirror_core::web_assets;

// ─── 릴레이 전용 핸들러 (정적 페이지) ───

async fn serve_mirror() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CACHE_CONTROL, web_assets::CACHE_NO_STORE.parse().unwrap());
    (StatusCode::OK, headers, Html(web_assets::INDEX_HTML))
}

async fn serve_download() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CACHE_CONTROL, web_assets::CACHE_NO_STORE.parse().unwrap());
    (StatusCode::OK, headers, Html(web_assets::DOWNLOAD_HTML))
}

async fn serve_privacy() -> impl IntoResponse {
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(header::CACHE_CONTROL, web_assets::CACHE_NO_STORE.parse().unwrap());
    (StatusCode::OK, headers, Html(web_assets::PRIVACY_HTML))
}

async fn serve_apk_qr() -> impl IntoResponse {
    web_assets::serve_text(web_assets::APK_QR_SVG, "image/svg+xml", web_assets::CACHE_PUBLIC_1DAY)
}

async fn get_trips() -> impl IntoResponse {
    Json(json!([]))
}

fn serve_file_with_range(
    bytes: Vec<u8>,
    content_type: &'static str,
    filename: &'static str,
    req_headers: &axum::http::HeaderMap,
) -> Response {
    let total_len = bytes.len();
    let mut resp_headers = axum::http::HeaderMap::new();
    resp_headers.insert(header::CONTENT_TYPE, content_type.parse().unwrap());
    resp_headers.insert(
        header::CONTENT_DISPOSITION,
        format!("attachment; filename=\"{}\"", filename).parse().unwrap(),
    );
    resp_headers.insert(header::ACCEPT_RANGES, "bytes".parse().unwrap());
    resp_headers.insert(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate".parse().unwrap());
    resp_headers.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*".parse().unwrap());
    resp_headers.insert(
        header::ACCESS_CONTROL_EXPOSE_HEADERS,
        "Content-Range, Accept-Ranges, Content-Length, Content-Disposition".parse().unwrap(),
    );

    // Range 헤더 처리 (Android DownloadManager & 브라우저 멀티청크 다운로드 지원)
    if let Some(range_val) = req_headers.get(header::RANGE).and_then(|v| v.to_str().ok()) {
        if let Some(range_str) = range_val.strip_prefix("bytes=") {
            let parts: Vec<&str> = range_str.split('-').collect();
            let start = parts.first().and_then(|s| s.parse::<usize>().ok()).unwrap_or(0);
            let end = parts.get(1).and_then(|s| {
                if s.is_empty() {
                    None
                } else {
                    s.parse::<usize>().ok()
                }
            }).unwrap_or(total_len.saturating_sub(1));

            if start >= total_len || start > end {
                resp_headers.insert(
                    header::CONTENT_RANGE,
                    format!("bytes */{}", total_len).parse().unwrap(),
                );
                return (StatusCode::RANGE_NOT_SATISFIABLE, resp_headers, "").into_response();
            }

            let end_clamped = end.min(total_len.saturating_sub(1));
            let slice_len = end_clamped - start + 1;
            resp_headers.insert(
                header::CONTENT_RANGE,
                format!("bytes {}-{}/{}", start, end_clamped, total_len).parse().unwrap(),
            );
            resp_headers.insert(header::CONTENT_LENGTH, slice_len.to_string().parse().unwrap());

            let slice = bytes[start..=end_clamped].to_vec();
            return (StatusCode::PARTIAL_CONTENT, resp_headers, slice).into_response();
        }
    }

    resp_headers.insert(header::CONTENT_LENGTH, total_len.to_string().parse().unwrap());
    (StatusCode::OK, resp_headers, bytes).into_response()
}

async fn serve_apk(
    State(state): State<RelayState>,
    headers: axum::http::HeaderMap,
) -> Response {
    let fallback_paths = [
        state.apk_path.clone(),
        PathBuf::from("/home/profavor/mMirror/dist/mplatMirror.apk"),
        PathBuf::from("/home/profavor/mMirror/dist/mMirror.apk"),
        PathBuf::from("/home/profavor/mMirror/mplatMirror.apk"),
        PathBuf::from("/home/profavor/mMirror/mMirror.apk"),
        PathBuf::from("/home/profavor/mMirror/android/app/build/outputs/apk/release/app-release.apk"),
    ];

    for path in &fallback_paths {
        if let Ok(bytes) = tokio::fs::read(path).await {
            return serve_file_with_range(
                bytes,
                "application/vnd.android.package-archive",
                "mplatMirror.apk",
                &headers,
            );
        }
    }
    (StatusCode::NOT_FOUND, "APK not found").into_response()
}

async fn serve_aab(headers: axum::http::HeaderMap) -> Response {
    let fallback_paths = [
        PathBuf::from("/home/profavor/mMirror/dist/mplatMirror.aab"),
        PathBuf::from("/home/profavor/mMirror/mplatMirror.aab"),
        PathBuf::from("/home/profavor/mMirror/android/app/build/outputs/bundle/release/app-release.aab"),
    ];

    for path in &fallback_paths {
        if let Ok(bytes) = tokio::fs::read(path).await {
            return serve_file_with_range(
                bytes,
                "application/octet-stream",
                "mplatMirror.aab",
                &headers,
            );
        }
    }
    (StatusCode::NOT_FOUND, "AAB not found").into_response()
}

async fn serve_info() -> impl IntoResponse {
    Json(json!({
        "app": "mplat Mirror Relay",
        "version": env!("CARGO_PKG_VERSION"),
        "domain": "mplat-mirror.web.app",
        "web_url": "https://mplat-mirror.web.app",
        "relay_endpoint": "https://mdm.mplat.store:8088",
        "status": "online",
        "play_store_url": "https://play.google.com/store/apps/details?id=io.mmirror",
        "apk_url": "https://mdm.mplat.store:8088/dist/mplatMirror.apk"
    }))
}

async fn log_request_middleware(
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    req: Request,
    next: Next,
) -> Response {
    let method = req.method().to_string();
    let uri = req.uri().to_string();
    let user_agent = req.headers().get(header::USER_AGENT)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("-");
    let range = req.headers().get(header::RANGE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("-");
    println!("⚡ [RELAY REQUEST] {} {} from {} (UA: {}, Range: {})", method, uri, addr, user_agent, range);
    next.run(req).await
}

// ─── 진입점 ───

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

    let base_app = Router::new()
        // 페이지 라우트
        .route("/", get(serve_mirror))
        .route("/mirror", get(serve_mirror))
        .route("/tesla", get(serve_mirror))
        .route("/tesla.html", get(serve_mirror))
        .route("/download", get(serve_download))
        .route("/download.html", get(serve_download))
        .route("/privacy", get(serve_privacy))
        .route("/privacy.html", get(serve_privacy))
        .route("/intro", get(serve_download))
        .route("/guide", get(serve_download))
        // APK 다운로드
        .route("/mplatMirror.apk", get(serve_apk))
        .route("/dist/mplatMirror.apk", get(serve_apk))
        .route("/mMirror.apk", get(serve_apk))
        .route("/dist/mMirror.apk", get(serve_apk))
        .route("/app-release.apk", get(serve_apk))
        .route("/download/mplatMirror.apk", get(serve_apk))
        // AAB (App Bundle) 다운로드
        .route("/mplatMirror.aab", get(serve_aab))
        .route("/dist/mplatMirror.aab", get(serve_aab))
        .route("/app-release.aab", get(serve_aab))
        .route("/download/mplatMirror.aab", get(serve_aab))
        // API
        .route("/api/register_host", axum::routing::post(relay::discovery::register_host))
        .route("/api/discover", get(relay::discovery::discover_host))
        .route("/api/info", get(serve_info))
        .route("/api/trips", get(get_trips))
        // 공용 정적 에셋 (web_assets 모듈)
        .route("/style.css", get(web_assets::serve_css))
        .route("/player.js", get(web_assets::serve_player))
        .route("/touch.js", get(web_assets::serve_touch))
        .route("/triplog.js", get(web_assets::serve_triplog))
        .route("/leaflet.css", get(web_assets::serve_leaflet_css))
        .route("/leaflet.js", get(web_assets::serve_leaflet_js))
        .route("/firebase-config.js", get(web_assets::serve_firebase_config))
        .route("/logo.png", get(web_assets::serve_logo))
        .route("/app_logo.png", get(web_assets::serve_logo))
        .route("/apk_qr.svg", get(serve_apk_qr))
        .route("/dist/apk_qr.svg", get(serve_apk_qr))
        // WebSocket & WebRTC
        .route("/ws", get(relay::handlers::ws_handler))
        .route("/publish", get(relay::handlers::publish_handler))
        .route("/webrtc/signal", get(relay::signaling::webrtc_signal_handler))
        // 미들웨어
        .layer(middleware::from_fn(log_request_middleware))
        .layer(CorsLayer::permissive())
        .with_state(state);

    // TLS 설정
    let (cert_path, key_path) = if std::path::Path::new("/home/profavor/.letsencrypt/live/mplat.store/fullchain.pem").exists() {
        ("/home/profavor/.letsencrypt/live/mplat.store/fullchain.pem", "/home/profavor/.letsencrypt/live/mplat.store/privkey.pem")
    } else {
        ("/home/profavor/.letsencrypt/live/mdm.mplat.store/fullchain.pem", "/home/profavor/.letsencrypt/live/mdm.mplat.store/privkey.pem")
    };

    let tls_config = axum_server::tls_rustls::RustlsConfig::from_pem_file(cert_path, key_path)
        .await
        .map_err(|e| format!("Failed to load certs: {}", e))?;

    // 포트 8088 단일 서빙 (APK 배포 및 웹 플레이어 접속)
    let addr_8088: SocketAddr = ([0, 0, 0, 0], 8088).into();
    println!("🚀 mplat Mirror Web Server running on https://0.0.0.0:8088 (mdm.mplat.store:8088)");

    axum_server::bind_rustls(addr_8088, tls_config)
        .serve(base_app.into_make_service_with_connect_info::<SocketAddr>())
        .await?;

    Ok(())
}
