package com.termux.agent.start

import android.content.Context
import android.util.Log
import com.termux.agent.adb.AdbClient
import com.termux.agent.adb.AdbKey
import com.termux.agent.adb.AdbKeyException
import com.termux.agent.adb.AdbPairingRequiredException
import com.termux.agent.adb.PreferenceAdbKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

private const val TAG = "TermuxAdbStarter"

class StartProgress {
    @Volatile
    var starterSent = false
}

object AdbStarter {

    private const val CONNECT_TIMEOUT_MS = 250
    private const val CONNECT_ATTEMPTS = 5
    const val FRESH_PORT_TIMEOUT_MS = 10_000L

    private fun Throwable.isCertificateUnknown(): Boolean {
        val text = message ?: return false
        return text.contains("CERTIFICATE_UNKNOWN", ignoreCase = true) ||
            text.contains("SSLV3_ALERT", ignoreCase = true)
    }

    fun buildServerLaunchCommand(context: Context, token: String): String =
        buildServerLaunchShellCommand(
            apkPath = context.applicationInfo.sourceDir,
            packageName = context.packageName,
            authority = "${context.packageName}.agent",
            token = token
        )

    internal fun buildServerLaunchShellCommand(
        apkPath: String,
        packageName: String,
        authority: String,
        token: String
    ): String {
        val mainClass = "com.termux.agent.server.TermuxAgentServer"
        // TermuxAgentServer.main() safely reaps older instances via exact cmdline[0] match;
        // avoid `pkill -f` here because toybox would match and kill this outer `sh -c` parent.
        return "(setsid env CLASSPATH='$apkPath' /system/bin/app_process " +
            "-Djava.class.path='$apkPath' " +
            "/system/bin --nice-name=termux_agent_server " +
            "$mainClass --apk='$apkPath' --pkg='$packageName' --authority='$authority' --token='$token' " +
            "</dev/null >/dev/null 2>&1 &); sleep 0.2"
    }

    suspend fun startAdb(
        context: Context,
        port: Int,
        token: String,
        openTcpPort: Boolean = false,
        progress: StartProgress? = null,
        connectAttempts: Int = CONNECT_ATTEMPTS
    ): Int = withContext(Dispatchers.IO) {
        val key = runCatching {
            AdbKey(context, PreferenceAdbKeyStore(AgentSettings.prefs(context)))
        }.getOrElse {
            if (it is CancellationException) throw it
            throw AdbKeyException(it)
        }

        var activePort = port
        val tcpPort = AgentSettings.getTcpPort(context)
        val usbDebugging = AgentEnvironment.isAdbEnabled(context)
        var viaTcp = !AgentEnvironment.isTlsSupported() ||
            (activePort > 0 && activePort == AgentEnvironment.getAdbTcpPort())

        if (openTcpPort && usbDebugging && activePort != tcpPort) {
            AdbClient("127.0.0.1", activePort, key).use { client ->
                client.connectForPairing()
                activePort = tcpPort
                viaTcp = true
                runCatching { client.command("tcpip:$activePort") }
                    .onFailure {
                        if (it !is EOFException && it !is SocketException && it !is SSLException) throw it
                    }
                waitForPortAvailable("127.0.0.1", activePort)
            }
        }

        AdbClient("127.0.0.1", activePort, key).use { client ->
            connectWithRetry(client, connectAttempts)
            currentCoroutineContext().ensureActive()
            progress?.starterSent = true
            val output = StringBuilder()
            client.command("shell:${buildServerLaunchCommand(context, token)}") {
                output.append(String(it))
            }
            if (output.isNotEmpty()) {
                Log.d(TAG, "Launch shell output: $output")
            }
        }

        AgentSettings.setLastTransport(
            context,
            if (viaTcp) AgentSettings.TRANSPORT_TCP else AgentSettings.TRANSPORT_TLS
        )
        if (viaTcp && AgentSettings.getAutoDisableWireless(context) && !AgentSettings.getForceNoWifi(context)) {
            AgentPermissions.writeGlobalSetting(context, "adb_wifi_enabled", 0)
        }
        AgentSettings.setLastAdbPort(context, activePort)
        AgentSettings.setLastAdbPortBoot(context, AgentEnvironment.getBootCount(context))
        activePort
    }

