package com.termux.agent.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "TermuxAgentAdbMdns"

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val onResolved: (Pair<String, Int>) -> Unit
) {

    @Volatile
    private var running = false

    @Volatile
    private var serviceName: String? = null

    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    // NsdManager resolves one service at a time on Android 11-13; queue concurrent discoveries.
    @Volatile
    private var pendingResolve: NsdServiceInfo? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolveLock = Any()

    fun start() {
        if (running) return
        running = true
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices failed for $serviceType", e)
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        synchronized(resolveLock) {
            resolveQueue.clear()
            pendingResolve = null
        }
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "stopServiceDiscovery failed for $serviceType", e)
        }
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        synchronized(resolveLock) {
            if (pendingResolve == null) {
                resolve(info)
            } else {
                resolveQueue.add(info)
            }
        }
    }

    private fun resolve(info: NsdServiceInfo) {
        pendingResolve = info
        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(info, ResolveListener(this))
        } catch (e: Exception) {
            Log.w(TAG, "resolveService failed for ${info.serviceName}", e)
            drainResolveQueue()
        }
    }

    private fun onResolveFailed() {
        drainResolveQueue()
    }

    private fun drainResolveQueue() {
        synchronized(resolveLock) {
            pendingResolve = null
            if (!running) {
                resolveQueue.clear()
                return
            }
            val next = resolveQueue.poll() ?: return
            resolve(next)
        }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) {
            onResolved("" to -1)
        }
    }

    private fun onServiceResolved(resolved: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        val host = resolved.host?.hostAddress
        if (host != null && running && isLocalHostAddress(host) && isPortBound(host, resolved.port)) {
            serviceName = resolved.serviceName
            onResolved(host to resolved.port)
        }
        drainResolveQueue()
    }

    private fun isLocalHostAddress(host: String): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().any { iface ->
            iface.inetAddresses.asSequence().any { it.hostAddress == host }
        }
    }.getOrDefault(false)

    private fun isPortBound(host: String, port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress(host, port), 1)
            false
        }
    } catch (_: IOException) {
        true
    }

    internal class DiscoveryListener(private val mdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStartDiscoveryFailed $serviceType code=$errorCode")
        }
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            mdns.onServiceFound(serviceInfo)
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            mdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val mdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
            mdns.onResolveFailed()
        }
        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            mdns.onServiceResolved(nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    }
}
