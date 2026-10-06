pub mod protocol;
pub mod relay;
pub mod server;
pub mod tun_proxy;
pub mod web_assets;

use std::sync::{Mutex, OnceLock};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jfloat, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use tokio::runtime::Runtime;
use tracing::info;

use crate::protocol::{ControlMessage, DeviceConfig, GpsData, TripRecord};
use crate::server::MirrorServer;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static SERVER: Mutex<Option<MirrorServer>> = Mutex::new(None);
static JVM: OnceLock<JavaVM> = OnceLock::new();
static NATIVE_BRIDGE_CLASS: OnceLock<jni::objects::GlobalRef> = OnceLock::new();

fn get_runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("Failed to create Tokio Runtime")
    })
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_init(
    mut env: JNIEnv,
    class: JClass,
) {
    if let Ok(vm) = env.get_java_vm() {
        let _ = JVM.set(vm);
    }
    if NATIVE_BRIDGE_CLASS.get().is_none() {
        let cls = match env.find_class("io/mmirror/NativeBridge") {
            Ok(found) => found,
            Err(_) => class,
        };
        if let Ok(global_ref) = env.new_global_ref(cls) {
            let _ = NATIVE_BRIDGE_CLASS.set(global_ref);
        }
    }
    let _ = tracing_subscriber::fmt()
        .with_env_filter("info")
        .try_init();
    info!("mMirror NativeBridge initialized");
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_startServer(
    mut env: JNIEnv,
    class: JClass,
    port: jint,
) -> jint {
    if NATIVE_BRIDGE_CLASS.get().is_none() {
        let cls = match env.find_class("io/mmirror/NativeBridge") {
            Ok(found) => found,
            Err(_) => class,
        };
        if let Ok(global_ref) = env.new_global_ref(cls) {
            let _ = NATIVE_BRIDGE_CLASS.set(global_ref);
        }
    }
    let mut lock = SERVER.lock().unwrap();
    if let Some(ref server) = *lock {
        let current_port = server.bound_port();
        if current_port > 0 {
            crate::server::log_android(4, &format!("Server already active on port {}", current_port));
            return current_port as jint;
        }
    }

    let rt = get_runtime();
    let _enter = rt.enter();

    let server = MirrorServer::new(port as u16);

    // JNI 정적 메서드 호출 헬퍼 (보일러플레이트 제거)
    fn call_jni_static(env: &mut JNIEnv, class: &JClass, method: &str, sig: &str, args: &[jni::objects::JValue]) {
        if let Err(e) = env.call_static_method(class, method, sig, args) {
            crate::server::log_android(6, &format!("{} JNI error: {}", method, e));
            if let Ok(true) = env.exception_check() {
                let _ = env.exception_clear();
            }
        }
    }

    // 안드로이드 Java 콜백 등록 (전역 참조 클래스 사용으로 ClassNotFoundException 및 ART abort 완벽 방지)
    server.set_control_callback(move |msg| {
        if let Some(jvm) = JVM.get() {
            if let Ok(mut env) = jvm.attach_current_thread() {
                if let Some(global_class) = NATIVE_BRIDGE_CLASS.get() {
                    let jclass = unsafe { JClass::from_raw(global_class.as_raw()) };
                    match msg {
                        ControlMessage::Touch { action, id, x, y } => {
                            if let Ok(action_jstr) = env.new_string(&action) {
                                call_jni_static(&mut env, &jclass, "onTouchEvent", "(Ljava/lang/String;IFF)V", &[
                                    (&action_jstr).into(),
                                    (id as jint).into(),
                                    (x.unwrap_or(0.0) as jfloat).into(),
                                    (y.unwrap_or(0.0) as jfloat).into(),
                                ]);
                            }
                        }
                        ControlMessage::Key { key } => {
                            if let Ok(key_jstr) = env.new_string(&key) {
                                call_jni_static(&mut env, &jclass, "onKeyEvent", "(Ljava/lang/String;)V", &[(&key_jstr).into()]);
                            }
                        }
                        ControlMessage::Command { cmd } => {
                            if let Ok(cmd_jstr) = env.new_string(&cmd) {
                                call_jni_static(&mut env, &jclass, "onCommandEvent", "(Ljava/lang/String;)V", &[(&cmd_jstr).into()]);
                            }
                        }
                        ControlMessage::SetScreenPower { on } => {
                            call_jni_static(&mut env, &jclass, "onSetScreenPower", "(Z)V", &[
                                (if on { 1 } else { 0 } as jboolean).into(),
                            ]);
                        }
                    }
                } else {
                    crate::server::log_android(6, "mMirror NativeBridge: NATIVE_BRIDGE_CLASS not initialized");
                }
            }
        }
    });

    match server.start() {
        Ok(actual_port) => {
            crate::server::log_android(4, &format!("mMirror NativeBridge: Server started on port {}", actual_port));
            *lock = Some(server);
            actual_port as jint
        }
        Err(e) => {
            crate::server::log_android(6, &format!("mMirror NativeBridge: Failed to start Rust server: {}", e));
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_getServerPort(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let lock = SERVER.lock().unwrap();
    if let Some(ref server) = *lock {
        server.bound_port() as jint
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_stopServer(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut lock = SERVER.lock().unwrap();
    if let Some(server) = lock.take() {
        server.stop();
        crate::server::log_android(4, "mMirror Rust Server stopped");
    }
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_hasConnectedClients(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let lock = SERVER.lock().unwrap();
    if let Some(ref server) = *lock {
        if server.has_clients() {
            return 1;
        }
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_sendVideoFrame(
    env: JNIEnv,
    _class: JClass,
    data: jbyteArray,
    offset: jint,
    length: jint,
) {
    let lock = SERVER.lock().unwrap();
    if let Some(ref server) = *lock {
        if server.has_clients() {
            let len = length as usize;
            let off = offset as usize;
            let mut buf = vec![0u8; len];

            unsafe {
                let env_raw = env.get_raw();
                (**env_raw).GetByteArrayRegion.unwrap()(
                    env_raw,
                    data,
                    off as jni::sys::jsize,
                    len as jni::sys::jsize,
                    buf.as_mut_ptr() as *mut jni::sys::jbyte,
                );
            }

            server.send_video(&buf);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_sendAudioData(
    env: JNIEnv,
    _class: JClass,
    data: jbyteArray,
    offset: jint,
    length: jint,
) {
    let len = length as usize;
    let off = offset as usize;
    let mut buf = vec![0u8; len];

    unsafe {
        let env_raw = env.get_raw();
        (**env_raw).GetByteArrayRegion.unwrap()(
            env_raw,
            data,
            off as jni::sys::jsize,
            len as jni::sys::jsize,
            buf.as_mut_ptr() as *mut jni::sys::jbyte,
        );
    }

    let lock = SERVER.lock().unwrap();
    if let Some(ref server) = *lock {
        server.send_audio(&buf);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_updateConfig(
    _env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
    rotation: jint,
    fps: jint,
) {
    let config = DeviceConfig {
        width: width as u32,
        height: height as u32,
        rotation: rotation as u32,
        fps: fps as u32,
    };
    let lock = SERVER.lock().unwrap();
    if let Some(ref server) = *lock {
        server.update_config(config);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_sendGpsData(
    _env: JNIEnv,
    _class: JClass,
    lat: jdouble,
    lng: jdouble,
    speed: jfloat,
    heading: jfloat,
    distance: jdouble,
    duration: jlong,
) {
    let gps = GpsData {
        lat: lat as f64,
        lng: lng as f64,
        speed_kmh: speed as f32,
        heading: heading as f32,
        trip_distance_meters: distance as f64,
        duration_seconds: duration as u64,
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
    };

    let lock = SERVER.lock().unwrap();
    if let Some(ref server) = *lock {
        server.send_gps(gps);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_saveTripRecord(
    mut env: JNIEnv,
    _class: JClass,
    trip_json: JString,
) {
    if let Ok(json_str) = env.get_string(&trip_json) {
        let rust_str = json_str.to_str().unwrap_or_default();
        if let Ok(trip) = serde_json::from_str::<TripRecord>(rust_str) {
            let lock = SERVER.lock().unwrap();
            if let Some(ref server) = *lock {
                server.add_trip(trip);
                info!("New trip record saved via JNI");
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_startTunProxy(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    target_port: jint,
) -> jboolean {
    let result = std::panic::catch_unwind(|| {
        let rt = get_runtime();
        let _enter = rt.enter();
        tun_proxy::start_tun_proxy(fd as i32, target_port as u16)
    });

    match result {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(e) => {
            crate::server::log_android(6, &format!("Panic in startTunProxy: {:?}", e));
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_stopTunProxy(
    _env: JNIEnv,
) {
    tun_proxy::stop_tun_proxy();
}

#[no_mangle]
pub extern "system" fn Java_io_mmirror_NativeBridge_getNativeLogs(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let logs = crate::server::get_rust_logs();
    match env.new_string(logs) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
