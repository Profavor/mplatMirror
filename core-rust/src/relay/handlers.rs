//! 레거시 WebSocket 릴레이 핸들러
//!
//! 폰(퍼블리셔) → 릴레이 서버 → 테슬라(뷰어)로 비디오/오디오/터치를 중계.
//! ⚠️ 이 방식은 모바일 데이터를 대량 소모하므로 통상적인 차량 미러링에서는 사용하지 않음.
//! WebRTC P2P 직결이 표준 방식이며, 이 핸들러는 원격 디버깅/테스트 전용.

use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    response::Response,
};
use futures_util::{SinkExt, StreamExt};
use std::sync::Arc;

use super::RelayState;

/// 테슬라/태블릿 뷰어 WebSocket (/ws)
pub async fn ws_handler(ws: WebSocketUpgrade, State(state): State<RelayState>) -> Response {
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
                    if sender.send(Message::Binary((*packet).clone())).await.is_err() {
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

/// 안드로이드 폰 퍼블리셔 WebSocket (/publish)
pub async fn publish_handler(ws: WebSocketUpgrade, State(state): State<RelayState>) -> Response {
    ws.on_upgrade(|socket| handle_phone_publisher(socket, state))
}

async fn handle_phone_publisher(socket: WebSocket, state: RelayState) {
    println!("📱 [RELAY] Phone publisher connected to /publish!");
    let (mut sender, mut receiver) = socket.split();

    let mut control_rx = state.control_tx.subscribe();

    // 테슬라의 터치 및 키프레임 요청을 폰으로 전송
    let send_task = tokio::spawn(async move {
        while let Ok(msg) = control_rx.recv().await {
            if sender.send(Message::Text(msg)).await.is_err() {
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
