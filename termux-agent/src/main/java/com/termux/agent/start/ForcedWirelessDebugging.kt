package com.termux.agent.start

import android.content.ContentResolver
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "TermuxForcedWadb"

/**
 * Forces `adb_wifi_enabled` on when the device has no Wi-Fi connection (Android 11-16),
 * pairing a local-only hotspot (for mDNS interface) with 20ms-cadence writes to win
 * the race against the framework's offline Wi-Fi constraint monitor.
 */
object ForcedWirelessDebugging {

    private const val KEY_WIFI_ENABLED = "adb_wifi_enabled"
    private const val BURST_COUNT = 80
    private const val BURST_INTERVAL_MS = 20L
    private const val INTERVAL_MS = 20L
    private const val WINDOW_MS = 120_000L
    private const val HOTSPOT_TIMEOUT_MS = 12_000L
    private const val HOTSPOT_ATTEMPTS = 3
    private const val HOTSPOT_RETRY_DELAY_MS = 1_500L

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var lastRefusal: String? = null
        private set

    suspend fun nudge(
        context: Context,
        log: (String) -> Unit = {},
        failFast: Boolean = false,
        onFailFast: (String) -> Unit = {},
        onHotspotSettled: () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val hotspotUp = startHotspot(context, log, if (failFast) 1 else HOTSPOT_ATTEMPTS)
        if (!hotspotUp && failFast) {
            onFailFast(lastRefusal ?: "local-only hotspot refused")
            return@withContext
        }
        onHotspotSettled()

        var passes = 0
        var writes = 0

        fun pass(): Boolean {
            passes++
            if (isEnabled(cr)) return true
            writes++
            return writeOnce(cr)
        }

        if (!pass()) {
            log("cannot write $KEY_WIFI_ENABLED (missing WRITE_SECURE_SETTINGS)")
            return@withContext
        }
        log(if (writes == 0) "wireless debugging already enabled" else "forcing wireless debugging")

        val deadline = System.currentTimeMillis() + WINDOW_MS
        repeat(BURST_COUNT - 1) {
            if (!isActive) return@withContext
            pass()
            delay(BURST_INTERVAL_MS)
        }

        while (isActive && System.currentTimeMillis() < deadline) {
            pass()
            delay(INTERVAL_MS)
        }

        Log.i(TAG, "Finished nudge: writes=$writes passes=$passes hotspot=${reservation != null}")
    }

    fun releaseHotspot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { reservation?.close() }
            .onFailure { Log.w(TAG, "Failed to close local-only hotspot", it) }
        reservation = null
    }

    private fun writeOnce(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.putInt(cr, KEY_WIFI_ENABLED, 1) }
            .onFailure { Log.w(TAG, "Failed to write $KEY_WIFI_ENABLED", it) }
            .isSuccess

    private fun isEnabled(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.getInt(cr, KEY_WIFI_ENABLED, 0) == 1 }.getOrDefault(false)

    private suspend fun startHotspot(
        context: Context,
        log: (String) -> Unit,
        attempts: Int
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            log("local-only hotspot requires Android 8+")
            return false
        }
        if (reservation != null) return true

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false

        lastRefusal = null
        repeat(attempts) { attempt ->
            val res = attemptHotspot(wifi)
            if (res.reservation != null) {
                reservation = res.reservation
                log("local-only hotspot active")
                return true
            }
            if (!res.retryable) {
                log("local-only hotspot refused: $lastRefusal")
                return false
            }
            if (attempt < attempts - 1) delay(HOTSPOT_RETRY_DELAY_MS)
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun attemptHotspot(wifi: WifiManager): HotspotAttempt {
        var retryable = true
        val started = withTimeoutOrNull(HOTSPOT_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<WifiManager.LocalOnlyHotspotReservation?> { cont ->
                    try {
                        wifi.startLocalOnlyHotspot(
                            object : WifiManager.LocalOnlyHotspotCallback() {
                                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                                    if (cont.isActive) cont.resume(res)
                                }
                                override fun onStopped() {
                                    if (cont.isActive) cont.resume(null)
                                }
                                override fun onFailed(reason: Int) {
                                    lastRefusal = reasonName(reason)
                                    if (cont.isActive) cont.resume(null)
                                }
                            },
                            Handler(Looper.getMainLooper())
                        )
                    } catch (e: Throwable) {
                        retryable = e !is SecurityException
                        lastRefusal = "${e.javaClass.simpleName}: ${e.message}"
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
        return HotspotAttempt(started, retryable)
    }

    private class HotspotAttempt(
        val reservation: WifiManager.LocalOnlyHotspotReservation?,
        val retryable: Boolean
    )

    private fun reasonName(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no channel"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "generic error"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "incompatible mode"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "tethering disallowed"
        else -> "reason=$reason"
    }
}
