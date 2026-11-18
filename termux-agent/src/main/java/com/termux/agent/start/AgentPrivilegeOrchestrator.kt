package com.termux.agent.start

import android.content.Context
import android.util.Log
import com.termux.agent.TermuxAgentClient
import com.termux.agent.adb.AdbPairingRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val TAG = "TermuxAgentOrchestrator"

data class StartOutcome(
    val ok: Boolean,
    val method: String,
    val uid: Int,
    val pid: Int,
    val port: Int,
    val message: String,
    val needsPairing: Boolean = false,
    val needsWifiOrRoot: Boolean = false
)

/**
 * Single auto-escalation engine for Variant B1:
 * Root (`su`) -> Classic ADB TCP (`service.adb.tcp.port` / `5555`) -> Wireless ADB (`mDNS` / No-Wi-Fi).
 */
object AgentPrivilegeOrchestrator {

    private val mutex = Mutex()

    suspend fun ensureStarted(
        context: Context,
        preferNoWifi: Boolean = AgentSettings.getForceNoWifi(context),
        enableTcpMode: Boolean = AgentSettings.getTcpMode(context),
        timeoutMs: Long = 135_000L
    ): StartOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (TermuxAgentClient.isAlive()) {
                val perms = AgentPermissions.grantSelfPermissions(context)
                return@withContext StartOutcome(
                    ok = true,
                    method = AgentSettings.getLastStartMethod(context),
                    uid = TermuxAgentClient.getUid(),
                    pid = TermuxAgentClient.getPid(),
                    port = AgentSettings.getLastAdbPort(context),
                    message = "Termux Agent Server already active (permissions=${perms.count { it.value }}/${perms.size})"
                )
            }

            // 1. Try Root first (fastest, works offline & survives reboot)
            val suCmd = AgentEnvironment.resolveSuBinary()
            if (suCmd != null) {
                val rootOutcome = startViaRoot(context, suCmd)
                if (rootOutcome.ok) return@withContext rootOutcome
                Log.w(TAG, "Root start failed (${rootOutcome.message}), falling back to ADB")
            }

