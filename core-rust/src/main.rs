use tracing::{info, Level};
use tracing_subscriber::FmtSubscriber;

use mmirror_core::protocol::{ControlMessage, DeviceConfig};
use mmirror_core::server::MirrorServer;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let subscriber = FmtSubscriber::builder()
        .with_max_level(Level::INFO)
        .finish();
    tracing::subscriber::set_global_default(subscriber)
        .expect("setting default subscriber failed");

    let port = 8282;
    println!("==================================================");
    println!("  mMirror for Tesla - Local Simulation & Dev Server");
    println!("  URL: http://localhost:{}", port);
    println!("==================================================");

    let server = MirrorServer::new(port);

    // 테슬라 브라우저 제어 이벤트 수신 리스너
    server.set_control_callback(|msg| match msg {
        ControlMessage::Touch { action, id, x, y } => {
            println!(
                "👉 [Tesla Touch] Action: {:<5} | ID: {} | X: {:.3}, Y: {:.3}",
                action,
                id,
                x.unwrap_or(0.0),
                y.unwrap_or(0.0)
            );
        }
        ControlMessage::Key { key } => {
            println!("🔑 [Tesla Key] Virtual Button: {}", key);
        }
        ControlMessage::Command { cmd } => {
            println!("⚡ [Tesla Command] Command: {}", cmd);
        }
        ControlMessage::SetScreenPower { on } => {
            println!("💡 [Tesla Screen Power] Set power: on={}", on);
        }
    });

    server.start().expect("Failed to start mirror server");

    // 기본 해상도 알림
    server.update_config(DeviceConfig {
        width: 1080,
        height: 2400,
        rotation: 0,
        fps: 60,
    });

    info!("Server is running. Open http://localhost:{} in your browser.", port);
    info!("Press Ctrl+C to terminate.");

    let server = std::sync::Arc::new(server);


    tokio::signal::ctrl_c().await?;
    info!("Shutting down mMirror Dev Server...");
    server.stop();

    Ok(())
}
