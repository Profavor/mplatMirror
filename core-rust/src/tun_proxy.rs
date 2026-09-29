use std::io;
use std::os::unix::io::{AsRawFd, FromRawFd, RawFd};
use std::pin::Pin;
use std::sync::Mutex;
use std::task::{ready, Context, Poll};
use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::net::TcpStream;
use tokio::sync::oneshot;

extern "C" {
    fn dup(fd: std::os::raw::c_int) -> std::os::raw::c_int;
}

pub struct AsyncTunDevice {
    inner: AsyncFd<std::fs::File>,
}

impl AsyncTunDevice {
    pub fn new(fd: RawFd) -> io::Result<Self> {
        unsafe {
            let flags = libc::fcntl(fd, libc::F_GETFL);
            if flags < 0 {
                return Err(io::Error::last_os_error());
            }
            if libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) < 0 {
                return Err(io::Error::last_os_error());
            }
            let file = std::fs::File::from_raw_fd(fd);
            let async_fd = AsyncFd::new(file)?;
            Ok(Self { inner: async_fd })
        }
    }
}

impl AsyncRead for AsyncTunDevice {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        loop {
            let mut guard = ready!(self.inner.poll_read_ready(cx))?;
            let unfilled = buf.initialize_unfilled();
            match guard.try_io(|inner| {
                let fd = inner.get_ref().as_raw_fd();
                let ret = unsafe {
                    libc::read(fd, unfilled.as_mut_ptr() as *mut libc::c_void, unfilled.len())
                };
                if ret < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(ret as usize)
                }
            }) {
                Ok(Ok(n)) => {
                    buf.advance(n);
                    return Poll::Ready(Ok(()));
                }
                Ok(Err(e)) => return Poll::Ready(Err(e)),
                Err(_would_block) => continue,
            }
        }
    }
}

impl AsyncWrite for AsyncTunDevice {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        loop {
            let mut guard = ready!(self.inner.poll_write_ready(cx))?;
            match guard.try_io(|inner| {
                let fd = inner.get_ref().as_raw_fd();
                let ret = unsafe {
                    libc::write(fd, buf.as_ptr() as *const libc::c_void, buf.len())
                };
                if ret < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(ret as usize)
                }
            }) {
                Ok(res) => return Poll::Ready(res),
                Err(_would_block) => continue,
            }
        }
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}

static TUN_SHUTDOWN_TX: Mutex<Option<oneshot::Sender<()>>> = Mutex::new(None);

pub fn start_tun_proxy(fd: i32, target_port: u16) -> bool {
    stop_tun_proxy();

    let dup_fd = unsafe { dup(fd as std::os::raw::c_int) };
    if dup_fd < 0 {
        crate::server::log_android(6, &format!("Failed to dup TUN fd {}: error", fd));
        return false;
    }

    let async_tun = match AsyncTunDevice::new(dup_fd) {
        Ok(dev) => dev,
        Err(e) => {
            crate::server::log_android(6, &format!("Failed to create AsyncTunDevice: {:?}", e));
            return false;
        }
    };

    let mut config = ipstack::IpStackConfig::default();
    if let Err(e) = config.mtu(1500) {
        crate::server::log_android(6, &format!("Failed to set MTU on ipstack: {:?}", e));
        return false;
    }
    config.packet_information = false;

    let mut ip_stack = ipstack::IpStack::new(config, async_tun);
    let (shutdown_tx, mut shutdown_rx) = oneshot::channel();

    if let Ok(mut lock) = TUN_SHUTDOWN_TX.lock() {
        *lock = Some(shutdown_tx);
    }

    crate::server::log_android(4, &format!("TUN Proxy started (AsyncFd): fd={}, target_port={}", fd, target_port));

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
                                    crate::server::log_android(
                                        4,
                                        &format!("⚡ [TUN TCP INFLOW] 핫스팟 클라이언트 연결 감지! Dst={:?} Src={:?}", local, peer),
                                    );

                                    tokio::spawn(async move {
                                        let mut first_byte = [0u8; 1];
                                        match client_tcp.read(&mut first_byte).await {
                                            Ok(1) => {
                                                let is_tls = first_byte[0] == 0x16;
                                                let dest_port = if is_tls {
                                                    // TLS / HTTPS 요청인 경우: 포트 9999 (또는 9998, 8443, 7679)
                                                    if local_port == 8443 || local_port == 7679 || local_port == 9998 {
                                                        local_port
                                                    } else {
                                                        9999
                                                    }
                                                } else {
                                                    // 일반 텍스트 HTTP 요청인 경우: 항상 실제 구동 중인 target_port (8080)로 전달!
                                                    target_port
                                                };

                                                let target_addr = if local_port == 8088 {
                                                    "122.40.252.50:8088".to_string()
                                                } else {
                                                    format!("127.0.0.1:{}", dest_port)
                                                };

                                                crate::server::log_android(
                                                    4,
                                                    &format!(
                                                        "🔄 [TUN PROXY] {} 로 전달 시작 (TLS={}, 수신포트={})",
                                                        target_addr, is_tls, local_port
                                                    ),
                                                );

                                                match TcpStream::connect(&target_addr).await {
                                                    Ok(mut target_tcp) => {
                                                        if let Err(e) = target_tcp.write_all(&first_byte).await {
                                                            crate::server::log_android(6, &format!("❌ [TUN WRITE ERROR] 초기 바이트 전송 실패: {}", e));
                                                            return;
                                                        }
                                                        crate::server::log_android(4, &format!("✅ [TUN CONNECTED] 127.0.0.1:{} 연결 수립! 양방향 데이터 중계 시작", dest_port));
                                                        let _ = tokio::io::copy_bidirectional(&mut client_tcp, &mut target_tcp).await;
                                                        let _ = target_tcp.shutdown().await;
                                                        let _ = client_tcp.shutdown().await;
                                                        crate::server::log_android(4, &format!("🏁 [TUN CLOSED] 127.0.0.1:{} 세션 종료", dest_port));
                                                    }
                                                    Err(e) => {
                                                        crate::server::log_android(6, &format!("❌ [TUN CONNECT FAIL] 127.0.0.1:{} 연결 실패: {}", dest_port, e));
                                                    }
                                                }
                                            }
                                            Ok(_) => {}
                                            Err(e) => {
                                                crate::server::log_android(6, &format!("❌ [TUN READ FAIL] 첫 바이트 수신 실패: {}", e));
                                            }
                                        }
                                    });
                                }
                                ipstack::IpStackStream::Udp(client_udp) => {
                                    crate::server::log_android(4, &format!("📡 [TUN UDP] 패킷 유입: Dst={:?} Src={:?}", client_udp.local_addr(), client_udp.peer_addr()));
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
