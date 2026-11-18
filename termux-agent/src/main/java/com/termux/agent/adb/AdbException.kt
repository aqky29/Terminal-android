package com.termux.agent.adb

class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AdbKeyException(cause: Throwable) : Exception("Failed to load or create ADB key", cause)

class AdbInvalidPairingCodeException : Exception("Invalid wireless debugging pairing code")

class AdbPairingRequiredException(message: String?, cause: Throwable?) : Exception(message, cause)
