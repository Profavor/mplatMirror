use axum::{
    http::{header, HeaderMap, StatusCode},
    response::IntoResponse,
};

// ─── 공용 정적 에셋 (server.rs + relay.rs 공유) ───
pub const INDEX_HTML: &str = include_str!("../../web/index.html");
pub const STYLE_CSS: &str = include_str!("../../web/style.css");
pub const LEAFLET_CSS: &str = include_str!("../../web/leaflet.css");
pub const PLAYER_JS: &str = include_str!("../../web/player.js");
pub const TOUCH_JS: &str = include_str!("../../web/touch.js");
pub const TRIPLOG_JS: &str = include_str!("../../web/triplog.js");
pub const LEAFLET_JS: &str = include_str!("../../web/leaflet.js");
pub const LOGO_PNG: &[u8] = include_bytes!("../../web/logo.png");
pub const FIREBASE_CONFIG_JS: &str = include_str!("../../web/firebase-config.js");

// ─── 릴레이 전용 에셋 (relay.rs만 사용) ───
pub const DOWNLOAD_HTML: &str = include_str!("../../web/download.html");
pub const PRIVACY_HTML: &str = include_str!("../../web/privacy.html");
pub const APK_QR_SVG: &str = include_str!("../../web/apk_qr.svg");

// ─── 캐시 정책 프리셋 ───
pub const CACHE_NO_STORE: &str = "no-cache, no-store, must-revalidate";
pub const CACHE_NO_CACHE: &str = "no-cache";
pub const CACHE_PUBLIC_1DAY: &str = "public, max-age=86400";

/// 텍스트 정적 에셋 서빙 (HTML, CSS, JS)
pub fn serve_text(body: &'static str, content_type: &'static str, cache: &'static str) -> impl IntoResponse {
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, content_type.parse().unwrap());
    headers.insert(header::CACHE_CONTROL, cache.parse().unwrap());
    (StatusCode::OK, headers, body)
}

/// 바이너리 정적 에셋 서빙 (PNG, 등)
pub fn serve_binary(body: &'static [u8], content_type: &'static str, cache: &'static str) -> impl IntoResponse {
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, content_type.parse().unwrap());
    headers.insert(header::CACHE_CONTROL, cache.parse().unwrap());
    (StatusCode::OK, headers, body)
}

// ─── 공용 핸들러 (server.rs + relay.rs에서 직접 라우터에 등록) ───

pub async fn serve_css() -> impl IntoResponse {
    serve_text(STYLE_CSS, "text/css; charset=utf-8", CACHE_NO_STORE)
}

pub async fn serve_player() -> impl IntoResponse {
    serve_text(PLAYER_JS, "application/javascript; charset=utf-8", CACHE_NO_STORE)
}

pub async fn serve_touch() -> impl IntoResponse {
    serve_text(TOUCH_JS, "application/javascript; charset=utf-8", CACHE_NO_STORE)
}

pub async fn serve_triplog() -> impl IntoResponse {
    serve_text(TRIPLOG_JS, "application/javascript; charset=utf-8", CACHE_NO_STORE)
}

pub async fn serve_firebase_config() -> impl IntoResponse {
    serve_text(FIREBASE_CONFIG_JS, "application/javascript; charset=utf-8", CACHE_NO_STORE)
}

pub async fn serve_leaflet_css() -> impl IntoResponse {
    serve_text(LEAFLET_CSS, "text/css; charset=utf-8", CACHE_PUBLIC_1DAY)
}

pub async fn serve_leaflet_js() -> impl IntoResponse {
    serve_text(LEAFLET_JS, "application/javascript; charset=utf-8", CACHE_PUBLIC_1DAY)
}

pub async fn serve_logo() -> impl IntoResponse {
    serve_binary(LOGO_PNG, "image/png", CACHE_PUBLIC_1DAY)
}
