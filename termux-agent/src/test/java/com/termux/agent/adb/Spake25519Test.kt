package com.termux.agent.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Spake25519Test {

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `M and N match BoringSSL spake25519 canonical encodings`() {
        assertEquals(
            "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e",
            Spake25519.M.encode().toHex()
        )
        assertEquals(
            "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778",
            Spake25519.N.encode().toHex()
        )
    }

    @Test
    fun `encode and decode roundtrips base and blinding points`() {
        for (pt in listOf(Spake25519.BASE_POINT, Spake25519.M, Spake25519.N)) {
            val encoded = pt.encode()
            val decoded = Spake25519.decodePoint(encoded)
            assertNotNull(decoded)
            assertArrayEquals(encoded, decoded!!.encode())
        }
        assertNull(Spake25519.decodePoint(ByteArray(31)))
    }

    @Test
    fun `base point subgroup order annihilates point`() {
        val order = BigInteger.ONE.shiftLeft(252)
            .add(BigInteger("27742317777372353535851937790883648493"))
        val zero = Spake25519.BASE_POINT.scalarMult(order)
        assertArrayEquals(Spake25519.IDENTITY.encode(), zero.encode())
    }

    @Test
    fun `alice and bob derive identical keys on matching password and differ on wrong password`() {
        val clientName = "adb pair client\u0000".toByteArray()
        val serverName = "adb pair server\u0000".toByteArray()
        val password = "123456secretkeymaterial".toByteArray()

        val alice = Spake25519.Context(Spake25519.Role.ALICE, clientName, serverName, SecureRandom())
        val bob = Spake25519.Context(Spake25519.Role.BOB, serverName, clientName, SecureRandom())

        val msgA = alice.generateMessage(password)
        val msgB = bob.generateMessage(password)

        val keyA = alice.processMessage(msgB)
        val keyB = bob.processMessage(msgA)

        assertNotNull(keyA)
        assertNotNull(keyB)
        assertEquals(64, keyA!!.size)
        assertArrayEquals(keyA, keyB)

        // Mismatched password
        val aliceBad = Spake25519.Context(Spake25519.Role.ALICE, clientName, serverName, SecureRandom())
        val bobBad = Spake25519.Context(Spake25519.Role.BOB, serverName, clientName, SecureRandom())
        val msgBadA = aliceBad.generateMessage(password)
        val msgBadB = bobBad.generateMessage("wrong".toByteArray())
        val badKeyA = aliceBad.processMessage(msgBadB)
        val badKeyB = bobBad.processMessage(msgBadA)
        assertNotNull(badKeyA)
        assertNotNull(badKeyB)
        assertFalse(badKeyA!!.contentEquals(badKeyB!!))
    }

    @Test
    fun `pairing cipher session roundtrips peer info with HKDF and AES-128-GCM`() {
        val clientName = "adb pair client\u0000".toByteArray()
        val serverName = "adb pair server\u0000".toByteArray()
        val info = "adb pairing_auth aes-128-gcm key".toByteArray()
        val password = "654321pairingkey".toByteArray()

        val bob = Spake25519.Context(Spake25519.Role.BOB, serverName, clientName, SecureRandom())
        val bobMsg = bob.generateMessage(password)

        val session = PairingCipherSession(password)
        assertTrue(session.initCipher(bobMsg))

        val bobKeyMaterial = bob.processMessage(session.localMsg)!!
        val bobAesKey = SecretKeySpec(PairingCipherSession.hkdfSha256(bobKeyMaterial, info, 16), "AES")

        val payload = "termux-agent-pubkey".toByteArray()
        val encrypted = session.encrypt(payload)!!

        val decCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decCipher.init(Cipher.DECRYPT_MODE, bobAesKey, GCMParameterSpec(128, ByteArray(12)))
        assertArrayEquals(payload, decCipher.doFinal(encrypted))
    }
}
