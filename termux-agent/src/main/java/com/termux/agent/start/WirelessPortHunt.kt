package com.termux.agent.start

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.termux.agent.adb.AdbMdns
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

private const val TAG = "TermuxWirelessPortHunt"

class WirelessPortHunt(
    context: Context,
    private val forced: Boolean,
    private val onForcing: () -> Unit = {},
    private val onWaitingForUnlock: () -> Unit = {},
    private val failFastOnHotspot: Boolean = false
) {

    private data class State(
        val port: Int = 0,
        val discovering: Boolean = false,
        val failure: Throwable? = null
    )

    private val appContext = context.applicationContext
    private val cr = appContext.contentResolver
    private val state = MutableStateFlow(State())
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> fail(e) }
    )
    private val startMutex = Mutex()
    private val lock = Any()

    private var started = false

    @Volatile
    private var closed = false
    private var mdns: AdbMdns? = null
    private var nudgeJob: Job? = null
    private var observer: ContentObserver? = null
    private var unlockReceiver: BroadcastReceiver? = null

    @Volatile
    private var awaitingAuth = false

    suspend fun fresh(exclude: Set<Int>, timeoutMs: Long): Int? {
        if (closed) return null
        ensureStarted()
        while (true) {
            state.first { it.discovering || it.failure != null || usable(it.port, exclude) }
            val reached = withTimeoutOrNull(timeoutMs) {
                state.first { it.failure != null || !it.discovering || usable(it.port, exclude) }
            } ?: return null
            reached.failure?.let { throw it }
            if (usable(reached.port, exclude)) return reached.port
        }
    }

    private fun usable(port: Int, exclude: Set<Int>) = port > 0 && port !in exclude

    private suspend fun ensureStarted() {
        startMutex.withLock {
            if (started || closed) return
            started = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startLocked()
            } else {
                fail(TimeoutException("mDNS wireless ADB discovery requires Android 11+"))
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun startLocked() {
        val discovery = AdbMdns(appContext, AdbMdns.TLS_CONNECT) { (_, port) ->
            if (port > 0) {
                Log.i(TAG, "mDNS resolved wireless ADB port $port")
                state.update { it.copy(port = port) }
            }
        }
        val watcher = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                when (AgentEnvironment.wirelessDebuggingState(appContext)) {
                    DebugSwitchState.OFF -> if (awaitingAuth && !forced) {
                        fail(SecurityException("Network is not authorized for wireless debugging"))
                    } else {
                        handleAuth()
                    }
                    DebugSwitchState.ON -> startDiscovery()
                    DebugSwitchState.UNKNOWN -> if (!forced) startDiscovery()
                }
            }
        }
        synchronized(lock) {
            if (closed) return
            mdns = discovery
            observer = watcher
        }

        AgentPermissions.writeGlobalSetting(appContext, "adb_wifi_enabled", 1)
        cr.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, watcher)
        if (closed) {
            runCatching { cr.unregisterContentObserver(watcher) }
            return
        }

        if (forced) {
            onForcing()
            nudgeJob = scope.launch {
                ForcedWirelessDebugging.nudge(
                    appContext,
                    { msg -> Log.i(TAG, "Forced WADB: $msg") },
                    failFast = failFastOnHotspot,
                    onFailFast = { reason ->
                        fail(IllegalStateException("Local-only hotspot refused: $reason"))
                    }
                ) {
                    startDiscovery()
                }
            }
        } else {
            startDiscovery()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startDiscovery() {
        val discovery = synchronized(lock) { if (closed) null else mdns } ?: return
        discovery.start()
        state.update { it.copy(discovering = true) }
    }

    private fun fail(e: Throwable) = state.update { it.copy(failure = e) }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun handleAuth() {
        synchronized(lock) {
            if (unlockReceiver != null || closed) return
            val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km?.isKeyguardLocked == true) {
                onWaitingForUnlock()
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action != Intent.ACTION_USER_PRESENT) return
                        synchronized(lock) {
                            runCatching { appContext.unregisterReceiver(this) }
                            unlockReceiver = null
                        }
                        AgentPermissions.writeGlobalSetting(appContext, "adb_wifi_enabled", 1)
                    }
                }
                unlockReceiver = receiver
                appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            } else {
                awaitingAuth = true
            }
        }

        if (!forced) {
            state.update { it.copy(discovering = false) }
            synchronized(lock) { mdns }?.stop()
        }
    }

    fun close() {
        val toStop: AdbMdns?
        val toUnobserve: ContentObserver?
        val toUnregister: BroadcastReceiver?
        synchronized(lock) {
            if (closed) return
            closed = true
            toStop = mdns
            toUnobserve = observer
            toUnregister = unlockReceiver
            unlockReceiver = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { toStop?.stop() }
        toUnobserve?.let { runCatching { cr.unregisterContentObserver(it) } }
        toUnregister?.let { runCatching { appContext.unregisterReceiver(it) } }
        nudgeJob?.cancel()
        scope.cancel()
        ForcedWirelessDebugging.releaseHotspot()
    }
}
