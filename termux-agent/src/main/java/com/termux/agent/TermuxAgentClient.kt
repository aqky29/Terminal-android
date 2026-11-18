package com.termux.agent

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class AgentExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val serverUid: Int
)

object TermuxAgentClient {

    private val serviceRef = AtomicReference<ITermuxAgentService?>(null)
    private val aliveFlow = MutableStateFlow(false)

    @Volatile
    private var expectedToken: String? = null

    @Volatile
    private var cachedUid: Int = -1

    @Volatile
    private var cachedPid: Int = -1

    fun expectToken(token: String) {
        expectedToken = token
    }

    fun consumeExpectedToken(token: String?): Boolean {
        val expected = expectedToken ?: return true
        if (token == expected) {
            expectedToken = null
            return true
        }
        return false
    }

    fun attachBinder(binder: IBinder): Boolean {
        if (!binder.pingBinder()) return false
        val service = ITermuxAgentService.Stub.asInterface(binder) ?: return false
        runCatching {
            binder.linkToDeath({
                serviceRef.compareAndSet(service, null)
                cachedUid = -1
                cachedPid = -1
                aliveFlow.value = false
            }, 0)
        }
        serviceRef.set(service)
        cachedUid = runCatching { service.uid }.getOrDefault(-1)
        cachedPid = runCatching { service.pid }.getOrDefault(-1)
        aliveFlow.value = true
        return true
    }

    fun isAlive(): Boolean {
        val svc = serviceRef.get() ?: return false
        val ok = runCatching { svc.asBinder().pingBinder() }.getOrDefault(false)
        if (!ok) {
            serviceRef.compareAndSet(svc, null)
            cachedUid = -1
            cachedPid = -1
            aliveFlow.value = false
        }
        return ok
    }

    fun getService(): ITermuxAgentService? = if (isAlive()) serviceRef.get() else null

    fun getUid(): Int {
        if (!isAlive()) return -1
        if (cachedUid >= 0) return cachedUid
        cachedUid = runCatching { serviceRef.get()?.uid ?: -1 }.getOrDefault(-1)
        return cachedUid
    }

    fun getPid(): Int {
        if (!isAlive()) return -1
        if (cachedPid > 0) return cachedPid
        cachedPid = runCatching { serviceRef.get()?.pid ?: -1 }.getOrDefault(-1)
        return cachedPid
    }

    fun getSeContext(): String? {
        val svc = getService() ?: return null
        return runCatching { svc.seContext }.getOrNull()
    }

    suspend fun awaitBinder(timeoutMs: Long = 20_000L): ITermuxAgentService {
        getService()?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            aliveFlow.first { it && getService() != null }
            getService()
        } ?: throw TimeoutException("Timed out waiting for Termux Agent Server binder (${timeoutMs}ms)")
    }

    fun execSync(command: String, cwd: String? = null, timeoutMs: Long = 30_000L): AgentExecResult {
        val svc = getService()
            ?: return AgentExecResult(1, "", "Termux Agent Server is not running (run `tdev up`)\n", 0L, -1)
        val start = System.currentTimeMillis()
        val uid = getUid()

        return runCatching {
            val stdoutPipe = ParcelFileDescriptor.createPipe()
            val stderrPipe = ParcelFileDescriptor.createPipe()
            val stdoutBuf = ByteArrayOutputStream()
            val stderrBuf = ByteArrayOutputStream()

            val tOut = drainPipe(stdoutPipe[0], stdoutBuf, 8 * 1024 * 1024)
            val tErr = drainPipe(stderrPipe[0], stderrBuf, 2 * 1024 * 1024)

            val exitCode = try {
                svc.execStreaming(command, cwd, timeoutMs, stdoutPipe[1], stderrPipe[1])
            } finally {
                runCatching { stdoutPipe[1].close() }
                runCatching { stderrPipe[1].close() }
            }
            tOut.join(2000)
            tErr.join(2000)

            AgentExecResult(
                exitCode = exitCode,
                stdout = stdoutBuf.toString("UTF-8"),
                stderr = stderrBuf.toString("UTF-8"),
                durationMs = System.currentTimeMillis() - start,
                serverUid = uid
            )
        }.getOrElse { err ->
            // Fallback to simple Bundle execution if pipe creation failed
            runCatching {
                val res = svc.execCommand(command, cwd, timeoutMs)
                AgentExecResult(
                    exitCode = res.getInt("exitCode", 1),
                    stdout = res.getString("stdout").orEmpty(),
                    stderr = res.getString("stderr").orEmpty(),
                    durationMs = res.getLong("durationMs", System.currentTimeMillis() - start),
                    serverUid = uid
                )
            }.getOrElse {
                AgentExecResult(1, "", err.message ?: "Binder execution failed", System.currentTimeMillis() - start, uid)
            }
        }
    }

    fun snapshot(): Bundle? {
        val svc = getService() ?: return null
        return runCatching { svc.deviceSnapshot }.getOrNull()
    }

    fun stopServer(): Boolean {
        val svc = getService() ?: return true
        val ok = runCatching { svc.exit() }.isSuccess
        serviceRef.set(null)
        cachedUid = -1
        cachedPid = -1
        aliveFlow.value = false
        return ok
    }

    private fun drainPipe(fd: ParcelFileDescriptor, out: ByteArrayOutputStream, maxBytes: Int): Thread =
        thread(isDaemon = true) {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    val remaining = maxBytes - total
                    if (remaining > 0) {
                        val toWrite = read.coerceAtMost(remaining)
                        out.write(buf, 0, toWrite)
                        total += toWrite
                    }
                }
            }
        }
}