            // 2. Try ADB escalation (Classic TCP port -> Live TLS port -> mDNS / Forced No-Wi-Fi)
            startViaAdb(context, preferNoWifi, enableTcpMode, timeoutMs)
        }
    }

    private suspend fun startViaRoot(context: Context, suPrefix: Array<String>): StartOutcome {
        val token = AgentSettings.getOrCreateServerToken(context)
        TermuxAgentClient.expectToken(token)
        val script = AdbStarter.buildServerLaunchCommand(context, token)

        val launched = runCatching {
            val proc = ProcessBuilder(*(suPrefix + script))
                .redirectErrorStream(true)
                .start()
            proc.waitFor(5000, TimeUnit.MILLISECONDS)
            proc.exitValue() == 0
        }.getOrDefault(false)

        if (!launched) {
            return StartOutcome(false, "root", -1, -1, -1, "su failed to launch server")
        }

        val service = runCatching { TermuxAgentClient.awaitBinder(10_000L) }.getOrNull()
            ?: return StartOutcome(false, "root", -1, -1, -1, "Timed out waiting for root server binder")

        AgentSettings.setLastTransport(context, AgentSettings.TRANSPORT_ROOT)
        AgentSettings.setLastStartMethod(context, "root")
        AgentPermissions.grantSelfPermissions(context)

        return StartOutcome(
            ok = true,
            method = "root",
            uid = runCatching { service.uid }.getOrDefault(0),
            pid = runCatching { service.pid }.getOrDefault(-1),
            port = -1,
            message = "Started Termux Agent Server via root"
        )
    }

    private suspend fun startViaAdb(
        context: Context,
        preferNoWifi: Boolean,
        enableTcpMode: Boolean,
        timeoutMs: Long
    ): StartOutcome {
        val tcpPortProp = AgentEnvironment.getAdbTcpPort()
        val tlsPortProp = AgentEnvironment.getAdbTlsPort()
        val wantedTcp = AgentSettings.getTcpPort(context)
        val lastPort = AgentSettings.getLastAdbPort(context)
        val verdict = StartTransport.rememberedPortVerdict(
            lastPort = lastPort,
            lastWasClassic = AgentSettings.getLastTransport(context) == AgentSettings.TRANSPORT_TCP,
            lastBoot = AgentSettings.getLastAdbPortBoot(context),
            currentBoot = AgentEnvironment.getBootCount(context),
            appTcpPort = wantedTcp
        )

        val candidates = StartTransport.candidatePorts(
            configured = tcpPortProp,
            tlsProperty = tlsPortProp,
            remembered = lastPort.takeIf { verdict == StartTransport.RememberedPort.USABLE },
            appTcpPort = wantedTcp
        )

        var chosenCandidate = candidates.firstOrNull { AdbStarter.isPortListening(it.port) }
        var hunt: WirelessPortHunt? = null

        try {
            var activePort = chosenCandidate?.port
            var methodLabel = when (chosenCandidate?.source) {
                StartTransport.PortSource.CLASSIC_PROPERTY,
                StartTransport.PortSource.APP_SETTING -> "adb_tcp"
                else -> "adb_wireless"
            }

            if (activePort == null) {
                if (!AgentEnvironment.isTlsSupported()) {
                    return StartOutcome(
                        ok = false,
                        method = "adb",
                        uid = -1,
                        pid = -1,
                        port = -1,
                        message = "No open ADB TCP port (run `adb tcpip $wantedTcp` once via USB on Android < 11)",
                        needsWifiOrRoot = true
                    )
                }

                val hasWifi = AgentEnvironment.isWifiConnected(context)
                val canForceNoWifi = preferNoWifi &&
                    !AgentEnvironment.isAdbWifiOverhauled() &&
                    AgentPermissions.hasWriteSecureSettings(context)

                if (!hasWifi && !canForceNoWifi) {
                    val reason = if (!AgentPermissions.hasWriteSecureSettings(context)) {
                        "Wi-Fi is off and WRITE_SECURE_SETTINGS is not granted yet. Connect to Wi-Fi (or run `tdev pair` once)."
                    } else {
                        "Wi-Fi is off and Android 17+ requires a real Wi-Fi network or USB TCP mode."
                    }
                    return StartOutcome(
                        ok = false,
                        method = "adb_wireless",
                        uid = -1,
                        pid = -1,
                        port = -1,
                        message = reason,
                        needsWifiOrRoot = true
                    )
                }

                val forced = !hasWifi && canForceNoWifi
                val hasWriteSecure = AgentPermissions.hasWriteSecureSettings(context)
                val switchOff = !forced && AgentEnvironment.wirelessDebuggingState(context) == DebugSwitchState.OFF
                if (switchOff && !hasWriteSecure) {
                    return StartOutcome(
                        ok = false,
                        method = "adb_wireless",
                        uid = -1,
                        pid = -1,
                        port = -1,
                        message = "Wireless Debugging is OFF and WRITE_SECURE_SETTINGS is not granted yet. Enable Wireless Debugging -> Pair device and run `tdev pair <code>` once (or `adb tcpip $wantedTcp` via USB).",
                        needsPairing = true
                    )
                }

                methodLabel = if (forced) "adb_no_wifi" else "adb_wireless"
                hunt = WirelessPortHunt(context, forced = forced)
                val discoveryDeadline = if (forced) timeoutMs.coerceAtMost(130_000L) else 15_000L
                activePort = hunt.fresh(emptySet(), discoveryDeadline)
                    ?: throw TimeoutException("Timed out searching for Wireless Debugging ADB port")
            }

            // If we found a wireless TLS port and TCP mode is desired, open classic port 5555 so future starts need no Wi-Fi
            if (enableTcpMode && activePort != wantedTcp && !AdbStarter.isPortListening(wantedTcp)) {
                val opened = runCatching {
                    AdbStarter.openTcpPort(context, wantedTcp, knownWirelessPort = activePort, hunt = hunt)
                }.getOrElse {
                    if (it is AdbPairingRequiredException || it is CancellationException) throw it
                    false
                }
                if (opened) {
                    val chosen = AdbStarter.firstListening(listOf(wantedTcp, activePort))
                    if (chosen != null) {
                        activePort = chosen
                        if (chosen == wantedTcp) methodLabel = "adb_tcp"
                    }
                }
            }

            if (activePort == wantedTcp) {
                hunt?.close()
                hunt = null
            }

            val token = AgentSettings.getOrCreateServerToken(context)
            TermuxAgentClient.expectToken(token)

            val usedPort = connectAndLaunch(context, hunt, activePort, token, wantedTcp)
            hunt?.close()
            hunt = null

            val service = TermuxAgentClient.awaitBinder(20_000L)
            AgentSettings.setLastStartMethod(context, methodLabel)
            AgentPermissions.grantSelfPermissions(context)

            return StartOutcome(
                ok = true,
                method = methodLabel,
                uid = runCatching { service.uid }.getOrDefault(2000),
                pid = runCatching { service.pid }.getOrDefault(-1),
                port = usedPort,
                message = "Started Termux Agent Server via $methodLabel on port $usedPort"
            )
        } catch (e: AdbPairingRequiredException) {
            return StartOutcome(
                ok = false,
                method = "adb_wireless",
                uid = -1,
                pid = -1,
                port = -1,
                message = "Device not paired with Termux Agent. Enable Wireless Debugging -> Pair device, then run `tdev pair <code>`.",
                needsPairing = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StartOutcome(
                ok = false,
                method = "adb",
                uid = -1,
                pid = -1,
                port = -1,
                message = e.message ?: e.javaClass.simpleName
            )
        } finally {
            hunt?.close()
        }
    }

    private suspend fun connectAndLaunch(
        context: Context,
        hunt: WirelessPortHunt?,
        initialPort: Int,
        token: String,
        wantedTcp: Int
    ): Int {
        var port = initialPort
        var searches = 0
        val failed = mutableSetOf<Int>()
        val progress = StartProgress()

        while (true) {
            val isWireless = AgentEnvironment.isTlsSupported() &&
                port != AgentEnvironment.getAdbTcpPort() &&
                port != wantedTcp

            if (isWireless && !AdbStarter.isPortListening(port) && searches < MAX_FRESH_PORT_SEARCHES) {
                failed += port
                searches++
                val fresh = AdbStarter.freshWirelessPort(context, failed, AdbStarter.FRESH_PORT_TIMEOUT_MS, hunt)
                if (fresh != null) {
                    port = fresh
                    continue
                }
            }

            try {
                return AdbStarter.startAdb(
                    context = context,
                    port = port,
                    token = token,
                    openTcpPort = false,
                    progress = progress,
                    connectAttempts = if (isWireless && searches < MAX_FRESH_PORT_SEARCHES) 2 else 5
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val decision = decideAfterConnectFailure(
                    wireless = isWireless,
                    commandSent = progress.starterSent,
                    failure = classifyConnectFailure(e),
                    searchesUsed = searches
                )
                if (decision != PortRetryDecision.SEARCH_AGAIN) throw e
                failed += port
                searches++
                val fresh = AdbStarter.freshWirelessPort(context, failed, AdbStarter.FRESH_PORT_TIMEOUT_MS, hunt)
                    ?: throw e
                port = fresh
                delay(200)
            }
        }
    }
}
