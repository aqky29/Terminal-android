package com.termux.agent.adb

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

private const val TAG = "TermuxAgentAdbKey"

class AdbKey(
    private val context: Context,
    private val adbKeyStore: AdbKeyStore,
    name: String = "termux-agent"
) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "_termux_agent_adbkey_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE_IN_BYTES = 12
        private const val TAG_SIZE_IN_BYTES = 16

        private val PADDING = byteArrayOf(
            0x00, 0x01, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x00,
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
            0x04, 0x14
        )
    }

    private val encryptionKey: Key? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        getOrCreateEncryptionKey() ?: error("Failed to create AndroidKeyStore encryption key")
    } else {
        null
    }

    private val privateKey: RSAPrivateKey = getOrCreatePrivateKey()
    private val publicKey: RSAPublicKey = KeyFactory.getInstance("RSA")
        .generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey
    private val certificate: X509Certificate

    init {
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val x509Certificate = X509v3CertificateBuilder(
            X500Name("CN=00"),
            BigInteger.ONE,
            Date(0),
            Date(2461449600L * 1000L),
            Locale.ROOT,
            X500Name("CN=00"),
            SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        ).build(signer)
        certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(x509Certificate.encoded)) as X509Certificate
    }

    val adbPublicKey: ByteArray by lazy {
        publicKey.adbEncoded(name)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getOrCreateEncryptionKey(): Key? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore.getKey(ENCRYPTION_KEY_ALIAS, null) ?: run {
            val spec = KeyGenParameterSpec.Builder(
                ENCRYPTION_KEY_ALIAS,
                KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(spec)
            generator.generateKey()
        }
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray? {
        val key = encryptionKey ?: return plaintext
        if (plaintext.size > Int.MAX_VALUE - IV_SIZE_IN_BYTES - TAG_SIZE_IN_BYTES) return null
        val ciphertext = ByteArray(IV_SIZE_IN_BYTES + plaintext.size + TAG_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        cipher.doFinal(plaintext, 0, plaintext.size, ciphertext, IV_SIZE_IN_BYTES)
        System.arraycopy(cipher.iv, 0, ciphertext, 0, IV_SIZE_IN_BYTES)
        return ciphertext
    }

    private fun decrypt(stored: ByteArray, aad: ByteArray): ByteArray {
        val key = encryptionKey ?: return stored
        require(stored.size >= IV_SIZE_IN_BYTES + TAG_SIZE_IN_BYTES) { "Invalid encrypted ADB key" }
        val params = GCMParameterSpec(8 * TAG_SIZE_IN_BYTES, stored, 0, IV_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, params)
        cipher.updateAAD(aad)
        return cipher.doFinal(stored, IV_SIZE_IN_BYTES, stored.size - IV_SIZE_IN_BYTES)
    }

    private fun isUserUnlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val um = context.getSystemService(android.os.UserManager::class.java) ?: return true
        return um.isUserUnlocked
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        var loaded: RSAPrivateKey? = null
        val aad = ByteArray(16)
        "adbkey".toByteArray().copyInto(aad)

        val stored = adbKeyStore.get()
        if (stored != null) {
            try {
                val plaintext = decrypt(stored, aad)
                val keyFactory = KeyFactory.getInstance("RSA")
                loaded = keyFactory.generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateKey
            } catch (e: Exception) {
                if (!isUserUnlocked()) {
                    throw IllegalStateException("ADB key could not be decrypted while device is locked", e)
                }
                Log.w(TAG, "Failed to read stored ADB key, regenerating", e)
            }
        }
        if (loaded == null) {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val kp = kpg.generateKeyPair()
            loaded = kp.private as RSAPrivateKey
            val encoded = encrypt(loaded.encoded, aad)
            if (encoded != null) {
                adbKeyStore.put(encoded)
            }
        }
        return loaded
    }

    fun sign(data: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(data)
    }

    private val keyManager
        get() = object : X509ExtendedKeyManager() {
            private val alias = "key"

            override fun chooseClientAlias(
                keyTypes: Array<out String>,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? = if (keyTypes.any { it == "RSA" }) alias else null

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == this.alias) arrayOf(certificate) else null

            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == this.alias) privateKey else null

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }

    private val trustManager
        @RequiresApi(Build.VERSION_CODES.N)
        get() = object : X509ExtendedTrustManager() {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

    @get:RequiresApi(Build.VERSION_CODES.R)
    val sslContext: SSLContext by lazy {
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        ctx
    }
}

interface AdbKeyStore {
    fun put(bytes: ByteArray)
    fun get(): ByteArray?
}

class PreferenceAdbKeyStore(private val preferences: SharedPreferences) : AdbKeyStore {
    private val keyName = "adbkey"

    override fun put(bytes: ByteArray) {
        preferences.edit().putString(keyName, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    override fun get(): ByteArray? {
        val raw = preferences.getString(keyName, null) ?: return null
        return Base64.decode(raw, Base64.NO_WRAP)
    }
}

private const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
private const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
private const val RSA_PUBLIC_KEY_SIZE = 524

private fun BigInteger.toAdbEncodedWords(): IntArray {
    val encoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)
    var tmp = this
    for (i in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
        val qr = tmp.divideAndRemainder(r32)
        tmp = qr[0]
        encoded[i] = qr[1].toInt()
    }
    return encoded
}

private fun RSAPublicKey.adbEncoded(name: String): ByteArray {
    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSA_PUBLIC_KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncodedWords().forEach { buffer.putInt(it) }
    rr.toAdbEncodedWords().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())

    val base64Bytes = Base64.encode(buffer.array(), Base64.NO_WRAP)
    val nameBytes = " $name\u0000".toByteArray(Charsets.UTF_8)
    val out = ByteArray(base64Bytes.size + nameBytes.size)
    base64Bytes.copyInto(out)
    nameBytes.copyInto(out, base64Bytes.size)
    return out
}
