package com.termux.agent.adb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

private const val TAG = "TermuxAgentAdbPair"

private const val CURRENT_KEY_HEADER_VERSION: Byte = 1
private const val MIN_SUPPORTED_KEY_HEADER_VERSION: Byte = 1
private const val MAX_SUPPORTED_KEY_HEADER_VERSION: Byte = 1
private const val MAX_PEER_INFO_SIZE = 8192
private const val MAX_PAYLOAD_SIZE = MAX_PEER_INFO_SIZE * 2

private const val EXPORTED_KEY_LABEL = "adb-label\u0000"
private const val EXPORTED_KEY_SIZE = 64
private const val PAIRING_PACKET_HEADER_SIZE = 6

private val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
private val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.UTF_8)
private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)

private class PeerInfo(
    val type: Byte,
    data: ByteArray
) {
    val data = ByteArray(MAX_PEER_INFO_SIZE - 1)

    init {
        data.copyInto(this.data, 0, 0, data.size.coerceAtMost(MAX_PEER_INFO_SIZE - 1))
    }

    enum class Type(val value: Byte) {
        ADB_RSA_PUB_KEY(0),
        ADB_DEVICE_GUID(1)
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.put(type)
        buffer.put(data)
    }

    companion object {
        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()
            val data = ByteArray(MAX_PEER_INFO_SIZE - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

private class PairingPacketHeader(
    val version: Byte,
    val type: Byte,
    val payload: Int
) {
    enum class Type(val value: Byte) {
        SPAKE2_MSG(0),
        PEER_INFO(1)
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.put(version)
        buffer.put(type)
        buffer.putInt(payload)
    }

    companion object {
        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int
            if (version < MIN_SUPPORTED_KEY_HEADER_VERSION || version > MAX_SUPPORTED_KEY_HEADER_VERSION) {
                Log.e(TAG, "PairingPacketHeader version mismatch: $version")
                return null
            }
            if (type != Type.SPAKE2_MSG.value && type != Type.PEER_INFO.value) {
                Log.e(TAG, "Unknown PairingPacketHeader type: $type")
                return null
            }
            if (payload <= 0 || payload > MAX_PAYLOAD_SIZE) {
                Log.e(TAG, "PairingPacketHeader invalid payload size: $payload")
                return null
            }
            return PairingPacketHeader(version, type, payload)
        }
    }
}

/**
 * Pure-Kotlin pairing cipher using BoringSSL-compatible [Spake25519], HKDF-SHA256, and AES-128-GCM.
 */
internal class PairingCipherSession(password: ByteArray) {
    private val spake2 = Spake25519.Context(
        Spake25519.Role.ALICE,
        CLIENT_NAME,
        SERVER_NAME
    )
    val localMsg: ByteArray = spake2.generateMessage(password)

    private var aesKey: SecretKeySpec? = null
    private var encSeq: Long = 0
    private var decSeq: Long = 0

    fun initCipher(theirMsg: ByteArray): Boolean {
        val keyMaterial = spake2.processMessage(theirMsg) ?: return false
        val aesKeyBytes = hkdfSha256(keyMaterial, HKDF_INFO, 16)
        aesKey = SecretKeySpec(aesKeyBytes, "AES")
        return true
    }

    fun encrypt(plaintext: ByteArray): ByteArray? {
        val key = aesKey ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = seqToNonce(encSeq)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val out = cipher.doFinal(plaintext)
        encSeq++
        return out
    }

    fun decrypt(ciphertext: ByteArray): ByteArray? {
        val key = aesKey ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val nonce = seqToNonce(decSeq)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            val out = cipher.doFinal(ciphertext)
            decSeq++
            out
        } catch (e: Exception) {
            Log.w(TAG, "Pairing AES-GCM decrypt failed", e)
            null
        }
    }

    private fun seqToNonce(seq: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).array()

    companion object {
        internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val hmac = Mac.getInstance("HmacSHA256")
            // RFC 5869: when salt is empty/null, salt is HashLen (32) zero bytes
            hmac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = hmac.doFinal(ikm)

            hmac.init(SecretKeySpec(prk, "HmacSHA256"))
            hmac.update(info)
            hmac.update(1.toByte())
            return hmac.doFinal().copyOf(length)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairCode: String,
    private val key: AdbKey
) : Closeable {

    private var socket: Socket? = null
    private var sslSocket: SSLSocket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    private var session: PairingCipherSession? = null

    private val peerInfo = PeerInfo(PeerInfo.Type.ADB_RSA_PUB_KEY.value, key.adbPublicKey)

    fun start(): Boolean {
        setupTlsConnection()
        if (!doExchangeMsgs()) return false
        return doExchangePeerInfo()
    }

    private fun setupTlsConnection() {
        val sock = Socket()
        socket = sock
        sock.connect(InetSocketAddress(host, port), 5000)
        sock.soTimeout = 15000
        sock.tcpNoDelay = true

        val ssl = key.sslContext.socketFactory.createSocket(sock, host, port, true) as SSLSocket
        ssl.soTimeout = 15000
        ssl.startHandshake()
        sslSocket = ssl

        inputStream = DataInputStream(ssl.inputStream)
        outputStream = DataOutputStream(ssl.outputStream)

        val pairCodeBytes = pairCode.toByteArray(Charsets.UTF_8)
        val keyMaterial = exportKeyingMaterial(ssl, EXPORTED_KEY_LABEL, EXPORTED_KEY_SIZE)
        val passwordBytes = ByteArray(pairCodeBytes.size + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

        session = PairingCipherSession(passwordBytes)
    }

    private fun exportKeyingMaterial(ssl: SSLSocket, label: String, length: Int): ByteArray {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/")
            }
        }
        val conscryptClass = Class.forName("com.android.org.conscrypt.Conscrypt")
        val method = conscryptClass.getDeclaredMethod(
            "exportKeyingMaterial",
            SSLSocket::class.java,
            String::class.java,
            ByteArray::class.java,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        return method.invoke(null, ssl, label, null, length) as ByteArray
    }

    private fun readHeader(): PairingPacketHeader? {
        val input = inputStream ?: return null
        val bytes = ByteArray(PAIRING_PACKET_HEADER_SIZE)
        input.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return PairingPacketHeader.readFrom(buffer)
    }

    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val output = outputStream ?: error("Pairing output stream closed")
        val buffer = ByteBuffer.allocate(PAIRING_PACKET_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)
        output.write(buffer.array())
        output.write(payload)
        output.flush()
    }

    private fun doExchangeMsgs(): Boolean {
        val s = session ?: return false
        val input = inputStream ?: return false
        val msg = s.localMsg

        writeHeader(
            PairingPacketHeader(CURRENT_KEY_HEADER_VERSION, PairingPacketHeader.Type.SPAKE2_MSG.value, msg.size),
            msg
        )

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG.value) return false

        val theirMessage = ByteArray(theirHeader.payload)
        input.readFully(theirMessage)
        return s.initCipher(theirMessage)
    }

    private fun doExchangePeerInfo(): Boolean {
        val s = session ?: return false
        val input = inputStream ?: return false

        val buf = ByteBuffer.allocate(MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buf)

        val outBuf = s.encrypt(buf.array()) ?: return false
        writeHeader(
            PairingPacketHeader(CURRENT_KEY_HEADER_VERSION, PairingPacketHeader.Type.PEER_INFO.value, outBuf.size),
            outBuf
        )

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO.value) return false

        val theirMessage = ByteArray(theirHeader.payload)
        input.readFully(theirMessage)

        val decrypted = s.decrypt(theirMessage) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != MAX_PEER_INFO_SIZE) {
            Log.e(TAG, "Unexpected PeerInfo size=${decrypted.size}")
            return false
        }
        val theirPeer = PeerInfo.readFrom(ByteBuffer.wrap(decrypted))
        Log.d(TAG, "Paired peer type=${theirPeer.type}")
        return true
    }

    override fun close() {
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
        runCatching { sslSocket?.close() }
        runCatching { socket?.close() }
    }
}
