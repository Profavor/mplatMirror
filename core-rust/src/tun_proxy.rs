use std::os::unix::io::FromRawFd;
use std::sync::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::oneshot;

extern "C" {
    fn dup(fd: std::os::raw::c_int) -> std::os::raw::c_int;
}

static TUN_SHUTDOWN_TX: Mutex<Option<oneshot::Sender<()>>> = Mutex::new(None);

pub fn start_tun_proxy(fd: i32, target_port: u16) -> bool {
    stop_tun_proxy();

    let dup_fd = unsafe { dup(fd as std::os::raw::c_int) };
    if dup_fd < 0 {
        crate::server::log_android(6, &format!("Failed to dup TUN fd {}: error", fd));
        return false;
    }

    // 파일 디스크립터가 블로킹 모드인지 확인하여 tokio::fs::File의 spawn_blocking 워커가 정상 대기하도록 보장
    unsafe {
        let flags = libc::fcntl(dup_fd, libc::F_GETFL);
        if flags >= 0 {
            libc::fcntl(dup_fd, libc::F_SETFL, flags & !libc::O_NONBLOCK);
        }
    }

    let std_file = unsafe { std::fs::File::from_raw_fd(dup_fd) };
    let async_file = tokio::fs::File::from_std(std_file);

    let mut config = ipstack::IpStackConfig::default();
    if let Err(e) = config.mtu(1500) {
        crate::server::log_android(6, &format!("Failed to set MTU on ipstack: {:?}", e));
        return false;
    }
    config.packet_information = false;

    let mut ip_stack = ipstack::IpStack::new(config, async_file);
    let (shutdown_tx, mut shutdown_rx) = oneshot::channel();

    if let Ok(mut lock) = TUN_SHUTDOWN_TX.lock() {
        *lock = Some(shutdown_tx);
    }

    crate::server::log_android(4, &format!("TUN Proxy started: fd={}, target_port={}", fd, target_port));

    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = &mut shutdown_rx => {
                    crate::server::log_android(4, "TUN Proxy shutting down via signal");
                    break;
                }
                accept_res = ip_stack.accept() => {
                    match accept_res {
                        Ok(stream) => {
                            match stream {
                                ipstack::IpStackStream::Tcp(mut client_tcp) => {
                                    let peer = client_tcp.peer_addr();
                                    let local = client_tcp.local_addr();
                                    let local_port = local.port();
                                    crate::server::log_android(4, &format!("TUN TCP connection: {:?} -> {:?}", local, peer));

                                    tokio::spawn(async move {
                                        let mut first_byte = [0u8; 1];
                                        match client_tcp.read(&mut first_byte).await {
                                            Ok(1) => {
                                                let is_tls = first_byte[0] == 0x16;
                                                let dest_port = if is_tls {
                                                    // TLS / HTTPS 요청인 경우: 9999 (또는 8443, 7679)
                                                    if local_port == 8443 || local_port == 7679 {
                                                        local_port
                                                    } else {
                                                        9999
                                                    }
                                                } else {
                                                    // 일반 텍스트 HTTP 요청인 경우: target_port (8080) 또는 7777, 7678
                                                    if local_port == 7777 || local_port == 7678 {
                                                        local_port
                                                    } else {
                                                        target_port
                                                    }
                                                };

                                                crate::server::log_android(
                                                    4,
                                                    &format!(
                                                        "TUN TCP proxying to 127.0.0.1:{} (tls={}) for incoming port {}",
                                                        dest_port, is_tls, local_port
                                                    ),
                                                );

                                                match TcpStream::connect(format!("127.0.0.1:{}", dest_port)).await {
                                                    Ok(mut target_tcp) => {
                                                        if let Err(e) = target_tcp.write_all(&first_byte).await {
                                                            crate::server::log_android(6, &format!("Failed to write initial byte: {}", e));
                                                            return;
                                                        }
                                                        let _ = tokio::io::copy_bidirectional(&mut client_tcp, &mut target_tcp).await;
                                                        let _ = target_tcp.shutdown().await;
                                                        let _ = client_tcp.shutdown().await;
                                                    }
                                                    Err(e) => {
                                                        crate::server::log_android(6, &format!("Failed to connect to local port {}: {}", dest_port, e));
                                                    }
                                                }
                                            }
                                            Ok(_) => {}
                                            Err(e) => {
                                                crate::server::log_android(6, &format!("Failed to read initial byte from TUN TCP: {}", e));
                                            }
                                        }
                                    });
                                }
                                ipstack::IpStackStream::Udp(client_udp) => {
                                    crate::server::log_android(3, &format!("TUN UDP packet from {:?}", client_udp.local_addr()));
                                }
                                _ => {}
                            }
                        }
                        Err(e) => {
                            crate::server::log_android(6, &format!("TUN IpStack accept error (retrying): {:?}", e));
                            tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
                        }
                    }
                }
            }
        }
        crate::server::log_android(4, "TUN Proxy loop exited");
    });

    true
}

pub fn stop_tun_proxy() {
    if let Ok(mut lock) = TUN_SHUTDOWN_TX.lock() {
        if let Some(tx) = lock.take() {
            let _ = tx.send(());
            crate::server::log_android(4, "Sent shutdown signal to TUN Proxy");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_tun_proxy_init() {
        let (s1, _s2) = std::os::unix::net::UnixStream::pair().unwrap();
        use std::os::unix::io::IntoRawFd;
        let fd = s1.into_raw_fd();
        let res = start_tun_proxy(fd, 8080);
        assert!(res);
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
        stop_tun_proxy();
    }
}
