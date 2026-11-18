package com.termux.agent.adb

import android.os.Build
import android.util.Log
import com.termux.agent.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import com.termux.agent.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import com.termux.agent.adb.AdbProtocol.ADB_AUTH_TOKEN
import com.termux.agent.adb.AdbProtocol.A_AUTH
import com.termux.agent.adb.AdbProtocol.A_CLSE
import com.termux.agent.adb.AdbProtocol.A_CNXN
import com.termux.agent.adb.AdbProtocol.A_MAXDATA
import com.termux.agent.adb.AdbProtocol.A_OKAY
import com.termux.agent.adb.AdbProtocol.A_OPEN
import com.termux.agent.adb.AdbProtocol.A_STLS
import com.termux.agent.adb.AdbProtocol.A_STLS_VERSION
import com.termux.agent.adb.AdbProtocol.A_VERSION
import com.termux.agent.adb.AdbProtocol.A_WRTE
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "TermuxAgentAdbClient"

class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey
) : Closeable {

    private var socket: Socket? = null
    private var plainInputStream: DataInputStream? = null
    private var plainOutputStream: DataOutputStream? = null

    private var useTls = false
    private var tlsSocket: SSLSocket? = null
    private var tlsInputStream: DataInputStream? = null
    private var tlsOutputStream: DataOutputStream? = null

    private val inputStream: DataInputStream
        get() = (if (useTls) tlsInputStream else plainInputStream)
            ?: error("ADB stream is not connected")

    private val outputStream: DataOutputStream
        get() = (if (useTls) tlsOutputStream else plainOutputStream)
            ?: error("ADB stream is not connected")

    fun connect(connectTimeoutMs: Int = 5000, readTimeoutMs: Int = 15000) {
        val sock = Socket()
        socket = sock
        sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
        sock.soTimeout = readTimeoutMs
        sock.tcpNoDelay = true
        plainInputStream = DataInputStream(sock.getInputStream())
        plainOutputStream = DataOutputStream(sock.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        if (message.command == A_STLS) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                error("ADB TLS requires Android 11+")
            }
            write(A_STLS, A_STLS_VERSION, 0)

            val ssl = key.sslContext.socketFactory.createSocket(sock, host, port, true) as SSLSocket
            ssl.soTimeout = readTimeoutMs
            ssl.startHandshake()
            Log.d(TAG, "TLS handshake succeeded on $host:$port")

            tlsSocket = ssl
            tlsInputStream = DataInputStream(ssl.inputStream)
            tlsOutputStream = DataOutputStream(ssl.outputStream)
            useTls = true

            message = read()
        } else if (message.command == A_AUTH) {
            if (message.arg0 != ADB_AUTH_TOKEN) error("Expected ADB_AUTH_TOKEN, got ${message.arg0}")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != A_CNXN) error("Expected A_CNXN, got ${message.toStringShort()}")
    }

    fun command(cmd: String, listener: ((ByteArray) -> Unit)? = null) {
        val localId = 1
        write(A_OPEN, localId, 0, cmd)

        var message = read()
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    when (message.command) {
                        A_WRTE -> {
                            if (message.dataLength > 0 && message.data != null) {
                                listener?.invoke(message.data!!)
                            }
                            write(A_OKAY, localId, remoteId)
                        }
                        A_CLSE -> {
                            write(A_CLSE, localId, remoteId)
                            break
                        }
                        else -> error("Unexpected ADB stream message: ${message.toStringShort()}")
                    }
                }
            }
            A_CLSE -> {
                write(A_CLSE, localId, message.arg0)
            }
            else -> error("Unexpected A_OPEN response: ${message.toStringShort()}")
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) =
        write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) =
        write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
    }

    private fun read(): AdbMessage {
        val header = ByteArray(AdbMessage.HEADER_LENGTH)
        inputStream.readFully(header, 0, AdbMessage.HEADER_LENGTH)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        require(dataLength >= 0 && dataLength <= 1024 * 1024) { "Invalid ADB dataLength: $dataLength" }
        val data = if (dataLength > 0) {
            ByteArray(dataLength).also { inputStream.readFully(it, 0, dataLength) }
        } else {
            ByteArray(0)
        }
        val msg = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        msg.validateOrThrow()
        return msg
    }

    override fun close() {
        runCatching { plainInputStream?.close() }
        runCatching { plainOutputStream?.close() }
        runCatching { socket?.close() }
        if (useTls) {
            runCatching { tlsInputStream?.close() }
            runCatching { tlsOutputStream?.close() }
            runCatching { tlsSocket?.close() }
        }
    }
}
