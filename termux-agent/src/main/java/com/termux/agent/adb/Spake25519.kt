package com.termux.agent.adb

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure-Kotlin implementation of BoringSSL's SPAKE2 over Ed25519 (`crypto/curve25519/spake25519.cc`),
 * avoiding any NDK / BoringSSL native dependency for ADB wireless debugging pairing.
 */
object Spake25519 {

    enum class Role { ALICE, BOB }

    // Prime field p = 2^255 - 19
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    // Prime order of the Ed25519 base-point subgroup
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    // Twisted Edwards constant d = -121665 / 121666 mod p
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)

    private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)

    // sqrt(-1) mod p = 2^((p-1)/4) mod p
    private val SQRT_MINUS_1: BigInteger = BigInteger.valueOf(2)
        .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    // Exponent (p - 5) / 8 used for square-root recovery
    private val EXP_P_MINUS_5_DIV_8: BigInteger = P.subtract(BigInteger.valueOf(5)).shiftRight(3)

    private val TWO: BigInteger = BigInteger.valueOf(2)

    data class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger
    ) {
        fun negate(): Point = Point(P.subtract(x).mod(P), y, z, P.subtract(t).mod(P))

        fun add(other: Point): Point {
            val a = y.subtract(x).multiply(other.y.subtract(other.x)).mod(P)
            val b = y.add(x).multiply(other.y.add(other.x)).mod(P)
            val c = t.multiply(TWO_D).multiply(other.t).mod(P)
            val d = TWO.multiply(z).multiply(other.z).mod(P)
            val e = b.subtract(a).mod(P)
            val f = d.subtract(c).mod(P)
            val g = d.add(c).mod(P)
            val h = b.add(a).mod(P)
            return Point(
                e.multiply(f).mod(P),
                g.multiply(h).mod(P),
                f.multiply(g).mod(P),
                e.multiply(h).mod(P)
            )
        }

        fun sub(other: Point): Point = add(other.negate())

        fun scalarMult(scalar: BigInteger): Point {
            var acc = IDENTITY
            var cur = this
            val bitLen = scalar.bitLength()
            for (i in 0 until bitLen) {
                if (scalar.testBit(i)) {
                    acc = acc.add(cur)
                }
                if (i + 1 < bitLen) {
                    cur = cur.add(cur)
                }
            }
            return acc
        }

        fun encode(): ByteArray {
            val zInv = z.modInverse(P)
            val px = x.multiply(zInv).mod(P)
            val py = y.multiply(zInv).mod(P)
            val out = ByteArray(32)
            val yBytes = py.toByteArray() // big-endian two's complement
            var outIdx = 0
            for (i in yBytes.size - 1 downTo 0) {
                if (outIdx < 32) {
                    out[outIdx++] = yBytes[i]
                }
            }
            if (px.testBit(0)) {
                out[31] = (out[31].toInt() or 0x80).toByte()
            }
            return out
        }
    }

    val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    // Standard Ed25519 base point B: y = 4/5 mod p, x positive (even)
    val BASE_POINT: Point = run {
        val by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val encoded = Point(BigInteger.ZERO, by, BigInteger.ONE, BigInteger.ZERO).encode()
        // Re-derive x from y with sign=0
        decodePoint(encoded) ?: error("Failed to initialize Ed25519 base point")
    }

    // BoringSSL spake25519.cc exact point M
    val M: Point = fromAffine(
        BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"),
        BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114")
    )

    // BoringSSL spake25519.cc exact point N
    val N: Point = fromAffine(
        BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"),
        BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000")
    )

    private fun fromAffine(x: BigInteger, y: BigInteger): Point =
        Point(x.mod(P), y.mod(P), BigInteger.ONE, x.multiply(y).mod(P))

    fun decodePoint(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val sign = (bytes[31].toInt() ushr 7) and 1
        val yCopy = bytes.copyOf()
        yCopy[31] = (yCopy[31].toInt() and 0x7F).toByte()
        val y = littleEndianToBigInt(yCopy).mod(P)

        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val v3 = v.pow(3).mod(P)
        val v7 = v3.multiply(v3).multiply(v).mod(P)
        val uv3 = u.multiply(v3).mod(P)
        val uv7 = u.multiply(v7).mod(P)

        var x = uv3.multiply(uv7.modPow(EXP_P_MINUS_5_DIV_8, P)).mod(P)
        val check = v.multiply(x).multiply(x).mod(P)
        if (check != u) {
            if (check == P.subtract(u).mod(P)) {
                x = x.multiply(SQRT_MINUS_1).mod(P)
            } else {
                return null
            }
        }
        if ((if (x.testBit(0)) 1 else 0) != sign) {
            x = P.subtract(x).mod(P)
        }
        val x2 = x.multiply(x).mod(P)
        val lhs = y2.subtract(x2).mod(P)
        val rhs = BigInteger.ONE.add(D.multiply(x2).multiply(y2)).mod(P)
        if (lhs != rhs) return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun littleEndianToBigInt(le: ByteArray): BigInteger {
        val be = ByteArray(le.size + 1)
        for (i in le.indices) {
            be[le.size - i] = le[i]
        }
        return BigInteger(1, be)
    }

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    private fun updateWithLengthPrefix(out: ByteArrayOutputStream, data: ByteArray) {
        var len = data.size.toLong()
        for (i in 0 until 8) {
            out.write((len and 0xFF).toInt())
            len = len ushr 8
        }
        out.write(data)
    }

    class Context(
        private val role: Role,
        private val myName: ByteArray,
        private val theirName: ByteArray,
        private val random: SecureRandom = SecureRandom()
    ) {
        private var state = 0 // 0=init, 1=msgGenerated, 2=keyGenerated
        private var privateKey: BigInteger = BigInteger.ZERO
        private var passwordScalar: BigInteger = BigInteger.ZERO
        private var passwordHash: ByteArray = ByteArray(0)
        private var myMsg: ByteArray = ByteArray(0)

        fun generateMessage(password: ByteArray): ByteArray {
            check(state == 0) { "SPAKE2 generateMessage already called" }

            val randBytes = ByteArray(64)
            random.nextBytes(randBytes)
            // Multiply by cofactor (8) so ephemeral scalar clears small-order components.
            privateKey = littleEndianToBigInt(randBytes).mod(L).shiftLeft(3)

            passwordHash = sha512(password)
            var pwScalar = littleEndianToBigInt(passwordHash).mod(L)
            // Match BoringSSL's spake25519 cofactor fix (adding 1L, 2L, 4L so low 3 bits are zero).
            if (pwScalar.testBit(0)) pwScalar = pwScalar.add(L)
            if (pwScalar.testBit(1)) pwScalar = pwScalar.add(L.shiftLeft(1))
            if (pwScalar.testBit(2)) pwScalar = pwScalar.add(L.shiftLeft(2))
            passwordScalar = pwScalar

            val pPoint = BASE_POINT.scalarMult(privateKey)
            val maskBase = if (role == Role.ALICE) M else N
            val mask = maskBase.scalarMult(passwordScalar)
            val pStar = pPoint.add(mask)
            myMsg = pStar.encode()
            state = 1
            return myMsg.copyOf()
        }

        fun processMessage(theirMsg: ByteArray): ByteArray? {
            check(state == 1) { "SPAKE2 processMessage called in invalid state $state" }
            val qStar = decodePoint(theirMsg) ?: return null

            val peerMaskBase = if (role == Role.ALICE) N else M
            val peerMask = peerMaskBase.scalarMult(passwordScalar)
            val qExt = qStar.sub(peerMask)
            val dhShared = qExt.scalarMult(privateKey).encode()

            val transcript = ByteArrayOutputStream()
            if (role == Role.ALICE) {
                updateWithLengthPrefix(transcript, myName)
                updateWithLengthPrefix(transcript, theirName)
                updateWithLengthPrefix(transcript, myMsg)
                updateWithLengthPrefix(transcript, theirMsg)
            } else {
                updateWithLengthPrefix(transcript, theirName)
                updateWithLengthPrefix(transcript, myName)
                updateWithLengthPrefix(transcript, theirMsg)
                updateWithLengthPrefix(transcript, myMsg)
            }
            updateWithLengthPrefix(transcript, dhShared)
            updateWithLengthPrefix(transcript, passwordHash)

            state = 2
            return sha512(transcript.toByteArray())
        }
    }
}
