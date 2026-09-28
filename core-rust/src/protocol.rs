use serde::{Deserialize, Serialize};

pub const PKT_TYPE_VIDEO: u8 = 0x01;
pub const PKT_TYPE_AUDIO: u8 = 0x02;
pub const PKT_TYPE_CONFIG: u8 = 0x03;
pub const PKT_TYPE_GPS: u8 = 0x04;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum ControlMessage {
    #[serde(rename = "touch")]
    Touch {
        action: String, // "down", "move", "up"
        id: i32,
        #[serde(default)]
        x: Option<f32>, // 0.0 ~ 1.0
        #[serde(default)]
        y: Option<f32>, // 0.0 ~ 1.0
    },
    #[serde(rename = "key")]
    Key {
        key: String, // "BACK", "HOME", "RECENTS"
    },
    #[serde(rename = "command")]
    Command {
        cmd: String, // "ROTATE", etc.
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceConfig {
    pub width: u32,
    pub height: u32,
    pub rotation: u32,
    pub fps: u32,
}

impl DeviceConfig {
    pub fn to_bytes(&self) -> Vec<u8> {
        let json_str = serde_json::to_string(self).unwrap_or_default();
        let mut buf = Vec::with_capacity(1 + json_str.len());
        buf.push(PKT_TYPE_CONFIG);
        buf.extend_from_slice(json_str.as_bytes());
        buf
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpsData {
    pub lat: f64,
    pub lng: f64,
    pub speed_kmh: f32,
    pub heading: f32,
    pub trip_distance_meters: f64,
    pub duration_seconds: u64,
    pub timestamp: u64,
}

impl GpsData {
    pub fn to_bytes(&self) -> Vec<u8> {
        let json_str = serde_json::to_string(self).unwrap_or_default();
        let mut buf = Vec::with_capacity(1 + json_str.len());
        buf.push(PKT_TYPE_GPS);
        buf.extend_from_slice(json_str.as_bytes());
        buf
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PathPoint {
    pub lat: f64,
    pub lng: f64,
    pub speed: f32,
    pub time: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TripRecord {
    pub id: String,
    pub start_time: String,
    pub end_time: String,
    pub distance_km: f64,
    pub duration_sec: u64,
    pub avg_speed_kmh: f32,
    pub max_speed_kmh: f32,
    #[serde(default)]
    pub path: Vec<PathPoint>,
}
