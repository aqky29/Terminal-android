package com.termux.agent.start

import com.termux.agent.adb.AdbPairingRequiredException
import java.io.IOException
import java.net.SocketException
import javax.net.ssl.SSLException

enum class ConnectFailureKind {
    REFUSED_OR_CLOSED,
    TLS,
    PAIRING,
    OTHER
}

enum class PortRetryDecision {
    USE_AS_IS,
    SEARCH_AGAIN,
    GIVE_UP
}

const val MAX_FRESH_PORT_SEARCHES = 3

internal fun isPairingRejection(message: String?): Boolean =
    message != null &&
        (message.contains("CERTIFICATE_UNKNOWN", ignoreCase = true) ||
            message.contains("SSLV3_ALERT", ignoreCase = true))

internal fun classifyConnectFailure(e: Throwable): ConnectFailureKind = when {
    e is AdbPairingRequiredException ->
        if (isPairingRejection(e.message) || isPairingRejection(e.cause?.message)) {
            ConnectFailureKind.PAIRING
        } else {
            ConnectFailureKind.TLS
        }
    e is SSLException -> ConnectFailureKind.TLS
    e is SocketException || e is IOException -> ConnectFailureKind.REFUSED_OR_CLOSED
    else -> ConnectFailureKind.OTHER
}

internal fun decideAfterConnectFailure(
    wireless: Boolean,
    commandSent: Boolean,
    failure: ConnectFailureKind,
    searchesUsed: Int,
    maxSearches: Int = MAX_FRESH_PORT_SEARCHES
): PortRetryDecision = when {
    !wireless || commandSent -> PortRetryDecision.GIVE_UP
    failure == ConnectFailureKind.PAIRING || failure == ConnectFailureKind.OTHER ->
        PortRetryDecision.GIVE_UP
    searchesUsed >= maxSearches -> PortRetryDecision.GIVE_UP
    else -> PortRetryDecision.SEARCH_AGAIN
}
