package com.termux.agent.start

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

enum class DebugSwitchState {
    ON,
    OFF,
    UNKNOWN
}

object AgentEnvironment {
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"
    private val REDACTED_SETTINGS = setOf(
        Settings.Global.ADB_ENABLED,
        DEVELOPMENT_SETTINGS_ENABLED,
        ADB_WIFI_ENABLED
    )

    private const val ROOT_REPROBE_MS = 10_000L

    @Volatile
    private var lastRootProbeAt = -1L

    @Volatile
    private var cachedSuCommand: Array<String>? = null

    @SuppressLint("PrivateApi")
    fun getSystemProperty(key: String, defaultValue: String = ""): String = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        val method = sp.getMethod("get", String::class.java, String::class.java)
        method.invoke(null, key, defaultValue) as? String ?: defaultValue
    }.getOrDefault(defaultValue)

    @SuppressLint("PrivateApi")
    fun getSystemPropertyInt(key: String, defaultValue: Int): Int = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        val method = sp.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
        method.invoke(null, key, defaultValue) as? Int ?: defaultValue
    }.getOrDefault(defaultValue)

    fun isTlsSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun isAdbWifiOverhauled(): Boolean = Build.VERSION.SDK_INT >= 37

    fun getAdbTcpPort(): Int {
        var port = getSystemPropertyInt("service.adb.tcp.port", -1)
        if (port <= 0) port = getSystemPropertyInt("persist.adb.tcp.port", -1)
        return port
    }

    fun getAdbTlsPort(): Int = getSystemPropertyInt("service.adb.tls.port", -1)

    fun getBootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.let { it.isConnected && it.type == ConnectivityManager.TYPE_WIFI } ?: false
        }
    }

    fun isDebugSwitchReadRedacted(key: String, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= 37 && key in REDACTED_SETTINGS

    fun wirelessDebuggingState(context: Context): DebugSwitchState {
        val raw = runCatching {
            Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) != 0
        }.getOrNull() ?: return DebugSwitchState.UNKNOWN
        return when {
            raw -> DebugSwitchState.ON
            Build.VERSION.SDK_INT >= 37 -> DebugSwitchState.UNKNOWN
            else -> DebugSwitchState.OFF
        }
    }

    fun isWirelessDebuggingEnabled(context: Context): Boolean =
        wirelessDebuggingState(context) != DebugSwitchState.OFF

    fun isAdbEnabled(context: Context): Boolean {
        val raw = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
        }.getOrNull() ?: return true
        return raw || Build.VERSION.SDK_INT >= 37
    }

    fun resolveSuBinary(): Array<String>? {
        cachedSuCommand?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (lastRootProbeAt >= 0 && now - lastRootProbeAt < ROOT_REPROBE_MS) return null
        lastRootProbeAt = now

        val candidates = listOf(
            arrayOf("su", "-M", "-c"),
            arrayOf("su", "-mm", "-c"),
            arrayOf("su", "-c")
        )
        for (cmd in candidates) {
            if (probeSu(cmd)) {
                cachedSuCommand = cmd
                return cmd
            }
        }
        return null
    }

    fun isRooted(): Boolean = resolveSuBinary() != null

    private fun probeSu(prefix: Array<String>): Boolean = runCatching {
        val full = prefix + "id -u"
        val proc = ProcessBuilder(*full)
            .redirectErrorStream(true)
            .start()
        val finished = proc.waitFor(2500, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return false
        }
        val out = BufferedReader(InputStreamReader(proc.inputStream)).readText().trim()
        proc.exitValue() == 0 && out == "0"
    }.getOrDefault(false)
}
