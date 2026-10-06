//! WebRTC 시그널링 핸들러
//!
//! 테슬라 브라우저(뷰어)와 스마트폰(퍼블리셔) 간 SDP/ICE 교환만 중계.
//! 실제 미디어 스트림은 로컬 Wi-Fi P2P 직결 (0MB 모바일 데이터).

use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        ConnectInfo, Query, State,
    },
    response::Response,
};
use futures_util::{SinkExt, StreamExt};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::sync::{broadcast, RwLock};

use super::{RelayState, RoomChannels, WebrtcSignalQuery};

pub async fn webrtc_signal_handler(
    ws: WebSocketUpgrade,
    ConnectInfo(_addr): ConnectInfo<SocketAddr>,
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

    let (to_publisher, to_viewer, cached_offer, cached_candidates, cached_config) = {
        let mut rooms = state.webrtc_rooms.write().await;
        let entry = rooms.entry(room.clone()).or_insert_with(|| {
            let (to_pub, _) = broadcast::channel(128);
            let (to_view, _) = broadcast::channel(128);
            RoomChannels {
                to_publisher: to_pub,
                to_viewer: to_view,
                cached_offer: Arc::new(RwLock::new(None)),
                cached_candidates: Arc::new(RwLock::new(Vec::new())),
                cached_config: Arc::new(RwLock::new(None)),
            }
        });
        (
            entry.to_publisher.clone(),
            entry.to_viewer.clone(),
            entry.cached_offer.clone(),
            entry.cached_candidates.clone(),
            entry.cached_config.clone(),
        )
    };

    let (mut sender, mut receiver) = socket.split();

    if role == "publisher" {
        // 퍼블리셔 연결 시 과거 세션 잔여 캐시 완전 소거
        *cached_offer.write().await = None;
        cached_candidates.write().await.clear();
        *cached_config.write().await = None;

        // 기존 뷰어들에게 스마트폰(송출기) 연결 알림
        let _ = to_viewer.send(r#"{"type":"publisher_connected"}"#.to_string());

        let mut rx = to_publisher.subscribe();
        let send_task = tokio::spawn(async move {
            while let Ok(msg) = rx.recv().await {
                if sender.send(Message::Text(msg)).await.is_err() {
                    break;
                }
            }
        });

        let forward_tx = to_viewer.clone();
        let c_offer = cached_offer.clone();
        let c_cand = cached_candidates.clone();
        let c_conf = cached_config.clone();

        let recv_task = tokio::spawn(async move {
            while let Some(Ok(msg)) = receiver.next().await {
                if let Message::Text(text) = msg {
                    if text.contains("\"type\":\"offer\"") || text.contains("\"offer\"") {
                        println!("📥 [SIGNAL] Forwarding fresh SDP Offer from publisher (len={})", text.len());
                        *c_offer.write().await = Some(text.clone());
                        c_cand.write().await.clear();
                    } else if text.contains("\"type\":\"candidate\"") {
                        println!("📡 [SIGNAL] Forwarding Candidate from publisher: {}", text);
                        let mut c = c_cand.write().await;
                        if c.len() < 64 {
                            c.push(text.clone());
                        }
                    } else if text.contains("\"type\":\"config\"") {
                        *c_conf.write().await = Some(text.clone());
                    } else if text.contains("\"type\":\"stream_stopped\"") {
                        println!("🛑 [SIGNAL] Publisher announced stream_stopped");
                        *c_offer.write().await = None;
                        c_cand.write().await.clear();
                        *c_conf.write().await = None;
                    }
                    let _ = forward_tx.send(text);
                }
            }
        });

        tokio::select! {
            _ = send_task => {},
            _ = recv_task => {},
        }

        println!("📡 [WEBRTC SIGNAL] Publisher disconnected: room={}", room);
        *cached_offer.write().await = None;
        cached_candidates.write().await.clear();
        *cached_config.write().await = None;
    } else {
        println!("📢 [SIGNAL] Viewer connected to room: {}", room);

        // 캐시된 config 즉시 전달
        if let Some(config) = cached_config.read().await.as_ref() {
            let _ = sender.send(Message::Text(config.clone())).await;
        }

        // 퍼블리셔에 뷰어 준비 완료 알림
        println!("📢 [SIGNAL] Requesting fresh SDP Offer from publisher for new viewer");
        let _ = to_publisher.send(r#"{"type":"ready"}"#.to_string());

        let mut rx = to_viewer.subscribe();
        let send_task = tokio::spawn(async move {
            while let Ok(msg) = rx.recv().await {
                if sender.send(Message::Text(msg)).await.is_err() {
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
                        println!("📡 [SIGNAL] Forwarding Candidate from viewer: {}", text);
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
