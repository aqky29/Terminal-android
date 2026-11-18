package com.termux.agent.adb

import com.termux.agent.adb.AdbProtocol.A_AUTH
import com.termux.agent.adb.AdbProtocol.A_CLSE
import com.termux.agent.adb.AdbProtocol.A_CNXN
import com.termux.agent.adb.AdbProtocol.A_OKAY
import com.termux.agent.adb.AdbProtocol.A_OPEN
import com.termux.agent.adb.AdbProtocol.A_STLS
import com.termux.agent.adb.AdbProtocol.A_SYNC
import com.termux.agent.adb.AdbProtocol.A_WRTE
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataCrc32: Int,
    val magic: Int,
    val data: ByteArray?
) {

    constructor(command: Int, arg0: Int, arg1: Int, data: String) : this(
        command,
        arg0,
        arg1,
        "$data\u0000".toByteArray(Charsets.UTF_8)
    )

    constructor(command: Int, arg0: Int, arg1: Int, data: ByteArray?) : this(
        command,
        arg0,
        arg1,
        data?.size ?: 0,
        crc32(data),
        (command.toLong() xor 0xFFFFFFFFL).toInt(),
        data
    )

    fun validate(): Boolean {
        if (command != (magic xor -0x1)) return false
        if (dataLength != 0 && crc32(data) != dataCrc32) return false
        return true
    }

    fun validateOrThrow() {
        if (!validate()) throw IllegalArgumentException("Bad ADB message: ${toStringShort()}")
    }

    fun toByteArray(): ByteArray {
        val length = HEADER_LENGTH + (data?.size ?: 0)
        return ByteBuffer.allocate(length).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(dataLength)
            putInt(dataCrc32)
            putInt(magic)
            if (data != null) put(data)
        }.array()
    }

    fun toStringShort(): String {
        val commandName = when (command) {
            A_SYNC -> "A_SYNC"
            A_CNXN -> "A_CNXN"
            A_AUTH -> "A_AUTH"
            A_OPEN -> "A_OPEN"
            A_OKAY -> "A_OKAY"
            A_CLSE -> "A_CLSE"
            A_WRTE -> "A_WRTE"
            A_STLS -> "A_STLS"
            else -> command.toString()
        }
        return "command=$commandName, arg0=$arg0, arg1=$arg1, dataLength=$dataLength"
    }

    companion object {
        const val HEADER_LENGTH = 24

        private fun crc32(data: ByteArray?): Int {
            if (data == null) return 0
            var res = 0
            for (b in data) {
                res += (b.toInt() and 0xFF)
            }
            return res
        }
    }
}
