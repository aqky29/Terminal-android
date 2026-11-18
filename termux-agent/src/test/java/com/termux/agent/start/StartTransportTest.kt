package com.termux.agent.start

import com.termux.agent.adb.AdbPairingRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

class StartTransportTest {

    @Test
    fun `wifiRequired respects tcpMode and forceWireless`() {
        assertFalse(StartTransport.wifiRequired(tcpPort = 5555, tcpMode = true, forceWireless = false))
        assertTrue(StartTransport.wifiRequired(tcpPort = -1, tcpMode = true, forceWireless = false))
        assertTrue(StartTransport.wifiRequired(tcpPort = 5555, tcpMode = false, forceWireless = false))
        assertFalse(StartTransport.wifiRequired(tcpPort = -1, tcpMode = false, forceWireless = true))
    }

    @Test
    fun `rememberedPortVerdict rejects wireless port from prior boot`() {
        assertEquals(
            StartTransport.RememberedPort.OTHER_BOOT,
            StartTransport.rememberedPortVerdict(
                lastPort = 36371,
                lastWasClassic = false,
                lastBoot = 10,
                currentBoot = 11,
                appTcpPort = 5555
            )
        )
        assertEquals(
            StartTransport.RememberedPort.USABLE,
            StartTransport.rememberedPortVerdict(
                lastPort = 36371,
                lastWasClassic = false,
                lastBoot = 11,
                currentBoot = 11,
                appTcpPort = 5555
            )
        )
        assertEquals(
            StartTransport.RememberedPort.USABLE,
            StartTransport.rememberedPortVerdict(
                lastPort = 5555,
                lastWasClassic = true,
                lastBoot = 10,
                currentBoot = 11,
                appTcpPort = 5555
            )
        )
    }

    @Test
    fun `candidatePorts orders and deduplicates valid ports`() {
        val list = StartTransport.candidatePorts(
            configured = 5555,
            tlsProperty = 41234,
            remembered = 41234,
            appTcpPort = 5555
        )
        assertEquals(2, list.size)
        assertEquals(5555, list[0].port)
        assertEquals(StartTransport.PortSource.CLASSIC_PROPERTY, list[0].source)
        assertEquals(41234, list[1].port)
        assertEquals(StartTransport.PortSource.TLS_PROPERTY, list[1].source)
    }

    @Test
    fun `decideAfterConnectFailure gives up on pairing rejection or sent command`() {
        assertEquals(
            PortRetryDecision.GIVE_UP,
            decideAfterConnectFailure(
                wireless = true,
                commandSent = false,
                failure = classifyConnectFailure(AdbPairingRequiredException("CERTIFICATE_UNKNOWN", null)),
                searchesUsed = 0
            )
        )
        assertEquals(
            PortRetryDecision.GIVE_UP,
            decideAfterConnectFailure(
                wireless = true,
                commandSent = true,
                failure = classifyConnectFailure(ConnectException("refused")),
                searchesUsed = 0
            )
        )
        assertEquals(
            PortRetryDecision.SEARCH_AGAIN,
            decideAfterConnectFailure(
                wireless = true,
                commandSent = false,
                failure = classifyConnectFailure(ConnectException("refused")),
                searchesUsed = 0
            )
        )
    }

    @Test
    fun `server launch shell command avoids self-killing pkill -f`() {
        val cmd = AdbStarter.buildServerLaunchShellCommand(
            apkPath = "/data/app/com.termux/base.apk",
            packageName = "com.termux",
            authority = "com.termux.agent",
            token = "tok123"
        )
        assertFalse(cmd.contains("pkill"))
        assertTrue(cmd.contains("--nice-name=termux_agent_server"))
        assertTrue(cmd.contains("com.termux.agent.server.TermuxAgentServer"))
        assertTrue(cmd.contains("--token='tok123'"))
    }
}
