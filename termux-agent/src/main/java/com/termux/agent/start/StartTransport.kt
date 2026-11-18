package com.termux.agent.start

object StartTransport {

    enum class PortSource(val label: String) {
        CLASSIC_PROPERTY("classic port property"),
        TLS_PROPERTY("service.adb.tls.port"),
        REMEMBERED("remembered from last start"),
        APP_SETTING("TCP port setting"),
        MDNS("mDNS")
    }

    data class PortCandidate(val port: Int, val source: PortSource)

    enum class RememberedPort {
        USABLE,
        NONE,
        OTHER_BOOT,
        UNKNOWN_BOOT
    }

    fun wifiRequired(tcpPort: Int, tcpMode: Boolean, forceWireless: Boolean = false): Boolean =
        !forceWireless && (tcpPort <= 0 || !tcpMode)

    fun classicPortInUse(
        tlsSupported: Boolean,
        tcpPort: Int,
        tcpMode: Boolean,
        portListening: Boolean
    ): Boolean = !tlsSupported || (tcpMode && tcpPort > 0 && portListening)

    fun rememberedPortVerdict(
        lastPort: Int,
        lastWasClassic: Boolean,
        lastBoot: Int,
        currentBoot: Int,
        appTcpPort: Int
    ): RememberedPort = when {
        lastPort <= 0 -> RememberedPort.NONE
        lastWasClassic || lastPort == appTcpPort -> RememberedPort.USABLE
        lastBoot <= 0 || currentBoot <= 0 -> RememberedPort.UNKNOWN_BOOT
        lastBoot != currentBoot -> RememberedPort.OTHER_BOOT
        else -> RememberedPort.USABLE
    }

    fun candidatePorts(
        configured: Int,
        tlsProperty: Int,
        remembered: Int?,
        appTcpPort: Int
    ): List<PortCandidate> = listOf(
        PortCandidate(configured, PortSource.CLASSIC_PROPERTY),
        PortCandidate(tlsProperty, PortSource.TLS_PROPERTY),
        PortCandidate(remembered ?: -1, PortSource.REMEMBERED),
        PortCandidate(appTcpPort, PortSource.APP_SETTING)
    ).filter { it.port > 0 }.distinctBy { it.port }
}
