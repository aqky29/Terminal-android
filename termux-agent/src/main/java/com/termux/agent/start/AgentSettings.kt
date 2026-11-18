package com.termux.agent.start

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

object AgentSettings {
    private const val PREF_NAME = "termux_agent_settings"
    private const val KEY_LAST_ADB_PORT = "last_adb_port"
    private const val KEY_LAST_ADB_PORT_BOOT = "last_adb_port_boot"
    private const val KEY_LAST_ADB_TRANSPORT = "last_adb_transport"
    private const val KEY_TCP_PORT = "tcp_port"
    private const val KEY_TCP_MODE = "tcp_mode"
    private const val KEY_FORCE_NO_WIFI = "force_no_wifi"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_AUTO_DISABLE_WIRELESS = "auto_disable_wireless"
    private const val KEY_LAST_START_METHOD = "last_start_method"
    private const val KEY_SERVER_AUTH_TOKEN = "server_auth_token"

    const val TRANSPORT_NONE = 0
    const val TRANSPORT_TLS = 1
    const val TRANSPORT_TCP = 2
    const val TRANSPORT_ROOT = 3

    fun prefs(context: Context): SharedPreferences {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { context.applicationContext.createDeviceProtectedStorageContext() }
                .getOrDefault(context.applicationContext)
        } else {
            context.applicationContext
        }
        return storageContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getTcpPort(context: Context): Int = prefs(context).getInt(KEY_TCP_PORT, 5555)
    fun setTcpPort(context: Context, port: Int) = prefs(context).edit().putInt(KEY_TCP_PORT, port).apply()

    fun getTcpMode(context: Context): Boolean = prefs(context).getBoolean(KEY_TCP_MODE, true)
    fun setTcpMode(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_TCP_MODE, enabled).apply()

    fun getForceNoWifi(context: Context): Boolean = prefs(context).getBoolean(KEY_FORCE_NO_WIFI, true)
    fun setForceNoWifi(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_FORCE_NO_WIFI, enabled).apply()

    fun getAutoStart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START, true)
    fun setAutoStart(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()

    fun getAutoDisableWireless(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_DISABLE_WIRELESS, false)
    fun setAutoDisableWireless(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_DISABLE_WIRELESS, enabled).apply()

    fun getLastAdbPort(context: Context): Int = prefs(context).getInt(KEY_LAST_ADB_PORT, -1)
    fun setLastAdbPort(context: Context, port: Int) = prefs(context).edit().putInt(KEY_LAST_ADB_PORT, port).apply()

    fun getLastAdbPortBoot(context: Context): Int = prefs(context).getInt(KEY_LAST_ADB_PORT_BOOT, -1)
    fun setLastAdbPortBoot(context: Context, bootCount: Int) =
        prefs(context).edit().putInt(KEY_LAST_ADB_PORT_BOOT, bootCount).apply()

    fun getLastTransport(context: Context): Int = prefs(context).getInt(KEY_LAST_ADB_TRANSPORT, TRANSPORT_NONE)
    fun setLastTransport(context: Context, transport: Int) =
        prefs(context).edit().putInt(KEY_LAST_ADB_TRANSPORT, transport).apply()

    fun getLastStartMethod(context: Context): String =
        prefs(context).getString(KEY_LAST_START_METHOD, "none") ?: "none"

    fun setLastStartMethod(context: Context, method: String) =
        prefs(context).edit().putString(KEY_LAST_START_METHOD, method).apply()

    fun getOrCreateServerToken(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_SERVER_AUTH_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val created = java.util.UUID.randomUUID().toString()
        p.edit().putString(KEY_SERVER_AUTH_TOKEN, created).apply()
        return created
    }
}
