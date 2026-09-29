package io.mmirror.adb

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedWriter
import java.util.concurrent.Executors

object AdbTouchManager {
    private const val TAG = "AdbTouchManager"
    const val SHIZUKU_REQUEST_CODE = 7001

    private val touchExecutor = Executors.newSingleThreadExecutor()
    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null

    private val newProcessMethod by lazy {
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve Shizuku.newProcess: ${e.message}")
            null
        }
    }

    private fun runShizukuProcess(cmd: Array<String>): Process? {
        return try {
            newProcessMethod?.invoke(null, cmd, null, null) as? Process
        } catch (e: Exception) {
            Log.w(TAG, "Error invoking Shizuku.newProcess: ${e.message}")
            null
        }
    }

    @Synchronized
    private fun getShellWriter(): BufferedWriter? {
        if (!isShizukuAvailable) return null
        if (shellWriter == null || shellProcess?.isAlive != true) {
            try {
                shellProcess = runShizukuProcess(arrayOf("sh"))
                shellWriter = shellProcess?.outputStream?.bufferedWriter()
                Log.i(TAG, "Persistent Shizuku shell started")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start persistent Shizuku shell: ${e.message}")
                shellWriter = null
                shellProcess = null
            }
        }
        return shellWriter
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    val isShizukuRunning: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

    val isShizukuPermissionGranted: Boolean
        get() = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

    val isShizukuAvailable: Boolean
        get() = isShizukuPermissionGranted

    fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestShizukuPermission failed: ${e.message}")
        }
    }

    fun launchAppOnDisplay(context: Context, packageName: String, displayId: Int = -1) {
        if (!isShizukuAvailable) {
            Log.w(TAG, "Shizuku not available to launch $packageName")
            return
        }
        touchExecutor.execute {
            try {
                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(packageName)
                val comp = launchIntent?.component?.flattenToShortString()
                val cmd = if (comp != null) {
                    if (displayId > 0) "am start -n $comp --display $displayId\n"
                    else "am start -n $comp\n"
                } else {
                    if (displayId > 0) "monkey -p $packageName -c android.intent.category.LAUNCHER 1\n"
                    else "monkey -p $packageName 1\n"
                }
                Log.i(TAG, "Executing launch cmd: $cmd")
                val writer = getShellWriter()
                if (writer != null) {
                    writer.write(cmd)
                    writer.flush()
                } else {
                    val process = runShizukuProcess(arrayOf("sh", "-c", cmd.trim()))
                    process?.waitFor()
                }
                Log.i(TAG, "✓ Launched $packageName on display $displayId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch $packageName on display $displayId: ${e.message}")
            }
        }
    }

    fun injectTap(x: Float, y: Float, displayId: Int = -1) {
        if (!isShizukuAvailable) return
        touchExecutor.execute {
            try {
                val cmd = if (displayId > 0) {
                    "input -d $displayId tap ${x.toInt()} ${y.toInt()}\n"
                } else {
                    "input tap ${x.toInt()} ${y.toInt()}\n"
                }
                val writer = getShellWriter()
                if (writer != null) {
                    writer.write(cmd)
                    writer.flush()
                } else {
                    val args = if (displayId > 0) {
                        arrayOf("input", "-d", displayId.toString(), "tap", x.toInt().toString(), y.toInt().toString())
                    } else {
                        arrayOf("input", "tap", x.toInt().toString(), y.toInt().toString())
                    }
                    val process = runShizukuProcess(args)
                    process?.waitFor()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AdbTap failed: ${e.message}")
                shellWriter = null
            }
        }
    }

    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 100, displayId: Int = -1) {
        if (!isShizukuAvailable) return
        touchExecutor.execute {
            try {
                val cmd = if (displayId > 0) {
                    "input -d $displayId swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs\n"
                } else {
                    "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs\n"
                }
                val writer = getShellWriter()
                if (writer != null) {
                    writer.write(cmd)
                    writer.flush()
                } else {
                    val args = if (displayId > 0) {
                        arrayOf(
                            "input", "-d", displayId.toString(), "swipe",
                            x1.toInt().toString(), y1.toInt().toString(),
                            x2.toInt().toString(), y2.toInt().toString(),
                            durationMs.toString()
                        )
                    } else {
                        arrayOf(
                            "input", "swipe",
                            x1.toInt().toString(), y1.toInt().toString(),
                            x2.toInt().toString(), y2.toInt().toString(),
                            durationMs.toString()
                        )
                    }
                    val process = runShizukuProcess(args)
                    process?.waitFor()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AdbSwipe failed: ${e.message}")
                shellWriter = null
            }
        }
    }

    fun injectKey(keyCode: Int, displayId: Int = -1) {
        if (!isShizukuAvailable) return
        touchExecutor.execute {
            try {
                val cmd = if (displayId > 0) {
                    "input -d $displayId keyevent $keyCode\n"
                } else {
                    "input keyevent $keyCode\n"
                }
                val writer = getShellWriter()
                if (writer != null) {
                    writer.write(cmd)
                    writer.flush()
                } else {
                    val args = if (displayId > 0) {
                        arrayOf("input", "-d", displayId.toString(), "keyevent", keyCode.toString())
                    } else {
                        arrayOf("input", "keyevent", keyCode.toString())
                    }
                    val process = runShizukuProcess(args)
                    process?.waitFor()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AdbKey failed: ${e.message}")
                shellWriter = null
            }
        }
    }

    // 터치 세션 추적 (접근성 서비스 없이 Shizuku 단독 구동 시에도 터치/스와이프 완벽 처리)
    private data class AdbTouchSession(
        val startX: Float,
        val startY: Float,
        var lastX: Float,
        var lastY: Float,
        val startTime: Long,
        var isMoved: Boolean = false
    )
    private val sessions = mutableMapOf<Int, AdbTouchSession>()

    fun onTouch(action: String, id: Int, normX: Float, normY: Float, width: Int, height: Int, displayId: Int = -1) {
        val pixelX = (normX * width).coerceIn(0f, width.toFloat())
        val pixelY = (normY * height).coerceIn(0f, height.toFloat())

        when (action) {
            "down" -> {
                sessions[id] = AdbTouchSession(
                    startX = pixelX,
                    startY = pixelY,
                    lastX = pixelX,
                    lastY = pixelY,
                    startTime = System.currentTimeMillis()
                )
            }
            "move" -> {
                val session = sessions[id] ?: return
                val dist = kotlin.math.hypot(pixelX - session.lastX, pixelY - session.lastY)
                if (dist > 12f) {
                    session.isMoved = true
                    injectSwipe(session.lastX, session.lastY, pixelX, pixelY, 60, displayId)
                    session.lastX = pixelX
                    session.lastY = pixelY
                }
            }
            "up" -> {
                val session = sessions.remove(id) ?: return
                val duration = System.currentTimeMillis() - session.startTime
                if (!session.isMoved && duration < 500) {
                    injectTap(session.startX, session.startY, displayId)
                } else if (session.isMoved) {
                    val dist = kotlin.math.hypot(pixelX - session.lastX, pixelY - session.lastY)
                    if (dist > 6f) {
                        injectSwipe(session.lastX, session.lastY, pixelX, pixelY, 40, displayId)
                    }
                }
            }
        }
    }
}
