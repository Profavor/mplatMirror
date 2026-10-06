package io.mmirror

import android.content.Context
import android.net.wifi.WifiManager
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * mMirror 통합 네트워크 유틸리티
 * 
 * - 안드로이드 핫스팟 활성화 상태 감지 (리플렉션 및 인터페이스 검사)
 * - 핫스팟 로컬 게이트웨이 IP 추출 (swlan, ap, wlan1 등)
 * - 테슬라 차량 직결용 커널 ARP 캐시 및 DHCP 서브넷 IP 후보 집합 추출
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"
    const val DEFAULT_HOTSPOT_IP = "192.168.43.1"

    /**
     * 모바일 핫스팟(Wi-Fi AP)이 현재 켜져 있는지 확인합니다.
     */
    fun isWifiApEnabled(context: Context? = null): Boolean {
        // 1. WifiManager 비공개 API 리플렉션 검사
        if (context != null) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager != null) {
                    val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
                    method.isAccessible = true
                    val enabled = method.invoke(wifiManager) as? Boolean
                    if (enabled == true) return true
                }
            } catch (_: Exception) {}
        }

        // 2. 가상/테더링 네트워크 인터페이스 검사 (swlan0, ap0, softap, tether 등)
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.contains("swlan") || name.startsWith("ap") || name.contains("softap") || name.contains("tether")) {
                    for (addr in Collections.list(iface.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return false
    }

    /**
     * 현재 활성화된 모바일 핫스팟의 게이트웨이 IPv4 주소를 반환합니다.
     */
    fun getHotspotIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // 1순위: 전용 핫스팟/소프트AP 인터페이스 (swlan0, ap0, softap, tether, wlan1)
            for (iface in interfaces) {
                if (!iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.contains("swlan") || name.startsWith("ap") || name.contains("softap") || name.contains("tether") || name.contains("wlan1")) {
                    for (addr in Collections.list(iface.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: ""
                            if (ip.isNotEmpty()) return ip
                        }
                    }
                }
            }

            // 2순위: 안드로이드 기본 핫스팟 대역 (192.168.43.x, 일반 wlan0 클라이언트 제외)
            for (iface in interfaces) {
                if (!iface.isUp) continue
                val name = iface.name.lowercase()
                if (name == "wlan0") continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.startsWith("192.168.43.")) return ip
                    }
                }
            }

            // 3순위: 셀룰러(rmnet/ccmni/pdp/wwan) 및 wlan0 제외 사설 서브넷 (192.168.x.x, 10.x.x.x, 172.x.x.x)
            for (iface in interfaces) {
                if (!iface.isUp) continue
                val name = iface.name.lowercase()
                if (name == "wlan0" || name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") || name.startsWith("wwan")) {
                    continue
                }
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return DEFAULT_HOTSPOT_IP
    }

    /**
     * 테슬라 브라우저의 mDNS(.local) 후보를 로컬 직결하기 위한 서브넷 IP 후보군을 생성합니다.
     */
    fun getCandidateTargetIps(hotspotIp: String): Set<String> {
        val targets = linkedSetOf<String>()
        val prefix = hotspotIp.substringBeforeLast(".") + "."
        val gwOctet = hotspotIp.substringAfterLast(".").toIntOrNull() ?: 1

        // 1. /proc/net/arp 확인 (리눅스 커널 ARP 캐시에서 활성 접속 클라이언트 즉시 추출)
        try {
            val arpLines = File("/proc/net/arp").readLines()
            for (line in arpLines) {
                val tokens = line.trim().split(Regex("\\s+"))
                if (tokens.size >= 4 && tokens[0] != "IP" && tokens[0].startsWith(prefix)) {
                    val ip = tokens[0]
                    if (ip != hotspotIp && !ip.endsWith(".0") && !ip.endsWith(".255")) {
                        targets.add(ip)
                        AppLogger.i(TAG, "🔍 Detected connected client from ARP: $ip")
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. 게이트웨이 직후 대역 (안드로이드 핫스팟 DHCP의 최우선 할당 구간!)
        for (offset in 1..10) {
            val octet = gwOctet + offset
            if (octet in 2..254) {
                targets.add("$prefix$octet")
            }
        }

        // 3. 표준 DHCP 기본 풀 (2..15)
        for (octet in 2..15) {
            targets.add("$prefix$octet")
        }

        // 4. 게이트웨이 직전 대역 (1..5)
        for (offset in 1..5) {
            val octet = gwOctet - offset
            if (octet in 2..254) {
                targets.add("$prefix$octet")
            }
        }

        targets.remove(hotspotIp)
        return targets
    }
}