    suspend fun openTcpPort(
        context: Context,
        port: Int,
        knownWirelessPort: Int? = null,
        hunt: WirelessPortHunt? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!AgentEnvironment.isWifiConnected(context) && !AgentSettings.getForceNoWifi(context)) {
            return@withContext false
        }
        AgentPermissions.writeGlobalSetting(context, "adb_wifi_enabled", 1)
        val key = AdbKey(context, PreferenceAdbKeyStore(AgentSettings.prefs(context)))
        val failed = mutableSetOf<Int>()
        var searches = 0
        var candidate = knownWirelessPort

        for (round in 0..MAX_FRESH_PORT_SEARCHES) {
            var found: Int? = candidate?.takeIf { isPortListening(it) }
            if (found == null) {
                if (candidate != null) failed += candidate
                if (searches < MAX_FRESH_PORT_SEARCHES) {
                    searches++
                    found = freshWirelessPort(context, failed, FRESH_PORT_TIMEOUT_MS, hunt)
                }
            }
            val wirelessPort = found ?: return@withContext false

            var tcpipSent = false
            try {
                AdbClient("127.0.0.1", wirelessPort, key).use { client ->
                    client.connectForPairing()
                    tcpipSent = true
                    runCatching { client.command("tcpip:$port") }
                        .onFailure {
                            if (it !is EOFException && it !is SocketException && it !is SSLException) throw it
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val decision = decideAfterConnectFailure(
                    wireless = true,
                    commandSent = tcpipSent,
                    failure = classifyConnectFailure(e),
                    searchesUsed = searches
                )
                if (decision == PortRetryDecision.SEARCH_AGAIN) {
                    failed += wirelessPort
                    candidate = null
                    continue
                }
                if (e is AdbPairingRequiredException) throw e
                return@withContext false
            }
            return@withContext waitForPortAvailable("127.0.0.1", port)
        }
        false
    }

    suspend fun freshWirelessPort(
        context: Context,
        exclude: Set<Int>,
        timeoutMs: Long,
        hunt: WirelessPortHunt? = null
    ): Int? {
        if (!AgentEnvironment.isTlsSupported()) return null
        val search = hunt ?: WirelessPortHunt(context, forced = false)
        return try {
            search.fresh(exclude, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Wireless port search failed: ${e.message}")
            null
        } finally {
            if (hunt == null) search.close()
        }
    }

    suspend fun isPortListening(port: Int, host: String = "127.0.0.1"): Boolean =
        withContext(Dispatchers.IO) {
            if (port <= 0) return@withContext false
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            }.isSuccess
        }

    suspend fun firstListening(candidates: List<Int>, host: String = "127.0.0.1"): Int? =
        candidates.filter { it > 0 }.distinct().firstOrNull { isPortListening(it, host) }

    private suspend fun waitForPortAvailable(
        host: String,
        port: Int,
        timeoutMs: Long = 15_000L
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortListening(port, host)) return@withContext true
            delay(300)
        }
        false
    }

    private suspend fun AdbClient.connectForPairing() {
        try {
            connect()
        } catch (e: Exception) {
            if (e is AdbPairingRequiredException) throw e
            if (e is SSLException || e.isCertificateUnknown()) {
                throw AdbPairingRequiredException(e.message, e)
            }
            throw e
        }
    }

    private suspend fun connectWithRetry(client: AdbClient, maxAttempts: Int) {
        var delayTime = 0L
        for (attempt in 1..maxAttempts) {
            try {
                if (delayTime > 0) delay(delayTime)
                client.connectForPairing()
                break
            } catch (e: Exception) {
                if (
                    attempt == maxAttempts ||
                    e is CancellationException ||
                    e is SocketTimeoutException ||
                    e is AdbPairingRequiredException
                ) throw e
                delayTime += 1000L
            }
        }
    }
}
