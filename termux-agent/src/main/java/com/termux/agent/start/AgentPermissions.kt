package com.termux.agent.start

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.termux.agent.TermuxAgentClient

private const val TAG = "TermuxAgentPermissions"

object AgentPermissions {

    val PRIVILEGED_PERMISSIONS: List<String> = buildList {
        add(Manifest.permission.WRITE_SECURE_SETTINGS)
        add(Manifest.permission.DUMP)
        add(Manifest.permission.READ_LOGS)
        add(Manifest.permission.PACKAGE_USAGE_STATS)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun hasWriteSecureSettings(context: Context): Boolean =
        hasPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS)

    fun snapshotPermissions(context: Context): Map<String, Boolean> =
        PRIVILEGED_PERMISSIONS.associateWith { hasPermission(context, it) }

    fun writeGlobalSetting(context: Context, key: String, value: Int): Boolean =
        writeGlobal(context, key, value.toString()) {
            Settings.Global.putInt(context.contentResolver, key, value)
        }

    fun writeGlobalLongSetting(context: Context, key: String, value: Long): Boolean =
        writeGlobal(context, key, value.toString()) {
            Settings.Global.putLong(context.contentResolver, key, value)
        }

    private fun writeGlobal(context: Context, key: String, value: String, direct: () -> Unit): Boolean {
        val directSuccess = runCatching { direct() }.isSuccess
        if (AgentEnvironment.isDebugSwitchReadRedacted(key)) {
            if (directSuccess) return true
            if (Looper.myLooper() == Looper.getMainLooper()) return false
            val res = TermuxAgentClient.execSync("settings put global $key $value && echo ok", timeoutMs = 4000)
            return res.exitCode == 0 && res.stdout.trim() == "ok"
        }

        if (readGlobalSetting(context, key) == value) return true
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        TermuxAgentClient.execSync("settings put global $key $value", timeoutMs = 4000)
        return readGlobalSetting(context, key) == value
    }

    private fun readGlobalSetting(context: Context, key: String): String? = runCatching {
        Settings.Global.getString(context.contentResolver, key)
    }.getOrNull()

    /**
     * Self-grants Termux's declared protected permissions and disables Android 12+ Phantom Process Killer
     * through the currently active privileged server.
     */
    fun grantSelfPermissions(context: Context): Map<String, Boolean> {
        if (!TermuxAgentClient.isAlive()) return snapshotPermissions(context)
        val pkg = context.packageName
        val commands = mutableListOf<String>()
        for (perm in PRIVILEGED_PERMISSIONS) {
            if (!hasPermission(context, perm)) {
                commands += "pm grant $pkg $perm 2>/dev/null || true"
            }
        }
        // Grant PACKAGE_USAGE_STATS AppOp as well on ROMs where pm grant alone leaves AppOps restricted
        commands += "appops set $pkg GET_USAGE_STATS allow 2>/dev/null || true"
        // Allow RECORD_AUDIO AppOp as well so CLI audio capture works smoothly when agent is active
        commands += "appops set $pkg RECORD_AUDIO allow 2>/dev/null || true"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            commands += "settings put global settings_enable_monitor_phantom_procs false 2>/dev/null || true"
            commands += "/system/bin/device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null || true"
        }
        if (commands.isNotEmpty()) {
            val res = TermuxAgentClient.execSync(commands.joinToString("; "), timeoutMs = 8000)
            if (res.exitCode != 0) {
                Log.w(TAG, "Self-grant finished with exit=${res.exitCode}: ${res.stderr}")
            }
        }
        return snapshotPermissions(context)
    }
}
