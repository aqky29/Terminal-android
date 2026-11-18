package com.termux.agent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import com.termux.agent.start.AgentEnvironment
import com.termux.agent.start.AgentPermissions
import com.termux.agent.start.AgentSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DeviceSnapshotCollector {

    fun collectStatusJson(context: Context): JSONObject {
        val alive = TermuxAgentClient.isAlive()
        val permissions = AgentPermissions.snapshotPermissions(context)
        return JSONObject().apply {
            put("serverRunning", alive)
            put("serverUid", if (alive) TermuxAgentClient.getUid() else JSONObject.NULL)
            put("serverPid", if (alive) TermuxAgentClient.getPid() else JSONObject.NULL)
            put("serverSeContext", if (alive) (TermuxAgentClient.getSeContext() ?: JSONObject.NULL) else JSONObject.NULL)
            put("lastStartMethod", AgentSettings.getLastStartMethod(context))
            put("wifiConnected", AgentEnvironment.isWifiConnected(context))
            put("wirelessDebugging", AgentEnvironment.wirelessDebuggingState(context).name)
            put("adbTcpPort", AgentEnvironment.getAdbTcpPort())
            put("adbTlsPort", AgentEnvironment.getAdbTlsPort())
            put("rooted", AgentEnvironment.isRooted())
            put("permissions", JSONObject(permissions))
        }
    }

    fun collectInfoJson(context: Context): JSONObject {
        val serverBundle = TermuxAgentClient.snapshot()
        val root = JSONObject()

        root.put("agent", collectStatusJson(context))
        root.put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("androidRelease", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("securityPatch", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else JSONObject.NULL)
            put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("uptimeMs", SystemClock.elapsedRealtime())
        })

        // Battery: merge Android BatteryManager with privileged dumpsys battery when available
        val batteryJson = collectBattery(context, serverBundle?.getString("dumpsysBattery"))
        root.put("battery", batteryJson)

        // Memory: prefer /proc/meminfo from server, fallback to ActivityManager
        val memJson = parseMemInfo(serverBundle?.getString("meminfo"))
            ?: collectAppMemory(context)
        root.put("memory", memJson)

        // Storage
        root.put("storage", JSONObject().apply {
            val dataTotal = serverBundle?.getLong("dataTotalBytes", -1L) ?: -1L
            val dataAvail = serverBundle?.getLong("dataAvailableBytes", -1L) ?: -1L
            if (dataTotal > 0) {
                put("dataTotalBytes", dataTotal)
                put("dataAvailableBytes", dataAvail)
            } else {
                runCatching {
                    val stat = StatFs(context.filesDir.absolutePath)
                    put("dataTotalBytes", stat.totalBytes)
                    put("dataAvailableBytes", stat.availableBytes)
                }
            }
            val sdTotal = serverBundle?.getLong("sdcardTotalBytes", -1L) ?: -1L
            val sdAvail = serverBundle?.getLong("sdcardAvailableBytes", -1L) ?: -1L
            if (sdTotal > 0) {
                put("sdcardTotalBytes", sdTotal)
                put("sdcardAvailableBytes", sdAvail)
            }
        })

        serverBundle?.getString("loadavg")?.takeIf { it.isNotBlank() }?.let { load ->
            root.put("loadavg", load)
        }
        serverBundle?.getString("topActivity")?.takeIf { it.isNotBlank() }?.let { top ->
            root.put("topActivity", top)
        }
        return root
    }

    private fun collectBattery(context: Context, dumpsys: String?): JSONObject {
        val json = JSONObject()
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) json.put("levelPercent", (level * 100) / scale)
            json.put("status", intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
            json.put("plugged", intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (temp > 0) json.put("temperatureC", temp / 10.0)
            val mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (mv > 0) json.put("voltageMv", mv)
            json.put("technology", intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
        }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (bm != null) {
            val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (cur != Int.MIN_VALUE) json.put("currentUa", cur)
            val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (cc != Int.MIN_VALUE && cc > 0) json.put("chargeCounterUah", cc)
        }
        if (!dumpsys.isNullOrBlank()) {
            fun match(regex: Regex): String? = regex.find(dumpsys)?.groupValues?.getOrNull(1)
            match(Regex("""mSavedBatteryAsoc:\s*\[(\d+)]"""))?.toIntOrNull()?.let {
                json.put("samsungAsocPercent", it)
            }
            match(Regex("""mSavedBatteryBsoh:\s*([0-9.]+)"""))?.toDoubleOrNull()?.let {
                json.put("samsungBsohPercent", it)
            }
            match(Regex("""mSavedBatteryUsage:\s*\[(\d+)]"""))?.toDoubleOrNull()?.let {
                json.put("batteryCycles", it / 100.0)
            }
        }
        return json
    }

    private fun parseMemInfo(raw: String?): JSONObject? {
        val text = raw?.takeIf { it.isNotBlank() }
            ?: runCatching { File("/proc/meminfo").readText() }.getOrNull()
            ?: return null
        val json = JSONObject()
        val wanted = setOf("MemTotal", "MemFree", "MemAvailable", "SwapTotal", "SwapFree")
        for (line in text.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            if (key !in wanted) continue
            val kb = line.substring(idx + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
            json.put("${key}Kb", kb)
        }
        return if (json.length() > 0) json else null
    }

    private fun collectAppMemory(context: Context): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        return JSONObject().apply {
            put("totalMemBytes", mi.totalMem)
            put("availMemBytes", mi.availMem)
            put("lowMemory", mi.lowMemory)
        }
    }
}
