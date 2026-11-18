package com.termux.agent.server

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.termux.agent.ITermuxAgentService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val TAG = "TermuxAgentServer"
private const val PROCESS_NAME = "termux_agent_server"

/**
 * Privileged standalone daemon executed under `/system/bin/app_process` with UID 0, 1000, or 2000.
 * Exposes [ITermuxAgentService] back to the Termux app via [ContentProvider.call] Binder handoff.
 */
class TermuxAgentServer(
    private val packageName: String,
    private val authority: String,
    private val launchToken: String,
    private val allowedAppId: Int
) : ITermuxAgentService.Stub() {

    private val startedAtMs = SystemClock.elapsedRealtime()
    private val watchingReconnect = AtomicBoolean(false)

    @Volatile
    private var lastDeliveredPid = -1

    private fun enforceCaller() {
        val callingUid = Binder.getCallingUid()
        if (callingUid == 0 || callingUid == Process.myUid()) return
        val callingAppId = callingUid % 100000
        if (allowedAppId > 0 && callingAppId == allowedAppId) return
        val resolvedUid = resolvePackageUid(packageName)
        if (resolvedUid > 0 && callingAppId == (resolvedUid % 100000)) return
        throw SecurityException("Unauthorized caller uid=$callingUid")
    }

    override fun getUid(): Int {
        enforceCaller()
        return Process.myUid()
    }

    override fun getPid(): Int {
        enforceCaller()
        return Process.myPid()
    }

    override fun getSeContext(): String {
        enforceCaller()
        return readFileTrimmed("/proc/self/attr/current") ?: "unknown"
    }

    override fun execCommand(command: String, cwd: String?, timeoutMs: Long): Bundle {
        enforceCaller()
        val start = SystemClock.elapsedRealtime()
        val workingDir = resolveWorkingDir(cwd)
        val proc = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workingDir)
            .start()

        val stdoutBuf = ByteArrayOutputStream()
        val stderrBuf = ByteArrayOutputStream()
        val tOut = pumpBounded(proc.inputStream, stdoutBuf, 512 * 1024)
        val tErr = pumpBounded(proc.errorStream, stderrBuf, 256 * 1024)

        val effectiveTimeout = if (timeoutMs > 0) timeoutMs else 30_000L
        val finished = proc.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)
        val timedOut = !finished
        if (timedOut) {
            proc.destroyForcibly()
            proc.waitFor(1000, TimeUnit.MILLISECONDS)
        }
        tOut.join(2000)
        tErr.join(2000)

        val exitCode = if (timedOut) 124 else runCatching { proc.exitValue() }.getOrDefault(1)
        return Bundle().apply {
            putInt("exitCode", exitCode)
            putBoolean("timedOut", timedOut)
            putString("stdout", stdoutBuf.toString("UTF-8"))
            putString("stderr", stderrBuf.toString("UTF-8"))
            putLong("durationMs", SystemClock.elapsedRealtime() - start)
        }
    }

    override fun execStreaming(
        command: String,
        cwd: String?,
        timeoutMs: Long,
        stdoutFd: ParcelFileDescriptor?,
        stderrFd: ParcelFileDescriptor?
    ): Int {
        enforceCaller()
        val workingDir = resolveWorkingDir(cwd)
        val outStream = stdoutFd?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
        val errStream = stderrFd?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }

        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(workingDir)
                .start()

            val tOut = pumpStream(proc.inputStream, outStream)
            val tErr = pumpStream(proc.errorStream, errStream)

            val effectiveTimeout = if (timeoutMs > 0) timeoutMs else 60_000L
            val finished = proc.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                proc.waitFor(1000, TimeUnit.MILLISECONDS)
            }
            tOut.join(2000)
            tErr.join(2000)
            if (!finished) 124 else runCatching { proc.exitValue() }.getOrDefault(1)
        } finally {
            runCatching { outStream?.close() }
            runCatching { errStream?.close() }
        }
    }

    override fun getDeviceSnapshot(): Bundle {
        enforceCaller()
        val bundle = Bundle()
        bundle.putInt("serverUid", Process.myUid())
        bundle.putInt("serverPid", Process.myPid())
        bundle.putString("seContext", readFileTrimmed("/proc/self/attr/current") ?: "unknown")
        bundle.putLong("serverUptimeMs", SystemClock.elapsedRealtime() - startedAtMs)

        bundle.putString("dumpsysBattery", runQuickShell("dumpsys battery"))
        bundle.putString("topActivity", runQuickShell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -n 5"))
        bundle.putString("meminfo", readFileTrimmed("/proc/meminfo"))
        bundle.putString("loadavg", readFileTrimmed("/proc/loadavg"))
        bundle.putString("adbTcpPort", getSystemProperty("service.adb.tcp.port", ""))
        bundle.putString("adbTlsPort", getSystemProperty("service.adb.tls.port", ""))

        runCatching {
            val dataStat = StatFs("/data")
            bundle.putLong("dataTotalBytes", dataStat.totalBytes)
            bundle.putLong("dataAvailableBytes", dataStat.availableBytes)
        }
        runCatching {
            val sdStat = StatFs("/sdcard")
            bundle.putLong("sdcardTotalBytes", sdStat.totalBytes)
            bundle.putLong("sdcardAvailableBytes", sdStat.availableBytes)
        }
        return bundle
    }

    override fun exit() {
        enforceCaller()
        Log.i(TAG, "Shutting down TermuxAgentServer on client request")
        thread(isDaemon = true) {
            Thread.sleep(50)
            exitProcess(0)
        }
    }

    private fun runQuickShell(cmd: String): String = runCatching {
        val proc = ProcessBuilder("/system/bin/sh", "-c", cmd).start()
        val out = BufferedReader(InputStreamReader(proc.inputStream)).readText()
        if (!proc.waitFor(3000, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
        }
        out.trim()
    }.getOrDefault("")

    private fun resolveWorkingDir(cwd: String?): File {
        if (!cwd.isNullOrBlank()) {
            val f = File(cwd)
            if (f.isDirectory && f.canRead()) return f
        }
        val tmp = File("/data/local/tmp")
        return if (tmp.isDirectory) tmp else File("/")
    }

    private fun pumpStream(input: InputStream, output: OutputStream?): Thread =
        thread(isDaemon = true) {
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    output?.write(buf, 0, read)
                    output?.flush()
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { input.close() }
                runCatching { output?.flush() }
                runCatching { output?.close() }
            }
        }

    private fun pumpBounded(input: InputStream, output: ByteArrayOutputStream, maxBytes: Int): Thread =
        thread(isDaemon = true) {
            try {
                val buf = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    val remaining = maxBytes - total
                    if (remaining > 0) {
                        val toWrite = read.coerceAtMost(remaining)
                        output.write(buf, 0, toWrite)
                        total += toWrite
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { input.close() }
            }
        }

    internal fun deliverBinderWithRetries() {
        for (attempt in 1..15) {
            if (sendBinderOnce()) return
            Thread.sleep(200)
        }
        Log.w(TAG, "Could not deliver binder to $authority after initial retries")
    }

    @SuppressLint("PrivateApi")
    private fun sendBinderOnce(): Boolean {
        val am = getActivityManager() ?: return false
        whitelistTemp(packageName)

        var provider: Any? = null
        try {
            val holder = getContentProviderExternal(am, authority) ?: return false
            provider = holder.javaClass.getField("provider").get(holder) ?: return false

            val extras = Bundle().apply {
                putBinder("agent_binder", this@TermuxAgentServer)
                putString("token", launchToken)
                putInt("uid", Process.myUid())
                putInt("pid", Process.myPid())
            }
            val reply = callProvider(provider, authority, "sendAgentBinder", extras) ?: return false
            val appCallback = reply.getBinder("app_callback")
            val appPid = reply.getInt("app_pid", -1)
            if (appPid > 0) lastDeliveredPid = appPid

            appCallback?.let { cb ->
                runCatching {
                    cb.linkToDeath({
                        Log.i(TAG, "Termux app died; waiting for restart to re-deliver binder")
                        startReconnectWatcher()
                    }, 0)
                }
            }
            Log.i(TAG, "Delivered TermuxAgentServer binder (uid=${Process.myUid()}) to $authority pid=$appPid")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "sendBinderOnce failed: ${e.message}")
            return false
        } finally {
            if (provider != null) {
                removeContentProviderExternal(am, authority)
            }
        }
    }

    private fun startReconnectWatcher() {
        if (!watchingReconnect.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "AgentReconnectWatcher") {
            try {
                var elapsedMs = 0L
                while (true) {
                    val delayMs = if (elapsedMs < 30_000L) 1500L else 5000L
                    Thread.sleep(delayMs)
                    elapsedMs += delayMs

                    val pid = findRunningAppPid(packageName)
                    if (pid > 0 && pid != lastDeliveredPid) {
                        if (sendBinderOnce()) {
                            return@thread
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                watchingReconnect.set(false)
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun findRunningAppPid(pkg: String): Int = runCatching {
        val am = getActivityManager() ?: return -1
        val list = am.javaClass.getMethod("getRunningAppProcesses").invoke(am) as? List<*> ?: return -1
        for (item in list) {
            if (item == null) continue
            val processName = item.javaClass.getField("processName").get(item) as? String
            val pid = item.javaClass.getField("pid").getInt(item)
            if (processName == pkg) return pid
        }
        -1
    }.getOrDefault(-1)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val uid = Process.myUid()
            if (uid != 0 && uid != 1000 && uid != 2000) {
                System.err.println("fatal: TermuxAgentServer must run as uid 0, 1000, or 2000 (got $uid)")
                exitProcess(6)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            }
            setProcessName(PROCESS_NAME)
            killExistingServers()

            var pkg = "com.termux"
            var authority = "com.termux.agent"
            var token = ""
            for (arg in args) {
                when {
                    arg.startsWith("--pkg=") -> pkg = arg.substringAfter("--pkg=")
                    arg.startsWith("--authority=") -> authority = arg.substringAfter("--authority=")
                    arg.startsWith("--token=") -> token = arg.substringAfter("--token=")
                }
            }

            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }

            waitForSystemService("package")
            waitForSystemService("activity")

            val pkgUid = resolvePackageUid(pkg)
            val appId = if (pkgUid > 0) pkgUid % 100000 else -1
            val server = TermuxAgentServer(pkg, authority, token, appId)

            thread(isDaemon = true, name = "AgentBinderDelivery") {
                server.deliverBinderWithRetries()
            }

            Looper.loop()
            exitProcess(0)
        }

        @SuppressLint("PrivateApi")
        private fun setProcessName(name: String) {
            runCatching {
                val ddm = Class.forName("android.ddm.DdmHandleAppName")
                ddm.getMethod("setAppName", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(null, name, 0)
            }
        }

        private fun killExistingServers() {
            val myPid = Process.myPid()
            val procDir = File("/proc")
            val entries = procDir.listFiles() ?: return
            for (entry in entries) {
                val pid = entry.name.toIntOrNull() ?: continue
                if (pid == myPid) continue
                val cmdline = runCatching { File(entry, "cmdline").readBytes() }.getOrNull() ?: continue
                val firstArg = cmdline.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
                if (firstArg == PROCESS_NAME) {
                    runCatching { Process.killProcess(pid) }
                }
            }
        }

        @SuppressLint("PrivateApi")
        private fun waitForSystemService(name: String) {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            repeat(30) {
                if (getService.invoke(null, name) != null) return
                Thread.sleep(500)
            }
        }

        @SuppressLint("PrivateApi")
        private fun getActivityManager(): Any? = runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "activity") as? IBinder
                ?: return null
            try {
                Class.forName("android.app.IActivityManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)
            } catch (_: Throwable) {
                Class.forName("android.app.ActivityManagerNative")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)
            }
        }.getOrNull()

        @SuppressLint("PrivateApi")
        private fun resolvePackageUid(pkg: String): Int = runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "package") as? IBinder
                ?: return -1
            val pm = Class.forName("android.content.pm.IPackageManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return -1
            val cls = pm.javaClass
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    cls.getMethod("getPackageUid", String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(pm, pkg, 0L, 0) as Int
                }.getOrNull()?.let { return it }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching {
                    cls.getMethod("getPackageUid", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(pm, pkg, 0, 0) as Int
                }.getOrNull()?.let { return it }
            }
            cls.getMethod("getPackageUid", String::class.java, Int::class.javaPrimitiveType)
                .invoke(pm, pkg, 0) as Int
        }.getOrDefault(-1)

        @SuppressLint("PrivateApi")
        private fun whitelistTemp(pkg: String) {
            runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
                val sm = Class.forName("android.os.ServiceManager")
                val binder = sm.getMethod("getService", String::class.java).invoke(null, "deviceidle") as? IBinder
                    ?: return
                val dic = Class.forName("android.os.IDeviceIdleController\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder) ?: return
                runCatching {
                    dic.javaClass.getMethod(
                        "addPowerSaveTempWhitelistApp",
                        String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, String::class.java
                    ).invoke(dic, pkg, 30_000L, 0, 316, "shell")
                }.onFailure {
                    dic.javaClass.getMethod(
                        "addPowerSaveTempWhitelistApp",
                        String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
                    ).invoke(dic, pkg, 30_000L, 0, "shell")
                }
            }
        }

        private fun getContentProviderExternal(am: Any, authority: String): Any? {
            val cls = am.javaClass
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cls.getMethod(
                    "getContentProviderExternal",
                    String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java
                ).invoke(am, authority, 0, null, authority)
            } else {
                cls.getMethod(
                    "getContentProviderExternal",
                    String::class.java, Int::class.javaPrimitiveType, IBinder::class.java
                ).invoke(am, authority, 0, null)
            }
        }

        private fun removeContentProviderExternal(am: Any, authority: String) {
            runCatching {
                am.javaClass.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
                    .invoke(am, authority, null)
            }
        }

        @SuppressLint("PrivateApi")
        private fun callProvider(provider: Any, authority: String, method: String, extras: Bundle): Bundle? {
            val candidates = if (Process.myUid() == 2000) listOf<String?>(null, "com.android.shell") else listOf<String?>(null)
            var lastError: Throwable? = null
            for (callingPkg in candidates) {
                try {
                    return invokeProviderCall(provider, callingPkg, authority, method, extras)
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            if (lastError != null) throw lastError
            return null
        }

        @SuppressLint("PrivateApi")
        private fun invokeProviderCall(
            provider: Any,
            callingPkg: String?,
            authority: String,
            method: String,
            extras: Bundle
        ): Bundle? {
            val cls = provider.javaClass
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    val attr = AttributionSource.Builder(Os.getuid())
                        .apply { if (callingPkg != null) setPackageName(callingPkg) }
                        .build()
                    cls.getMethod(
                        "call",
                        AttributionSource::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java
                    ).invoke(provider, attr, authority, method, null, extras) as? Bundle
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    cls.getMethod(
                        "call",
                        String::class.java, String::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java
                    ).invoke(provider, callingPkg, null, authority, method, null, extras) as? Bundle
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    cls.getMethod(
                        "call",
                        String::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java
                    ).invoke(provider, callingPkg, authority, method, null, extras) as? Bundle
                }
                else -> {
                    cls.getMethod(
                        "call",
                        String::class.java, String::class.java, String::class.java, Bundle::class.java
                    ).invoke(provider, callingPkg, method, null, extras) as? Bundle
                }
            }
        }

        private fun readFileTrimmed(path: String): String? = runCatching {
            File(path).readText().trim().trim('\u0000')
        }.getOrNull()

        @SuppressLint("PrivateApi")
        private fun getSystemProperty(key: String, defaultValue: String): String = runCatching {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("get", String::class.java, String::class.java).invoke(null, key, defaultValue) as? String
                ?: defaultValue
        }.getOrDefault(defaultValue)
    }
}
